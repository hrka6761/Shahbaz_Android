package ir.hrka.shahbaz.hardwareconnection.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReceiverRegistrationTest {
    @Test
    fun firstRegistrationFailureStillAttemptsCompleteRollback() {
        val events = mutableListOf<String>()

        assertThrows(SecurityException::class.java) {
            registerReceiversAtomically(
                registerPermissionReceiver = {
                    events += "register-permission"
                    throw SecurityException("denied")
                },
                registerUsbLifecycleReceiver = { events += "register-usb" },
                unregisterPermissionReceiver = { events += "unregister-permission" },
                unregisterUsbLifecycleReceiver = { events += "unregister-usb" },
            )
        }

        assertEquals(
            listOf("register-permission", "unregister-usb", "unregister-permission"),
            events,
        )
    }

    @Test
    fun secondRegistrationFailureRollsBackBothReceiversAndRethrows() {
        val events = mutableListOf<String>()

        assertThrows(SecurityException::class.java) {
            registerReceiversAtomically(
                registerPermissionReceiver = { events += "register-permission" },
                registerUsbLifecycleReceiver = {
                    events += "register-usb"
                    throw SecurityException("denied")
                },
                unregisterPermissionReceiver = { events += "unregister-permission" },
                unregisterUsbLifecycleReceiver = { events += "unregister-usb" },
            )
        }

        assertEquals(
            listOf(
                "register-permission",
                "register-usb",
                "unregister-usb",
                "unregister-permission",
            ),
            events,
        )
    }

    @Test
    fun successfulRegistrationDoesNotInvokeRollback() {
        val events = mutableListOf<String>()

        registerReceiversAtomically(
            registerPermissionReceiver = { events += "register-permission" },
            registerUsbLifecycleReceiver = { events += "register-usb" },
            unregisterPermissionReceiver = { events += "unregister-permission" },
            unregisterUsbLifecycleReceiver = { events += "unregister-usb" },
        )

        assertEquals(listOf("register-permission", "register-usb"), events)
    }
}
