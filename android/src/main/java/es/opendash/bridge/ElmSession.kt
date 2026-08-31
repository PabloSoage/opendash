package es.opendash.bridge

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
 */
class ElmSession(private val bridge: Bridge) {

    private var echo = true
    private var headers = false
    private var spaces = true
    private var txHeader = 0x7DF
    private var protocol = 6

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
        rest == "DPN" -> protocol.toString(16).uppercase(Locale.ROOT)
        rest == "DP" -> protocolName(protocol)
        rest.startsWith("SP") -> {
            protocol = rest.removePrefix("SP").removePrefix("A").toIntOrNull(16) ?: 6
            if (protocol == 0) protocol = 6
            "OK"
        }
        rest.startsWith("SH") -> {
            val v = rest.removePrefix("SH").toIntOrNull(16)
            if (v == null) "?" else { txHeader = v; "OK" }
        }
        else -> "?"
    }

    private fun obd(cmd: String): String {
        val payload = cmd.hexToBytesOrNull() ?: return "?"
        if (payload.isEmpty()) return "?"
        val answer = bridge.request(txHeader, payload, TIMEOUT_MS) ?: return "NO DATA"
        val (id, data) = answer
        val sep = if (spaces) " " else ""
        val body = data.joinToString(sep) { "%02X".format(it) }
        return if (headers) "%03X".format(id) + sep + body else body
    }

    private fun reset() {
        echo = true
        headers = false
        spaces = true
        txHeader = 0x7DF
        protocol = 6
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
        const val TIMEOUT_MS = 1000L
    }
}
