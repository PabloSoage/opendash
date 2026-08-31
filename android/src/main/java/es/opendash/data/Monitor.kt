package es.opendash.data

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import es.opendash.Session
import es.opendash.obd.Pid
import kotlin.concurrent.thread

/**
 * Polls the parameters you have selected and keeps their recent history.
 *
 * Round-robin, one request at a time, because there is one bus and one socket.
 * Selecting twenty parameters therefore refreshes each of them twenty times
 * more slowly than selecting one — which is worth knowing before wondering why
 * a chart looks coarse.
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
        Target("obd:${pid.id}", pid.name, pid.unit) {
            Session.diagnostics.mode01(pid.id)?.let { pid.value(it) }
        }
    }

    fun start(targets: List<Target>, record: Boolean, label: String) {
        if (running.value || targets.isEmpty()) return
        running.value = true

        if (record) {
            recorder = Recorder(Recorder.newFile(settings.recordingDirectory(), label)).also { it.open() }
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
                    Thread.sleep(settings.pollIntervalMs.toLong())
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
