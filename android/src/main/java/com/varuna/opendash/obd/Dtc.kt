package com.varuna.opendash.obd

/**
 * A stored fault code.
 *
 * Two bytes on the wire, and the encoding is the reason a code reads the way it
 * does on a garage screen:
 *
 * ```
 * bits 15..14   00 P powertrain · 01 C chassis · 10 B body · 11 U network
 * bits 13..12   the first digit, 0 to 3
 * bits 11..8    the second digit, hexadecimal
 * bits  7..0    the last two digits, hexadecimal
 * ```
 *
 * So 0x0134 is P0134 and 0x4271 is C0271.
 */
data class Dtc(val raw: Int, val pending: Boolean = false) {

    val code: String = buildString {
        append("PCBU"[(raw shr 14) and 0x03])
        append((raw shr 12) and 0x03)
        append(HEX[(raw shr 8) and 0x0f])
        append(HEX[(raw shr 4) and 0x0f])
        append(HEX[raw and 0x0f])
    }

    override fun toString() = code

    private companion object {
        const val HEX = "0123456789ABCDEF"
    }
}

/**
 * Codes come back as pairs of bytes with no separator, and a pair of zeros is
 * padding rather than a code — P0000 does not exist.
 */
fun decodeDtcs(payload: ByteArray, pending: Boolean = false): List<Dtc> {
    val out = ArrayList<Dtc>()
    var i = 0
    while (i + 1 < payload.size) {
        val raw = ((payload[i].toInt() and 0xff) shl 8) or (payload[i + 1].toInt() and 0xff)
        if (raw != 0) out.add(Dtc(raw, pending))
        i += 2
    }
    return out
}
