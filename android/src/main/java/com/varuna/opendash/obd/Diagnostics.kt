package com.varuna.opendash.obd

import com.varuna.opendash.data.Catalogue
import com.varuna.opendash.protocol.IsoTp
import com.varuna.opendash.protocol.Sm3Client

/**
 * Request and response over the bus, with the segmentation handled.
 *
 * Everything here is a read. Nothing writes to a module, clears a code or runs
 * a routine, and that is deliberate: this is a tool for looking at a car, and
 * the failure modes of the other kind are expensive.
 */
class Diagnostics(private val sm3: Sm3Client) {

    private val isotp = IsoTp()

    /**
     * Send [payload] to [txId] and wait for the answer from the matching
     * response id. Returns null on timeout.
     *
     * The waiting is done by reading, not by asking. This used to send a poll
     * every ten milliseconds until the answer turned up, on the assumption
     * that frames have to be fetched; they do not. Over the factory session
     * the adapter pushed 226 121 frame blocks unasked against 1744 polls the
     * tool sent — 130 pushed for every one requested — and the polls came at a
     * median of one a second. So a request now reads the socket in short
     * slices and polls at [POLL_EVERY_MS], which is a twentieth of what it
     * did and still four times what the manufacturer tool ever sustains.
     *
     * Worth being precise about why this changed. It is not proven that the
     * old rate is what dropped the link; what is measured is that it was some
     * ninety times anything in the capture, that it was unnecessary, and that
     * reading the socket only while a poll was outstanding left everything the
     * device pushed in between sitting unread.
     */
    /**
     * Services this tool is allowed to emit. All of them read.
     *
     * This is a hard stop, not a convention. The catalogue extracted from GDS2
     * lists 2517 parameters with "Command" in the name, 879 with "Test" and 782
     * with "Learn": in the factory tool those can be commanded as well as read,
     * and the same identifier that reports a relay state can also close it.
     * Reading them is harmless; writing them moves things on a car with someone
     * in it. So the write services never leave here.
     *
     * 0x2E WriteDataByIdentifier, 0x2F InputOutputControl, 0x31 RoutineControl,
     * 0x14 ClearDiagnosticInformation and 0x27 SecurityAccess are absent on
     * purpose. Adding one is a decision, not a patch.
     *
     * 0x2C is here, and it is the one that deserves its own sentence, because
     * it is the only service on this list that changes anything in a module
     * rather than only reading it. What it changes is which parameters that
     * module will emit: it declares a packet, in RAM, that lasts as long as the
     * session. Nothing is written to the car, nothing is calibrated, nothing
     * moves. It is what the manufacturer's own tool sends every time somebody
     * opens a live data screen — the captured session has seven of them — and
     * without it there is no way to read more than a handful of parameters a
     * second, which is not enough to watch a rail pressure while driving.
     */
    private val readServices =
        setOf(0x01, 0x02, 0x03, 0x06, 0x07, 0x09, 0x19, 0x1A, 0x22, 0x2C, 0xAA, 0x3E)

    private fun refuseIfNotRead(payload: ByteArray) {
        val service = payload.firstOrNull()?.toInt()?.and(0xff) ?: return
        // A flow-control frame is transport, not a service.
        if (service and 0xf0 == 0x30) return
        require(service in readServices) {
            "refusing to send service 0x" + service.toString(16) + ": this tool only reads"
        }
    }

    fun request(
        txId: Int,
        payload: ByteArray,
        timeoutMs: Long = 1500,
        rxId: Int = responseIdFor(txId),
    ): ByteArray? {
        refuseIfNotRead(payload)
        isotp.reset()
        sm3.drain()
        sm3.send(txId, payload)

        val deadline = System.currentTimeMillis() + timeoutMs
        var flowControlSent = false
        var nextPoll = System.currentTimeMillis() + POLL_EVERY_MS
        while (System.currentTimeMillis() < deadline) {
            // Blocks for the slice, so this is a read and not a spin.
            sm3.receive(minOf(SLICE_MS, deadline - System.currentTimeMillis()))
            if (System.currentTimeMillis() >= nextPoll) {
                sm3.poll()
                nextPoll = System.currentTimeMillis() + POLL_EVERY_MS
            }
            for (frame in sm3.drain()) {
                if (frame.id != rxId && rxId != ANY) continue
                val pci = (frame.data.getOrNull(0)?.toInt() ?: 0) and 0xf0
                val done = isotp.push(frame.id, frame.data)
                if (done != null) return done
                // A multi-frame answer stalls until the tester says go ahead.
                if (pci == 0x10 && !flowControlSent) {
                    sm3.send(txId, IsoTp.FLOW_CONTROL)
                    flowControlSent = true
                }
            }
        }
        return null
    }

    /** Mode 01. Returns the data after the echoed mode and pid. */
    fun mode01(pid: Int, txId: Int = FUNCTIONAL): ByteArray? {
        val r = request(txId, byteArrayOf(0x01, pid.toByte()), rxId = ANY) ?: return null
        if (r.size < 2 || (r[0].toInt() and 0xff) != 0x41) return null
        return r.copyOfRange(2, r.size)
    }

    /**
     * Service 0x22, ReadDataByIdentifier: the two-byte identifiers.
     *
     * The catalogue extracted from GDS2 lists 4 993 distinct identifiers, and
     * 4 775 of them do not fit in a byte, so mode 01 cannot reach them. The
     * GDS2 capture contains exactly one 0x22 request — `22 F8 02`, which the
     * catalogue lists as the VIN — and that is enough to fix the request form:
     * the service byte, then the identifier big-endian.
     *
     * What that one example does not establish is coverage. GDS2 reads almost
     * everything else by defining a dynamic packet with service 0x2C and
     * streaming it, so most of these identifiers have never been seen answered
     * one at a time. A module is free to refuse, and refusing costs a timeout
     * and nothing else, so the honest thing is to ask and report the answer
     * rather than to pretend the list is unreachable.
     *
     * The timeout is short on purpose: an identifier that is not supported
     * should cost a fraction of a second, not a second and a half.
     */
    fun readDataByIdentifier(id: Int, txId: Int = ENGINE, timeoutMs: Long = 600): ByteArray? {
        val payload = byteArrayOf(0x22, (id shr 8).toByte(), id.toByte())
        val r = request(txId, payload, timeoutMs = timeoutMs) ?: return null
        if (r.size < 3 || (r[0].toInt() and 0xff) != 0x62) return null
        // The identifier is echoed before the data.
        if (((r[1].toInt() and 0xff) shl 8 or (r[2].toInt() and 0xff)) != id) return null
        return r.copyOfRange(3, r.size)
    }

    /** The supported-PID bitmasks, four requests instead of ninety-six. */
    fun supportedPids(): Set<Int> = withTesterPresent {
        val masks = HashMap<Int, ByteArray>()
        for (base in listOf(0x00, 0x20, 0x40, 0x60)) {
            val m = mode01(base) ?: break
            masks[base] = m
            // bit 0 of the last byte says whether the next block exists
            if (m.size < 4 || (m[3].toInt() and 0x01) == 0) break
        }
        Pids.supported(masks)
    }

    fun readiness(): Readiness? = mode01(0x01)?.let { Readiness(it) }

    /**
     * Stored fault codes, mode 03. Read only — clearing them is service 0x14,
     * which this tool does not emit.
     *
     * Answered functionally, so every module that has something to say does,
     * and the same code can arrive from more than one of them.
     */
    fun storedFaults(): List<Dtc> = faults(0x03, pending = false)

    /**
     * Pending codes, mode 07: seen once but not yet often enough to light the
     * lamp. Worth reading precisely because they do not show anywhere else.
     */
    fun pendingFaults(): List<Dtc> = faults(0x07, pending = true)

    private fun faults(mode: Int, pending: Boolean): List<Dtc> {
        val r = request(FUNCTIONAL, byteArrayOf(mode.toByte()), rxId = ANY) ?: return emptyList()
        // 43 <count> <pairs...> on a segmented answer, 43 <pairs...> on a short one.
        if (r.isEmpty() || (r[0].toInt() and 0xff) != mode + 0x40) return emptyList()
        val body = if (r.size > 1 && r.size % 2 == 0) r.copyOfRange(2, r.size)
        else r.copyOfRange(1, r.size)
        return decodeDtcs(body, pending)
    }

    /** Mode 09 PID 02: the VIN, as the engine reports it. */
    fun vin(): String? {
        val r = request(FUNCTIONAL, byteArrayOf(0x09, 0x02), rxId = ANY) ?: return null
        if (r.size < 3) return null
        // 49 02 <count> then the characters
        return String(r.copyOfRange(3, r.size), Charsets.US_ASCII).trim { it <= ' ' }
    }

    /**
     * GM service 0x1A: how the car says what it is. This is what GDS2 asks,
     * and it beats any make-and-model menu — it works on a swapped engine.
     */
    fun identify(txId: Int = ENGINE): VehicleId = withTesterPresent(txId) {
        fun read(localId: Int): String? {
            val r = request(txId, byteArrayOf(0x1A, localId.toByte())) ?: return null
            if (r.size < 3 || (r[0].toInt() and 0xff) != 0x5A) return null
            return String(r.copyOfRange(2, r.size), Charsets.US_ASCII).trim { it <= ' ' }
        }
        VehicleId(
            vin = read(0x90),
            system = read(0x92),
            engine = read(0x97),
            calibration = read(0x98),
            moduleSerial = read(0xB4),
        )
    }

    /**
     * Run [block] while sending TesterPresent to [module] every two seconds.
     *
     * A module that has been put into a non-default session drops back out of
     * it after a few seconds of silence, and a sweep that walks a hundred
     * identifiers is easily silent that long between two of them. This keeps
     * the session alive underneath it.
     *
     * The heartbeat goes to the module being addressed, not always to the
     * engine: telling the engine the tester is present says nothing about a
     * session opened on the body module, and the sweep that most needs this is
     * the one walking identifiers on something else.
     *
     * Each beat is one exchange under the client's lock, so it cannot land in
     * the middle of another request — which is exactly the arrangement
     * TesterPresent is designed for.
     */
    private fun <T> withTesterPresent(module: Int = ENGINE, block: () -> T): T {
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val hb = kotlin.concurrent.thread(name = "tester-present", isDaemon = true) {
            while (running.get()) {
                try {
                    Thread.sleep(TESTER_PRESENT_MS)
                    if (running.get()) sm3.send(module, byteArrayOf(0x3E, 0x00))
                } catch (_: InterruptedException) {
                    return@thread
                } catch (_: Exception) {
                    // A dead link is the caller's problem to report, not this
                    // thread's; it will find out on its own next request.
                }
            }
        }
        return try {
            block()
        } finally {
            running.set(false)
            hb.interrupt()
        }
    }

    // ── live data, the way the factory tool reads it ──────────────────────

    /**
     * Declare [plan]'s packets and set the module emitting them.
     *
     * Returns false if a declaration was refused, in which case nothing is
     * emitting and there is nothing to stop. Each declaration is answered
     * `6C <packet>`. The start is not answered at all in the captures — the
     * emission is the answer — so it goes out without waiting, and whether it
     * worked is answered by frames arriving, which is the only honest test.
     */
    fun beginStream(plan: Stream.Plan): Boolean {
        if (plan.isEmpty) return false
        for (declaration in plan.declarations()) {
            val r = request(plan.module, declaration, timeoutMs = STREAM_SETUP_MS)
            if (r == null || (r[0].toInt() and 0xff) != 0x6C) return false
        }
        fire(plan.module, plan.start())
        return true
    }

    /**
     * Stop a module emitting.
     *
     * Sent on the way out of live data and again in the session teardown. A
     * module left emitting keeps a hundred frames a second coming at an adapter
     * nobody is reading any more, and the next tool to connect finds a bus busy
     * with somebody else's packets.
     */
    fun endStream(module: Int = ENGINE) {
        try {
            fire(module, Stream.STOP)
        } catch (_: Exception) {
            // A link already gone cannot be told to stop, and saying so here
            // would replace a useful error with a useless one.
        }
    }

    /**
     * Collect whatever the module has emitted since the last call.
     *
     * Reads the socket for [sliceMs] and hands back the decoded values. The
     * emitted frames are not ISO-TP: each is one packet, whole, so there is
     * nothing to reassemble and nothing to acknowledge.
     */
    fun readStream(plan: Stream.Plan, sliceMs: Long = 50): List<Pair<Catalogue.Parameter, Double>> {
        sm3.receive(sliceMs)
        val rxId = responseIdFor(plan.module)
        val out = ArrayList<Pair<Catalogue.Parameter, Double>>()
        for (frame in sm3.drain()) {
            if (rxId != ANY && frame.id != rxId) continue
            out.addAll(Stream.decode(plan, frame.data))
        }
        return out
    }

    /** Send without waiting for an answer, still refusing anything that writes. */
    private fun fire(txId: Int, payload: ByteArray) {
        refuseIfNotRead(payload)
        sm3.send(txId, payload)
    }

    /** One identifier, as a module answered it. */
    class Identification(
        val module: Int,
        val id: Int,
        val label: String,
        val bytes: ByteArray,
    ) {
        val hex: String get() = Identifiers.hex(bytes)
    }

    /**
     * Read one local identifier. Null when the module does not answer or
     * refuses, which is most of them for most identifiers.
     *
     * The timeout is short because a sweep is mostly misses: a module answers
     * the dozen identifiers it implements and ignores the rest, and at a second
     * and a half each that would take a quarter of an hour.
     */
    fun readLocalIdentifier(id: Int, txId: Int = ENGINE, timeoutMs: Long = 400): ByteArray? {
        val r = request(txId, byteArrayOf(0x1A, id.toByte()), timeoutMs = timeoutMs) ?: return null
        if (r.size < 2 || (r[0].toInt() and 0xff) != 0x5A) return null
        if ((r[1].toInt() and 0xff) != id) return null
        return r.copyOfRange(2, r.size)
    }

    /**
     * Which of [MODULES] are on the bus, found by asking each for its VIN.
     *
     * Every module in the factory capture answered `1A 90`, including the ones
     * that returned zeros, so it makes a cheap liveness probe: one short
     * request each rather than a full sweep of something that is not there.
     */
    fun modulesPresent(onProgress: (Int) -> Unit = {}): List<Int> = withTesterPresent {
        MODULES.filter { module ->
            onProgress(module)
            readLocalIdentifier(0x90, module, timeoutMs = 300) != null
        }
    }

    /**
     * Everything [module] will say about itself.
     *
     * Read-only by construction — 0x1A is in [readServices] and nothing here
     * writes — so this is safe to run on a car with the engine running, and it
     * is the only way to find out what the unlabelled identifiers hold: the
     * catalogue names 50 of the 58 the factory tool asks, and the rest come
     * back as bytes with an honest blank beside them.
     */
    fun identification(
        module: Int = ENGINE,
        stop: () -> Boolean = { false },
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): List<Identification> = withTesterPresent(module) {
        val out = ArrayList<Identification>()
        for ((index, entry) in Identifiers.all.withIndex()) {
            if (stop()) break
            onProgress(index, Identifiers.all.size)
            val bytes = readLocalIdentifier(entry.id, module) ?: continue
            if (bytes.isEmpty()) continue
            out.add(Identification(module, entry.id, entry.label, bytes))
        }
        out
    }

    class VehicleId(
        val vin: String?,
        val system: String?,
        val engine: String?,
        val calibration: String?,
        val moduleSerial: String?,
    ) {
        val known: Boolean get() = !vin.isNullOrBlank() || !engine.isNullOrBlank()
    }

    companion object {
        /**
         * How long one read blocks before the loop looks at what arrived.
         * Short enough that a flow control frame goes out well inside the
         * second an ISO-TP sender waits for one.
         */
        private const val SLICE_MS = 50L

        /**
         * How often a request pokes the adapter while waiting. The factory
         * tool sends one poll a second at the median and never sustains more
         * than 27 in a second; four a second stays inside that and is a
         * twentieth of what this used to do.
         */
        private const val POLL_EVERY_MS = 250L

        /** How often a long operation says the tester is still here. */
        private const val TESTER_PRESENT_MS = 2000L

        /** A packet declaration is a short exchange; it either lands or it does not. */
        private const val STREAM_SETUP_MS = 1000L

        /** OBD functional request: every module that listens answers. */
        const val FUNCTIONAL = 0x7DF
        /** The engine, addressed directly. */
        const val ENGINE = 0x7E0
        /** Accept whichever module replies. */
        const val ANY = -1

        /**
         * Modules worth asking, request-side.
         *
         * The engine over the OBD pair, then the GM range. The factory capture
         * only ever addressed 0x242, 0x243, 0x249 and 0x254 and saw answers
         * from 0x241, 0x242, 0x243, 0x244, 0x247, 0x24A, 0x24C, 0x24D and
         * 0x251 — different sets, because it also listened to modules it never
         * asked. Neither is the whole list, so the whole range is swept: a
         * module that is not there costs one short timeout.
         */
        /**
         * Where to look for modules.
         *
         * The report the factory tool produced for this car names eighteen
         * modules and every one of them answered — its "No Communication"
         * section is empty. Their addresses, taken from the catalogue, are
         * 0x241 through 0x25F plus 0x7E0 and 0x7E5, so the powertrain range is
         * swept whole rather than only its first address.
         */
        val MODULES: List<Int> = (0x7E0..0x7E7).toList() + (0x241..0x25F).toList()

        /** GM keeps request and response 0x400 apart; OBD uses 0x7E0/0x7E8. */
        fun responseIdFor(txId: Int): Int = when {
            txId == FUNCTIONAL -> ANY
            txId in 0x7E0..0x7E7 -> txId + 8
            txId in 0x240..0x25F -> txId + 0x400
            else -> ANY
        }
    }
}
