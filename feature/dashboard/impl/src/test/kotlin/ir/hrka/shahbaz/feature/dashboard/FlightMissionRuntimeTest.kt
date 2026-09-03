package ir.hrka.shahbaz.feature.dashboard

import ir.hrka.shahbaz.autopilot.Autopilot
import ir.hrka.shahbaz.autopilot.AutopilotInput
import ir.hrka.shahbaz.autopilot.AutopilotNavigationFix
import ir.hrka.shahbaz.autopilot.AutopilotOutput
import ir.hrka.shahbaz.autopilot.AutopilotPhase
import ir.hrka.shahbaz.autopilot.AutopilotRequest
import ir.hrka.shahbaz.autopilot.AutopilotSnapshot
import ir.hrka.shahbaz.core.model.FlightPlan
import ir.hrka.shahbaz.core.model.GeoCoordinate
import ir.hrka.shahbaz.flightcontroller.AndroidPhoneSensorFrame
import ir.hrka.shahbaz.flightcontroller.ControlTracking
import ir.hrka.shahbaz.flightcontroller.ControllerSetpoints
import ir.hrka.shahbaz.flightcontroller.FlightControlCommand
import ir.hrka.shahbaz.flightcontroller.FlightController
import ir.hrka.shahbaz.flightcontroller.FlightControllerActuatorAction
import ir.hrka.shahbaz.flightcontroller.FlightControllerArmingState
import ir.hrka.shahbaz.flightcontroller.FlightControllerInput
import ir.hrka.shahbaz.flightcontroller.FlightControllerLifecycleRequest
import ir.hrka.shahbaz.flightcontroller.FlightControllerOutput
import ir.hrka.shahbaz.flightcontroller.FlightControllerSnapshot
import ir.hrka.shahbaz.flightcontroller.MotorPwmActuatorOutput
import ir.hrka.shahbaz.flightcontroller.PhoneSensorSnapshot
import ir.hrka.shahbaz.hardwareconnection.BoardActuatorCommandResult
import ir.hrka.shahbaz.hardwareconnection.BoardActuatorRejection
import ir.hrka.shahbaz.hardwareconnection.BoardConnectionState
import ir.hrka.shahbaz.hardwareconnection.BoardDeviceInfo
import ir.hrka.shahbaz.hardwareconnection.BoardDeviceStatus
import ir.hrka.shahbaz.hardwareconnection.BoardMotorPulse
import ir.hrka.shahbaz.hardwareconnection.BoardTarget
import ir.hrka.shahbaz.hardwareconnection.BoardTelemetrySnapshot
import ir.hrka.shahbaz.hardwareconnection.BoardUsbDevice
import ir.hrka.shahbaz.hardwareconnection.Ms5611Telemetry
import ir.hrka.shahbaz.hardwareconnection.RangefinderRole
import ir.hrka.shahbaz.hardwareconnection.SensorSample
import ir.hrka.shahbaz.hardwareconnection.SensorSampleQuality
import ir.hrka.shahbaz.hardwareconnection.SensorState
import ir.hrka.shahbaz.hardwareconnection.Sht30Telemetry
import ir.hrka.shahbaz.hardwareconnection.Vl53l0xTelemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Focused verification of the dashboard composition boundary and its safety escalation. */
class FlightMissionRuntimeTest {
    @Test
    fun `hardware sink preserves action mapping motor order and generation timestamp`() {
        val port = RecordingPort()
        val sink = HardwareConnectionActuatorSink(port)
        val pwm = FlightControllerActuatorAction.ApplyMotorPwm(
            generatedAtNanos = 987_654_321L,
            motors = listOf(
                MotorPwmActuatorOutput(0, 1_100),
                MotorPwmActuatorOutput(1, 1_200),
                MotorPwmActuatorOutput(2, 1_300),
                MotorPwmActuatorOutput(3, 1_400),
            ),
        )

        sink.apply(FlightControllerActuatorAction.Arm)
        sink.apply(pwm)
        sink.apply(FlightControllerActuatorAction.Disarm)
        sink.apply(FlightControllerActuatorAction.EmergencyStop)

        assertEquals(listOf("arm", "motors", "disarm", "emergency"), port.calls)
        assertEquals(987_654_321L, port.generatedAtNanos)
        assertEquals(
            listOf(
                BoardMotorPulse(0, 1_100),
                BoardMotorPulse(1, 1_200),
                BoardMotorPulse(2, 1_300),
                BoardMotorPulse(3, 1_400),
            ),
            port.pulses,
        )
    }

    @Test
    fun `controller input preserves source timestamps and excludes no unavailable sample`() {
        val coordinate = GeoCoordinate(35.6892, 51.3890)
        val telemetry = BoardTelemetrySnapshot(
            sht30 = SensorState.Available(
                sample(
                    value = Sht30Telemetry(temperatureCelsius = 21.5, relativeHumidityPercent = 42.0),
                    receivedAtMillis = 110L,
                    observedAtMillis = 90L,
                ),
            ),
            ms5611 = SensorState.Available(
                sample(
                    value = Ms5611Telemetry(
                        pressurePascal = 90_000,
                        temperatureCelsius = 20.0,
                        altitudeAboveMeanSeaLevelMeters = 1_050.0,
                        qnhHectopascal = 1_013.25,
                    ),
                    receivedAtMillis = 120L,
                    observedAtMillis = 95L,
                ),
            ),
            deviceStatus = BoardDeviceStatus(
                safetyStateCode = 0,
                communicationStateCode = 0,
                telemetryEnabled = true,
                actuatorArmed = true,
                sht30Online = true,
                ms5611Online = true,
                receivedAtElapsedRealtimeMillis = 130L,
            ),
        )
        val input = buildControllerInput(
            nowNanos = 200_000_000L,
            phone = PhoneSensorSnapshot(),
            connection = readyConnection(),
            telemetry = telemetry,
            missionInputs = FlightMissionInputs(
                navigationFix = AutopilotNavigationFix(
                    coordinate = coordinate,
                    horizontalAccuracyMeters = 2.0,
                    observedAtNanos = 100_000_000L,
                ),
                navigationAltitudeAboveMeanSeaLevelMeters = 1_040.0,
            ),
        )

        assertEquals(100_000_000L, input.phone.location?.timestampNanos)
        assertEquals(1_040.0, input.phone.location?.value?.altitudeAboveMeanSeaLevelMeters)
        assertEquals(90_000_000L, input.external.temperatureCelsius?.timestampNanos)
        assertEquals(95_000_000L, input.external.pressurePascal?.timestampNanos)
        assertTrue(input.board.ready)
        assertTrue(input.board.actuatorAvailable)
        assertEquals(4, input.board.activeMotorChannels)
        assertTrue(input.board.actuatorArmed)
        assertEquals(130_000_000L, input.board.actuatorStateObservedAtNanos)

        val unavailable = BoardTelemetrySnapshot(
            sht30 = SensorState.AwaitingFirstSample,
            ms5611 = SensorState.Unavailable(
                ir.hrka.shahbaz.hardwareconnection.SensorUnavailableReason.BOARD_DISCONNECTED,
            ),
        ).toExternalSensorSnapshot()
        assertNull(unavailable.temperatureCelsius)
        assertNull(unavailable.pressurePascal)
        assertNull(unavailable.altitudeAboveMeanSeaLevelMeters)
    }

    @Test
    fun `only a live downward rangefinder sample crosses the autopilot boundary`() {
        val groundValue = Vl53l0xTelemetry(
            role = RangefinderRole.GROUND,
            distanceMillimeters = 1_234,
            rawRangeStatus = 0,
            signalQualityPercent = 87,
        )
        val live = BoardTelemetrySnapshot(
            groundRange = SensorState.Available(
                sample(
                    groundValue,
                    receivedAtMillis = 456L,
                    observedAtMillis = 123L,
                ),
            ),
            upRange = SensorState.Available(
                sample(
                    groundValue.copy(
                        role = RangefinderRole.UP,
                        distanceMillimeters = 500,
                    ),
                    receivedAtMillis = 457L,
                ),
            ),
        ).toAutopilotGroundRangeObservation()

        assertEquals(1.234, live?.distanceMeters)
        assertEquals(87, live?.signalQualityPercent)
        assertEquals(123_000_000L, live?.observedAtNanos)

        val stale = BoardTelemetrySnapshot(
            groundRange = SensorState.Stale(
                lastSample = sample(groundValue, receivedAtMillis = 456L),
                staleSinceElapsedRealtimeMillis = 500L,
            ),
        ).toAutopilotGroundRangeObservation()
        val wrongRoleOnly = BoardTelemetrySnapshot(
            upRange = SensorState.Available(
                sample(
                    groundValue.copy(role = RangefinderRole.UP),
                    receivedAtMillis = 456L,
                ),
            ),
        ).toAutopilotGroundRangeObservation()

        assertNull(stale)
        assertNull(wrongRoleOnly)
    }

    @Test
    fun `neutral policy adapters preserve every arming lifecycle and position command field`() {
        FlightControllerArmingState.entries.forEach { state ->
            val contract = FlightControllerSnapshot(armingState = state)
                .toAutopilotControllerSnapshot()
            assertEquals(state.name, contract.armingState.name)
        }

        ir.hrka.shahbaz.flightcontracts.FlightControllerLifecycleRequest.entries.forEach { request ->
            assertEquals(request.name, request.toControllerLifecycleRequest().name)
        }

        val contractCommand = ir.hrka.shahbaz.flightcontracts.FlightControlCommand(
            sequence = 42L,
            issuedAtNanos = 100L,
            validUntilNanos = 200L,
            setpoint = ir.hrka.shahbaz.flightcontracts.PositionControlTarget(
                localPositionNedMeters = ir.hrka.shahbaz.flightcontracts.Vector3d(1.0, 2.0, -3.0),
                localVelocityFeedForwardNedMetersPerSecond =
                    ir.hrka.shahbaz.flightcontracts.Vector3d(0.1, 0.2, 0.3),
                yawNedRadians = 0.4,
                yawRateFeedForwardRadPerSecond = 0.5,
            ),
        )
        val mapped = contractCommand.toControllerCommand()
        val target = mapped.setpoint as ir.hrka.shahbaz.flightcontroller.PositionControlTarget
        assertEquals(42L, mapped.sequence)
        assertEquals(100L, mapped.issuedAtNanos)
        assertEquals(200L, mapped.validUntilNanos)
        assertEquals(ir.hrka.shahbaz.flightcontroller.Vector3d(1.0, 2.0, -3.0), target.localPositionNedMeters)
        assertEquals(
            ir.hrka.shahbaz.flightcontroller.Vector3d(0.1, 0.2, 0.3),
            target.localVelocityFeedForwardNedMetersPerSecond,
        )
        assertEquals(0.4, target.yawNedRadians)
        assertEquals(0.5, target.yawRateFeedForwardRadPerSecond, 0.0)
    }

    @Test
    fun `pending operator intents are bounded and highest safety priority wins`() {
        val autopilot = RecordingAutopilot()
        val controller = RecordingController()
        val runtime = runtime(autopilot = autopilot, controller = controller)

        assertTrue(runtime.startMission())
        assertTrue(runtime.abortMission())
        assertTrue(runtime.runSingleIterationForTest())

        assertEquals(listOf(AutopilotRequest.ABORT), autopilot.requests)
        runtime.close()
    }

    @Test
    fun `actuator rejection stops remaining actions and promotes next iteration to emergency`() {
        val autopilot = RecordingAutopilot()
        val controller = RecordingController(
            actions = listOf(
                FlightControllerActuatorAction.ApplyMotorPwm(
                    generatedAtNanos = 1L,
                    motors = listOf(MotorPwmActuatorOutput(0, 1_100)),
                ),
                FlightControllerActuatorAction.Arm,
            ),
        )
        val sink = RecordingSink(rejectMotorFrames = true)
        val runtime = runtime(
            autopilot = autopilot,
            controller = controller,
            sink = sink,
        )

        assertTrue(runtime.runSingleIterationForTest())
        assertEquals(
            listOf(
                FlightControllerActuatorAction.ApplyMotorPwm::class.java,
                FlightControllerActuatorAction.EmergencyStop::class.java,
            ),
            sink.actions.map { it::class.java },
        )
        assertEquals(
            FlightMissionRuntimeFailureCode.ACTUATOR_COMMAND_REJECTED,
            runtime.state.value.failure?.code,
        )
        assertEquals(AutopilotPhase.EMERGENCY_STOPPED, runtime.state.value.autopilot.phase)

        assertTrue(runtime.runSingleIterationForTest())
        assertEquals(AutopilotRequest.EMERGENCY_STOP, autopilot.requests.last())
        assertFalse(sink.actions.any { it == FlightControllerActuatorAction.Arm })
        runtime.close()
    }

    @Test
    fun `control loop failure clears running state and cannot be silently restarted`() {
        val sink = RecordingSink()
        val runtime = runtime(
            autopilot = ThrowingAutopilot(),
            controller = RecordingController(),
            sink = sink,
        )

        assertTrue(runtime.prepare())
        assertFalse(runtime.state.value.running)
        assertEquals(
            FlightMissionRuntimeFailureCode.CONTROL_LOOP_FAILED,
            runtime.state.value.failure?.code,
        )
        assertTrue(sink.actions.contains(FlightControllerActuatorAction.EmergencyStop))
        assertFalse(runtime.prepare())
        assertFalse(runtime.state.value.running)
        runtime.close()
    }

    private fun runtime(
        autopilot: Autopilot,
        controller: RecordingController,
        sink: FlightActuatorSink = RecordingSink(),
    ): FlightMissionRuntime = FlightMissionRuntime(
        inputs = MutableStateFlow(FlightMissionInputs()),
        phoneSensorSource = FakePhoneSource(),
        boardStateSource = FakeBoardSource(),
        flightController = controller,
        autopilot = autopilot,
        actuatorSink = sink,
        clock = IncrementingClock(),
        loopDispatcher = Dispatchers.Unconfined,
        loopPeriodMillis = 10L,
    )

    private class IncrementingClock : FlightMonotonicClock {
        private var now = 0L
        override fun nowNanos(): Long {
            now += 10_000_000L
            return now
        }
    }

    private class FakePhoneSource : FlightPhoneSensorSource {
        override val frame = MutableStateFlow(AndroidPhoneSensorFrame(0L, PhoneSensorSnapshot()))
        override fun start() = Unit
        override fun close() = Unit
    }

    private class FakeBoardSource : FlightBoardStateSource {
        override val connectionState: StateFlow<BoardConnectionState> =
            MutableStateFlow(BoardConnectionState.Stopped)
        override val telemetry: StateFlow<BoardTelemetrySnapshot> =
            MutableStateFlow(BoardTelemetrySnapshot())
    }

    private class RecordingAutopilot : Autopilot {
        private val plan = flightPlan()
        private val mutableSnapshot = MutableStateFlow(
            AutopilotSnapshot(AutopilotPhase.STANDBY, plan),
        )
        override val snapshot: StateFlow<AutopilotSnapshot> = mutableSnapshot
        val requests = mutableListOf<AutopilotRequest>()

        override fun step(input: AutopilotInput, request: AutopilotRequest): AutopilotOutput {
            requests += request
            if (request == AutopilotRequest.EMERGENCY_STOP) {
                mutableSnapshot.value = mutableSnapshot.value.copy(
                    phase = AutopilotPhase.EMERGENCY_STOPPED,
                )
            }
            return AutopilotOutput(
                timestampNanos = input.timestampNanos,
                snapshot = mutableSnapshot.value,
                flightControlCommand = null,
                lifecycleRequest =
                    ir.hrka.shahbaz.flightcontracts.FlightControllerLifecycleRequest.HOLD_DISARMED,
            )
        }
    }

    private class ThrowingAutopilot : Autopilot {
        private val mutableSnapshot = MutableStateFlow(
            AutopilotSnapshot(AutopilotPhase.STANDBY, flightPlan()),
        )
        override val snapshot: StateFlow<AutopilotSnapshot> = mutableSnapshot

        override fun step(input: AutopilotInput, request: AutopilotRequest): AutopilotOutput {
            error("Injected control-loop failure")
        }
    }

    private class RecordingController(
        private val actions: List<FlightControllerActuatorAction> = emptyList(),
    ) : FlightController {
        private val mutableSnapshot = MutableStateFlow(FlightControllerSnapshot())
        override val snapshot: StateFlow<FlightControllerSnapshot> = mutableSnapshot

        override fun step(
            input: FlightControllerInput,
            command: FlightControlCommand?,
            lifecycleRequest: FlightControllerLifecycleRequest,
        ): FlightControllerOutput {
            val output = FlightControllerOutput(
                timestampNanos = input.timestampNanos,
                armingState = FlightControllerArmingState.DISARMED,
                estimate = mutableSnapshot.value.estimate,
                health = mutableSnapshot.value.health,
                controllerSetpoints = ControllerSetpoints(),
                tracking = ControlTracking(),
                motors = emptyList(),
                actuatorActions = actions,
            )
            mutableSnapshot.value = mutableSnapshot.value.copy(lastOutput = output)
            return output
        }

        override fun reset() = Unit
    }

    private class RecordingSink(
        private val rejectMotorFrames: Boolean = false,
    ) : FlightActuatorSink {
        val actions = mutableListOf<FlightControllerActuatorAction>()

        override fun apply(action: FlightControllerActuatorAction): BoardActuatorCommandResult {
            actions += action
            return if (rejectMotorFrames && action is FlightControllerActuatorAction.ApplyMotorPwm) {
                BoardActuatorCommandResult.Rejected(
                    BoardActuatorRejection.NOT_READY,
                    "Actuator output unavailable",
                )
            } else {
                BoardActuatorCommandResult.Queued(1)
            }
        }
    }

    private class RecordingPort : BoardActuatorPort {
        val calls = mutableListOf<String>()
        var pulses: List<BoardMotorPulse> = emptyList()
        var generatedAtNanos: Long? = null

        override fun armActuators(): BoardActuatorCommandResult = queued("arm")

        override fun sendMotorPulses(
            pulses: List<BoardMotorPulse>,
            generatedAtElapsedRealtimeNanos: Long,
        ): BoardActuatorCommandResult {
            calls += "motors"
            this.pulses = pulses
            generatedAtNanos = generatedAtElapsedRealtimeNanos
            return BoardActuatorCommandResult.Queued(pulses.size)
        }

        override fun disarmActuators(): BoardActuatorCommandResult = queued("disarm")

        override fun emergencyStopActuators(): BoardActuatorCommandResult = queued("emergency")

        private fun queued(call: String): BoardActuatorCommandResult {
            calls += call
            return BoardActuatorCommandResult.Queued(1)
        }
    }

    private companion object {
        fun flightPlan() = FlightPlan(
            origin = GeoCoordinate(35.6892, 51.3890),
            destination = GeoCoordinate(35.6900, 51.3900),
            targetAltitudeAboveOriginMeters = 20.0,
            destinationGroundAltitudeAboveOriginMeters = 0.0,
        )

        fun readyConnection() = BoardConnectionState.Ready(
            device = BoardUsbDevice(1, "board", 0x303A, 0x4001),
            deviceInfo = BoardDeviceInfo(
                protocolVersion = 2,
                target = BoardTarget.ESP32_S3,
                supportedMotorChannels = 4,
                supportedServoChannels = 0,
                detectedFlashBytes = 16L * 1024 * 1024,
                detectedPsramBytes = 8L * 1024 * 1024,
                boardValidationIssueMask = 0L,
                activeMotorChannels = 4,
                activeServoChannels = 0,
                actuatorAvailable = true,
                actuatorsEnabledByConfiguration = true,
            ),
            connectedAtElapsedRealtimeMillis = 1L,
        )

        fun <T> sample(
            value: T,
            receivedAtMillis: Long,
            observedAtMillis: Long = receivedAtMillis,
        ) = SensorSample(
            value = value,
            sequence = 1L,
            deviceTimestampMicros = 1uL,
            receivedAtElapsedRealtimeMillis = receivedAtMillis,
            quality = SensorSampleQuality(
                recoveredAfterError = false,
                rateLimited = false,
                rawValidityFlags = 0L,
                rawQualityFlags = 0L,
                rawHealthFlags = 0L,
            ),
            observedAtElapsedRealtimeMillis = observedAtMillis,
        )
    }
}
