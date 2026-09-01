package com.varuna.opendash.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.varuna.opendash.Session
import com.varuna.opendash.obd.Pid
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

    var lastError by mutableStateOf<String?>(null)
        private set

    private var worker: Thread? = null
    private var recorder: Recorder? = null

    /** What to poll. [request] returns null when the module does not answer. */
    class Target(
        val key: String,
        val name: String,
        val unit: String,
        val request: () -> Double?,
    )

    fun targetsFor(pids: List<Pid>): List<Target> = pids.map { pid ->
        Target("obd:" + pid.id, pid.name, pid.unit) {
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
        catalogue.parameters.filter { it.pid in 0..0xffff && it.bytes in 1..4 }

    fun notRequestable(catalogue: Catalogue): Int =
        catalogue.parameters.size - requestable(catalogue).size

    fun targetsForCatalogue(params: List<Catalogue.Parameter>): List<Target> = params.map { p ->
        Target("cat:" + p.key, p.name, p.unit) {
            val raw = if (p.pid <= 0xff) {
                Session.diagnostics.mode01(p.pid)
            } else {
                Session.diagnostics.readDataByIdentifier(p.pid)
            } ?: return@Target null
            if (raw.size < p.bytes) return@Target null
            var value = 0L
            for (i in 0 until p.bytes) value = (value shl 8) or (raw[i].toLong() and 0xff)
            p.scale(value)
        }
    }

    fun start(targets: List<Target>, record: Boolean, label: String) {
        if (isRunning || targets.isEmpty()) return
        lastError = null

        if (record) {
            val compress = settings.compressRecordings
            recorder = try {
                Recorder(store.create(label, compress), compress).also {
                    recordingName = it.name
                }
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                null
            }
        }

        isRunning = true
        recordedRows = 0
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
                        recorder?.add(t.name, t.unit, v)
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
            isRunning = false
        }
    }

    fun stop() {
        isRunning = false
        worker = null
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
    }
}
