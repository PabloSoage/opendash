package com.varuna.opendash.data

import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/**
 * Opens a recorded session, whichever of the two formats it is in, and puts it
 * in the shape the viewer wants.
 *
 * A file this app wrote — `ms,parameter,identifier,unit,value`, optionally
 * gzipped, and the four-column files written before it — or a
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

        // Streamed, never held whole. A two-and-a-half hour recording is 5.7
        // million rows: 312 MB of text decompressed, and 624 MB again as a
        // String, because a Kotlin String is UTF-16. That is over before a
        // single row is parsed, and from the outside it is the app closing when
        // you press a file.
        val stream =
            if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
                GZIPInputStream(ByteArrayInputStream(bytes))
            } else {
                ByteArrayInputStream(bytes)
            }
        return stream.bufferedReader(Charsets.UTF_8).use { csv(it, modified) }
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
    /**
     * The header says which column is which, rather than this counting them.
     *
     * The recorder grew an `identifier` column — a marque catalogue names the
     * same thing once per configuration, so a name alone does not identify a
     * series — and this read the fourth field as the value whatever it was. On
     * a five-column file the fourth field is the unit, `"%".toDoubleOrNull()`
     * is null, every line was skipped, and a complete 185 000-row recording
     * opened as "no readings in this file".
     *
     * Reading the header means both shapes open, and it is one line of work
     * against a format that will grow a column again.
     */
    private fun csv(reader: java.io.BufferedReader, modified: Long): Session {
        val index = LinkedHashMap<String, Int>()
        val units = ArrayList<String>()
        // Primitive, not ArrayList<Int> and ArrayList<Double>. Those box every
        // single value — sixteen bytes and a reference where four or eight
        // would do — so 5.7 million readings cost about 270 MB of boxes on top
        // of the 68 MB the numbers actually need, and then toIntArray()
        // allocates the real array while the boxes are still alive.
        val times = ArrayList<Ints>()
        val values = ArrayList<Doubles>()

        val header = reader.readLine()?.let { split(it).map { f -> f.trim().lowercase() } } ?: emptyList()
        fun columnOf(name: String, fallback: Int): Int =
            header.indexOf(name).takeIf { it >= 0 } ?: fallback
        val msAt = columnOf("ms", 0)
        val nameAt = columnOf("parameter", 1)
        val idAt = header.indexOf("identifier")
        val unitAt = columnOf("unit", 2)
        val valueAt = columnOf("value", 3)
        val widest = maxOf(msAt, nameAt, unitAt, valueAt, idAt)

        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            val parts = split(line)
            if (parts.size <= widest) continue
            val ms = parts[msAt].toIntOrNull() ?: continue
            val value = parts[valueAt].toDoubleOrNull() ?: continue
            // Namesakes stay apart here too. Two rows called "Exhaust Gas
            // Temperature Sensor 1" reading different identifiers are two
            // series, and merged into one they read as a sensor flipping
            // between two temperatures.
            val label =
                if (idAt >= 0 && parts[idAt].isNotBlank()) parts[nameAt] + "  " + parts[idAt]
                else parts[nameAt]
            val i = index.getOrPut(label) {
                units.add(parts[unitAt])
                times.add(Ints())
                values.add(Doubles())
                index.size
            }
            times[i].add(ms)
            values[i].add(value)
        }
        require(index.isNotEmpty()) { "no readings in this file" }

        val channels = index.keys.mapIndexed { i, name ->
            Channel(name, units[i], times[i].trimmed(), values[i].trimmed())
        }
        return Session(modified, channels, null)
    }

    /**
     * Growable primitive arrays.
     *
     * One per channel rather than one for the file, which is what keeps the
     * peak down: a channel of a long recording is a hundred and forty thousand
     * readings, so the doubling that growth costs is half a megabyte at a time
     * and not half the file.
     */
    private class Ints {
        private var a = IntArray(256)
        private var n = 0
        fun add(v: Int) {
            if (n == a.size) a = a.copyOf(a.size * 2)
            a[n++] = v
        }
        fun trimmed(): IntArray = a.copyOf(n)
    }

    private class Doubles {
        private var a = DoubleArray(256)
        private var n = 0
        fun add(v: Double) {
            if (n == a.size) a = a.copyOf(a.size * 2)
            a[n++] = v
        }
        fun trimmed(): DoubleArray = a.copyOf(n)
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
