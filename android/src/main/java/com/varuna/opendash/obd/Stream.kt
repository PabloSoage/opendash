package com.varuna.opendash.obd

import com.varuna.opendash.data.Catalogue

/**
 * Live data the way the manufacturer's tool reads it.
 *
 * Asking for one parameter at a time and waiting for each answer costs a round
 * trip apiece — twenty to forty milliseconds on this car — so ten parameters
 * refresh four or five times a second between them. That is enough to watch a
 * coolant temperature and nowhere near enough to watch a rail pressure while
 * driving.
 *
 * GDS2 does not do that. It **declares a packet** with service 0x2C, naming the
 * identifiers it wants and the order it wants them in, then tells the module to
 * **emit** that packet with service 0xAA. The module then sends it unasked, at
 * about a hundred frames a second, until it is told to stop. Measured over the
 * factory session: 480 requests from the tester against 167 447 frames pushed
 * back, across seven packets.
 *
 * The whole shape of it is fixed by the captures and by one decode against the
 * car. `2C FD 00 0C 00 05` declares packet 0xFD holding identifier 0x000C then
 * 0x0005, and the frames that follow carry the engine speed in bytes 1 and 2
 * divided by four and the coolant temperature in byte 3 minus forty — which is
 * what the car showed. So:
 *
 * * identifiers are two bytes, and they are exactly the `pid` column of the
 *   catalogue extracted from GDS2;
 * * a frame is the packet number followed by the values, concatenated in the
 *   order declared, seven bytes of room;
 * * packets are numbered from [FIRST_PACKET] upward.
 *
 * ## One identifier can be several parameters
 *
 * Checked against a screenshot of GDS2 taken while the capture was running: its
 * "Cruise Control, PTO and Traction Control Data" screen shows eighteen rows
 * that come from thirteen identifiers, because 0x150C is a single byte holding
 * seven switches and six of those rows are six of its bits. So a packet carries
 * *identifiers*, and a parameter reads a slice of one. The catalogue already
 * describes the slice, in the shift-and-mask form of its formula.
 */
object Stream {

    /** Where in a packet a parameter's bytes live, and how to scale them. */
    class Field(
        val parameter: Catalogue.Parameter,
        val packet: Int,
        val offset: Int,
        val width: Int,
    )

    /**
     * A set of packets to declare, and where every parameter reads from.
     *
     * [leftOut] are the parameters that did not fit. Reporting them beats
     * silently dropping them: a screen can say "these four are not in the
     * stream" instead of showing four rows that never move.
     */
    class Plan(
        val module: Int,
        val packets: List<Packet>,
        val fields: List<Field>,
        val leftOut: List<Catalogue.Parameter>,
    ) {
        val isEmpty: Boolean get() = packets.isEmpty()

        /** `2C <packet> <id hi> <id lo> …`, one per packet. */
        fun declarations(): List<ByteArray> = packets.map { p ->
            val out = ByteArray(2 + p.identifiers.size * 2)
            out[0] = 0x2C
            out[1] = p.number.toByte()
            p.identifiers.forEachIndexed { i, id ->
                out[2 + i * 2] = (id shr 8).toByte()
                out[3 + i * 2] = id.toByte()
            }
            out
        }

        /** `AA 04 <packet> …` — start emitting all of them. */
        fun start(): ByteArray =
            byteArrayOf(0xAA.toByte(), 0x04) + ByteArray(packets.size) { packets[it].number.toByte() }
    }

    class Packet(val number: Int, val identifiers: List<Int>, val width: Int)

    /**
     * Lay [parameters] out into packets for [module].
     *
     * Identifiers are gathered first and parameters mapped onto them, so a
     * bit-packed byte is carried once however many rows read from it. Widths
     * come from the catalogue; where two parameters disagree about how wide
     * their shared identifier is, the wider one wins, because reading too few
     * bytes loses data and reading too many only wastes room in the packet.
     */
    fun plan(module: Int, parameters: List<Catalogue.Parameter>): Plan {
        val width = LinkedHashMap<Int, Int>()
        for (p in parameters) {
            if (p.pid !in 1..0xffff || p.bytes !in 1..PACKET_BYTES) continue
            width[p.pid] = maxOf(width[p.pid] ?: 0, p.bytes)
        }

        val packets = ArrayList<Packet>()
        val where = HashMap<Int, Pair<Int, Int>>()      // identifier to packet and offset
        var current = ArrayList<Int>()
        var used = 0
        var number = FIRST_PACKET

        fun close() {
            if (current.isEmpty()) return
            packets.add(Packet(number, current.toList(), used))
            number++
            current = ArrayList()
            used = 0
        }

        for ((id, w) in width) {
            if (number > LAST_PACKET) break
            if (used + w > PACKET_BYTES) {
                close()
                if (number > LAST_PACKET) break
            }
            where[id] = number to used
            current.add(id)
            used += w
        }
        close()

        val fields = ArrayList<Field>()
        val leftOut = ArrayList<Catalogue.Parameter>()
        for (p in parameters) {
            val at = where[p.pid]
            if (at == null) leftOut.add(p)
            else fields.add(Field(p, at.first, at.second, width[p.pid] ?: p.bytes))
        }
        return Plan(module, packets, fields, leftOut)
    }

    /**
     * Read one emitted frame. Returns the parameters it carried and their
     * scaled values, or nothing if the frame belongs to another packet.
     */
    fun decode(plan: Plan, frame: ByteArray): List<Pair<Catalogue.Parameter, Double>> {
        if (frame.isEmpty()) return emptyList()
        val packet = frame[0].toInt() and 0xff
        val out = ArrayList<Pair<Catalogue.Parameter, Double>>()
        for (f in plan.fields) {
            if (f.packet != packet) continue
            // Byte 0 is the packet number, so the payload starts at one.
            val at = 1 + f.offset
            if (at + f.width > frame.size) continue
            var raw = 0L
            for (i in 0 until f.width) raw = (raw shl 8) or (frame[at + i].toLong() and 0xff)
            val value = f.parameter.scale(raw) ?: raw.toDouble()
            out.add(f.parameter to value)
        }
        return out
    }

    /** Stop everything this module is emitting. */
    val STOP: ByteArray = byteArrayOf(0xAA.toByte(), 0x00)

    /**
     * Seven bytes of room: a CAN frame is eight and the first is the packet
     * number.
     */
    const val PACKET_BYTES = 7

    /**
     * The packet numbers the factory tool used, 0xF8 through 0xFE. Seven of
     * them, all seen emitting in the same session.
     */
    const val FIRST_PACKET = 0xF8
    const val LAST_PACKET = 0xFE
}
