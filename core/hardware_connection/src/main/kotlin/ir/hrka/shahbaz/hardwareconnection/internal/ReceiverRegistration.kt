/** Transactional registration helper used to avoid partially installed USB receivers. */
package ir.hrka.shahbaz.hardwareconnection.internal

internal inline fun registerReceiversAtomically(
    registerPermissionReceiver: () -> Unit,
    registerUsbLifecycleReceiver: () -> Unit,
    unregisterPermissionReceiver: () -> Unit,
    unregisterUsbLifecycleReceiver: () -> Unit,
) {
    try {
        registerPermissionReceiver()
        registerUsbLifecycleReceiver()
    } catch (error: RuntimeException) {
        // Either platform call may have installed a receiver before reporting failure.
        runCatching(unregisterUsbLifecycleReceiver)
        runCatching(unregisterPermissionReceiver)
        throw error
    }
}
