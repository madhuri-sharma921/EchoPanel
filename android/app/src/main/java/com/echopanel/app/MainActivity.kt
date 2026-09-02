package com.echopanel.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.echopanel.app.domain.model.PersonaRole
import com.echopanel.app.presentation.common.theme.EchoPanelTheme
import com.echopanel.app.presentation.interview.InterviewScreen
import com.echopanel.app.presentation.report.ReportScreen
import com.echopanel.app.presentation.welcome.WelcomeScreen
import dagger.hilt.android.AndroidEntryPoint
import java.net.URLDecoder
import java.net.URLEncoder

private object Routes {
    const val WELCOME = "welcome"
    const val INTERVIEW = "interview/{candidateName}/{personas}"
    const val REPORT = "report/{sessionId}"

    fun interview(candidateName: String, personas: List<PersonaRole>): String {
        val encodedName = URLEncoder.encode(candidateName, "UTF-8")
        val encodedPersonas = URLEncoder.encode(personas.joinToString(","), "UTF-8")
        return "interview/$encodedName/$encodedPersonas"
    }

    fun report(sessionId: String) = "report/$sessionId"
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            EchoPanelTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RequireMicPermission {
                        val navController = rememberNavController()
                        NavHost(navController = navController, startDestination = Routes.WELCOME) {
                            composable(Routes.WELCOME) {
                                WelcomeScreen(
                                    onStartInterview = { candidateName, personas ->
                                        navController.navigate(Routes.interview(candidateName, personas))
                                    },
                                )
                            }
                            composable(
                                route = Routes.INTERVIEW,
                                arguments = listOf(
                                    navArgument("candidateName") { type = NavType.StringType },
                                    navArgument("personas") { type = NavType.StringType },
                                ),
                            ) { backStackEntry ->
                                val candidateName = URLDecoder.decode(
                                    backStackEntry.arguments?.getString("candidateName").orEmpty(),
                                    "UTF-8",
                                )
                                val personas = URLDecoder.decode(
                                    backStackEntry.arguments?.getString("personas").orEmpty(),
                                    "UTF-8",
                                ).split(",").mapNotNull { name ->
                                    runCatching { PersonaRole.valueOf(name) }.getOrNull()
                                }
                                InterviewScreen(
                                    candidateName = candidateName,
                                    personas = personas.ifEmpty { listOf(PersonaRole.TECHNICAL) },
                                    onInterviewEnded = { sessionId ->
                                        navController.navigate(Routes.report(sessionId))
                                    },
                                )
                            }
                            composable(
                                route = Routes.REPORT,
                                arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
                            ) { backStackEntry ->
                                val sessionId = backStackEntry.arguments?.getString("sessionId").orEmpty()
                                ReportScreen(sessionId = sessionId)
                            }
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun RequireMicPermission(content: @Composable () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            launcher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    if (hasPermission) {
        content()
    } else {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Microphone access is required for the voice interview.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(
                modifier = Modifier.padding(top = 16.dp),
                onClick = { launcher.launch(Manifest.permission.RECORD_AUDIO) },
            ) {
                Text("Grant microphone access")
            }
        }
    }
}
