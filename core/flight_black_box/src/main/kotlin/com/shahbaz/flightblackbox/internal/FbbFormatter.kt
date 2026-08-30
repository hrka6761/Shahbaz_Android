package com.shahbaz.flightblackbox.internal

import com.shahbaz.flightblackbox.FbbAppInfo
import com.shahbaz.flightblackbox.FbbConfig
import com.shahbaz.flightblackbox.FbbEvent
import com.shahbaz.flightblackbox.FbbEventRef
import com.shahbaz.flightblackbox.FbbEventType
import com.shahbaz.flightblackbox.FbbReportStatus
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Documents the FbbSession type and the role it plays in this module.
 */
internal data class FbbSession(
    /**
     * Exposes the id value.
     */
    val id: String,
    /**
     * Exposes the startedAtEpochMillis value.
     */
    val startedAtEpochMillis: Long,
    /**
     * Exposes the startedAtElapsedNanos value.
     */
    val startedAtElapsedNanos: Long,
    /**
     * Exposes the reportFileName value.
     */
    val reportFileName: String,
)

/**
 * Documents the FbbFormatter type and the role it plays in this module.
 */
internal class FbbFormatter(
    /**
     * Exposes the session value.
     */
    private val session: FbbSession,
    /**
     * Exposes the appInfo value.
     */
    private val appInfo: FbbAppInfo,
    /**
     * Exposes the config value.
     */
    private val config: FbbConfig,
) {
    private val redactor = FbbRedactor(config)

    /**
     * Runs the header operation.
     */
    fun header(): List<String> = listOf(
        "SHAHBAZ FLIGHT BLACK BOX",
        "Format: SEN/1",
        "Session ID: ${session.id}",
        "Started: ${formatWallTime(session.startedAtEpochMillis)}",
        "Status: ${FbbReportStatus.ACTIVE}",
        "App Version: ${redactor.valueToString(appInfo.versionName)}",
        "Version Code: ${appInfo.versionCode}",
        "Build Type: ${redactor.valueToString(appInfo.buildType)}",
        "Android Version: ${redactor.valueToString(appInfo.androidVersion)}",
        "Device Model: ${redactor.valueToString(appInfo.deviceModel)}",
        "Trace Level: ${config.traceLevel}",
        "Durability Mode: ${config.durabilityMode}",
        "Queue Capacity: ${config.queueCapacity}",
        "Storage: files/flight_black_box/reports/${session.reportFileName}",
        "--------------------------------------------------",
        "TIMELINE",
    )

    /**
     * Runs the event operation.
     */
    fun event(sequence: Long, event: FbbEvent, elapsedNanos: Long): List<String> {
        val eventId = formatEventId(sequence)
        val detail = event.detail?.let { redactor.detail(it) }
        val metadata = redactor.metadata(event.metadata).toMutableMap()
        if (event.cause != null) metadata["cause"] = event.cause.value
        if (event.parent != null) metadata["parent"] = event.parent.value
        if (!event.traceId.isNullOrBlank()) metadata["trace"] = event.traceId
        if (!event.spanId.isNullOrBlank()) metadata["span"] = event.spanId
        if (!event.parentSpanId.isNullOrBlank()) metadata["parentSpan"] = event.parentSpanId
        if (config.includeThreadName) metadata["thread"] = Thread.currentThread().name
        val detailId = detail?.let(::detailId)
        if (detailId != null) metadata["detail"] = detailId

        val mainLine = buildString {
            append("[")
            append(formatRelative(elapsedNanos - session.startedAtElapsedNanos))
            append("] ")
            append(eventId)
            append(" ")
            append(event.type.name)
            append(" | ")
            append(redactor.valueToString(event.description))
            val meta = metadata.toSortedMap().entries.joinToString(" | ") { (key, value) ->
                "$key=${redactor.valueToString(value)}"
            }
            if (meta.isNotBlank()) {
                append(" | ")
                append(meta)
            }
            append(" #END")
        }

        if (detail == null || detailId == null) return listOf(mainLine)
        return listOf(mainLine) + formatDetail(detailId, detail)
    }

    /**
     * Runs the footer operation.
     */
    fun footer(status: FbbReportStatus, endedAtEpochMillis: Long, latestSequence: Long): List<String> =
        footerLines(status, endedAtEpochMillis, latestSequence)

    /**
     * Runs the throwableEvent operation.
     */
    fun throwableEvent(
        sequence: Long,
        type: FbbEventType,
        description: String,
        error: Throwable,
        cause: FbbEventRef?,
        metadata: Map<String, Any?>,
        persistenceEvent: (FbbEvent) -> List<String>,
    ): List<String> {
        val allMetadata = metadata + mapOf(
            "exceptionClass" to error.javaClass.name,
            "exceptionMessage" to error.message,
        )
        return persistenceEvent(
            FbbEvent(
                type = type,
                description = description,
                cause = cause,
                metadata = allMetadata,
                detail = error.stackTraceText(),
            )
        )
    }

    /**
     * Runs the formatDetail operation.
     */
    private fun formatDetail(detailId: String, detail: String): List<String> {
        val normalizedLines = detail
            .lineSequence()
            .map { it.take(MAX_DETAIL_LINE_LENGTH) }
            .toList()
            .ifEmpty { listOf("<empty>") }
        return buildList {
            add("[DETAIL $detailId] stackTrace lines=${normalizedLines.size} #END")
            normalizedLines.forEach { line -> add("  ${line.replace('|', '/')} #END") }
            add("[/DETAIL $detailId] #END")
        }
    }

    /**
     * Runs the detailId operation.
     */
    private fun detailId(text: String): String =
        "D" + text.sha256Short().uppercase(Locale.US)

    companion object {
        private const val MAX_DETAIL_LINE_LENGTH = 1_000
        private val WallTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS XXX", Locale.US)

        /**
         * Runs the formatEventId operation.
         */
        fun formatEventId(sequence: Long): String = "E" + sequence.toString().padStart(6, '0')

        /**
         * Runs the formatWallTime operation.
         */
        fun formatWallTime(epochMillis: Long): String =
            Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(WallTimeFormatter)

        /**
         * Runs the footerLines operation.
         */
        fun footerLines(
            status: FbbReportStatus,
            endedAtEpochMillis: Long,
            latestSequence: Long,
        ): List<String> = listOf(
            "--------------------------------------------------",
            "REPORT END | status=$status | ended=${formatWallTime(endedAtEpochMillis)} | " +
                "latestEvent=${latestSequence.toLatestEventId()} #END",
        )

        /**
         * Runs the formatRelative operation.
         */
        fun formatRelative(nanos: Long): String {
            val millis = (nanos.coerceAtLeast(0L) / 1_000_000L)
            val hours = millis / 3_600_000L
            val minutes = (millis / 60_000L) % 60L
            val seconds = (millis / 1_000L) % 60L
            val remainder = millis % 1_000L
            return "+%02d:%02d:%02d.%03d".format(Locale.US, hours, minutes, seconds, remainder)
        }

        /**
         * Runs the recoveryLine operation.
         */
        fun recoveryLine(
            sequence: Long,
            startedAtEpochMillis: Long,
            nowEpochMillis: Long,
            description: String,
            metadata: Map<String, Any?>,
        ): String {
            val eventId = formatEventId(sequence)
            val relativeNanos = (nowEpochMillis - startedAtEpochMillis).coerceAtLeast(0L) * 1_000_000L
            val meta = metadata.toSortedMap().entries.joinToString(" | ") { (key, value) ->
                "$key=${value.toString().replace('|', '/')}"
            }
            return buildString {
                append("[")
                append(formatRelative(relativeNanos))
                append("] ")
                append(eventId)
                append(" RECOVERY | ")
                append(description.replace('|', '/'))
                if (meta.isNotBlank()) {
                    append(" | ")
                    append(meta)
                }
                append(" #END")
            }
        }

        /**
         * Runs the Long operation.
         */
        private fun Long.toLatestEventId(): String =
            if (this > 0L) formatEventId(this) else "none"
    }
}

/**
 * Runs the Throwable operation.
 */
private fun Throwable.stackTraceText(): String {
    val writer = StringWriter()
    printStackTrace(PrintWriter(writer))
    return writer.toString()
}
