/** Coordinates lifecycle-scoped board telemetry with app-owned phone orientation state. */
package ir.hrka.shahbaz.feature.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shahbaz.flightblackbox.FbbEventRef
import com.shahbaz.flightblackbox.FbbEventType
import com.shahbaz.flightblackbox.FbbPersistence
import com.shahbaz.flightblackbox.FlightBlackBox
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
    private var lastBoardConnectionEvent: FbbEventRef? = null
    private var lastUsbPermissionRequestEvent: FbbEventRef? = null

    init {
        FlightBlackBox.record(
            type = FbbEventType.APP,
            description = "DashboardViewModel initialized",
            metadata = mapOf("boardConnection" to mutableUiState.value.boardConnection.fbbKind()),
            persistence = FbbPersistence.IMPORTANT,
        )
        viewModelScope.launch {
            board.connectionState.collect { connection ->
                val previous = mutableUiState.value.boardConnection
                baselineCaptureGate = when (connection) {
                    is BoardConnectionState.Ready -> takeoffBaselineCaptureGate(
                        connection = connection,
                        telemetry = mutableUiState.value.boardTelemetry,
                    )
                    else -> null
                }
                mutableUiState.update { it.copy(boardConnection = connection) }
                lastBoardConnectionEvent = FlightBlackBox.record(
                    type = if (connection is BoardConnectionState.Failed) {
                        FbbEventType.ERROR
                    } else {
                        FbbEventType.STATE
                    },
                    description = "boardConnection: ${previous.fbbKind()} -> ${connection.fbbKind()}",
                    cause = lastUsbPermissionRequestEvent,
                    metadata = connection.fbbMetadata(),
                    persistence = if (connection is BoardConnectionState.Failed) {
                        FbbPersistence.CRITICAL
                    } else {
                        FbbPersistence.IMPORTANT
                    },
                )
                if (
                    usbPermissionRequestResolved(
                        requestPending = usbPermissionRequestPending,
                        connection = connection,
                        permissionRequiredIsTerminal = usbPermissionRequestReturnedToHost,
                    )
                ) {
                    val resolved = FlightBlackBox.record(
                        type = FbbEventType.DECISION,
                        description = "USB permission request resolved -> clear pending flags",
                        cause = lastBoardConnectionEvent,
                        metadata = mapOf(
                            "connection" to connection.fbbKind(),
                            "returnedToHost" to usbPermissionRequestReturnedToHost,
                        ),
                        persistence = FbbPersistence.IMPORTANT,
                    )
                    usbPermissionRequestPending = false
                    usbPermissionRequestReturnedToHost = false
                    if (
                        shouldSchedulePermissionResultBackgroundStop(
                            hostForeground = hostForeground,
                            permissionStopDeferred = boardStopDeferredForPermission,
                        )
                    ) {
                        FlightBlackBox.record(
                            type = FbbEventType.DECISION,
                            description = "host background with deferred USB permission result -> schedule delayed stop",
                            cause = resolved,
                            persistence = FbbPersistence.IMPORTANT,
                        )
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
        val event = FlightBlackBox.record(
            type = FbbEventType.CALL,
            description = "DashboardViewModel.setFlightPlan()",
            metadata = mapOf("hostForeground" to hostForeground),
            persistence = FbbPersistence.IMPORTANT,
        )
        val changed = mutableUiState.value.flightPlan != plan
        mutableUiState.update {
            it.copy(
                flightPlan = plan,
                baselinePressurePascal = if (changed) null else it.baselinePressurePascal,
            )
        }
        if (changed) {
            FlightBlackBox.record(
                type = FbbEventType.STATE,
                description = "flightPlan: null-or-previous -> active",
                cause = event,
                persistence = FbbPersistence.IMPORTANT,
            )
            val current = mutableUiState.value
            baselineCaptureGate = takeoffBaselineCaptureGate(
                connection = current.boardConnection,
                telemetry = current.boardTelemetry,
            )
        }
        if (hostForeground) {
            val decision = FlightBlackBox.record(
                type = FbbEventType.DECISION,
                description = "hostForeground=true -> start board sources",
                cause = event,
                persistence = FbbPersistence.IMPORTANT,
            )
            startSources(decision)
        }
    }

    /** Clears the plan when returning to setup and releases the external board connection. */
    fun clearFlightPlan() {
        val clear = FlightBlackBox.record(
            type = FbbEventType.CALL,
            description = "DashboardViewModel.clearFlightPlan()",
            persistence = FbbPersistence.IMPORTANT,
        )
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
        FlightBlackBox.record(
            type = FbbEventType.STATE,
            description = "flightPlan: active-or-null -> null",
            cause = clear,
            persistence = FbbPersistence.IMPORTANT,
        )
        stopSources(clear)
    }

    /** Updates phone GPS/orientation data owned by the existing app-level location pipeline. */
    fun updatePhoneSensors(sensors: DashboardPhoneSensors, isOnline: Boolean) {
        mutableUiState.update { it.copy(phoneSensors = sensors, isOnline = isOnline) }
    }

    /** Marks the activity visible; sources start only after a valid flight plan exists. */
    fun onHostForeground() {
        val event = FlightBlackBox.record(
            type = FbbEventType.CALL,
            description = "DashboardViewModel.onHostForeground()",
            persistence = FbbPersistence.IMPORTANT,
        )
        cancelPermissionResultBackgroundStop()
        hostForeground = true
        FlightBlackBox.record(
            type = FbbEventType.STATE,
            description = "DashboardViewModel.hostForeground: false -> true",
            cause = event,
        )
        usbPermissionRequestReturnedToHost = permissionRequestReturnedToHostAfterForeground(
            alreadyReturned = usbPermissionRequestReturnedToHost,
            permissionStopDeferred = boardStopDeferredForPermission,
            requestPending = usbPermissionRequestPending,
        )
        boardStopDeferredForPermission = false
        if (mutableUiState.value.flightPlan != null) {
            val decision = FlightBlackBox.record(
                type = FbbEventType.DECISION,
                description = "flightPlan present -> start board sources",
                cause = event,
                persistence = FbbPersistence.IMPORTANT,
            )
            startSources(decision)
        }
    }

    /** Reconciles USB permission after transient system UI, including the permission dialog. */
    fun onHostResume() {
        val event = FlightBlackBox.record(
            type = FbbEventType.CALL,
            description = "DashboardViewModel.onHostResume()",
            persistence = FbbPersistence.IMPORTANT,
        )
        if (hostForeground && mutableUiState.value.flightPlan != null) {
            FlightBlackBox.record(
                type = FbbEventType.DECISION,
                description = "hostForeground=true && flightPlan present -> board.refresh()",
                cause = event,
            )
            board.refresh()
        }
    }

    /** Releases the external board whenever the host is no longer visible. */
    fun onHostBackground() {
        val event = FlightBlackBox.record(
            type = FbbEventType.CALL,
            description = "DashboardViewModel.onHostBackground()",
            persistence = FbbPersistence.IMPORTANT,
        )
        hostForeground = false
        FlightBlackBox.record(
            type = FbbEventType.STATE,
            description = "DashboardViewModel.hostForeground: true -> false",
            cause = event,
        )
        val connection = mutableUiState.value.boardConnection
        if (
            usbPermissionRequestResolved(
                requestPending = usbPermissionRequestPending,
                connection = connection,
                permissionRequiredIsTerminal = usbPermissionRequestReturnedToHost,
            )
        ) {
            FlightBlackBox.record(
                type = FbbEventType.DECISION,
                description = "USB permission result resolved while backgrounding -> defer stop",
                cause = event,
                metadata = mapOf("connection" to connection.fbbKind()),
                persistence = FbbPersistence.IMPORTANT,
            )
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
            FlightBlackBox.record(
                type = FbbEventType.DECISION,
                description = "USB permission prompt active -> keep board sources temporarily",
                cause = event,
                metadata = mapOf("connection" to connection.fbbKind()),
                persistence = FbbPersistence.IMPORTANT,
            )
            boardStopDeferredForPermission = true
            return
        }
        cancelPermissionResultBackgroundStop()
        boardStopDeferredForPermission = false
        stopSources(event)
    }

    fun requestUsbPermission() {
        val request = FlightBlackBox.record(
            type = FbbEventType.USER,
            description = "DashboardScreen.GrantUsbPermission clicked",
            metadata = mapOf("connection" to mutableUiState.value.boardConnection.fbbKind()),
            persistence = FbbPersistence.IMPORTANT,
        )
        lastUsbPermissionRequestEvent = request
        // Set this before crossing dispatchers so an immediate Activity.onStop cannot unregister
        // the result receiver in the short interval before core publishes RequestingPermission.
        cancelPermissionResultBackgroundStop()
        usbPermissionRequestPending = true
        usbPermissionRequestReturnedToHost = false
        board.requestPermission()
    }

    fun retryBoardConnection() {
        val retry = FlightBlackBox.record(
            type = FbbEventType.USER,
            description = "DashboardScreen.RetryBoardConnection clicked",
            persistence = FbbPersistence.IMPORTANT,
        )
        board.retry()
        FlightBlackBox.record(
            type = FbbEventType.CALL,
            description = "HardwareConnection.retry()",
            cause = retry,
        )
    }

    fun setQnhHectopascal(value: Double) = board.setQnh(value)

    override fun onCleared() {
        FlightBlackBox.record(
            type = FbbEventType.LIFECYCLE,
            description = "DashboardViewModel.onCleared()",
            persistence = FbbPersistence.IMPORTANT,
        )
        board.close()
        super.onCleared()
    }

    private fun startSources(cause: FbbEventRef?) {
        FlightBlackBox.record(
            type = FbbEventType.CALL,
            description = "HardwareConnection.start()",
            cause = cause,
        )
        board.start()
    }

    private fun stopSources(cause: FbbEventRef?) {
        FlightBlackBox.record(
            type = FbbEventType.CALL,
            description = "HardwareConnection.stop()",
            cause = cause,
        )
        board.stop()
    }

    private fun schedulePermissionResultBackgroundStop() {
        cancelPermissionResultBackgroundStop()
        permissionResultBackgroundStopJob = viewModelScope.launch {
            delay(PERMISSION_RESULT_FOREGROUND_GRACE_MILLIS)
            if (!hostForeground && boardStopDeferredForPermission) {
                boardStopDeferredForPermission = false
                val timeout = FlightBlackBox.record(
                    type = FbbEventType.TIMEOUT,
                    description = "USB permission result foreground grace expired -> stop board sources",
                    persistence = FbbPersistence.IMPORTANT,
                )
                stopSources(timeout)
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

private fun BoardConnectionState.fbbKind(): String = when (this) {
    BoardConnectionState.Stopped -> "Stopped"
    BoardConnectionState.Searching -> "Searching"
    is BoardConnectionState.PermissionRequired -> "PermissionRequired"
    is BoardConnectionState.RequestingPermission -> "RequestingPermission"
    is BoardConnectionState.Opening -> "Opening"
    is BoardConnectionState.Synchronizing -> "Synchronizing"
    is BoardConnectionState.ValidatingDevice -> "ValidatingDevice"
    is BoardConnectionState.AwaitingHeartbeat -> "AwaitingHeartbeat"
    is BoardConnectionState.StartingTelemetry -> "StartingTelemetry"
    is BoardConnectionState.Ready -> "Ready"
    is BoardConnectionState.Disconnected -> "Disconnected"
    is BoardConnectionState.Failed -> "Failed"
}

private fun BoardConnectionState.fbbMetadata(): Map<String, Any?> = when (this) {
    is BoardConnectionState.PermissionRequired -> device.fbbMetadata()
    is BoardConnectionState.RequestingPermission -> device.fbbMetadata()
    is BoardConnectionState.Opening -> device.fbbMetadata()
    is BoardConnectionState.Synchronizing -> device.fbbMetadata()
    is BoardConnectionState.ValidatingDevice -> device.fbbMetadata()
    is BoardConnectionState.AwaitingHeartbeat -> device.fbbMetadata() + mapOf(
        "target" to deviceInfo.target,
        "protocol" to deviceInfo.protocolVersion,
    )
    is BoardConnectionState.StartingTelemetry -> device.fbbMetadata() + mapOf(
        "target" to deviceInfo.target,
        "protocol" to deviceInfo.protocolVersion,
    )
    is BoardConnectionState.Ready -> device.fbbMetadata() + mapOf(
        "target" to deviceInfo.target,
        "protocol" to deviceInfo.protocolVersion,
        "connectedAtMs" to connectedAtElapsedRealtimeMillis,
    )
    is BoardConnectionState.Disconnected -> mapOf("reason" to reason)
    is BoardConnectionState.Failed -> mapOf(
        "code" to error.code,
        "recoverable" to error.recoverable,
        "message" to error.message,
    )
    BoardConnectionState.Stopped,
    BoardConnectionState.Searching -> emptyMap()
}

private fun ir.hrka.shahbaz.hardwareconnection.BoardUsbDevice.fbbMetadata(): Map<String, Any?> =
    mapOf(
        "deviceId" to deviceId,
        "vid" to "0x${vendorId.toString(16)}",
        "pid" to "0x${productId.toString(16)}",
    )
