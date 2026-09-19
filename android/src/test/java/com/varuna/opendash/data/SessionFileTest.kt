package com.varuna.opendash.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * The recording reader, on the host.
 *
 * It parses bytes by hand now — sliding window, fields by offset, numbers
 * straight out of the ASCII — because a recording is millions of rows and a
 * `BufferedReader` plus `split` is about ten allocations each. Hand-written
 * parsing is exactly the kind of thing that works on the file in front of you
 * and then quietly drops the last row of somebody else's, so the shapes that
 * have actually turned up are pinned here.
 */
class SessionFileTest {

    private fun read(text: String) = SessionFile.read(text.toByteArray(Charsets.UTF_8))

    private fun gzipped(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    @Test
    fun `five columns, quoted names, identifier kept`() {
        val session = read(
            """
            ms,parameter,identifier,unit,value
            813,"MAF Sensor",0x0010,g/s,0.73
            814,"Engine Speed",0x000C,RPM,812.0
            900,"MAF Sensor",0x0010,g/s,1.25
            """.trimIndent(),
        )
        assertEquals(2, session.channels.size)
        val maf = session.channels[0]
        assertEquals("MAF Sensor  0x0010", maf.name)
        assertEquals("g/s", maf.unit)
        assertEquals(2, maf.size)
        assertEquals(813, maf.times[0])
        assertEquals(0.73, maf.values[0], 0.0)
        assertEquals(1.25, maf.values[1], 0.0)
        assertEquals(900, session.durationMs)
        assertEquals(3, session.samples)
    }

    /** The order on screen is the order they were recorded in. */
    @Test
    fun `channels keep the order they first appear in`() {
        val session = read(
            """
            ms,parameter,identifier,unit,value
            1,"Zulu",0x0003,%,1
            2,"Alpha",0x0001,%,2
            3,"Mike",0x0002,%,3
            """.trimIndent(),
        )
        assertEquals(listOf("Zulu  0x0003", "Alpha  0x0001", "Mike  0x0002"), session.channels.map { it.name })
    }

    /**
     * Two rows with the same name and different identifiers are two sensors,
     * and merged into one they read as a sensor flipping between two values.
     */
    @Test
    fun `namesakes with different identifiers stay apart`() {
        val session = read(
            """
            ms,parameter,identifier,unit,value
            1,"Exhaust Gas Temperature Sensor 1",0x303A,°C,376
            1,"Exhaust Gas Temperature Sensor 1",0x303B,°C,357
            """.trimIndent(),
        )
        assertEquals(2, session.channels.size)
        assertEquals(376.0, session.channels[0].values[0], 0.0)
        assertEquals(357.0, session.channels[1].values[0], 0.0)
    }

    /** The four-column files written before the identifier column existed. */
    @Test
    fun `the older four column shape still opens`() {
        val session = read(
            """
            ms,parameter,unit,value
            10,"Engine coolant temperature",°C,88
            20,"Engine coolant temperature",°C,89
            """.trimIndent(),
        )
        assertEquals(1, session.channels.size)
        assertEquals("Engine coolant temperature", session.channels[0].name)
        assertEquals("°C", session.channels[0].unit)
        assertEquals(89.0, session.channels[0].values[1], 0.0)
    }

    @Test
    fun `gzip is detected by its signature, not by a file name`() {
        val session = SessionFile.read(
            gzipped(
                "ms,parameter,identifier,unit,value\n" +
                    "5,\"Vehicle Speed\",0x000D,km/h,91\n",
            ),
        )
        assertEquals(1, session.channels.size)
        assertEquals(91.0, session.channels[0].values[0], 0.0)
    }

    /**
     * Negative numbers, long decimals and exponents all turn up: torque goes
     * negative on the overrun, and the manufacturer's own export writes
     * `29.41176470588235`.
     */
    @Test
    fun `the numbers survive`() {
        val session = read(
            """
            ms,parameter,identifier,unit,value
            1,"Torque",0x1A2D,N·m,-28
            2,"Torque",0x1A2D,N·m,29.41176470588235
            3,"Torque",0x1A2D,N·m,0.0
            4,"Torque",0x1A2D,N·m,1.5e2
            5,"Torque",0x1A2D,N·m,+7.25
            """.trimIndent(),
        )
        val v = session.channels[0].values
        assertEquals(-28.0, v[0], 0.0)
        assertEquals(29.41176470588235, v[1], 0.0)
        assertEquals(0.0, v[2], 0.0)
        assertEquals(150.0, v[3], 0.0)
        assertEquals(7.25, v[4], 0.0)
    }

    /**
     * A recording that was cut — the link went away, the phone was locked —
     * ends mid-row. Everything before the tear has to survive.
     */
    @Test
    fun `a torn last row costs only itself`() {
        val session = read(
            "ms,parameter,identifier,unit,value\n" +
                "1,\"Boost\",0x2002,kPa,101\n" +
                "2,\"Boost\",0x2002,kPa,253\n" +
                "3,\"Boost\",0x20",
        )
        assertEquals(1, session.channels.size)
        assertEquals(2, session.channels[0].size)
        assertEquals(253.0, session.channels[0].values[1], 0.0)
    }

    @Test
    fun `carriage returns and blank lines are not rows`() {
        val session = read(
            "ms,parameter,identifier,unit,value\r\n" +
                "1,\"Boost\",0x2002,kPa,101\r\n" +
                "\r\n" +
                "2,\"Boost\",0x2002,kPa,102\r\n",
        )
        assertEquals(2, session.channels[0].size)
        assertEquals(102.0, session.channels[0].values[1], 0.0)
        assertEquals("kPa", session.channels[0].unit)
    }

    /** A row whose value is not a number is skipped, not fatal. */
    @Test
    fun `rows that are not readings are skipped`() {
        val session = read(
            """
            ms,parameter,identifier,unit,value
            1,"Boost",0x2002,kPa,101
            2,"Boost",0x2002,kPa,
            3,"Boost",0x2002,kPa,n/a
            4,"Boost",0x2002,kPa,102
            """.trimIndent(),
        )
        assertEquals(2, session.channels[0].size)
    }

    @Test
    fun `a file with no readings says so`() {
        val e = runCatching { read("ms,parameter,identifier,unit,value\n") }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
    }

    /**
     * The window slides, and the buffer starts at 64 KB. A recording is far
     * bigger than that, so the case that matters is a row straddling the
     * refill boundary — which only shows up above the buffer size.
     */
    @Test
    fun `rows straddling the buffer boundary are not lost`() {
        val rows = StringBuilder("ms,parameter,identifier,unit,value\n")
        val n = 40_000
        for (i in 1..n) rows.append(i).append(",\"Engine Speed\",0x000C,RPM,").append(i * 2).append('\n')
        val session = SessionFile.read(gzipped(rows.toString()))
        assertEquals(1, session.channels.size)
        assertEquals(n, session.channels[0].size)
        assertEquals(n, session.channels[0].times[n - 1])
        assertEquals((n * 2).toDouble(), session.channels[0].values[n - 1], 0.0)
    }

    /** Many channels, so the lookup is exercised past its first bucket. */
    @Test
    fun `many channels interleaved all come back whole`() {
        val rows = StringBuilder("ms,parameter,identifier,unit,value\n")
        val channels = 40
        val each = 500
        for (t in 0 until each) {
            for (c in 0 until channels) {
                rows.append(t).append(",\"Parameter ").append(c).append("\",0x")
                    .append(String.format("%04X", 0x3000 + c)).append(",%,").append(c * 1000 + t)
                    .append('\n')
            }
        }
        val session = SessionFile.read(gzipped(rows.toString()))
        assertEquals(channels, session.channels.size)
        for (c in 0 until channels) {
            assertEquals(each, session.channels[c].size)
            assertEquals((c * 1000).toDouble(), session.channels[c].values[0], 0.0)
        }
        assertEquals(channels * each, session.samples)
    }
}
