package es.opendash.data

import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes a session to CSV as it happens.
 *
 * Flushed on every row rather than buffered: a recording that ends because the
 * car was switched off should still contain everything up to that moment, and
 * a few hundred flushes a minute is nothing.
 *
 * One row per reading rather than one row per instant, because the tool polls
 * parameters round-robin and they do not share a timestamp. Widening it into a
 * column per parameter is a job for whatever reads the file.
 */
class Recorder(private val file: File) {

    private var writer: BufferedWriter? = null
    private var count = 0
    private val started = System.currentTimeMillis()

    val rows: Int get() = count
    val path: String get() = file.absolutePath
    val isOpen: Boolean get() = writer != null

    fun open() {
        if (writer != null) return
        file.parentFile?.mkdirs()
        writer = file.bufferedWriter().also {
            it.write("ms,parameter,unit,value")
            it.newLine()
        }
        count = 0
    }

    fun add(parameter: String, unit: String, value: Double) {
        val w = writer ?: return
        val ms = System.currentTimeMillis() - started
        w.write("$ms,\"${parameter.replace("\"", "")}\",$unit,$value")
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
        fun newFile(directory: File, label: String): File {
            val stamp = SimpleDateFormat("yyMMdd_HHmm", Locale.ROOT).format(Date())
            val safe = label.replace(Regex("[^A-Za-z0-9_-]"), "_")
            return File(directory, "${stamp}_$safe.csv")
        }
    }
}
