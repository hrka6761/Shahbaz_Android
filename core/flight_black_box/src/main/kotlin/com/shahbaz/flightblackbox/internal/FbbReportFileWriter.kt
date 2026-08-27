package com.shahbaz.flightblackbox.internal

import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

internal class FbbReportFileWriter(reportFile: File) : Closeable {
    private val output = FileOutputStream(reportFile, true)
    private val writer = BufferedWriter(OutputStreamWriter(output, Charsets.UTF_8))

    @Synchronized
    fun append(lines: List<String>, flush: Boolean, force: Boolean) {
        lines.forEach { line ->
            writer.write(line)
            writer.newLine()
        }
        if (flush || force) writer.flush()
        if (force) output.channel.force(true)
    }

    @Synchronized
    fun flush(force: Boolean) {
        writer.flush()
        if (force) output.channel.force(true)
    }

    override fun close() {
        synchronized(this) {
            writer.flush()
            output.channel.force(true)
            writer.close()
        }
    }
}
