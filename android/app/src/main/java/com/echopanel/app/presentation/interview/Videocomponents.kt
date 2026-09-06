package com.echopanel.app.presentation.interview

import android.view.SurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.echopanel.app.R
import com.echopanel.app.domain.model.AgentActivityState
import com.echopanel.app.domain.model.CallState
import com.echopanel.app.domain.repository.AgoraCallRepository
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * The real interviewee video tile — the candidate's own front camera,
 * rendered via Agora's local video renderer (see
 * AgoraCallRepository.createLocalVideoView). Includes an on/off toggle
 * the candidate controls themselves, independent of whether the camera
 * is technically available (permission/hardware) — turning it off stops
 * capture and publishing entirely (see setLocalVideoEnabled), not just
 * hides the tile locally.
 *
 * FIX (camera stuck/blank): the previous version fetched the SurfaceView
 * exactly once in a LaunchedEffect(Unit) with no retry. On real devices
 * the RtcEngine can take a beat longer to finish initializing than the
 * first composition pass, so that single attempt would return null
 * permanently and the tile would be stuck on the initials fallback
 * forever — even once the engine was actually ready. This version
 * retries with a short backoff until it succeeds or the call ends, and
 * keys the LaunchedEffect on [callState] so a reconnect gets a fresh
 * attempt instead of reusing a stale null.
 */
@Composable
fun CandidateCameraTile(
    agoraCallRepository: AgoraCallRepository,
    candidateName: String,
    callState: CallState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val videoGeneration by agoraCallRepository.observeLocalVideoGeneration().collectAsState()
    val videoEnabled by agoraCallRepository.observeLocalVideoEnabled().collectAsState()
    // Keyed on callState and videoEnabled: retains the last valid SurfaceView
    // during transient retries rather than tearing it down and causing the tile
    // to blink/flicker on every generation bump.
    var surfaceView by remember(callState, videoEnabled) { mutableStateOf<SurfaceView?>(null) }
    var attempted by remember(callState, videoEnabled) { mutableStateOf(0) }

    LaunchedEffect(callState, videoGeneration, videoEnabled) {
        if (callState != CallState.Connected || !videoEnabled) {
            surfaceView = null
            return@LaunchedEffect
        }
        var view: SurfaceView? = null
        var tries = 0
        while (view == null && tries < 8) {
            view = agoraCallRepository.createLocalVideoView(context)
            if (view == null) {
                tries++
                attempted = tries
                delay(200L * tries) // gentle backoff
            }
        }
        if (view != null) {
            surfaceView = view
        }
    }

    Box(
        modifier = modifier
            .width(60.dp)
            .height(84.dp),
        contentAlignment = Alignment.BottomEnd,
    ) {
        Box(
            modifier = Modifier
                .width(56.dp)
                .height(76.dp)
                .align(Alignment.TopStart)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val view = surfaceView
            when {
                !videoEnabled -> {
                    // Candidate turned their own camera off — a distinct,
                    // intentional state from "unavailable", shown with
                    // the same initials fallback but the video-off icon
                    // makes the reason obvious rather than looking broken.
                    Text(
                        text = candidateName.trim().take(1).uppercase().ifBlank { "?" },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                view != null -> {
                    var lastBoundView by remember { mutableStateOf<SurfaceView?>(null) }
                    AndroidView(
                        factory = {
                            view.apply { setZOrderMediaOverlay(true) }
                        },
                        update = { sv ->
                            sv.setZOrderMediaOverlay(true)
                            if (lastBoundView !== sv) {
                                agoraCallRepository.rebindLocalVideo(sv)
                                lastBoundView = sv
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                callState == CallState.Connected && attempted < 8 -> {
                    // Still retrying — show a small spinner instead of a
                    // silent blank box, so it's visibly "loading" rather
                    // than looking broken.
                    CircularProgressIndicator(modifier = Modifier.height(16.dp), strokeWidth = 2.dp)
                }
                else -> {
                    // Genuinely unavailable (permission declined, no camera
                    // hardware, or retries exhausted) — initials fallback so
                    // the tile never renders as a dead hole in the UI.
                    Text(
                        text = candidateName.trim().take(1).uppercase().ifBlank { "?" },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // The on/off toggle itself — a plain icon-only button (no circular
        // chip background) anchored to the tile's corner, so the icon
        // itself is what communicates state: a plain camera glyph when
        // on, camera-with-slash when off, colored differently too so the
        // state reads instantly rather than needing to notice a small
        // glyph change against a same-looking background. Always shown
        // once connected, so the candidate can always find it (including
        // to turn video back ON from the off state).
        if (callState == CallState.Connected) {
            IconButton(
                onClick = { agoraCallRepository.setLocalVideoEnabled(!videoEnabled) },
                modifier = Modifier.size(26.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = if (videoEnabled) {
                        MaterialTheme.colorScheme.surface
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                ),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    // A slightly larger, dim duplicate of the icon behind
                    // the real one acts as a soft outline/shadow — keeps
                    // the glyph legible over a bright video feed without
                    // needing a solid chip background behind it.
                    Icon(
                        imageVector = if (videoEnabled) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f),
                        modifier = Modifier.size(20.dp),
                    )
                    Icon(
                        imageVector = if (videoEnabled) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                        contentDescription = if (videoEnabled) "Turn camera off" else "Turn camera on",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/**
 * The AI interviewer's "video" — a single shared photo used for every
 * interviewer persona (technical, behavioral, etc.), since there's no
 * real AI camera feed and the personas take turns speaking through the
 * same on-screen "panelist." [agentState] no longer swaps between two
 * Lottie loops; instead the still photo gets a periodic eye-blink so it
 * reads as a live presence instead of a frozen headshot, plus a small
 * pulsing indicator while the persona is actually speaking.
 *
 * The blink is drawn as two short lines over the photo's estimated eye
 * position rather than baked into the image asset, so it works at
 * whatever size this composable is laid out at. EYE_* below are that
 * position as fractions of ai_interviewer_photo's own bounds (a fixed
 * 795x900 portrait) — keep the surrounding Box at that same aspect ratio
 * (via aspectRatio(PHOTO_ASPECT_RATIO)) so the fractions stay accurate
 * instead of drifting if ContentScale.Crop ever had to crop unevenly.
 */
private const val PHOTO_ASPECT_RATIO = 795f / 900f
private val EYE_LINE_Y = 0.298f
private val LEFT_EYE_X_RANGE = 0.383f to 0.468f
private val RIGHT_EYE_X_RANGE = 0.543f to 0.628f

@Composable
fun AiInterviewerAvatarView(
    agentState: AgentActivityState,
    modifier: Modifier = Modifier,
) {
    val isSpeaking = agentState == AgentActivityState.SPEAKING

    // Blinks every 2.5-5s, closed for ~120ms — irregular enough that it
    // doesn't read as a mechanical loop, occasional enough that it stays
    // subtle rather than distracting from the actual interview.
    var eyesClosed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(Random.nextLong(2500L, 5000L))
            eyesClosed = true
            delay(120L)
            eyesClosed = false
        }
    }

    val speakingPulse by rememberInfiniteTransition(label = "speakingPulse").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "speakingPulseAlpha",
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .aspectRatio(PHOTO_ASPECT_RATIO)
                .clip(RoundedCornerShape(16.dp))
                .then(
                    if (isSpeaking) {
                        Modifier.border(
                            width = 3.dp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = speakingPulse),
                            shape = RoundedCornerShape(16.dp),
                        )
                    } else {
                        Modifier
                    },
                ),
        ) {
            Image(
                painter = painterResource(R.drawable.ai_interviewer_photo),
                contentDescription = "AI interviewer",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            if (eyesClosed) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val eyelidColor = Color(0xFF6B4A3A)
                    val strokeWidth = size.height * 0.012f
                    val y = size.height * EYE_LINE_Y
                    drawLine(
                        color = eyelidColor,
                        start = Offset(size.width * LEFT_EYE_X_RANGE.first, y),
                        end = Offset(size.width * LEFT_EYE_X_RANGE.second, y),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = eyelidColor,
                        start = Offset(size.width * RIGHT_EYE_X_RANGE.first, y),
                        end = Offset(size.width * RIGHT_EYE_X_RANGE.second, y),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round,
                    )
                }
            }

            // Small "speaking" indicator — replaces the cue the old
            // idle/speaking Lottie swap used to give, now that the photo
            // itself doesn't animate for that.
            if (isSpeaking) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 10.dp)
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = speakingPulse)),
                )
            }
        }
    }
}