package com.varuna.opendash.protocol

/**
 * The h4 fingerprint.
 *
 * The firmware checks it — a greeting with one bit flipped is answered with a
 * TCP reset — so anything sent has to carry the right one. It is affine over
 * GF(2), which means it can be used without being named: take a frame that was
 * actually recorded, change what you need, and XOR in the contribution of every
 * bit that differs.
 *
 * Measured: 85 of 85 held-out writes with a random id and a random payload get
 * exactly the fingerprint the device put on the wire.
 */
object H4 {

    /** Build a write frame for [canId] and [payload], with the right h4. */
    fun write(canId: Int, payload: ByteArray): ByteArray {
        val frame = Recorded.writeTemplate.copyOf()
        val data = frame.copyOfRange(Frame.HEADER, frame.size)

        // record layout: 60 80 02 | len u32 | id u32 | 8 data bytes
        Frame.putLe32(data, 7, canId)
        for (i in 0 until 8) {
            data[11 + i] = when {
                i == 0 -> payload.size.toByte()
                i - 1 < payload.size -> payload[i - 1]
                else -> 0
            }
        }

        // Refused rather than sent. A frame whose fingerprint cannot be
        // computed from what the sweeps pinned down is one the device drops
        // without a word, and a request that vanishes is far harder to chase
        // than one that never left. For an eleven-bit id and eight payload
        // bytes this never triggers, which is the shape everything here sends.
        if (!isVerified(frame, data)) {
            throw IllegalArgumentException(
                "refusing to send a write whose h4 rests on bits the sweeps never pinned down"
            )
        }

        val h4 = derive(frame, data)
        System.arraycopy(data, 0, frame, Frame.HEADER, data.size)
        Frame.putLe32(frame, 4, h4)
        return frame
    }

    /**
     * h4 for [data] given a [reference] frame whose seq, h8 and padding are
     * reused unchanged.
     */
    fun derive(reference: ByteArray, data: ByteArray): Int {
        var h4 = Frame.le32(reference, 4)
        val tail = reference.copyOfRange(Frame.HEADER, reference.size)
        val n = minOf(20, maxOf(tail.size, data.size))
        for (byte in 0 until n) {
            val a = if (byte < tail.size) tail[byte].toInt() and 0xff else 0
            val b = if (byte < data.size) data[byte].toInt() and 0xff else 0
            val diff = a xor b
            if (diff == 0) continue
            for (bit in 0 until 8) {
                if (diff and (1 shl bit) != 0) h4 = h4 xor Recorded.h4Bits[byte * 8 + bit]
            }
        }
        return h4
    }

    /**
     * Does this change only touch bits the sweeps pinned down? A frame that
     * fails this may get a fingerprint the device drops without a word, which
     * is a much harder thing to debug than a refusal here.
     */
    fun isVerified(reference: ByteArray, data: ByteArray): Boolean {
        val tail = reference.copyOfRange(Frame.HEADER, reference.size)
        for (byte in 0 until 20) {
            val a = if (byte < tail.size) tail[byte].toInt() and 0xff else 0
            val b = if (byte < data.size) data[byte].toInt() and 0xff else 0
            val unknown = Recorded.h4Known[byte].inv() and 0xff
            if ((a xor b) and unknown != 0) return false
        }
        return true
    }
}
