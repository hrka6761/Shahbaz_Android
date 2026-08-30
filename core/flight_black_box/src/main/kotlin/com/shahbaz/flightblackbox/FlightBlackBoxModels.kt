package com.shahbaz.flightblackbox

import java.io.File

/** Controls how much diagnostic detail producers should emit. */
enum class FbbTraceLevel {
    BASIC,
    DETAILED,
    DEEP,
}

/** Controls how aggressively the writer flushes normal diagnostics to durable storage. */
enum class FbbDurabilityMode {
    STANDARD,
    RELIABLE,
    STRICT,
}

/** Per-event persistence importance. */
enum class FbbPersistence {
    NORMAL,
    IMPORTANT,
    CRITICAL,
}

/** Streaming session status persisted beside each report. */
enum class FbbReportStatus {
    ACTIVE,
    COMPLETED,
    CRASHED,
    ABNORMAL_TERMINATION,
    INCOMPLETE,
}

/** Stable event categories used in the human-readable execution timeline. */
enum class FbbEventType {
    SESSION,
    APP,
    TRIGGER,
    CALL,
    ENTER,
    RETURN,
    VALUE,
    STATE,
    DECISION,
    USER,
    UI,
    NAV,
    SYSTEM,
    LIFECYCLE,
    ASYNC_START,
    ASYNC_RESUME,
    ASYNC_SUSPEND,
    WAIT,
    TIMEOUT,
    USB_TX,
    USB_RX,
    WARNING,
    ERROR,
    EXCEPTION,
    CRASH,
    RECOVERY,
    FBB_INTERNAL,
}

/** Opaque event identifier returned to callers so causal chains can be explicit. */
data class FbbEventRef(val value: String) {
    init {
        require(value.isNotBlank()) { "Event reference cannot be blank" }
    }

    /**
     * Runs the toString operation.
     */
    override fun toString(): String = value
}

/** Immutable process and app facts written into each report header. */
data class FbbAppInfo(
    /**
     * Exposes the appName value.
     */
    val appName: String = "Shahbaz",
    /**
     * Exposes the versionName value.
     */
    val versionName: String = "unknown",
    /**
     * Exposes the versionCode value.
     */
    val versionCode: Long = 0L,
    /**
     * Exposes the buildType value.
     */
    val buildType: String = "unknown",
    /**
     * Exposes the androidVersion value.
     */
    val androidVersion: String = "unknown",
    /**
     * Exposes the deviceModel value.
     */
    val deviceModel: String = "unknown",
)

/** Configuration for one process-owned recorder session. */
data class FbbConfig(
    /**
     * Exposes the traceLevel value.
     */
    val traceLevel: FbbTraceLevel = FbbTraceLevel.DEEP,
    /**
     * Exposes the durabilityMode value.
     */
    val durabilityMode: FbbDurabilityMode = FbbDurabilityMode.STRICT,
    /**
     * Exposes the queueCapacity value.
     */
    val queueCapacity: Int = 4_096,
    /**
     * Exposes the queueBackpressureWarningThresholdMillis value.
     */
    val queueBackpressureWarningThresholdMillis: Long = 50L,
    /**
     * Exposes the normalFlushIntervalMillis value.
     */
    val normalFlushIntervalMillis: Long = 250L,
    /**
     * Exposes the forceIntervalMillis value.
     */
    val forceIntervalMillis: Long = 2_000L,
    /**
     * Exposes the maxInlineValueLength value.
     */
    val maxInlineValueLength: Int = 240,
    /**
     * Exposes the maxDetailLength value.
     */
    val maxDetailLength: Int = 24_000,
    /**
     * Exposes the includeThreadName value.
     */
    val includeThreadName: Boolean = true,
) {
    init {
        require(queueCapacity > 0) { "Queue capacity must be positive" }
        require(queueBackpressureWarningThresholdMillis >= 0L)
        require(normalFlushIntervalMillis > 0L)
        require(forceIntervalMillis >= normalFlushIntervalMillis)
        require(maxInlineValueLength in 32..4_096)
        require(maxDetailLength in 1_024..512_000)
    }
}

/**
 * Runs the FbbConfig operation.
 */
internal fun FbbConfig.withRequiredDiagnosticsMode(): FbbConfig =
    copy(traceLevel = FbbTraceLevel.DEEP, durabilityMode = FbbDurabilityMode.STRICT)

/** Complete public event shape accepted by the recorder. */
data class FbbEvent(
    /**
     * Exposes the type value.
     */
    val type: FbbEventType,
    /**
     * Exposes the description value.
     */
    val description: String,
    /**
     * Exposes the cause value.
     */
    val cause: FbbEventRef? = null,
    /**
     * Exposes the parent value.
     */
    val parent: FbbEventRef? = null,
    /**
     * Exposes the traceId value.
     */
    val traceId: String? = null,
    /**
     * Exposes the spanId value.
     */
    val spanId: String? = null,
    /**
     * Exposes the parentSpanId value.
     */
    val parentSpanId: String? = null,
    /**
     * Exposes the metadata value.
     */
    val metadata: Map<String, Any?> = emptyMap(),
    /**
     * Exposes the detail value.
     */
    val detail: String? = null,
    /**
     * Exposes the persistence value.
     */
    val persistence: FbbPersistence = FbbPersistence.NORMAL,
)

/** Point-in-time health counters for persistence awareness and backpressure diagnostics. */
data class FbbHealth(
    /**
     * Exposes the initialized value.
     */
    val initialized: Boolean,
    /**
     * Exposes the activeSessionId value.
     */
    val activeSessionId: String?,
    /**
     * Exposes the latestProducedSequence value.
     */
    val latestProducedSequence: Long,
    /**
     * Exposes the latestWrittenSequence value.
     */
    val latestWrittenSequence: Long,
    /**
     * Exposes the latestDurableSequence value.
     */
    val latestDurableSequence: Long,
    /**
     * Exposes the queuedEvents value.
     */
    val queuedEvents: Int,
    /**
     * Exposes the backpressureBlocks value.
     */
    val backpressureBlocks: Long,
    /**
     * Exposes the writerFailures value.
     */
    val writerFailures: Long,
    /**
     * Exposes the lastFailure value.
     */
    val lastFailure: String?,
)

/** Metadata used by Reports or another UI-owning module to manage reports safely. */
data class FbbReportDescriptor(
    /**
     * Exposes the sessionId value.
     */
    val sessionId: String,
    /**
     * Exposes the fileName value.
     */
    val fileName: String,
    /**
     * Exposes the file value.
     */
    val file: File,
    /**
     * Exposes the startedAtEpochMillis value.
     */
    val startedAtEpochMillis: Long,
    /**
     * Exposes the status value.
     */
    val status: FbbReportStatus,
    /**
     * Exposes the active value.
     */
    val active: Boolean,
    /**
     * Exposes the sizeBytes value.
     */
    val sizeBytes: Long,
)

/** Parsed report facts used by Reports without loading the full report into memory. */
data class FbbReportDetails(
    /**
     * Exposes the descriptor value.
     */
    val descriptor: FbbReportDescriptor,
    /**
     * Exposes the endedAtEpochMillis value.
     */
    val endedAtEpochMillis: Long?,
    /**
     * Exposes the durationMillis value.
     */
    val durationMillis: Long?,
    /**
     * Exposes the eventCount value.
     */
    val eventCount: Int,
    /**
     * Exposes the warningCount value.
     */
    val warningCount: Int,
    /**
     * Exposes the errorCount value.
     */
    val errorCount: Int,
    /**
     * Exposes the crashCount value.
     */
    val crashCount: Int,
)

/** Aggregate report-storage facts for summary UI. */
data class FbbReportStorageStats(
    /**
     * Exposes the reportCount value.
     */
    val reportCount: Int,
    /**
     * Exposes the activeReportCount value.
     */
    val activeReportCount: Int,
    /**
     * Exposes the totalBytes value.
     */
    val totalBytes: Long,
)

/** Window of report text read from a large report file. */
data class FbbReportTextChunk(
    /**
     * Exposes the sessionId value.
     */
    val sessionId: String,
    /**
     * Exposes the startOffsetBytes value.
     */
    val startOffsetBytes: Long,
    /**
     * Exposes the nextOffsetBytes value.
     */
    val nextOffsetBytes: Long?,
    /**
     * Exposes the endOfFile value.
     */
    val endOfFile: Boolean,
    /**
     * Exposes the text value.
     */
    val text: String,
)

/** One bounded search hit from a report file. */
data class FbbReportSearchMatch(
    /**
     * Exposes the sessionId value.
     */
    val sessionId: String,
    /**
     * Exposes the lineNumber value.
     */
    val lineNumber: Int,
    /**
     * Exposes the excerpt value.
     */
    val excerpt: String,
)

/** Marks a function or class as intended for Flight Black Box tracing. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.CONSTRUCTOR)
@Retention(AnnotationRetention.BINARY)
annotation class FbbTrace(
    /**
     * Exposes the level value.
     */
    val level: FbbTraceLevel = FbbTraceLevel.DEEP,
    /**
     * Exposes the name value.
     */
    val name: String = "",
)

/** Marks a value parameter, field, or property as sensitive when explicit formatting is added. */
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class FbbRedact
