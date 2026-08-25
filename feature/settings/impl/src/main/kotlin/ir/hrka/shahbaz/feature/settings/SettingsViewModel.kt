package ir.hrka.shahbaz.feature.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shahbaz.flightblackbox.FbbConfig
import com.shahbaz.flightblackbox.FbbDurabilityMode
import com.shahbaz.flightblackbox.FbbEventType
import com.shahbaz.flightblackbox.FbbPersistence
import com.shahbaz.flightblackbox.FbbReportDetails
import com.shahbaz.flightblackbox.FbbReportSearchMatch
import com.shahbaz.flightblackbox.FbbReportStatus
import com.shahbaz.flightblackbox.FbbReportStorageStats
import com.shahbaz.flightblackbox.FbbTraceLevel
import com.shahbaz.flightblackbox.FlightBlackBox
import com.shahbaz.flightblackbox.FlightBlackBoxReports
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val reports: List<FbbReportDetails> = emptyList(),
    val stats: FbbReportStorageStats = FbbReportStorageStats(
        reportCount = 0,
        activeReportCount = 0,
        totalBytes = 0L,
    ),
    val config: FbbConfig = FbbConfig(),
    val selectedSessionIds: Set<String> = emptySet(),
    val loadedReportSessionId: String? = null,
    val loadedReportText: String = "",
    val nextReportOffsetBytes: Long? = null,
    val searchQuery: String = "",
    val searchMatches: List<FbbReportSearchMatch> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext: Application
        get() = getApplication()

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            val snapshot = withContext(Dispatchers.IO) {
                val repository = FlightBlackBox.reports(appContext)
                Triple(
                    repository.getAllReportDetails(),
                    repository.storageStats(),
                    FlightBlackBox.configuration(appContext).read(),
                )
            }
            _uiState.update { state ->
                state.copy(
                    reports = snapshot.first,
                    stats = snapshot.second,
                    config = snapshot.third,
                    selectedSessionIds = state.selectedSessionIds
                        .intersect(snapshot.first.map { it.descriptor.sessionId }.toSet()),
                    busy = false,
                )
            }
        }
    }

    fun toggleReportSelection(sessionId: String) {
        _uiState.update { state ->
            val selected = state.selectedSessionIds.toMutableSet()
            if (!selected.add(sessionId)) selected.remove(sessionId)
            state.copy(selectedSessionIds = selected)
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedSessionIds = emptySet()) }
    }

    fun openReport(sessionId: String) {
        viewModelScope.launch {
            val chunk = withContext(Dispatchers.IO) {
                FlightBlackBox.reports(appContext).readReportChunk(sessionId)
            } ?: return@launch showMessage("Report file is no longer available")
            _uiState.update {
                it.copy(
                    loadedReportSessionId = sessionId,
                    loadedReportText = chunk.text,
                    nextReportOffsetBytes = chunk.nextOffsetBytes,
                    searchQuery = "",
                    searchMatches = emptyList(),
                )
            }
            FlightBlackBox.record(
                type = FbbEventType.UI,
                description = "Settings report viewer opened",
                metadata = mapOf("sessionId" to sessionId),
            )
        }
    }

    fun loadMoreReportText() {
        val state = _uiState.value
        val sessionId = state.loadedReportSessionId ?: return
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
        val sessionId = _uiState.value.loadedReportSessionId ?: return
        _uiState.update { it.copy(searchQuery = query) }
        viewModelScope.launch {
            val matches = withContext(Dispatchers.IO) {
                FlightBlackBox.reports(appContext).searchReport(sessionId, query, maxMatches = 50)
            }
            _uiState.update { it.copy(searchMatches = matches) }
        }
    }

    fun deleteReport(sessionId: String) {
        runReportMutation(
            eventDescription = "Settings deleted one flight black box report",
            metadata = mapOf("sessionId" to sessionId),
            successMessage = "Report deleted",
        ) {
            repository -> if (repository.deleteReport(sessionId)) 1 else 0
        }
    }

    fun deleteSelectedReports() {
        val selected = _uiState.value.selectedSessionIds
        if (selected.isEmpty()) return
        runReportMutation(
            eventDescription = "Settings deleted selected flight black box reports",
            metadata = mapOf("count" to selected.size),
            successMessage = "${selected.size} selected report(s) deleted",
        ) {
            repository -> repository.deleteReports(selected)
        }
    }

    fun deleteAllReports() {
        runReportMutation(
            eventDescription = "Settings deleted all deletable flight black box reports",
            successMessage = "Deletable reports deleted",
        ) {
            repository -> repository.deleteAllReports()
        }
    }

    fun cleanupOlderThan(days: Int) {
        val millis = days * 24L * 60L * 60L * 1_000L
        runReportMutation(
            eventDescription = "Settings cleaned up old flight black box reports",
            metadata = mapOf("olderThanDays" to days),
            successMessage = "Old reports cleaned up",
        ) {
            repository -> repository.deleteReportsOlderThan(olderThanMillis = millis)
        }
    }

    fun cleanupToMaxStorageBytes(maxBytes: Long) {
        runReportMutation(
            eventDescription = "Settings cleaned up flight black box report storage",
            metadata = mapOf("maxBytes" to maxBytes),
            successMessage = "Report storage cleaned up",
        ) {
            repository -> repository.cleanupToMaxStorageBytes(maxBytes = maxBytes)
        }
    }

    fun setTraceLevel(level: FbbTraceLevel) {
        val updated = FlightBlackBox.configuration(appContext).update { copy(traceLevel = level) }
        _uiState.update { it.copy(config = updated, message = "Trace level saved for next launch") }
        recordConfigChange("traceLevel", level.name)
    }

    fun setDurabilityMode(mode: FbbDurabilityMode) {
        val updated = FlightBlackBox.configuration(appContext).update { copy(durabilityMode = mode) }
        _uiState.update { it.copy(config = updated, message = "Durability mode saved for next launch") }
        recordConfigChange("durabilityMode", mode.name)
    }

    fun resetConfiguration() {
        val updated = FlightBlackBox.configuration(appContext).reset()
        _uiState.update { it.copy(config = updated, message = "Recorder configuration reset") }
        recordConfigChange("reset", "true")
    }

    fun reportFile(sessionId: String): File? =
        FlightBlackBox.reports(appContext).getReportFile(sessionId)

    fun showMessage(message: String) {
        _uiState.update { it.copy(message = message) }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private fun runReportMutation(
        eventDescription: String,
        metadata: Map<String, Any?> = emptyMap(),
        successMessage: String,
        block: (FlightBlackBoxReports) -> Int,
    ) {
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                block(FlightBlackBox.reports(appContext))
            }
            FlightBlackBox.record(
                type = FbbEventType.USER,
                description = eventDescription,
                metadata = metadata + mapOf("deleted" to deleted),
                persistence = FbbPersistence.IMPORTANT,
            )
            _uiState.update {
                it.copy(
                    selectedSessionIds = emptySet(),
                    loadedReportSessionId = it.loadedReportSessionId.takeUnless { deleted > 0 },
                    loadedReportText = if (deleted > 0) "" else it.loadedReportText,
                    nextReportOffsetBytes = if (deleted > 0) null else it.nextReportOffsetBytes,
                    message = if (deleted > 0) successMessage else "No reports were deleted",
                )
            }
            refresh()
        }
    }

    private fun recordConfigChange(name: String, value: String) {
        FlightBlackBox.record(
            type = FbbEventType.USER,
            description = "Settings changed flight black box configuration",
            metadata = mapOf(name to value),
            persistence = FbbPersistence.IMPORTANT,
        )
    }
}

val FbbReportDetails.isActiveReport: Boolean
    get() = descriptor.active || descriptor.status == FbbReportStatus.ACTIVE
