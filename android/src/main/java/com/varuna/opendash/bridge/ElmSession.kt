package com.varuna.opendash.bridge

import java.util.Locale

/**
 * One conversation with a phone app.
 *
 * PROVISIONAL. This mirrors `core/src/elm327.rs` in Kotlin so the socket can be
 * exercised before the native library is bound. Once the JNI bridge lands this
 * should call into the core instead, because two implementations of the same
 * command set will drift, and the Rust one is the one with tests behind it.
 *
 * Everything that touches the wire — framing, padding, the `h4` fingerprint,
 * ISO-TP — stays in the core regardless. This only parses text.
 *
 * ## Answering in the shape an ELM327 answers
 *
 * This used to reassemble a segmented reply and print it as one long line of
 * bytes. No adapter behaves that way, and no client parses it. A real ELM327
 * prints the CAN frames: one line each, with the ISO-TP protocol control byte
 * that says whether the frame is the whole answer or the first of several.
 * A client asking for the VIN gets a three-frame answer, looks for `10 14` on
 * the first line, and when it never arrives it asks again — for ever. From the
 * outside that is an app stuck on "identifying vehicle" with a bridge that
 * looks like it is working.
 *
 * So the reassembled answer is cut back into frames on the way out. The
 * segmentation is not guesswork: ISO-TP says a reply of eight bytes or more
 * begins with `1` and the twelve-bit length, and each following frame with `2`
 * and a sequence number that wraps at sixteen.
 */
class ElmSession(private val bridge: Bridge) {

    private var echo = true
    private var headers = false
    private var spaces = true
    private var txHeader = 0x7DF
    private var protocol = 6
    private var automatic = true

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
        // Settings this bridge has no dial for, acknowledged rather than
        // refused. A client that sets its timeout or its flow control and gets
        // "?" back can decide the adapter is broken and stop, and every one of
        // these is a request to behave in a way this bridge already behaves.
        rest.startsWith("ST") || rest.startsWith("AT") || rest.startsWith("CRA") ||
            rest.startsWith("FC") || rest.startsWith("CAF") || rest.startsWith("CF") ||
            rest.startsWith("CM") || rest.startsWith("CP") || rest.startsWith("TP") ||
            rest == "AL" || rest == "NL" || rest == "PC" || rest == "M0" || rest == "M1" ||
            rest == "R0" || rest == "R1" || rest == "CSM0" || rest == "CSM1" -> "OK"
        else -> "?"
    }

    private fun obd(cmd: String): String {
        val payload = cmd.hexToBytesOrNull() ?: return "?"
        if (payload.isEmpty()) return "?"
        val answer = bridge.request(txHeader, payload, TIMEOUT_MS) ?: return "NO DATA"
        val (id, data) = answer
        return frames(data).joinToString("\r") { line ->
            val sep = if (spaces) " " else ""
            val body = line.joinToString(sep) { "%02X".format(it) }
            if (headers) "%03X".format(id) + sep + body else body
        }
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
    }
}
