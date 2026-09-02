package com.echopanel.app.presentation.report

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.echopanel.app.domain.model.FinalReport
import com.echopanel.app.domain.model.VerdictItem
import com.echopanel.app.domain.usecase.GetFinalReportUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReportUiState(
    val report: FinalReport? = null,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
)

@HiltViewModel
class ReportViewModel @Inject constructor(
    private val getFinalReport: GetFinalReportUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReportUiState())
    val uiState: StateFlow<ReportUiState> = _uiState.asStateFlow()

    fun loadReport(sessionId: String) {
        viewModelScope.launch {
            getFinalReport(sessionId)
                .onSuccess { report ->
                    _uiState.value = ReportUiState(report = report, isLoading = false)
                }
                .onFailure { error ->
                    _uiState.value = ReportUiState(
                        isLoading = false,
                        errorMessage = error.message,
                    )
                }
        }
    }
}

@Composable
fun ReportScreen(
    sessionId: String,
    viewModel: ReportViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(sessionId) { viewModel.loadReport(sessionId) }

    when {
        uiState.isLoading -> Text("Loading report…", modifier = Modifier.padding(16.dp))
        uiState.errorMessage != null -> Text(
            "Error: ${uiState.errorMessage}",
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.error,
        )
        uiState.report != null -> ReportContent(uiState.report!!)
    }
}

@Composable
private fun ReportContent(report: FinalReport) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(text = "Interview Report: ${report.candidateName}", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "${report.contradictionsFlagged} contradictions · ${report.vagueAnswersFlagged} vague answers flagged",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(report.perCompetency) { item -> VerdictCard(item) }
        }
    }
}

@Composable
private fun VerdictCard(item: VerdictItem) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = item.competency, style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(
                progress = { item.score },
                modifier = Modifier.padding(vertical = 4.dp),
            )
            Text(text = item.verdict, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
