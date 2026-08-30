/** Pure CDC control-line policy for one logical Shahbaz board session. */
package ir.hrka.shahbaz.hardwareconnection.internal.usb

/**
 * Exposes the CDC_CONTROL_LINE_IDLE value.
 */
internal const val CDC_CONTROL_LINE_IDLE: Int = 0x0000
/**
 * Exposes the CDC_CONTROL_LINE_ACTIVE value.
 */
internal const val CDC_CONTROL_LINE_ACTIVE: Int = 0x0001
/**
 * Exposes the CDC_LINE_CODING_SIZE value.
 */
internal const val CDC_LINE_CODING_SIZE: Int = 7

/**
 * Documents the CdcOpenRequest type and the role it plays in this module.
 */
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

/**
 * Runs the cdcControlLineTransferSucceeded operation.
 */
internal fun cdcControlLineTransferSucceeded(result: Int): Boolean = result == 0

/**
 * Runs the cdcLineCodingTransferSucceeded operation.
 */
internal fun cdcLineCodingTransferSucceeded(result: Int): Boolean =
    result == CDC_LINE_CODING_SIZE
