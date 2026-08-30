package ir.hrka.shahbaz.hardwareconnection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Documents the BoardModelsTest type and the role it plays in this module.
 */
class BoardModelsTest {
    /**
     * Runs the usbIdentityMatchesProductionTinyUsbCdcDescriptor operation.
     */
    @Test
    fun usbIdentityMatchesProductionTinyUsbCdcDescriptor() {
        assertEquals(0x303A, ShahbazBoardUsbIdentity.VENDOR_ID)
        assertEquals(0x4001, ShahbazBoardUsbIdentity.PRODUCT_ID)
        assertEquals(true, hasExactShahbazBoardUsbIdentity(0x303A, 0x4001))
        assertEquals(false, hasExactShahbazBoardUsbIdentity(0x303A, 0x4002))
    }

    /**
     * Runs the invalidTimingAndQnhConfigurationIsRejected operation.
     */
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
        assertThrows(IllegalArgumentException::class.java) {
            HardwareConnectionConfig(initialTimeSyncMaximumAttempts = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HardwareConnectionConfig(initialTimeSyncRetryIntervalMillis = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HardwareConnectionConfig(maximumMotorCommandBatch = 0)
        }
    }

    /**
     * Runs the actuatorPulseBoundsAreValidated operation.
     */
    @Test
    fun actuatorPulseBoundsAreValidated() {
        val bounds = BoardPulseBounds(900, 2_100)
        assertEquals(true, bounds.contains(1_500))
        assertEquals(false, bounds.contains(2_500))
        assertThrows(IllegalArgumentException::class.java) {
            BoardPulseBounds(2_100, 900)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BoardMotorPulse(channel = 256, pulseMicros = 1_500)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BoardServoPulse(channel = 0, pulseMicros = 0)
        }
    }
}
