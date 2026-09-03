/** Fail-closed validation for runtime facts required by the selected connection profile. */
package ir.hrka.shahbaz.hardwareconnection.internal

import ir.hrka.shahbaz.hardwareconnection.BoardDeviceInfo
import ir.hrka.shahbaz.hardwareconnection.BoardTarget
import ir.hrka.shahbaz.hardwareconnection.internal.protocol.WireContract

/** Firmware-defined detected safety failures that make the board unusable. */
internal const val FATAL_BOARD_VALIDATION_ISSUE_MASK: Long = 0x400F

/**
 * Known unverified-evidence/advisory bits that remain visible in [BoardDeviceInfo].
 *
 * Bits 16..18 describe a disabled VL53L0X capability (missing XSHUT evidence, invalid XSHUT
 * pins, or an actuator-pin conflict). Firmware keeps every rangefinder in reset for those cases,
 * so the rest of the independently validated sensor board remains usable.
 */
internal const val ADVISORY_BOARD_VALIDATION_ISSUE_MASK: Long = 0x7BFF0

/**
 * Exposes the KNOWN_BOARD_VALIDATION_ISSUE_MASK value.
 */
private const val KNOWN_BOARD_VALIDATION_ISSUE_MASK: Long =
    FATAL_BOARD_VALIDATION_ISSUE_MASK or ADVISORY_BOARD_VALIDATION_ISSUE_MASK

/**
 * Runs the BoardDeviceInfo operation.
 */
internal fun BoardDeviceInfo.validationError(allowActuatorProfile: Boolean = false): String? {
    val fatalIssues = boardValidationIssueMask and FATAL_BOARD_VALIDATION_ISSUE_MASK
    val unknownIssues = boardValidationIssueMask and KNOWN_BOARD_VALIDATION_ISSUE_MASK.inv()
    val actuatorFactsPresent = actuatorAvailable ||
        actuatorsEnabledByConfiguration ||
        activeMotorChannels != 0 ||
        activeServoChannels != 0
    return when {
        protocolVersion != WireContract.VERSION ->
            "Board reports Protocol $protocolVersion, expected ${WireContract.VERSION}"
        target != BoardTarget.ESP32_S3 ->
            "Board target is not ESP32-S3"
        fatalIssues != 0L ->
            "Board reports fatal validation issues 0x${fatalIssues.toString(16)}"
        unknownIssues != 0L ->
            "Board reports unknown validation issues 0x${unknownIssues.toString(16)}"
        supportedMotorChannels !in 0..MAX_PROTOCOL_ACTUATOR_CHANNELS ||
            supportedServoChannels !in 0..MAX_PROTOCOL_ACTUATOR_CHANNELS ->
            "Board reports actuator-channel capacity outside the Protocol v2 range"
        activeMotorChannels !in 0..supportedMotorChannels ||
            activeServoChannels !in 0..supportedServoChannels ->
            "Board reports active actuator channels outside its supported capacity"
        !allowActuatorProfile && actuatorFactsPresent ->
            "This sensor dashboard requires all board actuator hardware to remain unavailable and disabled"
        allowActuatorProfile && actuatorFactsPresent && !actuatorsEnabledByConfiguration ->
            "Active actuator hardware must be explicitly enabled by board configuration"
        allowActuatorProfile && actuatorFactsPresent && !actuatorAvailable ->
            "Configured actuator hardware is not available"
        allowActuatorProfile && actuatorFactsPresent && activeMotorChannels == 0 ->
            "An actuator flight profile must expose at least one active motor channel"
        else -> null
    }
}

/** Protocol v2 reports each supported-channel count in one unsigned byte. */
private const val MAX_PROTOCOL_ACTUATOR_CHANNELS = 255
