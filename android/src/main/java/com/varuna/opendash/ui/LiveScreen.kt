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
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.varuna.opendash.R
import com.varuna.opendash.Session
import com.varuna.opendash.data.CarProfile
import com.varuna.opendash.data.Catalogue
import com.varuna.opendash.data.Monitor
import com.varuna.opendash.data.PluginRepository
import com.varuna.opendash.data.Settings
import com.varuna.opendash.obd.Diagnostics
import com.varuna.opendash.obd.Stream
import com.varuna.opendash.ui.theme.ValueStyle
import java.util.Locale
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    // A map rather than a list. Membership is tested once per visible row
    // and again for every row when the selection is collected, and a list makes
    // each of those a scan: with a few thousand parameters on screen that is
    // millions of comparisons on the main thread, which is a frozen app and
    // then a dead one.
    val selected = remember { mutableStateMapOf<String, Unit>() }
    var rows by remember { mutableStateOf<List<Item>>(emptyList()) }
    var catalogue by remember { mutableStateOf<Catalogue?>(null) }
    var filter by remember { mutableStateOf("") }
    var record by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    // The setup panel, folded away.
    //
    // Open, it runs from the scan button to Start and takes three quarters of
    // the screen; with the filter field above the list that left about an
    // eighth of it to read parameters in and scroll through, which is where the
    // work happens. Setup is chosen once and the list is watched for a long
    // time, so it folds itself away as soon as there is something to watch.
    var setupOpen by remember { mutableStateOf(true) }

    var streaming by remember { mutableStateOf(true) }

    // What this car answered when it was asked, and whether the list is being
    // kept to it. See CarProfile: the catalogue cannot say which of a marque's
    // configurations is parked outside, so the car is asked once and the answer
    // is remembered.
    val context = LocalContext.current
    val profiles = remember { CarProfile(context) }
    var profileVin by remember { mutableStateOf("") }
    var answered by remember { mutableStateOf<Set<Int>?>(null) }
    var probing by remember { mutableStateOf(false) }
    var probeDone by remember { mutableIntStateOf(0) }
    var probeTotal by remember { mutableIntStateOf(0) }
    var keepToCar by remember { mutableStateOf(true) }

    val installed = remember(plugins.revision) { plugins.installed() }

    // Where the chosen module answers. A module name can sit at more than one
    // address across the marque, so prefer one this car actually answered on.
    val moduleAddress = run {
        val addresses = catalogue?.addressesByModule?.get(settings.catalogueModule).orEmpty()
        addresses.firstOrNull { it in Session.modulesPresent }
            ?: addresses.firstOrNull()
            ?: Diagnostics.ENGINE
    }

    // The packets to declare, if the selection can be streamed at all. Only a
    // catalogue module can: a standard OBD PID is addressed to whoever answers
    // rather than to one module, so it has no packet to belong to.
    val chosenParameters = rows
        .filter { it.key in selected.keys }
        .filterIsInstance<Item.FromCatalogue>()
        .map { it.parameter }
    val canStream = selected.isNotEmpty() &&
        chosenParameters.size == selected.size &&
        settings.catalogueModule.isNotEmpty()
    val streamPlan =
        if (canStream) Stream.plan(moduleAddress, chosenParameters) else null

    // The stored profile for this module, if this car has one.
    LaunchedEffect(settings.catalogueModule, Session.state) {
        if (Session.state != Session.State.CHANNEL_OPEN) return@LaunchedEffect
        val vin = withContext(Dispatchers.IO) { Session.guarded { Session.diagnostics.vin() } }.orEmpty()
        profileVin = vin
        answered = profiles.answered(vin, moduleAddress)
    }

    fun profileCar() {
        probing = true
        thread {
            try {
                Session.guarded {
                    val vin = Session.diagnostics.vin().orEmpty()
                    profileVin = vin
                    val c = catalogue ?: return@guarded
                    val ids = c.parametersFor(settings.catalogueModule, settings.catalogueVariant)
                        .map { it.pid }
                        .filter { it in 0..0xffff }
                        .distinct()
                    probeTotal = ids.size
                    val found = Session.diagnostics.probe(
                        ids = ids,
                        module = moduleAddress,
                        onProgress = { done, total -> probeDone = done; probeTotal = total },
                    )
                    profiles.save(vin, moduleAddress, ids.toSet(), found)
                    answered = found
                }
            } finally {
                probing = false
                probeDone = 0
            }
        }
    }

    fun rebuild() {
        busy = true
        thread {
            // Guarded: asking the car what it supports is a socket
            // conversation, and a link that goes mid-question must end the
            // rebuild, not the app.
            try {
                Session.guarded {
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
                        // The module is what makes it usable. A brand catalogue is
                        // every configuration the marque ever shipped — 21 382
                        // parameters for Opel — and one vehicle is a fraction of
                        // them: the four modules that answer on this car account
                        // for 5 941, the engine alone for 4 080, and one variant of
                        // the engine for a few hundred. Without a module chosen
                        // this listed all 21 382, which is not a list, it is a
                        // reason to close the screen.
                        val module = settings.catalogueModule
                        val all = if (module.isEmpty()) emptyList() else {
                            c.parametersFor(module, settings.catalogueVariant)
                        }
                        val known = answered
                        val pool = monitor.requestable(
                            if (keepToCar && known != null) c.keptTo(all, known) else all
                        )

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
                }
                // Once there is something to look at, the panel is in the way.
                if (rows.isNotEmpty()) setupOpen = false
            } finally {
                busy = false
            }
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
                TextButton(onClick = { setupOpen = !setupOpen }) {
                    Text(
                        stringResource(
                            if (setupOpen) R.string.live_setup_hide else R.string.live_setup_show
                        )
                    )
                }
            }
            // What was chosen, in one line, while the panel is folded away.
            // Without it, folding the panel hides which module the list is even
            // about.
            if (!setupOpen && settings.catalogueModule.isNotEmpty()) {
                Text(
                    settings.catalogueModule,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (setupOpen) {
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
                    // Module first, variant second. A brand catalogue is every
                    // configuration the marque ever shipped, and offered as one
                    // flat list of variant names it is 221 entries of things
                    // like "Amplifier - NGI" with no way to tell which of them
                    // has anything to do with this car. Grouped by module it is
                    // a dozen readable names, and the ones this car answered on
                    // come first.
                    val answered = Session.modulesPresent
                    val modules = remember(c, answered) {
                        c.namedModules.sortedWith(
                            compareByDescending<String> { name ->
                                c.addressesByModule[name].orEmpty().any { it in answered }
                            }.thenBy { it }
                        )
                    }
                    if (modules.isNotEmpty()) {
                        val here = stringResource(R.string.live_module_here)
                        Combo(
                            label = stringResource(R.string.live_module),
                            value = settings.catalogueModule.takeIf { it in modules }.orEmpty(),
                            options = modules,
                            render = { name ->
                                val onThisCar = c.addressesByModule[name].orEmpty().any { it in answered }
                                name + (if (onThisCar) "  $here" else "")
                            },
                            onSelect = {
                                settings.catalogueModule = it
                                settings.catalogueVariant = ""
                                selected.clear()
                                rows = emptyList()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    val module = settings.catalogueModule.takeIf { it in modules }.orEmpty()
                    val variants = remember(c, module) { c.variantsOf(module) }
                    if (variants.size > 1) {
                        val allVariants = stringResource(R.string.live_variant_all)
                        Combo(
                            label = stringResource(R.string.live_variant),
                            value = settings.catalogueVariant,
                            options = listOf("") + variants.map { it.name },
                            render = { name ->
                                if (name.isEmpty()) {
                                    allVariants + "  (" + c.parametersFor(module).size + ")"
                                } else {
                                    // The module name is repeated at the front
                                    // of every one of its variants; saying it
                                    // twice on one screen helps nobody.
                                    name.removePrefix(module).trim(' ', '-') +
                                        "  (" + (variants.firstOrNull { it.name == name }?.keys?.size ?: 0) + ")"
                                }
                            },
                            onSelect = {
                                settings.catalogueVariant = it
                                selected.clear()
                                rows = emptyList()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Hint(stringResource(R.string.live_variant_hint))
                    }
                    if (module.isEmpty()) {
                        Hint(stringResource(R.string.live_module_hint))
                    } else {
                        val pool = remember(c, module, settings.catalogueVariant) {
                            c.parametersFor(module, settings.catalogueVariant)
                        }
                        val askable = remember(pool) { pool.count { it.pid in 0..0xffff && it.bytes in 1..4 } }
                        Hint(
                            stringResource(R.string.live_coverage, askable, pool.size - askable)
                        )
                        // Asking the car which of them it has. The catalogue
                        // cannot say, so this is the only honest filter there
                        // is — and it is affordable because a module refuses
                        // politely: 7F 22 31 comes back in tens of
                        // milliseconds, not as a timeout.
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            OutlinedButton(
                                enabled = !probing && !busy && !monitor.isRunning &&
                                    Session.state == Session.State.CHANNEL_OPEN,
                                onClick = { profileCar() },
                            ) {
                                Text(
                                    if (probing) {
                                        stringResource(R.string.live_profile_busy, probeDone, probeTotal)
                                    } else {
                                        stringResource(R.string.live_profile)
                                    }
                                )
                            }
                            answered?.let { known ->
                                FilterChip(
                                    selected = keepToCar,
                                    enabled = !monitor.isRunning,
                                    onClick = { keepToCar = !keepToCar },
                                    label = { Text(stringResource(R.string.live_profile_only, known.size)) },
                                )
                            }
                        }
                        if (answered == null) Hint(stringResource(R.string.live_profile_hint))
                    }
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
                )
                Spacer(Modifier.weight(1f))
                // Only offered when every chosen row is a catalogue parameter.
                // A standard OBD PID is addressed to whoever answers rather
                // than to one module, so it has no packet to belong to.
                Switch(
                    checked = streaming && canStream,
                    enabled = !monitor.isRunning && canStream,
                    onCheckedChange = { streaming = it },
                )
                Text(
                    stringResource(R.string.live_stream),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (canStream && streaming) {
                Hint(stringResource(R.string.live_stream_hint))
                // Said rather than swallowed: a parameter that did not fit is a
                // row that would sit there never moving, and there is no way to
                // tell that from one the module refuses.
                val left = streamPlan?.leftOut?.size ?: 0
                if (left > 0) Hint(stringResource(R.string.live_stream_left_out, left))
            }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
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
                            val chosen = rows.filter { it.key in selected.keys }
                            val plan = streamPlan
                            if (streaming && plan != null && !plan.isEmpty) {
                                monitor.startStream(plan, record, "live")
                            } else {
                                monitor.start(targetsFor(monitor, chosen, moduleAddress), record, "live")
                            }
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

        val charted = rows.filter { it.key in selected.keys }.take(settings.chartCount)

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
                items(rows.filter { it.key in selected.keys }, key = { "value:" + it.key }) { row ->
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
                    PickRow(row, row.key in selected.keys) { on ->
                        if (on) selected[row.key] = Unit else selected.remove(row.key)
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

/**
 * [module] is where the catalogue parameters are asked: they belong to one
 * module, and asking the engine about a body module parameter gets silence.
 * The standard OBD list is unaffected, being addressed functionally.
 */
private fun targetsFor(monitor: Monitor, rows: List<Item>, module: Int): List<Monitor.Target> {
    val standard = rows.filterIsInstance<Item.Standard>().map { it.pid }
    val catalogue = rows.filterIsInstance<Item.FromCatalogue>().map { it.parameter }
    return monitor.targetsFor(standard) + monitor.targetsForCatalogue(catalogue, module)
}
