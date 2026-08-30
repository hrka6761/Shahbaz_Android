/** Pure policies for the staged, fail-closed board handshake. */
package ir.hrka.shahbaz.hardwareconnection.internal

/**
 * Documents the ValidatedHandshakeAction type and the role it plays in this module.
 */
internal enum class ValidatedHandshakeAction {
    WAIT_FOR_HEARTBEAT,
    START_TELEMETRY,
    WAIT_FOR_TELEMETRY_ACK,
    READY,
}

/** Heartbeat recovery must precede StartTelemetry, whose acknowledgement must precede Ready. */
internal fun validatedHandshakeAction(
    heartbeatAcknowledged: Boolean,
    telemetryStartRequested: Boolean,
    telemetryStartAcknowledged: Boolean,
): ValidatedHandshakeAction {
    require(heartbeatAcknowledged || !telemetryStartRequested) {
        "StartTelemetry cannot be requested before heartbeat recovery"
    }
    require(!telemetryStartAcknowledged || telemetryStartRequested) {
        "StartTelemetry cannot be acknowledged before it is requested"
    }
    return when {
        !heartbeatAcknowledged -> ValidatedHandshakeAction.WAIT_FOR_HEARTBEAT
        !telemetryStartRequested -> ValidatedHandshakeAction.START_TELEMETRY
        !telemetryStartAcknowledged -> ValidatedHandshakeAction.WAIT_FOR_TELEMETRY_ACK
        else -> ValidatedHandshakeAction.READY
    }
}

/**
 * Documents the InitialTimeSyncAction type and the role it plays in this module.
 */
internal enum class InitialTimeSyncAction {
    WAIT,
    RETRY,
    FAIL,
}

/**
 * Documents the AcceptedTimeSyncAction type and the role it plays in this module.
 */
internal enum class AcceptedTimeSyncAction {
    REQUEST_DEVICE_INFO,
    REFRESH_MAPPING_ONLY,
}

/** Only the first accepted TimeSync may advance the staged handshake. */
internal fun acceptedTimeSyncAction(hadEstablishedSessionToken: Boolean): AcceptedTimeSyncAction =
    if (hadEstablishedSessionToken) {
        AcceptedTimeSyncAction.REFRESH_MAPPING_ONLY
    } else {
        AcceptedTimeSyncAction.REQUEST_DEVICE_INFO
    }

/** Bounded retry policy for the initial TimeSync race at a new logical CDC session. */
internal fun initialTimeSyncAction(
    elapsedSinceLastAttemptMillis: Long,
    attemptsSent: Int,
    retryIntervalMillis: Long,
    maximumAttempts: Int,
): InitialTimeSyncAction {
    require(elapsedSinceLastAttemptMillis >= 0)
    require(maximumAttempts > 0)
    require(attemptsSent in 1..maximumAttempts)
    require(retryIntervalMillis > 0)
    if (elapsedSinceLastAttemptMillis < retryIntervalMillis) {
        return InitialTimeSyncAction.WAIT
    }
    return if (attemptsSent < maximumAttempts) {
        InitialTimeSyncAction.RETRY
    } else {
        InitialTimeSyncAction.FAIL
    }
}
