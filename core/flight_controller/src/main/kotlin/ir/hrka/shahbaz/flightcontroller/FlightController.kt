package ir.hrka.shahbaz.flightcontroller

import com.shahbaz.flightblackbox.FbbEventRef
import com.shahbaz.flightblackbox.FbbEventType
import com.shahbaz.flightblackbox.FbbPersistence
import com.shahbaz.flightblackbox.FlightBlackBox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Public synchronous flight-controller contract.
 *
 * A future Autopilot or Remote Pilot supplies [FlightControlCommand] values. The controller owns
 * state estimation, safety gates, feedback control, and actuator allocation; it never chooses the
 * next command. The caller owns single-loop scheduling and sends returned
 * [FlightControllerActuatorAction] values through `:core:hardware_connection`.
 *
 * Implementations serialize [step] and [reset], but callers should still use one dedicated control
 * loop so blocking work cannot introduce timing jitter.
 */
interface FlightController {
    /** Latest observable state after the most recent [step]. */
    val snapshot: StateFlow<FlightControllerSnapshot>

    /**
     * Runs one estimation, safety, control, and allocation iteration.
     *
     * A null, expired, future, or out-of-order [command] blocks arming and causes an armed
     * controller to fail safe. [DISARM][FlightControllerLifecycleRequest.DISARM] and
     * [EMERGENCY_STOP][FlightControllerLifecycleRequest.EMERGENCY_STOP] remain effective without a
     * valid command.
     *
     * @param input Current monotonic time plus independently timestamped sensors and board state.
     * @param command Current externally supplied control primitive, or null when none is available.
     * @param lifecycleRequest Explicit arming or immediate-safety request.
     * @return Immutable estimate, health, tracking, motor outputs, and neutral actuator actions.
     */
    fun step(
        input: FlightControllerInput,
        command: FlightControlCommand?,
        lifecycleRequest: FlightControllerLifecycleRequest,
    ): FlightControllerOutput

    /**
     * Clears estimator references, accepted command, controller state, and safety state.
     *
     * Reset is rejected while arming or armed because this method cannot itself transmit a hardware
     * disarm. Callers must disarm through [step] first.
     */
    fun reset()

    /** Factory for the default Shahbaz Quad-X controller. */
    companion object {
        /** Creates a controller using [config]. */
        fun create(config: FlightControllerConfig = FlightControllerConfig()): FlightController =
            DefaultFlightController(config)
    }
}

/** Default PX4-inspired cascaded multicopter controller. */
private class DefaultFlightController(
    /**
     * Exposes the config value.
     */
    private val config: FlightControllerConfig,
) : FlightController {
    /**
     * Exposes the estimator value.
     */
    private val estimator = StateEstimator(config)
    /**
     * Exposes the positionController value.
     */
    private val positionController = PositionController(config)
    /**
     * Exposes the attitudeController value.
     */
    private val attitudeController = AttitudeController(config)
    /**
     * Exposes the rateController value.
     */
    private val rateController = RateController(config)
    /**
     * Exposes the allocator value.
     */
    private val allocator = QuadXControlAllocator(config)
    /**
     * Exposes the mutableSnapshot value.
     */
    private val mutableSnapshot = MutableStateFlow(FlightControllerSnapshot())

    /**
     * Stores the mutable armingState value.
     */
    private var armingState = FlightControllerArmingState.DISARMED
    /**
     * Stores the mutable armingRequestedAtNanos value.
     */
    private var armingRequestedAtNanos: Long? = null
    /**
     * Stores the mutable lastInputNanos value.
     */
    private var lastInputNanos: Long? = null
    /**
     * Stores the mutable lastEstimate value.
     */
    private var lastEstimate = VehicleStateEstimate()
    /**
     * Stores the mutable latestAcceptedCommand value.
     */
    private var latestAcceptedCommand: FlightControlCommand? = null
    /**
     * Stores the mutable activeControlKind value.
     */
    private var activeControlKind: ControlKind? = null
    /**
     * Stores the mutable previousMotorSaturated value.
     */
    private var previousMotorSaturated = false
    /**
     * Stores the mutable lastEvent value.
     */
    private var lastEvent: FbbEventRef? = null
    /**
     * Stores the mutable lastRecordedIssueCodes value.
     */
    private var lastRecordedIssueCodes: Set<FlightControllerHealthIssueCode> = emptySet()
    /**
     * Stores the mutable saturationRecorded value.
     */
    private var saturationRecorded = false

    /**
     * Exposes the snapshot value.
     */
    override val snapshot: StateFlow<FlightControllerSnapshot> = mutableSnapshot.asStateFlow()

    /** Serializes all mutable estimator and controller state updates. */
    @Synchronized
    override fun step(
        input: FlightControllerInput,
        command: FlightControlCommand?,
        lifecycleRequest: FlightControllerLifecycleRequest,
    ): FlightControllerOutput {
        val timing = evaluateInputTiming(input.timestampNanos)
        val dtSeconds = loopDt(input.timestampNanos, timing.monotonic)
        if (timing.monotonic) {
            lastEstimate = estimator.update(input)
        }

        val commandValidation = validateCommand(
            candidate = command,
            nowNanos = input.timestampNanos,
            allowReplacement = timing.monotonic,
        )
        commandValidation.replacement?.let(::onCommandReplaced)

        var health = evaluateHealth(
            input = input,
            estimate = lastEstimate,
            timing = timing,
            commandValidation = commandValidation,
        )
        val armingDecision = updateArmingState(
            request = lifecycleRequest,
            health = health,
            input = input,
        )
        if (armingDecision.additionalIssues.isNotEmpty()) {
            health = health.copy(issues = health.issues + armingDecision.additionalIssues)
        }

        val activeCommand = commandValidation.accepted
        val computation = if (
            armingState == FlightControllerArmingState.ARMED &&
            health.issues.isEmpty() &&
            activeCommand != null
        ) {
            computeControllerSetpoints(activeCommand.setpoint, lastEstimate, dtSeconds)
        } else {
            if (armingState != FlightControllerArmingState.ARMED) {
                resetControlLoops()
            }
            ControllerComputation(
                controllerSetpoints = ControllerSetpoints(),
                tracking = inactiveTracking(command, commandValidation, health),
            )
        }

        val allocation = if (armingState == FlightControllerArmingState.ARMED) {
            allocator.allocate(
                throttle = computation.controllerSetpoints.throttle,
                torque = computation.controllerSetpoints.torqueNormalized,
            )
        } else {
            MotorAllocation(allocator.stopped(), saturated = false)
        }
        previousMotorSaturated = allocation.saturated

        var tracking = computation.tracking.copy(motorOutputSaturated = allocation.saturated)
        if (armingState == FlightControllerArmingState.ARMED && activeCommand != null) {
            tracking = tracking.copy(
                commandSequence = activeCommand.sequence,
                targetStatus = targetStatus(activeCommand.setpoint, tracking),
            )
        }

        val motorAction = if (armingState == FlightControllerArmingState.ARMED) {
            listOf(
                FlightControllerActuatorAction.ApplyMotorPwm(
                    generatedAtNanos = input.timestampNanos,
                    motors = allocation.motors.map {
                        MotorPwmActuatorOutput(it.channel, it.pulseMicros)
                    },
                ),
            )
        } else {
            emptyList()
        }
        val output = FlightControllerOutput(
            timestampNanos = input.timestampNanos,
            armingState = armingState,
            estimate = lastEstimate,
            health = health,
            controllerSetpoints = computation.controllerSetpoints,
            tracking = tracking,
            motors = allocation.motors,
            actuatorActions = armingDecision.actions + motorAction,
        )
        mutableSnapshot.value = FlightControllerSnapshot(armingState, lastEstimate, health, output)
        if (timing.monotonic) {
            lastInputNanos = input.timestampNanos
        }
        recordHealthChanges(health)
        recordSaturationChange(allocation.saturated)
        return output
    }

    /** Resets only from a state in which active controller output is already prohibited. */
    @Synchronized
    override fun reset() {
        check(
            armingState != FlightControllerArmingState.ARMED &&
                armingState != FlightControllerArmingState.ARMING,
        ) { "Disarm the flight controller before reset" }
        val prior = armingState
        estimator.reset()
        resetControlLoops()
        armingState = FlightControllerArmingState.DISARMED
        armingRequestedAtNanos = null
        lastInputNanos = null
        lastEstimate = VehicleStateEstimate()
        latestAcceptedCommand = null
        activeControlKind = null
        previousMotorSaturated = false
        lastRecordedIssueCodes = emptySet()
        saturationRecorded = false
        mutableSnapshot.value = FlightControllerSnapshot()
        recordStateTransition(prior, armingState, "reset")
    }

    /** Evaluates strict monotonicity and the maximum accepted loop gap. */
    private fun evaluateInputTiming(timestampNanos: Long): InputTiming {
        val previous = lastInputNanos ?: return InputTiming(monotonic = true, gapFresh = true)
        if (timestampNanos <= previous) {
            return InputTiming(monotonic = false, gapFresh = false)
        }
        return InputTiming(
            monotonic = true,
            gapFresh = timestampNanos - previous <= millisToNanos(config.staleInputAfterMillis),
        )
    }

    /** Calculates a bounded control-loop time step from valid monotonic input time. */
    private fun loopDt(timestampNanos: Long, monotonic: Boolean): Double {
        val previous = lastInputNanos
        val raw = if (!monotonic || previous == null) {
            config.loopPeriodMillis / 1_000.0
        } else {
            (timestampNanos - previous) / 1_000_000_000.0
        }
        return raw.coerceIn(config.minimumDtSeconds, config.maximumDtSeconds)
    }

    /** Validates command time, identity, ordering, and replacement semantics. */
    private fun validateCommand(
        candidate: FlightControlCommand?,
        nowNanos: Long,
        allowReplacement: Boolean,
    ): CommandValidation {
        if (candidate == null) {
            return CommandValidation(
                issues = listOf(
                    issue(FlightControllerHealthIssueCode.COMMAND_MISSING, "No pilot command supplied"),
                ),
            )
        }
        val issues = buildList {
            if (
                candidate.issuedAtNanos > nowNanos &&
                candidate.issuedAtNanos - nowNanos > millisToNanos(config.commandFutureToleranceMillis)
            ) {
                add(issue(FlightControllerHealthIssueCode.COMMAND_FROM_FUTURE, "Pilot command timestamp is in the future"))
            }
            if (nowNanos > candidate.validUntilNanos) {
                add(issue(FlightControllerHealthIssueCode.COMMAND_EXPIRED, "Pilot command freshness deadline expired"))
            }
            if (candidate.validityDurationNanos > millisToNanos(config.maximumCommandLifetimeMillis)) {
                add(
                    issue(
                        FlightControllerHealthIssueCode.COMMAND_LIFETIME_TOO_LONG,
                        "Pilot command validity exceeds ${config.maximumCommandLifetimeMillis}ms",
                    ),
                )
            }
            val previous = latestAcceptedCommand
            if (previous != null && candidate.sequence < previous.sequence) {
                add(issue(FlightControllerHealthIssueCode.COMMAND_OUT_OF_ORDER, "Pilot command sequence is older than the accepted sequence"))
            } else if (previous != null && candidate.sequence == previous.sequence && candidate != previous) {
                add(issue(FlightControllerHealthIssueCode.COMMAND_SEQUENCE_CONFLICT, "Pilot command sequence was reused with different content"))
            }
        }
        if (issues.isNotEmpty() || !allowReplacement) {
            return CommandValidation(issues = issues)
        }

        val previous = latestAcceptedCommand
        val replacement = if (previous == null || candidate.sequence > previous.sequence) candidate else null
        if (replacement != null) {
            latestAcceptedCommand = replacement
        }
        return CommandValidation(
            accepted = candidate,
            replacement = replacement,
            issues = emptyList(),
        )
    }

    /** Resets loop state only when the active feedback cascade changes. */
    private fun onCommandReplaced(command: FlightControlCommand) {
        val newKind = command.setpoint.controlKind()
        if (activeControlKind != null && activeControlKind != newKind) {
            resetControlLoops()
        }
        activeControlKind = newKind
        lastEvent = FlightBlackBox.record(
            type = FbbEventType.STATE,
            description = "FlightController command replaced",
            cause = lastEvent,
            metadata = mapOf("sequence" to command.sequence, "target" to newKind.name),
            persistence = FbbPersistence.IMPORTANT,
        )
    }

    /** Builds the arming and continued-flight health gate. */
    private fun evaluateHealth(
        input: FlightControllerInput,
        estimate: VehicleStateEstimate,
        timing: InputTiming,
        commandValidation: CommandValidation,
    ): FlightControllerHealth {
        val now = input.timestampNanos
        val attitudeAvailable = estimate.attitudeObservedAtNanos.isFreshAt(
            now,
            millisToNanos(config.criticalSensorMaxAgeMillis),
        )
        val angularRateAvailable = estimate.angularVelocityObservedAtNanos.isFreshAt(
            now,
            millisToNanos(config.criticalSensorMaxAgeMillis),
        )
        val boardStateFresh = input.board.observedAtNanos.isFreshAt(
            now,
            millisToNanos(config.boardStateMaxAgeMillis),
        )
        val motorChannelsReady = input.board.activeMotorChannels == config.motorLayout.motors.size
        val issues = buildList {
            if (!timing.monotonic) {
                add(issue(FlightControllerHealthIssueCode.INPUT_TIMESTAMP_NOT_MONOTONIC, "Input timestamp did not advance monotonically"))
            } else if (!timing.gapFresh) {
                add(issue(FlightControllerHealthIssueCode.INPUT_GAP_TOO_LARGE, "Input timestamp gap exceeds ${config.staleInputAfterMillis}ms"))
            }
            addAll(commandValidation.issues)
            if (!attitudeAvailable) {
                add(issue(FlightControllerHealthIssueCode.ATTITUDE_UNAVAILABLE, "Fresh body-to-NED attitude is unavailable"))
            }
            if (!angularRateAvailable) {
                add(issue(FlightControllerHealthIssueCode.ANGULAR_RATE_UNAVAILABLE, "Fresh body angular rate is unavailable"))
            }
            if (!boardStateFresh) {
                add(issue(FlightControllerHealthIssueCode.BOARD_STATE_STALE, "Board readiness observation is stale"))
            }
            if (!input.board.ready) {
                add(issue(FlightControllerHealthIssueCode.BOARD_NOT_READY, "Shahbaz interface board is not ready"))
            }
            if (!input.board.actuatorAvailable) {
                add(issue(FlightControllerHealthIssueCode.ACTUATOR_UNAVAILABLE, "Physical actuator output is unavailable"))
            }
            if (!motorChannelsReady) {
                add(
                    issue(
                        FlightControllerHealthIssueCode.MOTOR_CHANNELS_UNAVAILABLE,
                        "Board reports ${input.board.activeMotorChannels} active motors; " +
                            "the configured layout requires ${config.motorLayout.motors.size}",
                    ),
                )
            }
            val actuatorStatusFresh = input.board.actuatorStateObservedAtNanos.isFreshAt(
                now,
                millisToNanos(config.boardStateMaxAgeMillis),
            )
            if (
                armingState == FlightControllerArmingState.DISARMED &&
                actuatorStatusFresh &&
                input.board.actuatorArmed
            ) {
                add(issue(FlightControllerHealthIssueCode.ACTUATOR_ARMED_WHILE_DISARMED, "Board reports armed while controller is disarmed"))
            }
            when (commandValidation.accepted?.setpoint) {
                is AltitudeControlTarget -> {
                    if (!estimate.altitudeObservedAtNanos.isFreshAt(now, millisToNanos(config.altitudeSensorMaxAgeMillis))) {
                        add(issue(FlightControllerHealthIssueCode.ALTITUDE_UNAVAILABLE, "Altitude target requires a fresh altitude estimate"))
                    }
                    if (
                        estimate.verticalVelocityMetersPerSecond == null ||
                        !estimate.verticalVelocityObservedAtNanos.isFreshAt(
                            now,
                            millisToNanos(config.altitudeSensorMaxAgeMillis),
                        )
                    ) {
                        add(issue(FlightControllerHealthIssueCode.VERTICAL_VELOCITY_UNAVAILABLE, "Altitude target requires two fresh altitude samples"))
                    }
                }
                is PositionControlTarget -> {
                    if (
                        estimate.localPositionNedMeters == null ||
                        !estimate.positionObservedAtNanos.isFreshAt(
                            now,
                            millisToNanos(config.positionSensorMaxAgeMillis),
                        )
                    ) {
                        add(issue(FlightControllerHealthIssueCode.POSITION_UNAVAILABLE, "Position target requires complete local NED position"))
                    }
                    if (
                        estimate.localVelocityNedMetersPerSecond == null ||
                        !estimate.velocityObservedAtNanos.isFreshAt(
                            now,
                            millisToNanos(config.positionSensorMaxAgeMillis),
                        )
                    ) {
                        add(issue(FlightControllerHealthIssueCode.VELOCITY_UNAVAILABLE, "Position target requires complete local NED velocity"))
                    }
                }
                is VelocityControlTarget -> if (
                    estimate.localVelocityNedMetersPerSecond == null ||
                    !estimate.velocityObservedAtNanos.isFreshAt(
                        now,
                        millisToNanos(config.positionSensorMaxAgeMillis),
                    )
                ) {
                    add(issue(FlightControllerHealthIssueCode.VELOCITY_UNAVAILABLE, "Velocity target requires complete local NED velocity"))
                }
                is AttitudeControlTarget,
                is RateControlTarget,
                null,
                -> Unit
            }
        }
        return FlightControllerHealth(
            inputFresh = timing.monotonic && timing.gapFresh,
            attitudeAvailable = attitudeAvailable,
            angularRateAvailable = angularRateAvailable,
            boardReady = boardStateFresh && input.board.ready,
            actuatorAvailable = input.board.actuatorAvailable,
            motorChannelsReady = motorChannelsReady,
            commandAccepted = commandValidation.accepted != null,
            issues = issues,
        )
    }

    /** Applies explicit lifecycle requests and board-confirmed arming semantics. */
    private fun updateArmingState(
        request: FlightControllerLifecycleRequest,
        health: FlightControllerHealth,
        input: FlightControllerInput,
    ): ArmingDecision {
        val prior = armingState
        val actions = mutableListOf<FlightControllerActuatorAction>()
        val additionalIssues = mutableListOf<FlightControllerHealthIssue>()

        if (prior == FlightControllerArmingState.EMERGENCY_STOPPED) {
            if (request == FlightControllerLifecycleRequest.EMERGENCY_STOP) {
                actions += FlightControllerActuatorAction.EmergencyStop
            }
            return ArmingDecision(actions, additionalIssues)
        }

        when (request) {
            FlightControllerLifecycleRequest.EMERGENCY_STOP -> {
                armingState = FlightControllerArmingState.EMERGENCY_STOPPED
                armingRequestedAtNanos = null
                actions += FlightControllerActuatorAction.EmergencyStop
            }
            FlightControllerLifecycleRequest.DISARM -> {
                armingState = FlightControllerArmingState.DISARMED
                armingRequestedAtNanos = null
                actions += FlightControllerActuatorAction.Disarm
            }
            FlightControllerLifecycleRequest.HOLD_DISARMED -> {
                armingState = FlightControllerArmingState.DISARMED
                armingRequestedAtNanos = null
                if (prior != FlightControllerArmingState.DISARMED) {
                    actions += FlightControllerActuatorAction.Disarm
                }
            }
            FlightControllerLifecycleRequest.ARM -> when (prior) {
                FlightControllerArmingState.DISARMED -> if (health.canArm) {
                    armingState = FlightControllerArmingState.ARMING
                    armingRequestedAtNanos = input.timestampNanos
                    actions += FlightControllerActuatorAction.Arm
                } else {
                    actions += FlightControllerActuatorAction.Disarm
                    recordArmingRejection(health)
                }
                FlightControllerArmingState.ARMING -> confirmArmingOrFail(
                    input,
                    health,
                    actions,
                    additionalIssues,
                )
                FlightControllerArmingState.ARMED -> ensureContinuedSafety(
                    input,
                    health,
                    actions,
                    additionalIssues,
                )
                FlightControllerArmingState.FAILSAFE,
                FlightControllerArmingState.EMERGENCY_STOPPED,
                -> Unit
            }
            FlightControllerLifecycleRequest.RUN -> when (prior) {
                FlightControllerArmingState.ARMING -> confirmArmingOrFail(
                    input,
                    health,
                    actions,
                    additionalIssues,
                )
                FlightControllerArmingState.ARMED -> ensureContinuedSafety(
                    input,
                    health,
                    actions,
                    additionalIssues,
                )
                FlightControllerArmingState.DISARMED,
                FlightControllerArmingState.FAILSAFE,
                FlightControllerArmingState.EMERGENCY_STOPPED,
                -> Unit
            }
        }
        if (prior != armingState) {
            recordStateTransition(prior, armingState, request.name)
        }
        return ArmingDecision(actions, additionalIssues)
    }

    /** Waits for a fresh board status confirmation and fails closed on timeout or health loss. */
    private fun confirmArmingOrFail(
        input: FlightControllerInput,
        health: FlightControllerHealth,
        actions: MutableList<FlightControllerActuatorAction>,
        additionalIssues: MutableList<FlightControllerHealthIssue>,
    ) {
        if (!health.canArm) {
            enterFailsafe(actions)
            return
        }
        if (boardArmingConfirmed(input)) {
            armingState = FlightControllerArmingState.ARMED
            armingRequestedAtNanos = null
            return
        }
        val requestedAt = armingRequestedAtNanos ?: input.timestampNanos
        if (
            input.timestampNanos >= requestedAt &&
            input.timestampNanos - requestedAt > millisToNanos(config.armingConfirmationTimeoutMillis)
        ) {
            additionalIssues += issue(
                FlightControllerHealthIssueCode.ARMING_CONFIRMATION_TIMEOUT,
                "Board did not confirm arming within ${config.armingConfirmationTimeoutMillis}ms",
            )
            enterFailsafe(actions)
        }
    }

    /** Requires both general health and fresh board confirmation throughout armed flight. */
    private fun ensureContinuedSafety(
        input: FlightControllerInput,
        health: FlightControllerHealth,
        actions: MutableList<FlightControllerActuatorAction>,
        additionalIssues: MutableList<FlightControllerHealthIssue>,
    ) {
        if (!health.canArm) {
            enterFailsafe(actions)
            return
        }
        if (!boardArmingConfirmed(input)) {
            additionalIssues += issue(
                FlightControllerHealthIssueCode.ACTUATOR_DISARMED_UNEXPECTEDLY,
                "Board arming confirmation is false or stale",
            )
            enterFailsafe(actions)
        }
    }

    /** Returns true only for a fresh positive board actuator-state observation. */
    private fun boardArmingConfirmed(input: FlightControllerInput): Boolean =
        input.board.actuatorArmed &&
            input.board.actuatorStateObservedAtNanos.isFreshAt(
                input.timestampNanos,
                millisToNanos(config.boardStateMaxAgeMillis),
            )

    /** Enters latched low-level failsafe and emits one immediate disarm action. */
    private fun enterFailsafe(actions: MutableList<FlightControllerActuatorAction>) {
        if (armingState != FlightControllerArmingState.FAILSAFE) {
            armingState = FlightControllerArmingState.FAILSAFE
            armingRequestedAtNanos = null
            resetControlLoops()
            actions += FlightControllerActuatorAction.Disarm
        }
    }

    /** Runs the feedback cascade selected by the externally supplied target type. */
    private fun computeControllerSetpoints(
        setpoint: FlightControlSetpoint,
        estimate: VehicleStateEstimate,
        dtSeconds: Double,
    ): ControllerComputation {
        val target = when (setpoint) {
            is RateControlTarget -> PositionControllerResult(
                target = AttitudeThrottleSetpoint(
                    attitude = estimate.attitudeBodyToNed,
                    bodyRates = setpoint.bodyRatesRadPerSecond.clampAbs(config.maxBodyRateRadPerSecond),
                    throttle = commandedThrottle(setpoint.thrust),
                ),
                tracking = ControlTracking(),
            )
            is AttitudeControlTarget -> PositionControllerResult(
                target = AttitudeThrottleSetpoint(
                    attitude = limitedAttitude(setpoint.attitude),
                    bodyRates = null,
                    throttle = commandedThrottle(setpoint.thrust),
                    yawRateFeedForward = setpoint.yawRateFeedForwardRadPerSecond,
                ),
                tracking = ControlTracking(),
            )
            is AltitudeControlTarget -> positionController.updateAltitude(
                setpoint,
                estimate,
                dtSeconds,
                allowIntegral = !previousMotorSaturated,
            )
            is PositionControlTarget -> positionController.update(
                setpoint,
                estimate,
                dtSeconds,
                allowIntegral = !previousMotorSaturated,
            )
            is VelocityControlTarget -> positionController.updateVelocity(
                setpoint,
                estimate,
                dtSeconds,
                allowIntegral = !previousMotorSaturated,
            )
        }
        val desired = target.target
        val measuredRates = requireNotNull(estimate.angularVelocityBodyRadPerSecond)
        val attitudeError = attitudeController.error(estimate.attitudeBodyToNed, desired.attitude)
        val rateSetpoint = desired.bodyRates ?: attitudeController.update(
            currentAttitude = estimate.attitudeBodyToNed,
            desiredAttitude = desired.attitude,
            yawRateFeedForward = desired.yawRateFeedForward,
        )
        val rateError = rateSetpoint - measuredRates
        val torque = rateController.update(
            rateSetpoint = rateSetpoint,
            measuredRates = measuredRates,
            dtSeconds = dtSeconds,
            allowIntegral = !previousMotorSaturated,
        )
        return ControllerComputation(
            controllerSetpoints = ControllerSetpoints(
                attitude = desired.attitude,
                bodyRatesRadPerSecond = rateSetpoint,
                torqueNormalized = torque,
                throttle = desired.throttle,
            ),
            tracking = target.tracking.copy(
                attitudeErrorRadians = if (desired.bodyRates == null) attitudeError else Vector3d.ZERO,
                rateErrorRadPerSecond = rateError,
            ),
        )
    }

    /** Bounds direct attitude targets to configured roll/pitch limits while preserving NED yaw. */
    private fun limitedAttitude(attitude: Quaterniond): Quaterniond {
        val euler = attitude.toEuler()
        return Quaterniond.fromEuler(
            rollRadians = euler.rollRadians.coerceIn(-config.maxTiltRadians, config.maxTiltRadians),
            pitchRadians = euler.pitchRadians.coerceIn(-config.maxTiltRadians, config.maxTiltRadians),
            yawRadians = euler.yawRadians,
        )
    }

    /** Applies armed-flight throttle policy. */
    private fun commandedThrottle(thrust: CollectiveThrust): Double =
        thrust.normalized.coerceIn(config.minimumFlyingThrottle, config.maximumFlyingThrottle)

    /** Classifies primitive completion without selecting any follow-up target. */
    private fun targetStatus(
        setpoint: FlightControlSetpoint,
        tracking: ControlTracking,
    ): ControlTargetStatus {
        val reached = when (setpoint) {
            is RateControlTarget ->
                tracking.rateErrorRadPerSecond.maxAbsComponent() <= config.rateTargetToleranceRadPerSecond
            is AttitudeControlTarget ->
                tracking.attitudeErrorRadians.norm() <= config.attitudeTargetToleranceRadians &&
                    tracking.rateErrorRadPerSecond.maxAbsComponent() <= config.rateTargetToleranceRadPerSecond
            is AltitudeControlTarget ->
                kotlin.math.abs(tracking.altitudeErrorMeters ?: Double.POSITIVE_INFINITY) <=
                    config.altitudeTargetToleranceMeters &&
                    kotlin.math.abs(tracking.velocityErrorNedMetersPerSecond?.z ?: Double.POSITIVE_INFINITY) <=
                    config.velocityTargetToleranceMetersPerSecond
            is PositionControlTarget ->
                (tracking.positionErrorNedMeters?.norm() ?: Double.POSITIVE_INFINITY) <=
                    config.positionTargetToleranceMeters &&
                    (tracking.velocityErrorNedMetersPerSecond?.norm() ?: Double.POSITIVE_INFINITY) <=
                    config.velocityTargetToleranceMetersPerSecond
            is VelocityControlTarget ->
                (tracking.velocityErrorNedMetersPerSecond?.norm() ?: Double.POSITIVE_INFINITY) <=
                    config.velocityTargetToleranceMetersPerSecond
        }
        return if (reached) ControlTargetStatus.REACHED else ControlTargetStatus.TRACKING
    }

    /** Builds non-active tracking status from command and capability failures. */
    private fun inactiveTracking(
        suppliedCommand: FlightControlCommand?,
        validation: CommandValidation,
        health: FlightControllerHealth,
    ): ControlTracking {
        val unavailableCodes = setOf(
            FlightControllerHealthIssueCode.ATTITUDE_UNAVAILABLE,
            FlightControllerHealthIssueCode.ANGULAR_RATE_UNAVAILABLE,
            FlightControllerHealthIssueCode.ALTITUDE_UNAVAILABLE,
            FlightControllerHealthIssueCode.VERTICAL_VELOCITY_UNAVAILABLE,
            FlightControllerHealthIssueCode.POSITION_UNAVAILABLE,
            FlightControllerHealthIssueCode.VELOCITY_UNAVAILABLE,
        )
        val status = when {
            validation.issues.isNotEmpty() -> ControlTargetStatus.REJECTED
            health.issues.any { it.code in unavailableCodes } -> ControlTargetStatus.UNAVAILABLE
            else -> ControlTargetStatus.INACTIVE
        }
        return ControlTracking(
            commandSequence = suppliedCommand?.sequence,
            targetStatus = status,
        )
    }

    /** Clears feedback state that must not cross disarm, failsafe, or control-kind transitions. */
    private fun resetControlLoops() {
        positionController.reset()
        rateController.reset()
        previousMotorSaturated = false
    }

    /** Records every arming-state transition. */
    private fun recordStateTransition(
        prior: FlightControllerArmingState,
        next: FlightControllerArmingState,
        cause: String,
    ) {
        lastEvent = FlightBlackBox.record(
            type = FbbEventType.STATE,
            description = "FlightController arming state changed",
            cause = lastEvent,
            metadata = mapOf("from" to prior, "to" to next, "cause" to cause),
            persistence = FbbPersistence.IMPORTANT,
        )
    }

    /** Records rejected arming without logging every control-loop iteration. */
    private fun recordArmingRejection(health: FlightControllerHealth) {
        lastEvent = FlightBlackBox.record(
            type = FbbEventType.WARNING,
            description = "FlightController arming rejected",
            cause = lastEvent,
            metadata = mapOf("issues" to health.issues.map { it.code.name }),
            persistence = FbbPersistence.IMPORTANT,
        )
    }

    /** Records only changes to the health issue set. */
    private fun recordHealthChanges(health: FlightControllerHealth) {
        val codes = health.issues.mapTo(linkedSetOf()) { it.code }
        if (codes == lastRecordedIssueCodes) return
        lastRecordedIssueCodes = codes
        lastEvent = FlightBlackBox.record(
            type = if (codes.isEmpty()) FbbEventType.STATE else FbbEventType.WARNING,
            description = "FlightController health changed",
            cause = lastEvent,
            metadata = mapOf("issues" to codes.map { it.name }),
            persistence = if (codes.isEmpty()) FbbPersistence.NORMAL else FbbPersistence.IMPORTANT,
        )
    }

    /** Records only the leading and trailing edges of motor saturation. */
    private fun recordSaturationChange(saturated: Boolean) {
        if (saturationRecorded == saturated) return
        saturationRecorded = saturated
        lastEvent = FlightBlackBox.record(
            type = if (saturated) FbbEventType.WARNING else FbbEventType.STATE,
            description = if (saturated) "FlightController motor allocation saturated" else "FlightController motor allocation recovered",
            cause = lastEvent,
            persistence = if (saturated) FbbPersistence.IMPORTANT else FbbPersistence.NORMAL,
        )
    }
}

/** Input timestamp validation result. */
private data class InputTiming(val monotonic: Boolean, val gapFresh: Boolean)

/** Pilot-command validation and replacement result. */
private data class CommandValidation(
    /**
     * Exposes the accepted value.
     */
    val accepted: FlightControlCommand? = null,
    /**
     * Exposes the replacement value.
     */
    val replacement: FlightControlCommand? = null,
    /**
     * Exposes the issues value.
     */
    val issues: List<FlightControllerHealthIssue>,
)

/** Arming state-machine output. */
private data class ArmingDecision(
    /**
     * Exposes the actions value.
     */
    val actions: List<FlightControllerActuatorAction>,
    /**
     * Exposes the additionalIssues value.
     */
    val additionalIssues: List<FlightControllerHealthIssue>,
)

/** Creates one structured health issue. */
private fun issue(
    code: FlightControllerHealthIssueCode,
    message: String,
): FlightControllerHealthIssue = FlightControllerHealthIssue(code, message)

/** Converts nonnegative milliseconds to nanoseconds without overflow. */
private fun millisToNanos(milliseconds: Long): Long =
    if (milliseconds > Long.MAX_VALUE / 1_000_000L) Long.MAX_VALUE else milliseconds * 1_000_000L

/** Checks that this optional timestamp is not future and is within [maximumAgeNanos]. */
private fun Long?.isFreshAt(nowNanos: Long, maximumAgeNanos: Long): Boolean =
    this != null && nowNanos >= this && nowNanos - this <= maximumAgeNanos
