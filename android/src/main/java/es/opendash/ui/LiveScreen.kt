package es.opendash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import es.opendash.R
import es.opendash.Session
import es.opendash.data.Monitor
import es.opendash.obd.Pid
import es.opendash.obd.Pids
import kotlin.concurrent.thread

/**
 * Live values, as numbers and as lines.
 *
 * Pick what to watch and it is polled round-robin. That is worth knowing: with
 * one parameter selected the chart is smooth, with twenty each one refreshes
 * twenty times more slowly. The count is shown so the trade-off is visible
 * rather than surprising.
 *
 * The supported list comes from the car, not from a guess: mode 01 PID 00
 * returns a bitmask of what this engine answers, so nothing offered here is
 * something it will refuse.
 */
@Composable
fun LiveScreen(monitor: Monitor) {
    val selected = remember { mutableStateListOf<Int>() }
    var available by remember { mutableStateOf<List<Pid>>(emptyList()) }
    var record by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                enabled = !busy && Session.state == Session.State.CHANNEL_OPEN,
                onClick = {
                    busy = true
                    thread {
                        val ids = Session.diagnostics.supportedPids()
                        available = Pids.standard.filter { it.id in ids }
                        busy = false
                    }
                },
            ) { Text(stringResource(R.string.live_scan)) }

            Text(
                stringResource(R.string.live_selected, selected.size),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Switch(checked = record, onCheckedChange = { record = it }, enabled = !monitor.isRunning)
            Text(stringResource(R.string.live_record))
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
            if (monitor.isRunning) {
                OutlinedButton(onClick = { monitor.stop() }) {
                    Text(stringResource(R.string.live_stop))
                }
            } else {
                Button(
                    enabled = selected.isNotEmpty() && Session.state == Session.State.CHANNEL_OPEN,
                    onClick = {
                        monitor.reset()
                        val pids = available.filter { it.id in selected }
                        monitor.start(monitor.targetsFor(pids), record, "live")
                    },
                ) { Text(stringResource(R.string.live_start)) }
            }
        }

        if (monitor.isRunning && monitor.recordingTo != null) {
            Text(
                stringResource(R.string.live_recording_to, monitor.recordedRows, monitor.recordingTo ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            // What is running goes on top, charted.
            items(available.filter { it.id in selected }, key = { "chart-" + it.id }) { pid ->
                val s = monitor.series["obd:" + pid.id]
                if (s != null && s.size > 1) {
                    ParameterChart(pid.name, pid.unit, s)
                }
            }

            items(available, key = { "pick-" + it.id }) { pid ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = pid.id in selected,
                        enabled = !monitor.isRunning,
                        onCheckedChange = {
                            if (it) selected.add(pid.id) else selected.remove(pid.id)
                        },
                    )
                    Text(pid.name, modifier = Modifier.weight(1f))
                    Text(pid.unit, style = MaterialTheme.typography.labelSmall)
                }
            }

            if (available.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.live_scan_first),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
            }
        }
    }
}
