package com.echopanel.app.presentation.interview

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.echopanel.app.domain.model.CheatAlert
import com.echopanel.app.domain.model.CheatSeverity
import com.echopanel.app.domain.model.ScriptEntry
import com.echopanel.app.domain.model.ScriptQuestionSource

/**
 * Banner shown when an integrity/cheating signal has been flagged for
 * this session. Deliberately never says just "cheating detected" — every
 * alert carries the [CheatAlert.summary] that traces back to the specific
 * signals that caused it (see backend CheatFlag.contributing_signals),
 * so the interviewer always sees WHY it fired, not a bare accusation.
 *
 * Shown to both interviewer and candidate builds alike, in keeping with
 * the project's transparency-by-design stance — see AiDisclosureBanner.
 */
@Composable
fun CheatAlertBanner(alerts: List<CheatAlert>, modifier: Modifier = Modifier) {
    val latest = alerts.lastOrNull { !it.acknowledged } ?: return
    val (bg, fg) = when (latest.severity) {
        CheatSeverity.LOW -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        CheatSeverity.MEDIUM -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        CheatSeverity.HIGH -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = bg,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(10.dp))
            Column {
                Text(
                    text = "Integrity check — ${latest.severity.name.lowercase()}",
                    style = MaterialTheme.typography.labelLarge,
                    color = fg,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = latest.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = fg,
                )
            }
        }
    }
}

/**
 * The shared live "script panel" — both the interviewer and the
 * interviewee see the exact same list, polled from the same backend
 * endpoint (GET /script/{sessionId}). AI-suggested questions are
 * grounded in the Context Graph; the interviewer can also type their own,
 * which appears instantly on both sides too.
 */
@Composable
fun ScriptPanel(
    script: List<ScriptEntry>,
    isSuggesting: Boolean,
    onRequestSuggestions: () -> Unit,
    onAddCustomQuestion: (String) -> Unit,
    onMarkUsed: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember { mutableStateOf("") }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // "Shared script" heading intentionally removed — the
            // Suggest button and the entries below make the panel's
            // purpose obvious without a redundant label taking up space.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onRequestSuggestions, enabled = !isSuggesting) {
                    if (isSuggesting) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(6.dp))
                        Text("Thinking…")
                    } else {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("Suggest")
                    }
                }
            }

            if (script.isEmpty()) {
                Text(
                    "No questions queued yet — ask the panel to suggest some, or type your own below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(script.size),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(script.reversed()) { entry ->
                        ScriptEntryRow(entry = entry, onMarkUsed = { onMarkUsed(entry.id) })
                    }
                }
            }

            Divider(modifier = Modifier.padding(vertical = 10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            "Type your own question…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    singleLine = true,
                    // Explicit colors — this field sits on a translucent
                    // surfaceVariant card over a gradient background, and
                    // without pinning these, both the typed text and the
                    // border can land on a near-invisible low-contrast
                    // combination (this is exactly what showed up as a
                    // seemingly-empty box with unreadable input).
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        cursorColor = MaterialTheme.colorScheme.primary,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
                Spacer(Modifier.size(8.dp))
                IconButton(
                    onClick = {
                        if (draft.isNotBlank()) {
                            onAddCustomQuestion(draft)
                            draft = ""
                        }
                    },
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Add question")
                }
            }
        }
    }
}

// Small helper so the LazyColumn doesn't grow unbounded inside a scrolling
// screen — caps at roughly 4 visible rows' worth of height, whichever is
// smaller for a short list.
private fun Modifier.heightIn(itemCount: Int): Modifier =
    this.then(Modifier.height((minOf(itemCount, 4) * 64).dp))

@Composable
private fun ScriptEntryRow(entry: ScriptEntry, onMarkUsed: () -> Unit) {
    val isSuggested = entry.source == ScriptQuestionSource.SUGGESTED
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (entry.used) {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = if (isSuggested) {
                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f)
                        } else {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                        },
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(
                            text = if (isSuggested) "AI" else "YOU",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    entry.persona?.let { persona ->
                        Spacer(Modifier.size(6.dp))
                        Text(
                            persona.displayName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    entry.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (entry.used) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            if (!entry.used) {
                IconButton(onClick = onMarkUsed) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = "Mark as asked",
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}