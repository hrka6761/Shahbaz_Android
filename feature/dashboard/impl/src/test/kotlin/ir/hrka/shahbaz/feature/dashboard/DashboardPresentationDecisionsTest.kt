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

/**
 * Documents the DashboardPresentationDecisionsTest type and the role it plays in this module.
 */
class DashboardPresentationDecisionsTest {
    /**
     * Runs the repeatedForegroundCallbacksPreservePermissionRequestReturn operation.
     */
    @Test
    fun repeatedForegroundCallbacksPreservePermissionRequestReturn() {
        val firstForeground = permissionRequestReturnedToHostAfterForeground(
            alreadyReturned = false,
            permissionStopDeferred = true,
            requestPending = true,
        )
        val repeatedForeground = permissionRequestReturnedToHostAfterForeground(
            alreadyReturned = firstForeground,
            permissionStopDeferred = false,
            requestPending = true,
        )

        assertTrue(firstForeground)
        assertTrue(repeatedForeground)
    }

    /**
     * Runs the deferredPermissionRequestReturningToPermissionRequiredIsTerminal operation.
     */
    @Test
    fun deferredPermissionRequestReturningToPermissionRequiredIsTerminal() {
        val requestPending = true

        assertFalse(
            usbPermissionRequestResolved(
                requestPending = requestPending,
                connection = BoardConnectionState.PermissionRequired(Device),
                permissionRequiredIsTerminal = false,
            ),
        )
        assertFalse(
            usbPermissionRequestResolved(
                requestPending = requestPending,
                connection = BoardConnectionState.RequestingPermission(Device),
                permissionRequiredIsTerminal = true,
            ),
        )
        assertTrue(
            usbPermissionRequestResolved(
                requestPending = requestPending,
                connection = BoardConnectionState.PermissionRequired(Device),
                permissionRequiredIsTerminal = true,
            ),
        )
        assertTrue(
            shouldSchedulePermissionResultBackgroundStop(
                hostForeground = false,
                permissionStopDeferred = true,
            ),
        )
    }

    /**
     * Runs the usbPermissionRequestKeepsReceiverAliveAcrossSystemPromptLifecycle operation.
     */
    @Test
    fun usbPermissionRequestKeepsReceiverAliveAcrossSystemPromptLifecycle() {
        assertTrue(
            shouldKeepBoardStartedForPermissionResult(
                BoardConnectionState.RequestingPermission(Device),
            ),
        )
        assertFalse(
            shouldKeepBoardStartedForPermissionResult(
                BoardConnectionState.PermissionRequired(Device),
            ),
        )
        assertTrue(
            shouldKeepBoardStartedForPermissionResult(
                BoardConnectionState.PermissionRequired(Device),
                requestPending = true,
            ),
        )
        assertFalse(shouldKeepBoardStartedForPermissionResult(BoardConnectionState.Searching))

        assertFalse(
            shouldStopBoardForHostBackground(
                connection = BoardConnectionState.Opening(Device),
                requestPending = false,
                permissionStopAlreadyDeferred = true,
            ),
        )
        assertTrue(
            shouldStopBoardForHostBackground(
                connection = BoardConnectionState.Ready(Device, DeviceInfo, 1L),
                requestPending = false,
                permissionStopAlreadyDeferred = false,
            ),
        )

        assertFalse(
            usbPermissionRequestResolved(
                requestPending = true,
                connection = BoardConnectionState.RequestingPermission(Device),
                permissionRequiredIsTerminal = true,
            ),
        )
        assertTrue(
            usbPermissionRequestResolved(
                requestPending = true,
                connection = BoardConnectionState.Opening(Device),
            ),
        )
        assertFalse(
            usbPermissionRequestResolved(
                requestPending = false,
                connection = BoardConnectionState.Opening(Device),
            ),
        )
        assertFalse(
            usbPermissionRequestResolved(
                requestPending = true,
                connection = BoardConnectionState.PermissionRequired(Device),
                permissionRequiredIsTerminal = false,
            ),
        )
        assertTrue(
            usbPermissionRequestResolved(
                requestPending = true,
                connection = BoardConnectionState.PermissionRequired(Device),
                permissionRequiredIsTerminal = true,
            ),
        )
        assertTrue(
            shouldSchedulePermissionResultBackgroundStop(
                hostForeground = false,
                permissionStopDeferred = true,
            ),
        )
        assertFalse(
            shouldSchedulePermissionResultBackgroundStop(
                hostForeground = true,
                permissionStopDeferred = true,
            ),
        )
    }

    /**
     * Runs the paneLayoutUsesLandscapeOnlyWhenWidthExceedsHeight operation.
     */
    @Test
    fun paneLayoutUsesLandscapeOnlyWhenWidthExceedsHeight() {
        assertEquals(DashboardPaneLayout.LANDSCAPE, dashboardPaneLayout(900f, 500f))
        assertEquals(DashboardPaneLayout.PORTRAIT, dashboardPaneLayout(500f, 900f))
        assertEquals(DashboardPaneLayout.PORTRAIT, dashboardPaneLayout(600f, 600f))
    }

    /**
     * Runs the paneLayoutRejectsInvalidDimensions operation.
     */
    @Test(expected = IllegalArgumentException::class)
    fun paneLayoutRejectsInvalidDimensions() {
        dashboardPaneLayout(-1f, 600f)
    }

    /**
     * Runs the instrumentColumnCountAdaptsWithoutChangingPaneRatio operation.
     */
    @Test
    fun instrumentColumnCountAdaptsWithoutChangingPaneRatio() {
        assertEquals(1, instrumentColumnCount(519f))
        assertEquals(2, instrumentColumnCount(520f))
        assertEquals(3, instrumentColumnCount(900f))
    }

    /**
     * Runs the attachedDashboardMapStyleWinsOverLateLoadFailureOrTimeout operation.
     */
    @Test
    fun attachedDashboardMapStyleWinsOverLateLoadFailureOrTimeout() {
        assertEquals(
            DashboardMapLoadState.READY,
            dashboardMapLoadState(
                isOnline = true,
                styleAttached = true,
                mapLoaded = false,
                mapLoadTimedOut = true,
                mapLoadFailed = true,
            ),
        )
        assertEquals(
            DashboardMapLoadState.ERROR,
            dashboardMapLoadState(
                isOnline = true,
                styleAttached = false,
                mapLoaded = false,
                mapLoadTimedOut = true,
                mapLoadFailed = false,
            ),
        )
    }

    /**
     * Runs the dashboardMapKeepsOfflineStateDistinctAfterStyleAttachment operation.
     */
    @Test
    fun dashboardMapKeepsOfflineStateDistinctAfterStyleAttachment() {
        assertEquals(
            DashboardMapLoadState.OFFLINE,
            dashboardMapLoadState(
                isOnline = false,
                styleAttached = true,
                mapLoaded = true,
                mapLoadTimedOut = false,
                mapLoadFailed = false,
            ),
        )
    }

    /**
     * Runs the dashboardRemainsBlockedUntilFullReadyState operation.
     */
    @Test
    fun dashboardRemainsBlockedUntilFullReadyState() {
        assertTrue(shouldBlockDashboard(BoardConnectionState.Searching))
        assertTrue(shouldBlockDashboard(BoardConnectionState.PermissionRequired(Device)))
        assertTrue(
            shouldBlockDashboard(
                BoardConnectionState.AwaitingHeartbeat(Device, DeviceInfo)
            )
        )
        assertTrue(
            shouldBlockDashboard(
                BoardConnectionState.StartingTelemetry(Device, DeviceInfo)
            )
        )
        assertFalse(
            shouldBlockDashboard(
                BoardConnectionState.Ready(Device, DeviceInfo, 100L)
            )
        )
    }

    /**
     * Runs the dashboardBlockReasonMatchesConnectionStateForReports operation.
     */
    @Test
    fun dashboardBlockReasonMatchesConnectionStateForReports() {
        assertEquals("Searching", dashboardBlockReason(BoardConnectionState.Searching))
        assertEquals("PermissionRequired", dashboardBlockReason(BoardConnectionState.PermissionRequired(Device)))
        assertEquals("ValidatingDevice", dashboardBlockReason(BoardConnectionState.ValidatingDevice(Device)))
        assertEquals("Ready", dashboardBlockReason(BoardConnectionState.Ready(Device, DeviceInfo, 100L)))
        assertEquals(
            "Disconnected",
            dashboardBlockReason(BoardConnectionState.Disconnected(BoardDisconnectReason.USB_DETACHED)),
        )
    }

    /**
     * Runs the readyBoardEvidenceWarningsAreDegradedButDoNotBlockTheDashboard operation.
     */
    @Test
    fun readyBoardEvidenceWarningsAreDegradedButDoNotBlockTheDashboard() {
        val clean = BoardConnectionState.Ready(Device, DeviceInfo, 10L)
        val advisory = BoardConnectionState.Ready(
            Device,
            DeviceInfo.copy(boardValidationIssueMask = 0x2FF0),
            10L,
        )

        assertEquals(InstrumentStatusKind.LIVE, boardReadyStatusKind(clean))
        assertEquals(InstrumentStatusKind.DEGRADED, boardReadyStatusKind(advisory))
        assertFalse(shouldBlockDashboard(advisory))
    }

    /**
     * Runs the gateOffersOnlyTheRecoveryActionAppropriateToState operation.
     */
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

    /**
     * Runs the everyExternalSensorStateHasAnExplicitPresentation operation.
     */
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

    /**
     * Runs the everyPhoneSensorStateHasAnExplicitPresentation operation.
     */
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

    /**
     * Runs the compassAccuracyNeverPresentsUnknownLowOrUnreliableAsHealthy operation.
     */
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

        /**
         * Runs the reading operation.
         */
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
