package es.opendash.protocol

/**
 * Message framing.
 *
 * ```
 * 0..1   seq    u16 LE   TCP: always 0xffff
 * 2..3   token  u16 LE   TCP: always 0
 * 4..7   h4     u32 LE   fingerprint, checked by the firmware
 * 8..11  h8     u32 LE   context / handle
 * 12     op
 * 13..14 len    u16 LE
 * 15     chk    (op + len_lo + len_hi + 0x55) and 0xff
 * 16..   data
 * ```
 *
 * The detail that costs a session if missed: a message occupies
 * `align(16 + len, 4)` bytes on the wire, not `16 + len`. Send the short form
 * and the device waits for bytes that never arrive, the stream desynchronises
 * and the connection is reset. Measured over 4066 messages from two
 * independent captures.
 */
object Frame {

    const val HEADER = 16

    fun stride(len: Int): Int = (HEADER + len + 3) and 3.inv()

    fun checksum(op: Int, len: Int): Int =
        (op + (len and 0xff) + ((len ushr 8) and 0xff) + 0x55) and 0xff

    class Message(
        val seq: Int,
        val h4: Int,
        val h8: Int,
        val op: Int,
        val data: ByteArray,
    )

    /** Parse one message at [offset]. Returns it and how many bytes it took. */
    fun decode(buf: ByteArray, offset: Int = 0): Pair<Message, Int>? {
        if (offset + HEADER > buf.size) return null
        val op = buf[offset + 12].toInt() and 0xff
        val len = (buf[offset + 13].toInt() and 0xff) or ((buf[offset + 14].toInt() and 0xff) shl 8)
        if ((buf[offset + 15].toInt() and 0xff) != checksum(op, len)) return null
        if (offset + HEADER + len > buf.size) return null
        val msg = Message(
            seq = le16(buf, offset),
            h4 = le32(buf, offset + 4),
            h8 = le32(buf, offset + 8),
            op = op,
            data = buf.copyOfRange(offset + HEADER, offset + HEADER + len),
        )
        return msg to minOf(stride(len), buf.size - offset)
    }

    /** Walk a buffer, skipping anything that does not parse. */
    fun split(buf: ByteArray): List<Message> {
        val out = ArrayList<Message>()
        var off = 0
        while (off + HEADER <= buf.size) {
            val r = decode(buf, off)
            if (r == null) {
                off++
            } else {
                out.add(r.first)
                off += r.second
            }
        }
        return out
    }

    fun le16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)

    fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
            ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)

    fun putLe32(b: ByteArray, o: Int, v: Int) {
        b[o] = (v and 0xff).toByte()
        b[o + 1] = ((v ushr 8) and 0xff).toByte()
        b[o + 2] = ((v ushr 16) and 0xff).toByte()
        b[o + 3] = ((v ushr 24) and 0xff).toByte()
    }
}
