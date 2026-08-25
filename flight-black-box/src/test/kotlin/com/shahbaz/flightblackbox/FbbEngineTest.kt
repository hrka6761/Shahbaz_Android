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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FbbEngineTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

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
            while (find()) values += group(1).toInt()
            values
        }
        assertEquals((1..201).toList(), sequences)

        engine.close()
    }

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
        assertFalse(recoveredText.contains("PARTIAL WITHOUT TERMINATOR"))
        assertEquals(
            FbbReportStatus.ABNORMAL_TERMINATION,
            storage.listDescriptors(second.activeSessionId)
                .first { it.sessionId == firstDescriptor.sessionId }
                .status,
        )

        second.close()
    }

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

    private fun newStorage(): FbbStorage =
        FbbStorage.fromFilesDir(temporaryFolder.newFolder("files"))

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

    private class FakeClock : FbbClock {
        private val wall = AtomicLong(1_787_600_000_000L)
        private val elapsed = AtomicLong(0L)

        override fun wallClockMillis(): Long = wall.addAndGet(17L)

        override fun elapsedRealtimeNanos(): Long = elapsed.addAndGet(1_000_000L)
    }

    private companion object {
        val EventIdPattern: Pattern = Pattern.compile("\\bE(\\d{6})\\b")
    }
}
