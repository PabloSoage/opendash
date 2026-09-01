package com.varuna.opendash.data

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads the `.sm2` recordings the Scanmatik Windows software writes.
 *
 * A proprietary format, worked out from the files themselves:
 *
 * ```
 * 0x000  "SMFS" and a version
 * 0x01d  Windows FILETIME, 100 ns since 1601 — when the recording started
 * 0x410  from here the file is a chain of 0x400-byte SECTORS. Each one opens
 *        with a nine-byte record, `01 <u32 previous> <u32 next>`, a linked
 *        list; the first has 0xFFFFFFFF for previous and the last for next.
 * ```
 *
 * Strip those nine bytes from every sector and what is left is one continuous
 * stream:
 *
 * ```
 * text header : <u32 marker=1><u32 characters><UTF-16>, one per parameter
 * the series  : pairs of <u32 milliseconds><double value>, cycling through
 *               the parameters in order and starting over
 * padding     : 0xff to the end of the last sector
 * ```
 *
 * ## Why the sectors matter
 *
 * The nine bytes are inserted wherever the sector boundary falls, with no
 * regard for what they land in: they cut a value pair in half, and they cut a
 * UTF-16 parameter name in half. An earlier version of this reader did not know
 * about them and resynchronised by scanning for the next place four pairs made
 * sense. On a 23-minute recording that meant 953 resynchronisations, three
 * parameter names lost to a marker landing inside them, and — worse — a value
 * stream whose position no longer matched its channel, so readings were charted
 * under the wrong name.
 *
 * Removing the markers first, the same file reads as 80 670 pairs with no
 * resynchronisation at all, and 80 670 is exactly 2689 × 30.
 *
 * ## How many channels
 *
 * From the file, not from the names. The header carries a summary line of the
 * form `30 of 30 items 23:13.740`: the second number is how many parameters
 * were recorded, the first is how many the Scanmatik window happened to be
 * showing when it was saved. Counting the visible ones is what made a recording
 * filtered down to three look like a three-channel recording, and spread thirty
 * channels of readings across three names.
 */
object Sm2Reader {

    private const val FIRST_MARKER = 0x410
    private const val SECTOR = 0x400
    private const val MARKER = 9

    class Recording(
        val startedAt: Long,
        /** How many parameters were recorded. */
        val channels: Int,
        /** How many the recording window was showing when it was saved. */
        val visible: Int,
        /** One per channel; short of [channels] only if a name went missing. */
        val parameters: List<String>,
        /** milliseconds, channel index, value */
        val samples: List<Triple<Int, Int, Double>>,
    ) {
        val durationMs: Int
            get() = if (samples.isEmpty()) 0 else samples.last().first - samples.first().first
    }

    fun read(bytes: ByteArray): Recording {
        require(bytes.size > FIRST_MARKER && String(bytes, 0, 4, Charsets.US_ASCII) == "SMFS") {
            "not a .sm2 file: the SMFS signature is missing"
        }
        val filetime = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getLong(0x1d)
        val startedAt = filetime / 10_000 - 11_644_473_600_000L

        val stream = deSector(bytes)
        val buffer = ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN)

        // The tail of the last sector is 0xff padding, not readings.
        var end = stream.size
        while (end > 0 && stream[end - 1] == 0xff.toByte()) end--

        val texts = texts(stream, buffer)
        val summary = summary(stream, buffer)
        val names = texts.map { it.text }
            .filter { it.length >= 5 && !SUMMARY.containsMatchIn(it) }
        val channels = (summary?.channels ?: names.size).coerceAtLeast(1)

        val from = texts.lastOrNull()?.end ?: FIRST_MARKER
        val samples = series(buffer, from, end, channels)

        return Recording(
            startedAt = startedAt,
            channels = channels,
            visible = summary?.visible ?: names.size,
            parameters = names,
            samples = samples,
        )
    }

    // ── sectors ───────────────────────────────────────────────────────────

    private fun deSector(b: ByteArray): ByteArray {
        val out = ByteArray(b.size)
        var written = minOf(FIRST_MARKER, b.size)
        System.arraycopy(b, 0, out, 0, written)
        var o = FIRST_MARKER
        while (o < b.size) {
            val start = minOf(o + MARKER, b.size)
            val stop = minOf(o + SECTOR, b.size)
            if (stop > start) {
                System.arraycopy(b, start, out, written, stop - start)
                written += stop - start
            }
            o += SECTOR
        }
        return out.copyOf(written)
    }

    // ── header ────────────────────────────────────────────────────────────

    private class Text(val end: Int, val text: String)

    private val SUMMARY = Regex("""^(\d+) of (\d+) items""")

    private fun printable(c: Int) = (c in 32..0x2122) || c == 10 || c == 13

    /**
     * The marked text records, in order. Each is a 1, a character count and
     * that many UTF-16 code units.
     */
    private fun texts(b: ByteArray, buffer: ByteBuffer): List<Text> {
        val out = ArrayList<Text>()
        var o = FIRST_MARKER
        val limit = minOf(b.size, 0x8000)
        while (o + 8 < limit) {
            if (buffer.getInt(o) != 1) {
                o++
                continue
            }
            val n = buffer.getInt(o + 4)
            if (n < 1 || n > 200 || o + 8 + n * 2 > b.size) {
                o++
                continue
            }
            var ok = true
            for (k in 0 until n) {
                if (!printable(buffer.getShort(o + 8 + k * 2).toInt() and 0xffff)) {
                    ok = false
                    break
                }
            }
            if (!ok) {
                o++
                continue
            }
            val text = String(b, o + 8, n * 2, Charsets.UTF_16LE)
                .replace(Regex("[\r\n]+"), " ")
                .trim()
            out.add(Text(o + 8 + n * 2, text))
            o += 8 + n * 2
        }
        return out
    }

    class Summary(val visible: Int, val channels: Int)

    /**
     * `N of M items …`. Unlike the parameter names this one carries no marker
     * in front of it, so it is found by its own text.
     */
    private fun summary(b: ByteArray, buffer: ByteBuffer): Summary? {
        val limit = minOf(b.size, 0x8000)
        var p = FIRST_MARKER
        while (p + 8 < limit) {
            // " of " in UTF-16LE
            if (buffer.getShort(p).toInt() == ' '.code &&
                buffer.getShort(p + 2).toInt() == 'o'.code &&
                buffer.getShort(p + 4).toInt() == 'f'.code &&
                buffer.getShort(p + 6).toInt() == ' '.code
            ) {
                var start = p
                while (start >= 2 && printable(buffer.getShort(start - 2).toInt() and 0xffff)) start -= 2
                var stop = p
                while (stop + 2 <= b.size && printable(buffer.getShort(stop).toInt() and 0xffff)) stop += 2
                val text = String(b, start, stop - start, Charsets.UTF_16LE).trim()
                SUMMARY.find(text)?.let { m ->
                    return Summary(m.groupValues[1].toInt(), m.groupValues[2].toInt())
                }
            }
            p += 2
        }
        return null
    }

    // ── the series ────────────────────────────────────────────────────────

    /**
     * Straight through. With the markers gone there is nothing left to
     * resynchronise around, and validating here is what used to shift every
     * following reading onto the wrong channel: one rejected pair moves the
     * whole rotation by one.
     *
     * The start is found by looking for the first place eight pairs in a row
     * make sense, because between the last name and the first reading there is
     * a run of filler.
     */
    private fun series(
        buffer: ByteBuffer,
        from: Int,
        end: Int,
        channels: Int,
    ): List<Triple<Int, Int, Double>> {
        fun plausible(o: Int, previous: Int): Boolean {
            if (o + 12 > end) return false
            val t = buffer.getInt(o)
            val v = buffer.getDouble(o + 4)
            return t >= previous && t - previous < 30_000 && !v.isNaN() && !v.isInfinite() &&
                kotlin.math.abs(v) < 1e7 && (v == 0.0 || kotlin.math.abs(v) > 1e-9)
        }

        var o = from
        while (o + 96 < end) {
            var t = 0
            var ok = true
            for (k in 0 until 8) {
                if (!plausible(o + k * 12, t)) {
                    ok = false
                    break
                }
                t = buffer.getInt(o + k * 12)
            }
            if (ok) break
            o++
        }

        val out = ArrayList<Triple<Int, Int, Double>>((end - o) / 12 + 1)
        var index = 0
        while (o + 12 <= end) {
            out.add(Triple(buffer.getInt(o), index % channels, buffer.getDouble(o + 4)))
            index++
            o += 12
        }
        return out
    }
}
