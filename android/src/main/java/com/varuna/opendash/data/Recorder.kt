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
class Recorder(private val open: () -> RecordingStore.Sink, private val compress: Boolean = true) {

    private var sink: RecordingStore.Sink? = null
    private var writer: BufferedWriter? = null

    private var count = 0
    private var lastFlush = 0L
    private val started = System.currentTimeMillis()
    private var closed = false

    val rows: Int get() = count
    val name: String? get() = sink?.name

    /**
     * The file is not created until there is a row to put in it.
     *
     * A run that records nothing used to leave a file behind anyway: ten bytes,
     * a gzip header and not even the column names, because nothing was ever
     * written and nothing closed it either. A folder of those is what a bad
     * afternoon at the car looks like afterwards, and none of them can be told
     * from a recording that failed halfway.
     */
    private fun ready(): BufferedWriter? {
        writer?.let { return it }
        if (closed) return null
        val s = open()
        sink = s
        val w = BufferedWriter(
            OutputStreamWriter(
                if (compress) GZIPOutputStream(s.stream, true) else s.stream,
                Charsets.UTF_8,
            )
        )
        w.write("ms,parameter,identifier,unit,value")
        w.newLine()
        writer = w
        return w
    }

    /**
     * [identifier] is what tells two rows of the same name apart.
     *
     * A marque catalogue names the same thing in several engine variants, each
     * reading its own identifier with its own scaling: nine rows called
     * "Exhaust Gas Temperature Sensor 1" is normal. Watch two of them at once
     * and, written by name alone, both land in one column — which reads back as
     * a single sensor flipping between 102 °C and 128 °C every few
     * milliseconds. They are two different sensors, and the file has to say so.
     */
    fun add(parameter: String, identifier: String, unit: String, value: Double) {
        if (closed) return
        val w = ready() ?: return
        val ms = System.currentTimeMillis() - started
        w.write(ms.toString())
        w.write(",\"")
        w.write(parameter.replace("\"", ""))
        w.write("\",")
        w.write(identifier)
        w.write(",")
        w.write(unit)
        w.write(",")
        w.write(value.toString())
        w.newLine()
        // Flushed on a clock, not on every row.
        //
        // Per row was right when a row was a polled reading four times a
        // second. Streaming makes it seven hundred a second, and every one of
        // those was a gzip sync-flush — a full flush block emitted, on the same
        // thread that has to be back reading the socket before the module sends
        // the next packet. Every 250 ms costs at most a quarter second of
        // readings if the session is cut, which is the trade the old comment
        // was making anyway.
        val now = System.currentTimeMillis()
        if (now - lastFlush >= FLUSH_EVERY_MS) {
            w.flush()
            lastFlush = now
        }
        count++
    }

    fun close() {
        if (closed) return
        closed = true
        writer?.let {
            runCatching { it.flush() }
            runCatching { it.close() }
        }
    }

    companion object {
        private const val FLUSH_EVERY_MS = 250L

        /** For anything that needs a stream without the CSV framing. */
        fun raw(stream: OutputStream): OutputStream = stream
    }
}
