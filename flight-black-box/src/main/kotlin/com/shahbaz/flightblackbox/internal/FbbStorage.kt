package com.shahbaz.flightblackbox.internal

import com.shahbaz.flightblackbox.FbbReportDescriptor
import com.shahbaz.flightblackbox.FbbReportStatus
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.TimeZone
import java.util.UUID
import java.util.regex.Pattern

internal data class FbbSessionMetadata(
    val sessionId: String,
    val reportFileName: String,
    val startedAtEpochMillis: Long,
    val status: FbbReportStatus,
    val latestProducedSequence: Long,
    val latestWrittenSequence: Long,
    val latestDurableSequence: Long,
)

internal data class FbbRecoveryInspection(
    val latestSequence: Long,
    val truncatedIncompleteTail: Boolean,
)

internal class FbbStorage private constructor(private val rootDir: File) {
    private val reportsDir = File(rootDir, "reports")
    private val metadataDir = File(rootDir, "metadata")
    private val activeMetadataFile = File(metadataDir, "active_session.properties")

    init {
        reportsDir.mkdirs()
        metadataDir.mkdirs()
    }

    fun createSession(clock: FbbClock): FbbSessionMetadata {
        val sessionId = UUID.randomUUID().toString()
        val started = clock.wallClockMillis()
        val fileName = uniqueReportFileName(sessionId, started)
        val metadata = FbbSessionMetadata(
            sessionId = sessionId,
            reportFileName = fileName,
            startedAtEpochMillis = started,
            status = FbbReportStatus.ACTIVE,
            latestProducedSequence = 0L,
            latestWrittenSequence = 0L,
            latestDurableSequence = 0L,
        )
        writeSessionMetadata(metadata)
        writeActiveMetadata(metadata)
        return metadata
    }

    fun reportFile(metadata: FbbSessionMetadata): File = File(reportsDir, metadata.reportFileName)

    fun writeSessionMetadata(metadata: FbbSessionMetadata) {
        metadataDir.mkdirs()
        val file = sessionMetadataFile(metadata.sessionId)
        writePropertiesAtomically(file, metadata, "Shahbaz Flight Black Box session metadata")
        if (metadata.status == FbbReportStatus.ACTIVE || activeSessionId() == metadata.sessionId) {
            writeActiveMetadata(metadata)
        }
    }

    fun updateActiveMetadata(metadata: FbbSessionMetadata) {
        writeSessionMetadata(metadata)
        if (metadata.status == FbbReportStatus.ACTIVE) writeActiveMetadata(metadata)
    }

    fun readActiveMetadata(): FbbSessionMetadata? =
        readMetadataFile(activeMetadataFile)

    fun listDescriptors(activeSessionId: String?): List<FbbReportDescriptor> {
        val metadata = metadataDir.listFiles { file ->
            file.isFile &&
                file.extension == "properties" &&
                file.name != activeMetadataFile.name
        }.orEmpty()
        return metadata.mapNotNull { file ->
            val item = readMetadataFile(file) ?: return@mapNotNull null
            val report = reportFile(item)
            FbbReportDescriptor(
                sessionId = item.sessionId,
                fileName = item.reportFileName,
                file = report,
                startedAtEpochMillis = item.startedAtEpochMillis,
                status = item.status,
                active = item.sessionId == activeSessionId || item.status == FbbReportStatus.ACTIVE,
                sizeBytes = report.length(),
            )
        }.sortedByDescending { it.startedAtEpochMillis }
    }

    fun deleteReport(sessionId: String): Boolean {
        val metadata = readMetadataFile(sessionMetadataFile(sessionId)) ?: return false
        if (metadata.status == FbbReportStatus.ACTIVE) return false
        val reportDeleted = reportFile(metadata).delete()
        val metadataDeleted = sessionMetadataFile(sessionId).delete()
        return reportDeleted || metadataDeleted
    }

    fun inspectAndRepairReport(report: File): FbbRecoveryInspection {
        if (!report.exists() || report.length() == 0L) {
            return FbbRecoveryInspection(latestSequence = 0L, truncatedIncompleteTail = false)
        }
        val truncated = truncateIncompleteTail(report)
        val latestSequence = EventIdPattern.matcher(report.readText(Charsets.UTF_8))
            .let { matcher ->
                var latest = 0L
                while (matcher.find()) {
                    latest = maxOf(latest, matcher.group(1)?.toLong() ?: 0L)
                }
                latest
            }
        return FbbRecoveryInspection(latestSequence, truncated)
    }

    fun appendRecoveryRecord(
        metadata: FbbSessionMetadata,
        sequence: Long,
        nowEpochMillis: Long,
        truncatedIncompleteTail: Boolean,
    ): FbbSessionMetadata {
        val report = reportFile(metadata)
        report.parentFile?.mkdirs()
        val line = FbbFormatter.recoveryLine(
            sequence = sequence,
            startedAtEpochMillis = metadata.startedAtEpochMillis,
            nowEpochMillis = nowEpochMillis,
            description = "Previous session had no completed or crashed marker at process startup",
            metadata = mapOf(
                "status" to FbbReportStatus.ABNORMAL_TERMINATION,
                "lastValidEvent" to FbbFormatter.formatEventId(sequence - 1L),
                "truncatedIncompleteTail" to truncatedIncompleteTail,
            ),
        )
        FileOutputStream(report, true).use { output ->
            output.write(line.toByteArray(Charsets.UTF_8))
            output.write('\n'.code)
            output.flush()
            output.channel.force(true)
        }
        val updated = metadata.copy(
            status = FbbReportStatus.ABNORMAL_TERMINATION,
            latestProducedSequence = sequence,
            latestWrittenSequence = sequence,
            latestDurableSequence = sequence,
        )
        writeSessionMetadata(updated)
        return updated
    }

    private fun writeActiveMetadata(metadata: FbbSessionMetadata) {
        writePropertiesAtomically(activeMetadataFile, metadata, "Shahbaz Flight Black Box active session")
    }

    private fun writePropertiesAtomically(
        target: File,
        metadata: FbbSessionMetadata,
        comment: String,
    ) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(temp).use { output ->
            metadata.toProperties().store(output, comment)
            output.flush()
            output.channel.force(true)
        }
        runCatching {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.getOrElse {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun activeSessionId(): String? = readMetadataFile(activeMetadataFile)?.sessionId

    private fun readMetadataFile(file: File): FbbSessionMetadata? {
        if (!file.exists()) return null
        val properties = Properties()
        return runCatching {
            FileInputStream(file).use(properties::load)
            FbbSessionMetadata(
                sessionId = properties.requireProperty("sessionId"),
                reportFileName = properties.requireProperty("reportFileName"),
                startedAtEpochMillis = properties.requireProperty("startedAtEpochMillis").toLong(),
                status = FbbReportStatus.valueOf(properties.requireProperty("status")),
                latestProducedSequence = properties.getProperty("latestProducedSequence", "0").toLong(),
                latestWrittenSequence = properties.getProperty("latestWrittenSequence", "0").toLong(),
                latestDurableSequence = properties.getProperty("latestDurableSequence", "0").toLong(),
            )
        }.getOrNull()
    }

    private fun FbbSessionMetadata.toProperties(): Properties = Properties().apply {
        setProperty("sessionId", sessionId)
        setProperty("reportFileName", reportFileName)
        setProperty("startedAtEpochMillis", startedAtEpochMillis.toString())
        setProperty("status", status.name)
        setProperty("latestProducedSequence", latestProducedSequence.toString())
        setProperty("latestWrittenSequence", latestWrittenSequence.toString())
        setProperty("latestDurableSequence", latestDurableSequence.toString())
    }

    private fun sessionMetadataFile(sessionId: String): File =
        File(metadataDir, "$sessionId.properties")

    private fun uniqueReportFileName(sessionId: String, startedAtEpochMillis: Long): String {
        val formatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }
        val timestamp = formatter.format(Date(startedAtEpochMillis))
        val shortSessionId = sessionId.replace("-", "").takeLast(8).uppercase(Locale.US)
        val baseName = "FBB_${timestamp}_$shortSessionId"
        var candidate = "$baseName.txt"
        var suffix = 1
        while (File(reportsDir, candidate).exists()) {
            candidate = "${baseName}_$suffix.txt"
            suffix += 1
        }
        return candidate
    }

    private fun truncateIncompleteTail(report: File): Boolean {
        RandomAccessFile(report, "rw").use { file ->
            val length = file.length()
            if (length == 0L) return false
            file.seek(length - 1L)
            if (file.readByte() == '\n'.code.toByte()) return false
            var position = length - 1L
            while (position > 0L) {
                position -= 1L
                file.seek(position)
                if (file.readByte() == '\n'.code.toByte()) {
                    file.setLength(position + 1L)
                    return true
                }
            }
            file.setLength(0L)
            return true
        }
    }

    companion object {
        private val EventIdPattern = Pattern.compile("\\bE(\\d{6,})\\b")

        fun fromFilesDir(filesDir: File): FbbStorage =
            FbbStorage(File(filesDir, "flight_black_box"))
    }
}

private fun Properties.requireProperty(name: String): String =
    getProperty(name) ?: error("Missing property $name")
