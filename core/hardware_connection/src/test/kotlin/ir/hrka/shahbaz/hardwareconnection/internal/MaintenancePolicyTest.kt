package ir.hrka.shahbaz.hardwareconnection.internal

import ir.hrka.shahbaz.hardwareconnection.BoardConnectionState
import ir.hrka.shahbaz.hardwareconnection.BoardDeviceInfo
import ir.hrka.shahbaz.hardwareconnection.BoardTarget
import ir.hrka.shahbaz.hardwareconnection.BoardUsbDevice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Documents the MaintenancePolicyTest type and the role it plays in this module.
 */
class MaintenancePolicyTest {
    /**
     * Runs the heartbeatAndStatusMaintenanceStartsOnlyAfterDeviceInfoValidation operation.
     */
    @Test
    fun heartbeatAndStatusMaintenanceStartsOnlyAfterDeviceInfoValidation() {
        assertFalse(allowsPostValidationMaintenance(BoardConnectionState.Synchronizing(device)))
        assertFalse(allowsPostValidationMaintenance(BoardConnectionState.ValidatingDevice(device)))
        assertTrue(
            allowsPostValidationMaintenance(BoardConnectionState.AwaitingHeartbeat(device, info)),
        )
        assertTrue(
            allowsPostValidationMaintenance(BoardConnectionState.StartingTelemetry(device, info)),
        )
        assertTrue(allowsPostValidationMaintenance(BoardConnectionState.Ready(device, info, 10L)))
    }

    /**
     * Exposes the device value.
     */
    private val device = BoardUsbDevice(1, "usb", 0x303A, 0x4001)
    /**
     * Exposes the info value.
     */
    private val info = BoardDeviceInfo(
        protocolVersion = 2,
        target = BoardTarget.ESP32_S3,
        supportedMotorChannels = 4,
        supportedServoChannels = 2,
        detectedFlashBytes = 8 * 1024 * 1024L,
        detectedPsramBytes = 8 * 1024 * 1024L,
        boardValidationIssueMask = 0,
        activeMotorChannels = 0,
        activeServoChannels = 0,
        actuatorAvailable = false,
        actuatorsEnabledByConfiguration = false,
    )
}
