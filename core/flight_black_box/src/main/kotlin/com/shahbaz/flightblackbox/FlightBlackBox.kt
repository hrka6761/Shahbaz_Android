package com.shahbaz.flightblackbox

import android.content.Context
import android.os.Build
import com.shahbaz.flightblackbox.internal.AndroidFbbClock
import com.shahbaz.flightblackbox.internal.FbbEngine
import com.shahbaz.flightblackbox.internal.FbbStorage
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/** Process-owned facade for Shahbaz Flight Black Box diagnostics. */
object FlightBlackBox {
    @Volatile
    private var engine: FbbEngine? = null

    /** Initializes the current process session and creates the report file synchronously. */
    fun initialize(
        context: Context,
        appInfo: FbbAppInfo = context.defaultAppInfo(),
        config: FbbConfig = FbbConfig(),
    ) {
        val existing = engine
        if (existing != null) {
            existing.record(
                FbbEvent(
                    type = FbbEventType.FBB_INTERNAL,
                    description = "initialize() ignored because a session already exists",
                    persistence = FbbPersistence.IMPORTANT,
                )
            )
            return
        }

        synchronized(this) {
            if (engine != null) return
            val storage = FbbStorage.fromFilesDir(context.filesDir)
            engine = FbbEngine.start(
                storage = storage,
                appInfo = appInfo,
                config = config,
                clock = AndroidFbbClock,
                installCrashHandler = true,
            )
        }
    }

    /** Records one event and returns its stable event id when the recorder is initialized. */
    fun record(event: FbbEvent): FbbEventRef? = engine?.record(event)

    /** Event id for the process-start trigger written during initialization. */
    fun processStartEvent(): FbbEventRef? = engine?.processStartEvent

    /** Convenience overload for concise event producer call sites. */
    fun record(
        type: FbbEventType,
        description: String,
        cause: FbbEventRef? = null,
        parent: FbbEventRef? = null,
        metadata: Map<String, Any?> = emptyMap(),
        persistence: FbbPersistence = FbbPersistence.NORMAL,
    ): FbbEventRef? = record(
        FbbEvent(
            type = type,
            description = description,
            cause = cause,
            parent = parent,
            metadata = metadata,
            persistence = persistence,
        )
    )

    /** Records a synchronous function call boundary around [block]. */
    fun <T> traceCall(
        name: String,
        cause: FbbEventRef? = null,
        metadata: Map<String, Any?> = emptyMap(),
        includeResult: Boolean = true,
        block: () -> T,
    ): T {
        val start = engine?.elapsedRealtimeNanos() ?: 0L
        val call = record(
            FbbEvent(
                type = FbbEventType.CALL,
                description = name,
                cause = cause,
                metadata = metadata,
            )
        )
        return try {
            val result = block()
            val tookMillis = engine?.durationMillisSince(start)
            record(
                FbbEvent(
                    type = FbbEventType.RETURN,
                    description = if (includeResult) "$name -> $result" else name,
                    cause = call,
                    metadata = mapOf("tookMs" to tookMillis),
                )
            )
            result
        } catch (error: Throwable) {
            val tookMillis = engine?.durationMillisSince(start)
            recordThrowable(
                type = FbbEventType.EXCEPTION,
                description = "$name threw ${error.javaClass.simpleName}",
                error = error,
                cause = call,
                metadata = mapOf("tookMs" to tookMillis),
                persistence = FbbPersistence.CRITICAL,
            )
            throw error
        }
    }

    /** Records an exception or error with a detail section for the stack trace. */
    fun recordThrowable(
        type: FbbEventType = FbbEventType.EXCEPTION,
        description: String,
        error: Throwable,
        cause: FbbEventRef? = null,
        metadata: Map<String, Any?> = emptyMap(),
        persistence: FbbPersistence = FbbPersistence.CRITICAL,
    ): FbbEventRef? = engine?.recordThrowable(
        type = type,
        description = description,
        error = error,
        cause = cause,
        metadata = metadata,
        persistence = persistence,
    )

    /** Forces all currently queued events through the writer. Mainly useful in tests/tools. */
    fun flush() {
        engine?.flushAndForce()
    }

    /** Returns current persistence and health counters. */
    fun health(): FbbHealth = engine?.health() ?: FbbHealth(
        initialized = false,
        activeSessionId = null,
        latestProducedSequence = 0L,
        latestWrittenSequence = 0L,
        latestDurableSequence = 0L,
        queuedEvents = 0,
        backpressureBlocks = 0L,
        writerFailures = 0L,
        lastFailure = null,
    )

    /** Safe report access for UI-owning modules such as Settings. */
    fun reports(context: Context): FlightBlackBoxReports =
        FlightBlackBoxReports(FbbStorage.fromFilesDir(context.filesDir), engine?.activeSessionId)

    /** Persisted recorder configuration used by Settings and read during process startup. */
    fun configuration(context: Context): FlightBlackBoxConfiguration =
        FlightBlackBoxConfiguration(context.applicationContext)

    /** Test-only reset hook kept internal to the module's package API surface. */
    internal fun resetForTests() {
        synchronized(this) {
            engine?.close()
            engine = null
        }
    }
}

/** Minimal report repository. User-facing orchestration belongs in feature/settings. */
class FlightBlackBoxReports internal constructor(
    private val storage: FbbStorage,
    private val activeSessionId: String?,
) {
    fun getReportDescriptors(): List<FbbReportDescriptor> =
        storage.listDescriptors(activeSessionId)

    fun getAllReportDetails(): List<FbbReportDetails> =
        getReportDescriptors().map { descriptor -> descriptor.toDetails() }

    fun getReportDetails(sessionId: String): FbbReportDetails? =
        getReportDescriptors().firstOrNull { it.sessionId == sessionId }?.toDetails()

    fun getReportFile(sessionId: String): File? =
        getReportDescriptors().firstOrNull { it.sessionId == sessionId }?.file

    fun storageStats(): FbbReportStorageStats {
        val descriptors = getReportDescriptors()
        return FbbReportStorageStats(
            reportCount = descriptors.size,
            activeReportCount = descriptors.count { it.active },
            totalBytes = descriptors.sumOf { it.sizeBytes },
        )
    }

    fun readReportChunk(
        sessionId: String,
        offsetBytes: Long = 0L,
        maxBytes: Int = DEFAULT_REPORT_CHUNK_BYTES,
    ): FbbReportTextChunk? {
        require(offsetBytes >= 0L) { "offsetBytes must be non-negative" }
        require(maxBytes in 1..MAX_REPORT_CHUNK_BYTES) {
            "maxBytes must be between 1 and $MAX_REPORT_CHUNK_BYTES"
        }
        val report = getReportFile(sessionId)?.takeIf { it.exists() && it.isFile } ?: return null
        val fileSize = report.length()
        if (offsetBytes >= fileSize) {
            return FbbReportTextChunk(
                sessionId = sessionId,
                startOffsetBytes = fileSize,
                nextOffsetBytes = null,
                endOfFile = true,
                text = "",
            )
        }

        val readSize = minOf(maxBytes.toLong(), fileSize - offsetBytes).toInt()
        val bytes = ByteArray(readSize)
        RandomAccessFile(report, "r").use { file ->
            file.seek(offsetBytes)
            file.readFully(bytes)
        }
        val nextOffset = offsetBytes + readSize
        return FbbReportTextChunk(
            sessionId = sessionId,
            startOffsetBytes = offsetBytes,
            nextOffsetBytes = if (nextOffset < fileSize) nextOffset else null,
            endOfFile = nextOffset >= fileSize,
            text = bytes.toString(Charsets.UTF_8),
        )
    }

    fun searchReport(
        sessionId: String,
        query: String,
        maxMatches: Int = DEFAULT_SEARCH_MATCHES,
    ): List<FbbReportSearchMatch> {
        require(maxMatches > 0) { "maxMatches must be positive" }
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) return emptyList()
        val report = getReportFile(sessionId)?.takeIf { it.exists() && it.isFile } ?: return emptyList()
        val needle = normalizedQuery.lowercase(Locale.US)
        val matches = mutableListOf<FbbReportSearchMatch>()
        report.bufferedReader(Charsets.UTF_8).useLines { lines ->
            var lineNumber = 0
            for (line in lines) {
                lineNumber += 1
                if (line.lowercase(Locale.US).contains(needle)) {
                    matches += FbbReportSearchMatch(
                        sessionId = sessionId,
                        lineNumber = lineNumber,
                        excerpt = line.toBoundedExcerpt(normalizedQuery),
                    )
                    if (matches.size >= maxMatches) return matches
                }
            }
        }
        return matches
    }

    fun deleteReport(sessionId: String): Boolean {
        val descriptor = getReportDescriptors().firstOrNull { it.sessionId == sessionId }
            ?: return false
        if (descriptor.active || descriptor.status == FbbReportStatus.ACTIVE) return false
        return storage.deleteReport(sessionId)
    }

    fun deleteReports(sessionIds: Collection<String>): Int =
        sessionIds.distinct().count(::deleteReport)

    fun deleteAllReports(): Int =
        deleteReports(
            getReportDescriptors()
                .filterNot { it.active || it.status == FbbReportStatus.ACTIVE }
                .map { it.sessionId }
        )

    fun deleteReportsOlderThan(
        olderThanMillis: Long,
        includeCrashOrErrorReports: Boolean = false,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): Int {
        require(olderThanMillis >= 0L) { "olderThanMillis must be non-negative" }
        val cutoff = nowEpochMillis - olderThanMillis
        val candidates = getAllReportDetails()
            .filter { it.descriptor.startedAtEpochMillis < cutoff }
            .filter { !it.descriptor.active && it.descriptor.status != FbbReportStatus.ACTIVE }
            .filter { includeCrashOrErrorReports || !it.isCrashOrErrorReport() }
            .map { it.descriptor.sessionId }
        return deleteReports(candidates)
    }

    fun cleanupToMaxStorageBytes(
        maxBytes: Long,
        includeCrashOrErrorReports: Boolean = false,
    ): Int {
        require(maxBytes >= 0L) { "maxBytes must be non-negative" }
        var totalBytes = storageStats().totalBytes
        var deleted = 0
        val candidates = getAllReportDetails()
            .sortedBy { it.descriptor.startedAtEpochMillis }
            .filter { !it.descriptor.active && it.descriptor.status != FbbReportStatus.ACTIVE }
            .filter { includeCrashOrErrorReports || !it.isCrashOrErrorReport() }

        for (candidate in candidates) {
            if (totalBytes <= maxBytes) break
            if (deleteReport(candidate.descriptor.sessionId)) {
                totalBytes -= candidate.descriptor.sizeBytes
                deleted += 1
            }
        }
        return deleted
    }

    private fun FbbReportDescriptor.toDetails(): FbbReportDetails {
        var eventCount = 0
        var warningCount = 0
        var errorCount = 0
        var crashCount = 0
        var latestRelativeMillis: Long? = null
        if (file.exists() && file.isFile) {
            file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    val match = EventLinePattern.matchEntire(line) ?: continue
                    eventCount += 1
                    latestRelativeMillis = maxOf(
                        latestRelativeMillis ?: 0L,
                        match.relativeMillis(),
                    )
                    when (match.groupValues[5]) {
                        FbbEventType.WARNING.name -> warningCount += 1
                        FbbEventType.ERROR.name,
                        FbbEventType.EXCEPTION.name -> errorCount += 1
                        FbbEventType.CRASH.name -> {
                            crashCount += 1
                            errorCount += 1
                        }
                    }
                }
            }
        }
        val duration = latestRelativeMillis
        return FbbReportDetails(
            descriptor = this,
            endedAtEpochMillis = duration
                ?.takeUnless { active || status == FbbReportStatus.ACTIVE }
                ?.let { startedAtEpochMillis + it },
            durationMillis = duration,
            eventCount = eventCount,
            warningCount = warningCount,
            errorCount = errorCount,
            crashCount = crashCount,
        )
    }

    private fun FbbReportDetails.isCrashOrErrorReport(): Boolean =
        crashCount > 0 ||
            errorCount > 0 ||
            descriptor.status == FbbReportStatus.CRASHED ||
            descriptor.status == FbbReportStatus.ABNORMAL_TERMINATION

    private fun MatchResult.relativeMillis(): Long {
        val hours = groupValues[1].toLong()
        val minutes = groupValues[2].toLong()
        val seconds = groupValues[3].toLong()
        val millis = groupValues[4].toLong()
        return (((hours * 60L) + minutes) * 60L + seconds) * 1_000L + millis
    }

    private fun String.toBoundedExcerpt(query: String): String {
        val trimmed = trim()
        if (trimmed.length <= SEARCH_EXCERPT_CHARS) return trimmed
        val index = trimmed.lowercase(Locale.US).indexOf(query.lowercase(Locale.US)).coerceAtLeast(0)
        val start = (index - SEARCH_EXCERPT_CONTEXT_CHARS).coerceAtLeast(0)
        val end = (index + query.length + SEARCH_EXCERPT_CONTEXT_CHARS).coerceAtMost(trimmed.length)
        val prefix = if (start > 0) "..." else ""
        val suffix = if (end < trimmed.length) "..." else ""
        return prefix + trimmed.substring(start, end) + suffix
    }

    private companion object {
        private const val DEFAULT_REPORT_CHUNK_BYTES = 64 * 1024
        private const val MAX_REPORT_CHUNK_BYTES = 512 * 1024
        private const val DEFAULT_SEARCH_MATCHES = 100
        private const val SEARCH_EXCERPT_CHARS = 240
        private const val SEARCH_EXCERPT_CONTEXT_CHARS = 96
        private val EventLinePattern = Regex(
            "^\\[\\+(\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{3})] E\\d{6,} ([A-Z_]+)\\b.*$"
        )
    }
}

private fun Context.defaultAppInfo(): FbbAppInfo {
    val packageInfo = packageManager.getPackageInfo(packageName, 0)
    return FbbAppInfo(
        appName = applicationInfo.loadLabel(packageManager).toString(),
        versionName = packageInfo.versionName ?: "unknown",
        versionCode = if (Build.VERSION.SDK_INT >= 28) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        },
        buildType = runCatching {
            Class.forName("$packageName.BuildConfig").getField("BUILD_TYPE").get(null) as? String
        }.getOrNull() ?: "unknown",
        androidVersion = "${Build.VERSION.RELEASE} api=${Build.VERSION.SDK_INT}",
        deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
    )
}
