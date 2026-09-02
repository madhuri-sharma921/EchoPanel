package com.echopanel.app.presentation.welcome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.echopanel.app.domain.model.PersonaRole


@Composable
fun WelcomeScreen(
    onStartInterview: (candidateName: String, personas: List<PersonaRole>) -> Unit,
) {
    var candidateName by remember { mutableStateOf("") }
    var showNameError by remember { mutableStateOf(false) }
    val selectedPersonas = remember {
        mutableStateOf(
            setOf(PersonaRole.TECHNICAL, PersonaRole.PRODUCT_BUSINESS, PersonaRole.BEHAVIOURAL),
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        HeroHeader()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(28.dp))

            Text(
                "Your name",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = candidateName,
                onValueChange = {
                    candidateName = it
                    if (it.isNotBlank()) showNameError = false
                },
                placeholder = { Text("e.g. Priya Sharma") },
                singleLine = true,
                isError = showNameError,
                supportingText = {
                    if (showNameError) {
                        Text(
                            "Please enter your name to continue",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(28.dp))

            Text(
                "Choose your interview panel",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Pick at least one interviewer. They'll coordinate and take turns.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(PersonaRole.entries) { persona ->
                    PersonaSelectRow(
                        persona = persona,
                        isSelected = persona in selectedPersonas.value,
                        onToggle = {
                            selectedPersonas.value = if (persona in selectedPersonas.value) {
                                selectedPersonas.value - persona
                            } else {
                                selectedPersonas.value + persona
                            }
                        },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = {
                    if (candidateName.isBlank()) {
                        showNameError = true
                    } else {
                        onStartInterview(candidateName.trim(), selectedPersonas.value.toList())
                    }
                },
                enabled = selectedPersonas.value.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text(
                    "Start Interview",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun HeroHeader() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.primaryContainer,
                    ),
                ),
            )
            .padding(top = 48.dp, bottom = 28.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.16f),
        ) {
            Icon(
                imageVector = Icons.Filled.Groups,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .size(56.dp)
                    .padding(14.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "EchoPanel",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "A coordinated AI interview panel that adapts to you.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PersonaSelectRow(
    persona: PersonaRole,
    isSelected: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    persona.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    personaBlurb(persona),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun personaBlurb(persona: PersonaRole): String = when (persona) {
    PersonaRole.TECHNICAL -> "Probes correctness, depth, and edge cases"
    PersonaRole.PRODUCT_BUSINESS -> "Challenges business and customer impact"
    PersonaRole.BEHAVIOURAL -> "Looks for concrete, specific examples"
    PersonaRole.CUSTOMER -> "Pushes on end-user experience"
    PersonaRole.HIRING_MANAGER -> "Weighs overall fit and seniority"
}