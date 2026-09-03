package ir.hrka.shahbaz.feature.dashboard

import android.content.Context
import android.os.SystemClock
import ir.hrka.shahbaz.autopilot.Autopilot
import ir.hrka.shahbaz.autopilot.AutopilotConfig
import ir.hrka.shahbaz.autopilot.AutopilotGroundRangeObservation
import ir.hrka.shahbaz.autopilot.AutopilotInput
import ir.hrka.shahbaz.autopilot.AutopilotLandingObservation
import ir.hrka.shahbaz.autopilot.AutopilotNavigationFix
import ir.hrka.shahbaz.autopilot.AutopilotRequest
import ir.hrka.shahbaz.autopilot.AutopilotSafetyStatus
import ir.hrka.shahbaz.autopilot.AutopilotSnapshot
import ir.hrka.shahbaz.core.model.FlightPlan
import ir.hrka.shahbaz.flightcontracts.ControlTargetStatus as ContractTargetStatus
import ir.hrka.shahbaz.flightcontracts.ControlTracking as ContractTracking
import ir.hrka.shahbaz.flightcontracts.FlightControlCommand as ContractFlightControlCommand
import ir.hrka.shahbaz.flightcontracts.FlightControllerArmingState as ContractArmingState
import ir.hrka.shahbaz.flightcontracts.FlightControllerHealth as ContractHealth
import ir.hrka.shahbaz.flightcontracts.FlightControllerHealthIssue as ContractHealthIssue
import ir.hrka.shahbaz.flightcontracts.FlightControllerLifecycleRequest as ContractLifecycleRequest
import ir.hrka.shahbaz.flightcontracts.FlightControllerOutput as ContractControllerOutput
import ir.hrka.shahbaz.flightcontracts.FlightControllerSnapshot as ContractControllerSnapshot
import ir.hrka.shahbaz.flightcontracts.GeoPoint as ContractGeoPoint
import ir.hrka.shahbaz.flightcontracts.LocalNavigationReference as ContractNavigationReference
import ir.hrka.shahbaz.flightcontracts.PositionControlTarget as ContractPositionTarget
import ir.hrka.shahbaz.flightcontracts.Quaterniond as ContractQuaternion
import ir.hrka.shahbaz.flightcontracts.Vector3d as ContractVector
import ir.hrka.shahbaz.flightcontracts.VehicleStateEstimate as ContractVehicleEstimate
import ir.hrka.shahbaz.flightcontroller.AndroidPhoneSensorFrame
import ir.hrka.shahbaz.flightcontroller.AndroidPhoneSensorSource
import ir.hrka.shahbaz.flightcontroller.ExternalSensorSnapshot
import ir.hrka.shahbaz.flightcontroller.FlightController
import ir.hrka.shahbaz.flightcontroller.FlightControllerActuatorAction
import ir.hrka.shahbaz.flightcontroller.FlightControllerArmingState
import ir.hrka.shahbaz.flightcontroller.FlightControllerBoardState
import ir.hrka.shahbaz.flightcontroller.FlightControllerConfig
import ir.hrka.shahbaz.flightcontroller.FlightControllerInput
import ir.hrka.shahbaz.flightcontroller.FlightControllerLifecycleRequest
import ir.hrka.shahbaz.flightcontroller.FlightControllerSnapshot
import ir.hrka.shahbaz.flightcontroller.GeoPoint
import ir.hrka.shahbaz.flightcontroller.PhoneSensorSnapshot
import ir.hrka.shahbaz.flightcontroller.TimedSensorValue
import ir.hrka.shahbaz.hardwareconnection.BoardActuatorCommandResult
import ir.hrka.shahbaz.hardwareconnection.BoardConnectionState
import ir.hrka.shahbaz.hardwareconnection.BoardMotorPulse
import ir.hrka.shahbaz.hardwareconnection.BoardTelemetrySnapshot
import ir.hrka.shahbaz.hardwareconnection.HardwareConnection
import ir.hrka.shahbaz.hardwareconnection.SensorSample
import ir.hrka.shahbaz.hardwareconnection.SensorState
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Latest non-controller observations needed by the autonomous mission policy.
 *
 * Defaults deliberately describe unavailable providers. In particular, the runtime never infers
 * ground contact from pressure and never turns an absent safety provider into an affirmative
 * decision by stamping it with the loop time.
 */
internal data class FlightMissionInputs(
    val navigationFix: AutopilotNavigationFix? = null,
    val navigationAltitudeAboveMeanSeaLevelMeters: Double? = null,
    val landingObservation: AutopilotLandingObservation = AutopilotLandingObservation(),
    val safetyStatus: AutopilotSafetyStatus = AutopilotSafetyStatus(),
) {
    init {
        require(
            navigationAltitudeAboveMeanSeaLevelMeters == null ||
                navigationAltitudeAboveMeanSeaLevelMeters.isFinite(),
        )
    }
}

/** Stable reason for a runtime-level failure outside the pure autopilot state machine. */
internal enum class FlightMissionRuntimeFailureCode {
    ACTUATOR_COMMAND_REJECTED,
    CONTROL_LOOP_FAILED,
}

/** One latched runtime-level failure. */
internal data class FlightMissionRuntimeFailure(
    val code: FlightMissionRuntimeFailureCode,
    val message: String,
) {
    init {
        require(message.isNotBlank())
    }
}

/** Observable result of the latest serialized mission iteration. */
internal data class FlightMissionRuntimeState(
    val autopilot: AutopilotSnapshot,
    val controller: FlightControllerSnapshot,
    val running: Boolean = false,
    val actuatorFailure: BoardActuatorCommandResult.Rejected? = null,
    val failure: FlightMissionRuntimeFailure? = null,
)

/** Monotonic clock boundary shared by production scheduling and deterministic tests. */
internal fun interface FlightMonotonicClock {
    fun nowNanos(): Long
}

/** Minimal phone-sensor lifecycle used by the runtime. */
internal interface FlightPhoneSensorSource : Closeable {
    val frame: StateFlow<AndroidPhoneSensorFrame>

    fun start()
}

/** Read-only board state. Implementations do not own or close the underlying board connection. */
internal interface FlightBoardStateSource {
    val connectionState: StateFlow<BoardConnectionState>
    val telemetry: StateFlow<BoardTelemetrySnapshot>
}

/** Neutral action boundary used to test controller-to-board adaptation independently. */
internal fun interface FlightActuatorSink {
    fun apply(action: FlightControllerActuatorAction): BoardActuatorCommandResult
}

/** Narrow actuator port implemented by the borrowed [HardwareConnection]. */
internal interface BoardActuatorPort {
    fun armActuators(): BoardActuatorCommandResult

    fun sendMotorPulses(
        pulses: List<BoardMotorPulse>,
        generatedAtElapsedRealtimeNanos: Long,
    ): BoardActuatorCommandResult

    fun disarmActuators(): BoardActuatorCommandResult

    fun emergencyStopActuators(): BoardActuatorCommandResult
}

/** Exact hardware adapter for all actions the flight controller can emit. */
internal class HardwareConnectionActuatorSink(
    private val port: BoardActuatorPort,
) : FlightActuatorSink {
    constructor(board: HardwareConnection) : this(BorrowedHardwareActuatorPort(board))

    override fun apply(action: FlightControllerActuatorAction): BoardActuatorCommandResult =
        when (action) {
            FlightControllerActuatorAction.Arm -> port.armActuators()
            FlightControllerActuatorAction.Disarm -> port.disarmActuators()
            FlightControllerActuatorAction.EmergencyStop -> port.emergencyStopActuators()
            is FlightControllerActuatorAction.ApplyMotorPwm -> port.sendMotorPulses(
                pulses = action.motors.map { motor ->
                    BoardMotorPulse(channel = motor.channel, pulseMicros = motor.pulseMicros)
                },
                generatedAtElapsedRealtimeNanos = action.generatedAtNanos,
            )
        }
}

/**
 * Dashboard-owned composition of sensors, mission policy, feedback control, and board actions.
 *
 * The production constructor borrows [board]. The runtime never starts, stops, or closes it, so the
 * DashboardViewModel remains the sole `HardwareConnection` lifecycle owner. Controller and
 * autopilot mutation occurs only on the single serialized loop (or the explicit test hook).
 */
internal class FlightMissionRuntime internal constructor(
    private val inputs: StateFlow<FlightMissionInputs>,
    private val phoneSensorSource: FlightPhoneSensorSource,
    private val boardStateSource: FlightBoardStateSource,
    private val flightController: FlightController,
    private val autopilot: Autopilot,
    private val actuatorSink: FlightActuatorSink,
    private val clock: FlightMonotonicClock,
    private val loopDispatcher: CoroutineDispatcher,
    private val loopPeriodMillis: Long,
) : Closeable {
    /** Creates a runtime around one already-owned board connection. */
    constructor(
        context: Context,
        flightPlan: FlightPlan,
        board: HardwareConnection,
        inputs: StateFlow<FlightMissionInputs> = MutableStateFlow(FlightMissionInputs()),
        flightControllerConfig: FlightControllerConfig = FlightControllerConfig(),
        autopilotConfig: AutopilotConfig = AutopilotConfig(),
    ) : this(
        inputs = inputs,
        phoneSensorSource = AndroidFlightPhoneSensorSource(context),
        boardStateSource = BorrowedHardwareStateSource(board),
        flightController = FlightController.create(flightControllerConfig),
        autopilot = Autopilot.create(flightPlan, autopilotConfig),
        actuatorSink = HardwareConnectionActuatorSink(board),
        clock = FlightMonotonicClock(SystemClock::elapsedRealtimeNanos),
        loopDispatcher = Dispatchers.Default.limitedParallelism(1),
        loopPeriodMillis = flightControllerConfig.loopPeriodMillis,
    )

    private val closed = AtomicBoolean(false)
    private val prepared = AtomicBoolean(false)
    private val firstActuatorFailure =
        AtomicReference<BoardActuatorCommandResult.Rejected?>(null)
    private val firstRuntimeFailure = AtomicReference<FlightMissionRuntimeFailure?>(null)
    private val pendingRequest = AtomicReference(AutopilotRequest.NONE)
    private val iterationLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + loopDispatcher)

    private val mutableState = MutableStateFlow(
        FlightMissionRuntimeState(
            autopilot = autopilot.snapshot.value,
            controller = flightController.snapshot.value,
        ),
    )
    val state: StateFlow<FlightMissionRuntimeState> = mutableState.asStateFlow()

    private var loopJob: Job? = null
    private var lastIterationAtNanos: Long? = null

    init {
        require(loopPeriodMillis > 0L)
        require(loopPeriodMillis <= Long.MAX_VALUE / NANOS_PER_MILLISECOND)
    }

    /** Starts phone acquisition and the controller loop without starting the flight mission. */
    fun prepare(): Boolean {
        if (closed.get() || firstRuntimeFailure.get() != null) return false
        if (!prepared.compareAndSet(false, true)) return true
        return try {
            phoneSensorSource.start()
            mutableState.value = currentState(running = true)
            loopJob = scope.launch { runLoop() }
            true
        } catch (error: Throwable) {
            prepared.set(false)
            latchLoopFailure(error)
            false
        }
    }

    /** Queues the dashboard Start intent for exactly one serialized autopilot iteration. */
    fun startMission(): Boolean = enqueue(AutopilotRequest.START)

    /** Queues a controlled return-to-origin/landing request. */
    fun abortMission(): Boolean = enqueue(AutopilotRequest.ABORT)

    /**
     * Sends the board safety override immediately, then latches the same intent into both policy
     * layers on the next serialized iteration.
     */
    fun emergencyStop(): Boolean {
        if (closed.get()) return false
        enqueue(AutopilotRequest.EMERGENCY_STOP)
        val result = actuatorSink.apply(FlightControllerActuatorAction.EmergencyStop)
        handleActionResult(
            action = FlightControllerActuatorAction.EmergencyStop,
            result = result,
            sendImmediateEmergencyOverride = false,
        )
        return true
    }

    /**
     * Executes one synchronous iteration for focused JVM tests. Production callers use [prepare].
     */
    internal fun runSingleIterationForTest(): Boolean {
        if (closed.get()) return false
        return runIterationAt(clock.nowNanos())
    }

    /**
     * Stops only resources owned by this runtime. The borrowed HardwareConnection remains owned by
     * DashboardViewModel and is deliberately not closed here.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        loopJob?.cancel()
        synchronized(iterationLock) {
            val safetyAction = when (flightController.snapshot.value.armingState) {
                FlightControllerArmingState.ARMED,
                FlightControllerArmingState.ARMING,
                FlightControllerArmingState.DISARMING,
                FlightControllerArmingState.FAILSAFE,
                FlightControllerArmingState.EMERGENCY_STOPPED,
                -> FlightControllerActuatorAction.EmergencyStop
                FlightControllerArmingState.DISARMED -> FlightControllerActuatorAction.Disarm
            }
            actuatorSink.apply(safetyAction)
        }
        runCatching { phoneSensorSource.close() }
        prepared.set(false)
        mutableState.value = currentState(running = false)
        scope.cancel()
    }

    /** Retains only the highest-priority pending intent, so UI repetition cannot grow a queue. */
    private fun enqueue(request: AutopilotRequest): Boolean {
        if (closed.get()) return false
        while (true) {
            val current = pendingRequest.get()
            if (current.priority >= request.priority) return true
            if (pendingRequest.compareAndSet(current, request)) return true
        }
    }

    private suspend fun runLoop() {
        val periodNanos = loopPeriodMillis * NANOS_PER_MILLISECOND
        var nextDeadlineNanos = clock.nowNanos()
        try {
            while (currentCoroutineContext().isActive && !closed.get()) {
                val beforeDelay = clock.nowNanos()
                if (beforeDelay < nextDeadlineNanos) {
                    delayNanos(nextDeadlineNanos - beforeDelay)
                    continue
                }
                runIterationAt(beforeDelay)
                val nextPeriod = saturatingAdd(nextDeadlineNanos, periodNanos)
                nextDeadlineNanos = if (nextPeriod <= beforeDelay) {
                    saturatingAdd(beforeDelay, periodNanos)
                } else {
                    nextPeriod
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            // A failed serialized loop is terminal for this runtime instance. Clear the lifecycle
            // flag before publishing/escalating so no observer can see a dead loop as running and
            // prepare() cannot silently revive state after the latched emergency stop.
            prepared.set(false)
            latchLoopFailure(error)
        } finally {
            prepared.set(false)
            mutableState.value = currentState(running = false)
        }
    }

    private fun runIterationAt(timestampNanos: Long): Boolean = synchronized(iterationLock) {
        if (closed.get()) return@synchronized false
        val previousTimestamp = lastIterationAtNanos
        if (timestampNanos < 0L || previousTimestamp != null && timestampNanos <= previousTimestamp) {
            return@synchronized false
        }
        lastIterationAtNanos = timestampNanos

        val missionInputs = inputs.value
        val boardTelemetry = boardStateSource.telemetry.value
        val controllerInput = buildControllerInput(
            nowNanos = timestampNanos,
            phone = phoneSensorSource.frame.value.snapshot,
            connection = boardStateSource.connectionState.value,
            telemetry = boardTelemetry,
            missionInputs = missionInputs,
        )
        val autopilotOutput = autopilot.step(
            input = AutopilotInput(
                timestampNanos = timestampNanos,
                flightController = flightController.snapshot.value.toAutopilotControllerSnapshot(),
                navigationFix = missionInputs.navigationFix,
                landingObservation = missionInputs.landingObservation,
                groundRange = boardTelemetry.toAutopilotGroundRangeObservation(),
                safetyStatus = missionInputs.safetyStatus,
            ),
            request = drainRequest(),
        )
        val controllerOutput = flightController.step(
            input = controllerInput,
            command = autopilotOutput.flightControlCommand?.toControllerCommand(),
            lifecycleRequest = autopilotOutput.lifecycleRequest.toControllerLifecycleRequest(),
        )
        for (action in controllerOutput.actuatorActions) {
            if (!applyAction(action)) break
        }
        mutableState.value = currentState(running = prepared.get())
        true
    }

    private fun drainRequest(): AutopilotRequest {
        return pendingRequest.getAndSet(AutopilotRequest.NONE)
    }

    private fun applyAction(action: FlightControllerActuatorAction): Boolean {
        val result = actuatorSink.apply(action)
        handleActionResult(action, result, sendImmediateEmergencyOverride = true)
        return result !is BoardActuatorCommandResult.Rejected
    }

    private fun handleActionResult(
        action: FlightControllerActuatorAction,
        result: BoardActuatorCommandResult,
        sendImmediateEmergencyOverride: Boolean,
    ) {
        if (result !is BoardActuatorCommandResult.Rejected) return
        firstActuatorFailure.compareAndSet(null, result)
        firstRuntimeFailure.compareAndSet(
            null,
            FlightMissionRuntimeFailure(
                FlightMissionRuntimeFailureCode.ACTUATOR_COMMAND_REJECTED,
                result.message,
            ),
        )
        enqueue(AutopilotRequest.EMERGENCY_STOP)
        if (
            sendImmediateEmergencyOverride &&
            action != FlightControllerActuatorAction.EmergencyStop
        ) {
            actuatorSink.apply(FlightControllerActuatorAction.EmergencyStop)
        }
        mutableState.value = currentState(running = prepared.get())
    }

    private fun latchLoopFailure(error: Throwable) {
        val message = error.message?.takeIf(String::isNotBlank)
            ?: error.javaClass.simpleName.takeIf(String::isNotBlank)
            ?: "Unknown control-loop failure"
        firstRuntimeFailure.compareAndSet(
            null,
            FlightMissionRuntimeFailure(
                FlightMissionRuntimeFailureCode.CONTROL_LOOP_FAILED,
                message,
            ),
        )
        enqueue(AutopilotRequest.EMERGENCY_STOP)
        val result = actuatorSink.apply(FlightControllerActuatorAction.EmergencyStop)
        handleActionResult(
            action = FlightControllerActuatorAction.EmergencyStop,
            result = result,
            sendImmediateEmergencyOverride = false,
        )
        mutableState.value = currentState(running = false)
    }

    private fun currentState(running: Boolean): FlightMissionRuntimeState {
        val failure = firstRuntimeFailure.get()
        val rawAutopilot = autopilot.snapshot.value
        val presentedAutopilot = if (
            failure != null && rawAutopilot.phase !in TERMINAL_AUTOPILOT_PHASES
        ) {
            rawAutopilot.copy(phase = ir.hrka.shahbaz.autopilot.AutopilotPhase.EMERGENCY_STOPPED)
        } else {
            rawAutopilot
        }
        return FlightMissionRuntimeState(
            autopilot = presentedAutopilot,
            controller = flightController.snapshot.value,
            running = running,
            actuatorFailure = firstActuatorFailure.get(),
            failure = failure,
        )
    }
}

private class AndroidFlightPhoneSensorSource(
    context: Context,
) : FlightPhoneSensorSource {
    private val delegate = AndroidPhoneSensorSource(context.applicationContext)

    override val frame: StateFlow<AndroidPhoneSensorFrame> = delegate.frame

    override fun start() = delegate.start()

    override fun close() = delegate.close()
}

private class BorrowedHardwareStateSource(
    board: HardwareConnection,
) : FlightBoardStateSource {
    override val connectionState: StateFlow<BoardConnectionState> = board.connectionState
    override val telemetry: StateFlow<BoardTelemetrySnapshot> = board.telemetry
}

private class BorrowedHardwareActuatorPort(
    private val board: HardwareConnection,
) : BoardActuatorPort {
    override fun armActuators(): BoardActuatorCommandResult = board.armActuators()

    override fun sendMotorPulses(
        pulses: List<BoardMotorPulse>,
        generatedAtElapsedRealtimeNanos: Long,
    ): BoardActuatorCommandResult = board.sendMotorPulses(
        pulses = pulses,
        generatedAtElapsedRealtimeNanos = generatedAtElapsedRealtimeNanos,
    )

    override fun disarmActuators(): BoardActuatorCommandResult = board.disarmActuators()

    override fun emergencyStopActuators(): BoardActuatorCommandResult =
        board.emergencyStopActuators()
}

/** Builds one atomic controller frame without retaining stale board samples. */
internal fun buildControllerInput(
    nowNanos: Long,
    phone: PhoneSensorSnapshot,
    connection: BoardConnectionState,
    telemetry: BoardTelemetrySnapshot,
    missionInputs: FlightMissionInputs,
): FlightControllerInput {
    require(nowNanos >= 0L)
    val navigation = missionInputs.navigationFix
    val phoneWithLocation = phone.copy(
        location = navigation?.let { fix ->
            TimedSensorValue(
                value = GeoPoint(
                    latitudeDegrees = fix.coordinate.latitude,
                    longitudeDegrees = fix.coordinate.longitude,
                    altitudeAboveMeanSeaLevelMeters =
                        missionInputs.navigationAltitudeAboveMeanSeaLevelMeters,
                ),
                timestampNanos = fix.observedAtNanos,
            )
        },
    )
    return FlightControllerInput(
        timestampNanos = nowNanos,
        phone = phoneWithLocation,
        external = telemetry.toExternalSensorSnapshot(),
        board = connection.toFlightControllerBoardState(telemetry, nowNanos),
    )
}

/** Converts only independently validated Available samples into controller observations. */
internal fun BoardTelemetrySnapshot.toExternalSensorSnapshot(): ExternalSensorSnapshot {
    val environment = sht30.availableSample()
    val pressure = ms5611.availableSample()
    val environmentTimestamp = environment?.observedTimestampNanos()
    val pressureTimestamp = pressure?.observedTimestampNanos()
    return ExternalSensorSnapshot(
        temperatureCelsius = if (environment != null && environmentTimestamp != null) {
            TimedSensorValue(environment.value.temperatureCelsius, environmentTimestamp)
        } else {
            null
        },
        relativeHumidityPercent = if (environment != null && environmentTimestamp != null) {
            TimedSensorValue(environment.value.relativeHumidityPercent, environmentTimestamp)
        } else {
            null
        },
        pressurePascal = if (pressure != null && pressureTimestamp != null) {
            TimedSensorValue(pressure.value.pressurePascal, pressureTimestamp)
        } else {
            null
        },
        altitudeAboveMeanSeaLevelMeters = if (pressure != null && pressureTimestamp != null) {
            TimedSensorValue(pressure.value.altitudeAboveMeanSeaLevelMeters, pressureTimestamp)
        } else {
            null
        },
    )
}

/** Adapts only the downward role's current valid sample into the neutral autopilot input. */
internal fun BoardTelemetrySnapshot.toAutopilotGroundRangeObservation():
    AutopilotGroundRangeObservation? {
    val sample = groundRange.availableSample() ?: return null
    val timestampNanos = sample.observedTimestampNanos() ?: return null
    return AutopilotGroundRangeObservation(
        distanceMeters = sample.value.distanceMeters,
        signalQualityPercent = sample.value.signalQualityPercent,
        observedAtNanos = timestampNanos,
    )
}

/** Converts controller implementation feedback to the immutable policy contract. */
internal fun FlightControllerSnapshot.toAutopilotControllerSnapshot(): ContractControllerSnapshot =
    ContractControllerSnapshot(
        armingState = ContractArmingState.valueOf(armingState.name),
        estimate = ContractVehicleEstimate(
            localReference = ContractNavigationReference(
                horizontalOrigin = estimate.localReference.horizontalOrigin?.let {
                    ContractGeoPoint(it.latitudeDegrees, it.longitudeDegrees)
                },
                horizontalOriginObservedAtNanos =
                    estimate.localReference.horizontalOriginObservedAtNanos,
            ),
            attitudeBodyToNed = estimate.attitudeBodyToNed.let {
                ContractQuaternion(it.w, it.x, it.y, it.z)
            },
            angularVelocityBodyRadPerSecond = estimate.angularVelocityBodyRadPerSecond?.toContract(),
            localPositionNedMeters = estimate.localPositionNedMeters?.toContract(),
            localVelocityNedMetersPerSecond =
                estimate.localVelocityNedMetersPerSecond?.toContract(),
            altitudeAboveOriginMeters = estimate.altitudeAboveOriginMeters,
            verticalVelocityMetersPerSecond = estimate.verticalVelocityMetersPerSecond,
            attitudeObservedAtNanos = estimate.attitudeObservedAtNanos,
            angularVelocityObservedAtNanos = estimate.angularVelocityObservedAtNanos,
            altitudeObservedAtNanos = estimate.altitudeObservedAtNanos,
            verticalVelocityObservedAtNanos = estimate.verticalVelocityObservedAtNanos,
            positionObservedAtNanos = estimate.positionObservedAtNanos,
            velocityObservedAtNanos = estimate.velocityObservedAtNanos,
        ),
        health = ContractHealth(
            inputFresh = health.inputFresh,
            attitudeAvailable = health.attitudeAvailable,
            angularRateAvailable = health.angularRateAvailable,
            boardReady = health.boardReady,
            actuatorAvailable = health.actuatorAvailable,
            motorChannelsReady = health.motorChannelsReady,
            commandAccepted = health.commandAccepted,
            issues = health.issues.map { ContractHealthIssue(it.code.name, it.message) },
        ),
        lastOutput = lastOutput?.let { output ->
            ContractControllerOutput(
                tracking = ContractTracking(
                    commandSequence = output.tracking.commandSequence,
                    targetStatus = ContractTargetStatus.valueOf(output.tracking.targetStatus.name),
                ),
            )
        },
    )

/** Converts the policy's high-level position intent to the controller implementation API. */
internal fun ContractFlightControlCommand.toControllerCommand():
    ir.hrka.shahbaz.flightcontroller.FlightControlCommand =
    ir.hrka.shahbaz.flightcontroller.FlightControlCommand(
        sequence = sequence,
        issuedAtNanos = issuedAtNanos,
        validUntilNanos = validUntilNanos,
        setpoint = when (val target = setpoint) {
            is ContractPositionTarget -> ir.hrka.shahbaz.flightcontroller.PositionControlTarget(
                localPositionNedMeters = target.localPositionNedMeters.toController(),
                localVelocityFeedForwardNedMetersPerSecond =
                    target.localVelocityFeedForwardNedMetersPerSecond.toController(),
                yawNedRadians = target.yawNedRadians,
                yawRateFeedForwardRadPerSecond = target.yawRateFeedForwardRadPerSecond,
            )
        },
    )

internal fun ContractLifecycleRequest.toControllerLifecycleRequest():
    FlightControllerLifecycleRequest = FlightControllerLifecycleRequest.valueOf(name)

private fun ir.hrka.shahbaz.flightcontroller.Vector3d.toContract() = ContractVector(x, y, z)

private fun ContractVector.toController() =
    ir.hrka.shahbaz.flightcontroller.Vector3d(x, y, z)

/** Maps the current board observation while preserving the status sample's actual receipt time. */
internal fun BoardConnectionState.toFlightControllerBoardState(
    telemetry: BoardTelemetrySnapshot,
    observedAtNanos: Long,
): FlightControllerBoardState {
    require(observedAtNanos >= 0L)
    val ready = this as? BoardConnectionState.Ready
    val status = telemetry.deviceStatus.takeIf { ready != null }
    return FlightControllerBoardState(
        ready = ready != null,
        actuatorAvailable = ready?.deviceInfo?.let { info ->
            info.actuatorAvailable && info.actuatorsEnabledByConfiguration
        } == true,
        activeMotorChannels = ready?.deviceInfo?.activeMotorChannels ?: 0,
        actuatorArmed = status?.actuatorArmed ?: false,
        observedAtNanos = observedAtNanos,
        actuatorStateObservedAtNanos = status?.receivedAtElapsedRealtimeMillis
            ?.elapsedMillisToNanosOrNull(),
    )
}

private fun <T> SensorState<T>.availableSample(): SensorSample<T>? =
    (this as? SensorState.Available<T>)?.sample

private fun SensorSample<*>.observedTimestampNanos(): Long? =
    observedAtElapsedRealtimeMillis.elapsedMillisToNanosOrNull()

private fun Long.elapsedMillisToNanosOrNull(): Long? =
    takeIf { it >= 0L && it <= Long.MAX_VALUE / NANOS_PER_MILLISECOND }
        ?.times(NANOS_PER_MILLISECOND)

private val AutopilotRequest.priority: Int
    get() = when (this) {
        AutopilotRequest.NONE -> 0
        AutopilotRequest.START -> 1
        AutopilotRequest.ABORT -> 2
        AutopilotRequest.EMERGENCY_STOP -> 3
    }

private suspend fun delayNanos(nanos: Long) {
    if (nanos <= 0L) return
    val wholeMillis = nanos / NANOS_PER_MILLISECOND
    val roundedMillis = wholeMillis + if (nanos % NANOS_PER_MILLISECOND == 0L) 0L else 1L
    delay(max(1L, roundedMillis))
}

private fun saturatingAdd(value: Long, increment: Long): Long =
    if (value > Long.MAX_VALUE - increment) Long.MAX_VALUE else value + increment

private const val NANOS_PER_MILLISECOND = 1_000_000L

private val TERMINAL_AUTOPILOT_PHASES = setOf(
    ir.hrka.shahbaz.autopilot.AutopilotPhase.COMPLETED,
    ir.hrka.shahbaz.autopilot.AutopilotPhase.ABORTED,
    ir.hrka.shahbaz.autopilot.AutopilotPhase.FAILED,
    ir.hrka.shahbaz.autopilot.AutopilotPhase.EMERGENCY_STOPPED,
)
