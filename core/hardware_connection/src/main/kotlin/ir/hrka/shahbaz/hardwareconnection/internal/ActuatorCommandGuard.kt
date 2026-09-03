package ir.hrka.shahbaz.hardwareconnection.internal

import ir.hrka.shahbaz.hardwareconnection.internal.protocol.ApplicationAction
import java.util.concurrent.atomic.AtomicInteger

/** One board command awaiting an explicit Protocol v2 acknowledgement. */
internal data class PendingActuatorAcknowledgement(
    val sequence: UInt,
    val expectedAction: ApplicationAction,
    val sentAtElapsedRealtimeMillis: Long,
)

/**
 * Serial-dispatcher-owned acknowledgement tracker with a hard pending-command bound.
 *
 * The owning connection invokes every method from its single USB dispatcher. Safety overrides may
 * exceed the ordinary limit so a saturated output stream can never prevent an E-stop or disarm.
 */
internal class ActuatorAcknowledgementTracker(
    private val maximumPending: Int,
) {
    private val pending = linkedMapOf<UInt, PendingActuatorAcknowledgement>()

    init {
        require(maximumPending > 0)
    }

    val pendingCount: Int
        get() = pending.size

    fun canTrack(commandCount: Int): Boolean {
        require(commandCount > 0)
        return commandCount <= maximumPending - pending.size
    }

    fun track(
        sequence: UInt,
        expectedAction: ApplicationAction,
        sentAtElapsedRealtimeMillis: Long,
        bypassLimit: Boolean = false,
    ): Boolean {
        require(sentAtElapsedRealtimeMillis >= 0L)
        if (!bypassLimit && !canTrack(1)) return false
        if (sequence in pending) return false
        pending[sequence] = PendingActuatorAcknowledgement(
            sequence = sequence,
            expectedAction = expectedAction,
            sentAtElapsedRealtimeMillis = sentAtElapsedRealtimeMillis,
        )
        return true
    }

    fun remove(sequence: UInt): PendingActuatorAcknowledgement? = pending.remove(sequence)

    fun containsExpectedAction(expectedAction: ApplicationAction): Boolean =
        pending.values.any { it.expectedAction == expectedAction }

    fun firstTimedOut(
        nowElapsedRealtimeMillis: Long,
        timeoutMillis: Long,
    ): PendingActuatorAcknowledgement? {
        require(nowElapsedRealtimeMillis >= 0L)
        require(timeoutMillis > 0L)
        return pending.values.firstOrNull { command ->
            nowElapsedRealtimeMillis >= command.sentAtElapsedRealtimeMillis &&
                nowElapsedRealtimeMillis - command.sentAtElapsedRealtimeMillis > timeoutMillis
        }
    }

    fun clear() = pending.clear()
}

/** Thread-safe admission gate preventing callers from building an unbounded coroutine backlog. */
internal class BoundedActuatorSubmissionGate(
    private val maximumQueued: Int,
) {
    private val queued = AtomicInteger(0)

    init {
        require(maximumQueued > 0)
    }

    val queuedCount: Int
        get() = queued.get()

    fun tryAcquire(): Boolean {
        while (true) {
            val current = queued.get()
            if (current >= maximumQueued) return false
            if (queued.compareAndSet(current, current + 1)) return true
        }
    }

    fun release() {
        val remaining = queued.decrementAndGet()
        check(remaining >= 0) { "Actuator submission gate released without an acquired slot" }
    }
}
