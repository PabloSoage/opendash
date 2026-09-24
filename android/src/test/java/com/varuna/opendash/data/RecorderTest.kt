package com.varuna.opendash.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.zip.GZIPInputStream

class RecorderTest {

    private fun lines(bytes: ByteArray, compressed: Boolean): List<String> {
        val text = if (compressed) GZIPInputStream(bytes.inputStream()).readBytes() else bytes
        return String(text, Charsets.UTF_8).lines().filter { it.isNotEmpty() }
    }

    @Test
    fun everyRowReachesTheFileByTheTimeCloseReturns() {
        val out = ByteArrayOutputStream()
        val r = Recorder({ RecordingStore.Sink("a.csv.gz", out) }, compress = true)
        // More than one hand-over's worth, so the writer thread really runs.
        repeat(50_000) { r.add("Engine Speed", "0x000C", "RPM", it.toDouble()) }
        r.close()

        val rows = lines(out.toByteArray(), compressed = true)
        assertEquals("ms,parameter,identifier,unit,value", rows.first())
        assertEquals(50_000, rows.size - 1)
        // In order, and whole.
        assertTrue(rows[1].endsWith(",\"Engine Speed\",0x000C,RPM,0.0"))
        assertTrue(rows.last().endsWith(",49999.0"))
        assertEquals(50_000, r.rows)
        assertEquals(0, r.dropped)
    }

    @Test
    fun aRunThatRecordsNothingLeavesNoFile() {
        var opened = 0
        val r = Recorder({ opened++; RecordingStore.Sink("a.csv", ByteArrayOutputStream()) }, compress = false)
        r.close()
        assertEquals(0, opened)
    }

    /** A stream that takes [limit] bytes and then fails, like a provider that went away. */
    private class Breaks(private val limit: Int) : OutputStream() {
        val taken = ByteArrayOutputStream()
        override fun write(b: Int) {
            if (taken.size() >= limit) throw IOException("gone")
            taken.write(b)
        }
    }

    @Test
    fun aFailedWriteCarriesOnInANewFile() {
        val first = Breaks(limit = 200)
        val second = ByteArrayOutputStream()
        val sinks = ArrayDeque(listOf<OutputStream>(first, second))
        val r = Recorder({ RecordingStore.Sink("part", sinks.removeFirst()) }, compress = false)

        repeat(10) { r.add("A", "0x0001", "_", it.toDouble()) }
        // Let the first chunk reach the breaking file before the second is added.
        Thread.sleep(400)
        repeat(10) { r.add("B", "0x0002", "_", it.toDouble()) }
        r.close()

        assertNotNull(r.lastFailure)
        val carried = lines(second.toByteArray(), compressed = false)
        assertEquals("ms,parameter,identifier,unit,value", carried.first())
        assertTrue("the rest of the run went to the second file", carried.any { it.contains("\"B\"") })
    }
}
