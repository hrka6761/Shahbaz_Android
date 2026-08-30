/** Pure lifecycle policy for post-DeviceInfo USB maintenance traffic. */
package ir.hrka.shahbaz.hardwareconnection.internal

import ir.hrka.shahbaz.hardwareconnection.BoardConnectionState

/**
 * Runs the allowsPostValidationMaintenance operation.
 */
internal fun allowsPostValidationMaintenance(state: BoardConnectionState): Boolean =
    state is BoardConnectionState.AwaitingHeartbeat ||
        state is BoardConnectionState.StartingTelemetry ||
        state is BoardConnectionState.Ready
