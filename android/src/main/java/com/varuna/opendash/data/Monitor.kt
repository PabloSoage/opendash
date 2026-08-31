package com.varuna.opendash.data

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import com.varuna.opendash.Session
import com.varuna.opendash.obd.Pid
import kotlin.concurrent.thread

/**
 * Polls the parameters you have selected and keeps their recent history.
 *
 * Round-robin, one request at a time, because there is one bus and one socket.
 * Selecting twenty parameters therefore refreshes each of them twenty times
 * more slowly than selecting one — which is worth knowing before wondering why
 * a chart looks coarse.
 *
 * There is no fixed interval by default. The exchange is synchronous, so the
 * device sets the pace; sleeping on top of that only makes it slower. The
 * measured round trip is what the chart resolution is worth.
 */
class Monitor(private val settings: Settings) {

    /** key to its recent window. */
    val series = mutableStateMapOf<String, Series>()

    /** key to the most recent value, formatted by whoever displays it. */
    val values = mutableStateMapOf<String, Double>()

    private val running = mutableStateOf(false)
    private var worker: Thread? = null
    private var recorder: Recorder? = null

    val isRunning: Boolean get() = running.value
    val recordingTo: String? get() = recorder?.path
    val recordedRows: Int get() = recorder?.rows ?: 0

    /** What to poll: standard OBD PIDs, catalogue entries, or a mix. */
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
     * The same standard PIDs, but named and scaled by an installed catalogue.
     *
     * Only the entries this tool knows how to ask for. A catalogue lists PIDs
     * well beyond the mode 01 range — 4427, 54528 — which are read with a
     * service we have not worked out yet, so offering them would be offering
     * something that cannot be fetched. [requestable] is the filter, and
     * [notRequestable] counts what it left out, because a catalogue that shows
     * 21 000 parameters and delivers 25 should say so.
     */
    fun requestable(catalogue: Catalogue): List<Catalogue.Parameter> =
        catalogue.parameters.filter { it.pid in 0..0xff && it.bytes in 1..4 }

    fun notRequestable(catalogue: Catalogue): Int =
        catalogue.parameters.size - requestable(catalogue).size

    fun targetsForCatalogue(params: List<Catalogue.Parameter>): List<Target> = params.map { p ->
        Target("cat:" + p.key, p.name, p.unit) {
            val raw = Session.diagnostics.mode01(p.pid) ?: return@Target null
            if (raw.size < p.bytes) return@Target null
            var value = 0L
            for (i in 0 until p.bytes) value = (value shl 8) or (raw[i].toLong() and 0xff)
            p.scale(value)
        }
    }

    fun start(targets: List<Target>, record: Boolean, label: String) {
        if (running.value || targets.isEmpty()) return
        running.value = true

        if (record) {
            val gz = settings.compressRecordings
            val target = Recorder.newFile(settings.recordingDirectory(), label, gz)
            recorder = Recorder(target, gz).also { it.open() }
        }

        worker = thread(name = "monitor", isDaemon = true) {
            while (running.value) {
                for (t in targets) {
                    if (!running.value) break
                    val v = try {
                        t.request()
                    } catch (_: Exception) {
                        null
                    }
                    if (v != null) {
                        values[t.key] = v
                        series.getOrPut(t.key) { Series() }.add(v)
                        recorder?.add(t.name, t.unit, v)
                    }
                    val gap = settings.pollIntervalMs.toLong()
                    if (gap > 0) Thread.sleep(gap)
                }
            }
        }
    }

    fun stop() {
        running.value = false
        worker = null
        recorder?.close()
        recorder = null
    }

    fun reset() {
        series.clear()
        values.clear()
    }
}
