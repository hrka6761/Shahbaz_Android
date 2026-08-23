/** Pure CDC control-line policy for one logical Shahbaz board session. */
package ir.hrka.shahbaz.hardwareconnection.internal.usb

internal const val CDC_CONTROL_LINE_IDLE: Int = 0x0000
internal const val CDC_CONTROL_LINE_ACTIVE: Int = 0x0003

/** Drop DTR/RTS before asserting them so firmware observes a fresh logical session boundary. */
internal fun cdcOpenControlLineStates(): IntArray = intArrayOf(
    CDC_CONTROL_LINE_IDLE,
    CDC_CONTROL_LINE_ACTIVE,
)
