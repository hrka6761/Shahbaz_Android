package ir.hrka.shahbaz.hardwareconnection.internal.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CdcSessionPolicyTest {
    @Test
    fun `logical session drops DTR then sets coding then asserts DTR`() {
        assertEquals(
            listOf(
                CdcOpenRequest.DROP_DTR,
                CdcOpenRequest.SET_LINE_CODING,
                CdcOpenRequest.ASSERT_DTR,
            ),
            cdcOpenRequestOrder(),
        )
        assertEquals(0x0001, CDC_CONTROL_LINE_ACTIVE)
    }

    @Test
    fun `logical session close drops DTR`() {
        assertEquals(0, CDC_CONTROL_LINE_IDLE)
    }

    @Test
    fun `control transfers require exact successful byte counts`() {
        assertTrue(cdcControlLineTransferSucceeded(0))
        assertFalse(cdcControlLineTransferSucceeded(-1))
        assertFalse(cdcControlLineTransferSucceeded(1))

        assertTrue(cdcLineCodingTransferSucceeded(CDC_LINE_CODING_SIZE))
        assertFalse(cdcLineCodingTransferSucceeded(-1))
        assertFalse(cdcLineCodingTransferSucceeded(CDC_LINE_CODING_SIZE - 1))
        assertFalse(cdcLineCodingTransferSucceeded(CDC_LINE_CODING_SIZE + 1))
    }
}
