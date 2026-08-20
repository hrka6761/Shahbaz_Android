/** Coordinates lifecycle-scoped board telemetry with app-owned phone orientation state. */
package ir.hrka.shahbaz.feature.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ir.hrka.shahbaz.core.model.FlightPlan
import ir.hrka.shahbaz.hardwareconnection.BoardConnectionState
import ir.hrka.shahbaz.hardwareconnection.HardwareConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Activity-scoped dashboard state holder following the existing MapViewModel convention. */
class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val board = HardwareConnection(appContext)
    private val mutableUiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = mutableUiState.asStateFlow()

    private var hostForeground = false
    private var baselineCaptureGate: TakeoffBaselineCaptureGate? = null

    init {
        viewModelScope.launch {
            board.connectionState.collect { connection ->
                baselineCaptureGate = when (connection) {
                    is BoardConnectionState.Ready -> takeoffBaselineCaptureGate(
                        connection = connection,
                        telemetry = mutableUiState.value.boardTelemetry,
                    )
                    else -> null
                }
                mutableUiState.update { it.copy(boardConnection = connection) }
            }
        }
        viewModelScope.launch {
            board.telemetry.collect { telemetry ->
                mutableUiState.update { current ->
                    current.copy(
                        boardTelemetry = telemetry,
                        baselinePressurePascal = eligibleTakeoffBaselinePressure(
                            establishedBaselinePascal = current.baselinePressurePascal,
                            hasFlightPlan = current.flightPlan != null,
                            connection = current.boardConnection,
                            gate = baselineCaptureGate,
                            telemetry = telemetry,
                        ),
                    )
                }
            }
        }
    }

    /** Installs a new immutable flight plan and resets only flight-relative derived state. */
    fun setFlightPlan(plan: FlightPlan) {
        val changed = mutableUiState.value.flightPlan != plan
        mutableUiState.update {
            it.copy(
                flightPlan = plan,
                baselinePressurePascal = if (changed) null else it.baselinePressurePascal,
            )
        }
        if (changed) {
            val current = mutableUiState.value
            baselineCaptureGate = takeoffBaselineCaptureGate(
                connection = current.boardConnection,
                telemetry = current.boardTelemetry,
            )
        }
        if (hostForeground) startSources()
    }

    /** Clears the plan when returning to setup and releases the external board connection. */
    fun clearFlightPlan() {
        baselineCaptureGate = null
        mutableUiState.update {
            DashboardUiState(
                phoneSensors = it.phoneSensors,
                isOnline = it.isOnline,
            )
        }
        stopSources()
    }

    /** Updates phone GPS/orientation data owned by the existing app-level location pipeline. */
    fun updatePhoneSensors(sensors: DashboardPhoneSensors, isOnline: Boolean) {
        mutableUiState.update { it.copy(phoneSensors = sensors, isOnline = isOnline) }
    }

    /** Marks the activity visible; sources start only after a valid flight plan exists. */
    fun onHostForeground() {
        hostForeground = true
        if (mutableUiState.value.flightPlan != null) {
            startSources()
        }
    }

    /** Releases the external board whenever the host is no longer visible. */
    fun onHostBackground() {
        hostForeground = false
        stopSources()
    }

    fun requestUsbPermission() = board.requestPermission()

    fun retryBoardConnection() = board.retry()

    fun setQnhHectopascal(value: Double) = board.setQnh(value)

    override fun onCleared() {
        board.close()
        super.onCleared()
    }

    private fun startSources() {
        board.start()
    }

    private fun stopSources() {
        board.stop()
    }
}
