package com.varuna.opendash.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.varuna.opendash.R
import com.varuna.opendash.Session
import com.varuna.opendash.bridge.BridgeService
import com.varuna.opendash.data.Settings
import com.varuna.opendash.net.WifiLink
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Where the link stands, and the one thing to press next.
 *
 * ## What was wrong with the old one
 *
 * Four panels — Wi-Fi, adapter, vehicle, bridge — all open at once, each with
 * its own status line in its own colour and its own row of three buttons. On a
 * phone held upright that is three screenfuls, the question "is it connected?"
 * is answered somewhere in the middle of it, and a row of three buttons does
 * not fit the width, so the third one is clipped.
 *
 * It also had the proportions backwards. Nine of the twelve controls are setup:
 * the access point name, the prefix, the password, the host, the port, the
 * bridge interface. Those are set once and then read never again, and they were
 * taking the top of the screen every single time.
 *
 * ## What this is instead
 *
 * One card at the top with the chain in it — Wi-Fi, adapter, bus — three rungs,
 * each a dot and a line of text, so where you are and what is missing reads in
 * one glance. Under it, the actions, **one per row and full width**, because a
 * button that is full width cannot be clipped by a narrow screen.
 *
 * Everything else folds. A folded panel still shows its value in the subtitle,
 * so nothing is hidden — the network it will join, the address it will dial,
 * whether the bridge is listening. Open one and it is the same fields as before.
 *
 * Everything that touches the socket runs off the main thread. A poll can take
 * over a second when the bus is quiet, and blocking the UI thread for that is
 * how an app gets killed for not responding.
 */
@Composable
fun LinkScreen(settings: Settings, onOpenIdentification: () -> Unit) {
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var identifying by remember { mutableStateOf(false) }
    var nearby by remember { mutableStateOf(WifiLink.visible(context, settings.wifiPrefix)) }

    // Saved, so a rotation does not fold away the panel that was being used.
    var openWifi by rememberSaveable { mutableStateOf(false) }
    var openAdapter by rememberSaveable { mutableStateOf(false) }
    var openVehicle by rememberSaveable { mutableStateOf(false) }
    var openBridge by rememberSaveable { mutableStateOf(false) }

    val askToScan = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { nearby = WifiLink.visible(context, settings.wifiPrefix) }

    // A scan takes seconds and finishes long after the button that asked for
    // it has returned, so the list has to be told when the results land rather
    // than read once and left. Reading it once is why the list stayed empty.
    DisposableEffect(settings.wifiPrefix) {
        val watch = WifiLink.watch(context, settings.wifiPrefix) { nearby = it }
        onDispose { watch.close() }
    }

    // The voltage comes off the adapter, not the bus, so it is readable the
    // moment the socket is up. Polled here rather than during composition,
    // which happens on the main thread and must not touch a socket.
    LaunchedEffect(Session.state) {
        while (Session.state != Session.State.DISCONNECTED) {
            withContext(Dispatchers.IO) { Session.refreshBattery() }
            delay(2000)
        }
    }

    val joined = WifiLink.state == WifiLink.State.JOINED
    val joining = WifiLink.state == WifiLink.State.JOINING
    val state = Session.state
    val connected = state != Session.State.DISCONNECTED
    val open = state == Session.State.CHANNEL_OPEN
    val anywhere = stringResource(R.string.wifi_any, settings.wifiPrefix)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // ── the chain ────────────────────────────────────────────────────
        Panel {
            Step(
                label = stringResource(R.string.wifi),
                detail = when (WifiLink.state) {
                    WifiLink.State.OFF -> stringResource(R.string.wifi_off)
                    WifiLink.State.JOINING -> stringResource(R.string.wifi_joining, WifiLink.target)
                    WifiLink.State.JOINED -> WifiLink.target
                    WifiLink.State.FAILED -> stringResource(R.string.wifi_failed)
                },
                done = joined,
                busy = joining,
            )
            Step(
                label = stringResource(R.string.link_adapter),
                detail = when {
                    Session.serial.isNotEmpty() && Session.firmware.isNotEmpty() ->
                        Session.serial + " · " + Session.firmware
                    state == Session.State.CONNECTING -> stringResource(R.string.link_connecting)
                    else -> stringResource(R.string.link_disconnected)
                },
                done = connected,
                busy = state == Session.State.CONNECTING || busy,
            )
            Step(
                label = stringResource(R.string.link_step_bus),
                detail = when {
                    !open -> stringResource(R.string.link_step_bus_closed)
                    Session.busNarrowed -> stringResource(R.string.link_bus_filtered)
                    else -> stringResource(R.string.link_bus_wide)
                },
                done = open,
            )

            if (Session.batteryMillivolts > 0) {
                Field(
                    stringResource(R.string.link_voltage),
                    String.format(Locale.ROOT, "%.2f V", Session.batteryMillivolts / 1000.0),
                )
            }
            ErrorLine(WifiLink.lastError)
            ErrorLine(Session.lastError ?: Session.transportFault)

            // One action per row, full width. Three buttons side by side is
            // what the old screen did and the third one did not fit.
            if (!joined) {
                Button(
                    enabled = !joining,
                    onClick = {
                        WifiLink.join(
                            context, settings.wifiSsid, settings.wifiPassword, settings.wifiPrefix,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.wifi_join)) }
            }
            if (!connected) {
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        thread {
                            try {
                                Session.configure(settings.host, settings.port)
                                Session.openChannel()
                            } finally {
                                busy = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.action_connect)) }
            } else {
                OutlinedButton(
                    enabled = !busy,
                    onClick = { Session.disconnect() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.action_disconnect)) }
            }
            if (joined) {
                TextButton(
                    onClick = { WifiLink.leave(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.wifi_leave)) }
            }
        }

        // ── the vehicle ──────────────────────────────────────────────────
        Section(stringResource(R.string.vehicle))
        Fold(
            title = stringResource(R.string.vehicle),
            subtitle = Session.vehicle?.takeIf { it.known }?.engine
                ?: stringResource(R.string.vehicle_unknown),
            open = openVehicle,
            onToggle = { openVehicle = !openVehicle },
        ) {
            val v = Session.vehicle
            if (v != null && v.known) {
                Field(stringResource(R.string.vehicle_vin), v.vin)
                Field(stringResource(R.string.vehicle_system), v.system)
                Field(stringResource(R.string.vehicle_engine), v.engine)
                Field(stringResource(R.string.vehicle_calibration), v.calibration)
            }
            Button(
                enabled = !identifying && open,
                onClick = {
                    identifying = true
                    thread {
                        try {
                            Session.identify()
                        } finally {
                            identifying = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (identifying) stringResource(R.string.state_working)
                    else stringResource(R.string.vehicle_identify)
                )
            }
            OutlinedButton(
                enabled = open,
                onClick = onOpenIdentification,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.ident_open)) }
            Hint(stringResource(R.string.vehicle_hint))
        }

        // ── setup, folded ────────────────────────────────────────────────
        Section(stringResource(R.string.link_setup))
        Fold(
            title = stringResource(R.string.wifi),
            subtitle = settings.wifiSsid.ifBlank { anywhere },
            open = openWifi,
            onToggle = { openWifi = !openWifi },
        ) {
            // The list is only there when the location permission was given.
            // Without it the system picker does the same job, so the combo
            // appears when it can and the field is always available. The empty
            // name stays in the list as an explicit choice rather than being
            // implied by a blank field: it means "whatever Android finds".
            if (nearby.names.isNotEmpty()) {
                Combo(
                    label = stringResource(R.string.wifi_network),
                    value = settings.wifiSsid,
                    options = listOf("") + nearby.names,
                    render = { it.ifEmpty { anywhere } },
                    onSelect = { settings.wifiSsid = it },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                OutlinedTextField(
                    value = settings.wifiSsid,
                    onValueChange = { settings.wifiSsid = it },
                    label = { Text(stringResource(R.string.wifi_network)) },
                    placeholder = { Text(anywhere) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // Never an empty list on its own: it looks identical whether
                // the permission was refused, the location switch is off, the
                // Wi-Fi is off or there is genuinely nothing there, and only
                // the last of those is out of the user's hands.
                Hint(
                    stringResource(
                        when (nearby.why) {
                            WifiLink.Why.NO_PERMISSION -> R.string.wifi_why_permission
                            WifiLink.Why.LOCATION_OFF -> R.string.wifi_why_location
                            WifiLink.Why.WIFI_OFF -> R.string.wifi_why_off
                            WifiLink.Why.THROTTLED -> R.string.wifi_why_throttled
                            else -> R.string.wifi_why_nothing
                        }
                    )
                )
            }

            // Only matters when no name was given: it is what the system
            // picker is asked to offer. Editable because an adapter whose
            // access point has been renamed is otherwise unreachable from
            // here, with no way to tell that from it being out of range.
            if (settings.wifiSsid.isBlank()) {
                OutlinedTextField(
                    value = settings.wifiPrefix,
                    onValueChange = { settings.wifiPrefix = it.trim() },
                    label = { Text(stringResource(R.string.wifi_prefix)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = settings.wifiPassword,
                onValueChange = { settings.wifiPassword = it },
                label = { Text(stringResource(R.string.link_psk)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Pressing it scans, and asks for the permission only if that
                // is what is missing — so it does something visible every time,
                // which is what a button that scanned nothing and said nothing
                // did not.
                OutlinedButton(
                    onClick = {
                        val found = WifiLink.visible(context, settings.wifiPrefix)
                        nearby = found
                        if (found.why == WifiLink.Why.NO_PERMISSION) {
                            askToScan.launch(WifiLink.scanPermission)
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.wifi_list)) }
                TextButton(
                    onClick = { copyThenOpenWifi(context, settings.wifiPassword) },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.wifi_settings)) }
            }
            Hint(stringResource(R.string.wifi_hint))
        }

        Fold(
            title = stringResource(R.string.link_adapter),
            subtitle = settings.host + ":" + settings.port,
            open = openAdapter,
            onToggle = { openAdapter = !openAdapter },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = settings.host,
                    onValueChange = { settings.host = it.trim() },
                    label = { Text(stringResource(R.string.link_host)) },
                    singleLine = true,
                    modifier = Modifier.weight(2f),
                )
                OutlinedTextField(
                    value = settings.port.toString(),
                    onValueChange = { it.toIntOrNull()?.let { p -> settings.port = p } },
                    label = { Text(stringResource(R.string.link_port)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            if (Session.serial.isNotEmpty()) {
                Field(stringResource(R.string.link_serial), Session.serial)
            }
            if (Session.firmware.isNotEmpty()) {
                Field(stringResource(R.string.link_firmware), Session.firmware)
            }
            // Only shown when it is not zero, and then it is the whole story:
            // bytes the reader had to throw away to find the start of a
            // message. A healthy link never needs to.
            if (Session.resynchronised > 0) {
                Field(stringResource(R.string.link_resync), Session.resynchronised.toString())
            }
            // Answers nobody was waiting for. Zero on a link whose model of
            // the protocol is right, and the first thing to look at if a
            // reading ever comes back belonging to the previous question.
            if (Session.unpaired > 0) {
                Field(stringResource(R.string.link_unpaired), Session.unpaired.toString())
            }
        }

        Fold(
            title = stringResource(R.string.bridge),
            subtitle = if (Session.bridgePort > 0) {
                stringResource(R.string.bridge_listening, settings.elmHost, Session.bridgePort)
            } else {
                stringResource(R.string.bridge_stopped)
            },
            open = openBridge,
            onToggle = { openBridge = !openBridge },
        ) {
            // Read from the service, not from the button: starting it can fail
            // and the label has to follow what actually happened.
            val running = Session.bridgePort > 0
            var probe by remember { mutableStateOf("") }
            var probing by remember { mutableStateOf(false) }
            ErrorLine(Session.bridgeError)
            val hostAll = stringResource(R.string.bridge_host_all)
            val hostLocal = stringResource(R.string.bridge_host_local)
            Combo(
                label = stringResource(R.string.bridge_host_label),
                value = settings.elmHost,
                options = listOf("127.0.0.1", "0.0.0.0"),
                render = { if (it == "0.0.0.0") hostAll else hostLocal },
                onSelect = { settings.elmHost = it },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!running) {
                    Button(
                        onClick = { BridgeService.start(context, settings.elmPort, settings.elmHost) },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.action_start)) }
                } else {
                    OutlinedButton(
                        onClick = { BridgeService.stop(context) },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.action_stop)) }
                }
                // The bridge is the one part of this app with no screen of its
                // own: it is a socket another app talks to, so when it does not
                // work there is nothing to look at. This connects to it exactly
                // as that other app would and shows what came back, which says
                // in one line whether the fault is here or over there.
                OutlinedButton(
                    enabled = running && !probing,
                    onClick = {
                        probing = true
                        probe = ""
                        thread {
                            probe = try {
                                askBridge(settings.elmHost, settings.elmPort)
                            } catch (e: Exception) {
                                e.message ?: e.javaClass.simpleName
                            } finally {
                                probing = false
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.bridge_test)) }
            }
            if (probe.isNotEmpty()) {
                Text(
                    probe,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Hint(stringResource(R.string.bridge_hint))
        }
    }
}

/**
 * Put the access point password on the clipboard and open the Wi-Fi settings.
 *
 * The way out when the app cannot do it itself: Android 9 and older have no API
 * to join a named network, and a device that refuses the request leaves the
 * user somewhere. Two taps with the password already on the clipboard works
 * everywhere.
 */
private fun copyThenOpenWifi(context: Context, password: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("wifi", password))
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        Intent(AndroidSettings.Panel.ACTION_WIFI)
    } else {
        Intent(AndroidSettings.ACTION_WIFI_SETTINGS)
    }
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/**
 * Talk to the bridge the way an OBD app would, and report what happened.
 *
 * Bound to all interfaces the socket still answers on the loopback, so the
 * test connects there either way: what is being checked is that the server is
 * up and the car answers through it, not which interface it is reachable on.
 */
private fun askBridge(host: String, port: Int): String {
    val to = if (host == "0.0.0.0") "127.0.0.1" else host
    Socket().use { s ->
        s.connect(InetSocketAddress(to, port), 2000)
        s.soTimeout = 5000
        val out = s.getOutputStream()
        val input = s.getInputStream()

        // Everything an ELM327 says ends at the prompt. Reading up to it is the
        // whole framing, and a read that times out before it arrives is itself
        // the answer: the server took the connection and then said nothing.
        fun untilPrompt(): String {
            val sb = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0 || c == '>'.code) return sb.toString().trim()
                sb.append(c.toChar())
                if (sb.length > 512) return sb.toString().trim()
            }
        }
        fun ask(command: String): String {
            out.write((command + "\r").toByteArray(Charsets.US_ASCII))
            out.flush()
            return untilPrompt().replace("\r", " ").trim()
        }

        untilPrompt()                                   // the greeting prompt
        val version = ask("ATZ")
        val volts = ask("ATRV")
        val pids = ask("0100")
        return "ATZ $version · ATRV $volts · 0100 $pids"
    }
}
