package ir.hrka.shahbaz.hardwareconnection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BoardModelsTest {
    @Test
    fun usbIdentityMatchesProductionTinyUsbCdcDescriptor() {
        assertEquals(0x303A, ShahbazBoardUsbIdentity.VENDOR_ID)
        assertEquals(0x4001, ShahbazBoardUsbIdentity.PRODUCT_ID)
        assertEquals(true, hasExactShahbazBoardUsbIdentity(0x303A, 0x4001))
        assertEquals(false, hasExactShahbazBoardUsbIdentity(0x303A, 0x4002))
    }

    @Test
    fun invalidTimingAndQnhConfigurationIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            HardwareConnectionConfig(initialQnhHectopascal = 700.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HardwareConnectionConfig(heartbeatIntervalMillis = 1_100, heartbeatTimeoutMillis = 1_000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HardwareConnectionConfig(firstSensorSampleTimeoutMillis = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HardwareConnectionConfig(sensorTimestampFutureToleranceMillis = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HardwareConnectionConfig(maximumUnknownSensors = 0)
        }
    }
}
