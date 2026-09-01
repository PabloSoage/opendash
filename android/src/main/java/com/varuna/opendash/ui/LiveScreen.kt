package com.varuna.opendash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.varuna.opendash.R
import com.varuna.opendash.Session
import com.varuna.opendash.data.Catalogue
import com.varuna.opendash.data.Monitor
import com.varuna.opendash.data.PluginRepository
import com.varuna.opendash.data.Settings
import com.varuna.opendash.ui.theme.ValueStyle
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Live values, as numbers and as lines.
 *
 * Everything selected is polled round-robin, because there is one bus and one
 * socket. Twenty parameters therefore refresh twenty times more slowly than
 * one, which is why the measured rate sits next to the count rather than being
 * left as a surprise.
 *
 * Charts are limited to a few at a time, from settings. The rest of the
 * selection still shows as numbers, which is what most of them are wanted for.
 */
@Composable
fun LiveScreen(monitor: Monitor, plugins: PluginRepository, settings: Settings) {
    val selected = remember { mutableStateListOf<String>() }
    var rows by remember { mutableStateOf<List<Item>>(emptyList()) }
    var catalogue by remember { mutableStateOf<Catalogue?>(null) }
    var filter by remember { mutableStateOf("") }
    var record by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    val installed = remember(plugins.revision) { plugins.installed() }

    fun rebuild() {
        busy = true
        thread {
            val supported = Session.diagnostics.supportedPids()
            val standard = com.varuna.opendash.obd.Pids.standard.filter { it.id in supported }
            val c = catalogue
            rows = if (c == null) {
                standard.map { Item.Standard(it) }
            } else {
                // A catalogue renames and rescales the same identifiers, and
                // adds the two-byte ones the vehicle never advertises. The
                // standard list stays, so nothing is lost by turning it on.
                //
                // The variant is what makes it usable. A brand catalogue is
                // every module configuration the marque ever shipped — 21 382
                // parameters for Opel, 14 117 of them two-byte — and one
                // vehicle is a handful of them. Narrowed to a variant, an
                // engine module is a few hundred.
                val keys = settings.catalogueVariant
                    .takeIf { it.isNotEmpty() }
                    ?.let { c.variants[it]?.toSet() }
                val pool = monitor.requestable(c)
                    .let { all -> if (keys == null) all else all.filter { it.key in keys } }

                val byPid = pool.groupBy { it.pid }
                val named = standard.map { pid ->
                    byPid[pid.id]?.firstOrNull()?.let { Item.FromCatalogue(it, certain = true) }
                        ?: Item.Standard(pid)
                }
                val extra = pool
                    .filter { it.pid > 0xff }
                    .map { Item.FromCatalogue(it, certain = false) }
                named + extra
            }
            busy = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {

        Panel {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Button(
                    enabled = !busy && !monitor.isRunning &&
                        Session.state == Session.State.CHANNEL_OPEN,
                    onClick = { rebuild() },
                ) {
                    Text(
                        when {
                            busy -> stringResource(R.string.state_working)
                            rows.isEmpty() -> stringResource(R.string.live_scan)
                            else -> stringResource(R.string.live_rescan)
                        }
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.live_selected, selected.size, rows.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (installed.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = catalogue == null,
                        enabled = !monitor.isRunning,
                        onClick = {
                            catalogue = null
                            selected.clear()
                            rows = emptyList()
                        },
                        label = { Text(stringResource(R.string.live_catalogue_off)) },
                    )
                    installed.forEach { brand ->
                        FilterChip(
                            selected = catalogue?.brand == brand,
                            enabled = !monitor.isRunning,
                            onClick = {
                                catalogue = plugins.load(brand, settings.catalogueLanguage)
                                selected.clear()
                                rows = emptyList()
                            },
                            label = { Text(brand) },
                        )
                    }
                }
                catalogue?.let { c ->
                    if (c.variants.isNotEmpty()) {
                        val allVariants = stringResource(R.string.live_variant_all)
                        val names = remember(c) { listOf("") + c.variants.keys.sorted() }
                        Combo(
                            label = stringResource(R.string.live_variant),
                            value = settings.catalogueVariant,
                            options = names,
                            render = { name ->
                                if (name.isEmpty()) allVariants
                                else name + "  (" + (c.variants[name]?.size ?: 0) + ")"
                            },
                            onSelect = {
                                settings.catalogueVariant = it
                                selected.clear()
                                rows = emptyList()
                            },
                        )
                        Hint(stringResource(R.string.live_variant_hint))
                    }
                    Hint(
                        stringResource(
                            R.string.live_coverage,
                            monitor.requestable(c).size,
                            monitor.notRequestable(c),
                        )
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Switch(
                    checked = record,
                    enabled = !monitor.isRunning,
                    onCheckedChange = { record = it },
                )
                Text(
                    stringResource(R.string.live_record),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (monitor.isRunning) {
                    OutlinedButton(onClick = { monitor.stop() }) {
                        Text(stringResource(R.string.action_stop))
                    }
                } else {
                    Button(
                        enabled = selected.isNotEmpty() &&
                            Session.state == Session.State.CHANNEL_OPEN,
                        onClick = {
                            monitor.reset()
                            val chosen = rows.filter { it.key in selected }
                            monitor.start(targetsFor(monitor, chosen), record, "live")
                        },
                    ) { Text(stringResource(R.string.action_start)) }
                }
            }

            if (monitor.isRunning) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(R.string.live_rate, monitor.rate),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    monitor.recordingName?.let {
                        Text(
                            stringResource(R.string.live_recording, monitor.recordedRows, it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            ErrorLine(monitor.lastError)
        }

        val charted = rows.filter { it.key in selected }.take(settings.chartCount)

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (monitor.isRunning) {
                items(charted, key = { "chart:" + it.key }) { row ->
                    val series = monitor.series[row.key]
                    if (series != null && series.size > 1) {
                        ParameterChart(row.name, row.unit, series, monitor.tick)
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.live_chart_limit, settings.chartCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Slider(
                            value = settings.chartCount.toFloat(),
                            onValueChange = { settings.chartCount = it.toInt().coerceIn(0, 12) },
                            valueRange = 0f..12f,
                            steps = 11,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
                items(rows.filter { it.key in selected }, key = { "value:" + it.key }) { row ->
                    ValueRow(row, monitor)
                }
            }

            if (rows.isEmpty()) {
                item {
                    Hint(
                        stringResource(R.string.live_empty),
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            } else if (!monitor.isRunning) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = filter,
                            onValueChange = { filter = it },
                            label = { Text(stringResource(R.string.live_search)) },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        if (selected.isNotEmpty()) {
                            AssistChip(
                                onClick = { selected.clear() },
                                label = { Text(stringResource(R.string.live_clear_selection)) },
                            )
                        }
                    }
                }
                val visible = if (filter.isBlank()) rows
                else rows.filter { it.name.contains(filter, ignoreCase = true) }
                items(visible, key = { "pick:" + it.key }) { row ->
                    PickRow(row, row.key in selected) { on ->
                        if (on) selected.add(row.key) else selected.remove(row.key)
                    }
                }
            }
        }
    }
}

/**
 * One line of the selection list.
 *
 * Two-byte identifiers are marked, because they are an educated request rather
 * than a certainty: the vehicle advertises its mode 01 support and says nothing
 * about the rest, so those may simply not answer.
 */
@Composable
private fun PickRow(row: Item, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                row.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!row.certain) {
                Text(
                    row.identifier,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            row.unit,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ValueRow(row: Item, monitor: Monitor) {
    val value = monitor.values[row.key]
    val quiet = monitor.silent[row.key] == true
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            row.name,
            style = MaterialTheme.typography.bodyMedium,
            color = if (quiet) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            when {
                quiet -> "—"
                value == null -> "…"
                else -> String.format(Locale.ROOT, "%.2f", value)
            },
            style = ValueStyle,
            color = if (quiet) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.primary,
        )
        Text(
            " " + row.unit,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Something that can be watched, from either source. */
private sealed class Item {
    abstract val key: String
    abstract val name: String
    abstract val unit: String

    /** True when the vehicle itself said it supports this. */
    abstract val certain: Boolean
    abstract val identifier: String

    class Standard(val pid: com.varuna.opendash.obd.Pid) : Item() {
        override val key = "obd:" + pid.id
        override val name = pid.name
        override val unit = pid.unit
        override val certain = true
        override val identifier = String.format(Locale.ROOT, "PID %02X", pid.id)
    }

    class FromCatalogue(
        val parameter: Catalogue.Parameter,
        override val certain: Boolean,
    ) : Item() {
        override val key = "cat:" + parameter.key
        override val name = parameter.name
        override val unit = parameter.unit
        override val identifier =
            String.format(Locale.ROOT, "0x%04X · service 0x22", parameter.pid)
    }
}

private fun targetsFor(monitor: Monitor, rows: List<Item>): List<Monitor.Target> {
    val standard = rows.filterIsInstance<Item.Standard>().map { it.pid }
    val catalogue = rows.filterIsInstance<Item.FromCatalogue>().map { it.parameter }
    return monitor.targetsFor(standard) + monitor.targetsForCatalogue(catalogue)
}
