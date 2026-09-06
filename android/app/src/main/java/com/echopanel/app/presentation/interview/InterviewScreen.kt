package com.echopanel.app.presentation.interview

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.echopanel.app.data.proctoring.FaceProctoringAnalyzer
import com.echopanel.app.domain.model.AgentActivityState
import com.echopanel.app.domain.model.CallState
import com.echopanel.app.domain.model.PersonaRole
import com.echopanel.app.domain.model.ScenarioCard
import com.echopanel.app.domain.model.TranscriptTurn
import com.echopanel.app.presentation.disclosure.AiDisclosureBanner
import com.echopanel.app.presentation.disclosure.ConsentDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InterviewScreen(
    candidateName: String,
    personas: List<PersonaRole>,
    onInterviewEnded: (sessionId: String) -> Unit,
    viewModel: InterviewViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Camera permission — requested UP FRONT, before the voice call is
    // ever joined, not after connecting. This is the actual fix for the
    // candidate camera tile rendering as a solid black box: Agora's
    // engine.enableVideo()/startPreview() run inside joinCall(), which
    // used to fire before permission had been granted, so the camera
    // capturer silently failed and the local renderer painted black
    // (a bound surface with zero incoming frames) instead of showing
    // anything. Requesting permission before the join ever starts means
    // joinCall() always runs with the permission decision already made.
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED,
        )
    }
    var cameraPermissionRequested by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
        cameraPermissionRequested = true
    }

    // Only start the session (and therefore, eventually, joinCall()) once
    // the permission prompt has been resolved either way — a decline
    // still proceeds (video/proctoring degrade gracefully), but we never
    // race ahead of the user's answer.
    LaunchedEffect(cameraPermissionRequested) {
        if (cameraPermissionRequested) {
            viewModel.startSession(candidateName = candidateName, personas = personas)
        }
    }

    val proctoringAnalyzer = remember(context) {
        FaceProctoringAnalyzer(context.applicationContext)
    }

    // Start face-proctoring once connected and permitted; the analyzer
    // tears down camera resources itself when the lifecycle stops or the
    // flow is cancelled (see FaceProctoringAnalyzer.observe).
    LaunchedEffect(uiState.callState, hasCameraPermission) {
        if (uiState.callState == CallState.Connected && hasCameraPermission) {
            viewModel.startFaceProctoring(proctoringAnalyzer, lifecycleOwner)
        }
    }

    // App-backgrounding is itself an integrity signal distinct from
    // camera/mic — observed via the standard lifecycle, no extra
    // permission needed.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && uiState.callState == CallState.Connected) {
                viewModel.onAppBackgrounded()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Explicit call teardown when THIS SCREEN leaves composition — not
    // just relying on the ViewModel's onCleared(). This matters because
    // Hilt's hiltViewModel() scopes the ViewModel to the surrounding
    // navigation back-stack entry, which can outlive a simple "navigate
    // away" if that entry stays on the stack — meaning onCleared() (and
    // therefore leaveCall()) might never fire just from leaving this
    // screen, leaving a stale RtcEngine with a dead camera capturer
    // running in the background. Reopening the interview screen would
    // then just rebind a new SurfaceView to that same broken engine,
    // which is the exact "stuck even after reopening" symptom — this
    // guarantees the engine is actually torn down when the screen goes
    // away, regardless of ViewModel/back-stack lifecycle timing.
    DisposableEffect(Unit) {
        onDispose {
            viewModel.leaveCallImmediately()
        }
    }

    var showScriptSheet by remember { mutableStateOf(false) }
    val scriptSheetState = rememberModalBottomSheetState()

    Scaffold(
        topBar = { AiDisclosureBanner() },
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            // Script panel moved from an always-visible, scrollable card
            // (easy to miss — it could end up scrolled well below the
            // fold under the avatar/camera/banners on smaller screens)
            // to a FAB that opens it on demand as a bottom sheet. The
            // badge shows how many suggested/typed questions are still
            // unused, so the interviewer knows there's something to look
            // at without needing to open the sheet first.
            if (uiState.callState == CallState.Connected) {
                val unusedCount = uiState.script.count { !it.used }
                BadgedBox(
                    badge = {
                        if (unusedCount > 0) {
                            Badge { Text(unusedCount.toString()) }
                        }
                    },
                ) {
                    FloatingActionButton(onClick = { showScriptSheet = true }) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = "Open shared script")
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Everything above the transcript (avatar, camera PiP, cheat
            // banner, scenario banner, script panel) now lives inside its
            // own vertical scroll — previously this was a plain, non-
            // scrollable Column, so once the script panel and a couple of
            // banners were all visible at once, the combined height could
            // exceed the screen with no way to reach the parts pushed
            // off-screen (including, on smaller devices, being unable to
            // scroll back up to the video tiles at all). weight(1f, fill
            // = false) lets this section take only the space it needs up
            // to the available height, then the transcript below claims
            // whatever remains via its own weight(1f).
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                when (val state = uiState.callState) {
                    CallState.Connecting -> ConnectingHero()
                    is CallState.Error -> ErrorBanner(state.message)
                    CallState.Connected -> LiveStatusHero(
                        agentState = uiState.agentState,
                        callState = state,
                        candidateName = candidateName,
                        agoraCallRepository = viewModel.agoraCallRepository,
                        analyzer = proctoringAnalyzer,
                        onInterrupt = viewModel::onInterruptAgent,
                        onFinish = {
                            uiState.sessionId?.let(onInterviewEnded)
                        },
                    )
                    else -> Unit
                }

                uiState.errorMessage?.let { message ->
                    ErrorBanner(message)
                }

                AnimatedVisibility(
                    visible = uiState.cheatAlerts.any { !it.acknowledged },
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    CheatAlertBanner(
                        alerts = uiState.cheatAlerts,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                AnimatedVisibility(
                    visible = uiState.scenario != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    uiState.scenario?.let { scenario ->
                        ScenarioBanner(
                            scenario = scenario,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }

                if (uiState.callState == CallState.Connected) {
                    AnimatedVisibility(
                        visible = uiState.pinConfirmation != null,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        uiState.pinConfirmation?.let { message ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "\u2713",
                                        fontSize = 18.sp,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    )
                                    Spacer(Modifier.size(8.dp))
                                    Text(
                                        text = message,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // The transcript claims whatever height remains after the
            // (now independently scrollable) header section above, and
            // scrolls on its own via the LazyColumn inside TranscriptList
            // — unchanged from before, just now correctly bounded instead
            // of being squeezed or pushed off-screen by an overflowing
            // header.
            TranscriptList(
                transcript = uiState.transcript,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp),
            )
        }
    }

    if (uiState.showConsentDialog) {
        ConsentDialog(
            onConsent = viewModel::onConsentGiven,
            onDecline = viewModel::onConsentDeclined,
        )
    }

    if (showScriptSheet) {
        ModalBottomSheet(
            onDismissRequest = { showScriptSheet = false },
            sheetState = scriptSheetState,
        ) {
            ScriptPanel(
                script = uiState.script,
                isSuggesting = uiState.isSuggestingQuestions,
                onRequestSuggestions = viewModel::onRequestSuggestedQuestions,
                onAddCustomQuestion = viewModel::onAddCustomQuestion,
                onMarkUsed = viewModel::onMarkScriptEntryUsed,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Spacer(Modifier.size(16.dp))
        }
    }
}

@Composable
private fun ConnectingHero() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Text(
            "Connecting to your interview panel…",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(14.dp),
        )
    }
}

/**
 * A visual "scenario card" a persona sets up before a role-play or
 * scenario-based question (a named PS11 requirement). The panel still
 * speaks the framing aloud through Agora's TTS — this card gives the
 * candidate something to see at the same time, rather than relying on
 * audio alone to set the scene.
 */
@Composable
private fun ScenarioBanner(scenario: ScenarioCard, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(scenario.emoji, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.size(10.dp))
                Column {
                    Text(
                        "SCENARIO",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.tertiary,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        scenario.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Spacer(Modifier.size(8.dp))
            Text(
                scenario.setting,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}


@Composable
private fun LiveStatusHero(
    agentState: AgentActivityState,
    callState: CallState,
    candidateName: String,
    agoraCallRepository: com.echopanel.app.domain.repository.AgoraCallRepository,
    onInterrupt: () -> Unit,
    onFinish: () -> Unit,
    analyzer: FaceProctoringAnalyzer? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.background,
                    ),
                ),
            )
            .padding(vertical = 24.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Two video tiles: the AI panel's looping avatar animation (large,
        // center — there's no real AI camera feed, this is a placeholder
        // that reacts to agentState) and the candidate's own real front
        // camera as a small picture-in-picture tile in the corner, the
        // same layout convention as most video-call apps.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp),
        ) {
            AiInterviewerAvatarView(
                agentState = agentState,
                modifier = Modifier
                    .fillMaxSize()
                    .align(Alignment.Center),
            )
            CandidateCameraTile(
                agoraCallRepository = agoraCallRepository,
                candidateName = candidateName,
                callState = callState,
                analyzer = analyzer,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp),
            )
        }

        Text(
            text = statusLabel(agentState),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )

        if (agentState == AgentActivityState.SPEAKING) {
            Button(
                onClick = onInterrupt,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ),
            ) {
                Icon(Icons.Filled.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Interrupt")
            }
        }

        androidx.compose.material3.TextButton(onClick = onFinish) {
            Text("Finish interview & see report", color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun PulsingOrb(agentState: AgentActivityState) {
    val transition = rememberInfiniteTransition(label = "orb-pulse")
    val isActive = agentState == AgentActivityState.LISTENING ||
            agentState == AgentActivityState.SPEAKING ||
            agentState == AgentActivityState.THINKING

    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (isActive) 1.18f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "orb-scale",
    )

    val orbColor by animateColorAsState(
        targetValue = when (agentState) {
            AgentActivityState.SPEAKING -> MaterialTheme.colorScheme.secondary
            AgentActivityState.LISTENING -> MaterialTheme.colorScheme.primary
            AgentActivityState.THINKING -> MaterialTheme.colorScheme.tertiary
            AgentActivityState.SILENT -> MaterialTheme.colorScheme.outline
            AgentActivityState.UNKNOWN -> MaterialTheme.colorScheme.outline
        },
        label = "orb-color",
    )

    Box(
        modifier = Modifier
            .size(88.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(orbColor, orbColor.copy(alpha = 0.55f)),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Mic,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.surface,
            modifier = Modifier.size(34.dp),
        )
    }
}

private fun statusLabel(state: AgentActivityState): String = when (state) {
    AgentActivityState.SILENT -> "Panel is ready"
    AgentActivityState.LISTENING -> "Listening to you…"
    AgentActivityState.THINKING -> "Panel is thinking…"
    AgentActivityState.SPEAKING -> "Panel is speaking"
    AgentActivityState.UNKNOWN -> "Connecting…"
}

@Composable
private fun TranscriptList(transcript: List<TranscriptTurn>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()

    LaunchedEffect(transcript.size) {
        if (transcript.isNotEmpty()) {
            listState.animateScrollToItem(transcript.size - 1)
        }
    }

    if (transcript.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                "The transcript will appear here as the conversation happens.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier,
        state = listState,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
    ) {
        items(transcript) { turn -> TranscriptBubble(turn) }
    }
}

@Composable
private fun TranscriptBubble(turn: TranscriptTurn) {
    val isCandidate = turn.isCandidate
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isCandidate) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 300.dp),
            horizontalAlignment = if (isCandidate) Alignment.End else Alignment.Start,
        ) {
            if (!isCandidate) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = turn.speaker,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 14.dp, bottom = 3.dp),
                    )
                    // Reaction emoji for this persona's turn — e.g. 🤔 for a
                    // vague answer, ⚡ for a caught contradiction, 👍 for a
                    // solid, engaged answer. Sized well above body text and
                    // given a small spring pop-in so it visibly registers
                    // as a "reaction" rather than reading as stray inline
                    // punctuation next to the speaker name — this is the
                    // single biggest driver of the panel feeling like it's
                    // actively engaging with what the candidate just said.
                    turn.reactionEmoji?.let { emoji ->
                        var popped by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                        val scale by animateFloatAsState(
                            targetValue = if (popped) 1f else 0f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessLow,
                            ),
                            label = "reactionEmojiPop",
                        )
                        androidx.compose.runtime.LaunchedEffect(emoji) { popped = true }
                        Text(
                            text = emoji,
                            fontSize = 26.sp,
                            modifier = Modifier
                                .padding(start = 6.dp, bottom = 1.dp)
                                .graphicsLayer(scaleX = scale, scaleY = scale),
                        )
                    }
                }
            }
            Surface(
                color = if (isCandidate) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (isCandidate) {
                    MaterialTheme.colorScheme.onSecondary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                shape = RoundedCornerShape(
                    topStart = 18.dp,
                    topEnd = 18.dp,
                    bottomStart = if (isCandidate) 18.dp else 4.dp,
                    bottomEnd = if (isCandidate) 4.dp else 18.dp,
                ),
                shadowElevation = 1.dp,
            ) {
                Text(
                    text = turn.text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                )
            }
        }
    }
}