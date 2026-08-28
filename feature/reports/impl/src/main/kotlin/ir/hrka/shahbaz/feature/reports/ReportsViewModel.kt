package ir.hrka.shahbaz.feature.reports

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shahbaz.flightblackbox.FbbEventType
import com.shahbaz.flightblackbox.FbbPersistence
import com.shahbaz.flightblackbox.FbbReportDetails
import com.shahbaz.flightblackbox.FbbReportStatus
import com.shahbaz.flightblackbox.FlightBlackBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ReportsUiState(
    val reports: List<FbbReportDetails> = emptyList(),
    val selectedSessionIds: Set<String> = emptySet(),
    val selectionMode: Boolean = false,
    val busy: Boolean = true,
    val message: String? = null,
)

class ReportsViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext: Application
        get() = getApplication()

    private val _uiState = MutableStateFlow(ReportsUiState())
    val uiState: StateFlow<ReportsUiState> = _uiState

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            val reports = withContext(Dispatchers.IO) {
                FlightBlackBox.reports(appContext).getAllReportDetails()
            }
            _uiState.update { state ->
                state.copy(
                    reports = reports,
                    selectedSessionIds = state.selectedSessionIds
                        .intersect(reports.map { it.descriptor.sessionId }.toSet()),
                    busy = false,
                )
            }
        }
    }

    fun enterSelectionMode(sessionId: String) {
        _uiState.update { state ->
            val selected = state.selectedSessionIds.toMutableSet()
            if (state.canSelect(sessionId)) selected += sessionId
            state.copy(selectionMode = true, selectedSessionIds = selected)
        }
    }

    fun toggleReportSelection(sessionId: String) {
        _uiState.update { state ->
            if (!state.canSelect(sessionId)) return@update state
            val selected = state.selectedSessionIds.toMutableSet()
            if (!selected.add(sessionId)) selected.remove(sessionId)
            state.copy(selectedSessionIds = selected)
        }
    }

    fun setAllReportsSelected(selected: Boolean) {
        _uiState.update { state ->
            state.copy(
                selectionMode = true,
                selectedSessionIds = if (selected) {
                    state.reports
                        .filterNot { it.isActiveReport }
                        .map { it.descriptor.sessionId }
                        .toSet()
                } else {
                    emptySet()
                },
            )
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectionMode = false, selectedSessionIds = emptySet()) }
    }

    fun deleteSelectedReports() {
        val selected = _uiState.value.selectedSessionIds
        if (selected.isEmpty()) return
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                FlightBlackBox.reports(appContext).deleteReports(selected)
            }
            FlightBlackBox.record(
                type = FbbEventType.USER,
                description = "Reports deleted selected flight black box reports",
                metadata = mapOf("count" to selected.size, "deleted" to deleted),
                persistence = FbbPersistence.IMPORTANT,
            )
            _uiState.update {
                it.copy(
                    selectionMode = false,
                    selectedSessionIds = emptySet(),
                    message = if (deleted > 0) "$deleted report(s) deleted" else "No reports were deleted",
                )
            }
            refresh()
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private fun ReportsUiState.canSelect(sessionId: String): Boolean =
        reports.firstOrNull { it.descriptor.sessionId == sessionId }?.isActiveReport == false
}

val FbbReportDetails.isActiveReport: Boolean
    get() = descriptor.active || descriptor.status == FbbReportStatus.ACTIVE
