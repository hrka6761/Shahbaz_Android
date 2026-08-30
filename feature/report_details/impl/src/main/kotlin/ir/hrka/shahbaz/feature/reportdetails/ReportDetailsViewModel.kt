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

/**
 * Documents the ReportDetailsUiState type and the role it plays in this module.
 */
data class ReportDetailsUiState(
    /**
     * Exposes the sessionId value.
     */
    val sessionId: String? = null,
    /**
     * Exposes the report value.
     */
    val report: FbbReportDetails? = null,
    /**
     * Exposes the loadedReportText value.
     */
    val loadedReportText: String = "",
    /**
     * Exposes the nextReportOffsetBytes value.
     */
    val nextReportOffsetBytes: Long? = null,
    /**
     * Exposes the searchQuery value.
     */
    val searchQuery: String = "",
    /**
     * Exposes the searchMatches value.
     */
    val searchMatches: List<FbbReportSearchMatch> = emptyList(),
    /**
     * Exposes the busy value.
     */
    val busy: Boolean = false,
    /**
     * Exposes the message value.
     */
    val message: String? = null,
)

/**
 * Documents the ReportDetailsViewModel type and the role it plays in this module.
 */
class ReportDetailsViewModel(application: Application) : AndroidViewModel(application) {
    /**
     * Exposes the appContext value.
     */
    private val appContext: Application
        get() = getApplication()

    /**
     * Exposes the _uiState value.
     */
    private val _uiState = MutableStateFlow(ReportDetailsUiState())
    /**
     * Exposes the uiState value.
     */
    val uiState: StateFlow<ReportDetailsUiState> = _uiState

    /**
     * Runs the loadReportDetails operation.
     */
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

    /**
     * Runs the openReport operation.
     */
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

    /**
     * Runs the loadMoreReportText operation.
     */
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

    /**
     * Runs the searchReport operation.
     */
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

    /**
     * Runs the deleteReport operation.
     */
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

    /**
     * Runs the reportFile operation.
     */
    fun reportFile(sessionId: String): File? =
        FlightBlackBox.reports(appContext).getReportFile(sessionId)

    /**
     * Runs the showMessage operation.
     */
    fun showMessage(message: String) {
        _uiState.update { it.copy(message = message) }
    }

    /**
     * Runs the clearMessage operation.
     */
    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }
}

/**
 * Exposes the FbbReportDetails value.
 */
val FbbReportDetails.isActiveReport: Boolean
    get() = descriptor.active || descriptor.status == FbbReportStatus.ACTIVE
