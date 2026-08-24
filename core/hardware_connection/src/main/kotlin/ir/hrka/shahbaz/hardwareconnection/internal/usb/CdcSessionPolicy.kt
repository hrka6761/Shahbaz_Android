/** Pure CDC control-line policy for one logical Shahbaz board session. */
package ir.hrka.shahbaz.hardwareconnection.internal.usb

internal const val CDC_CONTROL_LINE_IDLE: Int = 0x0000
internal const val CDC_CONTROL_LINE_ACTIVE: Int = 0x0001
internal const val CDC_LINE_CODING_SIZE: Int = 7

internal enum class CdcOpenRequest {
    DROP_DTR,
    SET_LINE_CODING,
    ASSERT_DTR,
}

/** The board reference requires a logical boundary before applying coding and opening DTR. */
internal fun cdcOpenRequestOrder(): List<CdcOpenRequest> = listOf(
    CdcOpenRequest.DROP_DTR,
    CdcOpenRequest.SET_LINE_CODING,
    CdcOpenRequest.ASSERT_DTR,
)

internal fun cdcControlLineTransferSucceeded(result: Int): Boolean = result == 0

internal fun cdcLineCodingTransferSucceeded(result: Int): Boolean =
    result == CDC_LINE_CODING_SIZE
