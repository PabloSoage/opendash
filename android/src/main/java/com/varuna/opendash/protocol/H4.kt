package com.varuna.opendash.protocol

/**
 * The two derived words every message carries: the `h4` fingerprint and the
 * `h8` sum.
 *
 * The firmware checks both. A frame with either one wrong is not answered and
 * not refused — the adapter drops it and closes the connection, which from the
 * outside looks like the link dying for no reason at all. This app spent a long
 * time doing exactly that: every request it ever sent to the car carried a
 * fingerprint computed from an incomplete table, and an `h8` copied unchanged
 * from the reference frame. Measured on the car, the reset came three
 * milliseconds after the write went out.
 *
 * ## `h8` is a sum
 *
 * Read the twenty data bytes as five little-endian 32-bit words and add them.
 * That is `h8` for all 708 recorded writes, exactly. It moves with the payload,
 * so a frame that is built rather than replayed has to recompute it.
 *
 * ## `h4` is a CRC
 *
 * It is affine over GF(2), so a new frame's fingerprint is the reference
 * frame's fingerprint XOR the contribution of every data bit that differs.
 * What used to be missing was the contributions: they were fitted, and a fit
 * leaves holes wherever the recorded traffic never varied a bit. The old code
 * had a companion mask meant to flag those holes, but it was built from which
 * columns had been pivots in the elimination — not the same question — so it
 * waved through 22 bits it could not vouch for, and `1A 90`, `3E 00` and
 * `01 20` all landed on them.
 *
 * [H4Table.BITS] is not fitted any more. Each bit's contribution is the next
 * one shifted down by one with the reflected polynomial `0x9960034C` folded in
 * when a one falls off the bottom, which is the recurrence of a CRC, so the
 * whole table follows from a single anchor and has no holes. Every entry the
 * captures pin down independently agrees with it, and the requests this app
 * sends come out byte for byte identical to the ones the manufacturer's own
 * tool put on the wire.
 */
object H4 {

    /** How many data bytes take part in both derived words. */
    private const val COVERED = 20

    /**
     * Build a write message carrying [frameBytes] as the CAN frame for [canId],
     * signed so the device accepts it.
     *
     * The eight bytes of the record are the CAN frame itself. That matters for
     * anything that is not a single frame: flow control has to reach the module
     * as `30 00 00`, and building it as a length plus seven payload bytes puts
     * `08 30 00` on the wire, which reads as a single frame invoking service
     * 0x30. Every one of the 480 writes in the recorded session carries 8 in the
     * record's length field, and every one of them starts with a single-frame
     * PCI, so the two readings only ever coincided there.
     */
    fun writeFrame(canId: Int, frameBytes: ByteArray): ByteArray {
        require(frameBytes.size <= 8) { "a CAN frame carries at most eight bytes" }
        val frame = Recorded.writeTemplate.copyOf()
        val data = frame.copyOfRange(Frame.HEADER, frame.size)

        // record layout: 60 80 02 | len u32 | id u32 | 8 data bytes
        Frame.putLe32(data, 7, canId)
        for (i in 0 until 8) {
            data[11 + i] = if (i < frameBytes.size) frameBytes[i] else 0
        }

        val h4 = derive(frame, data)
        System.arraycopy(data, 0, frame, Frame.HEADER, data.size)
        Frame.putLe32(frame, 4, h4)
        Frame.putLe32(frame, 8, sum(data))
        return frame
    }

    /**
     * Build a write frame for [canId] carrying [payload] as an ISO-TP single
     * frame — the length goes in the leading PCI byte.
     */
    fun write(canId: Int, payload: ByteArray): ByteArray {
        require(payload.size <= 7) { "a single frame carries at most seven payload bytes" }
        return writeFrame(canId, singleFrame(payload))
    }

    /** [payload] wrapped as an ISO-TP single frame: the PCI byte is the length. */
    fun singleFrame(payload: ByteArray): ByteArray {
        val out = ByteArray(8)
        out[0] = payload.size.toByte()
        payload.copyInto(out, 1)
        return out
    }

    /**
     * [h8] for a write record: the data as little-endian 32-bit words, added.
     *
     * Short data counts as zero-padded, which is what the wire carries — the
     * frame is aligned to four bytes anyway.
     */
    fun sum(data: ByteArray): Int {
        var total = 0
        for (word in 0 until COVERED / 4) {
            var v = 0
            for (byte in 0 until 4) {
                val b = data.getOrNull(word * 4 + byte)?.toInt()?.and(0xff) ?: 0
                v = v or (b shl (byte * 8))
            }
            total += v
        }
        return total
    }

    /**
     * `h4` for [data] given a [reference] frame whose seq and padding are
     * reused unchanged.
     */
    fun derive(reference: ByteArray, data: ByteArray): Int {
        var h4 = Frame.le32(reference, 4)
        val tail = reference.copyOfRange(Frame.HEADER, reference.size)
        for (byte in 0 until COVERED) {
            val a = tail.getOrNull(byte)?.toInt()?.and(0xff) ?: 0
            val b = data.getOrNull(byte)?.toInt()?.and(0xff) ?: 0
            val diff = a xor b
            if (diff == 0) continue
            for (bit in 0 until 8) {
                if (diff and (1 shl bit) != 0) h4 = h4 xor H4Table.BITS[byte * 8 + bit]
            }
        }
        return h4
    }
}
