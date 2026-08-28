package ir.hrka.shahbaz.feature.reportdetails

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shahbaz.flightblackbox.FbbEventType
import com.shahbaz.flightblackbox.FbbPersistence
import com.shahbaz.flightblackbox.FbbReportDetails
import com.shahbaz.flightblackbox.FbbReportSearchMatch
import com.shahbaz.flightblackbox.FbbReportStatus
import com.shahbaz.flightblackbox.FlightBlackBox
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ReportDetailsUiState(
    val sessionId: String? = null,
    val report: FbbReportDetails? = null,
    val loadedReportText: String = "",
    val nextReportOffsetBytes: Long? = null,
    val searchQuery: String = "",
    val searchMatches: List<FbbReportSearchMatch> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
)

class ReportDetailsViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext: Application
        get() = getApplication()

    private val _uiState = MutableStateFlow(ReportDetailsUiState())
    val uiState: StateFlow<ReportDetailsUiState> = _uiState

    fun loadReportDetails(sessionId: String) {
        viewModelScope.launch {
            val previousSessionId = _uiState.value.sessionId
            _uiState.update { it.copy(sessionId = sessionId, busy = true) }
            val report = withContext(Dispatchers.IO) {
                FlightBlackBox.reports(appContext).getReportDetails(sessionId)
            }
            val sameSession = previousSessionId == sessionId
            _uiState.update {
                it.copy(
                    report = report,
                    busy = false,
                    loadedReportText = if (sameSession) it.loadedReportText else "",
                    nextReportOffsetBytes = if (sameSession) it.nextReportOffsetBytes else null,
                    searchQuery = if (sameSession) it.searchQuery else "",
                    searchMatches = if (sameSession) it.searchMatches else emptyList(),
                )
            }
        }
    }

    fun openReport(sessionId: String) {
        viewModelScope.launch {
            val chunk = withContext(Dispatchers.IO) {
                FlightBlackBox.reports(appContext).readReportChunk(sessionId)
            } ?: return@launch showMessage("Report file is no longer available")
            _uiState.update {
                it.copy(
                    loadedReportText = chunk.text,
                    nextReportOffsetBytes = chunk.nextOffsetBytes,
                    searchQuery = "",
                    searchMatches = emptyList(),
                )
            }
            FlightBlackBox.record(
                type = FbbEventType.UI,
                description = "Report details viewer opened",
                metadata = mapOf("sessionId" to sessionId),
            )
        }
    }

    fun loadMoreReportText() {
        val state = _uiState.value
        val sessionId = state.sessionId ?: return
        val nextOffset = state.nextReportOffsetBytes ?: return
        viewModelScope.launch {
            val chunk = withContext(Dispatchers.IO) {
                FlightBlackBox.reports(appContext).readReportChunk(
                    sessionId = sessionId,
                    offsetBytes = nextOffset,
                )
            } ?: return@launch showMessage("Report file is no longer available")
            _uiState.update {
                it.copy(
                    loadedReportText = it.loadedReportText + chunk.text,
                    nextReportOffsetBytes = chunk.nextOffsetBytes,
                )
            }
        }
    }

    fun searchReport(query: String) {
        val sessionId = _uiState.value.sessionId ?: return
        _uiState.update { it.copy(searchQuery = query) }
        viewModelScope.launch {
            val matches = withContext(Dispatchers.IO) {
                FlightBlackBox.reports(appContext).searchReport(sessionId, query, maxMatches = 50)
            }
            _uiState.update { it.copy(searchMatches = matches) }
        }
    }

    fun deleteReport(sessionId: String) {
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                if (FlightBlackBox.reports(appContext).deleteReport(sessionId)) 1 else 0
            }
            FlightBlackBox.record(
                type = FbbEventType.USER,
                description = "Report details deleted one flight black box report",
                metadata = mapOf("sessionId" to sessionId, "deleted" to deleted),
                persistence = FbbPersistence.IMPORTANT,
            )
            _uiState.update {
                it.copy(
                    report = if (deleted > 0) null else it.report,
                    loadedReportText = if (deleted > 0) "" else it.loadedReportText,
                    nextReportOffsetBytes = if (deleted > 0) null else it.nextReportOffsetBytes,
                    message = if (deleted > 0) "Report deleted" else "No reports were deleted",
                )
            }
        }
    }

    fun reportFile(sessionId: String): File? =
        FlightBlackBox.reports(appContext).getReportFile(sessionId)

    fun showMessage(message: String) {
        _uiState.update { it.copy(message = message) }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }
}

val FbbReportDetails.isActiveReport: Boolean
    get() = descriptor.active || descriptor.status == FbbReportStatus.ACTIVE
