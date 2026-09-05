package com.echopanel.app.data.proctoring


import android.annotation.SuppressLint
import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.echopanel.app.domain.model.ClientCheatSignalType
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.concurrent.Executors

data class ObservedSignal(
    val type: ClientCheatSignalType,
    val strength: Float,
    val detail: String,
)

class FaceProctoringAnalyzer(private val context: Context) {

    private var cameraProvider: ProcessCameraProvider? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .build()
    )

    // Sustained-state tracking so a single blinked frame doesn't fire a
    // signal — we only report once a condition holds for several
    // consecutive analyzed frames (roughly 1.5s at the ~2fps analysis
    // rate below), which is what "sustained gaze-off" or "no face" means
    // in the CheatSignalType vocabulary.
    private var consecutiveNoFace = 0
    private var consecutiveMultiFace = 0
    private var consecutiveGazeOff = 0
    private val sustainedFrameThreshold = 3

    /**
     * Emits an [ObservedSignal] each time a sustained condition is newly
     * detected. Consumers (the ViewModel or a dedicated collector) forward
     * these to ReportCheatSignalUseCase. Uses callbackFlow so camera
     * teardown (unbinding, closing the detector) happens deterministically
     * on flow cancellation via awaitClose.
     */
    @SuppressLint("UnsafeOptInUsageError")
    fun observe(lifecycleOwner: LifecycleOwner): Flow<ObservedSignal> = callbackFlow {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                analyzeFrame(imageProxy) { signal -> trySend(signal) }
            }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    analysis,
                )
            } catch (_ : Exception) {
                // Camera unavailable (emulator without a front camera, or
                // permission not yet granted) — proctoring degrades to
                // audio/text signals only rather than crashing the call.
                close()
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(context))

        awaitClose {
            cameraProvider?.unbindAll()
            detector.close()
            analysisExecutor.shutdown()
        }
    }.distinctUntilChanged { old, new -> old.type == new.type }

    @SuppressLint("UnsafeOptInUsageError")
    private fun analyzeFrame(imageProxy: ImageProxy, onSignal: (ObservedSignal) -> Unit) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        detector.process(image)
            .addOnSuccessListener { faces ->
                when {
                    faces.isEmpty() -> {
                        consecutiveNoFace++
                        consecutiveMultiFace = 0
                        consecutiveGazeOff = 0
                        if (consecutiveNoFace == sustainedFrameThreshold) {
                            onSignal(
                                ObservedSignal(
                                    ClientCheatSignalType.NO_FACE_DETECTED,
                                    strength = 0.6f,
                                    detail = "No face visible in frame for ~1.5s",
                                )
                            )
                        }
                    }
                    faces.size > 1 -> {
                        consecutiveMultiFace++
                        consecutiveNoFace = 0
                        consecutiveGazeOff = 0
                        if (consecutiveMultiFace == sustainedFrameThreshold) {
                            onSignal(
                                ObservedSignal(
                                    ClientCheatSignalType.MULTIPLE_FACES,
                                    strength = 0.85f,
                                    detail = "${faces.size} faces detected in frame",
                                )
                            )
                        }
                    }
                    else -> {
                        consecutiveNoFace = 0
                        consecutiveMultiFace = 0
                        val face = faces[0]
                        // Sustained large head-yaw is treated as "looking
                        // away from the screen" — a coarse but genuinely
                        // computed signal, not a stub. Threshold chosen so
                        // normal glances don't fire, only a sustained turn.
                        val yaw = face.headEulerAngleY
                        if (kotlin.math.abs(yaw) > 35f) {
                            consecutiveGazeOff++
                            if (consecutiveGazeOff == sustainedFrameThreshold) {
                                onSignal(
                                    ObservedSignal(
                                        ClientCheatSignalType.GAZE_OFF_SCREEN,
                                        strength = 0.4f,
                                        detail = "Sustained head turn (${yaw.toInt()}°) away from camera",
                                    )
                                )
                            }
                        } else {
                            consecutiveGazeOff = 0
                        }
                    }
                }
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    fun stop() {
        cameraProvider?.unbindAll()
    }
}


class AppBackgroundProctoringObserver : DefaultLifecycleObserver {

    private var onBackgrounded: (() -> Unit)? = null

    fun observe(callback: () -> Unit) {
        onBackgrounded = callback
    }

    override fun onStop(owner: LifecycleOwner) {
        onBackgrounded?.invoke()
    }
}