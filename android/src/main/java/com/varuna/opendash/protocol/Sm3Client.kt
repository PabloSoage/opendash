package com.varuna.opendash.protocol

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A session with the SM3 over its own Wi-Fi.
 *
 * The device is a plain TCP server on 192.168.81.1:777 once the phone joins its
 * access point. Opening a channel means replaying, byte for byte, the sequence
 * the manufacturer's driver sends — the firmware checks the h4 fingerprint on
 * every message, so anything hand-rolled is refused.
 *
 * Reading is a poll: send op 0x1c with subcommand 40 80 02 and the device
 * answers with 0xfe blocks carrying whatever CAN frames arrived.
 */
class Sm3Client(
    private val host: String = DEFAULT_HOST,
    private val port: Int = DEFAULT_PORT,
) {
    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    /** Frames seen since the last drain, oldest first. */
    private val received = ConcurrentLinkedQueue<CanFrame>()

    data class CanFrame(val id: Int, val data: ByteArray) {
        override fun equals(other: Any?) =
            other is CanFrame && id == other.id && data.contentEquals(other.data)

        override fun hashCode() = id * 31 + data.contentHashCode()
    }

    class Identity(val serial: String, val firmware: String)

    val isConnected: Boolean get() = socket?.isConnected == true && socket?.isClosed == false

    /**
     * Open the socket and greet. Returns what the device says it is, which
     * doubles as proof the link works before anything else is attempted.
     */
    fun connect(timeoutMs: Int = 4000): Identity {
        close()
        val s = Socket()
        s.connect(InetSocketAddress(host, port), timeoutMs)
        s.soTimeout = timeoutMs
        s.tcpNoDelay = true
        socket = s
        input = s.getInputStream()
        output = s.getOutputStream()

        val reply = exchange(Recorded.opening[0]) ?: error("no answer to the greeting")
        return identity(reply)
    }

    /**
     * Replay the recorded opening: session, config, channel and filters. After
     * this the bus can be polled.
     */
    fun openChannel() {
        for (i in 1 until Recorded.opening.size) {
            exchange(Recorded.opening[i])
        }
    }

    /** Poll once; returns how many frames arrived. */
    fun poll(): Int {
        val reply = exchange(Recorded.read) ?: return 0
        var n = 0
        for (msg in Frame.split(reply)) {
            if (msg.op != OP_FRAMES) continue
            for (f in framesIn(msg.data)) {
                received.add(f)
                n++
            }
        }
        return n
    }

    /**
     * The device's own voltages, from opcode 0x20.
     *
     * No payload, always answered, and the answer is six bytes that appear
     * nowhere else in the protocol. Read as three little-endian u16, the first
     * is the battery in millivolts: over one recorded session it ran from 8765
     * while cranking to 14 674 with the alternator charging, sitting at 12 348
     * with the engine off. That is where ATRV comes from.
     *
     * A message with no data is the one case where h4 needs no table: it is
     * simply seq, so this frame can be built rather than replayed.
     */
    fun voltages(): Voltages? {
        val frame = ByteArray(Frame.HEADER)
        Frame.putLe32(frame, 0, 0x0000ffff)          // seq 0xffff, token 0
        Frame.putLe32(frame, 4, 0x0000ffff)          // len = 0, so h4 == seq
        frame[12] = OP_VOLTAGES.toByte()
        frame[15] = Frame.checksum(OP_VOLTAGES, 0).toByte()

        val reply = exchange(frame) ?: return null
        for (msg in Frame.split(reply)) {
            if (msg.op != 0x00 || msg.data.size < 6) continue
            return Voltages(
                batteryMillivolts = Frame.le16(msg.data, 0),
                second = Frame.le16(msg.data, 2),
                flags = Frame.le16(msg.data, 4),
            )
        }
        return null
    }

    /** The second field is unidentified; the flags take a handful of values. */
    class Voltages(val batteryMillivolts: Int, val second: Int, val flags: Int)

    /** Put a request on the bus. The h4 is computed, not guessed. */
    fun send(canId: Int, payload: ByteArray) {
        val frame = H4.write(canId, payload)
        exchange(frame)
    }

    fun drain(): List<CanFrame> {
        val out = ArrayList<CanFrame>()
        while (true) out.add(received.poll() ?: break)
        return out
    }

    fun close() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        input = null
        output = null
        received.clear()
    }

    // ── the wire ──────────────────────────────────────────────────────────

    private fun exchange(message: ByteArray): ByteArray? {
        val out = output ?: error("not connected")
        val inp = input ?: error("not connected")
        out.write(message)
        out.flush()
        val buf = ByteArray(8192)
        return try {
            val n = inp.read(buf)
            if (n <= 0) null else buf.copyOf(n)
        } catch (_: java.net.SocketTimeoutException) {
            null
        }
    }

    /**
     * A 0xfe block: 32 bytes of block header, the frame count in the high
     * nibble of byte 34, then 16-byte records of len u32 | id u32 | 8 bytes.
     */
    private fun framesIn(data: ByteArray): List<CanFrame> {
        if (data.size < 36) return emptyList()
        val count = (data[34].toInt() and 0xff) shr 4
        val out = ArrayList<CanFrame>(count)
        for (i in 0 until count) {
            val o = 36 + i * 16
            if (o + 16 > data.size) break
            val len = Frame.le32(data, o)
            if (len !in 0..8) continue
            out.add(CanFrame(Frame.le32(data, o + 4), data.copyOfRange(o + 8, o + 8 + len)))
        }
        return out
    }

    /**
     * The greeting answer carries the serial and the firmware version at fixed
     * offsets, which is how a session is confirmed good before trusting it.
     */
    private fun identity(reply: ByteArray): Identity {
        val d = Frame.decode(reply)?.first?.data ?: return Identity("?", "?")
        val serial = if (d.size >= 24) {
            val raw = String(d.copyOfRange(0, 8), Charsets.US_ASCII).trim { it <= ' ' }
            raw.trimStart('0').ifEmpty { raw }
        } else "?"
        val firmware = if (d.size >= 32) Frame.le32(d, 28).toString() else "?"
        return Identity(serial, firmware)
    }

    companion object {
        const val DEFAULT_HOST = "192.168.81.1"
        const val DEFAULT_PORT = 777
        private const val OP_FRAMES = 0xfe
        private const val OP_VOLTAGES = 0x20
    }
}
