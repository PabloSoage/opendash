package com.varuna.opendash.data

import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
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
 * ## Why flushed on a clock
 *
 * A recording ends when the car is switched off, not when someone presses stop.
 * The stream is opened with sync flushing so the bytes are in the file as they
 * happen: an interrupted session is short, not corrupt.
 *
 * ## Why on a thread of its own
 *
 * [add] is called from the thread that reads the socket, and that thread also
 * sends the heartbeat that keeps the module emitting. Compressing and writing
 * used to happen right there, and a write can block: storage picked through the
 * system file picker is a content provider, and a content provider takes as
 * long as it takes. Five seconds of that is a heartbeat that did not go out, a
 * module that stops emitting, and a recording that ends for a reason that had
 * nothing to do with the car.
 *
 * So [add] only formats a line into a buffer. Every [HANDOVER_MS] the buffer is
 * handed to a writer thread, which does the compressing and the waiting.
 *
 * ## Why a failed write opens another file
 *
 * A write that fails used to throw on the reading thread and end the run. The
 * link was fine, the module was emitting, and the rest of the drive was lost
 * because a file would not take one more block. Now the writer says so and
 * opens a new file for the rest. The time column carries on from the same
 * start, so the two join end to end; two files of one drive are worth a great
 * deal more than one file of half of it.
 */
class Recorder(private val open: () -> RecordingStore.Sink, private val compress: Boolean = true) {

    private val started = System.currentTimeMillis()

    @Volatile private var count = 0
    @Volatile private var closed = false

    /** What the reading thread has formatted and not yet handed over. */
    private var pending = StringBuilder(CHUNK_CHARS)
    private var lastHandover = 0L

    private val chunks = LinkedBlockingQueue<String>()
    private val queuedChars = java.util.concurrent.atomic.AtomicLong()

    /** Rows dropped because the writer fell too far behind. Zero, normally. */
    @Volatile var dropped = 0
        private set

    /** The last thing that went wrong writing, for the screen. */
    @Volatile var lastFailure: String? = null
        private set

    /** The file being written. Changes if a write failed and another was opened. */
    @Volatile private var sink: RecordingStore.Sink? = null

    private val writer = Thread(::write, "recorder").apply { isDaemon = true }

    val rows: Int get() = count
    val name: String? get() = sink?.name

    /**
     * [identifier] is what tells two rows of the same name apart.
     *
     * A marque catalogue names the same thing in several engine variants, each
     * reading its own identifier with its own scaling: nine rows called
     * "Exhaust Gas Temperature Sensor 1" is normal. Watch two of them at once
     * and, written by name alone, both land in one column — which reads back as
     * a single sensor flipping between 102 °C and 128 °C every few
     * milliseconds. They are two different sensors, and the file has to say so.
     *
     * Called from one thread only, the one reading the module.
     */
    fun add(parameter: String, identifier: String, unit: String, value: Double) {
        if (closed) return
        // The writer is not started until there is a row, so a run that
        // records nothing leaves no file behind: ten bytes, a gzip header and
        // not even the column names, indistinguishable afterwards from a
        // recording that failed halfway.
        if (count == 0) writer.start()
        val ms = System.currentTimeMillis() - started
        val b = pending
        b.append(ms).append(",\"").append(parameter.replace("\"", "")).append("\",")
        b.append(identifier).append(',').append(unit).append(',').append(value).append('\n')
        count++
        val now = System.currentTimeMillis()
        if (b.length >= CHUNK_CHARS || now - lastHandover >= HANDOVER_MS) {
            lastHandover = now
            handOver()
        }
    }

    private fun handOver() {
        if (pending.isEmpty()) return
        val text = pending.toString()
        pending = StringBuilder(CHUNK_CHARS)
        // A writer stuck for minutes would otherwise take the phone's memory
        // with it, at about 150 KB a second. Past the bound, rows are counted
        // and dropped rather than queued, and the screen can say how many.
        if (queuedChars.get() > MAX_QUEUED_CHARS) {
            dropped += text.count { it == '\n' }
            return
        }
        queuedChars.addAndGet(text.length.toLong())
        chunks.put(text)
    }

    /** The writer thread: takes chunks, compresses, writes, flushes on a clock. */
    private fun write() {
        var out: BufferedWriter? = null
        var lastFlush = 0L
        var parts = 0
        fun openNext(): BufferedWriter {
            parts++
            val s = open()
            sink = s
            val w = BufferedWriter(
                OutputStreamWriter(
                    if (compress) GZIPOutputStream(s.stream, true) else s.stream,
                    Charsets.UTF_8,
                )
            )
            w.write(HEADER)
            w.newLine()
            return w
        }
        while (true) {
            val text = chunks.poll(HANDOVER_MS, TimeUnit.MILLISECONDS)
            if (text == null) {
                if (closed) break
                continue
            }
            queuedChars.addAndGet(-text.length.toLong())
            try {
                val w = out ?: openNext().also { out = it }
                w.write(text)
                val now = System.currentTimeMillis()
                if (now - lastFlush >= FLUSH_EVERY_MS) {
                    w.flush()
                    lastFlush = now
                }
            } catch (e: Exception) {
                lastFailure = "writing failed (" + (e.message ?: e.javaClass.simpleName) +
                    "); carrying on in a new file"
                runCatching { out?.close() }
                out = null
                // Bounded: storage that refuses every file is not going to
                // start accepting them, and a loop opening empty files for the
                // rest of a drive is worse than stopping.
                if (parts >= MAX_PARTS) {
                    lastFailure = "writing failed in " + parts + " files; recording stopped"
                    closed = true
                    chunks.clear()
                    break
                }
            }
        }
        out?.let {
            runCatching { it.flush() }
            runCatching { it.close() }
        }
    }

    /**
     * Hand over what is left and wait for it to reach the file, so the
     * recording is complete when the screen says it is.
     *
     * May be called from another thread than [add] -- the one that pressed
     * Stop -- while the reader is still finishing a slice. What that slice
     * adds after this point is lost, which is a few milliseconds of a run
     * somebody has just ended.
     */
    fun close() {
        if (closed) return
        handOver()
        closed = true
        // Bounded, so a stuck provider cannot freeze the thread that pressed
        // Stop.
        if (writer.isAlive) runCatching { writer.join(CLOSE_WAIT_MS) }
    }

    companion object {
        private const val HEADER = "ms,parameter,identifier,unit,value"

        /** How often the reading thread hands its rows over. */
        private const val HANDOVER_MS = 100L

        /** Or sooner, if a chunk gets this big first. */
        private const val CHUNK_CHARS = 64 * 1024

        /**
         * Flushed every quarter second. Per row was right when a row was a
         * polled reading four times a second; streaming makes it seven hundred
         * a second, and every one of those was a gzip sync-flush. Every 250 ms
         * costs at most a quarter second of readings if the session is cut.
         */
        private const val FLUSH_EVERY_MS = 250L

        /** About seven minutes of rows at the measured rate. */
        private const val MAX_QUEUED_CHARS = 64L * 1024 * 1024

        private const val MAX_PARTS = 5

        private const val CLOSE_WAIT_MS = 5000L

        /** For anything that needs a stream without the CSV framing. */
        fun raw(stream: OutputStream): OutputStream = stream
    }
}
