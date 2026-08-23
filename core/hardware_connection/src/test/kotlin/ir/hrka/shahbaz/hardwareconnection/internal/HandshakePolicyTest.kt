package ir.hrka.shahbaz.hardwareconnection.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HandshakePolicyTest {
    @Test
    fun heartbeatRecoveryStrictlyPrecedesTelemetryStartAndReady() {
        assertEquals(
            ValidatedHandshakeAction.WAIT_FOR_HEARTBEAT,
            validatedHandshakeAction(false, false, false),
        )
        assertEquals(
            ValidatedHandshakeAction.START_TELEMETRY,
            validatedHandshakeAction(true, false, false),
        )
        assertEquals(
            ValidatedHandshakeAction.WAIT_FOR_TELEMETRY_ACK,
            validatedHandshakeAction(true, true, false),
        )
        assertEquals(
            ValidatedHandshakeAction.READY,
            validatedHandshakeAction(true, true, true),
        )
    }

    @Test
    fun impossibleTelemetryAcknowledgementIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            validatedHandshakeAction(true, false, true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validatedHandshakeAction(false, true, false)
        }
    }

    @Test
    fun initialTimeSyncRetriesAreTimedAndBounded() {
        assertEquals(
            InitialTimeSyncAction.WAIT,
            initialTimeSyncAction(499, 1, retryIntervalMillis = 500, maximumAttempts = 4),
        )
        assertEquals(
            InitialTimeSyncAction.RETRY,
            initialTimeSyncAction(500, 1, retryIntervalMillis = 500, maximumAttempts = 4),
        )
        assertEquals(
            InitialTimeSyncAction.RETRY,
            initialTimeSyncAction(500, 3, retryIntervalMillis = 500, maximumAttempts = 4),
        )
        assertEquals(
            InitialTimeSyncAction.FAIL,
            initialTimeSyncAction(500, 4, retryIntervalMillis = 500, maximumAttempts = 4),
        )
    }

    @Test
    fun acceptedRetryResponseCannotRestartDeviceInfoValidation() {
        assertEquals(
            AcceptedTimeSyncAction.REQUEST_DEVICE_INFO,
            acceptedTimeSyncAction(hadEstablishedSessionToken = false),
        )
        assertEquals(
            AcceptedTimeSyncAction.REFRESH_MAPPING_ONLY,
            acceptedTimeSyncAction(hadEstablishedSessionToken = true),
        )
    }
}
