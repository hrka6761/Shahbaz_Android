package ir.hrka.shahbaz.hardwareconnection.internal

import ir.hrka.shahbaz.hardwareconnection.BoardDeviceInfo
import ir.hrka.shahbaz.hardwareconnection.BoardTarget
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceInfoValidatorTest {
    private val safe = BoardDeviceInfo(
        protocolVersion = 2,
        target = BoardTarget.ESP32_S3,
        supportedMotorChannels = 4,
        supportedServoChannels = 2,
        detectedFlashBytes = 16L * 1024 * 1024,
        detectedPsramBytes = 8L * 1024 * 1024,
        boardValidationIssueMask = 0,
        activeMotorChannels = 0,
        activeServoChannels = 0,
        actuatorAvailable = false,
        actuatorsEnabledByConfiguration = false,
    )

    @Test
    fun safeProductionSensorProfileIsAccepted() {
        assertNull(safe.validationError())
    }

    @Test
    fun actuatorAvailabilityIsRejectedEvenWhenChannelsAreInactive() {
        assertNotNull(safe.copy(actuatorAvailable = true).validationError())
    }

    @Test
    fun validationEvidenceAndActuatorConfigurationAreFailClosed() {
        assertNotNull(safe.copy(boardValidationIssueMask = 1).validationError())
        assertNotNull(safe.copy(actuatorsEnabledByConfiguration = true).validationError())
        assertNotNull(safe.copy(activeMotorChannels = 1).validationError())
    }
}
