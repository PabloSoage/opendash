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
        /**
         * When the file holds a screen rather than a series: its cells, in
         * order. See [Sm2Reader.Recording.cells].
         */
        val cells: List<String> = emptyList(),
        /** Readings in the file, which is more than [samples] when it was thinned. */
        readings: Long = -1,
    ) {
        val durationMs: Int = channels.maxOfOrNull { it.times.lastOrNull() ?: 0 } ?: 0
        val samples: Int = channels.sumOf { it.size }
        val readings: Long = if (readings >= 0) readings else samples.toLong()
    }

    /**
     * The same, from a stream, which is how a file on the phone should be
     * read: a two-hour recording is 78 MB compressed, and reading it whole into
     * a byte array first was 78 MB spent before the first row.
     */
    fun read(input: java.io.InputStream, modified: Long = System.currentTimeMillis()): Session {
        val buffered = java.io.BufferedInputStream(input, 1 shl 16)
        buffered.mark(8)
        val head = ByteArray(4)
        val got = buffered.read(head)
        buffered.reset()
        if (got == 4 && String(head, Charsets.US_ASCII) == "SMFS") return sm2(buffered.readBytes())
        val stream = if (got >= 2 && head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte()) {
            GZIPInputStream(buffered, 1 shl 16)
        } else {
            buffered
        }
        return stream.use { csv(it, modified) }
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
                GZIPInputStream(ByteArrayInputStream(bytes), 1 shl 16)
            } else {
                ByteArrayInputStream(bytes)
            }
        return stream.use { csv(it, modified) }
    }

    // ── .sm2 ──────────────────────────────────────────────────────────────

    private fun sm2(bytes: ByteArray): Session {
        val recording = Sm2Reader.read(bytes)
        // A saved screen has no series to chart. Handing it to the code below
        // would build zero channels and the viewer would show an empty chart
        // where a readable table belongs.
        if (recording.isScreen) {
            return Session(recording.startedAt, emptyList(), null, recording.cells)
        }

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

    // ── our own CSV ───────────────────────────────────────

    /**
     * Our own CSV, parsed straight out of the bytes.
     *
     * The rows arrive interleaved, one parameter at a time, because that is how
     * they were polled. Each name gets an index on first sight, so the order on
     * screen is the order they were recorded in.
     *
     * The header says which column is which rather than this counting them. The
     * recorder grew an `identifier` column — a marque catalogue names the same
     * thing once per configuration, so a name alone does not identify a series
     * — and this used to read the fourth field as the value whatever it was. On
     * a five-column file the fourth field is the unit, `"%".toDoubleOrNull()`
     * is null, every line was skipped, and a complete 185 000-row recording
     * opened as "no readings in this file".
     *
     * **Why bytes and not a BufferedReader.** Reading lines and splitting them
     * is about ten allocations a row — the line itself, a list, a builder, and
     * a String per field — and a recording is millions of rows. The 22-minute
     * one is 1,9 million; the long one 5,7 million. That is tens of millions of
     * short-lived objects, on a phone, with the garbage collector running the
     * whole time, and from the outside it is a file that takes forever to open.
     *
     * So nothing here allocates per row. The bytes are scanned in a sliding
     * window, the two numbers are parsed from the bytes, and the channel is
     * found by hashing the name and identifier bytes — a String is built once
     * per channel, not once per row.
     */
    private fun csv(input: java.io.InputStream, modified: Long): Session {
        val lines = Lines(input)
        val fields = Fields()

        if (!lines.next()) throw IllegalArgumentException("no readings in this file")
        fields.split(lines.bytes, lines.from, lines.to)
        val header = (0 until fields.count).map {
            String(lines.bytes, fields.from(it), fields.length(it), Charsets.UTF_8)
                .trim().lowercase()
        }
        fun columnOf(name: String, fallback: Int): Int =
            header.indexOf(name).takeIf { it >= 0 } ?: fallback
        val msAt = columnOf("ms", 0)
        val nameAt = columnOf("parameter", 1)
        val idAt = header.indexOf("identifier")
        val unitAt = columnOf("unit", 2)
        val valueAt = columnOf("value", 3)
        val widest = maxOf(msAt, nameAt, unitAt, valueAt, idAt)

        val names = ArrayList<String>()
        val units = ArrayList<String>()
        val kept = ArrayList<Thinned>()
        val channels = Channels()
        var readings = 0L

        while (lines.next()) {
            if (lines.to <= lines.from) continue
            fields.split(lines.bytes, lines.from, lines.to)
            if (fields.count <= widest) continue
            val buf = lines.bytes
            val ms = parseInt(buf, fields.from(msAt), fields.to(msAt))
            if (ms < 0) continue
            val value = parseDouble(buf, fields.from(valueAt), fields.to(valueAt))
            if (value.isNaN()) continue

            // Namesakes stay apart. Two rows called "Exhaust Gas Temperature
            // Sensor 1" reading different identifiers are two series, and
            // merged into one they read as a sensor flipping between two
            // temperatures.
            val hasId = idAt >= 0 && fields.to(idAt) > fields.from(idAt)
            val i = channels.indexOf(
                buf,
                fields.from(nameAt), fields.to(nameAt),
                if (hasId) fields.from(idAt) else 0,
                if (hasId) fields.to(idAt) else 0,
            )
            if (i == channels.size) {
                val name = String(buf, fields.from(nameAt), fields.length(nameAt), Charsets.UTF_8)
                names.add(if (hasId) name + "  " + String(buf, fields.from(idAt), fields.length(idAt), Charsets.UTF_8) else name)
                units.add(String(buf, fields.from(unitAt), fields.length(unitAt), Charsets.UTF_8))
                kept.add(Thinned(MAX_PER_CHANNEL))
                channels.keep()
            }
            kept[i].add(ms, value)
            readings++
        }
        require(names.isNotEmpty()) { "no readings in this file" }

        return Session(
            modified,
            names.mapIndexed { i, name -> Channel(name, units[i], kept[i].times(), kept[i].values()) },
            null,
            readings = readings,
        )
    }

    // ── the byte machinery ────────────────────────────────────────────────

    /**
     * Lines out of a stream, without making a String of any of them.
     *
     * The window slides: what has been consumed is dropped to the front and the
     * buffer only grows when a single line does not fit in it, which for this
     * format never happens.
     */
    private class Lines(private val input: java.io.InputStream) {
        var bytes = ByteArray(1 shl 16)
            private set
        private var filled = 0
        private var at = 0

        /** Start and end of the line [next] just found, end exclusive. */
        var from = 0
            private set
        var to = 0
            private set

        fun next(): Boolean {
            while (true) {
                var i = at
                while (i < filled && bytes[i] != NEWLINE) i++
                if (i < filled) {
                    from = at
                    to = if (i > at && bytes[i - 1] == RETURN) i - 1 else i
                    at = i + 1
                    return true
                }
                if (at > 0) {
                    System.arraycopy(bytes, at, bytes, 0, filled - at)
                    filled -= at
                    at = 0
                }
                if (filled == bytes.size) bytes = bytes.copyOf(bytes.size * 2)
                val n = input.read(bytes, filled, bytes.size - filled)
                if (n <= 0) {
                    if (at >= filled) return false
                    from = at
                    to = filled
                    at = filled
                    return true
                }
                filled += n
            }
        }
    }

    /**
     * Where the commas are. Just enough CSV: only the parameter name is ever
     * quoted, and the quotes are dropped from the field rather than copied out.
     */
    private class Fields {
        private var starts = IntArray(16)
        private var ends = IntArray(16)
        var count = 0
            private set

        fun from(i: Int) = starts[i]
        fun to(i: Int) = ends[i]
        fun length(i: Int) = ends[i] - starts[i]

        fun split(buf: ByteArray, from: Int, to: Int) {
            count = 0
            var start = from
            var quoted = false
            var i = from
            while (i <= to) {
                if (i == to || (buf[i] == COMMA && !quoted)) {
                    add(start, i, buf)
                    start = i + 1
                } else if (buf[i] == QUOTE) {
                    quoted = !quoted
                }
                i++
            }
        }

        private fun add(start: Int, end: Int, buf: ByteArray) {
            if (count == starts.size) {
                starts = starts.copyOf(starts.size * 2)
                ends = ends.copyOf(ends.size * 2)
            }
            var a = start
            var b = end
            if (b > a && buf[a] == QUOTE) a++
            if (b > a && buf[b - 1] == QUOTE) b--
            starts[count] = a
            ends[count] = b
            count++
        }
    }

    /**
     * Which channel a row belongs to, found from the raw bytes.
     *
     * A 64-bit FNV-1a over the name and the identifier picks a candidate, and
     * the candidate's stored bytes are compared before it is believed —
     * a hash collision here would not fail, it would silently merge two
     * parameters into one series, which is the kind of wrong that gets
     * plotted and believed.
     */
    private class Channels {
        private val byHash = HashMap<Long, Int>()
        private val keys = ArrayList<ByteArray>()
        private var pending: ByteArray = ByteArray(0)
        private var pendingHash = 0L

        val size: Int get() = keys.size

        /**
         * The channel's index, or [size] when it is new — in which case the
         * caller adds its arrays and calls [keep].
         */
        fun indexOf(buf: ByteArray, aFrom: Int, aTo: Int, bFrom: Int, bTo: Int): Int {
            var h = -0x340d631b7bdddcdbL          // FNV-1a 64 offset basis
            for (i in aFrom until aTo) {
                h = (h xor (buf[i].toLong() and 0xff)) * 0x100000001b3L
            }
            h = (h xor 0xffL) * 0x100000001b3L    // the two ranges cannot run together
            for (i in bFrom until bTo) {
                h = (h xor (buf[i].toLong() and 0xff)) * 0x100000001b3L
            }
            val key = ByteArray((aTo - aFrom) + (bTo - bFrom))
            System.arraycopy(buf, aFrom, key, 0, aTo - aFrom)
            System.arraycopy(buf, bFrom, key, aTo - aFrom, bTo - bFrom)

            val candidate = byHash[h]
            if (candidate != null && keys[candidate].contentEquals(key)) return candidate
            if (candidate != null) {
                // Collision. Rare enough never to have been seen, cheap enough
                // to handle honestly: look for it the slow way.
                for (i in keys.indices) if (keys[i].contentEquals(key)) return i
            }
            pending = key
            pendingHash = h
            return keys.size
        }

        /** Confirms the channel [indexOf] just reported as new. */
        fun keep() {
            byHash.putIfAbsent(pendingHash, keys.size)
            keys.add(pending)
        }
    }

    /** The timestamp. Negative means the field was not a number. */
    private fun parseInt(buf: ByteArray, from: Int, to: Int): Int {
        if (to <= from) return -1
        var v = 0L
        for (i in from until to) {
            val d = buf[i] - ZERO
            if (d < 0 || d > 9) return -1
            v = v * 10 + d
            if (v > Int.MAX_VALUE) return -1
        }
        return v.toInt()
    }

    /**
     * The value. NaN means the field was not a number, which is how a header
     * row or a torn last line gets skipped.
     *
     * Exact where it matters: a mantissa of at most eighteen digits scaled by a
     * power of ten that Java represents exactly is one correctly rounded
     * division. Anything longer or stranger — an exponent, a very long decimal
     * — is handed to the platform, which is slower and always right.
     */
    private fun parseDouble(buf: ByteArray, from: Int, to: Int): Double {
        if (to <= from) return Double.NaN
        var i = from
        var negative = false
        if (buf[i] == MINUS) { negative = true; i++ } else if (buf[i] == PLUS) i++
        var mantissa = 0L
        var digits = 0
        var decimals = -1
        while (i < to) {
            val c = buf[i]
            if (c == DOT) {
                if (decimals >= 0) return Double.NaN
                decimals = 0
            } else {
                val d = c - ZERO
                if (d < 0 || d > 9) return slowDouble(buf, from, to)
                if (digits < 18) {
                    mantissa = mantissa * 10 + d
                    digits++
                    if (decimals >= 0) decimals++
                } else if (decimals < 0) {
                    return slowDouble(buf, from, to)
                }
            }
            i++
        }
        if (digits == 0) return Double.NaN
        val scaled = when {
            decimals <= 0 -> mantissa.toDouble()
            decimals < POWERS.size -> mantissa.toDouble() / POWERS[decimals]
            else -> return slowDouble(buf, from, to)
        }
        return if (negative) -scaled else scaled
    }

    private fun slowDouble(buf: ByteArray, from: Int, to: Int): Double =
        String(buf, from, to - from, Charsets.UTF_8).toDoubleOrNull() ?: Double.NaN

    private const val NEWLINE = '\n'.code.toByte()
    private const val RETURN = '\r'.code.toByte()
    private const val COMMA = ','.code.toByte()
    private const val QUOTE = '"'.code.toByte()
    private const val MINUS = '-'.code.toByte()
    private const val PLUS = '+'.code.toByte()
    private const val DOT = '.'.code.toByte()
    private const val ZERO = '0'.code.toByte()

    /** Powers of ten that a double holds exactly. */
    private val POWERS = DoubleArray(23) { Math.pow(10.0, it.toDouble()) }

    /**
     * Growable primitive arrays.
     *
     * One per channel rather than one for the file, which is what keeps the
     * peak down: a channel of a long recording is a hundred and forty thousand
     * readings, so the doubling that growth costs is half a megabyte at a time
     * and not half the file.
     */
    /**
     * A channel's readings, thinned as they arrive so a channel never holds
     * more than [cap] of them.
     *
     * A recording used to be kept whole, and that stopped working at two
     * hours: 20 million readings across 33 channels, 12 bytes each and twice
     * that while an array doubles, which is past what Android gives an app and
     * from the outside is the app closing when a file is pressed. A chart on a
     * phone is a few hundred pixels wide; 50 000 points per channel is more
     * than a hundred per pixel at any zoom that fits the screen.
     *
     * Thinned by keeping the extreme, not by striding. Every [stride] readings
     * collapse into one: the reading furthest from the last one kept. Striding
     * would keep whichever reading fell on the stride and throw away a spike
     * between two of them, and a spike -- a boost peak, a rail dip, the limiter
     * -- is usually why the file is being opened. When the cap is reached, the
     * kept readings are paired and each pair collapses the same way, and the
     * stride doubles. Nothing here reads the file twice.
     */
    private class Thinned(private val cap: Int) {
        private var t = IntArray(256)
        private var v = DoubleArray(256)
        private var n = 0
        private var stride = 1
        private var inWindow = 0
        private var candT = 0
        private var candV = 0.0
        private var candDev = -1.0

        fun add(ms: Int, value: Double) {
            val ref = if (n > 0) v[n - 1] else value
            val dev = Math.abs(value - ref)
            if (dev > candDev) { candT = ms; candV = value; candDev = dev }
            if (++inWindow < stride) return
            push(candT, candV)
            inWindow = 0
            candDev = -1.0
        }

        private fun push(ms: Int, value: Double) {
            if (n == cap) compact()
            if (n == t.size) {
                val size = minOf(t.size * 2, cap)
                t = t.copyOf(size)
                v = v.copyOf(size)
            }
            t[n] = ms
            v[n] = value
            n++
        }

        private fun compact() {
            var w = 0
            var i = 0
            while (i < n) {
                val ref = if (w > 0) v[w - 1] else v[i]
                val j = if (i + 1 < n && Math.abs(v[i + 1] - ref) > Math.abs(v[i] - ref)) i + 1 else i
                t[w] = t[j]
                v[w] = v[j]
                w++
                i += 2
            }
            n = w
            stride *= 2
        }

        private fun flush() {
            if (inWindow > 0 && candDev >= 0) {
                push(candT, candV)
                inWindow = 0
                candDev = -1.0
            }
        }

        fun times(): IntArray { flush(); return t.copyOf(n) }
        fun values(): DoubleArray { flush(); return v.copyOf(n) }
    }

    /** See [Thinned]. */
    const val MAX_PER_CHANNEL = 50_000
}
