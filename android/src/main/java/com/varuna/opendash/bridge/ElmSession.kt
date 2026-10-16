package com.varuna.opendash.bridge

import com.varuna.opendash.obd.Diagnostics
import java.util.Locale

/**
 * One conversation with a phone app.
 *
 * PROVISIONAL. This began as a Kotlin mirror of `core/src/elm327.rs` so the
 * socket could be exercised before the native library is bound, and it is now
 * ahead of it: raw mode, the receive filters, flow control and the headers-off
 * format exist only here, with the tests in `ElmSessionTest`. The core has to
 * catch up before the JNI bridge replaces this, or the drift goes the other
 * way.
 *
 * Everything that touches the wire — framing, padding, the `h4` fingerprint,
 * ISO-TP — stays in the core regardless. This only parses text.
 *
 * ## Answering in the shape an ELM327 answers
 *
 * Which shape depends on two settings, and a client may use any of the four:
 *
 *  - Headers on (`ATH1`): the CAN frames, one line each, with the id and the
 *    ISO-TP protocol byte, padded to eight bytes as they come off the bus.
 *    A client asking for the VIN this way gets three lines and looks for
 *    `10 14` on the first.
 *  - Headers off, the default: the message, not the frames. A single frame is
 *    printed without its protocol byte or padding (`41 00 98 3B 20 13`); a
 *    longer answer is its length on one line and numbered lines after it
 *    (`014`, `0: 49 02 …`, `1: …`). This used to print frames here too, and a
 *    client checking that the answer to `0100` begins with `41 00` decided
 *    there was no car.
 *  - `ATCAF0`: the client does its own ISO-TP. Its command is a whole frame,
 *    protocol byte included, and every frame that passes its receive filter
 *    (`ATCF`/`ATCM`, or `ATCRA`) comes back as it arrived until `ATST` passes
 *    with nothing. ScanMyOpel CAN works this way, and reads stored codes from
 *    frames on 0x5E8 that never appear in an answer on 0x7E8.
 *
 * Measured with ScanMyOpel CAN against a desk copy of this bridge, over an
 * emulated engine module, on 16 October: before these changes it hung
 * up on `ATBI` 46 times in a row; after them it identified the engine, read its
 * identification and listed eight stored codes.
 */
class ElmSession(private val bridge: Bridge) {

    private var echo = true
    private var headers = false
    private var spaces = true
    private var txHeader = 0x7DF
    private var protocol = 6
    private var automatic = true

    // Raw mode (`ATCAF0`) and what it needs.
    private var autoFormat = true
    private var filter: Int? = null
    private var mask = 0x7FF
    private var timeoutMs = DEFAULT_TIMEOUT_MS
    private var flowControlOn = true
    private var flowControlMode = 0
    private var flowControlHeader: Int? = null
    private var flowControlData = DEFAULT_FLOW_CONTROL

    fun handle(raw: String): String {
        val cmd = raw.filterNot { it.isWhitespace() }.uppercase(Locale.ROOT)
        if (cmd.isEmpty()) return ""
        val prefix = if (echo) "$raw\r" else ""
        return prefix + when {
            cmd.startsWith("AT") -> at(cmd.removePrefix("AT"))
            else -> obd(cmd)
        }
    }

    private fun at(rest: String): String = when {
        rest == "Z" || rest == "WS" || rest == "D" -> { reset(); "ELM327 v1.5" }
        rest == "I" -> "ELM327 v1.5"
        rest == "E0" -> { echo = false; "OK" }
        rest == "E1" -> { echo = true; "OK" }
        rest == "H0" -> { headers = false; "OK" }
        rest == "H1" -> { headers = true; "OK" }
        rest == "S0" -> { spaces = false; "OK" }
        rest == "S1" -> { spaces = true; "OK" }
        rest == "L0" || rest == "L1" -> "OK"
        rest == "RV" -> String.format(Locale.ROOT, "%.1fV", bridge.batteryMillivolts / 1000.0)
        rest == "DPN" -> (if (automatic) "A" else "") + protocol.toString(16).uppercase(Locale.ROOT)
        rest == "DP" -> (if (automatic) "AUTO, " else "") + protocolName(protocol)
        rest.startsWith("SP") -> {
            val asked = rest.removePrefix("SP").removePrefix("A").toIntOrNull(16) ?: 0
            automatic = asked == 0 || rest.removePrefix("SP").startsWith("A")
            protocol = if (asked == 0) 6 else asked
            "OK"
        }
        rest.startsWith("SH") -> {
            val v = rest.removePrefix("SH").toIntOrNull(16)
            if (v == null) "?" else { txHeader = v; "OK" }
        }
        // Bypass the protocol search. There is no search to bypass — the bus is
        // known — and ScanMyOpel CAN treats "?" here as the wrong adapter.
        rest == "BI" -> "OK"
        rest == "CAF0" -> { autoFormat = false; "OK" }
        rest == "CAF1" -> { autoFormat = true; "OK" }
        rest == "CFC0" -> { flowControlOn = false; "OK" }
        rest == "CFC1" -> { flowControlOn = true; "OK" }
        rest == "CRA" -> { filter = null; mask = 0x7FF; "OK" }
        rest.startsWith("CRA") -> receiveAddress(rest.removePrefix("CRA"))
        rest.startsWith("CF") -> rest.removePrefix("CF").toIntOrNull(16)
            ?.let { filter = it; "OK" } ?: "?"
        rest.startsWith("CM") -> rest.removePrefix("CM").toIntOrNull(16)
            ?.let { mask = it; "OK" } ?: "?"
        rest.startsWith("FCSH") -> rest.removePrefix("FCSH").toIntOrNull(16)
            ?.let { flowControlHeader = it; "OK" } ?: "?"
        rest.startsWith("FCSD") -> rest.removePrefix("FCSD").hexToBytesOrNull()
            ?.takeIf { it.size in 1..5 }?.let { flowControlData = it; "OK" } ?: "?"
        rest.startsWith("FCSM") -> rest.removePrefix("FCSM").toIntOrNull()
            ?.takeIf { it in 0..2 }?.let { flowControlMode = it; "OK" } ?: "?"
        // A timeout in steps of 4 ms, 0 meaning the default.
        rest.startsWith("ST") -> rest.removePrefix("ST").toIntOrNull(16)?.let {
            timeoutMs = if (it == 0) DEFAULT_TIMEOUT_MS else maxOf(it * 4L, MIN_TIMEOUT_MS)
            "OK"
        } ?: "?"
        // Settings this bridge has no dial for, acknowledged rather than
        // refused. A client that sets something and gets "?" back can decide
        // the adapter is broken and stop, and every one of these is a request
        // to behave in a way this bridge already behaves.
        rest.startsWith("AT") || rest.startsWith("CP") || rest.startsWith("TP") ||
            rest == "AL" || rest == "NL" || rest == "PC" || rest == "M0" || rest == "M1" ||
            rest == "R0" || rest == "R1" || rest == "CSM0" || rest == "CSM1" -> "OK"
        else -> "?"
    }

    /** `ATCRA hhh`, where an `X` matches any digit. */
    private fun receiveAddress(digits: String): String {
        if (digits.isEmpty() || digits.length > 3) return "?"
        var value = 0
        var care = 0
        for (c in digits.padStart(3, '0')) {
            value = value shl 4
            care = care shl 4
            if (c == 'X') continue
            value = value or (c.digitToIntOrNull(16) ?: return "?")
            care = care or 0xF
        }
        filter = value
        mask = care and 0x7FF
        return "OK"
    }

    private fun obd(cmd: String): String {
        val payload = cmd.hexToBytesOrNull() ?: return "?"
        if (payload.isEmpty()) return "?"
        if (!autoFormat) return raw(payload)
        // A service this app does not send — anything that writes — is refused
        // by [Diagnostics] before it reaches the bus. The client hears what it
        // would hear from a module that did not answer, and the session goes on.
        val answer = try {
            bridge.request(txHeader, payload, TIMEOUT_MS)
        } catch (_: IllegalArgumentException) {
            null
        } ?: return "NO DATA"
        val (id, data) = answer
        if (headers) return frames(data).joinToString("\r") { line(id, it) }
        if (data.size <= 7) return hex(data)
        return message(data)
    }

    private fun raw(frame: ByteArray): String {
        if (frame.size > 8) return "?"
        val set = filter
        val accepts: (Int) -> Boolean = if (set == null) {
            // No filter set: what an answer to this header can come back on,
            // not the whole bus — on this car that is 1300 frames a second.
            { Diagnostics.isDiagnosticReply(it) || Diagnostics.isEmitted(it) }
        } else {
            { (it and mask) == (set and mask) }
        }
        val frames = try {
            bridge.raw(txHeader, frame, accepts, ::flowControlFor, timeoutMs, RAW_TIMEOUT_MS)
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
        if (frames.isEmpty()) return "NO DATA"
        return frames.joinToString("\r") { (id, data) -> line(id, data) }
    }

    /** What an ELM327 sends after a first frame, under `ATFCSM` 0, 1 or 2. */
    private fun flowControlFor(answeredOn: Int): Pair<Int, ByteArray>? {
        if (!flowControlOn) return null
        val to = Diagnostics.requestIdFor(answeredOn, txHeader)
        return when (flowControlMode) {
            1 -> (flowControlHeader ?: to) to flowControlData
            2 -> to to flowControlData
            else -> to to DEFAULT_FLOW_CONTROL
        }
    }

    private fun line(id: Int, frame: ByteArray): String {
        val body = hex(frame)
        return if (headers) "%03X".format(id) + (if (spaces) " " else "") + body else body
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString(if (spaces) " " else "") { "%02X".format(it) }

    /** A long answer with headers off: its length, then numbered lines. */
    private fun message(data: ByteArray): String {
        val out = ArrayList<String>()
        out.add("%03X".format(data.size))
        val sep = if (spaces) " " else ""
        var at = 0
        var index = 0
        while (at < data.size) {
            val take = minOf(if (index == 0) 6 else 7, data.size - at)
            out.add("%X:".format(index and 0x0f) + sep + hex(data.copyOfRange(at, at + take)))
            at += take
            index++
        }
        return out.joinToString("\r")
    }

    /**
     * Cut a reassembled answer back into the CAN frames that carried it.
     *
     * Padded to eight bytes because that is what comes off the bus: the modules
     * on this car send full frames, and a client reading fixed-width lines is
     * within its rights to expect them.
     */
    private fun frames(data: ByteArray): List<ByteArray> {
        if (data.size <= 7) {
            return listOf(pad(byteArrayOf(data.size.toByte()) + data))
        }
        val out = ArrayList<ByteArray>()
        val first = ByteArray(8)
        first[0] = (0x10 or ((data.size shr 8) and 0x0f)).toByte()
        first[1] = data.size.toByte()
        System.arraycopy(data, 0, first, 2, 6)
        out.add(first)
        var at = 6
        var index = 1
        while (at < data.size) {
            val take = minOf(7, data.size - at)
            val frame = ByteArray(8)
            frame[0] = (0x20 or (index and 0x0f)).toByte()
            System.arraycopy(data, at, frame, 1, take)
            out.add(frame)
            at += take
            index++
        }
        return out
    }

    private fun pad(frame: ByteArray): ByteArray =
        if (frame.size >= 8) frame else frame + ByteArray(8 - frame.size)

    private fun reset() {
        echo = true
        headers = false
        spaces = true
        txHeader = 0x7DF
        protocol = 6
        automatic = true
        autoFormat = true
        filter = null
        mask = 0x7FF
        timeoutMs = DEFAULT_TIMEOUT_MS
        flowControlOn = true
        flowControlMode = 0
        flowControlHeader = null
        flowControlData = DEFAULT_FLOW_CONTROL
    }

    private fun protocolName(p: Int) = when (p) {
        1 -> "SAE J1850 PWM"
        2 -> "SAE J1850 VPW"
        3 -> "ISO 9141-2"
        4 -> "ISO 14230-4 KWP"
        5 -> "ISO 14230-4 KWP FAST"
        6 -> "ISO 15765-4 CAN 11/500"
        7 -> "ISO 15765-4 CAN 29/500"
        8 -> "ISO 15765-4 CAN 11/250"
        9 -> "ISO 15765-4 CAN 29/250"
        else -> "AUTO"
    }

    private fun String.hexToBytesOrNull(): ByteArray? {
        if (length % 2 != 0 || any { it !in '0'..'9' && it !in 'A'..'F' }) return null
        return ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private companion object {
        /**
         * Long enough for a segmented answer to finish arriving. The old second
         * was under what a three-frame reply needs once flow control is in it,
         * so the reply that most needs this — the VIN — was the one most likely
         * to time out.
         */
        const val TIMEOUT_MS = 2500L

        /** `ATST` 32, an ELM327's default: 200 ms with nothing more arriving. */
        const val DEFAULT_TIMEOUT_MS = 200L
        const val MIN_TIMEOUT_MS = 20L

        /** The longest a raw frame waits for its first answer. */
        const val RAW_TIMEOUT_MS = 2500L

        /** "Clear to send, no block limit, no gap." */
        val DEFAULT_FLOW_CONTROL = byteArrayOf(0x30, 0x00, 0x00)
    }
}
