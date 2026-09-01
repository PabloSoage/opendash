package com.varuna.opendash.data

import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.zip.GZIPOutputStream

/**
 * Writes a session as it happens.
 *
 * ## Why gzipped CSV
 *
 * Measured on a session the size of a 23-minute drive — 79 796 readings:
 *
 * ```
 * plain CSV                3.63 MB
 * Scanmatik's own .sm2     0.98 MB
 * CSV, gzipped             0.60 MB
 * ```
 *
 * So the obvious worry — that text is wasteful next to a proprietary binary —
 * is right about the text and wrong about the conclusion. Compressed, the same
 * rows come out smaller than the binary, because a column of similar numbers is
 * exactly what a compressor is good at. And a `.csv.gz` opens in a spreadsheet,
 * in pandas, in anything; a private binary opens in whatever we write for it.
 *
 * ## Why flushed on every row
 *
 * A recording ends when the car is switched off, not when someone presses stop.
 * The stream is opened with sync flushing so the bytes are in the file as they
 * happen: an interrupted session is short, not corrupt.
 */
class Recorder(private val sink: RecordingStore.Sink, compress: Boolean = true) {

    private val writer: BufferedWriter = BufferedWriter(
        OutputStreamWriter(
            if (compress) GZIPOutputStream(sink.stream, true) else sink.stream,
            Charsets.UTF_8,
        )
    ).also {
        it.write("ms,parameter,unit,value")
        it.newLine()
    }

    private var count = 0
    private val started = System.currentTimeMillis()
    private var closed = false

    val rows: Int get() = count
    val name: String get() = sink.name

    fun add(parameter: String, unit: String, value: Double) {
        if (closed) return
        val ms = System.currentTimeMillis() - started
        writer.write(ms.toString())
        writer.write(",\"")
        writer.write(parameter.replace("\"", ""))
        writer.write("\",")
        writer.write(unit)
        writer.write(",")
        writer.write(value.toString())
        writer.newLine()
        writer.flush()
        count++
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { writer.flush() }
        runCatching { writer.close() }
    }

    companion object {
        /** For anything that needs a stream without the CSV framing. */
        fun raw(stream: OutputStream): OutputStream = stream
    }
}
