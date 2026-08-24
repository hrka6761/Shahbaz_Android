/** Pure policy decisions used by Android USB permission dispatch and reconciliation. */
package ir.hrka.shahbaz.hardwareconnection.internal

import android.app.PendingIntent

/** UsbManager adds its permission result through a fill-in Intent, so this PendingIntent is mutable. */
internal fun usbPermissionPendingIntentFlags(): Int =
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE

internal enum class UsbPermissionReconciliation {
    RESCAN,
    OPEN,
    DENIED,
}

/**
 * Reconciles a callback with the app-owned request id and authoritative current UsbManager state.
 * Missing callback extras remain recoverable; a stale callback for a different device is rescanned.
 */
internal fun usbPermissionReconciliation(
    selectedDeviceId: Int?,
    requestedDeviceId: Int?,
    selectedDeviceIsAttached: Boolean,
    selectedDeviceHasPermission: Boolean,
): UsbPermissionReconciliation {
    if (selectedDeviceId == null) return UsbPermissionReconciliation.RESCAN
    if (requestedDeviceId != null && requestedDeviceId != selectedDeviceId) {
        return UsbPermissionReconciliation.RESCAN
    }
    if (!selectedDeviceIsAttached) return UsbPermissionReconciliation.RESCAN
    return if (selectedDeviceHasPermission) {
        UsbPermissionReconciliation.OPEN
    } else {
        UsbPermissionReconciliation.DENIED
    }
}

/** A re-enumerated sole device must never inherit an open transport/session for the old id. */
internal fun soleDeviceReplacesOpenedLink(openedDeviceId: Int?, soleDeviceId: Int): Boolean =
    openedDeviceId != null && openedDeviceId != soleDeviceId

internal enum class ReenumerationGraceAction {
    WAIT_FOR_REPLACEMENT,
    RESCAN_REPLACEMENT,
    PUBLISH_DETACHED,
}

/** Keeps a confirmed detach non-terminal while Android rebuilds the same USB attachment. */
internal fun reenumerationGraceAction(
    graceExpired: Boolean,
    matchingDeviceCount: Int,
): ReenumerationGraceAction {
    require(matchingDeviceCount >= 0)
    return when {
        matchingDeviceCount > 0 -> ReenumerationGraceAction.RESCAN_REPLACEMENT
        graceExpired -> ReenumerationGraceAction.PUBLISH_DETACHED
        else -> ReenumerationGraceAction.WAIT_FOR_REPLACEMENT
    }
}
