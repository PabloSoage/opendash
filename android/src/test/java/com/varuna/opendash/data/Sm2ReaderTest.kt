package com.varuna.opendash.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * A `.sm2` is not always a recording.
 *
 * The Windows software saves screens that hold no series at all — Monitor
 * Status is the one that turned up — under the same `SMFS` signature and the
 * same sectors. Read as a recording, such a file does not fail: it produces no
 * channels, a nonsense sample count, hours of duration, and a screenful of
 * numbers that mean nothing. That is what makes it worth a test.
 *
 * The case that actually cost time is [a record split across a sector seam].
 * The nine bytes that open each sector are inserted wherever the boundary
 * falls, with no regard for what they land in — in the real file they landed
 * between the low and the high byte of one letter of "Comprehensive
 * components", and a reader that did not strip them first lost that one cell
 * and only that one. Forty-seven of forty-eight looks like a complete result.
 */
class Sm2ReaderTest {

    private val SECTOR = 0x400
    private val FIRST = 0x410
    private val MARK = 9

    /** A text record: a marker, a character count, then UTF-16LE. */
    private fun record(marker: Int, text: String): ByteArray {
        val out = ByteArrayOutputStream()
        fun u32(v: Int) {
            out.write(v and 0xff)
            out.write((v shr 8) and 0xff)
            out.write((v shr 16) and 0xff)
            out.write((v shr 24) and 0xff)
        }
        u32(marker)
        u32(text.length)
        out.write(text.toByteArray(Charsets.UTF_16LE))
        return out.toByteArray()
    }

    /**
     * Wraps a payload the way the format does: a header, then sectors of
     * 0x400 with nine bytes of linked list at the front of each, and 0xff to
     * the end.
     */
    private fun file(payload: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        var at = 0
        while (at < payload.size) {
            body.write(byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 0))
            val take = minOf(SECTOR - MARK, payload.size - at)
            body.write(payload, at, take)
            at += take
            repeat(SECTOR - MARK - take) { body.write(0xff) }
        }
        val out = ByteArray(FIRST + body.size()) { 0xff.toByte() }
        "SMFS".toByteArray(Charsets.US_ASCII).copyInto(out, 0)
        // A FILETIME that decodes to something after 1970.
        val filetime = (1_700_000_000_000L + 11_644_473_600_000L) * 10_000
        for (i in 0 until 8) out[0x1d + i] = ((filetime shr (i * 8)) and 0xff).toByte()
        body.toByteArray().copyInto(out, FIRST)
        return out
    }

    private val monitores = listOf(
        "On-board system / module, [since DTC clear], [this cycle]", "incomplete", "incomplete",
        "Malfunction Indicator Lamp (MIL)", "Off", "---",
        "# of DTCs stored in this ECU", "0", "---",
        "Continuous monitors", "0", "---",
        "Misfire", "not supp.", "not supp.",
        "Fuel system", "not supp.", "not supp.",
        "Comprehensive components", "incomplete", "not supp.",
        "Non-continuous monitors", "incomplete", "not supp.",
        "Catalyst", "not supp.", "not supp.",
        "Heated catalyst", "not supp.", "not supp.",
        "Evaporative system", "not supp.", "not supp.",
        "Secondary air system", "not supp.", "not supp.",
        "A/C system refrigerant", "not supp.", "not supp.",
        "Oxygen sensor", "not supp.", "not supp.",
        "Oxygen sensor heater", "not supp.", "not supp.",
        "EGR system", "incomplete", "incomplete",
    )

    private fun screenFile(): ByteArray {
        val payload = ByteArrayOutputStream()
        for (cell in monitores) payload.write(record(0, cell))
        return file(payload.toByteArray())
    }

    @Test
    fun `a saved screen is not read as a recording`() {
        val session = SessionFile.read(screenFile())
        assertTrue("a screen must have no channels", session.channels.isEmpty())
        assertEquals(monitores.size, session.cells.size)
        assertEquals(monitores, session.cells)
    }

    /**
     * The one that matters. The payload here is long enough that a record lands
     * across a sector boundary, and it is verified to do so — a test that
     * believes it covers the seam without checking is worth nothing.
     */
    @Test
    fun `a record split across a sector seam survives`() {
        val bytes = screenFile()
        assertTrue("the fixture must span more than one sector", bytes.size > FIRST + SECTOR)

        // Somewhere a cell has to straddle the boundary, or this proves nothing.
        val payload = ByteArrayOutputStream()
        for (cell in monitores) payload.write(record(0, cell))
        var at = 0
        var straddles = false
        for (cell in monitores) {
            val size = 8 + cell.length * 2
            if (at / (SECTOR - MARK) != (at + size - 1) / (SECTOR - MARK)) straddles = true
            at += size
        }
        assertTrue("no record crosses a sector in this fixture", straddles)

        assertEquals(monitores, SessionFile.read(bytes).cells)
    }

    /** And a real recording must still read as one. */
    @Test
    fun `a recording is still a recording`() {
        val payload = ByteArrayOutputStream()
        // Marker 1 is what a parameter name carries.
        payload.write(record(1, "Engine Speed"))
        payload.write(record(1, "Vehicle Speed"))
        // Then pairs of <u32 ms><double>, cycling through the two.
        val pairs = ByteArrayOutputStream()
        for (t in 0 until 20) {
            val ms = t * 100
            val bits = java.lang.Double.doubleToLongBits(t.toDouble())
            for (i in 0 until 4) pairs.write(((ms shr (i * 8)) and 0xff))
            for (i in 0 until 8) pairs.write(((bits shr (i * 8)) and 0xff).toInt())
        }
        payload.write(pairs.toByteArray())

        val session = SessionFile.read(file(payload.toByteArray()))
        assertTrue("a recording must not come back as a screen", session.cells.isEmpty())
        assertTrue("a recording must have channels", session.channels.isNotEmpty())
    }
}
