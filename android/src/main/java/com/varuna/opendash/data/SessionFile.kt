package com.varuna.opendash.data

import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/**
 * Opens a recorded session, whichever of the two formats it is in, and puts it
 * in the shape the viewer wants.
 *
 * A file this app wrote — `ms,parameter,unit,value`, optionally gzipped — or a
 * `.sm2` from the Scanmatik Windows software. Both come out as a list of
 * channels, each with its own arrays of timestamps and values.
 *
 * Arrays rather than a list of rows, and one pair of arrays per channel rather
 * than one interleaved list, because the viewer scrolls: showing a five-second
 * window of a twenty-three-minute recording means finding the first and last
 * sample inside it, sixty times a second, for every channel on screen. On a
 * sorted `IntArray` that is a binary search; on a list of triples it is a scan
 * of eighty thousand entries, and the fling would stutter.
 */
object SessionFile {

    /** One parameter over the whole recording. [times] is ascending. */
    class Channel(
        val name: String,
        val unit: String,
        val times: IntArray,
        val values: DoubleArray,
    ) {
        val size: Int get() = times.size

        val min: Double = values.minOrNull() ?: 0.0
        val max: Double = values.maxOrNull() ?: 0.0

        /** First index with `times[i] >= ms`, or [size] when there is none. */
        fun firstAtOrAfter(ms: Int): Int {
            var low = 0
            var high = times.size
            while (low < high) {
                val mid = (low + high) ushr 1
                if (times[mid] < ms) low = mid + 1 else high = mid
            }
            return low
        }

        /**
         * The lowest and highest value between [from] and [to], with the
         * sample either side included so a trace that only crosses the window
         * still has a range.
         *
         * Null when there is nothing in there at all, which is the caller's cue
         * to fall back to the whole recording.
         */
        fun rangeIn(from: Int, to: Int): Pair<Double, Double>? {
            if (times.isEmpty()) return null
            val first = (firstAtOrAfter(from) - 1).coerceAtLeast(0)
            val last = firstAtOrAfter(to).coerceAtMost(times.size - 1)
            if (last < first) return null
            var low = values[first]
            var high = low
            for (i in first..last) {
                val v = values[i]
                if (v < low) low = v
                if (v > high) high = v
            }
            return low to high
        }

        /** The value in force at [ms] — the last one recorded at or before it. */
        fun valueAt(ms: Int): Double? {
            if (times.isEmpty()) return null
            val i = firstAtOrAfter(ms)
            return when {
                i < times.size && times[i] == ms -> values[i]
                i > 0 -> values[i - 1]
                else -> null
            }
        }
    }

    class Session(
        val startedAt: Long,
        val channels: List<Channel>,
        /** For a .sm2, how many parameters its window was showing when saved. */
        val visibleWhenSaved: Int?,
    ) {
        val durationMs: Int = channels.maxOfOrNull { it.times.lastOrNull() ?: 0 } ?: 0
        val samples: Int = channels.sumOf { it.size }
    }

    /** The signature decides, not the extension: a renamed file still opens. */
    fun read(bytes: ByteArray, modified: Long = System.currentTimeMillis()): Session {
        if (bytes.size > 4 && String(bytes, 0, 4, Charsets.US_ASCII) == "SMFS") return sm2(bytes)

        val text = if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            GZIPInputStream(ByteArrayInputStream(bytes)).use {
                String(it.readBytes(), Charsets.UTF_8)
            }
        } else {
            String(bytes, Charsets.UTF_8)
        }
        return csv(text, modified)
    }

    // ── .sm2 ──────────────────────────────────────────────────────────────

    private fun sm2(bytes: ByteArray): Session {
        val recording = Sm2Reader.read(bytes)

        // Every channel that was recorded, named where a name was found. A
        // recording saved with the window filtered down still holds all of
        // them, and showing only the named ones would be throwing away most of
        // the file.
        val counts = IntArray(recording.channels)
        for ((_, channel, _) in recording.samples) counts[channel]++

        val times = Array(recording.channels) { IntArray(counts[it]) }
        val values = Array(recording.channels) { DoubleArray(counts[it]) }
        val filled = IntArray(recording.channels)
        for ((ms, channel, value) in recording.samples) {
            val i = filled[channel]++
            times[channel][i] = ms
            values[channel][i] = value
        }

        val channels = (0 until recording.channels).map { c ->
            val name = recording.parameters.getOrNull(c) ?: "Channel ${c + 1}"
            Channel(name, Units.forParameter(name), times[c], values[c])
        }
        return Session(recording.startedAt, channels, recording.visible)
    }

    // ── our own CSV ───────────────────────────────────────────────────────

    /**
     * The rows arrive interleaved, one parameter at a time, because that is how
     * they were polled. Each name gets an index on first sight, so the order on
     * screen is the order they were recorded in.
     */
    private fun csv(text: String, modified: Long): Session {
        val index = LinkedHashMap<String, Int>()
        val units = ArrayList<String>()
        val times = ArrayList<ArrayList<Int>>()
        val values = ArrayList<ArrayList<Double>>()

        text.lineSequence().drop(1).forEach { line ->
            if (line.isBlank()) return@forEach
            val parts = split(line)
            if (parts.size < 4) return@forEach
            val ms = parts[0].toIntOrNull() ?: return@forEach
            val value = parts[3].toDoubleOrNull() ?: return@forEach
            val i = index.getOrPut(parts[1]) {
                units.add(parts[2])
                times.add(ArrayList())
                values.add(ArrayList())
                index.size
            }
            times[i].add(ms)
            values[i].add(value)
        }
        require(index.isNotEmpty()) { "no readings in this file" }

        val channels = index.keys.mapIndexed { i, name ->
            Channel(name, units[i], times[i].toIntArray(), values[i].toDoubleArray())
        }
        return Session(modified, channels, null)
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
