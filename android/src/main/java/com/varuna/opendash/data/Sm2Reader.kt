package com.varuna.opendash.data

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads the .sm2 recordings the Scanmatik Windows software writes.
 *
 * A proprietary format, worked out from the files themselves:
 *
 * ```
 * 0x00   "SMFS" and a version
 * 0x1d   Windows FILETIME, 100 ns since 1601 — when the recording started
 * 0x410  header: <u32 marker=1><u32 characters><UTF-16>, the title, the
 *        summary, and every parameter name and unit
 * ...    the series: pairs of <u32 milliseconds><double value>, cycling
 *        through the parameters in order and starting over
 * ```
 *
 * Between blocks there are control records that are not pairs, so a run that
 * stops making sense is resynchronised by looking for the next place where
 * four consecutive pairs do.
 *
 * Checked against a 23-minute drive: the series comes out 23:13.7 long and the
 * header says "23:13.740".
 */
object Sm2Reader {

    class Recording(
        val startedAt: Long,
        val parameters: List<String>,
        /** milliseconds, parameter index, value */
        val samples: List<Triple<Int, Int, Double>>,
    ) {
        val durationMs: Int get() = if (samples.isEmpty()) 0 else samples.last().first - samples.first().first
        fun seriesOf(index: Int): List<Pair<Int, Double>> =
            samples.filter { it.second == index }.map { it.first to it.third }
    }

    fun read(file: File): Recording = read(file.readBytes())

    fun read(b: ByteArray): Recording {
        require(b.size > 0x420 && String(b, 0, 4, Charsets.US_ASCII) == "SMFS") {
            "not a .sm2 file: the SMFS signature is missing"
        }
        val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)

        val filetime = buf.getLong(0x1d)
        val startedAt = filetime / 10_000 - 11_644_473_600_000L

        val (names, headerEnd) = header(b)
        val samples = series(buf, b.size, headerEnd, maxOf(names.size, 1))
        return Recording(startedAt, names, samples)
    }

    // ── header ────────────────────────────────────────────────────────────

    private fun printable(c: Int) = (c in 32..0x2122) || c == 10 || c == 13

    private fun header(b: ByteArray): Pair<List<String>, Int> {
        val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val names = ArrayList<String>()
        var o = 0x410
        var end = 0x1140
        while (o + 8 < minOf(b.size, 0x4000)) {
            if (buf.getInt(o) != 1) {
                o++
                continue
            }
            val n = buf.getInt(o + 4)
            if (n < 1 || n > 120 || o + 8 + n * 2 > b.size) {
                o++
                continue
            }
            var ok = true
            for (k in 0 until n) {
                if (!printable(buf.getShort(o + 8 + k * 2).toInt() and 0xffff)) {
                    ok = false
                    break
                }
            }
            if (!ok) {
                o++
                continue
            }
            val text = String(b, o + 8, n * 2, Charsets.UTF_16LE).replace(Regex("[\r\n]+"), " ")
            if (text.length >= 5 && !Regex("^\\d+ of \\d+").containsMatchIn(text)) names.add(text)
            o += 8 + n * 2
            end = o
        }
        return names to end
    }

    // ── the series ────────────────────────────────────────────────────────

    private fun series(
        buf: ByteBuffer,
        size: Int,
        from: Int,
        parameterCount: Int,
    ): List<Triple<Int, Int, Double>> {
        fun fits(o: Int, previous: Int): Boolean {
            if (o + 12 > size) return false
            val t = buf.getInt(o)
            val v = buf.getDouble(o + 4)
            // Denormals are buffer noise that passes for a normal number.
            return t >= previous && t - previous in 0..30_000 && !v.isNaN() && !v.isInfinite() &&
                kotlin.math.abs(v) < 1e7 && (v == 0.0 || kotlin.math.abs(v) > 1e-6)
        }

        // Start already synchronised: the first place four pairs in a row fit.
        var o = from
        while (o + 48 < size) {
            var t = 0
            var good = true
            for (k in 0 until 4) {
                if (!fits(o + k * 12, t)) {
                    good = false
                    break
                }
                t = buf.getInt(o + k * 12)
            }
            if (good) break
            o++
        }

        val out = ArrayList<Triple<Int, Int, Double>>()
        var previous = 0
        var index = 0
        while (o + 12 <= size) {
            if (fits(o, previous)) {
                val t = buf.getInt(o)
                out.add(Triple(t, index % parameterCount, buf.getDouble(o + 4)))
                previous = t
                index++
                o += 12
                continue
            }
            // A control record: look for where the pairs pick up again.
            var p = o + 1
            var found = -1
            while (p + 48 <= size && p < o + 4096) {
                var t = previous
                var good = true
                for (k in 0 until 4) {
                    if (!fits(p + k * 12, t)) {
                        good = false
                        break
                    }
                    t = buf.getInt(p + k * 12)
                }
                if (good) {
                    found = p
                    break
                }
                p++
            }
            if (found < 0) break
            o = found
        }
        return out
    }
}
