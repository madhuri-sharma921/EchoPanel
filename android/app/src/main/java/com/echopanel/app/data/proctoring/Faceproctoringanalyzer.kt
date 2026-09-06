package com.echopanel.app.data.proctoring


import android.annotation.SuppressLint
import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.view.PreviewView
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import android.util.Log
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
    private var lifecycleOwner: LifecycleOwner? = null
    private var previewUseCase: Preview? = null
    private var analysisUseCase: ImageAnalysis? = null
    private var isVideoEnabled: Boolean = true

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .build()
    )

    // Sustained-state tracking calibrated so a natural eye blink (100-200ms)
    // or micro-glance does NOT fire false cheating alerts — we only report
    // once a condition holds for sustained frames (roughly 1.5s).
    private var consecutiveNoFace = 0
    private var consecutiveMultiFace = 0
    private var consecutiveGazeOff = 0
    private val sustainedFrameThreshold = 15
    private val multiFaceFrameThreshold = 10

    fun bindPreviewView(previewView: PreviewView) {
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        val preview = Preview.Builder().build()
        preview.setSurfaceProvider(previewView.surfaceProvider)
        this.previewUseCase = preview
        rebindCamera()
    }

    fun setVideoEnabled(enabled: Boolean) {
        if (isVideoEnabled != enabled) {
            isVideoEnabled = enabled
            rebindCamera()
        }
    }

    private fun rebindCamera() {
        val provider = cameraProvider ?: return
        val lifecycle = lifecycleOwner ?: return
        try {
            provider.unbindAll()
            if (!isVideoEnabled) return
            val useCases = mutableListOf<UseCase>()
            analysisUseCase?.let { useCases.add(it) }
            previewUseCase?.let { useCases.add(it) }
            if (useCases.isNotEmpty()) {
                provider.bindToLifecycle(
                    lifecycle,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    *useCases.toTypedArray(),
                )
            }
        } catch (e: Exception) {
            Log.e("FaceProctoringAnalyzer", "Camera bind failed: ${e.message}", e)
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    fun observe(lifecycleOwner: LifecycleOwner): Flow<ObservedSignal> = callbackFlow {
        this@FaceProctoringAnalyzer.lifecycleOwner = lifecycleOwner
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = try {
                providerFuture.get()
            } catch (_: Exception) {
                close()
                return@addListener
            }
            cameraProvider = provider

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                analyzeFrame(imageProxy) { signal -> trySend(signal) }
            }
            analysisUseCase = analysis
            rebindCamera()
        }, androidx.core.content.ContextCompat.getMainExecutor(context))

        awaitClose {
            try {
                cameraProvider?.unbindAll()
                detector.close()
                analysisExecutor.shutdown()
            } catch (_: Exception) {}
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
                        // Only report after genuinely sustained absence (~1.5s),
                        // preventing false positives from transient blinks or lighting shifts
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
                        if (consecutiveMultiFace == multiFaceFrameThreshold) {
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

                        // Check eye-open probabilities: natural eye blinking (both eyes closed momentarily)
                        // is healthy liveness evidence, not a cheating event or missing face
                        val leftEyeOpen = face.leftEyeOpenProbability
                        val rightEyeOpen = face.rightEyeOpenProbability
                        val isBlinking = leftEyeOpen != null && rightEyeOpen != null &&
                                leftEyeOpen < 0.25f && rightEyeOpen < 0.25f

                        val yaw = face.headEulerAngleY
                        // If candidate is just blinking naturally with head facing camera,
                        // do not increment gaze-off or no-face
                        if (!isBlinking && kotlin.math.abs(yaw) > 35f) {
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