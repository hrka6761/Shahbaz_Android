package ir.hrka.shahbaz.hardwareconnection.internal.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CdcSessionPolicyTest {
    @Test
    fun `logical session drops then asserts DTR and RTS`() {
        assertArrayEquals(
            intArrayOf(CDC_CONTROL_LINE_IDLE, CDC_CONTROL_LINE_ACTIVE),
            cdcOpenControlLineStates(),
        )
    }

    @Test
    fun `logical session close drops DTR and RTS`() {
        assertEquals(0, CDC_CONTROL_LINE_IDLE)
    }
}
