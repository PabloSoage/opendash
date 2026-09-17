package com.varuna.opendash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.varuna.opendash.R
import com.varuna.opendash.Session
import com.varuna.opendash.data.CarProfile
import com.varuna.opendash.data.Catalogue
import com.varuna.opendash.data.Monitor
import com.varuna.opendash.data.Presets
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
    // On by default. The cost of a recording nobody wanted is 600 kB; the
    // cost of a drive nobody recorded is the drive.
    var record by remember { mutableStateOf(true) }
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

    // Seeing the selection, and naming it. See the list header below.
    var onlySelected by remember { mutableStateOf(false) }
    val presets = remember { Presets(context) }
    var presetRevision by remember { mutableIntStateOf(0) }
    val presetNames = remember(settings.catalogueModule, presetRevision) {
        presets.names(settings.catalogueModule)
    }
    var showPresets by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }

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

    // While a run is going, the screen stays on if this screen is what is being
    // looked at. The session no longer depends on it — LiveService holds the
    // link up with the screen off — but a screen that blanks while somebody is
    // reading a gauge is its own problem.
    val view = LocalView.current
    DisposableEffect(monitor.isRunning) {
        view.keepScreenOn = monitor.isRunning
        onDispose { view.keepScreenOn = false }
    }

    // Naming a selection, and picking one back up.
    if (naming) {
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text(stringResource(R.string.live_preset_save)) },
            text = {
                OutlinedTextField(
                    value = presetName,
                    onValueChange = { presetName = it },
                    label = { Text(stringResource(R.string.live_preset_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = presetName.isNotBlank(),
                    onClick = {
                        presets.save(settings.catalogueModule, presetName.trim(), selected.keys.toSet())
                        presetRevision++
                        presetName = ""
                        naming = false
                    },
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { naming = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
    if (showPresets) {
        AlertDialog(
            onDismissRequest = { showPresets = false },
            title = { Text(stringResource(R.string.live_presets_title)) },
            text = {
                Column {
                    for (name in presetNames) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        ) {
                            TextButton(
                                onClick = {
                                    // Replaces the selection rather than adding
                                    // to it: a preset is what you meant to
                                    // watch, not an addition to whatever was
                                    // left ticked from last time. Rows the
                                    // catalogue no longer has are dropped.
                                    val keys = presets.load(settings.catalogueModule, name)
                                    val present = rows.map { it.key }.toSet()
                                    selected.clear()
                                    for (k in keys) if (k in present) selected[k] = Unit
                                    showPresets = false
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text(name) }
                            TextButton(onClick = {
                                val text = presets.export(settings.catalogueModule, name)
                                val clip =
                                    context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                        as android.content.ClipboardManager
                                clip.setPrimaryClip(
                                    android.content.ClipData.newPlainText("opendash preset", text)
                                )
                            }) { Text(stringResource(R.string.action_export)) }
                            TextButton(onClick = {
                                presets.forget(settings.catalogueModule, name)
                                presetRevision++
                            }) { Text(stringResource(R.string.action_forget)) }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPresets = false }) {
                    Text(stringResource(R.string.action_close))
                }
            },
            dismissButton = {
                // Paste one in. A preset is plain text by design, so it travels
                // in a message, a note or a repository as easily as between two
                // phones.
                TextButton(onClick = {
                    val clip =
                        context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                    val text = clip.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                    if (text != null && presets.import(text) != null) presetRevision++
                }) { Text(stringResource(R.string.action_import)) }
            },
        )
    }

    // One scrolling surface, with the setup panel as the list's first item.
    // Side by side in a Column, the panel is measured first and takes every
    // pixel it asks for; the list gets what is left, which on a phone held
    // upright is nothing at all — the panel runs off the bottom of the screen
    // and there is no way to scroll down to the rest of it.
    val charted = rows.filter { it.key in selected.keys }.take(settings.chartCount)

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
    ) {

        item {
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
                    // Weighted rather than pushed by a spacer. A Row measures
                    // the children without a weight first, at whatever width
                    // they ask for, and the last one gets what is left over —
                    // which on a phone held upright was nothing, so the control
                    // that folds this panel away was itself off the screen.
                    Text(
                        stringResource(R.string.live_selected, selected.size, rows.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
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
                        val present = Session.modulesPresent
                        val modules = remember(c, present) {
                            c.namedModules.sortedWith(
                                compareByDescending<String> { name ->
                                    c.addressesByModule[name].orEmpty().any { it in present }
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
                                    val onThisCar = c.addressesByModule[name].orEmpty().any { it in present }
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
                            // How much the car's own answers actually cut, as
                            // both numbers. On its own a count of what is kept
                            // says nothing: 204 is either a filter working or a
                            // filter throwing away most of what this module has,
                            // and there is no telling which without the total.
                            val kept = remember(pool, answered) {
                                answered?.let { known ->
                                    c.keptTo(pool, known).count { it.pid in 0..0xffff && it.bytes in 1..4 }
                                }
                            }
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
                                        label = {
                                        Text(
                                            stringResource(
                                                R.string.live_profile_only,
                                                kept ?: known.size,
                                                askable,
                                            )
                                        )
                                    },
                                    )
                                }
                            }
                            if (answered == null) Hint(stringResource(R.string.live_profile_hint))
                        }
                    }
                }

                }

                // Outside the panel, because the panel folds away as soon as
                // there is something to watch and these are the two decisions
                // taken immediately before pressing Start. Folded out of sight,
                // "record this session" is a switch nobody remembers — which is
                // how a twenty-minute drive comes home unrecorded.
                //
                // One switch per line: two of them side by side needs about
                // 380dp between the switches themselves and two labels that are
                // sentences, and a phone held upright has 360.
                Toggle(
                    checked = record,
                    enabled = !monitor.isRunning,
                    onChange = { record = it },
                    label = stringResource(R.string.live_record),
                )
                // Only offered when every chosen row is a catalogue parameter of
                // one module. A standard OBD PID is addressed to whoever answers
                // rather than to one module, so it has no packet to belong to.
                Toggle(
                    checked = streaming && canStream,
                    enabled = !monitor.isRunning && canStream,
                    onChange = { streaming = it },
                    label = stringResource(R.string.live_stream),
                )
                if (!canStream && selected.isNotEmpty()) {
                    // A switch that will not move and does not say why is worse
                    // than no switch. There are only two reasons it can refuse.
                    val standard = selected.size - chosenParameters.size
                    Hint(
                        if (settings.catalogueModule.isEmpty()) {
                            stringResource(R.string.live_stream_needs_module)
                        } else {
                            stringResource(R.string.live_stream_has_standard, standard)
                        }
                    )
                }
                if (canStream && streaming) {
                    Hint(stringResource(R.string.live_stream_hint))
                    // Said rather than swallowed: what did not fit in the
                    // packets is polled, which is slower, and a row moving once
                    // a second next to one moving a hundred times a second
                    // needs explaining before it is noticed.
                    val left = streamPlan?.leftOut?.size ?: 0
                    if (left > 0) Hint(stringResource(R.string.live_stream_left_out, left))
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
                            stringResource(R.string.live_rate, monitor.rate, monitor.frameRate),
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
        }

        if (monitor.isRunning) {
            items(charted, key = { "chart:" + it.key }) { row ->
                val series = monitor.series[row.key]
                if (series != null && series.size > 1) {
                    // Two charts titled the same thing, one at 102 and one at
                    // 128, is not a reading anybody can use.
                    val title = if (row.certain) row.name else row.name + "  " + row.identifier
                    ParameterChart(title, row.unit, series, monitor.tick)
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
            // Seeing what is already chosen, and keeping it.
            //
            // A selection is built through a search box, and a search box shows
            // what matches rather than what is picked: with a few hundred rows
            // there is no way to look at the dozen already ticked without
            // remembering all twelve names first. And once built, it is the same
            // dozen next time — worth naming and keeping rather than rebuilding
            // through the search box again.
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = onlySelected,
                        onClick = { onlySelected = !onlySelected },
                        enabled = selected.isNotEmpty(),
                        label = { Text(stringResource(R.string.live_only_selected, selected.size)) },
                    )
                    AssistChip(
                        onClick = { showPresets = true },
                        label = { Text(stringResource(R.string.live_presets, presetNames.size)) },
                    )
                    if (selected.isNotEmpty() && settings.catalogueModule.isNotEmpty()) {
                        AssistChip(
                            onClick = { naming = true },
                            label = { Text(stringResource(R.string.live_preset_save)) },
                        )
                    }
                }
            }
            val visible = when {
                onlySelected -> rows.filter { it.key in selected.keys }
                filter.isBlank() -> rows
                else -> rows.filter { it.name.contains(filter, ignoreCase = true) }
            }
            items(visible, key = { "pick:" + it.key }) { row ->
                PickRow(row, row.key in selected.keys) { on ->
                    if (on) selected[row.key] = Unit else selected.remove(row.key)
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
        // The identifier under the name, for the same reason the picker shows
        // it: a marque catalogue names the same thing once per engine variant,
        // each reading its own identifier with its own scaling. Watch two of
        // them and without this they are two identical rows disagreeing with
        // each other — 102 °C against 128 °C, both called Exhaust Gas
        // Temperature Sensor 1.
        Column(modifier = Modifier.weight(1f)) {
            Text(
                row.name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (quiet) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
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
            when {
                quiet -> "—"
                value == null -> "…"
                // A bit is on or off. Printed as 1.00 next to an empty unit it
                // reads like a measurement nobody could put a unit to, which is
                // exactly how it reads on screen today.
                row.isFlag -> if (value != 0.0) stringResource(R.string.live_on)
                else stringResource(R.string.live_off)
                else -> String.format(Locale.ROOT, "%.2f", value)
            },
            style = ValueStyle,
            color = if (quiet) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.primary,
        )
        if (!row.isFlag) {
            Text(
                " " + row.unit,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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

    /** A single bit of a packed byte: on or off, not a quantity. */
    open val isFlag: Boolean get() = false

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
        // The signature, not the key. A list keyed by the catalogue key repeats
        // itself — 641 repeats in this car's engine list, one of them thirty-
        // seven times — and a LazyColumn handed a key it has already seen
        // throws, which takes the app down a second or two after the scan
        // finishes. It also meant ticking one row ticked all its namesakes,
        // since the selection is held by this same key.
        override val key = parameter.rowKey
        override val name = parameter.name
        override val unit = parameter.unit
        override val identifier =
            String.format(Locale.ROOT, "0x%04X · service 0x22", parameter.pid)
        override val isFlag = parameter.isFlag
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

/**
 * A switch with its caption, on a line of its own.
 *
 * The caption takes the leftover width and wraps rather than being clipped, so
 * a switch is never left standing there captioning itself.
 */
@Composable
private fun Toggle(
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
    label: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}
