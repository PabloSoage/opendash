package com.varuna.opendash.bridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ElmSessionTest {

    /** Answers from a table, and remembers what a raw exchange was asked. */
    private class FakeBridge(
        val answers: Map<String, ByteArray> = emptyMap(),
        val rawFrames: List<Pair<Int, ByteArray>> = emptyList(),
        val refuse: Boolean = false,
    ) : Bridge {
        override val batteryMillivolts = 12600
        var rawHeader = -1
        var rawFrame = ByteArray(0)
        var accepts: (Int) -> Boolean = { false }
        var flowControl: (Int) -> Pair<Int, ByteArray>? = { null }
        var silenceMs = -1L

        override fun request(header: Int, payload: ByteArray, timeoutMs: Long): Pair<Int, ByteArray>? {
            if (refuse) throw IllegalArgumentException("refusing")
            val key = payload.joinToString("") { "%02X".format(it) }
            return answers[key]?.let { 0x7E8 to it }
        }

        override fun raw(
            header: Int,
            frame: ByteArray,
            accepts: (Int) -> Boolean,
            flowControl: (Int) -> Pair<Int, ByteArray>?,
            silenceMs: Long,
            timeoutMs: Long,
        ): List<Pair<Int, ByteArray>> {
            if (refuse) throw IllegalArgumentException("refusing")
            rawHeader = header
            rawFrame = frame
            this.accepts = accepts
            this.flowControl = flowControl
            this.silenceMs = silenceMs
            return rawFrames.filter { accepts(it.first) }
        }
    }

    private fun bytes(hex: String) =
        hex.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun session(bridge: Bridge) = ElmSession(bridge).also { it.handle("ATE0") }

    @Test
    fun bypassInitialisationIsAccepted() {
        // ScanMyOpel CAN hung up on "?" here, every time.
        assertEquals("OK", session(FakeBridge()).handle("AT BI"))
    }

    @Test
    fun headersOffPrintsTheMessageWithoutProtocolByteOrPadding() {
        val s = session(FakeBridge(mapOf("0100" to bytes("41 00 98 3B 20 13"))))
        assertEquals("41 00 98 3B 20 13", s.handle("0100"))
        s.handle("ATS0")
        assertEquals("4100983B2013", s.handle("0100"))
    }

    @Test
    fun headersOnPrintsTheFrames() {
        val s = session(FakeBridge(mapOf("0100" to bytes("41 00 98 3B 20 13"))))
        s.handle("ATH1")
        assertEquals("7E8 06 41 00 98 3B 20 13 00", s.handle("0100"))
    }

    @Test
    fun aLongAnswerWithHeadersOffIsLengthThenNumberedLines() {
        // 20 bytes: a mode 09 answer shape, with a made-up identifier.
        val answer = bytes("49 02 01") + "TESTVIN0123456789".toByteArray()
        val s = session(FakeBridge(mapOf("0902" to answer)))
        assertEquals(
            "014\r0: 49 02 01 54 45 53\r1: 54 56 49 4E 30 31 32\r2: 33 34 35 36 37 38 39",
            s.handle("0902"),
        )
        s.handle("ATH1")
        assertEquals(
            "7E8 10 14 49 02 01 54 45 53\r7E8 21 54 56 49 4E 30 31 32\r7E8 22 33 34 35 36 37 38 39",
            s.handle("0902"),
        )
    }

    @Test
    fun aRefusedServiceIsNoDataAndTheSessionGoesOn() {
        val s = session(FakeBridge(refuse = true))
        assertEquals("NO DATA", s.handle("AE 1C 80"))
        assertEquals("OK", s.handle("ATH1"))
        s.handle("ATCAF0")
        assertEquals("NO DATA", s.handle("04 AE 1C 80 00"))
    }

    @Test
    fun scanMyOpelReadsStoredCodesFromFramesOnTheEmittedAddress() {
        // What ScanMyOpel CAN sent on 16 October, and the frames the emulated
        // engine answered with (codes on 0x5E8, end marker last).
        val bridge = FakeBridge(
            rawFrames = listOf(
                0x5E8 to bytes("81 24 55 00 7B 00 00 00"),
                0x641 to bytes("02 7E 00 00 00 00 00 00"),
                0x5E8 to bytes("81 00 00 00 FF 00 00 00"),
            ),
        )
        val s = session(bridge)
        for (c in listOf(
            "ATL0", "ATH1", "ATAL", "AT SP6", "AT SH 7E0", "AT CM 5FF", "AT CF 5E8",
            "AT FC SH 7E0", "AT FC SD 30 00 00", "AT FC SM 1", "AT BI", "ATST64", "ATAT0", "AT CAF0",
        )) assertEquals(c, "OK", s.handle(c))

        assertEquals("5E8 81 24 55 00 7B 00 00 00\r5E8 81 00 00 00 FF 00 00 00", s.handle("03 A9 81 12"))
        assertEquals(0x7E0, bridge.rawHeader)
        assertArrayEquals(bytes("03 A9 81 12"), bridge.rawFrame)
        // Mask 5FF over filter 5E8 lets the answer address through too.
        assertTrue(bridge.accepts(0x5E8))
        assertTrue(bridge.accepts(0x7E8))
        assertFalse(bridge.accepts(0x641))
        assertEquals(400L, bridge.silenceMs)
        val (to, data) = bridge.flowControl(0x7E8)!!
        assertEquals(0x7E0, to)
        assertArrayEquals(bytes("30 00 00"), data)
    }

    @Test
    fun receiveAddressTakesWildcards() {
        val bridge = FakeBridge(rawFrames = listOf(0x7E8 to bytes("01 60 00 00 00 00 00 00")))
        val s = session(bridge)
        s.handle("ATCAF0")
        assertEquals("OK", s.handle("ATCRA 7EX"))
        assertEquals("01 60 00 00 00 00 00 00", s.handle("01 20"))
        assertTrue(bridge.accepts(0x7E8))
        assertTrue(bridge.accepts(0x7EF))
        assertFalse(bridge.accepts(0x5E8))
    }

    @Test
    fun flowControlCanBeLeftToTheClient() {
        val bridge = FakeBridge(rawFrames = listOf(0x7E8 to bytes("10 14 49 02 01 54 45 53")))
        val s = session(bridge)
        s.handle("ATCAF0")
        s.handle("ATCFC0")
        s.handle("02 09 02")
        assertEquals(null, bridge.flowControl(0x7E8))
    }

    @Test
    fun resetPutsAutoFormattingBack() {
        val s = session(FakeBridge(mapOf("0100" to bytes("41 00 98 3B 20 13"))))
        s.handle("ATCAF0")
        assertEquals("ELM327 v1.5", s.handle("ATZ").substringAfterLast("\r"))
        s.handle("ATE0")
        assertEquals("41 00 98 3B 20 13", s.handle("0100"))
    }
}
