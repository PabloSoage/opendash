package com.varuna.opendash.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket

class TelemetryTest {

    @Test
    fun numbersAreJsonWhateverTheLocale() {
        val before = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("1650", TelemetryProtocol.number(1650.0))
            assertEquals("0.25", TelemetryProtocol.number(0.25))
            assertEquals("-40", TelemetryProtocol.number(-40.0))
            assertEquals("14.1", TelemetryProtocol.number(14.1))
            assertEquals("null", TelemetryProtocol.number(Double.NaN))
        } finally {
            java.util.Locale.setDefault(before)
        }
    }

    @Test
    fun sampleLine() {
        assertEquals(
            "{\"t\":1000,\"n\":\"Engine \\\"Speed\\\"\",\"id\":\"0x000C\",\"u\":\"RPM\",\"v\":812.5}",
            TelemetryProtocol.sample(1000, "Engine \"Speed\"", "0x000C", "RPM", 812.5),
        )
    }

    @Test
    fun commands() {
        val sel = TelemetryProtocol.parse(
            "{\"select\":[\"cat:a\",\"0x000C\"],\"always\":[\"0x000D\"],\"start\":true}",
        ) as Telemetry.Command.Select
        assertEquals(listOf("cat:a", "0x000C"), sel.keys)
        assertEquals(listOf("0x000D"), sel.always)
        assertTrue(sel.start)

        val p = TelemetryProtocol.parse("{ \"preset\" : \"Astra J \\u0041\" }") as Telemetry.Command.Preset
        assertEquals("Astra J A", p.name)
        assertEquals(false, p.start)

        assertTrue(TelemetryProtocol.parse("{\"stop\":true}") is Telemetry.Command.Stop)
        assertTrue(TelemetryProtocol.parse("{\"start\":true}") is Telemetry.Command.Start)

        // Refused rather than half-understood.
        assertNull(TelemetryProtocol.parse("{\"select\":[1,2]}"))
        assertNull(TelemetryProtocol.parse("{\"select\":[\"a\"]"))
        assertNull(TelemetryProtocol.parse("{}"))
        assertNull(TelemetryProtocol.parse("select a"))
        assertNull(TelemetryProtocol.parse("{\"select\":[\"a\"]} trailing"))
    }

    @Test
    fun serverGreetsBroadcastsAndAnswers() {
        val got = mutableListOf<String>()
        val server = TelemetryServer(0, "127.0.0.1") { line ->
            synchronized(got) { got.add(line) }
            TelemetryProtocol.ack(true, "")
        }
        server.greeting = { listOf(TelemetryProtocol.hello(), TelemetryProtocol.state(false, emptyList(), 0)) }
        server.start()
        try {
            Socket("127.0.0.1", server.port).use { s ->
                s.soTimeout = 3000
                val r = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                assertTrue(r.readLine().contains(TelemetryProtocol.NAME))
                assertTrue(r.readLine().startsWith("{\"type\":\"state\""))

                val deadline = System.currentTimeMillis() + 3000
                while (server.clients == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
                assertEquals(1, server.clients)

                for (i in 0 until 100) {
                    server.broadcast(TelemetryProtocol.sample(i.toLong(), "x", "0x0001", "", i.toDouble()))
                }
                for (i in 0 until 100) assertTrue(r.readLine().endsWith("\"v\":$i}"))

                s.getOutputStream().write("{\"stop\":true}\n".toByteArray())
                s.getOutputStream().flush()
                assertEquals("{\"type\":\"ack\",\"ok\":true}", r.readLine())
                synchronized(got) { assertEquals(listOf("{\"stop\":true}"), got) }
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun aSlowClientLosesTheOldestLinesNotTheReader() {
        val server = TelemetryServer(0, "127.0.0.1") { TelemetryProtocol.ack(true, "") }
        server.start()
        try {
            Socket("127.0.0.1", server.port).use { s ->
                s.receiveBufferSize = 4096
                val deadline = System.currentTimeMillis() + 3000
                while (server.clients == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
                // Nothing is read on this side, so the socket fills and then the
                // queue; broadcasting must still return at once every time.
                val t0 = System.nanoTime()
                val line = TelemetryProtocol.sample(0, "y".repeat(200), "0x0002", "", 1.0)
                for (i in 0 until 200_000) server.broadcast(line)
                val ms = (System.nanoTime() - t0) / 1e6
                assertTrue("broadcast took $ms ms", ms < 5_000)
                assertTrue(server.dropped > 0)
            }
        } finally {
            server.stop()
        }
    }
}
