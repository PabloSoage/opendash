package com.varuna.opendash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.varuna.opendash.obd.Actuation
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
 * ## What this screen is for, and what it was doing instead
 *
 * Three jobs live here and only one of them is the point. Choosing a catalogue
 * and a module is done once. Picking the dozen rows to watch is done at the
 * start of a session. **Watching them is done for two and a half hours.**
 *
 * The old layout showed all three at once, stacked, with the setup panel as the
 * first item of the same scrolling list as the values. On a phone held upright
 * that meant the job you do for hours was below the job you do once, and it
 * fought for width with a row of four chips that did not fit. Folding the panel
 * helped and did not fix it: a fold is still a thing on the screen, and it
 * unfolds itself every time the module changes.
 *
 * So: **one mode at a time.**
 *
 *  - A header that is always there and never more than two lines. It says what
 *    is going on and carries the one button that matters — Scan, then Start,
 *    then Stop.
 *  - Under it, the whole rest of the screen, given to exactly one thing: the
 *    values and charts while a run is going, the picker when it is not.
 *  - Everything else — catalogue, module, variant, the probe, recording, fast
 *    mode, the chart count, actuation — in two sheets that slide over the top
 *    and go away again. A sheet can be as tall as it likes without stealing a
 *    pixel from the list underneath.
 *
 * Everything selected is polled round-robin unless fast mode is on, because
 * there is one bus and one socket. Twenty parameters therefore refresh twenty
 * times more slowly than one, which is why the measured rate sits in the header
 * rather than being left as a surprise.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
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

    var streaming by remember { mutableStateOf(true) }

    // The two sheets. Nothing on this screen is hidden behind them that has to
    // be seen while driving; everything behind them is a decision taken once.
    var showSetup by remember { mutableStateOf(false) }
    var showOptions by remember { mutableStateOf(false) }

    // What this car answered when it was asked, and whether the list is being
    // kept to it. See CarProfile: the catalogue cannot say which of a marque's
    // configurations is parked outside, so the car is asked once and the answer
    // is remembered.
    val context = LocalContext.current
    val profiles = remember { CarProfile(context) }
    var profileVin by remember { mutableStateOf("") }
    var answered by remember { mutableStateOf<Set<Int>?>(null) }
    // How wide the module's answer was, per identifier. The catalogue lists
    // rows of several widths under one identifier and only the car knows which
    // applies — see CarProfile.widths.
    var widths by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }
    var probing by remember { mutableStateOf(false) }
    var probeDone by remember { mutableIntStateOf(0) }
    var probeTotal by remember { mutableIntStateOf(0) }
    var keepToCar by remember { mutableStateOf(true) }

    // Seeing the selection, and naming it. See the picker below.
    var onlySelected by remember { mutableStateOf(false) }
    // And seeing only what the module drives. See Actuation for what the
    // padlock on those rows means and, just as importantly, what it does not.
    var onlyCommandable by remember { mutableStateOf(false) }
    var releasing by remember { mutableStateOf(false) }
    var releaseNote by remember { mutableStateOf<Int?>(null) }
    val presets = remember { Presets(context) }
    var presetRevision by remember { mutableIntStateOf(0) }
    val presetNames = remember(settings.catalogueModule, presetRevision) {
        presets.names(settings.catalogueModule)
    }
    var showPresets by remember { mutableStateOf(false) }
    // What the last group action did. Applying a group can select nothing at
    // all — a profile written for one variant names rows another does not have
    // — and that has to be said rather than left to look like a dead button.
    var presetNote by remember { mutableStateOf<String?>(null) }
    // Which group the selection came from, or null when it was built by hand.
    // Cleared the moment a row is ticked or unticked, because from then on
    // what is on screen is no longer that group.
    var appliedPreset by remember { mutableStateOf<String?>(null) }
    // And in what order it listed them. That order decides which identifiers
    // share a packet — and two in the same packet arrive in the same frame, at
    // the same instant. A profile that pairs the air mass with its target only
    // means something if the pairing survives to the declaration.
    var appliedOrder by remember { mutableStateOf<List<String>>(emptyList()) }
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
    //
    // In the applied group's order when there is one, otherwise the
    // catalogue's. See appliedOrder: the order is what pairs identifiers into
    // packets, so a group that was written with a layout in mind keeps it.
    val chosenParameters = run {
        val byKey = rows.filterIsInstance<Item.FromCatalogue>().associateBy { it.key }
        val ordered = if (appliedOrder.isEmpty()) {
            rows.filterIsInstance<Item.FromCatalogue>().filter { it.key in selected.keys }
        } else {
            appliedOrder.mapNotNull { byKey[it] }.filter { it.key in selected.keys } +
                rows.filterIsInstance<Item.FromCatalogue>()
                    .filter { it.key in selected.keys && it.key !in appliedOrder }
        }
        ordered.map { it.parameter }
    }

    /** How many of the scanned rows name something the module drives. */
    val commandable = rows.count { it.commandable }
    val canStream = selected.isNotEmpty() &&
        chosenParameters.size == selected.size &&
        settings.catalogueModule.isNotEmpty()
    // Rounds, not one plan. Five packets is the module's fast configuration —
    // 96 Hz each against 51 Hz for seven — so a selection larger than ten
    // identifiers is cycled through it rather than spilling into the polled
    // overflow, which on a five-minute drive gave the DPF pressure 82 readings
    // against the accelerator's 14 897. See Stream.rotate.
    val streamPlan = if (!canStream) null else Stream.rotate(
        moduleAddress,
        chosenParameters,
        if (settings.streamRotate) Stream.PACKETS_PER_START
        else Stream.LAST_PACKET - Stream.FIRST_PACKET + 1,
    )

    // The stored profile for this module, if this car has one.
    LaunchedEffect(settings.catalogueModule, Session.state) {
        if (Session.state != Session.State.CHANNEL_OPEN) return@LaunchedEffect
        val vin = withContext(Dispatchers.IO) { Session.guarded { Session.diagnostics.vin() } }.orEmpty()
        profileVin = vin
        answered = profiles.answered(vin, moduleAddress)
        widths = profiles.widths(vin, moduleAddress)
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
                    answered = found.keys
                    widths = found
                }
            } finally {
                probing = false
                probeDone = 0
            }
        }
    }

    fun rebuild() {
        busy = true
        presetNote = null
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
                        val kept = if (keepToCar && known != null) c.keptTo(all, known) else all
                        // Of the rows sharing an identifier, only the ones as
                        // wide as the module's own answer. Picking a two-byte
                        // row where the car sends one byte — or the reverse —
                        // applies a scale built for the other width, and reads
                        // an accelerator pedal at 798 %.
                        val byWidth =
                            if (widths.isEmpty()) kept
                            else kept.filter { p -> widths[p.pid]?.let { it == p.bytes } ?: true }
                        val pool = monitor.requestable(byWidth)

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
                // The list is the screen now, so there is nothing to fold away:
                // once there is something to look at, the sheet closes itself.
                if (rows.isNotEmpty()) showSetup = false
            } finally {
                busy = false
            }
        }
    }

    /** Throw away the list and the selection: the module they described changed. */
    fun forget() {
        selected.clear()
        rows = emptyList()
        appliedPreset = null
        appliedOrder = emptyList()
        presetNote = null
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

    fun start() {
        monitor.reset()
        val chosen = rows.filter { it.key in selected.keys }
        val plan = streamPlan
        if (streaming && plan != null && !plan.isEmpty) {
            monitor.startStream(plan, record, "live", settings.streamDwellMs.toLong())
        } else {
            monitor.start(targetsFor(monitor, chosen, moduleAddress), record, "live")
        }
    }

    // ── the screen: a header, and one thing under it ──────────────────────
    Column(modifier = Modifier.fillMaxSize()) {
        LiveHeader(
            module = settings.catalogueModule,
            selectedCount = selected.size,
            totalCount = rows.size,
            appliedPreset = appliedPreset,
            monitor = monitor,
            busy = busy,
            canStart = selected.isNotEmpty() && Session.state == Session.State.CHANNEL_OPEN,
            canScan = !busy && !monitor.isRunning &&
                Session.state == Session.State.CHANNEL_OPEN,
            hasRows = rows.isNotEmpty(),
            onScan = { rebuild() },
            onStart = { start() },
            onStop = { monitor.stop() },
            onSetup = { showSetup = true },
            onOptions = { showOptions = true },
        )

        Box(modifier = Modifier.weight(1f)) {
            when {
                monitor.isRunning -> WatchList(rows, selected.keys, monitor, settings)
                rows.isEmpty() -> Column(
                    modifier = Modifier.fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 24.dp),
                ) {
                    Hint(stringResource(R.string.live_empty))
                    if (settings.catalogueModule.isEmpty() && installed.isNotEmpty()) {
                        Button(
                            onClick = { showSetup = true },
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        ) { Text(stringResource(R.string.live_setup)) }
                    }
                }
                else -> PickList(
                    rows = rows,
                    selected = selected,
                    filter = filter,
                    onFilter = { filter = it },
                    onlySelected = onlySelected,
                    onOnlySelected = { onlySelected = !onlySelected },
                    onlyCommandable = onlyCommandable,
                    onOnlyCommandable = { onlyCommandable = !onlyCommandable },
                    commandable = commandable,
                    presetNames = presetNames,
                    appliedPreset = appliedPreset,
                    presetNote = presetNote,
                    canName = selected.isNotEmpty() && settings.catalogueModule.isNotEmpty(),
                    onGroups = { showPresets = true },
                    onName = { naming = true },
                    onClear = {
                        selected.clear()
                        appliedPreset = null
                        appliedOrder = emptyList()
                        presetNote = null
                    },
                    onToggleRow = { key, on ->
                        if (on) selected[key] = Unit else selected.remove(key)
                        appliedPreset = null
                        appliedOrder = emptyList()
                    },
                )
            }
        }
    }

    // ── the sheets ────────────────────────────────────────────────────────
    if (showSetup) {
        ModalBottomSheet(
            onDismissRequest = { showSetup = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Section(stringResource(R.string.live_setup), first = true)
                if (installed.isEmpty()) {
                    Hint(stringResource(R.string.catalogues_none))
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        FilterChip(
                            selected = catalogue == null,
                            enabled = !monitor.isRunning,
                            onClick = { catalogue = null; forget() },
                            label = { Text(stringResource(R.string.live_catalogue_off)) },
                        )
                        installed.forEach { brand ->
                            FilterChip(
                                selected = catalogue?.brand == brand,
                                enabled = !monitor.isRunning,
                                onClick = {
                                    catalogue = plugins.load(brand, settings.catalogueLanguage)
                                    forget()
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
                                    val onThisCar =
                                        c.addressesByModule[name].orEmpty().any { it in present }
                                    name + (if (onThisCar) "  $here" else "")
                                },
                                onSelect = {
                                    settings.catalogueModule = it
                                    settings.catalogueVariant = ""
                                    forget()
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
                                        name.removePrefix(module).trim(' ', '-') + "  (" +
                                            (variants.firstOrNull { it.name == name }?.keys?.size ?: 0) + ")"
                                    }
                                },
                                onSelect = {
                                    settings.catalogueVariant = it
                                    forget()
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
                            val askable =
                                remember(pool) { pool.count { it.pid in 0..0xffff && it.bytes in 1..4 } }
                            Hint(stringResource(R.string.live_coverage, askable, pool.size - askable))
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
                            OutlinedButton(
                                enabled = !probing && !busy && !monitor.isRunning &&
                                    Session.state == Session.State.CHANNEL_OPEN,
                                onClick = { profileCar() },
                                modifier = Modifier.fillMaxWidth(),
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
                                Toggle(
                                    checked = keepToCar,
                                    enabled = !monitor.isRunning,
                                    onChange = { keepToCar = it },
                                    label = stringResource(
                                        R.string.live_profile_only, kept ?: known.size, askable,
                                    ),
                                )
                            }
                            if (answered == null) Hint(stringResource(R.string.live_profile_hint))
                        }
                    }
                }
                Button(
                    enabled = !busy && !monitor.isRunning &&
                        Session.state == Session.State.CHANNEL_OPEN,
                    onClick = { rebuild() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        when {
                            busy -> stringResource(R.string.state_working)
                            rows.isEmpty() -> stringResource(R.string.live_scan)
                            else -> stringResource(R.string.live_rescan)
                        }
                    )
                }
            }
        }
    }

    if (showOptions) {
        ModalBottomSheet(
            onDismissRequest = { showOptions = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Section(stringResource(R.string.live_options), first = true)
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
                    val rounds = streamPlan?.rounds?.size ?: 1
                    if (rounds > 1) {
                        Hint(
                            stringResource(
                                R.string.live_stream_rounds,
                                rounds,
                                streamPlan?.perRound ?: 0,
                                settings.streamDwellMs / 1000.0,
                                rounds * (settings.streamDwellMs + Monitor.SWITCH_MS) / 1000.0,
                            )
                        )
                    }
                    // Said rather than swallowed: what did not fit in the
                    // packets is polled, which is slower, and a row moving once
                    // a second next to one moving a hundred times a second
                    // needs explaining before it is noticed.
                    val left = streamPlan?.leftOut?.size ?: 0
                    if (left > 0) Hint(stringResource(R.string.live_stream_left_out, left))
                }

                Text(
                    stringResource(R.string.live_chart_limit, settings.chartCount),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = settings.chartCount.toFloat(),
                    onValueChange = { settings.chartCount = it.toInt().coerceIn(0, 12) },
                    valueRange = 0f..12f,
                    steps = 11,
                )

                // The actuation panel: what the padlocks mean, and the one
                // command this app knows how to send.
                if (commandable > 0) {
                    Section(stringResource(R.string.settings_advanced))
                    if (!Actuation.unlocked) {
                        Hint(stringResource(R.string.live_commandable_locked, commandable))
                    } else {
                        Hint(stringResource(R.string.live_commandable_unlocked, commandable))
                        OutlinedButton(
                            enabled = !releasing && Session.state == Session.State.CHANNEL_OPEN,
                            onClick = {
                                releasing = true
                                releaseNote = null
                                thread {
                                    val ok = Session.guarded {
                                        Session.diagnostics.releaseControl(moduleAddress)
                                    }
                                    releaseNote = when (ok) {
                                        true -> R.string.live_release_done
                                        false -> R.string.live_release_refused
                                        null -> R.string.live_release_failed
                                    }
                                    releasing = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.live_release)) }
                        releaseNote?.let { Hint(stringResource(it)) }
                    }
                }
            }
        }
    }

    // ── the dialogs ───────────────────────────────────────────────────────
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
                        presets.save(
                            settings.catalogueModule,
                            presetName.trim(),
                            // In the order the list shows them, not the order a
                            // hash map happens to hold them: a saved group has
                            // to come back the same next time.
                            rows.filter { it.key in selected.keys }.map { it.key },
                        )
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
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (presetNames.isEmpty()) Hint(stringResource(R.string.live_presets_empty))
                    for (name in presetNames) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        ) {
                            if (name == appliedPreset) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
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
                                    // Said out loud, because the quiet version
                                    // of this is a group that selects nothing:
                                    // a profile written against one variant
                                    // names rows another variant does not have,
                                    // and silence looks like a broken button.
                                    presetNote = context.getString(
                                        R.string.live_preset_applied, selected.size, keys.size,
                                    )
                                    appliedPreset = name
                                    appliedOrder = keys
                                    showPresets = false
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
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
                                if (appliedPreset == name) appliedPreset = null
                                presetRevision++
                            }) { Text(stringResource(R.string.action_forget)) }
                        }
                    }

                    // The two ways one gets here, in the body rather than the
                    // dialog's own button row: three buttons down there is one
                    // more than fits a phone held upright, and the two that get
                    // squeezed out are the ones that bring anything in.
                    //
                    // From the catalogue, if it publishes any. A brand ships its
                    // own profiles.txt, so the selections somebody already
                    // worked out for a car arrive with the parameters instead of
                    // being rebuilt through a search box. Also offered from the
                    // catalogues screen, which is reachable without the car.
                    val fromCatalogue = catalogue?.brand?.let { plugins.profilesOf(it) }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (fromCatalogue != null) {
                            TextButton(onClick = {
                                val taken = presets.importAll(fromCatalogue)
                                presetRevision++
                                presetNote = context.getString(
                                    R.string.live_presets_imported, taken.size,
                                )
                            }) { Text(stringResource(R.string.live_presets_catalogue)) }
                        }
                        // Paste one in. A preset is plain text by design, so it
                        // travels in a message, a note or a repository as easily
                        // as between two phones.
                        TextButton(onClick = {
                            val clip =
                                context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                    as android.content.ClipboardManager
                            val text =
                                clip.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                            val name = text?.let { presets.import(it) }
                            presetRevision++
                            presetNote = context.getString(
                                R.string.live_presets_imported, if (name == null) 0 else 1,
                            )
                        }) { Text(stringResource(R.string.action_import)) }
                    }
                    presetNote?.let { Hint(it) }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPresets = false }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }
}

/**
 * Two lines that are always there: where you are, and the one button.
 *
 * Never more than two lines, whatever the state. The screen underneath is the
 * work, and a header that grows is a header that eats it.
 */
@Composable
private fun LiveHeader(
    module: String,
    selectedCount: Int,
    totalCount: Int,
    appliedPreset: String?,
    monitor: Monitor,
    busy: Boolean,
    canStart: Boolean,
    canScan: Boolean,
    hasRows: Boolean,
    onScan: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSetup: () -> Unit,
    onOptions: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    module.ifEmpty { stringResource(R.string.live_no_module) },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // The second line is whatever matters most right now: while a
                // run is going that is the rate and which round is live, and
                // before it starts it is what has been picked.
                val detail = if (monitor.isRunning) {
                    buildString {
                        append(stringResource(R.string.live_rate, monitor.rate, monitor.frameRate))
                        if (monitor.rounds > 1) {
                            append(" · ")
                            append(stringResource(R.string.live_round, monitor.round + 1, monitor.rounds))
                        }
                        monitor.recordingName?.let {
                            append(" · ")
                            append(stringResource(R.string.live_recording, monitor.recordedRows, it))
                        }
                    }
                } else if (hasRows) {
                    (appliedPreset?.let { "$it · " } ?: "") +
                        stringResource(R.string.live_selected, selectedCount, totalCount)
                } else {
                    stringResource(R.string.live_not_scanned)
                }
                Text(
                    detail,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (monitor.isRunning) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(enabled = !monitor.isRunning, onClick = onSetup) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = stringResource(R.string.live_setup),
                )
            }
            IconButton(onClick = onOptions) {
                Icon(
                    Icons.Filled.Tune,
                    contentDescription = stringResource(R.string.live_options),
                )
            }
            // One button, and what it does follows the state. Scan when there
            // is nothing to watch, Start when there is, Stop while it runs.
            when {
                monitor.isRunning -> OutlinedButton(onClick = onStop) {
                    Text(stringResource(R.string.action_stop))
                }
                !hasRows -> Button(enabled = canScan, onClick = onScan) {
                    Text(
                        if (busy) stringResource(R.string.state_working)
                        else stringResource(R.string.live_scan)
                    )
                }
                else -> Button(enabled = canStart, onClick = onStart) {
                    Text(stringResource(R.string.action_start))
                }
            }
        }
        ErrorLine(monitor.lastError)
    }
}

/** What a run looks like: charts first, then every selected row as a number. */
@Composable
private fun WatchList(
    rows: List<Item>,
    selected: Set<String>,
    monitor: Monitor,
    settings: Settings,
) {
    val watched = rows.filter { it.key in selected }
    val charted = watched.take(settings.chartCount)
    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        items(charted, key = { "chart:" + it.key }) { row ->
            val series = monitor.series[row.key]
            if (series != null && series.size > 1) {
                // Two charts titled the same thing, one at 102 and one at
                // 128, is not a reading anybody can use.
                val title = if (row.certain) row.name else row.name + "  " + row.identifier
                ParameterChart(title, row.unit, series, monitor.tick)
            }
        }
        items(watched, key = { "value:" + it.key }) { row -> ValueRow(row, monitor) }
    }
}

/**
 * Choosing what to watch: a search field that stays put, the filters, the list.
 *
 * A selection is built through a search box, and a search box shows what
 * matches rather than what is picked: with a few hundred rows there is no way
 * to look at the dozen already ticked without remembering all twelve names
 * first. Hence the Selected filter, and hence groups.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PickList(
    rows: List<Item>,
    selected: Map<String, Unit>,
    filter: String,
    onFilter: (String) -> Unit,
    onlySelected: Boolean,
    onOnlySelected: () -> Unit,
    onlyCommandable: Boolean,
    onOnlyCommandable: () -> Unit,
    commandable: Int,
    presetNames: List<String>,
    appliedPreset: String?,
    presetNote: String?,
    canName: Boolean,
    onGroups: () -> Unit,
    onName: () -> Unit,
    onClear: () -> Unit,
    onToggleRow: (String, Boolean) -> Unit,
) {
    val visible = when {
        onlySelected -> rows.filter { it.key in selected.keys }
        // The outputs, and the module's own scoreboard for them. The
        // scoreboard is the half that says whether a request would be
        // taken at all, so filtering to actuators without it shows the
        // levers and hides the interlocks.
        onlyCommandable -> rows.filter { it.commandable || it.controlStatus }
        filter.isBlank() -> rows
        else -> rows.filter { it.name.contains(filter, ignoreCase = true) }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        // Fixed above the list, not scrolled with it. Scrolled, the search
        // field goes away the moment you use it and comes back only by
        // scrolling all the way up — through a few hundred rows.
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedTextField(
                value = filter,
                onValueChange = onFilter,
                label = { Text(stringResource(R.string.live_search)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            // Wrapping, not a Row. Four chips do not fit the width of a
            // phone held upright, and a Row does not wrap: it clips, so
            // the fourth one was a sliver at the edge with no way to
            // reach it. Which four they are changes with the module, so
            // there is no arrangement that always fits.
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilterChip(
                    selected = onlySelected,
                    onClick = onOnlySelected,
                    enabled = selected.isNotEmpty(),
                    label = { Text(stringResource(R.string.live_only_selected, selected.size)) },
                )
                // Named when one is applied, counted when none is.
                // "Groups (3)" next to a screen full of ticked rows
                // says how many groups exist and nothing about whether
                // what is on screen came from one of them — which is
                // the only thing anybody wants to know from that chip.
                FilterChip(
                    selected = appliedPreset != null,
                    onClick = onGroups,
                    leadingIcon = if (appliedPreset == null) null else ({
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }),
                    label = {
                        Text(
                            appliedPreset ?: stringResource(R.string.live_presets, presetNames.size),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
                if (canName) {
                    AssistChip(
                        onClick = onName,
                        label = { Text(stringResource(R.string.live_preset_save)) },
                    )
                }
                // Only offered when there are any. On a standard OBD
                // list there are none, and a chip reading "Actuators
                // (0)" is a worse answer than no chip.
                if (commandable > 0) {
                    FilterChip(
                        selected = onlyCommandable,
                        onClick = onOnlyCommandable,
                        label = { Text(stringResource(R.string.live_only_commandable, commandable)) },
                    )
                }
                if (selected.isNotEmpty()) {
                    AssistChip(
                        onClick = onClear,
                        label = { Text(stringResource(R.string.live_clear_selection)) },
                    )
                }
            }
            if (onlyCommandable) Hint(stringResource(R.string.live_only_commandable_hint))
            presetNote?.let { Hint(it) }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        ) {
            items(visible, key = { "pick:" + it.key }) { row ->
                PickRow(row, row.key in selected.keys) { on -> onToggleRow(row.key, on) }
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
        // The padlock marks a row the module drives rather than only reports.
        // It is shown whether or not actuation is unlocked, because the useful
        // half of it is knowing which of four thousand rows those are: "what
        // can this car be told to do" is a question the list can answer on its
        // own, sitting in the house with the car outside.
        if (row.commandable) {
            Icon(
                if (Actuation.unlocked) Icons.Filled.LockOpen else Icons.Filled.Lock,
                contentDescription = stringResource(R.string.live_commandable),
                modifier = Modifier.size(16.dp).padding(end = 2.dp),
                tint = if (Actuation.unlocked) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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

    /** Names something the module drives rather than only measures. */
    open val commandable: Boolean get() = false

    /** Part of the module's own device-control scoreboard. See [Actuation]. */
    open val controlStatus: Boolean get() = false

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
        override val commandable = Actuation.isCommandable(parameter.name)
        override val controlStatus = Actuation.isControlStatus(parameter.pid)
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
