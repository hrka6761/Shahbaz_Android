package ir.hrka.shahbaz.hardwareconnection.internal

import ir.hrka.shahbaz.hardwareconnection.BoardDeviceInfo
import ir.hrka.shahbaz.hardwareconnection.BoardTarget
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Documents the DeviceInfoValidatorTest type and the role it plays in this module.
 */
class DeviceInfoValidatorTest {
    /**
     * Exposes the safe value.
     */
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

    /**
     * Runs the safeProductionSensorProfileIsAccepted operation.
     */
    @Test
    fun safeProductionSensorProfileIsAccepted() {
        assertNull(safe.validationError())
    }

    /**
     * Runs the knownAdvisoryEvidenceIsAcceptedAndRetained operation.
     */
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

    /**
     * Runs the everyKnownFatalValidationIssueIsRejected operation.
     */
    @Test
    fun everyKnownFatalValidationIssueIsRejected() {
        (0 until 64).forEach { bit ->
            val issue = 1L shl bit
            if (issue and FATAL_BOARD_VALIDATION_ISSUE_MASK != 0L) {
                assertNotNull(safe.copy(boardValidationIssueMask = issue).validationError())
            }
        }
    }

    /**
     * Runs the unknownFutureValidationIssuesAreRejected operation.
     */
    @Test
    fun unknownFutureValidationIssuesAreRejected() {
        assertNotNull(safe.copy(boardValidationIssueMask = 0x8_0000).validationError())
    }

    /** Rangefinder wiring failures disable that capability without hiding other board sensors. */
    @Test
    fun rangefinderCapabilityIssuesAreKnownAdvisories() {
        listOf(0x1_0000L, 0x2_0000L, 0x4_0000L, 0x7_0000L).forEach { issueMask ->
            assertNull(safe.copy(boardValidationIssueMask = issueMask).validationError())
        }
    }

    /**
     * Runs the actuatorAvailabilityIsRejectedEvenWhenChannelsAreInactive operation.
     */
    @Test
    fun actuatorAvailabilityIsRejectedEvenWhenChannelsAreInactive() {
        assertNotNull(safe.copy(actuatorAvailable = true).validationError())
    }

    /**
     * Runs the validationEvidenceAndActuatorConfigurationAreFailClosed operation.
     */
    @Test
    fun validationEvidenceAndActuatorConfigurationAreFailClosed() {
        assertNotNull(safe.copy(boardValidationIssueMask = 1).validationError())
        assertNotNull(safe.copy(actuatorsEnabledByConfiguration = true).validationError())
        assertNotNull(safe.copy(activeMotorChannels = 1).validationError())
    }

    /** Explicit opt-in accepts a coherent motor profile without weakening the default profile. */
    @Test
    fun coherentActuatorProfileRequiresExplicitOptIn() {
        val actuatorProfile = safe.copy(
            activeMotorChannels = 4,
            actuatorAvailable = true,
            actuatorsEnabledByConfiguration = true,
        )

        assertNotNull(actuatorProfile.validationError())
        assertNull(actuatorProfile.validationError(allowActuatorProfile = true))
    }

    /** Sensor-only firmware remains a valid connection even when the host permits actuators. */
    @Test
    fun actuatorOptInStillAcceptsSensorOnlyFirmware() {
        assertNull(safe.validationError(allowActuatorProfile = true))
    }

    /** Partial, unavailable, or out-of-capacity actuator facts remain fail closed. */
    @Test
    fun incoherentActuatorProfilesAreRejectedAfterOptIn() {
        val allow = true

        assertNotNull(
            safe.copy(actuatorAvailable = true)
                .validationError(allowActuatorProfile = allow),
        )
        assertNotNull(
            safe.copy(actuatorsEnabledByConfiguration = true)
                .validationError(allowActuatorProfile = allow),
        )
        assertNotNull(
            safe.copy(
                actuatorAvailable = true,
                actuatorsEnabledByConfiguration = true,
                activeMotorChannels = 5,
            ).validationError(allowActuatorProfile = allow),
        )
        assertNotNull(
            safe.copy(
                actuatorAvailable = true,
                actuatorsEnabledByConfiguration = true,
                activeServoChannels = 3,
            ).validationError(allowActuatorProfile = allow),
        )
        assertNotNull(
            safe.copy(supportedMotorChannels = 256)
                .validationError(allowActuatorProfile = allow),
        )
    }
}
