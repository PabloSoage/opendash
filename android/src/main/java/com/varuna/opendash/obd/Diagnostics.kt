package com.varuna.opendash.obd

import com.varuna.opendash.data.Catalogue
import com.varuna.opendash.protocol.IsoTp
import com.varuna.opendash.protocol.Sm3Client

/**
 * Request and response over the bus, with the segmentation handled.
 *
 * Almost everything here is a read. Nothing writes to a module, clears a code
 * or runs a routine, and that is deliberate: this is a tool for looking at a
 * car, and the failure modes of the other kind are expensive.
 *
 * The exception is GMLAN DeviceControl, which is refused unless the device lock
 * has been answered. See [Actuation], and see [refuseIfNotRead] for the
 * services that stay refused either way.
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
     * purpose. Adding one is a decision, not a patch. 0xAE was such a decision
     * and is handled separately below: it is not on this list, and is allowed
     * only while the device lock says so.
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

    /**
     * The one service that may be added to [readServices], and only while the
     * device lock says so. See [Actuation] for what it is and what it is not.
     *
     * Note which services are still absent, and stay absent whether or not
     * anything is unlocked: 0x3B WriteDataByIdentifier, 0x27 SecurityAccess,
     * 0x28 DisableNormalCommunication, 0xA5 ProgrammingMode, 0x34/0x36
     * RequestDownload and TransferData. Those write to a module or put it in a
     * state it has to be programmed out of. A lock is the wrong control for
     * them; not implementing them is the right one.
     */
    private fun refuseIfNotRead(payload: ByteArray) {
        val service = payload.firstOrNull()?.toInt()?.and(0xff) ?: return
        // A flow-control frame is transport, not a service.
        if (service and 0xf0 == 0x30) return
        if (service == Actuation.SERVICE) {
            // `AE 00` hands everything back and cannot drive anything, so it
            // is never locked out: the way out of a held output must not
            // depend on the lock that let it be held.
            if (payload.contentEquals(Actuation.RETURN_ALL)) return
            require(Actuation.unlocked) {
                "refusing to command: actuation is locked"
            }
            return
        }
        require(service in readServices) {
            "refusing to send service 0x" + service.toString(16) + ": this tool only reads"
        }
    }

    /**
     * Hand every device back to [module], and say whether it agreed.
     *
     * The only command this app sends on its own. It is worth having before
     * there is anything to command with it: a module left holding a device
     * keeps holding it, and the way out is this or an ignition cycle.
     *
     * Not gated on [Actuation.unlocked] being checked here — [request] does
     * that — so the failure is the same honest exception as any other refusal
     * rather than a silent no-op.
     */
    fun releaseControl(module: Int = ENGINE): Boolean {
        val answer = request(module, Actuation.RETURN_ALL) ?: return false
        return answer.firstOrNull()?.toInt()?.and(0xff) == Actuation.POSITIVE
    }

    /**
     * Send a request too long for one frame, in ISO-TP.
     *
     * Until today nothing here needed it: every read is two or three bytes. A
     * packet declaration is what needs it — `2C <packet> <id16>…` is six bytes
     * for two identifiers and eight for three, and a single frame carries
     * seven. That ceiling is why a packet used to carry two values when it has
     * room for seven bytes of them.
     *
     * Measured against this module on 18 September: it accepts up to six
     * identifiers in a packet, emits all six, every value lands at the offset
     * it should, and the frame rate does not drop — 71 Hz against 72 for two.
     * Three times the samples per second without one extra frame on the bus.
     *
     *  - first frame `1L LL` and six bytes, where LLL is the total length;
     *  - the module answers a flow control `30 BS ST` on its own address;
     *  - consecutive frames `2N` and seven bytes, N counting 1..15 and round.
     *
     * Returns false rather than throwing when the module never clears us to
     * send: a declaration that is not acknowledged is an ordinary refusal and
     * the caller already knows what to do with one.
     */
    private fun sendSegmented(txId: Int, payload: ByteArray, rxId: Int): Boolean {
        val first = ByteArray(8)
        first[0] = (0x10 or ((payload.size shr 8) and 0x0f)).toByte()
        first[1] = (payload.size and 0xff).toByte()
        payload.copyInto(first, 2, 0, 6)
        sm3.sendFrame(txId, first)

        // Wait to be cleared. Sending the rest blind is what makes a module
        // discard the whole reassembly and answer no for the wrong reason.
        val accepts: (Int) -> Boolean =
            if (rxId != ANY) ({ it == rxId }) else ({ isDiagnosticReply(it) })
        var separation = 0L
        var cleared = false
        val until = System.currentTimeMillis() + FLOW_CONTROL_MS
        while (!cleared && System.currentTimeMillis() < until) {
            sm3.receive(minOf(SLICE_MS, until - System.currentTimeMillis()))
            for (frame in sm3.drain()) {
                if (!accepts(frame.id)) continue
                val pci = frame.data.getOrNull(0)?.toInt()?.and(0xff) ?: continue
                if (pci and 0xf0 != 0x30) continue
                when (pci and 0x0f) {
                    // Wait: the module is not ready, keep waiting.
                    1 -> continue
                    0 -> {
                        val st = frame.data.getOrNull(2)?.toInt()?.and(0xff) ?: 0
                        // Under 0x80 the separation is milliseconds; above it
                        // is microseconds, and a millisecond covers all of it.
                        separation = if (st <= 0x7f) st.toLong() else 1L
                        cleared = true
                    }
                    // Overflow: the module cannot hold a message this long.
                    else -> return false
                }
                if (cleared) break
            }
        }
        if (!cleared) return false

        var index = 1
        var at = 6
        while (at < payload.size) {
            val cf = ByteArray(8)
            cf[0] = (0x20 or (index and 0x0f)).toByte()
            val take = minOf(7, payload.size - at)
            payload.copyInto(cf, 1, at, at + take)
            sm3.sendFrame(txId, cf)
            at += take
            index++
            if (separation > 0) Thread.sleep(separation)
        }
        return true
    }

    /**
     * Emitted packet frames that a request read past while looking for its own
     * answer, kept for the stream reader instead of being thrown away.
     *
     * A request drains the adapter's queue and keeps only its answer. That was
     * harmless while nothing else was arriving, and it is not while a module is
     * emitting seven hundred frames a second: every polled request in the
     * middle of a stream, every heartbeat and every declaration used to discard
     * the twenty to forty milliseconds of packets that arrived while it waited,
     * and a parameter polled every 300 ms beside the stream cost the stream a
     * tenth of its readings without anything on screen saying so.
     *
     * Only kept while a stream is running, and bounded, so a module left
     * emitting with nobody reading cannot grow this without limit.
     */
    private val emitted = java.util.concurrent.ConcurrentLinkedQueue<Sm3Client.CanFrame>()

    @Volatile private var keepEmitted = false

    private fun spill(frame: Sm3Client.CanFrame) {
        if (!keepEmitted || !isEmitted(frame.id)) return
        if (emitted.size >= EMITTED_KEPT) emitted.poll()
        emitted.add(frame)
    }

    private fun spill(frames: List<Sm3Client.CanFrame>, from: Int = 0) {
        for (i in from until frames.size) spill(frames[i])
    }

    /**
     * The other direction: answers the stream reader read past while a request
     * on another thread was waiting for them.
     *
     * Commanding an actuator while the live view streams is two threads on
     * one socket, and each drains what arrives. Without this the reader
     * would swallow the module's `EE` and the command would be reported as
     * unanswered although the valve had moved -- which is the one mistake an
     * actuator screen must not make.
     */
    private val replies = java.util.concurrent.ConcurrentLinkedQueue<Sm3Client.CanFrame>()

    @Volatile private var waiting = false

    /** One request at a time, whichever thread it comes from. */
    private val asking = java.util.concurrent.locks.ReentrantLock()

    fun request(
        txId: Int,
        payload: ByteArray,
        timeoutMs: Long = 1500,
        rxId: Int = responseIdFor(txId),
    ): ByteArray? {
        refuseIfNotRead(payload)
        asking.lock()
        try {
            replies.clear()
            waiting = true
            return exchange(txId, payload, timeoutMs, rxId)
        } finally {
            waiting = false
            asking.unlock()
        }
    }

    private fun exchange(txId: Int, payload: ByteArray, timeoutMs: Long, rxId: Int): ByteArray? {
        isotp.reset()
        spill(sm3.drain())
        if (payload.size <= SINGLE_FRAME_BYTES) {
            sm3.send(txId, payload)
        } else if (!sendSegmented(txId, payload, rxId)) {
            return null
        }

        val deadline = System.currentTimeMillis() + timeoutMs
        var flowControlSent = false
        var answering = NONE
        var nextPoll = System.currentTimeMillis() + POLL_EVERY_MS
        // [ANY] means any address a module can answer on — not any frame that
        // turns up. The channel this app opens passes the whole bus, which on
        // this car is about 1340 frames a second, and roughly 173 of those a
        // second begin with a byte in 0x01..0x07: the exact shape of a complete
        // ISO-TP single frame. Accepting the first frame that parses therefore
        // means accepting a broadcast frame, essentially always, before the
        // module has had time to answer.
        //
        // That is why the standard-PID scan came back empty, why the readiness
        // monitors never appeared and why live data would not start even with a
        // single parameter selected: everything addressed functionally goes
        // through here, and every one of those requests was being answered by a
        // wheel speed frame.
        val accepts: (Int) -> Boolean =
            if (rxId != ANY) ({ it == rxId }) else ({ isDiagnosticReply(it) })
        while (System.currentTimeMillis() < deadline) {
            // Blocks for the slice, so this is a read and not a spin.
            sm3.receive(minOf(SLICE_MS, deadline - System.currentTimeMillis()))
            if (System.currentTimeMillis() >= nextPoll) {
                sm3.poll()
                nextPoll = System.currentTimeMillis() + POLL_EVERY_MS
            }
            val frames = ArrayList<Sm3Client.CanFrame>()
            while (true) frames.add(replies.poll() ?: break)
            frames.addAll(sm3.drain())
            for ((index, frame) in frames.withIndex()) {
                if (!accepts(frame.id)) {
                    spill(frame)
                    continue
                }
                // Once somebody has answered, only that somebody is listened to.
                // Without this a functional request can interleave two modules'
                // frames into one reassembly and hand back a spliced answer.
                if (answering == NONE) answering = frame.id else if (frame.id != answering) continue
                val pci = (frame.data.getOrNull(0)?.toInt() ?: 0) and 0xf0
                val done = isotp.push(frame.id, frame.data)
                if (done != null) {
                    // What arrived after the answer in the same drain is not
                    // this request's, and it may well be the stream's.
                    spill(frames, index + 1)
                    return done
                }
                // A multi-frame answer stalls until the tester says go ahead.
                // And "go ahead" is addressed to the module that is answering,
                // not to whoever was asked. A functional request goes out as a
                // broadcast on 0x7DF; a broadcast flow control is addressed to
                // nobody, so the module holding the rest of the message never
                // hears it and the answer stops after its first frame.
                //
                // That costs nothing while every answer fits in one frame,
                // which is why it was never seen — and it is precisely what a
                // multi-PID mode 01 stops doing.
                if (pci == 0x10 && !flowControlSent) {
                    sm3.sendFrame(requestIdFor(frame.id, txId), IsoTp.FLOW_CONTROL)
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
     * Mode 01 with several PIDs in one request, which is what makes the
     * standard OBD screen usable.
     *
     * The service takes up to six PIDs at a time — `01 04 05 0C 0D 0F 10` — and
     * answers all of them in one message, echoing each PID before its bytes.
     * One PID at a time costs a round trip each, and a round trip on this car
     * is about eighty milliseconds: twenty-five parameters are then two
     * seconds a lap, which is the twelve readings a second this used to
     * manage. In five requests it is under half a second.
     *
     * That number is not a guess. The manufacturer's own Windows software,
     * logging twenty-six OBD parameters on this same car, records every one of
     * them at **2,12 Hz** — fifty-seven readings a second. Six PIDs a request
     * is the only arrangement that fits: twenty-six PIDs in five requests, at
     * about ninety milliseconds each, is exactly 2,1 laps a second.
     *
     * Where the answer is split. Each PID's width comes from [Pids], and an
     * echoed PID this build does not know stops the parse rather than
     * guessing — a wrong width would not fail, it would silently shift every
     * value after it by a byte, which is the kind of bug that gets believed.
     */
    fun mode01Batch(pids: List<Int>, txId: Int = FUNCTIONAL): Map<Int, ByteArray> {
        if (pids.isEmpty()) return emptyMap()
        if (pids.size == 1) {
            val only = mode01(pids[0], txId) ?: return emptyMap()
            return mapOf(pids[0] to only)
        }
        val payload = ByteArray(pids.size + 1)
        payload[0] = 0x01
        for ((i, pid) in pids.withIndex()) payload[i + 1] = pid.toByte()
        val r = request(txId, payload, rxId = ANY) ?: return emptyMap()
        if (r.isEmpty() || (r[0].toInt() and 0xff) != 0x41) return emptyMap()

        val out = LinkedHashMap<Int, ByteArray>()
        var at = 1
        while (at < r.size) {
            val pid = r[at].toInt() and 0xff
            val width = Pids.byId[pid]?.bytes ?: break
            if (at + 1 + width > r.size) break
            out[pid] = r.copyOfRange(at + 1, at + 1 + width)
            at += 1 + width
        }
        return out
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

    /**
     * Which of [ids] this [module] will actually answer.
     *
     * The catalogue cannot say. A brand package describes every configuration
     * the marque ever shipped and carries no table from model to variant — the
     * factory tool asks the car and matches at run time, and this is that.
     *
     * It is affordable because a module refuses politely. Asking this engine
     * for an identifier it does not have comes back `7F 22 31`,
     * requestOutOfRange, not silence: 130 of 157 identifiers in one measured
     * run, at a median of 62 ms each including the poll cadence. So the whole
     * engine catalogue — 2 653 distinct identifiers across its 29 variants — is
     * a few minutes once, not a timeout apiece.
     *
     * [timeoutMs] is short on purpose and only bounds the ones that do go
     * quiet; a refusal returns as soon as it arrives.
     */
    fun probe(
        ids: List<Int>,
        module: Int = ENGINE,
        timeoutMs: Long = 400,
        stop: () -> Boolean = { false },
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Map<Int, Int> = withTesterPresent(module) {
        val answered = LinkedHashMap<Int, Int>()
        for ((index, id) in ids.withIndex()) {
            if (stop()) break
            onProgress(index, ids.size)
            val got = if (id <= 0xff) {
                mode01(id, txId = module)
            } else {
                readDataByIdentifier(id, txId = module, timeoutMs = timeoutMs)
            }
            // How many bytes came back, not merely that some did.
            //
            // One identifier carries rows of different widths — 0x131F has a
            // one-byte accelerator position scaled by 100/255 and a two-byte one
            // scaled as though it were still a byte — and the module decides
            // which it is. Keeping only the fact that it answered left the
            // choice to whichever row the catalogue listed first, and picking
            // the wrong one reads a pedal at 798 %.
            if (got != null) answered[id] = got.size
        }
        answered
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
    /**
     * The same, for a stream, which needs it more than a sweep does.
     *
     * A sweep is a conversation: every identifier it asks for is traffic, and
     * traffic is what resets the session timer. A stream is the opposite — the
     * module is told once to emit and then nothing more is said to it, so from
     * its side the tester went quiet the moment the emission started, and about
     * five seconds later it stops sending.
     *
     * Measured, not guessed: the bench has beaten a 3E every two seconds
     * through every stream since the first one, with a comment saying why. The
     * app never sent one, which is why the numbers arrived and then froze two
     * or three seconds in.
     */
    fun <T> whileStreaming(module: Int, block: () -> T): T = withTesterPresent(module, block)

    private fun <T> withTesterPresent(module: Int = ENGINE, block: () -> T): T {
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val hb = kotlin.concurrent.thread(name = "tester-present", isDaemon = true) {
            while (running.get()) {
                try {
                    Thread.sleep(TESTER_PRESENT_MS)
                    if (running.get()) sm3.send(module, TESTER_PRESENT)
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
        keepEmitted = true
        for (declaration in plan.declarations()) {
            val r = request(plan.module, declaration, timeoutMs = STREAM_SETUP_MS)
            if (r == null || (r[0].toInt() and 0xff) != 0x6C) return false
        }
        // As many start commands as the packet count needs: five numbers is
        // all one single frame carries.
        for (command in plan.start()) fire(plan.module, command)
        return true
    }

    /**
     * Tell a module the tester is still here.
     *
     * A bare `3E`, sent and not waited for. GMLAN drops the session about five
     * seconds after the last one, and a dropped session stops the emission
     * without announcing it: what arrives afterwards is nothing at all, which
     * reads exactly like a quiet moment.
     *
     * There is already a thread doing this every two seconds. This exists so
     * the reader can do it too, on its own thread, because that thread is the
     * one holding the client lock while it drains seven hundred frames a
     * second -- and a heartbeat that cannot get the lock is a heartbeat that
     * did not happen.
     */
    fun keepAlive(module: Int = ENGINE) {
        fire(module, TESTER_PRESENT)
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
    /**
     * One slice of the emission.
     *
     * [Batch.frames] is reported separately from the values because they answer
     * different questions. A frame carries every parameter of one packet, so
     * values are frames times parameters-per-packet; when the two stop agreeing
     * the difference is frames arriving and not being read, which is the one
     * thing a samples-per-second figure cannot show.
     */
    class Batch(val values: List<Pair<Catalogue.Parameter, Double>>, val frames: Int)

    fun readStream(plan: Stream.Plan, sliceMs: Long = 50): Batch {
        sm3.receive(sliceMs)
        val rxId = streamIdFor(plan.module)
        val out = ArrayList<Pair<Catalogue.Parameter, Double>>()
        var frames = 0
        fun take(frame: Sm3Client.CanFrame) {
            if (waiting && isDiagnosticReply(frame.id)) {
                if (replies.size < REPLIES_KEPT) replies.add(frame)
                return
            }
            if (rxId != ANY && frame.id != rxId) return
            frames++
            out.addAll(Stream.decode(plan, frame.data))
        }
        // What a request read past goes first: it arrived first.
        while (true) take(emitted.poll() ?: break)
        for (frame in sm3.drain()) take(frame)
        return Batch(out, frames)
    }

    /**
     * Stop keeping emitted frames for a reader, and drop what was kept.
     *
     * Separate from [endStream] because that one is also sent between rounds
     * and while reviving a stream, and the frames kept up to that moment are
     * still readings the recording wants.
     */
    fun streamFinished() {
        keepEmitted = false
        emitted.clear()
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

        /** What one ISO-TP single frame carries. */
        const val SINGLE_FRAME_BYTES = 7

        /** How long to wait to be cleared to send the rest of a long request. */
        private const val FLOW_CONTROL_MS = 1000L

        /**
         * How often a request pokes the adapter while waiting. The factory
         * tool sends one poll a second at the median and never sustains more
         * than 27 in a second; four a second stays inside that and is a
         * twentieth of what this used to do.
         */
        private const val POLL_EVERY_MS = 250L

        /** How often a long operation says the tester is still here. */
        private const val TESTER_PRESENT_MS = 2000L

        /**
         * TesterPresent, GMLAN style: the service byte on its own.
         *
         * The UDS habit of appending a 0x00 sub-function is wrong here. This
         * engine answers `3E 00` with `7F 3E 12` — sub-function not supported —
         * so the heartbeat never lands, the session lapses and any packet the
         * module was streaming stops. The factory tool sends a bare `3E` 166
         * times in the recorded session and gets `7E` back every time; it never
         * sends a sub-function.
         */
        private val TESTER_PRESENT = byteArrayOf(0x3E)

        /** A packet declaration is a short exchange; it either lands or it does not. */
        private const val STREAM_SETUP_MS = 1000L

        /**
         * How many read-past packet frames are held for the stream reader. At
         * seven hundred frames a second this is about thirty seconds, far more
         * than any request waits, and it is the ceiling on what a module left
         * emitting with nobody reading can take.
         */
        private const val EMITTED_KEPT = 20_000

        /** Answers held for a waiting request; an answer is a handful of frames. */
        private const val REPLIES_KEPT = 256

        /** Where any module's emitted packets arrive. See [streamIdFor]. */
        fun isEmitted(id: Int): Boolean = id == 0x5E8 || id in 0x540..0x55F

        /** OBD functional request: every module that listens answers. */
        const val FUNCTIONAL = 0x7DF
        /** The engine, addressed directly. */
        const val ENGINE = 0x7E0
        /** Accept whichever module replies. */
        const val ANY = -1

        /** No module has answered yet in this exchange. */
        private const val NONE = -2

        /**
         * Addresses a module can answer a diagnostic request on.
         *
         * 0x7E8..0x7EF is the powertrain range; 0x640..0x65F is where the GMLAN
         * modules at 0x240..0x25F reply. Nothing else on this bus is an answer
         * to anything, and the rest of what arrives — 51 addresses of it — is
         * the car talking to itself.
         */
        fun isDiagnosticReply(id: Int): Boolean =
            id in 0x7E8..0x7EF || id in 0x640..0x65F

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

        /**
         * The inverse, for talking back to whoever answered.
         *
         * Only flow control needs this, and only after a functional request:
         * the answer arrives on 0x7E8 and the "carry on" has to go to 0x7E0.
         * [asked] is what to fall back to when the response address is not one
         * this mapping recognises, which keeps a physically addressed request
         * behaving exactly as it did.
         */
        fun requestIdFor(rxId: Int, asked: Int): Int = when {
            rxId in 0x7E8..0x7EF -> rxId - 8
            rxId in 0x640..0x65F -> rxId - 0x400
            else -> asked
        }

        /**
         * Where a module's emitted packets arrive — which is not where its
         * answers do.
         *
         * GMLAN keeps segmented diagnostic answers apart from unacknowledged
         * periodic data: the engine answers a request on 0x7E8 and emits its
         * packets on 0x5E8. In the recorded factory session 0x5E8 carried
         * 167 422 packet frames covering all seven packets, while 0x7E8 carried
         * 510 frames of which not one began with a packet number; against the
         * car it behaves the same way. Filtering the stream on 0x7E8 therefore
         * discards every frame and looks exactly like a module that refused to
         * emit.
         *
         * The other modules follow the same shape, and the capture says so
         * rather than the specification: the tool that talked to 0x242 set a
         * flow-control filter on 0x642 and a pass filter on 0x542 in the same
         * channel block, and did the same for 0x641/0x541, 0x643/0x543 and
         * 0x549/0x554. So a module at 0x24X answers on 0x64X and emits on
         * 0x54X — a hundred lower, where the engine's pair is 0x7E8/0x5E8.
         */
        fun streamIdFor(txId: Int): Int = when (txId) {
            in 0x7E0..0x7E7 -> txId - 0x1F8
            in 0x240..0x25F -> txId + 0x300
            else -> ANY
        }
    }
}
