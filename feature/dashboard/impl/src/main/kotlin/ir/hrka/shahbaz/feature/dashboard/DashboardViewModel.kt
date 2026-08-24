/** Coordinates lifecycle-scoped board telemetry with app-owned phone orientation state. */
package ir.hrka.shahbaz.feature.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ir.hrka.shahbaz.core.model.FlightPlan
import ir.hrka.shahbaz.hardwareconnection.BoardConnectionState
import ir.hrka.shahbaz.hardwareconnection.HardwareConnection
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private var boardStopDeferredForPermission = false
    private var usbPermissionRequestPending = false
    private var usbPermissionRequestReturnedToHost = false
    private var permissionResultBackgroundStopJob: Job? = null
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
                if (
                    usbPermissionRequestResolved(
                        requestPending = usbPermissionRequestPending,
                        connection = connection,
                        permissionRequiredIsTerminal = usbPermissionRequestReturnedToHost,
                    )
                ) {
                    usbPermissionRequestPending = false
                    usbPermissionRequestReturnedToHost = false
                    if (
                        shouldSchedulePermissionResultBackgroundStop(
                            hostForeground = hostForeground,
                            permissionStopDeferred = boardStopDeferredForPermission,
                        )
                    ) {
                        schedulePermissionResultBackgroundStop()
                    }
                }
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
        cancelPermissionResultBackgroundStop()
        boardStopDeferredForPermission = false
        usbPermissionRequestPending = false
        usbPermissionRequestReturnedToHost = false
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
        cancelPermissionResultBackgroundStop()
        hostForeground = true
        usbPermissionRequestReturnedToHost = permissionRequestReturnedToHostAfterForeground(
            alreadyReturned = usbPermissionRequestReturnedToHost,
            permissionStopDeferred = boardStopDeferredForPermission,
            requestPending = usbPermissionRequestPending,
        )
        boardStopDeferredForPermission = false
        if (mutableUiState.value.flightPlan != null) {
            startSources()
        }
    }

    /** Reconciles USB permission after transient system UI, including the permission dialog. */
    fun onHostResume() {
        if (hostForeground && mutableUiState.value.flightPlan != null) {
            board.refresh()
        }
    }

    /** Releases the external board whenever the host is no longer visible. */
    fun onHostBackground() {
        hostForeground = false
        val connection = mutableUiState.value.boardConnection
        if (
            usbPermissionRequestResolved(
                requestPending = usbPermissionRequestPending,
                connection = connection,
                permissionRequiredIsTerminal = usbPermissionRequestReturnedToHost,
            )
        ) {
            usbPermissionRequestPending = false
            usbPermissionRequestReturnedToHost = false
            boardStopDeferredForPermission = true
            schedulePermissionResultBackgroundStop()
            return
        }
        if (
            !shouldStopBoardForHostBackground(
                connection,
                usbPermissionRequestPending,
                boardStopDeferredForPermission,
            )
        ) {
            boardStopDeferredForPermission = true
            return
        }
        cancelPermissionResultBackgroundStop()
        boardStopDeferredForPermission = false
        stopSources()
    }

    fun requestUsbPermission() {
        // Set this before crossing dispatchers so an immediate Activity.onStop cannot unregister
        // the result receiver in the short interval before core publishes RequestingPermission.
        cancelPermissionResultBackgroundStop()
        usbPermissionRequestPending = true
        usbPermissionRequestReturnedToHost = false
        board.requestPermission()
    }

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

    private fun schedulePermissionResultBackgroundStop() {
        cancelPermissionResultBackgroundStop()
        permissionResultBackgroundStopJob = viewModelScope.launch {
            delay(PERMISSION_RESULT_FOREGROUND_GRACE_MILLIS)
            if (!hostForeground && boardStopDeferredForPermission) {
                boardStopDeferredForPermission = false
                stopSources()
            }
            permissionResultBackgroundStopJob = null
        }
    }

    private fun cancelPermissionResultBackgroundStop() {
        permissionResultBackgroundStopJob?.cancel()
        permissionResultBackgroundStopJob = null
    }
}

private const val PERMISSION_RESULT_FOREGROUND_GRACE_MILLIS = 3_000L

/** A USB permission PendingIntent must remain observable while Android's system prompt is active. */
internal fun shouldKeepBoardStartedForPermissionResult(
    connection: BoardConnectionState,
    requestPending: Boolean = false,
): Boolean = requestPending || connection is BoardConnectionState.RequestingPermission

/** True once a requested grant/denial has advanced beyond the permission gate states. */
internal fun usbPermissionRequestResolved(
    requestPending: Boolean,
    connection: BoardConnectionState,
    permissionRequiredIsTerminal: Boolean = false,
): Boolean = when {
    !requestPending -> false
    connection is BoardConnectionState.RequestingPermission -> false
    connection is BoardConnectionState.PermissionRequired -> permissionRequiredIsTerminal
    else -> true
}

/** A stopped host gets a bounded chance to return before the resolved client is released. */
internal fun shouldSchedulePermissionResultBackgroundStop(
    hostForeground: Boolean,
    permissionStopDeferred: Boolean,
): Boolean = !hostForeground && permissionStopDeferred

/** Foreground callbacks are idempotent and cannot erase a previously observed prompt return. */
internal fun permissionRequestReturnedToHostAfterForeground(
    alreadyReturned: Boolean,
    permissionStopDeferred: Boolean,
    requestPending: Boolean,
): Boolean = alreadyReturned || (permissionStopDeferred && requestPending)

/**
 * Once a permission prompt caused onStop, its result may arrive before onStart. Keep the client
 * alive through that result transition; onHostForeground clears the deferral, so a later ordinary
 * background transition still closes the established link.
 */
internal fun shouldStopBoardForHostBackground(
    connection: BoardConnectionState,
    requestPending: Boolean,
    permissionStopAlreadyDeferred: Boolean,
): Boolean = !permissionStopAlreadyDeferred &&
    !shouldKeepBoardStartedForPermissionResult(connection, requestPending)
