package es.opendash.data

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
class Recorder(private val file: File, private val compress: Boolean = true) {

    private var writer: BufferedWriter? = null
    private var count = 0
    private val started = System.currentTimeMillis()

    val rows: Int get() = count
    val path: String get() = file.absolutePath
    val isOpen: Boolean get() = writer != null

    fun open() {
        if (writer != null) return
        file.parentFile?.mkdirs()
        val out = FileOutputStream(file)
        val stream = if (compress) GZIPOutputStream(out, true) else out
        writer = BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8)).also {
            it.write("ms,parameter,unit,value")
            it.newLine()
        }
        count = 0
    }

    fun add(parameter: String, unit: String, value: Double) {
        val w = writer ?: return
        val ms = System.currentTimeMillis() - started
        w.write(ms.toString())
        w.write(",\"")
        w.write(parameter.replace("\"", ""))
        w.write("\",")
        w.write(unit)
        w.write(",")
        w.write(value.toString())
        w.newLine()
        w.flush()
        count++
    }

    fun close() {
        writer?.flush()
        writer?.close()
        writer = null
    }

    companion object {
        fun newFile(directory: File, label: String, compress: Boolean = true): File {
            val stamp = SimpleDateFormat("yyMMdd_HHmm", Locale.ROOT).format(Date())
            val safe = label.replace(Regex("[^A-Za-z0-9_-]"), "_")
            val extension = if (compress) "csv.gz" else "csv"
            return File(directory, stamp + "_" + safe + "." + extension)
        }
    }
}
