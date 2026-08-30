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

/**
 * Documents the ReportsUiState type and the role it plays in this module.
 */
data class ReportsUiState(
    /**
     * Exposes the reports value.
     */
    val reports: List<FbbReportDetails> = emptyList(),
    /**
     * Exposes the selectedSessionIds value.
     */
    val selectedSessionIds: Set<String> = emptySet(),
    /**
     * Exposes the selectionMode value.
     */
    val selectionMode: Boolean = false,
    /**
     * Exposes the busy value.
     */
    val busy: Boolean = true,
    /**
     * Exposes the message value.
     */
    val message: String? = null,
)

/**
 * Documents the ReportsViewModel type and the role it plays in this module.
 */
class ReportsViewModel(application: Application) : AndroidViewModel(application) {
    /**
     * Exposes the appContext value.
     */
    private val appContext: Application
        get() = getApplication()

    /**
     * Exposes the _uiState value.
     */
    private val _uiState = MutableStateFlow(ReportsUiState())
    /**
     * Exposes the uiState value.
     */
    val uiState: StateFlow<ReportsUiState> = _uiState

    init {
        refresh()
    }

    /**
     * Runs the refresh operation.
     */
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

    /**
     * Runs the enterSelectionMode operation.
     */
    fun enterSelectionMode(sessionId: String) {
        _uiState.update { state ->
            val selected = state.selectedSessionIds.toMutableSet()
            if (state.canSelect(sessionId)) selected += sessionId
            state.copy(selectionMode = true, selectedSessionIds = selected)
        }
    }

    /**
     * Runs the toggleReportSelection operation.
     */
    fun toggleReportSelection(sessionId: String) {
        _uiState.update { state ->
            if (!state.canSelect(sessionId)) return@update state
            val selected = state.selectedSessionIds.toMutableSet()
            if (!selected.add(sessionId)) selected.remove(sessionId)
            state.copy(selectedSessionIds = selected)
        }
    }

    /**
     * Runs the setAllReportsSelected operation.
     */
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

    /**
     * Runs the clearSelection operation.
     */
    fun clearSelection() {
        _uiState.update { it.copy(selectionMode = false, selectedSessionIds = emptySet()) }
    }

    /**
     * Runs the deleteSelectedReports operation.
     */
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

    /**
     * Runs the clearMessage operation.
     */
    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    /**
     * Runs the ReportsUiState operation.
     */
    private fun ReportsUiState.canSelect(sessionId: String): Boolean =
        reports.firstOrNull { it.descriptor.sessionId == sessionId }?.isActiveReport == false
}

/**
 * Exposes the FbbReportDetails value.
 */
val FbbReportDetails.isActiveReport: Boolean
    get() = descriptor.active || descriptor.status == FbbReportStatus.ACTIVE
