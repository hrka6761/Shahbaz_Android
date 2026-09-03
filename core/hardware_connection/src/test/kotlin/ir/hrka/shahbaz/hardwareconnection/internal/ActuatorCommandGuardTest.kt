package ir.hrka.shahbaz.hardwareconnection.internal

import ir.hrka.shahbaz.hardwareconnection.internal.protocol.ApplicationAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActuatorCommandGuardTest {
    @Test
    fun `ack tracker bounds ordinary traffic while allowing a safety override`() {
        val tracker = ActuatorAcknowledgementTracker(maximumPending = 2)

        assertTrue(tracker.track(1u, ApplicationAction.MOTOR_FRAME_COMMAND, 100L))
        assertTrue(tracker.canTrack(1))
        assertFalse(tracker.canTrack(2))
        assertTrue(tracker.track(2u, ApplicationAction.MOTOR_FRAME_COMMAND, 101L))
        assertTrue(tracker.containsExpectedAction(ApplicationAction.MOTOR_FRAME_COMMAND))
        assertFalse(tracker.containsExpectedAction(ApplicationAction.DISARM_APPLIED))
        assertFalse(tracker.track(3u, ApplicationAction.MOTOR_FRAME_COMMAND, 102L))
        assertTrue(
            tracker.track(
                sequence = 4u,
                expectedAction = ApplicationAction.EMERGENCY_STOP_APPLIED,
                sentAtElapsedRealtimeMillis = 103L,
                bypassLimit = true,
            ),
        )
        assertEquals(3, tracker.pendingCount)
    }

    @Test
    fun `ack removal and timeout retain exact command identity`() {
        val tracker = ActuatorAcknowledgementTracker(maximumPending = 4)
        tracker.track(10u, ApplicationAction.ARM_APPLIED, 1_000L)
        tracker.track(11u, ApplicationAction.MOTOR_FRAME_COMMAND, 1_050L)

        val acknowledged = tracker.remove(10u)
        assertEquals(ApplicationAction.ARM_APPLIED, acknowledged?.expectedAction)
        assertNull(tracker.remove(99u))
        assertNull(tracker.firstTimedOut(1_150L, timeoutMillis = 100L))

        val timedOut = tracker.firstTimedOut(1_151L, timeoutMillis = 100L)
        assertEquals(11u, timedOut?.sequence)
        assertEquals(ApplicationAction.MOTOR_FRAME_COMMAND, timedOut?.expectedAction)
    }

    @Test
    fun `submission gate never grows beyond its configured capacity`() {
        val gate = BoundedActuatorSubmissionGate(maximumQueued = 2)

        assertTrue(gate.tryAcquire())
        assertTrue(gate.tryAcquire())
        assertFalse(gate.tryAcquire())
        assertEquals(2, gate.queuedCount)

        gate.release()
        assertTrue(gate.tryAcquire())
        assertEquals(2, gate.queuedCount)
    }
}
