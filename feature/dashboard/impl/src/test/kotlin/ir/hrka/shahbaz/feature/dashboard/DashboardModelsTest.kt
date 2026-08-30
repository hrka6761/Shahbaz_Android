/** Tests pure dashboard derivation, orientation math, and independent failure behavior. */
package ir.hrka.shahbaz.feature.dashboard

import ir.hrka.compass.CalibrationStatus
import ir.hrka.compass.CompassAccuracy
import ir.hrka.compass.CompassAccuracyLevel
import ir.hrka.compass.CompassDirection
import ir.hrka.compass.CompassReading
import ir.hrka.compass.CompassSensorSource
import ir.hrka.compass.NorthReference
import ir.hrka.shahbaz.hardwareconnection.BoardConnectionState
import ir.hrka.shahbaz.hardwareconnection.BoardDeviceInfo
import ir.hrka.shahbaz.hardwareconnection.BoardDisconnectReason
import ir.hrka.shahbaz.hardwareconnection.BoardTarget
import ir.hrka.shahbaz.hardwareconnection.BoardTelemetrySnapshot
import ir.hrka.shahbaz.hardwareconnection.BoardUsbDevice
import ir.hrka.shahbaz.hardwareconnection.Ms5611Telemetry
import ir.hrka.shahbaz.hardwareconnection.SensorError
import ir.hrka.shahbaz.hardwareconnection.SensorErrorCode
import ir.hrka.shahbaz.hardwareconnection.SensorSample
import ir.hrka.shahbaz.hardwareconnection.SensorSampleQuality
import ir.hrka.shahbaz.hardwareconnection.SensorState
import ir.hrka.shahbaz.hardwareconnection.Sht30Telemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

/** JVM coverage for dashboard rules that do not require Android services or Compose rendering. */
class DashboardModelsTest {
    /**
     * Runs the relative altitude is zero at baseline and rises as pressure falls operation.
     */
    @Test
    fun `relative altitude is zero at baseline and rises as pressure falls`() {
        assertEquals(0.0, relativeBarometricAltitudeMeters(101_325, 101_325), 0.0001)
        assertTrue(relativeBarometricAltitudeMeters(100_000, 101_325) > 0.0)
        assertTrue(relativeBarometricAltitudeMeters(102_000, 101_325) < 0.0)
    }

    /**
     * Runs the relative altitude rejects impossible pressures operation.
     */
    @Test
    fun `relative altitude rejects impossible pressures`() {
        assertThrows(IllegalArgumentException::class.java) {
            relativeBarometricAltitudeMeters(0, 101_325)
        }
        assertThrows(IllegalArgumentException::class.java) {
            relativeBarometricAltitudeMeters(101_325, 200_000)
        }
    }

    /**
     * Runs the relative altitude accepts the complete hardware decoder pressure range operation.
     */
    @Test
    fun `relative altitude accepts the complete hardware decoder pressure range`() {
        assertTrue(relativeBarometricAltitudeMeters(1_000, 120_000).isFinite())
        assertTrue(relativeBarometricAltitudeMeters(120_000, 1_000).isFinite())
    }

    /**
     * Runs the dashboard altitude getter is total at decoder boundaries and invalid baselines operation.
     */
    @Test
    fun `dashboard altitude getter is total at decoder boundaries and invalid baselines`() {
        val lowPressure = DashboardUiState(
            boardTelemetry = BoardTelemetrySnapshot(
                ms5611 = SensorState.Available(msSample(1_000)),
            ),
            baselinePressurePascal = 120_000,
        )
        assertTrue(requireNotNull(lowPressure.altitudeAboveTakeoffMeters).isFinite())

        val highPressure = lowPressure.copy(
            boardTelemetry = BoardTelemetrySnapshot(
                ms5611 = SensorState.Available(msSample(120_000)),
            ),
            baselinePressurePascal = 1_000,
        )
        assertTrue(requireNotNull(highPressure.altitudeAboveTakeoffMeters).isFinite())

        val invalidBaseline = lowPressure.copy(baselinePressurePascal = 0)
        assertNull(invalidBaseline.altitudeAboveTakeoffMeters)
    }

    /**
     * Runs the baseline requires a new pressure sample after the current session becomes ready operation.
     */
    @Test
    fun `baseline requires a new pressure sample after the current session becomes ready`() {
        val ready = readyConnection(connectedAtMillis = 100L)
        val samplePresentAtReady = BoardTelemetrySnapshot(
            ms5611 = SensorState.Available(
                msSample(pressurePascal = 101_325, sequence = 7, receivedAtMillis = 120L)
            ),
        )
        val gate = requireNotNull(takeoffBaselineCaptureGate(ready, samplePresentAtReady))

        assertNull(
            eligibleTakeoffBaselinePressure(
                establishedBaselinePascal = null,
                hasFlightPlan = true,
                connection = BoardConnectionState.Searching,
                gate = gate,
                telemetry = samplePresentAtReady,
            )
        )
        assertNull(
            eligibleTakeoffBaselinePressure(
                establishedBaselinePascal = null,
                hasFlightPlan = true,
                connection = ready,
                gate = gate,
                telemetry = samplePresentAtReady,
            )
        )

        val nextSample = BoardTelemetrySnapshot(
            ms5611 = SensorState.Available(
                msSample(pressurePascal = 101_300, sequence = 8, receivedAtMillis = 121L)
            ),
        )
        assertEquals(
            101_300,
            eligibleTakeoffBaselinePressure(
                establishedBaselinePascal = null,
                hasFlightPlan = true,
                connection = ready,
                gate = gate,
                telemetry = nextSample,
            ),
        )
    }

    /**
     * Runs the baseline rejects samples predating or belonging to a different ready session operation.
     */
    @Test
    fun `baseline rejects samples predating or belonging to a different ready session`() {
        val ready = readyConnection(connectedAtMillis = 100L)
        val gate = requireNotNull(
            takeoffBaselineCaptureGate(ready, BoardTelemetrySnapshot())
        )
        val preSessionSample = BoardTelemetrySnapshot(
            ms5611 = SensorState.Available(
                msSample(pressurePascal = 101_325, sequence = 2, receivedAtMillis = 99L)
            ),
        )
        assertNull(
            eligibleTakeoffBaselinePressure(
                establishedBaselinePascal = null,
                hasFlightPlan = true,
                connection = ready,
                gate = gate,
                telemetry = preSessionSample,
            )
        )
        assertNull(
            eligibleTakeoffBaselinePressure(
                establishedBaselinePascal = null,
                hasFlightPlan = true,
                connection = readyConnection(connectedAtMillis = 500L),
                gate = gate,
                telemetry = BoardTelemetrySnapshot(
                    ms5611 = SensorState.Available(
                        msSample(101_300, sequence = 3, receivedAtMillis = 501L)
                    ),
                ),
            )
        )
    }

    /**
     * Runs the established flight baseline survives an ordinary reconnect but no-plan state cannot capture operation.
     */
    @Test
    fun `established flight baseline survives an ordinary reconnect but no-plan state cannot capture`() {
        assertEquals(
            101_325,
            eligibleTakeoffBaselinePressure(
                establishedBaselinePascal = 101_325,
                hasFlightPlan = true,
                connection = BoardConnectionState.Disconnected(BoardDisconnectReason.USB_DETACHED),
                gate = null,
                telemetry = BoardTelemetrySnapshot(),
            ),
        )

        val ready = readyConnection(connectedAtMillis = 200L)
        val telemetry = BoardTelemetrySnapshot(
            ms5611 = SensorState.Available(
                msSample(101_200, sequence = 1, receivedAtMillis = 201L)
            ),
        )
        assertNull(
            eligibleTakeoffBaselinePressure(
                establishedBaselinePascal = null,
                hasFlightPlan = false,
                connection = ready,
                gate = takeoffBaselineCaptureGate(ready, BoardTelemetrySnapshot()),
                telemetry = telemetry,
            )
        )
    }

    /**
     * Runs the cardinal angles report signed deviation and absolute distance to N E S W operation.
     */
    @Test
    fun `cardinal angles report signed deviation and absolute distance to N E S W`() {
        val angles = cardinalAngles(compassReading(30f), NorthReference.MAGNETIC)

        assertEquals(CardinalDirections, angles.map { it.direction })
        assertAngle(angles, CompassDirection.NORTH, signed = 30f, absolute = 30f)
        assertAngle(angles, CompassDirection.EAST, signed = -60f, absolute = 60f)
        assertAngle(angles, CompassDirection.SOUTH, signed = -150f, absolute = 150f)
        assertAngle(angles, CompassDirection.WEST, signed = 120f, absolute = 120f)
    }

    /**
     * Runs the true cardinal angles are absent until a geomagnetic position provides true north operation.
     */
    @Test
    fun `true cardinal angles are absent until a geomagnetic position provides true north`() {
        assertTrue(cardinalAngles(compassReading(30f), NorthReference.TRUE).isEmpty())
    }

    /**
     * Runs the one external sensor failure does not erase another sensor value operation.
     */
    @Test
    fun `one external sensor failure does not erase another sensor value`() {
        val shtSample = SensorSample(
            value = Sht30Telemetry(22.5, 48.0),
            sequence = 7,
            deviceTimestampMicros = 100uL,
            receivedAtElapsedRealtimeMillis = 200,
            quality = quality(),
        )
        val state = DashboardUiState(
            boardTelemetry = BoardTelemetrySnapshot(
                sht30 = SensorState.Available(shtSample),
                ms5611 = SensorState.Failed<Ms5611Telemetry>(
                    lastSample = null,
                    error = SensorError(SensorErrorCode.SENSOR_OFFLINE, "offline", 200),
                ),
            ),
        )

        assertEquals(shtSample, (state.boardTelemetry.sht30 as SensorState.Available).sample)
        assertTrue(state.boardTelemetry.ms5611 is SensorState.Failed)
        assertNull(state.currentPressurePascal)
    }

    /**
     * Runs the quality operation.
     */
    private fun quality() = SensorSampleQuality(
        recoveredAfterError = false,
        rateLimited = false,
        rawValidityFlags = 0,
        rawQualityFlags = 0,
        rawHealthFlags = 0,
    )

    /**
     * Runs the msSample operation.
     */
    private fun msSample(
        pressurePascal: Int,
        sequence: Long = 1,
        receivedAtMillis: Long = 1L,
    ) = SensorSample(
        value = Ms5611Telemetry(
            pressurePascal = pressurePascal,
            temperatureCelsius = 20.0,
            altitudeAboveMeanSeaLevelMeters = 0.0,
            qnhHectopascal = 1013.25,
        ),
        sequence = sequence,
        deviceTimestampMicros = 1uL,
        receivedAtElapsedRealtimeMillis = receivedAtMillis,
        quality = quality(),
    )

    /**
     * Runs the compassReading operation.
     */
    private fun compassReading(azimuth: Float) = CompassReading(
        magneticAzimuthDegrees = azimuth,
        trueAzimuthDegrees = null,
        declinationDegrees = null,
        pitchDegrees = 5f,
        rollDegrees = -4f,
        accuracy = CompassAccuracy(
            level = CompassAccuracyLevel.HIGH,
            estimatedErrorDegrees = null,
            calibrationStatus = CalibrationStatus.NOT_REQUIRED,
        ),
        sensorSource = CompassSensorSource.ROTATION_VECTOR,
        timestampNanos = 1L,
    )

    /**
     * Runs the assertAngle operation.
     */
    private fun assertAngle(
        angles: List<CardinalAngle>,
        direction: CompassDirection,
        signed: Float,
        absolute: Float,
    ) {
        val angle = angles.single { it.direction == direction }
        assertEquals(signed, angle.signedDegrees, 0.0001f)
        assertEquals(absolute, angle.absoluteDegrees, 0.0001f)
    }

    /**
     * Runs the readyConnection operation.
     */
    private fun readyConnection(connectedAtMillis: Long) = BoardConnectionState.Ready(
        device = BoardUsbDevice(
            deviceId = 4,
            deviceName = "board",
            vendorId = 0x303A,
            productId = 0x4001,
        ),
        deviceInfo = BoardDeviceInfo(
            protocolVersion = 2,
            target = BoardTarget.ESP32_S3,
            supportedMotorChannels = 0,
            supportedServoChannels = 0,
            detectedFlashBytes = 16L * 1024 * 1024,
            detectedPsramBytes = 8L * 1024 * 1024,
            boardValidationIssueMask = 0,
            activeMotorChannels = 0,
            activeServoChannels = 0,
            actuatorAvailable = false,
            actuatorsEnabledByConfiguration = false,
        ),
        connectedAtElapsedRealtimeMillis = connectedAtMillis,
    )
}
