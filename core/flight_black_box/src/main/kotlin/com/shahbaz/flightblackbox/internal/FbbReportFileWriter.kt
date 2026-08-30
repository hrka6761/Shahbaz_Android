package com.shahbaz.flightblackbox.internal

import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

/**
 * Documents the FbbReportFileWriter type and the role it plays in this module.
 */
internal class FbbReportFileWriter(reportFile: File) : Closeable {
    /**
     * Exposes the output value.
     */
    private val output = FileOutputStream(reportFile, true)
    /**
     * Exposes the writer value.
     */
    private val writer = BufferedWriter(OutputStreamWriter(output, Charsets.UTF_8))

    /**
     * Runs the append operation.
     */
    @Synchronized
    fun append(lines: List<String>, flush: Boolean, force: Boolean) {
        lines.forEach { line ->
            writer.write(line)
            writer.newLine()
        }
        if (flush || force) writer.flush()
        if (force) output.channel.force(true)
    }

    /**
     * Runs the flush operation.
     */
    @Synchronized
    fun flush(force: Boolean) {
        writer.flush()
        if (force) output.channel.force(true)
    }

    /**
     * Runs the close operation.
     */
    override fun close() {
        synchronized(this) {
            writer.flush()
            output.channel.force(true)
            writer.close()
        }
    }
}
