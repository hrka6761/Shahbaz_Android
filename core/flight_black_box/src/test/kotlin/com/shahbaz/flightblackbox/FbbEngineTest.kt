package com.shahbaz.flightblackbox

import com.shahbaz.flightblackbox.internal.FbbClock
import com.shahbaz.flightblackbox.internal.FbbEngine
import com.shahbaz.flightblackbox.internal.FbbStorage
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Documents the FbbEngineTest type and the role it plays in this module.
 */
class FbbEngineTest {
    /**
     * Exposes the temporaryFolder value.
     */
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /**
     * Runs the sessionCreationCreatesReportImmediately operation.
     */
    @Test
    fun sessionCreationCreatesReportImmediately() {
        val storage = newStorage()
        val engine = startEngine(storage)

        val descriptor = storage.listDescriptors(engine.activeSessionId).single()
        assertTrue(descriptor.active)
        assertTrue(descriptor.file.exists())
        assertTrue(descriptor.file.readText().contains("SHAHBAZ FLIGHT BLACK BOX"))
        assertTrue(descriptor.file.readText().contains("Status: ACTIVE"))

        engine.close()
    }

    /**
     * Runs the recorderAlwaysUsesDeepTraceAndStrictDurability operation.
     */
    @Test
    fun recorderAlwaysUsesDeepTraceAndStrictDurability() {
        val storage = newStorage()
        val engine = startEngine(
            storage = storage,
            config = FbbConfig(
                traceLevel = FbbTraceLevel.BASIC,
                durabilityMode = FbbDurabilityMode.STANDARD,
            ),
        )

        val descriptor = storage.listDescriptors(engine.activeSessionId).single()
        val header = descriptor.file.readText()
        assertTrue(header.contains("Trace Level: DEEP"))
        assertTrue(header.contains("Durability Mode: STRICT"))

        engine.record(FbbEvent(type = FbbEventType.APP, description = "normal event"))

        val health = engine.health()
        assertEquals(2L, health.latestProducedSequence)
        assertEquals(2L, health.latestWrittenSequence)
        assertEquals(2L, health.latestDurableSequence)

        engine.close()
    }

    /**
     * Runs the eventSequencingAndCausalRelationshipsAreWritten operation.
     */
    @Test
    fun eventSequencingAndCausalRelationshipsAreWritten() {
        val storage = newStorage()
        val engine = startEngine(storage)
        val user = engine.record(
            FbbEvent(
                type = FbbEventType.USER,
                description = "MainScreen.ConnectButton clicked",
                persistence = FbbPersistence.IMPORTANT,
            )
        )
        assertNotNull(user)
        engine.record(
            FbbEvent(
                type = FbbEventType.CALL,
                description = "UsbRepository.connect()",
                cause = user,
            )
        )
        engine.flushAndForce()

        val text = storage.listDescriptors(engine.activeSessionId).single().file.readText()
        assertTrue(text.contains("E000001 TRIGGER"))
        assertTrue(text.contains("E000002 USER | MainScreen.ConnectButton clicked"))
        assertTrue(text.contains("E000003 CALL | UsbRepository.connect()"))
        assertTrue(text.contains("cause=E000002"))

        engine.close()
    }

    /**
     * Runs the sensitiveMetadataIsRedacted operation.
     */
    @Test
    fun sensitiveMetadataIsRedacted() {
        val storage = newStorage()
        val engine = startEngine(storage)
        engine.record(
            FbbEvent(
                type = FbbEventType.VALUE,
                description = "Authentication metadata inspected",
                metadata = mapOf(
                    "sessionToken" to "secret-token-123",
                    "deviceId" to "board-1",
                ),
            )
        )
        engine.flushAndForce()

        val text = storage.listDescriptors(engine.activeSessionId).single().file.readText()
        assertTrue(text.contains("sessionToken=<REDACTED>"))
        assertTrue(text.contains("deviceId=board-1"))
        assertFalse(text.contains("secret-token-123"))

        engine.close()
    }

    /**
     * Runs the multipleProducersRemainGapFreeAndOrdered operation.
     */
    @Test
    fun multipleProducersRemainGapFreeAndOrdered() {
        val storage = newStorage()
        val engine = startEngine(
            storage,
            FbbConfig(queueCapacity = 8, queueBackpressureWarningThresholdMillis = 1),
        )
        val threads = (1..4).map { producer ->
            Thread {
                repeat(50) { index ->
                    engine.record(
                        FbbEvent(
                            type = FbbEventType.APP,
                            description = "producer=$producer event=$index",
                        )
                    )
                }
            }
        }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)
        engine.flushAndForce()

        val text = storage.listDescriptors(engine.activeSessionId).single().file.readText()
        val sequences = EventIdPattern.matcher(text).run {
            val values = mutableListOf<Int>()
            while (find()) values += requireNotNull(group(1)).toInt()
            values
        }
        assertEquals((1..201).toList(), sequences)

        engine.close()
    }

    /**
     * Runs the criticalEventIsDurablyPersistedBeforeReturning operation.
     */
    @Test
    fun criticalEventIsDurablyPersistedBeforeReturning() {
        val storage = newStorage()
        val engine = startEngine(storage)

        engine.record(
            FbbEvent(
                type = FbbEventType.ERROR,
                description = "Protocol failure",
                persistence = FbbPersistence.CRITICAL,
            )
        )

        val health = engine.health()
        assertEquals(2L, health.latestProducedSequence)
        assertEquals(2L, health.latestWrittenSequence)
        assertEquals(2L, health.latestDurableSequence)

        engine.close()
    }

    /**
     * Runs the previousActiveSessionIsRecoveredAndIncompleteTailIsTruncated operation.
     */
    @Test
    fun previousActiveSessionIsRecoveredAndIncompleteTailIsTruncated() {
        val storage = newStorage()
        val first = startEngine(storage)
        first.record(FbbEvent(type = FbbEventType.APP, description = "before kill"))
        first.flushAndForce()
        val firstDescriptor = storage.listDescriptors(first.activeSessionId).single()
        first.close()
        FileOutputStream(firstDescriptor.file, true).use { output ->
            output.write("PARTIAL WITHOUT TERMINATOR".toByteArray())
        }

        val second = startEngine(storage)
        second.flushAndForce()

        val recoveredText = firstDescriptor.file.readText()
        assertTrue(recoveredText.contains("ABNORMAL_TERMINATION"))
        assertTrue(recoveredText.contains("truncatedIncompleteTail=true"))
        assertTrue(recoveredText.contains("REPORT END | status=ABNORMAL_TERMINATION"))
        assertTrue(recoveredText.contains("latestEvent=E000003"))
        assertFalse(recoveredText.contains("PARTIAL WITHOUT TERMINATOR"))
        assertEquals(
            FbbReportStatus.ABNORMAL_TERMINATION,
            storage.listDescriptors(second.activeSessionId)
                .first { it.sessionId == firstDescriptor.sessionId }
                .status,
        )

        second.close()
    }

    /**
     * Runs the activeReportCannotBeDeletedThroughRepository operation.
     */
    @Test
    fun activeReportCannotBeDeletedThroughRepository() {
        val storage = newStorage()
        val engine = startEngine(storage)
        val reports = FlightBlackBoxReports(storage, engine.activeSessionId)
        val active = reports.getReportDescriptors().single()

        assertFalse(reports.deleteReport(active.sessionId))
        assertTrue(active.file.exists())

        engine.close()
    }

    /**
     * Runs the repositorySupportsReportsManagementWithoutReadingWholeFiles operation.
     */
    @Test
    fun repositorySupportsReportsManagementWithoutReadingWholeFiles() {
        val storage = newStorage()
        val engine = startEngine(storage)
        engine.record(
            FbbEvent(
                type = FbbEventType.WARNING,
                description = "Location provider is stale",
            )
        )
        engine.record(
            FbbEvent(
                type = FbbEventType.ERROR,
                description = "Protocol failure while decoding frame",
            )
        )
        engine.flushAndForce()
        engine.completeSession()
        engine.close()
        val reportText = storage.listDescriptors(activeSessionId = null).single().file.readText()

        val reports = FlightBlackBoxReports(storage, activeSessionId = null)
        val details = reports.getReportDetails(engine.activeSessionId)

        assertNotNull(details)
        assertEquals(FbbReportStatus.COMPLETED, details?.descriptor?.status)
        assertTrue(reportText.contains("REPORT END | status=COMPLETED"))
        assertTrue(reportText.contains("latestEvent=E000003"))
        assertEquals(3, details?.eventCount)
        assertEquals(1, details?.warningCount)
        assertEquals(1, details?.errorCount)
        assertTrue((details?.durationMillis ?: 0L) > 0L)
        assertTrue((reports.storageStats().totalBytes) > 0L)

        val chunk = reports.readReportChunk(engine.activeSessionId, maxBytes = 128)
        assertNotNull(chunk)
        assertEquals(engine.activeSessionId, chunk?.sessionId)
        assertTrue(chunk?.text?.contains("SHAHBAZ FLIGHT BLACK BOX") == true)

        val matches = reports.searchReport(engine.activeSessionId, "Protocol failure")
        assertEquals(1, matches.size)
        assertEquals(engine.activeSessionId, matches.single().sessionId)
        assertTrue(matches.single().excerpt.contains("Protocol failure"))

        assertEquals(0, reports.cleanupToMaxStorageBytes(maxBytes = 0L))
        assertNotNull(reports.getReportFile(engine.activeSessionId))
        assertEquals(
            1,
            reports.cleanupToMaxStorageBytes(
                maxBytes = 0L,
                includeCrashOrErrorReports = true,
            ),
        )
        assertNull(reports.getReportFile(engine.activeSessionId))
    }

    /**
     * Runs the newStorage operation.
     */
    private fun newStorage(): FbbStorage =
        FbbStorage.fromFilesDir(temporaryFolder.newFolder("files"))

    /**
     * Runs the startEngine operation.
     */
    private fun startEngine(
        storage: FbbStorage,
        config: FbbConfig = FbbConfig(),
    ): FbbEngine = FbbEngine.start(
        storage = storage,
        appInfo = FbbAppInfo(
            appName = "ShahbazTest",
            versionName = "1.0",
            versionCode = 1,
            buildType = "debug",
            androidVersion = "test",
            deviceModel = "jvm",
        ),
        config = config,
        clock = FakeClock(),
        installCrashHandler = false,
    )

    /**
     * Documents the FakeClock type and the role it plays in this module.
     */
    private class FakeClock : FbbClock {
        private val wall = AtomicLong(1_787_600_000_000L)
        private val elapsed = AtomicLong(0L)

        /**
         * Runs the wallClockMillis operation.
         */
        override fun wallClockMillis(): Long = wall.addAndGet(17L)

        /**
         * Runs the elapsedRealtimeNanos operation.
         */
        override fun elapsedRealtimeNanos(): Long = elapsed.addAndGet(1_000_000L)
    }

    private companion object {
        val EventIdPattern: Pattern = Pattern.compile("\\bE(\\d{6})\\b")
    }
}
