package com.echopanel.app.presentation.interview

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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

    LaunchedEffect(Unit) {
        viewModel.startSession(candidateName = candidateName, personas = personas)
    }

    // Camera permission for on-device face/gaze proctoring signals — this
    // is an integrity feature, not a hard requirement to run the
    // interview: a decline just means face-based signals are skipped,
    // audio/text-derived signals still work regardless.
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(uiState.callState) {
        if (uiState.callState == CallState.Connected && !hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Start face-proctoring once connected and permitted; the analyzer
    // tears down camera resources itself when the lifecycle stops or the
    // flow is cancelled (see FaceProctoringAnalyzer.observe).
    LaunchedEffect(uiState.callState, hasCameraPermission) {
        if (uiState.callState == CallState.Connected && hasCameraPermission) {
            val analyzer = FaceProctoringAnalyzer(context.applicationContext)
            viewModel.startFaceProctoring(analyzer, lifecycleOwner)
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

    Scaffold(
        topBar = { AiDisclosureBanner() },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val state = uiState.callState) {
                CallState.Connecting -> ConnectingHero()
                is CallState.Error -> ErrorBanner(state.message)
                CallState.Connected -> LiveStatusHero(
                    agentState = uiState.agentState,
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
                ScriptPanel(
                    script = uiState.script,
                    isSuggesting = uiState.isSuggestingQuestions,
                    onRequestSuggestions = viewModel::onRequestSuggestedQuestions,
                    onAddCustomQuestion = viewModel::onAddCustomQuestion,
                    onMarkUsed = viewModel::onMarkScriptEntryUsed,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            TranscriptList(
                transcript = uiState.transcript,
                modifier = Modifier
                    .fillMaxSize()
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
    onInterrupt: () -> Unit,
    onFinish: () -> Unit,
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
        PulsingOrb(agentState)

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
                    // solid, engaged answer. Gives the panel a visible,
                    // at-a-glance "reaction" beyond just the spoken text.
                    turn.reactionEmoji?.let { emoji ->
                        Text(
                            text = emoji,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(start = 4.dp, bottom = 3.dp),
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