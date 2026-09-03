package com.varuna.opendash.protocol

import java.io.IOException
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
 * Two rules govern everything below, and both were learnt the hard way.
 *
 * **TCP is a stream, not a sequence of messages.** One `read` returns whatever
 * happened to arrive: half a message, three messages, a message and a half.
 * Treating a read as a message makes the greeting come back too short to hold a
 * serial number, and from then on every following read is offset by the
 * leftovers. So the socket feeds a buffer and messages are taken out of it by
 * length, never by read boundary.
 *
 * **Every reply carries op 0x00, whatever was asked.** Measured over the whole
 * factory capture: 1502 answers to `0x20`, 1387 to `0x1c`, 603 to `0x50`, 86 to
 * the greeting — all of them op 0x00. What tells them apart is only the order
 * they arrive in. Messages with op 0xfe are different: those are frame
 * deliveries the device sends unasked, interleaved with the answers.
 *
 * Together they explain a symptom worth writing down. Asking for the voltage
 * and reading back whatever was in the socket returns the answer to the
 * previous bus poll, whose first two bytes read as millivolts give **0.640 V**
 * — 1069 of the 1387 poll answers in the capture do exactly that. So: one
 * request at a time under a lock, then read until an op 0x00 arrives, stashing
 * any frames met on the way.
 */
class Sm3Client(
    /** Where the adapter answers. Editable: the firmware lets both change. */
    var host: String = DEFAULT_HOST,
    var port: Int = DEFAULT_PORT,
) {
    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    /** Guards the socket. Every screen and the bridge share one link. */
    private val lock = Any()

    /** Frames seen since the last drain, oldest first. */
    private val received = ConcurrentLinkedQueue<CanFrame>()

    /** Running total, so a poll can report its own yield without walking it. */
    private var stashed = 0

    /** Bytes read but not yet a whole message. */
    private val buffer = ByteArray(1 shl 16)
    private var used = 0

    /**
     * Bytes thrown away resynchronising, and what last went wrong. Both are
     * shown on the health screen: a link that works has zero and null, and a
     * link that is lying about working does not.
     */
    var resynchronised = 0
        private set
    var lastFault: String? = null
        private set

    /**
     * Answers that arrived with no request outstanding, dropped rather than
     * kept. Measured against the factory capture this should stay at zero:
     * 5597 of 5683 requests were answered exactly once and the 85 apparent
     * doubles are the same bytes captured twice, a retransmission rather than
     * the firmware speaking twice. A number that climbs here means that model
     * is wrong on this device, which is worth knowing before it shows up as a
     * voltage reading from the wrong answer.
     */
    var unpaired = 0
        private set

    /**
     * Called with the socket before it connects, so it can be pinned to a
     * particular network. Without it Android sends the packets out over mobile
     * data — the adapter's access point has no internet, so the system prefers
     * the other one — and the connection simply never arrives.
     */
    var bindSocket: ((Socket) -> Unit)? = null

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
    fun connect(timeoutMs: Int = 4000): Identity = synchronized(lock) {
        closeLocked()
        lastFault = null
        val s = Socket()
        try {
            bindSocket?.invoke(s)
            s.connect(InetSocketAddress(host, port), timeoutMs)
        } catch (e: Exception) {
            try { s.close() } catch (_: Exception) {}
            throw e
        }
        s.soTimeout = READ_SLICE_MS
        s.tcpNoDelay = true
        socket = s
        input = s.getInputStream()
        output = s.getOutputStream()
        used = 0

        val reply = exchangeLocked(Recorded.opening[0], timeoutMs.toLong())
        if (reply == null) {
            closeLocked()
            throw IOException("the adapter did not answer the greeting")
        }
        identity(reply)
    }

    /**
     * Replay the recorded opening: session, config, channel and filters.
     *
     * Every step is checked. Replaying the rest of the sequence after one has
     * gone unanswered is what leaves the device in a state where it stops
     * talking to anything, its own application included, until it is unplugged.
     */
    fun openChannel() = synchronized(lock) {
        for (i in 1 until Recorded.opening.size) {
            if (exchangeLocked(Recorded.opening[i], STEP_TIMEOUT_MS) == null) {
                val at = "step $i of ${Recorded.opening.size}"
                closeLocked()
                throw IOException("the adapter stopped answering at $at of the opening")
            }
        }
    }

    /** Poll once; returns how many frames arrived. */
    fun poll(): Int = synchronized(lock) {
        val before = stashed
        exchangeLocked(Recorded.read, STEP_TIMEOUT_MS)
        stashed - before
    }

    /**
     * Read what the device is sending, without asking it for anything.
     *
     * The adapter pushes. Measured over the factory session: 226 121 frame
     * blocks arrived unasked against 1744 polls the tool sent — 130 pushed for
     * every one requested, at 135 blocks a second on average and 639 while a
     * packet was streaming. The poll is a status read at roughly one a second
     * (median gap 1003 ms), not a way to fetch frames.
     *
     * That matters because a request used to wait for its answer by polling
     * every 10 millisecond, some ninety times what the manufacturer tool ever
     * sends, and it read the socket only while a poll was outstanding. This
     * reads the socket for its own sake, which is what the protocol actually
     * wants.
     *
     * Answers arriving with nobody waiting for them are counted and dropped.
     * Carrying one forward is what would make the next request read the
     * previous answer, and a battery that reads 0.640 V is what that looks
     * like from the outside.
     */
    fun receive(timeoutMs: Long): Int = synchronized(lock) {
        if (output == null) return 0
        val before = stashed
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val msg = nextMessage(deadline) ?: break
            if (msg.op == OP_FRAMES) {
                for (f in framesIn(msg.data)) {
                    received.add(f)
                    stashed++
                }
            } else {
                unpaired++
            }
        }
        stashed - before
    }

    /**
     * The device's own voltages, from opcode 0x20.
     *
     * The answer is six bytes that appear nowhere else in the protocol. Read as
     * three little-endian u16, the first is the battery in millivolts: over one
     * recorded session it ran from 8765 while cranking to 14 674 with the
     * alternator charging, sitting at 12 348 with the engine off. That is where
     * ATRV comes from.
     *
     * The length is checked exactly rather than as a minimum. Six is what this
     * answer is; anything else is somebody else's answer, and accepting it is
     * how a battery reads 0.64 V.
     *
     * A message with no data is the one case where h4 needs no table: it is
     * simply seq, so this frame can be built rather than replayed.
     */
    fun voltages(): Voltages? = synchronized(lock) {
        val frame = ByteArray(Frame.HEADER)
        Frame.putLe32(frame, 0, 0x0000ffff)          // seq 0xffff, token 0
        Frame.putLe32(frame, 4, 0x0000ffff)          // len = 0, so h4 == seq
        frame[12] = OP_VOLTAGES.toByte()
        frame[15] = Frame.checksum(OP_VOLTAGES, 0).toByte()

        val reply = exchangeLocked(frame, STEP_TIMEOUT_MS) ?: return null
        if (reply.data.size != VOLTAGES_LENGTH) {
            lastFault = "voltage answer was ${reply.data.size} bytes, not $VOLTAGES_LENGTH"
            return null
        }
        Voltages(
            batteryMillivolts = Frame.le16(reply.data, 0),
            second = Frame.le16(reply.data, 2),
            flags = Frame.le16(reply.data, 4),
        )
    }

    /** The second field is unidentified; the flags take a handful of values. */
    class Voltages(val batteryMillivolts: Int, val second: Int, val flags: Int)

    /** Put a request on the bus. The h4 is computed, not guessed. */
    fun send(canId: Int, payload: ByteArray) = synchronized(lock) {
        exchangeLocked(H4.write(canId, payload), STEP_TIMEOUT_MS)
        Unit
    }

    fun drain(): List<CanFrame> {
        val out = ArrayList<CanFrame>()
        while (true) out.add(received.poll() ?: break)
        return out
    }

    /**
     * Hand the channel back before dropping the socket.
     *
     * Best effort, and short: if the device has already stopped answering there
     * is nothing to say to it, and the socket goes either way.
     *
     * Attempted whenever the socket is still open, which used not to be the
     * case. It used to be skipped whenever anything had gone wrong earlier in
     * the session, and that is precisely backwards: a single request that timed
     * out — an ordinary event — left a fault recorded, and every disconnect
     * from then on dropped the socket without a word. The device keeps the
     * session, and the next application to try, its own included, finds the
     * adapter refusing everything until it is unplugged. A link that is really
     * dead has no output stream by this point and the loop is skipped anyway.
     */
    fun close() = synchronized(lock) {
        if (output != null) {
            try {
                for (message in Recorded.closing) exchangeLocked(message, CLOSE_TIMEOUT_MS)
            } catch (_: Exception) {
            }
        }
        closeLocked()
    }

    // ── the wire ──────────────────────────────────────────────────────────

    private fun closeLocked() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        input = null
        output = null
        used = 0
        received.clear()
    }

    /**
     * Write one message and wait for the answer to it.
     *
     * Answers all carry op 0x00, so the one that belongs to this request is
     * simply the next one; frame deliveries met on the way are kept rather than
     * discarded, which is what makes polling and asking share a socket without
     * stealing each other's replies.
     */
    private fun exchangeLocked(message: ByteArray, timeoutMs: Long): Frame.Message? {
        val out = output ?: throw IOException("not connected")
        try {
            out.write(message)
            out.flush()
        } catch (e: IOException) {
            fail("write failed: " + (e.message ?: e.javaClass.simpleName))
            throw e
        }

        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val msg = nextMessage(deadline)
            if (msg == null) {
                lastFault = "no answer within ${timeoutMs} ms"
                return null
            }
            if (msg.op == OP_FRAMES) {
                for (f in framesIn(msg.data)) {
                    received.add(f)
                    stashed++
                }
                continue
            }
            return msg
        }
    }

    /** Take the next whole message out of the buffer, filling it as needed. */
    private fun nextMessage(deadline: Long): Frame.Message? {
        while (true) {
            if (used >= Frame.HEADER) {
                val size = Frame.sizeAt(buffer, 0)
                if (size == null) {
                    // Not a header. One byte at a time is the only honest way
                    // back: the stream has no marker to search for.
                    discard(1)
                    resynchronised++
                    continue
                }
                if (used >= size) {
                    val msg = Frame.decode(buffer, 0)?.first
                    discard(size)
                    if (msg != null) return msg
                    resynchronised += size
                    continue
                }
                if (size > buffer.size) {
                    val why = "a message of $size bytes does not fit the read buffer"
                    fail(why)
                    throw IOException(why)
                }
            }
            if (!fill(deadline)) return null
        }
    }

    /** Read once into the buffer. False means the deadline passed. */
    private fun fill(deadline: Long): Boolean {
        val inp = input ?: throw IOException("not connected")
        while (System.currentTimeMillis() < deadline) {
            if (used == buffer.size) {
                val why = "read buffer full with nothing that parses"
                fail(why)
                throw IOException(why)
            }
            val n = try {
                inp.read(buffer, used, buffer.size - used)
            } catch (_: java.net.SocketTimeoutException) {
                continue
            } catch (e: IOException) {
                fail("read failed: " + (e.message ?: e.javaClass.simpleName))
                throw e
            }
            if (n < 0) {
                val why = "the adapter closed the connection"
                fail(why)
                throw IOException(why)
            }
            if (n > 0) {
                used += n
                return true
            }
        }
        return false
    }

    private fun discard(n: Int) {
        System.arraycopy(buffer, n, buffer, 0, used - n)
        used -= n
    }

    /** Record what went wrong and drop the link, so the device frees it. */
    private fun fail(why: String) {
        lastFault = why
        closeLocked()
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
     * The greeting answer carries the serial as a little-endian u32 at 0 and
     * the firmware version as another at 28.
     *
     * The serial is a number whose **hexadecimal is the string**: the four
     * bytes `the four bytes` are 0x0-------, which written out is <serial>. This
     * used to be read as ASCII, and by an unhappy coincidence those same bytes
     * spell "DA4" followed by a newline, so the app showed a plausible-looking
     * serial that was three characters of a seven-character number. What
     * settles it is the adapter's own access point, which calls itself
     * `DIRECT-SCANMATIK-#<serial>`.
     *
     * The firmware needed no change: 17204 on that device, and the same
     * offset in the factory capture gives 17204 too.
     *
     * A short answer used to come out as two question marks. It now says so:
     * the greeting is 66 bytes and anything else means the read is wrong, which
     * is worth knowing rather than hiding.
     */
    private fun identity(reply: Frame.Message): Identity {
        val d = reply.data
        if (d.size < 32) throw IOException("the greeting answer was ${d.size} bytes, expected 66")
        val serial = (Frame.le32(d, 0).toLong() and 0xffffffffL)
            .toString(16).uppercase().trimStart('0')
        return Identity(serial, Frame.le32(d, 28).toString())
    }

    companion object {
        const val DEFAULT_HOST = "192.168.81.1"
        const val DEFAULT_PORT = 777
        private const val OP_FRAMES = 0xfe
        private const val OP_VOLTAGES = 0x20
        private const val VOLTAGES_LENGTH = 6

        /** How long one socket read blocks before the deadline is rechecked. */
        private const val READ_SLICE_MS = 250

        /** Long enough for a slow answer, short enough not to freeze a screen. */
        private const val STEP_TIMEOUT_MS = 2000L

        /** Saying goodbye is worth a moment, not a wait. */
        private const val CLOSE_TIMEOUT_MS = 400L
    }
}
