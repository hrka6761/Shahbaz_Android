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

data class SettingsUiState(
    val stats: FbbReportStorageStats = FbbReportStorageStats(
        reportCount = 0,
        activeReportCount = 0,
        totalBytes = 0L,
    ),
    val errorCount: Int = 0,
    val warningCount: Int = 0,
    val busy: Boolean = false,
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

private data class SettingsSnapshot(
    val stats: FbbReportStorageStats,
    val errorCount: Int,
    val warningCount: Int,
)
