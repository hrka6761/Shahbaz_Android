package ir.hrka.shahbaz.feature.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shahbaz.flightblackbox.FbbReportStorageStats
import com.shahbaz.flightblackbox.FlightBlackBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Documents the SettingsUiState type and the role it plays in this module.
 */
data class SettingsUiState(
    /**
     * Exposes the stats value.
     */
    val stats: FbbReportStorageStats = FbbReportStorageStats(
        reportCount = 0,
        activeReportCount = 0,
        totalBytes = 0L,
    ),
    /**
     * Exposes the errorCount value.
     */
    val errorCount: Int = 0,
    /**
     * Exposes the warningCount value.
     */
    val warningCount: Int = 0,
    /**
     * Exposes the busy value.
     */
    val busy: Boolean = false,
)

/**
 * Documents the SettingsViewModel type and the role it plays in this module.
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    /**
     * Exposes the appContext value.
     */
    private val appContext: Application
        get() = getApplication()

    /**
     * Exposes the _uiState value.
     */
    private val _uiState = MutableStateFlow(SettingsUiState())
    /**
     * Exposes the uiState value.
     */
    val uiState: StateFlow<SettingsUiState> = _uiState

    init {
        refresh()
    }

    /**
     * Runs the refresh operation.
     */
    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            val snapshot = withContext(Dispatchers.IO) {
                val reports = FlightBlackBox.reports(appContext)
                val details = reports.getAllReportDetails()
                SettingsSnapshot(
                    stats = reports.storageStats(),
                    errorCount = details.sumOf { it.errorCount },
                    warningCount = details.sumOf { it.warningCount },
                )
            }
            _uiState.update {
                it.copy(
                    stats = snapshot.stats,
                    errorCount = snapshot.errorCount,
                    warningCount = snapshot.warningCount,
                    busy = false,
                )
            }
        }
    }
}

/**
 * Documents the SettingsSnapshot type and the role it plays in this module.
 */
private data class SettingsSnapshot(
    /**
     * Exposes the stats value.
     */
    val stats: FbbReportStorageStats,
    /**
     * Exposes the errorCount value.
     */
    val errorCount: Int,
    /**
     * Exposes the warningCount value.
     */
    val warningCount: Int,
)
