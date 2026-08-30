package ir.hrka.shahbaz.flightcontroller

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Documents the FlightControllerTest type and the role it plays in this module.
 */
class FlightControllerTest {
    /**
     * Runs the disarmedControllerProducesOnlyDiagnosticStoppedValues operation.
     */
    @Test
    fun disarmedControllerProducesOnlyDiagnosticStoppedValues() {
        val now = 1_000_000_000L
        val output = FlightController.create().step(
            input = healthyInput(now),
            command = command(1, now, levelAttitudeTarget()),
            lifecycleRequest = FlightControllerLifecycleRequest.HOLD_DISARMED,
        )

        assertEquals(FlightControllerArmingState.DISARMED, output.armingState)
        assertTrue(output.actuatorActions.isEmpty())
        assertEquals(listOf(1_000, 1_000, 1_000, 1_000), output.motors.map { it.pulseMicros })
        assertEquals(ControlTargetStatus.INACTIVE, output.tracking.targetStatus)
    }

    /**
     * Runs the runNeverArmsWithoutExplicitArmRequest operation.
     */
    @Test
    fun runNeverArmsWithoutExplicitArmRequest() {
        val now = 1_000_000_000L
        val output = FlightController.create().step(
            input = healthyInput(now),
            command = command(1, now, levelAttitudeTarget()),
            lifecycleRequest = FlightControllerLifecycleRequest.RUN,
        )

        assertEquals(FlightControllerArmingState.DISARMED, output.armingState)
        assertTrue(output.actuatorActions.isEmpty())
    }

    /**
     * Runs the armWaitsForFreshBoardConfirmationBeforeMotorOutput operation.
     */
    @Test
    fun armWaitsForFreshBoardConfirmationBeforeMotorOutput() {
        val controller = FlightController.create()
        val now = 1_000_000_000L
        val command = command(1, now, levelAttitudeTarget())

        val request = controller.step(
            input = healthyInput(now, boardArmed = false),
            command = command,
            lifecycleRequest = FlightControllerLifecycleRequest.ARM,
        )
        assertEquals(FlightControllerArmingState.ARMING, request.armingState)
        assertEquals(listOf(FlightControllerActuatorAction.Arm), request.actuatorActions)
        assertFalse(request.actuatorActions.any { it is FlightControllerActuatorAction.ApplyMotorPwm })

        val confirmed = controller.step(
            input = healthyInput(now + 10.ms, boardArmed = true),
            command = command,
            lifecycleRequest = FlightControllerLifecycleRequest.RUN,
        )
        assertEquals(FlightControllerArmingState.ARMED, confirmed.armingState)
        assertTrue(confirmed.actuatorActions.single() is FlightControllerActuatorAction.ApplyMotorPwm)
        assertEquals(listOf(1_500, 1_500, 1_500, 1_500), confirmed.motors.map { it.pulseMicros })
    }

    /**
     * Runs the unsafeArmIsRejectedWithStructuredIssue operation.
     */
    @Test
    fun unsafeArmIsRejectedWithStructuredIssue() {
        val now = 1_000_000_000L
        val output = FlightController.create().step(
            input = healthyInput(now).copy(
                board = FlightControllerBoardState(
                    ready = true,
                    actuatorAvailable = false,
                    activeMotorChannels = 4,
                    observedAtNanos = now,
                    actuatorStateObservedAtNanos = now,
                ),
            ),
            command = command(1, now, levelAttitudeTarget()),
            lifecycleRequest = FlightControllerLifecycleRequest.ARM,
        )

        assertEquals(FlightControllerArmingState.DISARMED, output.armingState)
        assertEquals(listOf(FlightControllerActuatorAction.Disarm), output.actuatorActions)
        assertTrue(output.health.hasIssue(FlightControllerHealthIssueCode.ACTUATOR_UNAVAILABLE))
    }

    /**
     * Runs the unexpectedActuatorCountRejectsArming operation.
     */
    @Test
    fun unexpectedActuatorCountRejectsArming() {
        listOf(3, 5).forEachIndexed { index, channelCount ->
            val now = 1_000_000_000L + index * 10.ms
            val input = healthyInput(now).copy(
                board = healthyInput(now).board.copy(activeMotorChannels = channelCount),
            )

            val output = FlightController.create().step(
                input = input,
                command = command(1, now, levelAttitudeTarget()),
                lifecycleRequest = FlightControllerLifecycleRequest.ARM,
            )

            assertEquals(FlightControllerArmingState.DISARMED, output.armingState)
            assertEquals(listOf(FlightControllerActuatorAction.Disarm), output.actuatorActions)
            assertTrue(output.health.hasIssue(FlightControllerHealthIssueCode.MOTOR_CHANNELS_UNAVAILABLE))
        }
    }

    /**
     * Runs the armingConfirmationTimeoutFailsClosed operation.
     */
    @Test
    fun armingConfirmationTimeoutFailsClosed() {
        val controller = FlightController.create(
            FlightControllerConfig(armingConfirmationTimeoutMillis = 20),
        )
        val now = 1_000_000_000L
        val command = command(1, now, levelAttitudeTarget())
        controller.step(healthyInput(now), command, FlightControllerLifecycleRequest.ARM)

        val output = controller.step(
            healthyInput(now + 30.ms, boardArmed = false),
            command,
            FlightControllerLifecycleRequest.RUN,
        )

        assertEquals(FlightControllerArmingState.FAILSAFE, output.armingState)
        assertEquals(listOf(FlightControllerActuatorAction.Disarm), output.actuatorActions)
        assertTrue(output.health.hasIssue(FlightControllerHealthIssueCode.ARMING_CONFIRMATION_TIMEOUT))
    }

    /**
     * Runs the unexpectedBoardDisarmForcesFailsafe operation.
     */
    @Test
    fun unexpectedBoardDisarmForcesFailsafe() {
        val now = 1_000_000_000L
        val command = command(1, now, levelAttitudeTarget())
        val controller = armedController(now, command)

        val output = controller.step(
            healthyInput(now + 20.ms, boardArmed = false),
            command,
            FlightControllerLifecycleRequest.RUN,
        )

        assertEquals(FlightControllerArmingState.FAILSAFE, output.armingState)
        assertEquals(listOf(FlightControllerActuatorAction.Disarm), output.actuatorActions)
        assertTrue(output.health.hasIssue(FlightControllerHealthIssueCode.ACTUATOR_DISARMED_UNEXPECTEDLY))
    }

    /**
     * Runs the positiveRollAttitudeErrorUsesConfiguredQuadXSigns operation.
     */
    @Test
    fun positiveRollAttitudeErrorUsesConfiguredQuadXSigns() {
        val now = 1_000_000_000L
        val initial = command(1, now, levelAttitudeTarget())
        val controller = armedController(now, initial)
        val target = AttitudeControlTarget.fromEuler(
            rollRadians = 0.10,
            pitchRadians = 0.0,
            yawRadians = 0.0,
            thrust = CollectiveThrust(0.5),
        )

        val output = controller.step(
            healthyInput(now + 20.ms, boardArmed = true),
            command(2, now + 20.ms, target),
            FlightControllerLifecycleRequest.RUN,
        )

        val pulse = output.motors.associate { it.channel to it.pulseMicros }
        assertTrue(output.controllerSetpoints.torqueNormalized.x > 0.0)
        assertTrue(requireNotNull(pulse[1]) > requireNotNull(pulse[0]))
        assertTrue(requireNotNull(pulse[2]) > requireNotNull(pulse[3]))
    }

    /**
     * Runs the measuredPositiveRollRateProducesOpposingTorque operation.
     */
    @Test
    fun measuredPositiveRollRateProducesOpposingTorque() {
        val now = 1_000_000_000L
        val target = RateControlTarget(Vector3d.ZERO, CollectiveThrust(0.5))
        val initial = command(1, now, target)
        val controller = armedController(now, initial)

        val output = controller.step(
            healthyInput(
                now + 20.ms,
                boardArmed = true,
                angularVelocity = Vector3d(0.5, 0.0, 0.0),
            ),
            command(2, now + 20.ms, target),
            FlightControllerLifecycleRequest.RUN,
        )

        assertTrue(output.controllerSetpoints.torqueNormalized.x < 0.0)
        assertTrue(output.tracking.rateErrorRadPerSecond.x < 0.0)
    }

    /**
     * Runs the altitudeAboveCurrentCommandsMoreThanHoverThrottle operation.
     */
    @Test
    fun altitudeAboveCurrentCommandsMoreThanHoverThrottle() {
        val now = 1_000_000_000L
        val initial = command(1, now, levelAttitudeTarget())
        val controller = armedController(now, initial)
        val climb = command(
            2,
            now + 20.ms,
            AltitudeControlTarget(altitudeAboveOriginMeters = 1.0),
        )

        val output = controller.step(
            healthyInput(now + 20.ms, boardArmed = true, altitudeMslMeters = 100.0),
            climb,
            FlightControllerLifecycleRequest.RUN,
        )

        assertEquals(FlightControllerArmingState.ARMED, output.armingState)
        assertTrue(output.controllerSetpoints.throttle > 0.5)
        assertTrue(requireNotNull(output.tracking.altitudeErrorMeters) > 0.0)
    }

    /**
     * Runs the altitudeErrorShrinksAsMeasurementApproachesTarget operation.
     */
    @Test
    fun altitudeErrorShrinksAsMeasurementApproachesTarget() {
        val now = 1_000_000_000L
        val initial = command(1, now, levelAttitudeTarget())
        val controller = armedController(now, initial)
        val climb = command(
            2,
            now + 20.ms,
            AltitudeControlTarget(altitudeAboveOriginMeters = 1.0),
            validForMillis = 500,
        )
        val first = controller.step(
            healthyInput(now + 20.ms, boardArmed = true, altitudeMslMeters = 100.0),
            climb,
            FlightControllerLifecycleRequest.RUN,
        )
        val second = controller.step(
            healthyInput(now + 120.ms, boardArmed = true, altitudeMslMeters = 100.7),
            climb,
            FlightControllerLifecycleRequest.RUN,
        )

        assertTrue(abs(requireNotNull(second.tracking.altitudeErrorMeters)) < abs(requireNotNull(first.tracking.altitudeErrorMeters)))
    }

    /**
     * Runs the headingWraparoundUsesShortestRotationAcrossPi operation.
     */
    @Test
    fun headingWraparoundUsesShortestRotationAcrossPi() {
        val now = 1_000_000_000L
        val current = Quaterniond.fromEuler(0.0, 0.0, 179.0 * PI / 180.0)
        val initialTarget = AttitudeControlTarget(current, CollectiveThrust(0.5))
        val controller = armedController(
            now,
            command(1, now, initialTarget),
            attitude = current,
        )
        val desired = AttitudeControlTarget.fromEuler(
            rollRadians = 0.0,
            pitchRadians = 0.0,
            yawRadians = -179.0 * PI / 180.0,
            thrust = CollectiveThrust(0.5),
        )

        val output = controller.step(
            healthyInput(now + 20.ms, boardArmed = true, attitude = current),
            command(2, now + 20.ms, desired),
            FlightControllerLifecycleRequest.RUN,
        )

        assertTrue(abs(output.controllerSetpoints.bodyRatesRadPerSecond.z) < 0.2)
        assertTrue(abs(output.controllerSetpoints.bodyRatesRadPerSecond.z) > 0.01)
    }

    /**
     * Runs the eastPositionErrorCommandsWestRollWithoutMissionSequencing operation.
     */
    @Test
    fun eastPositionErrorCommandsWestRollWithoutMissionSequencing() {
        val now = 1_000_000_000L
        val origin = GeoPoint(35.0, 51.0, 100.0)
        val initial = command(1, now, levelAttitudeTarget(), validForMillis = 800)
        val controller = armedController(now, initial, location = origin)
        val east = GeoPoint(
            latitudeDegrees = 35.0,
            longitudeDegrees = 51.0 + eastOffsetLongitudeDegrees(35.0, 1.0),
            altitudeAboveMeanSeaLevelMeters = 100.0,
        )

        val output = controller.step(
            healthyInput(now + 120.ms, boardArmed = true, location = east),
            command(
                2,
                now + 120.ms,
                PositionControlTarget(localPositionNedMeters = Vector3d.ZERO),
            ),
            FlightControllerLifecycleRequest.RUN,
        )

        assertTrue(requireNotNull(output.tracking.positionErrorNedMeters).y < 0.0)
        assertTrue(output.controllerSetpoints.attitude.toEuler().rollRadians < 0.0)
    }

    /**
     * Runs the northVelocityTargetCommandsNoseDownPitch operation.
     */
    @Test
    fun northVelocityTargetCommandsNoseDownPitch() {
        val now = 1_000_000_000L
        val origin = GeoPoint(35.0, 51.0, 100.0)
        val initial = command(1, now, levelAttitudeTarget(), validForMillis = 800)
        val controller = armedController(now, initial, location = origin)

        val output = controller.step(
            healthyInput(now + 120.ms, boardArmed = true, location = origin),
            command(
                2,
                now + 120.ms,
                VelocityControlTarget(Vector3d(1.0, 0.0, 0.0)),
            ),
            FlightControllerLifecycleRequest.RUN,
        )

        assertTrue(requireNotNull(output.tracking.velocityErrorNedMetersPerSecond).x > 0.0)
        assertTrue(output.controllerSetpoints.attitude.toEuler().pitchRadians < 0.0)
    }

    /**
     * Runs the reachedPrimitiveIsReportedWithoutGeneratingAnotherCommand operation.
     */
    @Test
    fun reachedPrimitiveIsReportedWithoutGeneratingAnotherCommand() {
        val now = 1_000_000_000L
        val initial = command(1, now, levelAttitudeTarget())
        val controller = armedController(now, initial)
        val hold = command(
            2,
            now + 20.ms,
            AltitudeControlTarget(altitudeAboveOriginMeters = 0.0),
        )

        val output = controller.step(
            healthyInput(now + 20.ms, boardArmed = true, altitudeMslMeters = 100.0),
            hold,
            FlightControllerLifecycleRequest.RUN,
        )

        assertEquals(ControlTargetStatus.REACHED, output.tracking.targetStatus)
        assertEquals(2L, output.tracking.commandSequence)
        assertTrue(output.actuatorActions.single() is FlightControllerActuatorAction.ApplyMotorPwm)
    }

    /**
     * Runs the newerCommandReplacesTargetAndOlderCommandIsRejected operation.
     */
    @Test
    fun newerCommandReplacesTargetAndOlderCommandIsRejected() {
        val now = 1_000_000_000L
        val level = command(1, now, levelAttitudeTarget(), validForMillis = 500)
        val controller = armedController(now, level)
        val roll = command(
            2,
            now + 20.ms,
            AttitudeControlTarget.fromEuler(0.1, 0.0, 0.0, CollectiveThrust(0.5)),
        )
        val replacement = controller.step(
            healthyInput(now + 20.ms, boardArmed = true),
            roll,
            FlightControllerLifecycleRequest.RUN,
        )
        assertTrue(replacement.controllerSetpoints.bodyRatesRadPerSecond.x > 0.0)

        val rejected = controller.step(
            healthyInput(now + 30.ms, boardArmed = true),
            level,
            FlightControllerLifecycleRequest.RUN,
        )
        assertEquals(FlightControllerArmingState.FAILSAFE, rejected.armingState)
        assertEquals(ControlTargetStatus.REJECTED, rejected.tracking.targetStatus)
        assertTrue(rejected.health.hasIssue(FlightControllerHealthIssueCode.COMMAND_OUT_OF_ORDER))
    }

    /**
     * Runs the expiredPilotCommandForcesArmedControllerToFailsafe operation.
     */
    @Test
    fun expiredPilotCommandForcesArmedControllerToFailsafe() {
        val now = 1_000_000_000L
        val shortCommand = command(1, now, levelAttitudeTarget(), validForMillis = 40)
        val controller = armedController(now, shortCommand, confirmationOffsetMillis = 10)

        val output = controller.step(
            healthyInput(now + 50.ms, boardArmed = true),
            shortCommand,
            FlightControllerLifecycleRequest.RUN,
        )

        assertEquals(FlightControllerArmingState.FAILSAFE, output.armingState)
        assertEquals(listOf(FlightControllerActuatorAction.Disarm), output.actuatorActions)
        assertTrue(output.health.hasIssue(FlightControllerHealthIssueCode.COMMAND_EXPIRED))
    }

    /**
     * Runs the staleIndividualGyroscopeFailsEvenWhenInputFrameTimeIsFresh operation.
     */
    @Test
    fun staleIndividualGyroscopeFailsEvenWhenInputFrameTimeIsFresh() {
        val config = FlightControllerConfig(criticalSensorMaxAgeMillis = 5)
        val now = 1_000_000_000L
        val command = command(1, now, levelAttitudeTarget())
        val controller = armedController(now, command, config = config)

        val output = controller.step(
            healthyInput(
                now + 20.ms,
                sensorTimestampNanos = now + 10.ms,
                boardArmed = true,
            ),
            command,
            FlightControllerLifecycleRequest.RUN,
        )

        assertEquals(FlightControllerArmingState.FAILSAFE, output.armingState)
        assertTrue(output.health.hasIssue(FlightControllerHealthIssueCode.ANGULAR_RATE_UNAVAILABLE))
    }

    /**
     * Runs the duplicateInputTimestampIsRejected operation.
     */
    @Test
    fun duplicateInputTimestampIsRejected() {
        val now = 1_000_000_000L
        val command = command(1, now, levelAttitudeTarget())
        val controller = armedController(now, command)

        val output = controller.step(
            healthyInput(now + 10.ms, boardArmed = true),
            command,
            FlightControllerLifecycleRequest.RUN,
        )

        assertEquals(FlightControllerArmingState.FAILSAFE, output.armingState)
        assertTrue(output.health.hasIssue(FlightControllerHealthIssueCode.INPUT_TIMESTAMP_NOT_MONOTONIC))
    }

    /**
     * Runs the aggressiveRateTargetSaturatesButOutputsRemainFiniteAndBounded operation.
     */
    @Test
    fun aggressiveRateTargetSaturatesButOutputsRemainFiniteAndBounded() {
        val now = 1_000_000_000L
        val target = RateControlTarget(
            bodyRatesRadPerSecond = Vector3d(20.0, 20.0, 20.0),
            thrust = CollectiveThrust(0.5),
        )
        val command = command(1, now, target)
        val controller = armedController(now, command)

        val output = controller.step(
            healthyInput(now + 20.ms, boardArmed = true),
            command,
            FlightControllerLifecycleRequest.RUN,
        )

        assertTrue(output.tracking.motorOutputSaturated)
        assertTrue(output.motors.all { it.normalized.isFinite() && it.normalized in 0.0..1.0 })
        assertTrue(output.motors.all { it.pulseMicros in 1_000..2_000 })
    }

    /**
     * Runs the rateIntegralDoesNotWindUpWhileAllocationIsSaturated operation.
     */
    @Test
    fun rateIntegralDoesNotWindUpWhileAllocationIsSaturated() {
        val controller = RateController(FlightControllerConfig())
        repeat(500) {
            controller.update(
                rateSetpoint = Vector3d(3.0, 0.0, 0.0),
                measuredRates = Vector3d.ZERO,
                dtSeconds = 0.01,
                allowIntegral = false,
            )
        }

        val recovered = controller.update(
            rateSetpoint = Vector3d.ZERO,
            measuredRates = Vector3d.ZERO,
            dtSeconds = 0.01,
            allowIntegral = true,
        )
        assertEquals(0.0, recovered.x, 1e-12)
    }

    /**
     * Runs the quadXMixerUsesDocumentedRollPitchAndYawSigns operation.
     */
    @Test
    fun quadXMixerUsesDocumentedRollPitchAndYawSigns() {
        val allocator = QuadXControlAllocator(FlightControllerConfig())

        val roll = allocator.allocate(0.5, Vector3d(0.1, 0.0, 0.0)).motors
            .associate { it.channel to it.normalized }
        assertTrue(requireNotNull(roll[1]) > requireNotNull(roll[0]))
        assertTrue(requireNotNull(roll[2]) > requireNotNull(roll[3]))

        val pitch = allocator.allocate(0.5, Vector3d(0.0, 0.1, 0.0)).motors
            .associate { it.channel to it.normalized }
        assertTrue(requireNotNull(pitch[0]) > requireNotNull(pitch[1]))
        assertTrue(requireNotNull(pitch[2]) > requireNotNull(pitch[3]))

        val yaw = allocator.allocate(0.5, Vector3d(0.0, 0.0, 0.1)).motors
            .associate { it.channel to it.normalized }
        assertTrue(requireNotNull(yaw[0]) > requireNotNull(yaw[2]))
        assertTrue(requireNotNull(yaw[1]) > requireNotNull(yaw[3]))
    }

    /**
     * Runs the invalidNumericalModelsAreRejectedBeforeControlMath operation.
     */
    @Test
    fun invalidNumericalModelsAreRejectedBeforeControlMath() {
        assertThrows(IllegalArgumentException::class.java) { Vector3d(Double.NaN, 0.0, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { CollectiveThrust(Double.POSITIVE_INFINITY) }
        assertThrows(IllegalArgumentException::class.java) {
            AttitudeControlTarget(Quaterniond(0.0, 0.0, 0.0, 0.0), CollectiveThrust(0.5))
        }
        assertThrows(IllegalArgumentException::class.java) { GeoPoint(91.0, 0.0) }
        assertThrows(IllegalArgumentException::class.java) {
            ExternalSensorSnapshot(
                relativeHumidityPercent = TimedSensorValue(101.0, 0L),
            )
        }
    }

    /**
     * Runs the emergencyStopOverridesCommandAndRemainsLatchedUntilReset operation.
     */
    @Test
    fun emergencyStopOverridesCommandAndRemainsLatchedUntilReset() {
        val now = 1_000_000_000L
        val command = command(1, now, levelAttitudeTarget())
        val controller = armedController(now, command)

        val stopped = controller.step(
            healthyInput(now + 20.ms, boardArmed = true),
            null,
            FlightControllerLifecycleRequest.EMERGENCY_STOP,
        )
        assertEquals(FlightControllerArmingState.EMERGENCY_STOPPED, stopped.armingState)
        assertEquals(listOf(FlightControllerActuatorAction.EmergencyStop), stopped.actuatorActions)

        val stillStopped = controller.step(
            healthyInput(now + 30.ms, boardArmed = false),
            command,
            FlightControllerLifecycleRequest.DISARM,
        )
        assertEquals(FlightControllerArmingState.EMERGENCY_STOPPED, stillStopped.armingState)
        assertTrue(stillStopped.actuatorActions.isEmpty())
    }

    /**
     * Runs the resetIsRejectedWhileArmedAndClearsCommandSequenceAfterDisarm operation.
     */
    @Test
    fun resetIsRejectedWhileArmedAndClearsCommandSequenceAfterDisarm() {
        val now = 1_000_000_000L
        val original = command(10, now, levelAttitudeTarget())
        val controller = armedController(now, original)
        assertThrows(IllegalStateException::class.java) { controller.reset() }

        controller.step(
            healthyInput(now + 20.ms, boardArmed = true),
            original,
            FlightControllerLifecycleRequest.DISARM,
        )
        controller.reset()
        val reusedSequence = command(
            1,
            now + 30.ms,
            AttitudeControlTarget.fromEuler(0.1, 0.0, 0.0, CollectiveThrust(0.5)),
        )
        val output = controller.step(
            healthyInput(now + 30.ms),
            reusedSequence,
            FlightControllerLifecycleRequest.ARM,
        )
        assertEquals(FlightControllerArmingState.ARMING, output.armingState)
        assertFalse(output.health.hasIssue(FlightControllerHealthIssueCode.COMMAND_OUT_OF_ORDER))
    }

    /**
     * Runs the estimatorDifferentiatesLocationUsingSampleTimeNotLoopTime operation.
     */
    @Test
    fun estimatorDifferentiatesLocationUsingSampleTimeNotLoopTime() {
        val estimator = StateEstimator(FlightControllerConfig())
        val now = 1_000_000_000L
        val origin = GeoPoint(35.0, 51.0, 100.0)
        estimator.update(healthyInput(now, location = origin))
        val east = GeoPoint(
            35.0,
            51.0 + eastOffsetLongitudeDegrees(35.0, 1.0),
            100.0,
        )
        val estimate = estimator.update(
            healthyInput(now + 100.ms, location = east),
        )

        assertEquals(10.0, requireNotNull(estimate.localVelocityNedMetersPerSecond).y, 0.05)
        val repeated = estimator.update(
            healthyInput(
                timestampNanos = now + 110.ms,
                sensorTimestampNanos = now + 100.ms,
                location = east,
            ),
        )
        assertEquals(
            requireNotNull(estimate.localVelocityNedMetersPerSecond).y,
            requireNotNull(repeated.localVelocityNedMetersPerSecond).y,
            1e-12,
        )
    }

    /**
     * Runs the phoneMountPresetMapsScreenUpTopForwardToBodyFrd operation.
     */
    @Test
    fun phoneMountPresetMapsScreenUpTopForwardToBodyFrd() {
        val transform = AndroidPhoneSensorMounting.SCREEN_UP_TOP_FORWARD.bodyFromDeviceRotation

        val deviceTop = transform.rotate(Vector3d(0.0, 1.0, 0.0))
        val deviceRight = transform.rotate(Vector3d(1.0, 0.0, 0.0))
        val deviceOutOfScreen = transform.rotate(Vector3d(0.0, 0.0, 1.0))

        assertVectorEquals(Vector3d(1.0, 0.0, 0.0), deviceTop)
        assertVectorEquals(Vector3d(0.0, 1.0, 0.0), deviceRight)
        assertVectorEquals(Vector3d(0.0, 0.0, -1.0), deviceOutOfScreen)
    }

    /**
     * Runs the armedController operation.
     */
    private fun armedController(
        now: Long,
        command: FlightControlCommand,
        attitude: Quaterniond = Quaterniond.IDENTITY,
        location: GeoPoint? = null,
        confirmationOffsetMillis: Long = 10,
        config: FlightControllerConfig = FlightControllerConfig(),
    ): FlightController {
        val controller = FlightController.create(config)
        val requested = controller.step(
            healthyInput(now, boardArmed = false, attitude = attitude, location = location),
            command,
            FlightControllerLifecycleRequest.ARM,
        )
        assertEquals(FlightControllerArmingState.ARMING, requested.armingState)
        val confirmationTime = now + confirmationOffsetMillis.ms
        val confirmed = controller.step(
            healthyInput(
                confirmationTime,
                boardArmed = true,
                attitude = attitude,
                location = location,
            ),
            command,
            FlightControllerLifecycleRequest.RUN,
        )
        assertEquals(FlightControllerArmingState.ARMED, confirmed.armingState)
        return controller
    }

    /**
     * Runs the command operation.
     */
    private fun command(
        sequence: Long,
        now: Long,
        target: FlightControlSetpoint,
        validForMillis: Long = 250,
    ): FlightControlCommand = FlightControlCommand.validFor(
        sequence = sequence,
        issuedAtNanos = now,
        validForNanos = validForMillis.ms,
        setpoint = target,
    )

    /**
     * Runs the levelAttitudeTarget operation.
     */
    private fun levelAttitudeTarget(): AttitudeControlTarget =
        AttitudeControlTarget.fromEuler(
            rollRadians = 0.0,
            pitchRadians = 0.0,
            yawRadians = 0.0,
            thrust = CollectiveThrust(0.5),
        )

    /**
     * Runs the healthyInput operation.
     */
    private fun healthyInput(
        timestampNanos: Long,
        sensorTimestampNanos: Long = timestampNanos,
        boardArmed: Boolean = false,
        attitude: Quaterniond = Quaterniond.IDENTITY,
        angularVelocity: Vector3d = Vector3d.ZERO,
        altitudeMslMeters: Double = 100.0,
        location: GeoPoint? = null,
    ): FlightControllerInput = FlightControllerInput(
        timestampNanos = timestampNanos,
        phone = PhoneSensorSnapshot(
            accelerationBodyMps2 = TimedSensorValue(
                Vector3d(0.0, 0.0, -9.80665),
                sensorTimestampNanos,
            ),
            angularVelocityBodyRadPerSecond = TimedSensorValue(
                angularVelocity,
                sensorTimestampNanos,
            ),
            magneticFieldBodyMicroTesla = TimedSensorValue(
                Vector3d(25.0, 0.0, 40.0),
                sensorTimestampNanos,
            ),
            attitudeBodyToNed = TimedSensorValue(attitude, sensorTimestampNanos),
            location = location?.let { TimedSensorValue(it, sensorTimestampNanos) },
        ),
        external = ExternalSensorSnapshot(
            temperatureCelsius = TimedSensorValue(25.0, sensorTimestampNanos),
            relativeHumidityPercent = TimedSensorValue(40.0, sensorTimestampNanos),
            pressurePascal = TimedSensorValue(101_325, sensorTimestampNanos),
            altitudeAboveMeanSeaLevelMeters = TimedSensorValue(
                altitudeMslMeters,
                sensorTimestampNanos,
            ),
        ),
        board = FlightControllerBoardState(
            ready = true,
            actuatorAvailable = true,
            activeMotorChannels = 4,
            actuatorArmed = boardArmed,
            observedAtNanos = timestampNanos,
            actuatorStateObservedAtNanos = timestampNanos,
        ),
    )

    /**
     * Runs the FlightControllerHealth operation.
     */
    private fun FlightControllerHealth.hasIssue(code: FlightControllerHealthIssueCode): Boolean =
        issues.any { it.code == code }

    /**
     * Runs the eastOffsetLongitudeDegrees operation.
     */
    private fun eastOffsetLongitudeDegrees(latitudeDegrees: Double, metersEast: Double): Double {
        val latitudeRadians = latitudeDegrees * PI / 180.0
        return metersEast / (6_378_137.0 * cos(latitudeRadians)) * 180.0 / PI
    }

    /**
     * Runs the assertVectorEquals operation.
     */
    private fun assertVectorEquals(expected: Vector3d, actual: Vector3d) {
        assertEquals(expected.x, actual.x, 1e-9)
        assertEquals(expected.y, actual.y, 1e-9)
        assertEquals(expected.z, actual.z, 1e-9)
    }

    /**
     * Exposes the Long value.
     */
    private val Long.ms: Long
        get() = this * 1_000_000L

    /**
     * Exposes the Int value.
     */
    private val Int.ms: Long
        get() = toLong() * 1_000_000L
}
