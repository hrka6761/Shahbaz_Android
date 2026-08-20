/** Fail-closed validation for runtime facts required by the sensor dashboard. */
package ir.hrka.shahbaz.hardwareconnection.internal

import ir.hrka.shahbaz.hardwareconnection.BoardDeviceInfo
import ir.hrka.shahbaz.hardwareconnection.BoardTarget
import ir.hrka.shahbaz.hardwareconnection.internal.protocol.WireContract

internal fun BoardDeviceInfo.validationError(): String? = when {
    protocolVersion != WireContract.VERSION ->
        "Board reports Protocol $protocolVersion, expected ${WireContract.VERSION}"
    target != BoardTarget.ESP32_S3 ->
        "Board target is not ESP32-S3"
    boardValidationIssueMask != 0L ->
        "Board validation issue mask is 0x${boardValidationIssueMask.toString(16)}"
    actuatorAvailable ||
        actuatorsEnabledByConfiguration ||
        activeMotorChannels != 0 ||
        activeServoChannels != 0 ->
        "This sensor dashboard requires all board actuator hardware to remain unavailable and disabled"
    else -> null
}
