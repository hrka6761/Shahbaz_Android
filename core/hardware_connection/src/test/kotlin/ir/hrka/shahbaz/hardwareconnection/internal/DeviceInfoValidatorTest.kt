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
    fun knownAdvisoryEvidenceIsAcceptedAndRetained() {
        val defaultEvidence = safe.copy(boardValidationIssueMask = 0x2FF0)
        val everyKnownAdvisory = safe.copy(
            boardValidationIssueMask = ADVISORY_BOARD_VALIDATION_ISSUE_MASK,
        )

        assertNull(defaultEvidence.validationError())
        assertNull(everyKnownAdvisory.validationError())
        org.junit.Assert.assertEquals(0x2FF0L, defaultEvidence.boardValidationIssueMask)
    }

    @Test
    fun everyKnownFatalValidationIssueIsRejected() {
        (0 until 64).forEach { bit ->
            val issue = 1L shl bit
            if (issue and FATAL_BOARD_VALIDATION_ISSUE_MASK != 0L) {
                assertNotNull(safe.copy(boardValidationIssueMask = issue).validationError())
            }
        }
    }

    @Test
    fun unknownFutureValidationIssuesAreRejected() {
        assertNotNull(safe.copy(boardValidationIssueMask = 0x1_0000).validationError())
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
