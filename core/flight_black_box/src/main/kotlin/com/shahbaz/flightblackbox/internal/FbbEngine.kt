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

internal class FbbEngine private constructor(
    private val storage: FbbStorage,
    private val session: FbbSession,
    private val config: FbbConfig,
    private val clock: FbbClock,
    private val formatter: FbbFormatter,
    private val fileWriter: FbbReportFileWriter,
) : Closeable {
    private val queue = LinkedBlockingQueue<FbbPendingRecord>(config.queueCapacity)
    private val recordLock = Any()
    private val queueAccessLock = Any()
    private val writerProcessingLock = Any()
    private val running = AtomicBoolean(true)
    private val footerWritten = AtomicBoolean(false)
    private val latestProducedSequence = AtomicLong(0L)
    private val latestWrittenSequence = AtomicLong(0L)
    private val latestDurableSequence = AtomicLong(0L)
    private val backpressureBlocks = AtomicLong(0L)
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

    val activeSessionId: String = session.id

    @Volatile
    var processStartEvent: FbbEventRef? = null
        private set

    private val writerThread = Thread(::writerLoop, "ShahbazFlightBlackBoxWriter").apply {
        isDaemon = true
        start()
    }

    fun elapsedRealtimeNanos(): Long = clock.elapsedRealtimeNanos()

    fun durationMillisSince(startElapsedNanos: Long): Long =
        ((clock.elapsedRealtimeNanos() - startElapsedNanos).coerceAtLeast(0L) / 1_000_000L)

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

    fun completeSession() {
        synchronized(recordLock) {
            flushAndForce()
            updateMetadata { copy(status = FbbReportStatus.COMPLETED) }
            writeFooterLocked(FbbReportStatus.COMPLETED)
            storage.writeSessionMetadata(metadata)
        }
    }

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

    private fun drainQueueSynchronouslyLocked() {
        while (true) {
            val record = queue.poll() ?: return
            writeRecord(record, flush = shouldFlush(record), force = shouldForce(record))
        }
    }

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

    private fun shouldAwaitAck(persistence: FbbPersistence): Boolean =
        config.durabilityMode == FbbDurabilityMode.STRICT || persistence == FbbPersistence.CRITICAL

    private fun shouldFlush(record: FbbPendingRecord): Boolean =
        record.persistence != FbbPersistence.NORMAL ||
            config.durabilityMode == FbbDurabilityMode.STRICT

    private fun shouldForce(record: FbbPendingRecord): Boolean =
        record.persistence == FbbPersistence.CRITICAL ||
            config.durabilityMode == FbbDurabilityMode.STRICT ||
            shouldForceByInterval()

    private fun shouldForceByInterval(): Boolean =
        latestWrittenSequence.get() > latestDurableSequence.get() &&
            durationMillisSince(lastForceElapsedNanos) >= config.forceIntervalMillis

    private fun rememberWriterFailure(error: Throwable) {
        writerFailures.incrementAndGet()
        lastFailure = "${error.javaClass.simpleName}: ${error.message.orEmpty()}"
    }

    private fun updateMetadata(update: FbbSessionMetadata.() -> FbbSessionMetadata) {
        metadata = metadata.update()
    }

    private var lastForceElapsedNanos: Long = clock.elapsedRealtimeNanos()

    private data class FbbPendingRecord(
        val sequence: Long,
        val eventId: String,
        val lines: List<String>,
        val persistence: FbbPersistence,
        val ack: CountDownLatch?,
    )

    companion object {
        private const val STRICT_ACK_TIMEOUT_MILLIS = 5_000L
        private const val WRITER_JOIN_TIMEOUT_MILLIS = 2_000L
        private const val METADATA_UPDATE_INTERVAL = 25L

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

private class FbbCrashHandler(
    private val engine: FbbEngine,
    private val previous: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, error: Throwable) {
        engine.recordCrashSynchronously(thread, error)
        if (previous != null && previous !== this) {
            previous.uncaughtException(thread, error)
        } else {
            Runtime.getRuntime().exit(10)
        }
    }

    companion object {
        fun install(engine: FbbEngine) {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            if (previous is FbbCrashHandler) return
            Thread.setDefaultUncaughtExceptionHandler(FbbCrashHandler(engine, previous))
        }
    }
}

private fun Throwable.stackTraceText(): String {
    val writer = java.io.StringWriter()
    printStackTrace(java.io.PrintWriter(writer))
    return writer.toString()
}
