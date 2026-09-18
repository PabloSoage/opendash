package com.varuna.opendash.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.varuna.opendash.Session
import com.varuna.opendash.obd.Diagnostics
import com.varuna.opendash.obd.Pid
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
        val request: () -> Double?,
    )

    fun targetsFor(pids: List<Pid>): List<Target> = pids.map { pid ->
        Target(
            "obd:" + pid.id,
            pid.name,
            pid.unit,
            String.format(java.util.Locale.ROOT, "PID %02X", pid.id),
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
        Target(p.rowKey, p.name, p.unit, p.identifierText) {
            val raw = if (p.pid <= 0xff) {
                Session.diagnostics.mode01(p.pid)
            } else {
                Session.diagnostics.readDataByIdentifier(p.pid, txId = module)
            } ?: return@Target null
            if (raw.size < p.bytes) return@Target null
            var value = 0L
            for (i in 0 until p.bytes) value = (value shl 8) or (raw[i].toLong() and 0xff)
            p.scale(value)
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
                // The session has to be held open for as long as the module is
                // emitting — see Diagnostics.whileStreaming. Without it the
                // module gives up on its own a few seconds in and every batch
                // from then on is empty, which on screen is numbers that arrive
                // and then freeze.
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
                            lastError = if (refused >= ROUNDS_GIVE_UP_AFTER) {
                                "the module refused to declare the data packets"
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
                        while (isRunning && System.currentTimeMillis() < until) {
                            val batch = Session.guarded {
                                Session.diagnostics.readStream(plan, STREAM_SLICE_MS)
                            }
                            frames += batch?.frames ?: 0
                            if (batch == null) {
                                // Said rather than swallowed. This is the link going
                                // out from under a screenful of numbers, and left
                                // silent it looks identical to a module that simply
                                // stopped having anything to say.
                                lastError = "the link went away while the module was emitting"
                                break
                            }
                            for ((parameter, value) in batch.values) {
                                val key = parameter.rowKey
                                values[key] = value
                                series.getOrPut(key) { Series() }.add(value)
                                recorder?.add(parameter.name, parameter.identifierText, parameter.unit, value)
                                readings++
                            }
                            // One polled reading every EXTRA_EVERY_MS, not one per
                            // turn round the loop. A request costs twenty to forty
                            // milliseconds against a fifty-millisecond slice, so one
                            // per turn is a third of the stream's time spent on the
                            // overflow — and with two parameters in the overflow
                            // that is a bad trade at any price.
                            val now = System.currentTimeMillis()
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
                            recordedRows = recorder?.rows ?: 0
                            recordingName = recorder?.name
                            tick++
                            val elapsed = System.currentTimeMillis() - since
                            if (elapsed >= 1000) {
                                rate = (readings * 1000L / elapsed).toInt()
                                frameRate = (frames * 1000L / elapsed).toInt()
                                readings = 0
                                frames = 0
                                since = System.currentTimeMillis()
                            }
                        }
                        // Between rounds: stop, so the packet numbers are free
                        // for the next declaration. Costs one round trip, which
                        // is why the dwell is seconds and not milliseconds.
                        if (rotation.rounds.size > 1 && isRunning) {
                            runCatching { Session.diagnostics.endStream(module) }
                            round = (round + 1) % rotation.rounds.size
                        }
                    }
                }
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
            } finally {
                // Always, even after a failure: a module left emitting keeps a
                // hundred frames a second coming at an adapter nobody is
                // reading, and the next tool to connect finds the bus busy.
                runCatching { Session.diagnostics.endStream(module) }
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

    private fun openRecorder(record: Boolean, label: String) {
        if (!record) return
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
            while (isRunning) {
                val lapStart = System.currentTimeMillis()
                var readings = 0
                for (t in targets) {
                    if (!isRunning) break
                    if ((misses[t.key] ?: 0) >= GIVE_UP_AFTER) continue
                    val v = try {
                        t.request()
                    } catch (_: Exception) {
                        null
                    }
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
                    val gap = settings.pollIntervalMs.toLong()
                    if (gap > 0) Thread.sleep(gap)
                }
                val elapsed = (System.currentTimeMillis() - lapStart).coerceAtLeast(1)
                rate = (readings * 1000L / elapsed).toInt()
                recordedRows = recorder?.rows ?: 0
                tick++
                // Everything in the rotation went quiet: stop hammering the bus.
                if (readings == 0 && targets.all { (misses[it.key] ?: 0) >= GIVE_UP_AFTER }) break
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

    private companion object {
        const val GIVE_UP_AFTER = 3

        /** How often the overflow gets a turn, so the stream keeps its own. */
        const val EXTRA_EVERY_MS = 300L

        /** How long one read of the emitted stream blocks before looping. */
        const val STREAM_SLICE_MS = 50L

        /**
         * How long a round emits before the next one takes the packets.
         *
         * Switching costs a stop and one declaration per packet — six round
         * trips at twenty to forty milliseconds each — so the dwell has to be
         * seconds for the duty cycle to be worth having. Two seconds against a
         * switch of about a fifth of a second is ninety per cent of the time
         * spent emitting.
         */
        const val DWELL_MS = 2000L

        /** Consecutive refused round declarations before a run is abandoned. */
        const val ROUNDS_GIVE_UP_AFTER = 4
    }
}
