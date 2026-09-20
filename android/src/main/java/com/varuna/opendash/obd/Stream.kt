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
 * that come from thirteen identifiers. 0x150C alone is one byte holding eight
 * one-bit fields, which nineteen catalogue rows read from, and six of those rows
 * were on that screen.
 *
 * So a packet carries *identifiers*, and a parameter reads a slice of one. The
 * catalogue already describes the slice, in the shift-and-mask form of its
 * formula.
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

        /**
         * `AA 04 <packet> …` — start emitting them, in as many commands as it
         * takes. Five packet numbers is all that fits in one single frame.
         */
        fun start(): List<ByteArray> =
            packets.map { it.number.toByte() }
                .chunked(PACKETS_PER_START)
                .map { byteArrayOf(0xAA.toByte(), 0x04) + it.toByteArray() }
    }

    /**
     * Both the declaration and the start command travel as ISO-TP single
     * frames, and a single frame carries seven bytes.
     *
     * That is what caps a plan, and it was worth learning the hard way: an
     * over-long payload came out with a PCI byte promising nine bytes and only
     * seven behind it, the module dropped it, and from outside it looked
     * exactly like a module that would not start emitting.
     *
     * * `2C <packet> <id16>…` leaves room for [MAX_IDENTIFIERS] identifiers.
     *   GDS2's seven recorded declarations carry one or two, never three.
     * * `AA 04 <packet>…` leaves room for [PACKETS_PER_START] packets in one
     *   command. GDS2 started four.
     *
     * Those are limits on a *message*, and they were read as a limit on the
     * plan: five packets of two identifiers is ten, which is exactly where a
     * selection of forty-seven parameters stopped, the first ten reading and
     * the other thirty-seven never receiving a single sample.
     *
     * The protocol numbers seven packets, not five, and nothing says a second
     * `AA 04` cannot follow the first — so [start] is a list of commands rather
     * than one. That raises the ceiling to fourteen identifiers, which is
     * higher and still a ceiling: a packet carries seven bytes, so seven
     * packets hold forty-nine bytes of values however they are declared.
     * Anything past that is polled, by [Plan.leftOut] and the monitor.
     */
    /**
     * How many identifiers one declaration may name.
     *
     * Two was never a property of the packet — a packet holds seven bytes of
     * values — it was a property of the *request*: `2C <packet> <id16> <id16>`
     * is six bytes and an ISO-TP single frame carries seven, so the third
     * identifier needed the request split across frames.
     *
     * It does split now, and this module takes it. Measured at the car on 18
     * September: six identifiers accepted, all six emitted, every value at the
     * offset it should be, and 71 Hz against 72 for two — three times the
     * samples with no extra frame on the bus. Six is where it was measured and
     * also where the packet runs out, since the narrowest identifier is a byte.
     *
     * [SAFE_IDENTIFIERS] is the old ceiling, kept as the way back: a module
     * that will not reassemble a request still works, one packet at a time.
     */
    const val MAX_IDENTIFIERS = 6
    const val SAFE_IDENTIFIERS = 2
    const val PACKETS_PER_START = 5

    class Packet(val number: Int, val identifiers: List<Int>, val width: Int)

    /**
     * The same selection, split into rounds that are declared one after another.
     *
     * [rounds] each hold at most [packetsPerRound] packets; the monitor declares
     * one, lets the module emit it for a while, stops it and declares the next.
     * [leftOut] is what could not go in any round — an identifier wider than a
     * packet — and is polled as before.
     */
    class Rotation(val rounds: List<Plan>, val leftOut: List<Catalogue.Parameter>) {
        val isEmpty: Boolean get() = rounds.isEmpty()

        /** How many identifiers are live at once, across the first round. */
        val perRound: Int get() = rounds.firstOrNull()?.packets?.sumOf { it.identifiers.size } ?: 0
    }

    /**
     * Lay [parameters] out into rounds of at most [packetsPerRound] packets.
     *
     * ## Why rounds at all
     *
     * A packet is cheap to read and expensive to have. Measured on this engine:
     * five packets emit at about 96 Hz each — 480 frames a second, 960 samples
     * — and seven emit at about 51 Hz each, which is 357 frames and 715 samples.
     * Declaring two more packets therefore **lowers** the total. The module is
     * not dividing a fixed bandwidth; it is paying a price per packet.
     *
     * So the fast configuration holds ten identifiers, and a real selection is
     * larger. What used to happen is that fourteen streamed and the rest were
     * polled round-robin at a third of a hertz: on a five-minute drive the DPF
     * differential pressure got 82 readings and the accelerator got 14 897.
     *
     * Rotating instead keeps the module in its fast configuration and moves the
     * membership. Twenty-four parameters become three rounds of ten; with a
     * two-second dwell each parameter is live for two seconds in every six and a
     * bit, which averages about 30 Hz — a hundred times what the polled overflow
     * gave, and the aggregate stays near the ceiling instead of falling below it.
     *
     * What it costs is that a parameter is dark between its turns, so a
     * transient can fall in a gap. That is a real trade and the screen says
     * which round is live rather than hiding it.
     *
     * Identifiers are gathered first and parameters mapped onto them, so a
     * bit-packed byte is carried once however many rows read from it. Where two
     * parameters disagree about how wide their shared identifier is, the wider
     * one wins: reading too few bytes loses data, reading too many only wastes
     * room in the packet.
     */
    fun rotate(
        module: Int,
        parameters: List<Catalogue.Parameter>,
        packetsPerRound: Int = LAST_PACKET - FIRST_PACKET + 1,
        maxIdentifiers: Int = MAX_IDENTIFIERS,
        /**
         * Identifiers that ride in **every** round instead of taking their turn.
         *
         * Rotation buys its sample rate by leaving each parameter dark between
         * its turns -- about seven seconds in twelve on a two-round profile.
         * For almost everything that is a good trade. For the handful of things
         * that give every other reading its meaning it is not: road speed dark
         * for seven seconds is a hard braking event that did not happen as far
         * as the recording is concerned, and that is not a gap in one trace, it
         * is a hole in the drive.
         *
         * It was measured on the 119 km run. Seven separate speed drops fell
         * entirely inside a rotation gap, one of them 89 km/h down to 28 with
         * nothing at all in between -- so all that can be said about it is that
         * the deceleration was at least 1.84 m/s2, when the one event that did
         * land inside an emission turned out to be 0.93 g.
         *
         * The cost is real and worth stating: these take room out of every
         * round, so the rotating pool gets fewer packets. On this car it
         * happened to be free, because a round had a packet with three bytes
         * spare.
         */
        alwaysOn: Set<Int> = emptySet(),
    ): Rotation {
        val width = LinkedHashMap<Int, Int>()
        for (p in parameters) {
            if (p.pid !in 1..0xffff || p.bytes !in 1..PACKET_BYTES) continue
            width[p.pid] = maxOf(width[p.pid] ?: 0, p.bytes)
        }

        /** Pack identifiers into packets, without numbering them yet. */
        fun pack(ids: List<Int>): List<List<Int>> {
            val out = ArrayList<List<Int>>()
            var current = ArrayList<Int>()
            var used = 0
            for (id in ids) {
                val w = width[id] ?: continue
                if (used + w > PACKET_BYTES || current.size >= maxIdentifiers) {
                    if (current.isNotEmpty()) out.add(current.toList())
                    current = ArrayList()
                    used = 0
                }
                current.add(id)
                used += w
            }
            if (current.isNotEmpty()) out.add(current.toList())
            return out
        }

        // The pinned ones first, because they are in every round and therefore
        // decide how much room is left for everything else.
        val pinnedIds = width.keys.filter { it in alwaysOn }
        val pinned = pack(pinnedIds)
        val bundles = pack(width.keys.filter { it !in alwaysOn })

        val packetCap = packetsPerRound.coerceIn(1, LAST_PACKET - FIRST_PACKET + 1)

        // Nothing to lay out is not an edge case to be clever about: an
        // empty selection reaches here, and a loop that divides by the number
        // of rounds takes the whole app down rather than returning nothing.
        if (bundles.isEmpty() && pinned.isEmpty()) return Rotation(emptyList(), parameters)

        val bytesOf = { ids: List<Int> -> ids.sumOf { width[it] ?: 0 } }
        val pinnedBytes = pinned.sumOf(bytesOf)
        // What one round has left once the pinned packets have taken theirs.
        val byteBudget = (STREAM_BYTES - pinnedBytes).coerceAtLeast(1)
        val packetBudget = (packetCap - pinned.size).coerceAtLeast(1)

        // Evenly, not greedily. Twelve packets in fives is 5, 5, 2 -- a last
        // round a fifth the size of the others, holding four parameters that
        // then get the same dwell as the ten in the first. Three rounds of four
        // is the same total and the same number of switches, and every
        // parameter is live for the same share of the time.
        fun evenly(into: Int): List<List<List<Int>>> {
            val out = ArrayList<List<List<Int>>>()
            var from = 0
            for (i in 0 until into) {
                val take = (bundles.size - from + (into - i - 1)) / (into - i)
                out.add(bundles.subList(from, from + take).toList())
                from += take
            }
            return out
        }

        // Then grow the number of rounds until every one of them fits. Both
        // limits are real and neither is the one this used to assume: a round
        // holds at most seven packets, and at most STREAM_BYTES of declared
        // payload across them.
        var count = 1
        var slices = evenly(1)
        while (count < maxOf(1, bundles.size)) {
            val fits = slices.all { it.size <= packetBudget && it.sumOf(bytesOf) <= byteBudget }
            if (fits) break
            count++
            slices = evenly(count)
        }

        val rounds = ArrayList<Plan>()
        val placed = HashSet<Int>()
        for (group in slices) {
            // Pinned packets go first so they keep the same numbers in every
            // round. That is not tidiness: the module is told to stop and the
            // packets are redeclared at each switch, and a reader that has just
            // seen packet 0xF8 mean one thing should not find it meaning
            // another a frame later.
            val packets = ArrayList<Packet>()
            val where = HashMap<Int, Pair<Int, Int>>()
            (pinned + group).forEachIndexed { i, ids ->
                val number = FIRST_PACKET + i
                var offset = 0
                for (id in ids) {
                    where[id] = number to offset
                    offset += width[id] ?: 1
                }
                packets.add(Packet(number, ids, offset))
            }
            val fields = ArrayList<Field>()
            for (p in parameters) {
                val at = where[p.pid] ?: continue
                fields.add(Field(p, at.first, at.second, width[p.pid] ?: p.bytes))
                placed.add(p.pid)
            }
            rounds.add(Plan(module, packets, fields, emptyList()))
        }
        val leftOut = parameters.filter { it.pid !in placed }
        return Rotation(rounds, leftOut)
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
     * How much declared payload one round can hold, across all its packets.
     *
     * **This, and not the packet count, is what the module limits.** Measured
     * on 20/09/2026 with our own client, declaring and reading for real:
     *
     * ```
     *  packets  ids each  total  Hz each  frames/s  samples/s
     *        1         6      6     99.6       100        598
     *        3         6     18    100.0       300      1 801
     *        5         6     30     99.9       500      2 998
     *        7         5     35     99.6       697      3 485
     *        6         6     36        rejected, 7F 2C 31
     * ```
     *
     * Seven packets emit as fast as one; 7x5 = 35 bytes is accepted and
     * 6x6 = 36 is refused at the declaration of the packet that overruns. A
     * capture of GDS2 the same day agrees from the outside: seven packets at
     * 97.6 Hz each, 683 frames a second.
     *
     * The earlier measurement said seven packets fell to 51 Hz and that the
     * module charged a price per packet. That was this side not reading fast
     * enough, not the module -- and the whole of the rotation was built on it:
     * five packets a round, the dwell, and the seven-second gaps that swallowed
     * a braking event whole.
     *
     * With one-byte identifiers this is **35 live at once** instead of the 30
     * that five packets of six allowed.
     */
    const val STREAM_BYTES = 35

    /**
     * The packet numbers the factory tool used, 0xF8 through 0xFE. Seven of
     * them, all seen emitting in the same session.
     */
    const val FIRST_PACKET = 0xF8
    const val LAST_PACKET = 0xFE
}
