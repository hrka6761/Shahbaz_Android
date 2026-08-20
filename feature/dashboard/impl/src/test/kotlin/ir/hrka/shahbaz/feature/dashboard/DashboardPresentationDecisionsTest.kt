package ir.hrka.shahbaz.feature.dashboard

import ir.hrka.compass.CalibrationStatus
import ir.hrka.compass.CompassAccuracy
import ir.hrka.compass.CompassAccuracyLevel
import ir.hrka.compass.CompassReading
import ir.hrka.compass.CompassSensorSource
import ir.hrka.shahbaz.hardwareconnection.BoardConnectionState
import ir.hrka.shahbaz.hardwareconnection.BoardDeviceInfo
import ir.hrka.shahbaz.hardwareconnection.BoardDisconnectReason
import ir.hrka.shahbaz.hardwareconnection.BoardLinkError
import ir.hrka.shahbaz.hardwareconnection.BoardLinkErrorCode
import ir.hrka.shahbaz.hardwareconnection.BoardTarget
import ir.hrka.shahbaz.hardwareconnection.BoardUsbDevice
import ir.hrka.shahbaz.hardwareconnection.SensorError
import ir.hrka.shahbaz.hardwareconnection.SensorErrorCode
import ir.hrka.shahbaz.hardwareconnection.SensorSample
import ir.hrka.shahbaz.hardwareconnection.SensorSampleQuality
import ir.hrka.shahbaz.hardwareconnection.SensorState
import ir.hrka.shahbaz.hardwareconnection.SensorUnavailableReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardPresentationDecisionsTest {
    @Test
    fun paneLayoutUsesLandscapeOnlyWhenWidthExceedsHeight() {
        assertEquals(DashboardPaneLayout.LANDSCAPE, dashboardPaneLayout(900f, 500f))
        assertEquals(DashboardPaneLayout.PORTRAIT, dashboardPaneLayout(500f, 900f))
        assertEquals(DashboardPaneLayout.PORTRAIT, dashboardPaneLayout(600f, 600f))
    }

    @Test(expected = IllegalArgumentException::class)
    fun paneLayoutRejectsInvalidDimensions() {
        dashboardPaneLayout(-1f, 600f)
    }

    @Test
    fun instrumentColumnCountAdaptsWithoutChangingPaneRatio() {
        assertEquals(1, instrumentColumnCount(519f))
        assertEquals(2, instrumentColumnCount(520f))
        assertEquals(3, instrumentColumnCount(900f))
    }

    @Test
    fun dashboardRemainsBlockedUntilFullReadyState() {
        assertTrue(shouldBlockDashboard(BoardConnectionState.Searching))
        assertTrue(shouldBlockDashboard(BoardConnectionState.PermissionRequired(Device)))
        assertTrue(
            shouldBlockDashboard(
                BoardConnectionState.AwaitingHeartbeat(Device, DeviceInfo)
            )
        )
        assertFalse(
            shouldBlockDashboard(
                BoardConnectionState.Ready(Device, DeviceInfo, 100L)
            )
        )
    }

    @Test
    fun gateOffersOnlyTheRecoveryActionAppropriateToState() {
        assertEquals(
            ConnectionGateAction.REQUEST_PERMISSION,
            connectionGateAction(BoardConnectionState.PermissionRequired(Device)),
        )
        assertEquals(
            ConnectionGateAction.RETRY,
            connectionGateAction(BoardConnectionState.Disconnected(BoardDisconnectReason.USB_DETACHED)),
        )
        assertEquals(
            ConnectionGateAction.RETRY,
            connectionGateAction(
                BoardConnectionState.Failed(
                    BoardLinkError(BoardLinkErrorCode.HEARTBEAT_TIMEOUT, "timeout", true)
                )
            ),
        )
        assertEquals(
            ConnectionGateAction.NONE,
            connectionGateAction(BoardConnectionState.Searching),
        )
        assertEquals(
            ConnectionGateAction.NONE,
            connectionGateAction(
                BoardConnectionState.Failed(
                    BoardLinkError(BoardLinkErrorCode.DEVICE_INFO_INVALID, "wrong board", false)
                )
            ),
        )
    }

    @Test
    fun everyExternalSensorStateHasAnExplicitPresentation() {
        assertEquals(InstrumentStatusKind.LIVE, sensorStatusKind(SensorState.Available(Sample)))
        assertEquals(
            InstrumentStatusKind.STALE,
            sensorStatusKind(SensorState.Stale(Sample, staleSinceElapsedRealtimeMillis = 2L)),
        )
        assertEquals(
            InstrumentStatusKind.LOADING,
            sensorStatusKind(SensorState.AwaitingFirstSample),
        )

        val unavailableExpectations = mapOf(
            SensorUnavailableReason.BOARD_DISCONNECTED to InstrumentStatusKind.NOT_CONNECTED,
            SensorUnavailableReason.TELEMETRY_NOT_STARTED to InstrumentStatusKind.LOADING,
            SensorUnavailableReason.SENSOR_REPORTED_OFFLINE to InstrumentStatusKind.NOT_PRESENT,
        )
        unavailableExpectations.forEach { (reason, expected) ->
            assertEquals(expected, sensorStatusKind(SensorState.Unavailable(reason)))
        }

        val failureExpectations = mapOf(
            SensorErrorCode.INVALID_PAYLOAD to InstrumentStatusKind.INVALID,
            SensorErrorCode.INVALID_VALIDITY to InstrumentStatusKind.INVALID,
            SensorErrorCode.OUT_OF_RANGE to InstrumentStatusKind.INVALID,
            SensorErrorCode.NO_RESPONSE to InstrumentStatusKind.NO_RESPONSE,
            SensorErrorCode.NOT_FRESH to InstrumentStatusKind.STALE,
            SensorErrorCode.SENSOR_OFFLINE to InstrumentStatusKind.NOT_PRESENT,
            SensorErrorCode.HEALTH_FAULT to InstrumentStatusKind.ERROR,
        )
        failureExpectations.forEach { (code, expected) ->
            assertEquals(
                expected,
                sensorStatusKind(
                    SensorState.Failed(
                        lastSample = Sample,
                        error = SensorError(code, code.name, 3L),
                    )
                ),
            )
        }
    }

    @Test
    fun everyPhoneSensorStateHasAnExplicitPresentation() {
        assertEquals(InstrumentStatusKind.INACTIVE, phoneStatusKind(PhoneReading.Inactive))
        assertEquals(
            InstrumentStatusKind.LOADING,
            phoneStatusKind(PhoneReading.AwaitingFirstSample),
        )
        assertEquals(InstrumentStatusKind.LIVE, phoneStatusKind(PhoneReading.Available(1)))
        assertEquals(InstrumentStatusKind.STALE, phoneStatusKind(PhoneReading.Stale(1)))
        assertEquals(
            InstrumentStatusKind.NO_RESPONSE,
            phoneStatusKind(PhoneReading.NoResponse(1, "timeout")),
        )
        assertEquals(
            InstrumentStatusKind.INVALID,
            phoneStatusKind(PhoneReading.Invalid(1, "invalid")),
        )
        assertEquals(
            InstrumentStatusKind.NOT_PRESENT,
            phoneStatusKind(PhoneReading.NotPresent("missing")),
        )
        assertEquals(
            InstrumentStatusKind.UNAVAILABLE,
            phoneStatusKind(PhoneReading.Unavailable("permission denied")),
        )
        assertEquals(InstrumentStatusKind.ERROR, phoneStatusKind(PhoneReading.Failed("failed")))
    }

    @Test
    fun compassAccuracyNeverPresentsUnknownLowOrUnreliableAsHealthy() {
        assertEquals(
            InstrumentStatusKind.DEGRADED,
            orientationStatusKind(PhoneReading.Available(reading(CompassAccuracyLevel.UNKNOWN))),
        )
        assertEquals(
            InstrumentStatusKind.INVALID,
            orientationStatusKind(PhoneReading.Available(reading(CompassAccuracyLevel.UNRELIABLE))),
        )
        assertEquals(
            InstrumentStatusKind.DEGRADED,
            orientationStatusKind(PhoneReading.Available(reading(CompassAccuracyLevel.LOW))),
        )
        assertEquals(
            InstrumentStatusKind.LIVE,
            orientationStatusKind(PhoneReading.Available(reading(CompassAccuracyLevel.MEDIUM))),
        )
        assertEquals(
            InstrumentStatusKind.LIVE,
            orientationStatusKind(PhoneReading.Available(reading(CompassAccuracyLevel.HIGH))),
        )
    }

    private companion object {
        val Device = BoardUsbDevice(
            deviceId = 1,
            deviceName = "board",
            vendorId = 0x303A,
            productId = 0x4001,
        )

        val DeviceInfo = BoardDeviceInfo(
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
        )

        val Sample = SensorSample(
            value = "sample",
            sequence = 1,
            deviceTimestampMicros = 1uL,
            receivedAtElapsedRealtimeMillis = 1L,
            quality = SensorSampleQuality(
                recoveredAfterError = false,
                rateLimited = false,
                rawValidityFlags = 0L,
                rawQualityFlags = 0L,
                rawHealthFlags = 0L,
            ),
        )

        fun reading(level: CompassAccuracyLevel) = CompassReading(
            magneticAzimuthDegrees = 30f,
            trueAzimuthDegrees = null,
            declinationDegrees = null,
            pitchDegrees = 2f,
            rollDegrees = -3f,
            accuracy = CompassAccuracy(
                level = level,
                estimatedErrorDegrees = null,
                calibrationStatus = when (level) {
                    CompassAccuracyLevel.UNKNOWN -> CalibrationStatus.UNKNOWN
                    CompassAccuracyLevel.UNRELIABLE -> CalibrationStatus.REQUIRED
                    CompassAccuracyLevel.LOW -> CalibrationStatus.RECOMMENDED
                    CompassAccuracyLevel.MEDIUM,
                    CompassAccuracyLevel.HIGH -> CalibrationStatus.NOT_REQUIRED
                },
            ),
            sensorSource = CompassSensorSource.ROTATION_VECTOR,
            timestampNanos = 1L,
        )
    }
}
