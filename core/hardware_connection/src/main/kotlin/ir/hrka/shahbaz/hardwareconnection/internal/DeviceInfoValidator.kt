/** Fail-closed validation for runtime facts required by the sensor dashboard. */
package ir.hrka.shahbaz.hardwareconnection.internal

import ir.hrka.shahbaz.hardwareconnection.BoardDeviceInfo
import ir.hrka.shahbaz.hardwareconnection.BoardTarget
import ir.hrka.shahbaz.hardwareconnection.internal.protocol.WireContract

/** Firmware-defined detected safety failures that make the board unusable. */
internal const val FATAL_BOARD_VALIDATION_ISSUE_MASK: Long = 0x400F

/** Known unverified-evidence/advisory bits that remain visible in [BoardDeviceInfo]. */
internal const val ADVISORY_BOARD_VALIDATION_ISSUE_MASK: Long = 0xBFF0

/**
 * Exposes the KNOWN_BOARD_VALIDATION_ISSUE_MASK value.
 */
private const val KNOWN_BOARD_VALIDATION_ISSUE_MASK: Long =
    FATAL_BOARD_VALIDATION_ISSUE_MASK or ADVISORY_BOARD_VALIDATION_ISSUE_MASK

/**
 * Runs the BoardDeviceInfo operation.
 */
internal fun BoardDeviceInfo.validationError(): String? {
    val fatalIssues = boardValidationIssueMask and FATAL_BOARD_VALIDATION_ISSUE_MASK
    val unknownIssues = boardValidationIssueMask and KNOWN_BOARD_VALIDATION_ISSUE_MASK.inv()
    return when {
        protocolVersion != WireContract.VERSION ->
            "Board reports Protocol $protocolVersion, expected ${WireContract.VERSION}"
        target != BoardTarget.ESP32_S3 ->
            "Board target is not ESP32-S3"
        fatalIssues != 0L ->
            "Board reports fatal validation issues 0x${fatalIssues.toString(16)}"
        unknownIssues != 0L ->
            "Board reports unknown validation issues 0x${unknownIssues.toString(16)}"
        actuatorAvailable ||
            actuatorsEnabledByConfiguration ||
            activeMotorChannels != 0 ||
            activeServoChannels != 0 ->
            "This sensor dashboard requires all board actuator hardware to remain unavailable and disabled"
        else -> null
    }
}
