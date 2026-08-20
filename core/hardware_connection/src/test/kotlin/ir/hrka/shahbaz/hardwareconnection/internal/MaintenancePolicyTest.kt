package ir.hrka.shahbaz.hardwareconnection.internal

import ir.hrka.shahbaz.hardwareconnection.BoardConnectionState
import ir.hrka.shahbaz.hardwareconnection.BoardDeviceInfo
import ir.hrka.shahbaz.hardwareconnection.BoardTarget
import ir.hrka.shahbaz.hardwareconnection.BoardUsbDevice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaintenancePolicyTest {
    @Test
    fun heartbeatAndStatusMaintenanceStartsOnlyAfterDeviceInfoValidation() {
        assertFalse(allowsPostValidationMaintenance(BoardConnectionState.Synchronizing(device)))
        assertFalse(allowsPostValidationMaintenance(BoardConnectionState.ValidatingDevice(device)))
        assertTrue(
            allowsPostValidationMaintenance(BoardConnectionState.AwaitingHeartbeat(device, info)),
        )
        assertTrue(allowsPostValidationMaintenance(BoardConnectionState.Ready(device, info, 10L)))
    }

    private val device = BoardUsbDevice(1, "usb", 0x303A, 0x4001)
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
