package ir.hrka.shahbaz.autopilot

import ir.hrka.shahbaz.core.domain.wgs84GeodesicDistanceMeters
import ir.hrka.shahbaz.core.model.FlightPlan
import ir.hrka.shahbaz.core.model.GeoCoordinate
import ir.hrka.shahbaz.flightcontracts.ControlTargetStatus
import ir.hrka.shahbaz.flightcontracts.FlightControlCommand
import ir.hrka.shahbaz.flightcontracts.FlightControlSetpoint
import ir.hrka.shahbaz.flightcontracts.FlightControllerArmingState
import ir.hrka.shahbaz.flightcontracts.FlightControllerLifecycleRequest
import ir.hrka.shahbaz.flightcontracts.FlightControllerSnapshot
import ir.hrka.shahbaz.flightcontracts.GeoPoint
import ir.hrka.shahbaz.flightcontracts.PositionControlTarget
import ir.hrka.shahbaz.flightcontracts.Vector3d
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Deterministic point-to-point mission sequencer. It performs no I/O and owns no actuator API. */
internal class PointToPointAutopilot(
    private val flightPlan: FlightPlan,
    private val config: AutopilotConfig,
) : Autopilot {
    private var phase = AutopilotPhase.STANDBY
    private var phaseStartedAtNanos: Long? = null
    private var missionStartedAtNanos: Long? = null
    private var lastStepAtNanos: Long? = null
    private var nextCommandSequence = 0L
    private var lastIssuedCommandSequence: Long? = null
    private var lastIssuedCommandPhase: AutopilotPhase? = null
    private var reachedSinceNanos: Long? = null
    private var touchdownSinceNanos: Long? = null
    private var everArmed = false
    private var airborneObserved = false
    private var abortingToOrigin = false
    private var missionOutcomeAborted = false
    private var landingCommittedToTouchdown = false
    private var missionTimeoutHandled = false
    private var phaseTimeoutHandledForStartNanos: Long? = null
    private var returnClimbHorizontal = Vector3d.ZERO
    private var landingHorizontal = Vector3d.ZERO
    private var landingGroundAltitudeMeters = 0.0
    private var landingInitialAltitudeMeters = 0.0
    private var phaseMotion: PhaseMotion? = null
    private var landingProfile: TwoRateLandingDescentProfile? = null
    private val groundRangeLandingAid = GroundRangeLandingAid(config)
    private var groundRangeDecision = GroundRangeLandingDecision(
        LandingRangeAidState.INACTIVE,
    )
    private var lastIssuedTargetFinal = false
    private var latchedIssues: List<AutopilotIssue> = emptyList()
    private var activeTarget: Vector3d? = null

    private val mutableSnapshot = MutableStateFlow(
        AutopilotSnapshot(
            phase = phase,
            flightPlan = flightPlan,
            issues = validateMission(),
        ),
    )
    override val snapshot: StateFlow<AutopilotSnapshot> = mutableSnapshot.asStateFlow()

    override fun step(input: AutopilotInput, request: AutopilotRequest): AutopilotOutput {
        val now = input.timestampNanos
        // Emergency stop must win even when the caller's clock or ordinary control state is bad.
        if (
            request == AutopilotRequest.EMERGENCY_STOP ||
            input.flightController.armingState == FlightControllerArmingState.EMERGENCY_STOPPED
        ) {
            emergencyStop(now)
            return output(input)
        }
        val priorTimestamp = lastStepAtNanos
        if (priorTimestamp != null && now <= priorTimestamp) {
            fail(
                now,
                issue(
                    AutopilotIssueCode.TIMESTAMP_NOT_MONOTONIC,
                    "Autopilot timestamps must advance strictly monotonically",
                ),
            )
            return output(input)
        }
        lastStepAtNanos = now

        observeLandingDetector(input)
        if (handleControllerTerminalState(input)) return output(input)
        if (phase == AutopilotPhase.LANDING) updateGroundRangeDecision(input)

        // Once independent evidence confirms touchdown, removing thrust takes precedence over
        // route, wind, energy, and timeout contingencies that would otherwise command a go-around.
        // Explicit emergency stop and controller terminal states intentionally remain above it.
        if (phase == AutopilotPhase.LANDING && touchdownConfirmed(input)) {
            if (request == AutopilotRequest.ABORT) missionOutcomeAborted = true
            latchIssues(inFlightSafetyIssues(input))
            transition(AutopilotPhase.DISARMING, now)
            return output(input)
        }

        if (request == AutopilotRequest.ABORT) {
            handleAbortRequest(input)
        }

        if (isAirborneMissionPhase()) {
            val safetyIssues = inFlightSafetyIssues(input)
            if (safetyIssues.isNotEmpty()) {
                containInFlightSafetyLoss(input, safetyIssues)
            }
        }

        if (phase == AutopilotPhase.CRUISE || phase == AutopilotPhase.LANDING) {
            routeCorridorIssue(input.flightController)?.let { corridorIssue ->
                containInFlightSafetyLoss(input, listOf(corridorIssue))
            }
        }

        enforceTimeouts(input)
        advanceState(input, request)
        return output(input)
    }

    private fun advanceState(input: AutopilotInput, request: AutopilotRequest) {
        val now = input.timestampNanos
        when (phase) {
            AutopilotPhase.STANDBY -> if (request == AutopilotRequest.START) {
                val missionIssues = validateMission()
                if (missionIssues.isEmpty()) {
                    missionStartedAtNanos = now
                    abortingToOrigin = false
                    missionOutcomeAborted = false
                    landingCommittedToTouchdown = false
                    missionTimeoutHandled = false
                    latchedIssues = emptyList()
                    transition(AutopilotPhase.PREFLIGHT, now)
                } else {
                    latchedIssues = missionIssues
                }
            }

            AutopilotPhase.PREFLIGHT -> {
                val blockers = preflightIssues(input)
                if (blockers.isEmpty()) {
                    latchedIssues = emptyList()
                    transition(AutopilotPhase.ARMING, now)
                }
            }

            AutopilotPhase.ARMING -> when (input.flightController.armingState) {
                FlightControllerArmingState.DISARMED,
                FlightControllerArmingState.ARMING,
                FlightControllerArmingState.ARMED,
                -> {
                    // START authorizes arming only while every live preflight gate remains valid.
                    // A provider can disappear after PREFLIGHT emitted ARM but before the board's
                    // positive arming acknowledgement reaches the controller. Revalidate the
                    // acknowledgement-bearing input itself before allowing takeoff.
                    val blockers = preflightIssues(input, allowArmingInProgress = true)
                    if (blockers.isNotEmpty()) {
                        latchedIssues = blockers
                        transition(AutopilotPhase.FAILED, now)
                    } else if (
                        input.flightController.armingState == FlightControllerArmingState.ARMED
                    ) {
                        everArmed = true
                        transition(AutopilotPhase.TAKEOFF, now)
                    }
                }
                FlightControllerArmingState.DISARMING -> fail(
                    now,
                    issue(
                        AutopilotIssueCode.FLIGHT_CONTROLLER_NOT_READY,
                        "The flight controller began disarming while autonomous arming was pending",
                    ),
                )
                FlightControllerArmingState.FAILSAFE,
                FlightControllerArmingState.EMERGENCY_STOPPED,
                -> Unit // handled before this state dispatch
            }

            AutopilotPhase.TAKEOFF -> if (targetReachedAndSettled(input)) {
                transition(AutopilotPhase.CRUISE, now)
            }

            AutopilotPhase.CRUISE -> if (targetReachedAndSettled(input)) {
                val destination = destinationHorizontal(input.flightController) ?: run {
                    fail(
                        now,
                        issue(
                            AutopilotIssueCode.CONTROLLER_REFERENCE_UNAVAILABLE,
                            "The controller local reference disappeared before landing",
                        ),
                    )
                    return
                }
                beginLanding(
                    input = input,
                    horizontal = destination,
                    groundAltitudeMeters = flightPlan.destinationGroundAltitudeAboveOriginMeters,
                )
            }

            AutopilotPhase.RETURN_CLIMB -> if (targetReachedAndSettled(input)) {
                transition(AutopilotPhase.RETURNING, now)
            }

            AutopilotPhase.RETURNING -> if (targetReachedAndSettled(input)) {
                beginLanding(input, Vector3d.ZERO, 0.0)
            }

            // Touchdown is evaluated before in-flight containment and timeout handling in [step].
            AutopilotPhase.LANDING -> Unit

            AutopilotPhase.DISARMING -> if (
                input.flightController.armingState == FlightControllerArmingState.DISARMED
            ) {
                transition(
                    if (missionOutcomeAborted) AutopilotPhase.ABORTED else AutopilotPhase.COMPLETED,
                    now,
                )
            }

            AutopilotPhase.COMPLETED,
            AutopilotPhase.ABORTED,
            AutopilotPhase.FAILED,
            AutopilotPhase.EMERGENCY_STOPPED,
            -> Unit
        }
    }

    private fun output(input: AutopilotInput): AutopilotOutput {
        val now = input.timestampNanos
        val setpoint = setpoint(input)
        val dynamicIssues = when (phase) {
            AutopilotPhase.STANDBY -> validateMission()
            AutopilotPhase.PREFLIGHT -> preflightIssues(input)
            AutopilotPhase.LANDING -> landingRangeIssue()?.let(::listOf).orEmpty()
            else -> emptyList()
        }
        val command = if (setpoint != null) newCommand(now, setpoint) else null
        val lifecycle = when (phase) {
            AutopilotPhase.STANDBY,
            AutopilotPhase.PREFLIGHT,
            -> FlightControllerLifecycleRequest.HOLD_DISARMED
            AutopilotPhase.ARMING -> FlightControllerLifecycleRequest.ARM
            AutopilotPhase.TAKEOFF,
            AutopilotPhase.CRUISE,
            AutopilotPhase.LANDING,
            AutopilotPhase.RETURN_CLIMB,
            AutopilotPhase.RETURNING,
            -> FlightControllerLifecycleRequest.RUN
            AutopilotPhase.DISARMING,
            AutopilotPhase.FAILED,
            -> FlightControllerLifecycleRequest.DISARM
            AutopilotPhase.COMPLETED,
            AutopilotPhase.ABORTED,
            -> if (input.flightController.armingState == FlightControllerArmingState.DISARMED) {
                FlightControllerLifecycleRequest.HOLD_DISARMED
            } else {
                FlightControllerLifecycleRequest.DISARM
            }
            AutopilotPhase.EMERGENCY_STOPPED -> FlightControllerLifecycleRequest.EMERGENCY_STOP
        }
        val currentSnapshot = AutopilotSnapshot(
            phase = phase,
            flightPlan = flightPlan,
            abortingToOrigin = abortingToOrigin,
            activeTargetLocalNedMeters = activeTarget,
            landingRangeAidState = groundRangeDecision.state,
            issues = (latchedIssues + dynamicIssues).distinctBy { it.code },
        )
        mutableSnapshot.value = currentSnapshot
        return AutopilotOutput(now, currentSnapshot, command, lifecycle)
    }

    private fun setpoint(input: AutopilotInput): FlightControlSetpoint? {
        val cruiseAltitude = flightPlan.targetAltitudeAboveOriginMeters
        val routeYaw = routeYawRadians(input.flightController)
        val yaw = when (phase) {
            AutopilotPhase.RETURN_CLIMB,
            AutopilotPhase.RETURNING,
            -> yawTowardOrigin(input.flightController)
            else -> routeYaw
        }
        val positionTarget = when (phase) {
            AutopilotPhase.PREFLIGHT,
            AutopilotPhase.ARMING,
            -> PositionControlTarget(
                localPositionNedMeters = Vector3d.ZERO,
                yawNedRadians = routeYaw,
            )
            AutopilotPhase.TAKEOFF -> profiledPositionTarget(
                input = input,
                destination = Vector3d(0.0, 0.0, -cruiseAltitude),
                maximumSpeedMetersPerSecond = config.takeoffMaximumSpeedMetersPerSecond,
                maximumAccelerationMetersPerSecondSquared =
                    config.takeoffMaximumAccelerationMetersPerSecondSquared,
                yawNedRadians = yaw,
            )
            AutopilotPhase.CRUISE -> destinationHorizontal(input.flightController)?.copy(
                z = -cruiseAltitude,
            )?.let { destination ->
                profiledPositionTarget(
                    input = input,
                    destination = destination,
                    maximumSpeedMetersPerSecond = config.cruiseMaximumSpeedMetersPerSecond,
                    maximumAccelerationMetersPerSecondSquared =
                        config.cruiseMaximumAccelerationMetersPerSecondSquared,
                    yawNedRadians = yaw,
                )
            }
            AutopilotPhase.RETURN_CLIMB -> profiledPositionTarget(
                input = input,
                destination = returnClimbHorizontal.copy(z = -cruiseAltitude),
                maximumSpeedMetersPerSecond = config.takeoffMaximumSpeedMetersPerSecond,
                maximumAccelerationMetersPerSecondSquared =
                    config.takeoffMaximumAccelerationMetersPerSecondSquared,
                yawNedRadians = yaw,
            )
            AutopilotPhase.RETURNING -> profiledPositionTarget(
                input = input,
                destination = Vector3d(0.0, 0.0, -cruiseAltitude),
                maximumSpeedMetersPerSecond = config.cruiseMaximumSpeedMetersPerSecond,
                maximumAccelerationMetersPerSecondSquared =
                    config.cruiseMaximumAccelerationMetersPerSecondSquared,
                yawNedRadians = yaw,
            )
            AutopilotPhase.LANDING -> landingPositionTarget(input, yaw)
            AutopilotPhase.STANDBY,
            AutopilotPhase.DISARMING,
            AutopilotPhase.COMPLETED,
            AutopilotPhase.ABORTED,
            AutopilotPhase.FAILED,
            AutopilotPhase.EMERGENCY_STOPPED,
            -> null
        }
        if (positionTarget == null) {
            activeTarget = null
            lastIssuedTargetFinal = false
        } else if (phase == AutopilotPhase.PREFLIGHT || phase == AutopilotPhase.ARMING) {
            activeTarget = positionTarget.localPositionNedMeters
            lastIssuedTargetFinal = true
        }
        return positionTarget
    }

    /** Generates a rest-to-rest target while limiting how far it may lead actual position. */
    private fun profiledPositionTarget(
        input: AutopilotInput,
        destination: Vector3d,
        maximumSpeedMetersPerSecond: Double,
        maximumAccelerationMetersPerSecondSquared: Double,
        yawNedRadians: Double?,
    ): PositionControlTarget? {
        val actual = input.flightController.estimate.localPositionNedMeters ?: return null
        val existing = phaseMotion
        val motion = if (existing == null || existing.phase != phase || existing.destination != destination) {
            val displacement = destination - actual
            val distance = displacement.norm()
            PhaseMotion(
                phase = phase,
                origin = actual,
                destination = destination,
                direction = if (distance > MOTION_EPSILON_METERS) {
                    displacement / distance
                } else {
                    Vector3d.ZERO
                },
                profile = SymmetricMotionProfile(
                    distanceMeters = distance,
                    maximumSpeedMetersPerSecond = maximumSpeedMetersPerSecond,
                    maximumAccelerationMetersPerSecondSquared =
                        maximumAccelerationMetersPerSecondSquared,
                ),
            ).also { phaseMotion = it }
        } else {
            existing
        }
        val elapsedSeconds = elapsedNanos(
            input.timestampNanos,
            requireNotNull(phaseStartedAtNanos),
        ) / NANOS_PER_SECOND
        val scheduled = motion.profile.sample(elapsedSeconds)
        val actualProgress = (actual - motion.origin)
            .dot(motion.direction)
            .coerceIn(0.0, motion.profile.distanceMeters)
        val maximumCommandedProgress = (actualProgress + config.maximumTrajectoryLeadMeters)
            .coerceAtMost(motion.profile.distanceMeters)
        val commandedProgress = minOf(scheduled.positionMeters, maximumCommandedProgress)
        val leadLimited = commandedProgress + MOTION_EPSILON_METERS < scheduled.positionMeters
        val commandedPosition = if (
            motion.profile.distanceMeters <= MOTION_EPSILON_METERS ||
            commandedProgress >= motion.profile.distanceMeters - MOTION_EPSILON_METERS
        ) {
            motion.destination
        } else {
            motion.origin + motion.direction * commandedProgress
        }
        val feedForward = if (leadLimited || scheduled.complete) {
            Vector3d.ZERO
        } else {
            motion.direction * scheduled.velocityMetersPerSecond
        }
        activeTarget = commandedPosition
        lastIssuedTargetFinal = scheduled.complete && commandedPosition == motion.destination
        return PositionControlTarget(
            localPositionNedMeters = commandedPosition,
            localVelocityFeedForwardNedMetersPerSecond = feedForward,
            yawNedRadians = yawNedRadians,
        )
    }

    /** Samples the two-rate descent and prevents the altitude target outrunning the aircraft. */
    private fun landingPositionTarget(
        input: AutopilotInput,
        yawNedRadians: Double?,
    ): PositionControlTarget {
        val profile = requireNotNull(landingProfile)
        val elapsedSeconds = elapsedNanos(
            input.timestampNanos,
            requireNotNull(phaseStartedAtNanos),
        ) / NANOS_PER_SECOND
        val scheduled = profile.sample(elapsedSeconds)
        val actualAltitude = input.flightController.estimate.altitudeAboveOriginMeters
        updateGroundRangeDecision(input)
        if (groundRangeDecision.controlsFinalDescent) {
            val targetAltitude = requireNotNull(
                groundRangeDecision.controllerAltitudeTargetMeters,
            )
            val target = landingHorizontal.copy(z = -targetAltitude)
            activeTarget = target
            lastIssuedTargetFinal =
                groundRangeDecision.targetVerticalDistanceMeters == 0.0
            return PositionControlTarget(
                localPositionNedMeters = target,
                localVelocityFeedForwardNedMetersPerSecond = Vector3d(
                    0.0,
                    0.0,
                    groundRangeDecision.descentFeedForwardMetersPerSecond,
                ),
                yawNedRadians = yawNedRadians,
            )
        }
        if (
            groundRangeDecision.state == LandingRangeAidState.ACQUIRING ||
            groundRangeDecision.state == LandingRangeAidState.HOLDING_FOR_VALID_RANGE
        ) {
            val holdAltitude = actualAltitude ?: scheduled.altitudeAboveOriginMeters
            val target = landingHorizontal.copy(z = -holdAltitude)
            activeTarget = target
            lastIssuedTargetFinal = false
            return PositionControlTarget(
                localPositionNedMeters = target,
                localVelocityFeedForwardNedMetersPerSecond = Vector3d.ZERO,
                yawNedRadians = yawNedRadians,
            )
        }
        val minimumCommandedAltitude = actualAltitude
            ?.minus(config.maximumTrajectoryLeadMeters)
            ?: scheduled.altitudeAboveOriginMeters
        val maximumCommandedAltitude = actualAltitude
            ?.coerceAtMost(landingInitialAltitudeMeters)
            ?: landingInitialAltitudeMeters
        val commandedAltitude = max(scheduled.altitudeAboveOriginMeters, minimumCommandedAltitude)
            .coerceAtMost(maximumCommandedAltitude)
        val trajectoryLimited = abs(
            commandedAltitude - scheduled.altitudeAboveOriginMeters,
        ) > MOTION_EPSILON_METERS
        val target = landingHorizontal.copy(z = -commandedAltitude)
        activeTarget = target
        lastIssuedTargetFinal = scheduled.complete && !trajectoryLimited
        return PositionControlTarget(
            localPositionNedMeters = target,
            localVelocityFeedForwardNedMetersPerSecond = Vector3d(
                0.0,
                0.0,
                if (trajectoryLimited) 0.0 else -scheduled.verticalVelocityMetersPerSecond,
            ),
            yawNedRadians = yawNedRadians,
        )
    }

    private fun updateGroundRangeDecision(input: AutopilotInput) {
        groundRangeDecision = groundRangeLandingAid.update(
            nowNanos = input.timestampNanos,
            observation = input.groundRange,
            estimatedAltitudeAboveOriginMeters =
                input.flightController.estimate.altitudeAboveOriginMeters,
            landingGroundAltitudeAboveOriginMeters = landingGroundAltitudeMeters,
            attitudeBodyToNed = input.flightController.estimate.attitudeBodyToNed,
            maximumTrajectoryLeadMeters = config.maximumTrajectoryLeadMeters,
        )
    }

    private fun landingRangeIssue(): AutopilotIssue? {
        if (
            groundRangeDecision.state != LandingRangeAidState.HOLDING_FOR_VALID_RANGE ||
            !groundRangeDecision.lossGraceExpired
        ) return null
        val reason = groundRangeDecision.rejection?.name ?: "UNAVAILABLE"
        return issue(
            AutopilotIssueCode.GROUND_RANGE_UNUSABLE,
            "Final descent is holding for a valid downward range observation ($reason)",
        )
    }

    private fun newCommand(now: Long, setpoint: FlightControlSetpoint): FlightControlCommand? {
        if (nextCommandSequence == Long.MAX_VALUE) {
            fail(
                now,
                issue(
                    AutopilotIssueCode.COMMAND_SEQUENCE_EXHAUSTED,
                    "The flight-controller command sequence was exhausted",
                ),
            )
            activeTarget = null
            return null
        }
        val validityNanos = millisToNanos(config.commandValidityMillis)
        if (now > Long.MAX_VALUE - validityNanos) {
            fail(
                now,
                issue(
                    AutopilotIssueCode.COMMAND_SEQUENCE_EXHAUSTED,
                    "The command validity deadline would overflow monotonic time",
                ),
            )
            activeTarget = null
            return null
        }
        val sequence = nextCommandSequence++
        lastIssuedCommandSequence = sequence
        lastIssuedCommandPhase = phase
        return FlightControlCommand.validFor(sequence, now, validityNanos, setpoint)
    }

    private fun preflightIssues(
        input: AutopilotInput,
        allowArmingInProgress: Boolean = false,
    ): List<AutopilotIssue> {
        val now = input.timestampNanos
        val issues = mutableListOf<AutopilotIssue>()
        val fix = input.navigationFix
        when {
            fix == null -> issues += issue(
                AutopilotIssueCode.NAVIGATION_FIX_MISSING,
                "A live absolute navigation fix is required",
            )
            !isFresh(fix.observedAtNanos, now, config.maximumNavigationAgeMillis) -> issues += issue(
                AutopilotIssueCode.NAVIGATION_FIX_STALE,
                "The absolute navigation fix is stale or from the future",
            )
            fix.horizontalAccuracyMeters > config.maximumNavigationAccuracyMeters -> issues += issue(
                AutopilotIssueCode.NAVIGATION_FIX_INACCURATE,
                "Navigation accuracy is ${fix.horizontalAccuracyMeters}m",
            )
            wgs84GeodesicDistanceMeters(fix.coordinate, flightPlan.origin) >
                config.maximumOriginErrorMeters -> issues += issue(
                AutopilotIssueCode.AIRCRAFT_TOO_FAR_FROM_ORIGIN,
                "The aircraft is not within ${config.maximumOriginErrorMeters}m of the saved origin",
            )
        }

        val reference = input.flightController.estimate.localReference.horizontalOrigin
        when {
            reference == null -> issues += issue(
                AutopilotIssueCode.CONTROLLER_REFERENCE_UNAVAILABLE,
                "The flight controller has not captured its local position reference",
            )
            wgs84GeodesicDistanceMeters(reference.toCoordinate(), flightPlan.origin) >
                config.maximumOriginErrorMeters -> issues += issue(
                AutopilotIssueCode.CONTROLLER_REFERENCE_MISMATCH,
                "The controller local reference does not match the saved takeoff origin",
            )
        }

        val landing = input.landingObservation
        when {
            landing.state == LandedState.UNAVAILABLE -> issues += issue(
                AutopilotIssueCode.LANDING_DETECTOR_UNAVAILABLE,
                "An independent landing detector is required before autonomous arming",
            )
            !isFresh(
                requireNotNull(landing.observedAtNanos),
                now,
                config.maximumLandingObservationAgeMillis,
            ) -> issues += issue(
                AutopilotIssueCode.LANDING_OBSERVATION_STALE,
                "The landing-detector observation is stale or from the future",
            )
            landing.state != LandedState.ON_GROUND -> issues += issue(
                AutopilotIssueCode.AIRCRAFT_NOT_ON_GROUND,
                "The aircraft must be confirmed on the ground before arming",
            )
        }
        preflightGroundRangeIssue(input)?.let(issues::add)
        issues += safetyIssues(input, includeDestination = true)

        val controllerArmingStateAccepted =
            input.flightController.armingState == FlightControllerArmingState.DISARMED ||
                allowArmingInProgress &&
                (
                    input.flightController.armingState == FlightControllerArmingState.ARMING ||
                        input.flightController.armingState == FlightControllerArmingState.ARMED
                )
        if (!controllerArmingStateAccepted) {
            issues += issue(
                AutopilotIssueCode.FLIGHT_CONTROLLER_NOT_DISARMED,
                "The flight controller must be disarmed during preflight",
            )
        }
        val output = input.flightController.lastOutput
        if (
            !input.flightController.health.canArm ||
            output == null ||
            output.tracking.commandSequence != lastIssuedCommandSequence
        ) {
            val reasons = input.flightController.health.issues.joinToString { it.code }
            issues += issue(
                AutopilotIssueCode.FLIGHT_CONTROLLER_NOT_READY,
                if (reasons.isBlank()) {
                    "The flight controller has not evaluated the current autopilot target"
                } else {
                    "The flight controller is not ready: $reasons"
                },
            )
        }
        return issues.distinctBy { it.code }
    }

    /** Ensures a required landing aid is physically plausible before committing to takeoff. */
    private fun preflightGroundRangeIssue(input: AutopilotInput): AutopilotIssue? {
        if (!config.requireGroundRangeForTouchdown) return null
        val observation = input.groundRange ?: return issue(
            AutopilotIssueCode.GROUND_RANGE_UNUSABLE,
            "A live downward rangefinder sample is required before autonomous arming",
        )
        val now = input.timestampNanos
        if (!isFresh(observation.observedAtNanos, now, config.maximumGroundRangeAgeMillis)) {
            return issue(
                AutopilotIssueCode.GROUND_RANGE_UNUSABLE,
                "The downward rangefinder sample is stale or from the future",
            )
        }
        if (observation.signalQualityPercent < config.groundRangeMinimumQualityPercent) {
            return issue(
                AutopilotIssueCode.GROUND_RANGE_UNUSABLE,
                "The downward rangefinder signal quality is below the configured minimum",
            )
        }
        val bodyDownInNed = input.flightController.estimate.attitudeBodyToNed
            .normalized()
            .rotate(Vector3d(0.0, 0.0, 1.0))
        if (bodyDownInNed.z < cos(config.groundRangeMaximumTiltRadians)) {
            return issue(
                AutopilotIssueCode.GROUND_RANGE_UNUSABLE,
                "The aircraft attitude does not provide a trustworthy downward range",
            )
        }
        val verticalDistance = observation.distanceMeters * bodyDownInNed.z
        if (
            !verticalDistance.isFinite() || verticalDistance <= 0.0 ||
            verticalDistance > config.groundRangeReleaseHeightMeters
        ) {
            return issue(
                AutopilotIssueCode.GROUND_RANGE_UNUSABLE,
                "The preflight ground range is outside the configured landing-aid envelope",
            )
        }
        return null
    }

    private fun safetyIssues(
        input: AutopilotInput,
        includeDestination: Boolean,
    ): List<AutopilotIssue> {
        val safety = input.safetyStatus
        val now = input.timestampNanos
        if (!safety.isAvailable) {
            return listOf(issue(AutopilotIssueCode.SAFETY_STATUS_UNAVAILABLE, "Safety status is unavailable"))
        }
        if (!isFresh(requireNotNull(safety.observedAtNanos), now, config.maximumSafetyStatusAgeMillis)) {
            return listOf(
                issue(
                    AutopilotIssueCode.SAFETY_STATUS_STALE,
                    "Safety status is stale or from the future",
                ),
            )
        }
        return buildList {
            if (!safety.routeAndAirspaceClear) add(
                issue(
                    AutopilotIssueCode.ROUTE_OR_AIRSPACE_UNSAFE,
                    "The route and airspace have not been cleared",
                ),
            )
            if (includeDestination && !safety.destinationLandingZoneClear) add(
                issue(
                    AutopilotIssueCode.DESTINATION_LANDING_ZONE_UNSAFE,
                    "The destination landing zone has not been cleared",
                ),
            )
            if (!safety.energyReserveSufficient) add(
                issue(
                    AutopilotIssueCode.ENERGY_RESERVE_INSUFFICIENT,
                    "Energy reserve is insufficient for the mission and contingency",
                ),
            )
            if (!safety.geofenceHealthy) add(
                issue(AutopilotIssueCode.GEOFENCE_UNHEALTHY, "The geofence is unavailable or violated"),
            )
            if (!safety.windWithinLimits) add(
                issue(
                    AutopilotIssueCode.WIND_LIMIT_EXCEEDED,
                    "Measured or forecast wind exceeds the configured flight envelope",
                ),
            )
        }
    }

    private fun inFlightSafetyIssues(input: AutopilotInput): List<AutopilotIssue> =
        safetyIssues(input, includeDestination = !abortingToOrigin)

    private fun validateMission(): List<AutopilotIssue> = buildList {
        val distance = wgs84GeodesicDistanceMeters(flightPlan.origin, flightPlan.destination)
        if (distance > config.maximumMissionDistanceMeters) add(
            issue(
                AutopilotIssueCode.MISSION_DISTANCE_EXCEEDED,
                "Mission distance ${"%.1f".format(distance)}m exceeds ${config.maximumMissionDistanceMeters}m",
            ),
        )
        if (
            flightPlan.targetAltitudeAboveOriginMeters >
            config.maximumCruiseAltitudeAboveOriginMeters
        ) add(
            issue(
                AutopilotIssueCode.CRUISE_ALTITUDE_EXCEEDED,
                "Cruise altitude exceeds the configured ${config.maximumCruiseAltitudeAboveOriginMeters}m limit",
            ),
        )
        if (
            abs(flightPlan.destinationGroundAltitudeAboveOriginMeters) >
            config.maximumDestinationGroundOffsetMeters
        ) add(
            issue(
                AutopilotIssueCode.DESTINATION_GROUND_OFFSET_EXCEEDED,
                "Destination ground offset exceeds the configured " +
                    "${config.maximumDestinationGroundOffsetMeters}m magnitude limit",
            ),
        )
        val highestEndpoint = max(0.0, flightPlan.destinationGroundAltitudeAboveOriginMeters)
        if (
            flightPlan.targetAltitudeAboveOriginMeters - highestEndpoint <
            config.minimumCruiseClearanceMeters
        ) add(
            issue(
                AutopilotIssueCode.INSUFFICIENT_CRUISE_CLEARANCE,
                "Cruise altitude must clear both endpoint surfaces by at least " +
                    "${config.minimumCruiseClearanceMeters}m",
            ),
        )
    }

    private fun handleAbortRequest(input: AutopilotInput) {
        when (phase) {
            AutopilotPhase.STANDBY,
            AutopilotPhase.PREFLIGHT,
            -> {
                abortingToOrigin = true
                missionOutcomeAborted = true
                latchedIssues = emptyList()
                transition(AutopilotPhase.ABORTED, input.timestampNanos)
            }
            AutopilotPhase.ARMING -> {
                abortingToOrigin = true
                missionOutcomeAborted = true
                latchedIssues = emptyList()
                if (input.flightController.armingState == FlightControllerArmingState.ARMED) {
                    everArmed = true
                    beginReturnToOrigin(input, emptyList())
                } else {
                    transition(AutopilotPhase.DISARMING, input.timestampNanos)
                }
            }
            AutopilotPhase.TAKEOFF,
            AutopilotPhase.CRUISE,
            -> if (!abortingToOrigin) beginReturnToOrigin(input, emptyList())
            AutopilotPhase.LANDING -> if (landingCommittedToTouchdown) {
                missionOutcomeAborted = true
            } else if (!abortingToOrigin) {
                beginReturnToOrigin(input, emptyList())
            }
            AutopilotPhase.RETURN_CLIMB,
            AutopilotPhase.RETURNING,
            AutopilotPhase.DISARMING,
            AutopilotPhase.COMPLETED,
            AutopilotPhase.ABORTED,
            AutopilotPhase.FAILED,
            AutopilotPhase.EMERGENCY_STOPPED,
            -> Unit
        }
    }

    private fun beginReturnToOrigin(input: AutopilotInput, causes: List<AutopilotIssue>) {
        val position = input.flightController.estimate.localPositionNedMeters
        if (position == null) {
            missionOutcomeAborted = true
            latchIssues(
                causes + issue(
                    AutopilotIssueCode.LOCAL_POSITION_UNAVAILABLE,
                    "A controlled return cannot begin without local position",
                ),
            )
            return
        }
        abortingToOrigin = true
        missionOutcomeAborted = true
        landingCommittedToTouchdown = false
        latchIssues(causes)
        returnClimbHorizontal = Vector3d(position.x, position.y, 0.0)
        transition(AutopilotPhase.RETURN_CLIMB, input.timestampNanos)
    }

    private fun beginLanding(
        input: AutopilotInput,
        horizontal: Vector3d,
        groundAltitudeMeters: Double,
    ) {
        val initialAltitude = max(
            groundAltitudeMeters,
            input.flightController.estimate.altitudeAboveOriginMeters
                ?: flightPlan.targetAltitudeAboveOriginMeters,
        )
        transition(AutopilotPhase.LANDING, input.timestampNanos)
        landingCommittedToTouchdown = false
        landingHorizontal = Vector3d(horizontal.x, horizontal.y, 0.0)
        landingGroundAltitudeMeters = groundAltitudeMeters
        landingInitialAltitudeMeters = initialAltitude
        landingProfile = TwoRateLandingDescentProfile(
            initialAltitudeAboveOriginMeters = initialAltitude,
            groundAltitudeAboveOriginMeters = groundAltitudeMeters,
            flareHeightMeters = config.flareHeightMeters,
            descentRateMetersPerSecond = config.landingDescentRateMetersPerSecond,
            finalDescentRateMetersPerSecond = config.finalDescentRateMetersPerSecond,
            maximumAccelerationMetersPerSecondSquared =
                config.landingMaximumAccelerationMetersPerSecondSquared,
        )
    }

    /** Commits to a controlled descent at the current horizontal position without commanding climb. */
    private fun beginLandingAtCurrentPosition(
        input: AutopilotInput,
        causes: List<AutopilotIssue>,
    ) {
        val position = input.flightController.estimate.localPositionNedMeters
        if (position == null) {
            missionOutcomeAborted = true
            landingCommittedToTouchdown = true
            latchIssues(
                causes + issue(
                    AutopilotIssueCode.LOCAL_POSITION_UNAVAILABLE,
                    "A controlled local landing cannot begin without local position",
                ),
            )
            return
        }
        missionOutcomeAborted = true
        latchIssues(causes)
        beginLanding(
            input = input,
            horizontal = position,
            // No terrain surface is available between endpoints. Use the higher known endpoint as
            // a conservative floor; a real terrain/AGL provider remains mandatory for flight.
            groundAltitudeMeters = max(
                0.0,
                flightPlan.destinationGroundAltitudeAboveOriginMeters,
            ),
        )
        landingCommittedToTouchdown = true
    }

    /** Selects return or land-in-place containment without ever disarming an airborne vehicle. */
    private fun containInFlightSafetyLoss(
        input: AutopilotInput,
        problems: List<AutopilotIssue>,
    ) {
        if (problems.isEmpty() || !isAirborneMissionPhase()) return
        missionOutcomeAborted = true
        latchIssues(problems)
        val requiresLandingNow = problems.any { it.code in LAND_NOW_ISSUE_CODES }
        if (phase == AutopilotPhase.LANDING && (landingCommittedToTouchdown || requiresLandingNow)) {
            landingCommittedToTouchdown = true
            return
        }
        if (
            requiresLandingNow ||
            abortingToOrigin ||
            phase == AutopilotPhase.RETURN_CLIMB ||
            phase == AutopilotPhase.RETURNING
        ) {
            beginLandingAtCurrentPosition(input, problems)
        } else {
            beginReturnToOrigin(input, problems)
        }
    }

    private fun latchIssues(problems: List<AutopilotIssue>) {
        latchedIssues = (latchedIssues + problems).distinctBy { it.code }
    }

    private fun touchdownConfirmed(input: AutopilotInput): Boolean {
        val now = input.timestampNanos
        val landing = input.landingObservation
        val estimate = input.flightController.estimate
        val position = estimate.localPositionNedMeters
        val velocity = estimate.localVelocityNedMetersPerSecond
        val groundRangeConfirmsProximity = !config.requireGroundRangeForTouchdown ||
            groundRangeDecision.state == LandingRangeAidState.ACTIVE &&
            (groundRangeDecision.verticalDistanceMeters ?: Double.POSITIVE_INFINITY) <=
            config.maximumTouchdownGroundRangeMeters
        val valid = airborneObserved &&
            landing.state == LandedState.ON_GROUND &&
            isFresh(
                requireNotNull(landing.observedAtNanos),
                now,
                config.maximumLandingObservationAgeMillis,
            ) &&
            groundRangeConfirmsProximity &&
            position != null &&
            velocity != null &&
            hypot(position.x - landingHorizontal.x, position.y - landingHorizontal.y) <=
            config.touchdownHorizontalToleranceMeters &&
            abs((estimate.altitudeAboveOriginMeters ?: Double.POSITIVE_INFINITY) -
                landingGroundAltitudeMeters) <= config.touchdownAltitudeToleranceMeters &&
            hypot(velocity.x, velocity.y) <= config.maximumTouchdownHorizontalSpeedMetersPerSecond &&
            abs(estimate.verticalVelocityMetersPerSecond ?: Double.POSITIVE_INFINITY) <=
            config.maximumTouchdownVerticalSpeedMetersPerSecond
        if (!valid) {
            touchdownSinceNanos = null
            return false
        }
        val observedAt = requireNotNull(landing.observedAtNanos)
        val firstObservedAt = touchdownSinceNanos ?: observedAt.also { touchdownSinceNanos = it }
        if (observedAt < firstObservedAt) {
            touchdownSinceNanos = null
            return false
        }
        return elapsedNanos(observedAt, firstObservedAt) >=
            millisToNanos(config.touchdownConfirmationMillis)
    }

    private fun targetReachedAndSettled(input: AutopilotInput): Boolean {
        val tracking = input.flightController.lastOutput?.tracking
        val reached = lastIssuedCommandPhase == phase &&
            lastIssuedTargetFinal &&
            tracking?.commandSequence == lastIssuedCommandSequence &&
            tracking?.targetStatus == ControlTargetStatus.REACHED
        if (!reached) {
            reachedSinceNanos = null
            return false
        }
        val since = reachedSinceNanos ?: input.timestampNanos.also { reachedSinceNanos = it }
        return elapsedNanos(input.timestampNanos, since) >= millisToNanos(config.targetSettleMillis)
    }

    private fun observeLandingDetector(input: AutopilotInput) {
        val observation = input.landingObservation
        if (
            (everArmed || input.flightController.armingState == FlightControllerArmingState.ARMED) &&
            observation.state == LandedState.AIRBORNE &&
            isFresh(
                requireNotNull(observation.observedAtNanos),
                input.timestampNanos,
                config.maximumLandingObservationAgeMillis,
            )
        ) {
            airborneObserved = true
        }
    }

    private fun handleControllerTerminalState(input: AutopilotInput): Boolean {
        val controllerState = input.flightController.armingState
        if (controllerState == FlightControllerArmingState.EMERGENCY_STOPPED) {
            emergencyStop(input.timestampNanos)
            return true
        }
        if (controllerState == FlightControllerArmingState.FAILSAFE) {
            fail(
                input.timestampNanos,
                issue(
                    AutopilotIssueCode.FLIGHT_CONTROLLER_FAILSAFE,
                    "The flight controller entered its latched failsafe",
                ),
            )
            return true
        }
        if (
            everArmed &&
            controllerState == FlightControllerArmingState.DISARMED &&
            isAirborneMissionPhase()
        ) {
            fail(
                input.timestampNanos,
                issue(
                    AutopilotIssueCode.FLIGHT_CONTROLLER_DISARMED_IN_FLIGHT,
                    "The flight controller became disarmed during an active mission",
                ),
            )
            return true
        }
        return false
    }

    private fun enforceTimeouts(input: AutopilotInput) {
        if (phase in TERMINAL_PHASES || phase == AutopilotPhase.STANDBY) return
        val now = input.timestampNanos
        val missionStart = missionStartedAtNanos
        if (
            !missionTimeoutHandled &&
            missionStart != null &&
            elapsedNanos(now, missionStart) > millisToNanos(config.maximumMissionDurationMillis)
        ) {
            missionTimeoutHandled = true
            val timeout = issue(AutopilotIssueCode.MISSION_TIMEOUT, "Maximum mission duration elapsed")
            when {
                isAirborneMissionPhase() -> containTimeout(input, timeout)
                phase == AutopilotPhase.DISARMING -> {
                    missionOutcomeAborted = true
                    latchIssues(listOf(timeout))
                }
                else -> fail(now, timeout)
            }
            return
        }
        val allowedMillis = when (phase) {
            AutopilotPhase.PREFLIGHT -> config.preflightTimeoutMillis
            AutopilotPhase.ARMING -> config.armingTimeoutMillis
            AutopilotPhase.TAKEOFF -> config.takeoffTimeoutMillis
            AutopilotPhase.CRUISE -> config.cruiseTimeoutMillis
            AutopilotPhase.LANDING -> config.landingTimeoutMillis
            AutopilotPhase.RETURN_CLIMB,
            AutopilotPhase.RETURNING,
            -> config.returnTimeoutMillis
            else -> return
        }
        if (elapsedNanos(now, requireNotNull(phaseStartedAtNanos)) <= millisToNanos(allowedMillis)) return
        if (phaseTimeoutHandledForStartNanos == phaseStartedAtNanos) return
        phaseTimeoutHandledForStartNanos = phaseStartedAtNanos
        val timeout = issue(AutopilotIssueCode.PHASE_TIMEOUT, "$phase exceeded its timeout")
        if (isAirborneMissionPhase()) {
            containTimeout(input, timeout)
        } else {
            fail(now, timeout)
        }
    }

    /** One-shot timeout containment; no airborne timeout is allowed to request DISARM. */
    private fun containTimeout(input: AutopilotInput, timeout: AutopilotIssue) {
        missionOutcomeAborted = true
        latchIssues(listOf(timeout))
        when (phase) {
            AutopilotPhase.TAKEOFF,
            AutopilotPhase.CRUISE,
            -> beginReturnToOrigin(input, listOf(timeout))
            AutopilotPhase.RETURN_CLIMB,
            AutopilotPhase.RETURNING,
            -> beginLandingAtCurrentPosition(input, listOf(timeout))
            AutopilotPhase.LANDING -> landingCommittedToTouchdown = true
            else -> Unit
        }
    }

    private fun routeCorridorIssue(controller: FlightControllerSnapshot): AutopilotIssue? {
        val current = controller.estimate.localPositionNedMeters ?: return null
        val destination = destinationHorizontal(controller) ?: return null
        val distance = distanceToSegmentMeters(current, Vector3d.ZERO, destination)
        return if (distance > config.routeCorridorRadiusMeters) {
            issue(
                AutopilotIssueCode.ROUTE_CORRIDOR_EXCEEDED,
                "Aircraft is ${"%.1f".format(distance)}m outside the direct-route corridor",
            )
        } else {
            null
        }
    }

    private fun destinationHorizontal(controller: FlightControllerSnapshot): Vector3d? =
        controller.estimate.localReference.horizontalOrigin?.let { origin ->
            localHorizontalNedMeters(origin, flightPlan.destination)
        }

    private fun routeYawRadians(controller: FlightControllerSnapshot): Double? =
        destinationHorizontal(controller)?.let { target ->
            if (hypot(target.x, target.y) < 1e-6) null else atan2(target.y, target.x)
        }

    private fun yawTowardOrigin(controller: FlightControllerSnapshot): Double? {
        val current = controller.estimate.localPositionNedMeters ?: return null
        return if (hypot(current.x, current.y) < 1e-6) null else atan2(-current.y, -current.x)
    }

    private fun isAirborneMissionPhase(): Boolean = phase in setOf(
        AutopilotPhase.TAKEOFF,
        AutopilotPhase.CRUISE,
        AutopilotPhase.LANDING,
        AutopilotPhase.RETURN_CLIMB,
        AutopilotPhase.RETURNING,
    )

    private fun fail(now: Long, problem: AutopilotIssue) {
        if (phase == AutopilotPhase.EMERGENCY_STOPPED) return
        latchedIssues = (latchedIssues + problem).distinctBy { it.code }
        transition(AutopilotPhase.FAILED, now)
    }

    private fun emergencyStop(now: Long) {
        latchedIssues = emptyList()
        transition(AutopilotPhase.EMERGENCY_STOPPED, now)
    }

    private fun transition(next: AutopilotPhase, now: Long) {
        phase = next
        phaseStartedAtNanos = now
        reachedSinceNanos = null
        touchdownSinceNanos = null
        activeTarget = null
        phaseMotion = null
        landingProfile = null
        groundRangeLandingAid.reset()
        groundRangeDecision = GroundRangeLandingDecision(LandingRangeAidState.INACTIVE)
        lastIssuedTargetFinal = false
        phaseTimeoutHandledForStartNanos = null
    }

    companion object {
        private const val NANOS_PER_SECOND = 1_000_000_000.0
        private const val MOTION_EPSILON_METERS = 1e-6
        private val TERMINAL_PHASES = setOf(
            AutopilotPhase.COMPLETED,
            AutopilotPhase.ABORTED,
            AutopilotPhase.FAILED,
            AutopilotPhase.EMERGENCY_STOPPED,
        )
        private val LAND_NOW_ISSUE_CODES = setOf(
            AutopilotIssueCode.ENERGY_RESERVE_INSUFFICIENT,
            AutopilotIssueCode.WIND_LIMIT_EXCEEDED,
            AutopilotIssueCode.SAFETY_STATUS_UNAVAILABLE,
            AutopilotIssueCode.SAFETY_STATUS_STALE,
        )
    }
}

/** Motion state retained for one mission phase so every loop samples the same trajectory. */
private data class PhaseMotion(
    val phase: AutopilotPhase,
    val origin: Vector3d,
    val destination: Vector3d,
    val direction: Vector3d,
    val profile: SymmetricMotionProfile,
)

/** Converts a geographic point into the controller's short-distance local NED horizontal frame. */
internal fun localHorizontalNedMeters(origin: GeoPoint, target: GeoCoordinate): Vector3d {
    val earthRadiusMeters = 6_378_137.0
    val latitudeDelta = (target.latitude - origin.latitudeDegrees) * PI / 180.0
    val longitudeDeltaDegrees = normalizeLongitudeDeltaDegrees(target.longitude - origin.longitudeDegrees)
    val longitudeDelta = longitudeDeltaDegrees * PI / 180.0
    val meanLatitude = (target.latitude + origin.latitudeDegrees) * 0.5 * PI / 180.0
    return Vector3d(
        x = latitudeDelta * earthRadiusMeters,
        y = longitudeDelta * earthRadiusMeters * cos(meanLatitude),
        z = 0.0,
    )
}

/** Horizontal distance from [point] to the finite segment [start]..[end]. */
internal fun distanceToSegmentMeters(point: Vector3d, start: Vector3d, end: Vector3d): Double {
    val segmentX = end.x - start.x
    val segmentY = end.y - start.y
    val lengthSquared = segmentX * segmentX + segmentY * segmentY
    if (lengthSquared <= 1e-12) return hypot(point.x - start.x, point.y - start.y)
    val projection = (
        (point.x - start.x) * segmentX + (point.y - start.y) * segmentY
        ) / lengthSquared
    val bounded = projection.coerceIn(0.0, 1.0)
    return hypot(
        point.x - (start.x + bounded * segmentX),
        point.y - (start.y + bounded * segmentY),
    )
}

private fun GeoPoint.toCoordinate(): GeoCoordinate = GeoCoordinate(latitudeDegrees, longitudeDegrees)

private fun normalizeLongitudeDeltaDegrees(deltaDegrees: Double): Double =
    ((deltaDegrees + 180.0) % 360.0 + 360.0) % 360.0 - 180.0

private fun issue(code: AutopilotIssueCode, message: String) = AutopilotIssue(code, message)

private fun isFresh(observedAtNanos: Long, nowNanos: Long, maximumAgeMillis: Long): Boolean =
    nowNanos >= observedAtNanos && nowNanos - observedAtNanos <= millisToNanos(maximumAgeMillis)

private fun elapsedNanos(now: Long, earlier: Long): Long = if (now >= earlier) now - earlier else Long.MAX_VALUE

private fun millisToNanos(milliseconds: Long): Long =
    if (milliseconds > Long.MAX_VALUE / 1_000_000L) Long.MAX_VALUE else milliseconds * 1_000_000L
