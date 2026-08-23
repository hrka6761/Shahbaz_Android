package ir.hrka.shahbaz.hardwareconnection.internal

import android.app.PendingIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbPermissionPolicyTest {
    @Test
    fun permissionPendingIntentAllowsUsbManagerFillInExtras() {
        val flags = usbPermissionPendingIntentFlags()

        assertTrue(flags and PendingIntent.FLAG_UPDATE_CURRENT != 0)
        assertTrue(flags and PendingIntent.FLAG_MUTABLE != 0)
        assertFalse(flags and PendingIntent.FLAG_IMMUTABLE != 0)
    }

    @Test
    fun missingCallbackExtrasStillUseAuthoritativeSelectedDeviceState() {
        assertEquals(
            UsbPermissionReconciliation.OPEN,
            usbPermissionReconciliation(
                selectedDeviceId = 7,
                requestedDeviceId = null,
                selectedDeviceIsAttached = true,
                selectedDeviceHasPermission = true,
            ),
        )
        assertEquals(
            UsbPermissionReconciliation.DENIED,
            usbPermissionReconciliation(
                selectedDeviceId = 7,
                requestedDeviceId = null,
                selectedDeviceIsAttached = true,
                selectedDeviceHasPermission = false,
            ),
        )
    }

    @Test
    fun staleOrDetachedPermissionCallbacksRescan() {
        assertEquals(
            UsbPermissionReconciliation.RESCAN,
            usbPermissionReconciliation(7, 8, true, true),
        )
        assertEquals(
            UsbPermissionReconciliation.RESCAN,
            usbPermissionReconciliation(7, 7, false, true),
        )
        assertEquals(
            UsbPermissionReconciliation.RESCAN,
            usbPermissionReconciliation(null, 7, false, false),
        )
    }
}
