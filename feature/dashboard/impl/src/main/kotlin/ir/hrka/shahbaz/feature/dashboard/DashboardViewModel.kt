/** Coordinates lifecycle-scoped board telemetry with app-owned phone orientation state. */
package ir.hrka.shahbaz.feature.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shahbaz.flightblackbox.FbbEventRef
import com.shahbaz.flightblackbox.FbbEventType
import com.shahbaz.flightblackbox.FbbPersistence
import com.shahbaz.flightblackbox.FlightBlackBox
import ir.hrka.shahbaz.autopilot.AutopilotLandingObservation
import ir.hrka.shahbaz.autopilot.AutopilotNavigationFix
import ir.hrka.shahbaz.autopilot.AutopilotPhase
import ir.hrka.shahbaz.autopilot.AutopilotSafetyStatus
import ir.hrka.shahbaz.core.model.FlightPlan
import ir.hrka.shahbaz.feature.dashboard.impl.BuildConfig
import ir.hrka.shahbaz.hardwareconnection.BoardConnectionState
import ir.hrka.shahbaz.hardwareconnection.BoardPulseBounds
import ir.hrka.shahbaz.hardwareconnection.HardwareConnection
import ir.hrka.shahbaz.hardwareconnection.HardwareConnectionConfig
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
    /**
     * Exposes the appContext value.
     */
    private val appContext = application.applicationContext
    /**
     * Exposes the board value.
     */
    private val board = HardwareConnection(
        appContext,
        HardwareConnectionConfig(
            allowActuatorCommands = BuildConfig.EXPERIMENTAL_PHYSICAL_ACTUATORS,
            motorPulseBounds = BoardPulseBounds(1_000, 2_000),
        ),
    )
    /**
     * Exposes the mutableUiState value.
     */
    private val mutableUiState = MutableStateFlow(DashboardUiState())
    /**
     * Exposes the uiState value.
     */
    val uiState: StateFlow<DashboardUiState> = mutableUiState.asStateFlow()

    /** Inputs supplied by independent navigation, landing, power, route, geofence, and wind owners. */
    private val missionInputs = MutableStateFlow(FlightMissionInputs())

    /** Current dashboard composition runtime; it borrows [board] and never owns its lifecycle. */
    private var missionRuntime: FlightMissionRuntime? = null

    /** Mirrors runtime state into the immutable dashboard presentation state. */
    private var missionStateJob: Job? = null

    /** Prevents Activity recreation/foregrounding from silently resetting a terminal mission. */
    private var missionRestartBlocked = false

    /**
     * Stores the mutable hostForeground value.
     */
    private var hostForeground = false
    /**
     * Stores the mutable boardStopDeferredForPermission value.
     */
    private var boardStopDeferredForPermission = false
    /**
     * Stores the mutable usbPermissionRequestPending value.
     */
    private var usbPermissionRequestPending = false
    /**
     * Stores the mutable usbPermissionRequestReturnedToHost value.
     */
    private var usbPermissionRequestReturnedToHost = false
    /**
     * Stores the mutable permissionResultBackgroundStopJob value.
     */
    private var permissionResultBackgroundStopJob: Job? = null
    /**
     * Stores the mutable baselineCaptureGate value.
     */
    private var baselineCaptureGate: TakeoffBaselineCaptureGate? = null
    /**
     * Stores the mutable lastBoardConnectionEvent value.
     */
    private var lastBoardConnectionEvent: FbbEventRef? = null
    /**
     * Stores the mutable lastUsbPermissionRequestEvent value.
     */
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
                        description = "USB permission request completed -> clear pending flags",
                        cause = lastBoardConnectionEvent,
                        metadata = mapOf(
                            "connection" to connection.fbbKind(),
                            "hostReturnObserved" to usbPermissionRequestReturnedToHost,
                            "hostForeground" to hostForeground,
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
    fun setFlightPlan(plan: FlightPlan, cause: FbbEventRef? = null) {
        val event = FlightBlackBox.record(
            type = FbbEventType.CALL,
            description = "DashboardViewModel.setFlightPlan()",
            cause = cause,
            metadata = mapOf("hostForeground" to hostForeground),
            persistence = FbbPersistence.IMPORTANT,
        )
        val changed = mutableUiState.value.flightPlan != plan
        if (changed && mutableUiState.value.mission?.phase.requiresControlledShutdown()) {
            FlightBlackBox.record(
                type = FbbEventType.WARNING,
                description = "Flight plan replacement rejected while mission is active",
                cause = event,
                metadata = mapOf("phase" to mutableUiState.value.mission?.phase),
                persistence = FbbPersistence.CRITICAL,
            )
            return
        }
        if (changed) {
            releaseMissionRuntime()
            missionRestartBlocked = false
            missionInputs.update { current ->
                FlightMissionInputs(
                    navigationFix = current.navigationFix,
                    navigationAltitudeAboveMeanSeaLevelMeters =
                        current.navigationAltitudeAboveMeanSeaLevelMeters,
                )
            }
        }
        mutableUiState.update {
            it.copy(
                flightPlan = plan,
                mission = if (changed) null else it.mission,
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
    fun clearFlightPlan(): Boolean {
        val clear = FlightBlackBox.record(
            type = FbbEventType.CALL,
            description = "DashboardViewModel.clearFlightPlan()",
            persistence = FbbPersistence.IMPORTANT,
        )
        if (mutableUiState.value.mission?.phase.requiresControlledShutdown()) {
            FlightBlackBox.record(
                type = FbbEventType.DECISION,
                description = "Active mission -> request controlled abort before clearing plan",
                cause = clear,
                metadata = mapOf("phase" to mutableUiState.value.mission?.phase),
                persistence = FbbPersistence.CRITICAL,
            )
            abortFlight()
            return false
        }
        cancelPermissionResultBackgroundStop()
        boardStopDeferredForPermission = false
        usbPermissionRequestPending = false
        usbPermissionRequestReturnedToHost = false
        baselineCaptureGate = null
        releaseMissionRuntime()
        missionRestartBlocked = false
        missionInputs.value = FlightMissionInputs()
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
        return true
    }

    /** Updates phone GPS/orientation data owned by the existing app-level location pipeline. */
    fun updatePhoneSensors(sensors: DashboardPhoneSensors, isOnline: Boolean) {
        mutableUiState.update { it.copy(phoneSensors = sensors, isOnline = isOnline) }
    }

    /** Supplies a real acquisition-timestamped navigation fix; null immediately removes it. */
    fun updateNavigationFix(
        fix: AutopilotNavigationFix?,
        altitudeAboveMeanSeaLevelMeters: Double? = null,
    ) {
        missionInputs.update {
            it.copy(
                navigationFix = fix,
                navigationAltitudeAboveMeanSeaLevelMeters =
                    if (fix == null) null else altitudeAboveMeanSeaLevelMeters,
            )
        }
    }

    /** Supplies independent touchdown state. Pressure altitude is never converted into this input. */
    fun updateLandingObservation(observation: AutopilotLandingObservation) {
        missionInputs.update { it.copy(landingObservation = observation) }
    }

    /** Supplies current decisions from route, landing-zone, energy, geofence, and wind owners. */
    fun updateSafetyStatus(status: AutopilotSafetyStatus) {
        missionInputs.update { it.copy(safetyStatus = status) }
    }

    /** Dispatches operator intent to policy; the UI never arms the board directly. */
    fun startFlight() {
        FlightBlackBox.record(
            type = FbbEventType.USER,
            description = "DashboardScreen.StartFlight clicked -> autopilot preflight",
            persistence = FbbPersistence.IMPORTANT,
        )
        if (!missionRestartBlocked) missionRuntime?.startMission()
    }

    /** Requests the autopilot's controlled return/landing path. */
    fun abortFlight() {
        FlightBlackBox.record(
            type = FbbEventType.USER,
            description = "DashboardScreen.AbortFlight clicked",
            persistence = FbbPersistence.CRITICAL,
        )
        missionRuntime?.abortMission()
    }

    /** Sends the immediate hardware override and latches emergency stop in policy/control. */
    fun emergencyStop() {
        FlightBlackBox.record(
            type = FbbEventType.USER,
            description = "DashboardScreen.EmergencyStop clicked",
            persistence = FbbPersistence.CRITICAL,
        )
        if (missionRuntime?.emergencyStop() != true) {
            board.emergencyStopActuators()
        }
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
        if (usbPermissionRequestPending && !usbPermissionRequestReturnedToHost) {
            usbPermissionRequestReturnedToHost = true
            FlightBlackBox.record(
                type = FbbEventType.STATE,
                description = "USB permission prompt returned to dashboard host",
                cause = event,
                metadata = mapOf(
                    "connection" to mutableUiState.value.boardConnection.fbbKind(),
                    "hostForeground" to hostForeground,
                ),
                persistence = FbbPersistence.IMPORTANT,
            )
        }
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
        val phaseBeforeBackground = mutableUiState.value.mission?.phase
        if (phaseBeforeBackground.requiresControlledShutdown()) {
            missionRestartBlocked = true
            missionRuntime?.emergencyStop()
            mutableUiState.update { current ->
                current.copy(
                    mission = current.mission?.copy(phase = AutopilotPhase.EMERGENCY_STOPPED),
                )
            }
            FlightBlackBox.record(
                type = FbbEventType.WARNING,
                description = "Active mission lost foreground -> emergency stop and block restart",
                cause = event,
                metadata = mapOf("phase" to phaseBeforeBackground),
                persistence = FbbPersistence.CRITICAL,
            )
        } else if (phaseBeforeBackground.isTerminalMissionPhase()) {
            missionRestartBlocked = true
        }
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

    /**
     * Runs the requestUsbPermission operation.
     */
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

    /**
     * Runs the retryBoardConnection operation.
     */
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

    /**
     * Runs the setQnhHectopascal operation.
     */
    fun setQnhHectopascal(value: Double) = board.setQnh(value)

    /**
     * Runs the onCleared operation.
     */
    override fun onCleared() {
        FlightBlackBox.record(
            type = FbbEventType.LIFECYCLE,
            description = "DashboardViewModel.onCleared()",
            persistence = FbbPersistence.IMPORTANT,
        )
        releaseMissionRuntime()
        board.close()
    }

    /**
     * Runs the startSources operation.
     */
    private fun startSources(cause: FbbEventRef?) {
        FlightBlackBox.record(
            type = FbbEventType.CALL,
            description = "HardwareConnection.start()",
            cause = cause,
        )
        board.start()
        ensureMissionRuntime()
    }

    /**
     * Runs the stopSources operation.
     */
    private fun stopSources(cause: FbbEventRef?) {
        FlightBlackBox.record(
            type = FbbEventType.CALL,
            description = "HardwareConnection.stop()",
            cause = cause,
        )
        releaseMissionRuntime()
        board.stop()
    }

    /** Creates exactly one serialized mission runtime for the installed plan and starts preflight sampling. */
    private fun ensureMissionRuntime() {
        if (missionRuntime != null || missionRestartBlocked) return
        val plan = mutableUiState.value.flightPlan ?: return
        val runtime = FlightMissionRuntime(
            context = appContext,
            flightPlan = plan,
            board = board,
            inputs = missionInputs,
        )
        missionRuntime = runtime
        mutableUiState.update { it.copy(mission = runtime.state.value.autopilot) }
        missionStateJob = viewModelScope.launch {
            runtime.state.collect { runtimeState ->
                if (missionRuntime === runtime) {
                    mutableUiState.update { it.copy(mission = runtimeState.autopilot) }
                }
            }
        }
        if (!runtime.prepare()) {
            FlightBlackBox.record(
                type = FbbEventType.ERROR,
                description = "FlightMissionRuntime.prepare() failed",
                persistence = FbbPersistence.CRITICAL,
            )
        }
    }

    /** Stops the owned controller/sensor composition without closing the borrowed board. */
    private fun releaseMissionRuntime() {
        missionStateJob?.cancel()
        missionStateJob = null
        missionRuntime?.close()
        missionRuntime = null
    }

    /**
     * Runs the schedulePermissionResultBackgroundStop operation.
     */
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

    /**
     * Runs the cancelPermissionResultBackgroundStop operation.
     */
    private fun cancelPermissionResultBackgroundStop() {
        permissionResultBackgroundStopJob?.cancel()
        permissionResultBackgroundStopJob = null
    }
}

/**
 * Exposes the PERMISSION_RESULT_FOREGROUND_GRACE_MILLIS value.
 */
private const val PERMISSION_RESULT_FOREGROUND_GRACE_MILLIS = 3_000L

/** Phases in which replacing or discarding the plan must first complete a safe shutdown path. */
internal fun AutopilotPhase?.requiresControlledShutdown(): Boolean = when (this) {
    AutopilotPhase.ARMING,
    AutopilotPhase.TAKEOFF,
    AutopilotPhase.CRUISE,
    AutopilotPhase.LANDING,
    AutopilotPhase.RETURN_CLIMB,
    AutopilotPhase.RETURNING,
    AutopilotPhase.DISARMING,
    -> true
    AutopilotPhase.STANDBY,
    AutopilotPhase.PREFLIGHT,
    AutopilotPhase.COMPLETED,
    AutopilotPhase.ABORTED,
    AutopilotPhase.FAILED,
    AutopilotPhase.EMERGENCY_STOPPED,
    null,
    -> false
}

/** A terminal result remains visible until the operator explicitly clears the plan. */
internal fun AutopilotPhase?.isTerminalMissionPhase(): Boolean = this in setOf(
    AutopilotPhase.COMPLETED,
    AutopilotPhase.ABORTED,
    AutopilotPhase.FAILED,
    AutopilotPhase.EMERGENCY_STOPPED,
)

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

/**
 * Runs the BoardConnectionState operation.
 */
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

/**
 * Runs the BoardConnectionState operation.
 */
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

/**
 * Runs the ir operation.
 */
private fun ir.hrka.shahbaz.hardwareconnection.BoardUsbDevice.fbbMetadata(): Map<String, Any?> =
    mapOf(
        "deviceId" to deviceId,
        "vid" to "0x${vendorId.toString(16)}",
        "pid" to "0x${productId.toString(16)}",
    )
