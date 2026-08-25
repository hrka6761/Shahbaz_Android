package com.shahbaz.flightblackbox

import android.content.Context
import android.os.Build
import com.shahbaz.flightblackbox.internal.AndroidFbbClock
import com.shahbaz.flightblackbox.internal.FbbEngine
import com.shahbaz.flightblackbox.internal.FbbStorage
import java.io.File

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

    fun getReportFile(sessionId: String): File? =
        getReportDescriptors().firstOrNull { it.sessionId == sessionId }?.file

    fun deleteReport(sessionId: String): Boolean {
        val descriptor = getReportDescriptors().firstOrNull { it.sessionId == sessionId }
            ?: return false
        if (descriptor.active || descriptor.status == FbbReportStatus.ACTIVE) return false
        return storage.deleteReport(sessionId)
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
