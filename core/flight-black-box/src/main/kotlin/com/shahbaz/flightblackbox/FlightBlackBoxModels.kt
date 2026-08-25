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

    override fun toString(): String = value
}

/** Immutable process and app facts written into each report header. */
data class FbbAppInfo(
    val appName: String = "Shahbaz",
    val versionName: String = "unknown",
    val versionCode: Long = 0L,
    val buildType: String = "unknown",
    val androidVersion: String = "unknown",
    val deviceModel: String = "unknown",
)

/** Configuration for one process-owned recorder session. */
data class FbbConfig(
    val traceLevel: FbbTraceLevel = FbbTraceLevel.DETAILED,
    val durabilityMode: FbbDurabilityMode = FbbDurabilityMode.RELIABLE,
    val queueCapacity: Int = 4_096,
    val queueBackpressureWarningThresholdMillis: Long = 50L,
    val normalFlushIntervalMillis: Long = 250L,
    val forceIntervalMillis: Long = 2_000L,
    val maxInlineValueLength: Int = 240,
    val maxDetailLength: Int = 24_000,
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

/** Complete public event shape accepted by the recorder. */
data class FbbEvent(
    val type: FbbEventType,
    val description: String,
    val cause: FbbEventRef? = null,
    val parent: FbbEventRef? = null,
    val traceId: String? = null,
    val spanId: String? = null,
    val parentSpanId: String? = null,
    val metadata: Map<String, Any?> = emptyMap(),
    val detail: String? = null,
    val persistence: FbbPersistence = FbbPersistence.NORMAL,
)

/** Point-in-time health counters for persistence awareness and backpressure diagnostics. */
data class FbbHealth(
    val initialized: Boolean,
    val activeSessionId: String?,
    val latestProducedSequence: Long,
    val latestWrittenSequence: Long,
    val latestDurableSequence: Long,
    val queuedEvents: Int,
    val backpressureBlocks: Long,
    val writerFailures: Long,
    val lastFailure: String?,
)

/** Metadata used by Settings or another UI-owning module to manage reports safely. */
data class FbbReportDescriptor(
    val sessionId: String,
    val fileName: String,
    val file: File,
    val startedAtEpochMillis: Long,
    val status: FbbReportStatus,
    val active: Boolean,
    val sizeBytes: Long,
)

/** Marks a function or class as intended for Flight Black Box tracing. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.CONSTRUCTOR)
@Retention(AnnotationRetention.BINARY)
annotation class FbbTrace(
    val level: FbbTraceLevel = FbbTraceLevel.DETAILED,
    val name: String = "",
)

/** Marks a value parameter, field, or property as sensitive when explicit formatting is added. */
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class FbbRedact
