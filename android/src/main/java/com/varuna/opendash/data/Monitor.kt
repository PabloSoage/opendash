package com.varuna.opendash.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.varuna.opendash.Session
import com.varuna.opendash.obd.Diagnostics
import com.varuna.opendash.obd.Pid
import com.varuna.opendash.obd.Pids
import com.varuna.opendash.obd.Stream
import kotlin.concurrent.thread

/**
 * Polls the parameters you have selected and keeps their recent history.
 *
 * Round-robin, one request at a time, because there is one bus and one socket.
 * Selecting twenty parameters therefore refreshes each of them twenty times
 * more slowly than selecting one — worth knowing before wondering why a chart
 * looks coarse, which is why the measured rate is on screen.
 *
 * There is no fixed interval by default. The exchange is synchronous, so the
 * adapter sets the pace; sleeping on top of that only makes it slower.
 *
 * A parameter that does not answer three times running is dropped from the
 * rotation. Without that, one unsupported identifier costs a 1.5-second
 * timeout on every lap and drags everything else down with it.
 */
class Monitor(private val settings: Settings, private val store: RecordingStore) {

    /** key to its recent window. */
    val series = mutableStateMapOf<String, Series>()

    /** key to the most recent value. */
    val values = mutableStateMapOf<String, Double>()

    /** Keys that stopped answering, so the UI can say so instead of freezing. */
    val silent = mutableStateMapOf<String, Boolean>()

    /**
     * Bumped once per lap. [Series] is a plain ring buffer — making every
     * sample a snapshot write would allocate on the polling thread — so the
     * charts observe this counter instead and redraw when it moves.
     */
    var tick by mutableIntStateOf(0)
        private set

    var isRunning by mutableStateOf(false)
        private set

    var recordingName by mutableStateOf<String?>(null)
        private set

    /** Whether the current run is writing a file, as opposed to only showing. */
    val isRecording: Boolean get() = recorder != null

    /** Marks put in the current recording, from the screen or the notification. */
    var marks by mutableIntStateOf(0)
        private set

    /**
     * Put a mark in the recording: a row named "Marker" whose value counts up.
     *
     * For finding a moment again afterwards -- a noise, a hard stop, where the
     * tank was filled. Pressed with the phone in a mount, so it has to be one
     * tap on the notification, and it must not write from this thread: the
     * row is queued for the reader, which is the only thread that writes.
     */
    fun mark() {
        if (recorder == null) return
        pendingMarks.incrementAndGet()
    }

    private val pendingMarks = java.util.concurrent.atomic.AtomicInteger()

    /** Called on the reading thread, which owns the recorder. */
    private fun writeMarks() {
        while (pendingMarks.get() > 0) {
            pendingMarks.decrementAndGet()
            marks++
            recorder?.add(MARKER, MARKER_ID, "_", marks.toDouble())
        }
    }

    private var lastStatus = 0L

    /** Keep the notification saying what the run is doing, every [STATUS_MS]. */
    private fun postStatus(now: Long) {
        if (now - lastStatus < STATUS_MS) return
        lastStatus = now
        val rows = recordedRows
        val text = buildString {
            if (recorder != null) {
                append(String.format(java.util.Locale.ROOT, "%,d", rows)).append(" rows · ")
            }
            append(rate).append("/s")
            if (marks > 0) append(" · ").append(marks).append(" marks")
            lastError?.let { append("\n").append(it) }
        }
        LiveService.status(store.context, text, recorder != null)
    }

    var recordedRows by mutableIntStateOf(0)
        private set

    /** Readings per second over the last lap, for the rate display. */
    var rate by mutableIntStateOf(0)
        private set

    /**
     * Emitted frames per second, which is a different question from [rate].
     *
     * A packet frame carries every parameter declared in it, so samples per
     * second is frames per second times the parameters per frame — and when the
     * two stop agreeing it says where the loss is. Fifteen parameters in seven
     * packets read at 44.8 Hz each where ten in five packets read at 100: that
     * is either the module emitting a whole round more slowly, or this side not
     * reading fast enough to keep up, and the two are told apart by counting
     * what actually arrives.
     */
    var frameRate by mutableIntStateOf(0)
        private set

    /**
     * How many rounds the stream is cycling through, and which one is live.
     *
     * One round means no rotation at all and nothing worth saying. More than one
     * means a parameter is dark between its turns, and that has to be visible:
     * a chart with gaps in it is a different thing from a chart of a sensor that
     * stopped answering, and they look the same until the screen says so.
     */
    var rounds by mutableIntStateOf(1)
        private set

    var round by mutableIntStateOf(0)
        private set

    var lastError by mutableStateOf<String?>(null)
        private set

    private var worker: Thread? = null
    private var recorder: Recorder? = null

    /**
     * A run holds the link up for as long as it lasts.
     *
     * The foreground service, the Wi-Fi lock and the partial wake lock all live
     * in [LiveService]; this is where they are turned on and off, because the
     * monitor is the only thing that knows when a run actually begins and ends
     * — including when it ends by itself, which is exactly the case that used
     * to leave a locked phone with a dead socket and a half-written file.
     */
    private fun holdTheLink() = LiveService.start(store.context)

    private fun letGo() = LiveService.stop(store.context)

    /** What to poll. [request] returns null when the module does not answer. */
    class Target(
        val key: String,
        val name: String,
        val unit: String,
        /** What it was asked for by, so a recording can tell namesakes apart. */
        val identifier: String,
        /**
         * Set when this can travel in a batched mode 01 request — six PIDs to a
         * question instead of one. [decode] then turns the bytes that came back
         * for it into the number.
         *
         * Both or neither: a target with a PID and no decoder is asked for one
         * at a time, which is what everything did before batching existed.
         */
        val batchPid: Int? = null,
        val decode: ((ByteArray) -> Double?)? = null,
        val request: () -> Double?,
    )

    fun targetsFor(pids: List<Pid>): List<Target> = pids.map { pid ->
        Target(
            "obd:" + pid.id,
            pid.name,
            pid.unit,
            String.format(java.util.Locale.ROOT, "PID %02X", pid.id),
            batchPid = pid.id,
            decode = { pid.value(it) },
        ) {
            Session.diagnostics.mode01(pid.id)?.let { pid.value(it) }
        }
    }

    /**
     * Catalogue entries this tool knows how to ask for.
     *
     * Two forms. A parameter whose identifier fits in a byte is a mode 01 PID
     * and is read the way any OBD parameter is. One that needs two bytes is a
     * data identifier, read with service 0x22 — which the GDS2 capture shows
     * being used exactly once, to fetch the VIN as identifier 0xF802, so the
     * request form is known even though the coverage is not.
     *
     * That distinction matters on screen: the mode 01 ones are certain, the
     * two-byte ones are an educated request that a module is free to refuse.
     * A refusal costs nothing here beyond a timeout and the parameter being
     * dropped from the rotation.
     */
    fun requestable(catalogue: Catalogue): List<Catalogue.Parameter> =
        requestable(catalogue.parameters)

    /** The same filter over any subset, which is how a module's list is built. */
    fun requestable(parameters: List<Catalogue.Parameter>): List<Catalogue.Parameter> =
        parameters.filter { it.pid in 0..0xffff && it.bytes in 1..4 }

    fun notRequestable(catalogue: Catalogue): Int =
        catalogue.parameters.size - requestable(catalogue).size

    /**
     * [module] is the CAN address the parameters belong to. It used to be
     * assumed to be the engine for everything, so a body module parameter went
     * to a module that had never heard of it and came back empty — which on
     * screen is a row that never shows a number and no way to tell why.
     */
    fun targetsForCatalogue(
        params: List<Catalogue.Parameter>,
        module: Int = Diagnostics.ENGINE,
    ): List<Target> = params.map { p ->
        val fromBytes: (ByteArray) -> Double? = { raw ->
            if (raw.size < p.bytes) {
                null
            } else {
                var value = 0L
                for (i in 0 until p.bytes) value = (value shl 8) or (raw[i].toLong() and 0xff)
                p.scale(value)
            }
        }
        // A catalogue row can be batched too, but only when its identifier is a
        // standard PID **and** the catalogue agrees with J1979 about how wide
        // the answer is. Splitting a batched answer needs the width, and taking
        // the catalogue's word for it against the standard's would not fail
        // loudly — it would shift every value after it along by a byte.
        val standard = Pids.byId[p.pid]
        val batchable = p.pid <= 0xff && standard != null && standard.bytes == p.bytes
        Target(
            p.rowKey,
            p.name,
            p.unit,
            p.identifierText,
            batchPid = if (batchable) p.pid else null,
            decode = if (batchable) fromBytes else null,
        ) {
            val raw = if (p.pid <= 0xff) {
                Session.diagnostics.mode01(p.pid)
            } else {
                Session.diagnostics.readDataByIdentifier(p.pid, txId = module)
            } ?: return@Target null
            fromBytes(raw)
        }
    }

    /**
     * Let the module do the sending.
     *
     * The round-robin below asks for one parameter and waits for it, which
     * costs a round trip each — twenty to forty milliseconds on this car, so
     * ten parameters share five readings a second between them. Declaring a
     * packet and letting the module emit it turns that into about a hundred
     * frames a second with every parameter in each one, which is the difference
     * between watching a coolant temperature and logging a rail pressure while
     * driving.
     *
     * The rotation is still there and still the right thing for a handful of
     * standard OBD PIDs, which are addressed functionally and have no packet to
     * declare. This is for a catalogue module.
     */
    fun startStream(
        rotation: Stream.Rotation,
        record: Boolean,
        label: String,
        dwellMs: Long = DWELL_MS,
    ) {
        if (isRunning || rotation.isEmpty) return
        val module = rotation.rounds.first().module
        rounds = rotation.rounds.size
        round = 0
        lastError = null
        openRecorder(record, label)

        isRunning = true
        recordedRows = 0
        holdTheLink()
        worker = thread(name = "monitor-stream", isDaemon = true) {
            var declared = false
            try {
                Session.nowStreaming(module)
                var readings = 0
                var frames = 0
                var since = System.currentTimeMillis()
                // What did not fit in the packets is polled, one per turn round
                // the loop, instead of being dropped.
                //
                // Seven packets of seven bytes is forty-nine bytes of values,
                // and a selection can be larger than that — forty-seven
                // parameters certainly is. Those used to go into leftOut and
                // then nowhere: rows on screen that never showed a number,
                // indistinguishable from a module refusing to answer. One
                // polled request costs twenty to forty milliseconds against the
                // stream's fifty-millisecond slice, so everything selected gets
                // data, the packets fast and the remainder slowly.
                val extra = targetsForCatalogue(rotation.leftOut, module)
                var turn = 0
                var lastExtra = 0L
                var refused = 0
                var lastBeat = System.currentTimeMillis()
                // How many times the whole streaming session has been torn down
                // and built again after it stopped working. See below. Counted
                // for the screen, not as a budget: what ends a run now is time
                // without a single frame, never a number of attempts.
                var revivals = 0
                // The last time anything at all arrived from the module, across
                // declarations and revivals alike.
                var lastFrameAt = System.currentTimeMillis()
                // Revivals since the module last emitted anything, which is what
                // the wait between them grows with.
                var inARow = 0
                // So a failed write is said once, not re-said every slice.
                var toldFailure: String? = null

                fun givenUp() =
                    System.currentTimeMillis() - lastFrameAt > GIVE_UP_SILENT_MS

                // The session has to be held open for as long as the module is
                // emitting — see Diagnostics.whileStreaming. Without it the
                // module gives up on its own a few seconds in and every batch
                // from then on is empty, which on screen is numbers that arrive
                // and then freeze.
                //
                // ## Why this is a loop and not one call
                //
                // A 65-minute recording stopped at a round change with the rate
                // perfectly healthy up to the last sample, and pressing Start
                // again picked straight up — no reconnecting the Wi-Fi, no
                // reconnecting the adapter, just Start. **So the link was never
                // the problem.** The transport was alive the whole time; what
                // stopped was the streaming session, and what fixed it was
                // tearing the whole thing down and building it again.
                //
                // Why the module stopped accepting declarations is not known,
                // and guessing at it here would be inventing a cause to justify
                // a fix. What is known is exactly one thing: **a fresh start
                // works.** So when the rounds give up, do precisely what
                // pressing Start does — stop the emission, drop the heartbeat,
                // wait, and declare again — instead of ending a recording that
                // only needed the thing the user would have done by hand.
                //
                // The recorder is untouched across a revival. What was wanted
                // was the rest of the drive in one file, not a second file
                // starting at zero.
                while (isRunning) {
                    Session.diagnostics.whileStreaming(module) {
                        while (isRunning) {
                            // A round is declared, left to emit for the dwell, then
                            // stopped so the next one can take the packets. With a
                            // single round this happens once and the inner loop
                            // never ends, which is exactly the old behaviour.
                            val plan = rotation.rounds[round % rotation.rounds.size]
                            declared = Session.guarded { Session.diagnostics.beginStream(plan) } ?: false
                            if (!declared) {
                                // One refused round does not end a two-hour
                                // recording. A declaration is a round trip and a
                                // round trip can be lost — to a retransmission, to
                                // the module being busy — and ending the run there
                                // throws away everything after it as well as
                                // everything that round was for. So: say so, move
                                // to the next round, and only give up when several
                                // in a row fail, which is a module that has stopped
                                // listening rather than a message that went astray.
                                refused++
                                // The one case where the link really has gone: get
                                // it back before anything else, because nothing
                                // below can work over a socket that is not there.
                                if (Session.state != Session.State.CHANNEL_OPEN) {
                                    lastError = "the link went away; getting it back"
                                    if (reopenLink(::givenUp)) {
                                        refused = 0
                                        continue
                                    }
                                }
                                lastError = if (refused >= ROUNDS_GIVE_UP_AFTER) {
                                    "the module stopped accepting declarations"
                                } else {
                                    "a round was refused; carrying on with the next"
                                }
                                if (refused >= ROUNDS_GIVE_UP_AFTER) break
                                round = (round + 1) % rotation.rounds.size
                                continue
                            }
                            refused = 0
                            val until =
                                if (rotation.rounds.size > 1) System.currentTimeMillis() + dwellMs
                                else Long.MAX_VALUE
                            // When the last frame arrived. A module that has
                            // stopped emitting does not report it, and the read
                            // does not fail: it simply returns nothing, for ever.
                            var heard = System.currentTimeMillis()

                            /** One slice of the emission. False ends this declaration. */
                            fun slice(): Boolean {
                                val batch = Session.guarded {
                                    Session.diagnostics.readStream(plan, STREAM_SLICE_MS)
                                }
                                if (batch == null) {
                                    // Said rather than swallowed. This is the link going
                                    // out from under a screenful of numbers, and left
                                    // silent it looks identical to a module that simply
                                    // stopped having anything to say.
                                    lastError = "the link went away while the module was emitting"
                                    return false
                                }
                                frames += batch.frames
                                // ── the silence that ended a 32-minute recording ──
                                //
                                // A read that finds nothing is not a failure. It
                                // returns a batch of zero frames, which is exactly
                                // what an idle moment looks like, so nothing here
                                // could tell it from a module that had stopped for
                                // good. And with a single round there is no dwell
                                // to expire -- `until` is Long.MAX_VALUE -- so this
                                // loop simply span, for ever, reading nothing.
                                //
                                // That is not a theory. A recording on 20/09 ran
                                // 32 minutes at a flat 2 500 samples a second, lost
                                // the module at 1 948 s, came back for two seconds
                                // and then stopped -- and the app went on saying it
                                // was recording, at zero samples a second, for the
                                // remaining hour and a half of the drive. The rate
                                // never sagged beforehand, which is what rules out
                                // running out of memory: that decays, this fell off
                                // a cliff.
                                //
                                // So silence is now a fact this loop knows. Long
                                // enough of it and the round is declared again,
                                // which is all it takes.
                                val now = System.currentTimeMillis()
                                if (batch.frames > 0) {
                                    heard = now
                                    lastFrameAt = now
                                    inARow = 0
                                } else if (now - heard > SILENCE_MS) {
                                    lastError = "the module went quiet; declaring the packets again"
                                    runCatching { Session.diagnostics.endStream(module) }
                                    return false
                                }
                                for ((parameter, value) in batch.values) {
                                    val key = parameter.rowKey
                                    values[key] = value
                                    series.getOrPut(key) { Series() }.add(value)
                                    recorder?.add(parameter.name, parameter.identifierText, parameter.unit, value)
                                    readings++
                                }
                                // The heartbeat, from the thread that owns the
                                // socket, and before anything below that can
                                // take a while.
                                //
                                // A module drops the session about five seconds
                                // after the last TesterPresent, and there is a
                                // thread whose whole job is to send one every two.
                                // But it has to take the client lock to do it, and
                                // this loop holds that lock while it drains -- at
                                // seven hundred frames a second there is always
                                // something to drain. Starving that thread for five
                                // seconds is enough, and it is the best explanation
                                // there is for a module that went quiet on its own
                                // with the link still up.
                                //
                                // Sending it from here cannot be starved by
                                // anything, because this is what would starve it.
                                // The other thread stays: two TesterPresents cost
                                // nothing, and one of them arriving is the point.
                                if (now - lastBeat >= BEAT_MS) {
                                    lastBeat = now
                                    Session.guarded { Session.diagnostics.keepAlive(module) }
                                }
                                // One polled reading every EXTRA_EVERY_MS, not one per
                                // turn round the loop. A request costs twenty to forty
                                // milliseconds against a fifty-millisecond slice, so one
                                // per turn is a third of the stream's time spent on the
                                // overflow — and with two parameters in the overflow
                                // that is a bad trade at any price. What the stream
                                // emits while the request waits is kept for the next
                                // slice rather than lost; see Diagnostics.request.
                                if (extra.isNotEmpty() && now - lastExtra >= EXTRA_EVERY_MS) {
                                    lastExtra = now
                                    val t = extra[turn % extra.size]
                                    turn++
                                    val v = try { t.request() } catch (_: Exception) { null }
                                    if (v == null) {
                                        silent[t.key] = true
                                    } else {
                                        silent.remove(t.key)
                                        values[t.key] = v
                                        series.getOrPut(t.key) { Series() }.add(v)
                                        recorder?.add(t.name, t.identifier, t.unit, v)
                                        readings++
                                    }
                                }
                                writeMarks()
                                recordedRows = recorder?.rows ?: 0
                                recordingName = recorder?.name
                                postStatus(now)
                                recorder?.lastFailure?.let {
                                    if (it != toldFailure) {
                                        toldFailure = it
                                        lastError = it
                                    }
                                }
                                tick++
                                val elapsed = System.currentTimeMillis() - since
                                if (elapsed >= 1000) {
                                    rate = (readings * 1000L / elapsed).toInt()
                                    frameRate = (frames * 1000L / elapsed).toInt()
                                    readings = 0
                                    frames = 0
                                    since = System.currentTimeMillis()
                                }
                                return true
                            }

                            while (isRunning && System.currentTimeMillis() < until) {
                                // Nothing inside one slice can end the run. What
                                // can fail on the link is already guarded; this
                                // catches the rest -- a value that will not
                                // decode, a row the screen state will not take --
                                // which used to fall through to the handler at the
                                // bottom and end a recording that had nothing wrong
                                // with it but one bad reading.
                                val carryOn = try {
                                    slice()
                                } catch (e: InterruptedException) {
                                    throw e
                                } catch (e: Exception) {
                                    lastError = "skipped a slice: " + (e.message ?: e.javaClass.simpleName)
                                    true
                                }
                                if (!carryOn) break
                            }
                            // Between rounds: stop, so the packet numbers are free
                            // for the next declaration. Costs one round trip, which
                            // is why the dwell is seconds and not milliseconds.
                            if (rotation.rounds.size > 1 && isRunning) {
                                runCatching { Session.diagnostics.endStream(module) }
                                round = (round + 1) % rotation.rounds.size
                            }
                            // A module that has been silent for longer than a
                            // refuel is a car that has been switched off and left.
                            if (givenUp()) break
                        }
                    }

                    // Out here the heartbeat thread is already gone: leaving
                    // the block above is what stops it. So this is the same
                    // state the app is in between pressing Stop and pressing
                    // Start, which is the state a fresh start is known to work
                    // from.
                    //
                    // ## Why there is no longer a limit on how many times
                    //
                    // There used to be three revivals, for the whole run. Three
                    // is plenty for one bad moment and nothing like enough for
                    // a drive: a two-hour recording with a hiccup every forty
                    // minutes was over by the fourth, however healthy everything
                    // was in between. And the one stop every long drive has --
                    // the engine switched off to fill the tank -- is exactly a
                    // module that goes quiet for five minutes and then comes
                    // back, which is the case this loop exists for.
                    //
                    // So the run ends on time without a single frame, not on a
                    // count of attempts. Twenty minutes: longer than any stop for
                    // fuel, and short enough that a car switched off and left
                    // does not hold a wake lock and an open file all night.
                    if (!isRunning) break
                    if (givenUp()) {
                        lastError = "nothing from the module for " +
                            (GIVE_UP_SILENT_MS / 60_000) + " minutes; the recording is closed"
                        break
                    }
                    revivals++
                    inARow++
                    lastError = "the stream stopped; starting it again (" + revivals + ")"
                    // Tell the module to stop emitting whatever it still thinks
                    // it is emitting, so the packet numbers are free.
                    runCatching { Session.diagnostics.endStream(module) }
                    if (Session.state != Session.State.CHANNEL_OPEN && !reopenLink(::givenUp)) break
                    // Backed off: a module that did not come back after two
                    // seconds is likely an engine that is off, and asking it
                    // every two seconds for twenty minutes is noise on a bus
                    // somebody may be about to start a car on.
                    Thread.sleep(minOf(REVIVE_WAIT_MS * inARow, REVIVE_WAIT_MAX_MS))
                    refused = 0
                    round = 0
                }
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
            } finally {
                // Always, even after a failure: a module left emitting keeps a
                // hundred frames a second coming at an adapter nobody is
                // reading, and the next tool to connect finds the bus busy.
                runCatching { Session.diagnostics.endStream(module) }
                Session.diagnostics.streamFinished()
                Session.stoppedStreaming(module)
                // A run that ends by itself has to close its file too. Only
                // stop() used to, so a stream that died on its own left the
                // recording open and unterminated.
                closeRecorder()
                letGo()
                isRunning = false
            }
        }
    }

    /**
     * Get the link back, without giving up the recording to do it.
     *
     * Backed off, and bounded. Backed off because a Wi-Fi link that has just
     * dropped is not ready a millisecond later and hammering it is how a
     * recovery becomes a hang; bounded because a car that has been switched off
     * is never coming back, and a worker thread retrying for ever holds the
     * wake lock and the file open behind it.
     *
     * Bounded by [givenUp], the same clock as everything else in a run, and
     * not by a number of tries. It used to be six tries, about twenty seconds,
     * which ended the whole recording over a Wi-Fi drop that lasted
     * twenty-five.
     *
     * The file is not touched either way. A reconnected session keeps writing
     * to the same recording, which is the point: what was wanted was the rest
     * of the drive, not a second file starting at zero.
     */
    private fun reopenLink(givenUp: () -> Boolean): Boolean {
        var attempt = 0
        while (isRunning && !givenUp()) {
            Thread.sleep(minOf(RECONNECT_WAIT_MS * (attempt + 1), RECONNECT_WAIT_MAX_MS))
            if (!isRunning) return false
            attempt++
            if (Session.openChannel()) {
                lastError = "the link came back after " + attempt +
                    (if (attempt == 1) " try" else " tries")
                return true
            }
        }
        return false
    }

    private fun openRecorder(record: Boolean, label: String) {
        marks = 0
        pendingMarks.set(0)
        if (!record) return
        LiveService.onMark = ::mark
        val compress = settings.compressRecordings
        recorder = try {
            Recorder({ store.create(label, compress) }, compress)
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            null
        }
    }

    fun start(targets: List<Target>, record: Boolean, label: String) {
        if (isRunning || targets.isEmpty()) return
        lastError = null
        openRecorder(record, label)

        isRunning = true
        recordedRows = 0
        holdTheLink()
        worker = thread(name = "monitor", isDaemon = true) {
            val misses = HashMap<String, Int>()
            var readings = 0

            fun alive(t: Target) = (misses[t.key] ?: 0) < GIVE_UP_AFTER

            /** One answer, or the absence of one, booked the same way either way. */
            fun record(t: Target, v: Double?) {
                if (v == null) {
                    val n = (misses[t.key] ?: 0) + 1
                    misses[t.key] = n
                    if (n >= GIVE_UP_AFTER) silent[t.key] = true
                } else {
                    misses[t.key] = 0
                    silent.remove(t.key)
                    values[t.key] = v
                    series.getOrPut(t.key) { Series() }.add(v)
                    recorder?.add(t.name, t.identifier, t.unit, v)
                    readings++
                }
            }

            // Split once, not every lap. What can go six to a request goes
            // there; the rest keeps the one-at-a-time path it always had.
            val batched = targets.filter { it.batchPid != null && it.decode != null }
            val singly = targets.filter { it.batchPid == null || it.decode == null }

            while (isRunning) {
                val lapStart = System.currentTimeMillis()
                readings = 0

                for (chunk in batched.filter(::alive).chunked(MODE01_BATCH)) {
                    if (!isRunning) break
                    val answers = try {
                        Session.diagnostics.mode01Batch(chunk.map { it.batchPid!! })
                    } catch (_: Exception) {
                        emptyMap()
                    }
                    for (t in chunk) {
                        val raw = answers[t.batchPid]
                        record(t, if (raw == null) null else t.decode!!(raw))
                    }
                    val gap = settings.pollIntervalMs.toLong()
                    if (gap > 0) Thread.sleep(gap)
                }

                for (t in singly) {
                    if (!isRunning) break
                    if (!alive(t)) continue
                    record(
                        t,
                        try {
                            t.request()
                        } catch (_: Exception) {
                            null
                        },
                    )
                    val gap = settings.pollIntervalMs.toLong()
                    if (gap > 0) Thread.sleep(gap)
                }

                val elapsed = (System.currentTimeMillis() - lapStart).coerceAtLeast(1)
                rate = (readings * 1000L / elapsed).toInt()
                writeMarks()
                recordedRows = recorder?.rows ?: 0
                postStatus(System.currentTimeMillis())
                tick++
                // Everything in the rotation went quiet: stop hammering the bus.
                if (readings == 0 && targets.none(::alive)) break
            }
            closeRecorder()
            letGo()
            isRunning = false
        }
    }

    fun stop() {
        isRunning = false
        worker = null
        closeRecorder()
        letGo()
    }

    private fun closeRecorder() {
        LiveService.onMark = null
        recorder?.close()
        recorder = null
    }

    fun reset() {
        series.clear()
        values.clear()
        silent.clear()
        recordingName = null
        recordedRows = 0
        rate = 0
    }

    companion object {
        const val GIVE_UP_AFTER = 3

        /** How a mark is written: a parameter of its own, so it charts as one. */
        const val MARKER = "Marker"
        const val MARKER_ID = "mark"

        /** How often the notification is brought up to date. */
        const val STATUS_MS = 2000L

        /**
         * How many PIDs travel in one mode 01 request.
         *
         * Six is what J1979 allows, and six is what the request byte count
         * permits without segmenting it: the service byte plus six PIDs is
         * seven bytes, exactly one CAN frame's payload.
         */
        const val MODE01_BATCH = 6

        /** How often the overflow gets a turn, so the stream keeps its own. */
        const val EXTRA_EVERY_MS = 300L

        /** How long one read of the emitted stream blocks before looping. */
        const val STREAM_SLICE_MS = 50L

        /**
         * How long a module may say nothing before the packets are declared
         * again.
         *
         * Two seconds. Well past any gap a busy module leaves -- the measured
         * emission is 700 frames a second, and the worst gap in 32 minutes of
         * the 20/09 recording before it stopped was under half a second -- and
         * well inside the patience of somebody driving, who would otherwise be
         * recording nothing without knowing.
         */
        const val SILENCE_MS = 2000L

        /**
         * How often this loop sends its own TesterPresent.
         *
         * One second, against the roughly five a module waits before dropping
         * the session. See where it is sent: the point is mostly which thread
         * it goes out on. The interval matters too, because the link to the
         * adapter is Wi-Fi and Wi-Fi stalls: at two seconds a stall of three
         * was enough to let the session lapse, at one it takes four. A beat is
         * one CAN frame, which on a bus carrying seven hundred of them a second
         * from this module alone is nothing.
         */
        const val BEAT_MS = 1000L

        /**
         * How long a round emits before the next one takes the packets.
         *
         * Measured at the car on 18 September, and the estimate it replaces was
         * wrong by nine times. A switch is a stop, one declaration per packet
         * and a start — seven round trips — and a round trip costs far more
         * while the module is emitting than while it is idle, because every one
         * of them has to read past five hundred frames a second to find its own
         * answer. Measured: **1832 ms**, against the 200 ms this code assumed.
         *
         * That changes the arithmetic completely. At a two-second dwell over
         * four rounds the cycle is 15.3 s and each parameter is live for 13 %
         * of it, not 91 %. The dwell has to be long enough to pay for the
         * switch, so the default is five seconds: 27 s a cycle, 18 % live.
         *
         * The real fix is fewer switches, and that is a separate change — see
         * [Stream.rotate]. A multi-frame declaration takes six identifiers per
         * packet instead of two, which is proven on this module, and it halves
         * the rounds.
         */
        const val DWELL_MS = 5000L

        /** What one round change actually costs. Measured, not estimated. */
        const val SWITCH_MS = 1832L

        /** Consecutive refused round declarations before a run is abandoned. */
        const val ROUNDS_GIVE_UP_AFTER = 4

        /**
         * How long to wait between attempts to get a dropped link back: a
         * second longer each time, up to ten.
         */
        const val RECONNECT_WAIT_MS = 1000L
        const val RECONNECT_WAIT_MAX_MS = 10_000L

        /**
         * How long a run goes on without a single frame from the module before
         * it is closed.
         *
         * This is the only thing that ends a run on its own, and it is a time
         * rather than a count on purpose. Twenty minutes is longer than the
         * engine is off for a stop to fill the tank -- after which the module
         * boots, the packets are declared again and the same file carries on --
         * and short enough that a car switched off and left does not keep a
         * wake lock and an open file until the battery of the phone gives out.
         */
        const val GIVE_UP_SILENT_MS = 20 * 60_000L

        /**
         * Wait before building a stopped stream again: two seconds more on each
         * revival in a row, up to fifteen. The evidence that a rebuild is the
         * right shape of fix is narrow but solid: the session that stopped after
         * 65 minutes needed nothing but Start pressed again -- no reconnecting
         * anything -- so a fresh session is known to work where a retried round
         * did not.
         */
        const val REVIVE_WAIT_MS = 2000L
        const val REVIVE_WAIT_MAX_MS = 15_000L
    }
}
