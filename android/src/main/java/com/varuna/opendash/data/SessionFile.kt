package com.varuna.opendash.data

import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/**
 * Opens a recorded session, whichever of the two formats it is in.
 *
 * A file this app wrote — `ms,parameter,unit,value`, optionally gzipped — or a
 * `.sm2` from the Scanmatik Windows software. Both end up as the same thing: a
 * list of parameter names and a list of readings, so one chart drawer serves
 * both.
 */
object SessionFile {

    class Session(
        val startedAt: Long,
        val parameters: List<String>,
        val units: List<String>,
        /** milliseconds, parameter index, value */
        val samples: List<Triple<Int, Int, Double>>,
    ) {
        val durationMs: Int
            get() = if (samples.isEmpty()) 0 else samples.last().first - samples.first().first

        fun seriesOf(index: Int): List<Pair<Int, Double>> =
            samples.filter { it.second == index }.map { it.first to it.third }
    }

    /** The signature decides, not the extension: a renamed file still opens. */
    fun read(bytes: ByteArray, modified: Long = System.currentTimeMillis()): Session {
        if (bytes.size > 4 && String(bytes, 0, 4, Charsets.US_ASCII) == "SMFS") {
            val recording = Sm2Reader.read(bytes)
            return Session(
                startedAt = recording.startedAt,
                parameters = recording.parameters,
                units = List(recording.parameters.size) { "" },
                samples = recording.samples,
            )
        }
        val text = if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { String(it.readBytes(), Charsets.UTF_8) }
        } else {
            String(bytes, Charsets.UTF_8)
        }
        return csv(text, modified)
    }

    /**
     * The rows arrive interleaved, one parameter at a time, because that is how
     * they were polled. The index of each name is assigned on first sight, so
     * the order on screen matches the order they were recorded in.
     */
    private fun csv(text: String, modified: Long): Session {
        val names = ArrayList<String>()
        val units = ArrayList<String>()
        val index = HashMap<String, Int>()
        val samples = ArrayList<Triple<Int, Int, Double>>()

        text.lineSequence().drop(1).forEach { line ->
            if (line.isBlank()) return@forEach
            val parts = split(line)
            if (parts.size < 4) return@forEach
            val ms = parts[0].toIntOrNull() ?: return@forEach
            val value = parts[3].toDoubleOrNull() ?: return@forEach
            val i = index.getOrPut(parts[1]) {
                names.add(parts[1])
                units.add(parts[2])
                names.size - 1
            }
            samples.add(Triple(ms, i, value))
        }
        require(names.isNotEmpty()) { "no readings in this file" }
        return Session(modified, names, units, samples)
    }

    /** Just enough CSV: only the parameter name is ever quoted. */
    private fun split(line: String): List<String> {
        val out = ArrayList<String>(4)
        val field = StringBuilder()
        var quoted = false
        for (c in line) {
            when {
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> {
                    out.add(field.toString())
                    field.setLength(0)
                }
                else -> field.append(c)
            }
        }
        out.add(field.toString())
        return out
    }
}
