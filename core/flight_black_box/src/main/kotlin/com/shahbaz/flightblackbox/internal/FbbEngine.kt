package com.shahbaz.flightblackbox.internal

import com.shahbaz.flightblackbox.FbbAppInfo
import com.shahbaz.flightblackbox.FbbConfig
import com.shahbaz.flightblackbox.FbbDurabilityMode
import com.shahbaz.flightblackbox.FbbEvent
import com.shahbaz.flightblackbox.FbbEventRef
import com.shahbaz.flightblackbox.FbbEventType
import com.shahbaz.flightblackbox.FbbHealth
import com.shahbaz.flightblackbox.FbbPersistence
import com.shahbaz.flightblackbox.FbbReportStatus
import com.shahbaz.flightblackbox.withRequiredDiagnosticsMode
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Documents the FbbEngine type and the role it plays in this module.
 */
internal class FbbEngine private constructor(
    /**
     * Exposes the storage value.
     */
    private val storage: FbbStorage,
    /**
     * Exposes the session value.
     */
    private val session: FbbSession,
    /**
     * Exposes the config value.
     */
    private val config: FbbConfig,
    /**
     * Exposes the clock value.
     */
    private val clock: FbbClock,
    /**
     * Exposes the formatter value.
     */
    private val formatter: FbbFormatter,
    /**
     * Exposes the fileWriter value.
     */
    private val fileWriter: FbbReportFileWriter,
) : Closeable {
    /**
     * Exposes the queue value.
     */
    private val queue = LinkedBlockingQueue<FbbPendingRecord>(config.queueCapacity)
    /**
     * Exposes the recordLock value.
     */
    private val recordLock = Any()
    /**
     * Exposes the queueAccessLock value.
     */
    private val queueAccessLock = Any()
    /**
     * Exposes the writerProcessingLock value.
     */
    private val writerProcessingLock = Any()
    /**
     * Exposes the running value.
     */
    private val running = AtomicBoolean(true)
    /**
     * Exposes the footerWritten value.
     */
    private val footerWritten = AtomicBoolean(false)
    /**
     * Exposes the latestProducedSequence value.
     */
    private val latestProducedSequence = AtomicLong(0L)
    /**
     * Exposes the latestWrittenSequence value.
     */
    private val latestWrittenSequence = AtomicLong(0L)
    /**
     * Exposes the latestDurableSequence value.
     */
    private val latestDurableSequence = AtomicLong(0L)
    /**
     * Exposes the backpressureBlocks value.
     */
    private val backpressureBlocks = AtomicLong(0L)
    /**
     * Exposes the writerFailures value.
     */
    private val writerFailures = AtomicLong(0L)
    @Volatile private var lastFailure: String? = null
    @Volatile private var metadata = FbbSessionMetadata(
        sessionId = session.id,
        reportFileName = session.reportFileName,
        startedAtEpochMillis = session.startedAtEpochMillis,
        status = FbbReportStatus.ACTIVE,
        latestProducedSequence = 0L,
        latestWrittenSequence = 0L,
        latestDurableSequence = 0L,
    )

    /**
     * Exposes the activeSessionId value.
     */
    val activeSessionId: String = session.id

    /**
     * Stores the mutable processStartEvent value.
     */
    @Volatile
    var processStartEvent: FbbEventRef? = null
        private set

    /**
     * Exposes the writerThread value.
     */
    private val writerThread = Thread(::writerLoop, "ShahbazFlightBlackBoxWriter").apply {
        isDaemon = true
        start()
    }

    /**
     * Runs the elapsedRealtimeNanos operation.
     */
    fun elapsedRealtimeNanos(): Long = clock.elapsedRealtimeNanos()

    /**
     * Runs the durationMillisSince operation.
     */
    fun durationMillisSince(startElapsedNanos: Long): Long =
        ((clock.elapsedRealtimeNanos() - startElapsedNanos).coerceAtLeast(0L) / 1_000_000L)

    /**
     * Runs the record operation.
     */
    fun record(event: FbbEvent): FbbEventRef? {
        if (!running.get()) return null
        val pending = synchronized(recordLock) {
            val sequence = latestProducedSequence.incrementAndGet()
            val eventId = FbbFormatter.formatEventId(sequence)
            updateMetadata { copy(latestProducedSequence = sequence) }
            FbbPendingRecord(
                sequence = sequence,
                eventId = eventId,
                lines = formatter.event(sequence, event, clock.elapsedRealtimeNanos()),
                persistence = event.persistence,
                ack = if (shouldAwaitAck(event.persistence)) CountDownLatch(1) else null,
            ).also(::enqueueOrWriteSynchronously)
        }
        runCatching {
            pending.ack?.await(STRICT_ACK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        }.onFailure {
            if (it is InterruptedException) Thread.currentThread().interrupt()
        }
        return FbbEventRef(pending.eventId)
    }

    /**
     * Runs the recordThrowable operation.
     */
    fun recordThrowable(
        type: FbbEventType,
        description: String,
        error: Throwable,
        cause: FbbEventRef?,
        metadata: Map<String, Any?>,
        persistence: FbbPersistence,
    ): FbbEventRef? = record(
        FbbEvent(
            type = type,
            description = description,
            cause = cause,
            metadata = metadata + mapOf(
                "exceptionClass" to error.javaClass.name,
                "exceptionMessage" to error.message,
            ),
            detail = error.stackTraceText(),
            persistence = persistence,
        )
    )

    /**
     * Runs the recordCrashSynchronously operation.
     */
    fun recordCrashSynchronously(thread: Thread, error: Throwable) {
        if (!running.get()) return
        synchronized(recordLock) {
            synchronized(queueAccessLock) {
                synchronized(writerProcessingLock) {
                    drainQueueSynchronouslyLocked()
                    val sequence = latestProducedSequence.incrementAndGet()
                    val event = FbbEvent(
                        type = FbbEventType.CRASH,
                        description = "Uncaught exception on ${thread.name}: ${error.javaClass.simpleName}",
                        metadata = mapOf(
                            "thread" to thread.name,
                            "exceptionClass" to error.javaClass.name,
                            "exceptionMessage" to error.message,
                        ),
                        detail = error.stackTraceText(),
                        persistence = FbbPersistence.CRITICAL,
                    )
                    val record = FbbPendingRecord(
                        sequence = sequence,
                        eventId = FbbFormatter.formatEventId(sequence),
                        lines = formatter.event(sequence, event, clock.elapsedRealtimeNanos()),
                        persistence = FbbPersistence.CRITICAL,
                        ack = null,
                    )
                    writeRecord(record, flush = true, force = true)
                    updateMetadata {
                        copy(
                            status = FbbReportStatus.CRASHED,
                            latestProducedSequence = sequence,
                            latestWrittenSequence = sequence,
                            latestDurableSequence = sequence,
                        )
                    }
                    writeFooterLocked(FbbReportStatus.CRASHED)
                    storage.writeSessionMetadata(metadata)
                }
            }
        }
    }

    /**
     * Runs the flushAndForce operation.
     */
    fun flushAndForce() {
        synchronized(queueAccessLock) {
            synchronized(writerProcessingLock) {
                drainQueueSynchronouslyLocked()
                runCatching {
                    fileWriter.flush(force = true)
                    latestDurableSequence.set(latestWrittenSequence.get())
                    lastForceElapsedNanos = clock.elapsedRealtimeNanos()
                    updateMetadata {
                        copy(
                            latestWrittenSequence = this@FbbEngine.latestWrittenSequence.get(),
                            latestDurableSequence = this@FbbEngine.latestDurableSequence.get(),
                        )
                    }
                    storage.updateActiveMetadata(metadata)
                }.onFailure(::rememberWriterFailure)
            }
        }
    }

    /**
     * Runs the completeSession operation.
     */
    fun completeSession() {
        synchronized(recordLock) {
            flushAndForce()
            updateMetadata { copy(status = FbbReportStatus.COMPLETED) }
            writeFooterLocked(FbbReportStatus.COMPLETED)
            storage.writeSessionMetadata(metadata)
        }
    }

    /**
     * Runs the health operation.
     */
    fun health(): FbbHealth = FbbHealth(
        initialized = true,
        activeSessionId = session.id,
        latestProducedSequence = latestProducedSequence.get(),
        latestWrittenSequence = latestWrittenSequence.get(),
        latestDurableSequence = latestDurableSequence.get(),
        queuedEvents = queue.size,
        backpressureBlocks = backpressureBlocks.get(),
        writerFailures = writerFailures.get(),
        lastFailure = lastFailure,
    )

    /**
     * Runs the close operation.
     */
    override fun close() {
        running.set(false)
        flushAndForce()
        runCatching {
            writerThread.join(WRITER_JOIN_TIMEOUT_MILLIS)
        }.onFailure {
            if (it is InterruptedException) Thread.currentThread().interrupt()
        }
        runCatching { fileWriter.close() }.onFailure(::rememberWriterFailure)
    }

    /**
     * Runs the enqueueOrWriteSynchronously operation.
     */
    private fun enqueueOrWriteSynchronously(record: FbbPendingRecord) {
        val queued = runCatching {
            queue.offer(
                record,
                config.queueBackpressureWarningThresholdMillis,
                TimeUnit.MILLISECONDS,
            )
        }.getOrElse {
            if (it is InterruptedException) Thread.currentThread().interrupt()
            false
        }
        if (queued) return
        backpressureBlocks.incrementAndGet()
        synchronized(queueAccessLock) {
            synchronized(writerProcessingLock) {
                drainQueueSynchronouslyLocked()
                writeRecord(record, flush = true, force = shouldForce(record))
            }
        }
    }

    /**
     * Runs the writerLoop operation.
     */
    private fun writerLoop() {
        while (running.get() || queue.isNotEmpty()) {
            synchronized(queueAccessLock) {
                val record =
                    runCatching {
                        queue.poll(config.normalFlushIntervalMillis, TimeUnit.MILLISECONDS)
                    }.getOrElse {
                        if (it is InterruptedException) Thread.currentThread().interrupt()
                        rememberWriterFailure(it)
                        null
                    }
                synchronized(writerProcessingLock) {
                    if (record == null) {
                        flushIfDueLocked()
                    } else {
                        writeRecord(
                            record = record,
                            flush = shouldFlush(record),
                            force = shouldForce(record),
                        )
                    }
                }
            }
        }
    }

    /**
     * Runs the drainQueueSynchronouslyLocked operation.
     */
    private fun drainQueueSynchronouslyLocked() {
        while (true) {
            val record = queue.poll() ?: return
            writeRecord(record, flush = shouldFlush(record), force = shouldForce(record))
        }
    }

    /**
     * Runs the flushIfDueLocked operation.
     */
    private fun flushIfDueLocked() {
        val force = shouldForceByInterval()
        runCatching {
            fileWriter.flush(force = force)
            if (force) {
                latestDurableSequence.set(latestWrittenSequence.get())
                lastForceElapsedNanos = clock.elapsedRealtimeNanos()
            }
            updateMetadata {
                copy(
                    latestWrittenSequence = this@FbbEngine.latestWrittenSequence.get(),
                    latestDurableSequence = this@FbbEngine.latestDurableSequence.get(),
                )
            }
            storage.updateActiveMetadata(metadata)
        }.onFailure(::rememberWriterFailure)
    }

    /**
     * Runs the writeRecord operation.
     */
    private fun writeRecord(record: FbbPendingRecord, flush: Boolean, force: Boolean) {
        runCatching {
            fileWriter.append(record.lines, flush = flush, force = force)
            latestWrittenSequence.set(record.sequence)
            if (force) {
                latestDurableSequence.set(record.sequence)
                lastForceElapsedNanos = clock.elapsedRealtimeNanos()
            }
            updateMetadata {
                copy(
                    latestWrittenSequence = this@FbbEngine.latestWrittenSequence.get(),
                    latestDurableSequence = this@FbbEngine.latestDurableSequence.get(),
                )
            }
            if (flush || force || record.sequence % METADATA_UPDATE_INTERVAL == 0L) {
                storage.updateActiveMetadata(metadata)
            }
        }.onFailure(::rememberWriterFailure)
        record.ack?.countDown()
    }

    /**
     * Runs the writeFooterLocked operation.
     */
    private fun writeFooterLocked(status: FbbReportStatus) {
        if (!footerWritten.compareAndSet(false, true)) return
        runCatching {
            fileWriter.append(
                formatter.footer(
                    status = status,
                    endedAtEpochMillis = clock.wallClockMillis(),
                    latestSequence = latestWrittenSequence.get(),
                ),
                flush = true,
                force = true,
            )
            latestDurableSequence.set(latestWrittenSequence.get())
            lastForceElapsedNanos = clock.elapsedRealtimeNanos()
        }.onFailure(::rememberWriterFailure)
    }

    /**
     * Runs the shouldAwaitAck operation.
     */
    private fun shouldAwaitAck(persistence: FbbPersistence): Boolean =
        config.durabilityMode == FbbDurabilityMode.STRICT || persistence == FbbPersistence.CRITICAL

    /**
     * Runs the shouldFlush operation.
     */
    private fun shouldFlush(record: FbbPendingRecord): Boolean =
        record.persistence != FbbPersistence.NORMAL ||
            config.durabilityMode == FbbDurabilityMode.STRICT

    /**
     * Runs the shouldForce operation.
     */
    private fun shouldForce(record: FbbPendingRecord): Boolean =
        record.persistence == FbbPersistence.CRITICAL ||
            config.durabilityMode == FbbDurabilityMode.STRICT ||
            shouldForceByInterval()

    /**
     * Runs the shouldForceByInterval operation.
     */
    private fun shouldForceByInterval(): Boolean =
        latestWrittenSequence.get() > latestDurableSequence.get() &&
            durationMillisSince(lastForceElapsedNanos) >= config.forceIntervalMillis

    /**
     * Runs the rememberWriterFailure operation.
     */
    private fun rememberWriterFailure(error: Throwable) {
        writerFailures.incrementAndGet()
        lastFailure = "${error.javaClass.simpleName}: ${error.message.orEmpty()}"
    }

    /**
     * Runs the updateMetadata operation.
     */
    private fun updateMetadata(update: FbbSessionMetadata.() -> FbbSessionMetadata) {
        metadata = metadata.update()
    }

    /**
     * Stores the mutable lastForceElapsedNanos value.
     */
    private var lastForceElapsedNanos: Long = clock.elapsedRealtimeNanos()

    /**
     * Documents the FbbPendingRecord type and the role it plays in this module.
     */
    private data class FbbPendingRecord(
        /**
         * Exposes the sequence value.
         */
        val sequence: Long,
        /**
         * Exposes the eventId value.
         */
        val eventId: String,
        /**
         * Exposes the lines value.
         */
        val lines: List<String>,
        /**
         * Exposes the persistence value.
         */
        val persistence: FbbPersistence,
        /**
         * Exposes the ack value.
         */
        val ack: CountDownLatch?,
    )

    companion object {
        private const val STRICT_ACK_TIMEOUT_MILLIS = 5_000L
        private const val WRITER_JOIN_TIMEOUT_MILLIS = 2_000L
        private const val METADATA_UPDATE_INTERVAL = 25L

        /**
         * Runs the start operation.
         */
        fun start(
            storage: FbbStorage,
            appInfo: FbbAppInfo,
            config: FbbConfig,
            clock: FbbClock,
            installCrashHandler: Boolean,
        ): FbbEngine {
            val fixedConfig = config.withRequiredDiagnosticsMode()
            recoverPreviousActiveSession(storage, clock)
            val metadata = storage.createSession(clock)
            val session = FbbSession(
                id = metadata.sessionId,
                startedAtEpochMillis = metadata.startedAtEpochMillis,
                startedAtElapsedNanos = clock.elapsedRealtimeNanos(),
                reportFileName = metadata.reportFileName,
            )
            val formatter = FbbFormatter(session, appInfo, fixedConfig)
            val writer = FbbReportFileWriter(storage.reportFile(metadata))
            writer.append(formatter.header(), flush = true, force = true)
            val engine = FbbEngine(
                storage = storage,
                session = session,
                config = fixedConfig,
                clock = clock,
                formatter = formatter,
                fileWriter = writer,
            )
            engine.latestDurableSequence.set(0L)
            if (installCrashHandler) FbbCrashHandler.install(engine)
            engine.processStartEvent = engine.record(
                FbbEvent(
                    type = FbbEventType.TRIGGER,
                    description = "${appInfo.appName} process started",
                    persistence = FbbPersistence.IMPORTANT,
                )
            )
            return engine
        }

        /**
         * Runs the recoverPreviousActiveSession operation.
         */
        private fun recoverPreviousActiveSession(storage: FbbStorage, clock: FbbClock) {
            val previous = storage.readActiveMetadata() ?: return
            if (previous.status != FbbReportStatus.ACTIVE) return
            val inspection = storage.inspectAndRepairReport(storage.reportFile(previous))
            val recoverySequence = inspection.latestSequence + 1L
            storage.appendRecoveryRecord(
                metadata = previous,
                sequence = recoverySequence,
                nowEpochMillis = clock.wallClockMillis(),
                truncatedIncompleteTail = inspection.truncatedIncompleteTail,
            )
        }
    }
}

/**
 * Documents the FbbCrashHandler type and the role it plays in this module.
 */
private class FbbCrashHandler(
    /**
     * Exposes the engine value.
     */
    private val engine: FbbEngine,
    /**
     * Exposes the previous value.
     */
    private val previous: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {
    /**
     * Runs the uncaughtException operation.
     */
    override fun uncaughtException(thread: Thread, error: Throwable) {
        engine.recordCrashSynchronously(thread, error)
        if (previous != null && previous !== this) {
            previous.uncaughtException(thread, error)
        } else {
            Runtime.getRuntime().exit(10)
        }
    }

    companion object {
        /**
         * Runs the install operation.
         */
        fun install(engine: FbbEngine) {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            if (previous is FbbCrashHandler) return
            Thread.setDefaultUncaughtExceptionHandler(FbbCrashHandler(engine, previous))
        }
    }
}

/**
 * Runs the Throwable operation.
 */
private fun Throwable.stackTraceText(): String {
    val writer = java.io.StringWriter()
    printStackTrace(java.io.PrintWriter(writer))
    return writer.toString()
}
