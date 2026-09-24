package com.varuna.opendash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.varuna.opendash.R
import com.varuna.opendash.Session
import com.varuna.opendash.data.Monitor
import com.varuna.opendash.obd.Actuation
import com.varuna.opendash.obd.Actuators
import com.varuna.opendash.obd.Actuators.Risk
import kotlinx.coroutines.delay
import kotlin.concurrent.thread

/**
 * Asks the phone's owner to prove it is them. [credential] true means the
 * screen lock itself — pattern, PIN or password — and false a fingerprint,
 * falling back to the screen lock where there is none.
 */
typealias Authenticate = (credential: Boolean, onResult: (Boolean) -> Unit) -> Unit

private fun Risk.colour(): Color = when (this) {
    Risk.HARMLESS -> Color(0xFF2E7D32)
    Risk.LOW -> Color(0xFFF9A825)
    Risk.ENGINE -> Color(0xFFEF6C00)
    Risk.LEARN -> Color(0xFFC62828)
    Risk.FORBIDDEN -> Color(0xFF4A148C)
}

private fun Risk.title(): Int = when (this) {
    Risk.HARMLESS -> R.string.act_tier_harmless
    Risk.LOW -> R.string.act_tier_low
    Risk.ENGINE -> R.string.act_tier_engine
    Risk.LEARN -> R.string.act_tier_learn
    Risk.FORBIDDEN -> R.string.act_tier_forbidden
}

/** How long a typed confirmation makes you wait before it can be passed. */
private fun Risk.waitSeconds(): Int = when (this) {
    Risk.LEARN -> 10
    Risk.FORBIDDEN -> 30
    else -> 0
}

/**
 * Everything the car's modules can be commanded to do, one tier at a time,
 * with the way in harder the more there is to lose.
 *
 * Each tier asks for everything the one above it did:
 *
 *  - harmless: a fingerprint;
 *  - low: the warning read and acknowledged;
 *  - engine: a checklist, the car reading 0 km/h before every command, and the
 *    screen lock rather than a fingerprint;
 *  - learn: the output's name typed exactly and ten seconds to think;
 *  - do not touch: a sentence typed, thirty seconds, and the screen lock again
 *    before every single command.
 *
 * Only with the live view stopped: the two share one link, and a command
 * whose answer the live view reads looks like a command that failed.
 */
@Composable
fun ActuatorsScreen(
    actuators: List<Actuators.Actuator>,
    monitor: Monitor,
    authenticate: Authenticate,
    onClose: () -> Unit,
) {
    var gating by remember { mutableStateOf<Actuators.Actuator?>(null) }
    var controlling by remember { mutableStateOf<Actuators.Actuator?>(null) }
    var releaseNote by remember { mutableStateOf<Int?>(null) }
    val linked = Session.state == Session.State.CHANNEL_OPEN
    val usable = linked && !monitor.isRunning

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.act_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Hint(stringResource(R.string.act_intro))
                        when {
                            !linked -> ErrorLine(stringResource(R.string.act_no_link))
                            monitor.isRunning -> ErrorLine(stringResource(R.string.act_live_running))
                        }
                        // Always offered, never locked: AE 00 drives nothing.
                        OutlinedButton(
                            enabled = linked,
                            onClick = {
                                releaseNote = null
                                thread {
                                    val ok = releaseAll(actuators)
                                    releaseNote = if (ok) R.string.act_released else R.string.act_release_failed
                                }
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        ) { Text(stringResource(R.string.act_release_all)) }
                        releaseNote?.let { Hint(stringResource(it)) }
                        if (actuators.isEmpty()) Hint(stringResource(R.string.act_no_catalogue))
                    }
                    for (risk in Risk.entries) {
                        val tier = actuators.filter { it.risk == risk }
                        if (tier.isEmpty()) continue
                        item(key = "tier-" + risk.code) { TierHeader(risk) }
                        items(tier, key = { risk.code + "|" + it.name }) { a ->
                            ActuatorCard(a, enabled = usable) { gating = a }
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    gating?.let { a ->
        Gate(
            actuator = a,
            authenticate = authenticate,
            onPassed = {
                gating = null
                controlling = a
            },
            onCancel = { gating = null },
        )
    }
    controlling?.let { a ->
        ControlPanel(a, authenticate, onClose = { controlling = null })
    }
}

/** AE 00 to every module the list names an address for, the engine always. */
private fun releaseAll(actuators: List<Actuators.Actuator>): Boolean {
    val addresses = (actuators.mapNotNull { it.address } + com.varuna.opendash.obd.Diagnostics.ENGINE).distinct()
    var all = true
    for (address in addresses) {
        val ok = Session.guarded { Session.diagnostics.releaseControl(address) } ?: false
        all = all && ok
    }
    return all
}

@Composable
private fun TierHeader(risk: Risk) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(12.dp).background(risk.colour(), CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(risk.title()),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ActuatorCard(a: Actuators.Actuator, enabled: Boolean, onOperate: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(a.risk.colour(), CircleShape))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        a.module,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (a.commandable) {
                    Button(
                        enabled = enabled,
                        onClick = onOperate,
                        colors = ButtonDefaults.buttonColors(containerColor = a.risk.colour()),
                    ) { Text(stringResource(R.string.act_operate), color = Color.White) }
                }
            }
            Text(a.purpose, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            if (!a.commandable) {
                Text(
                    stringResource(R.string.act_not_captured),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            TextButton(onClick = { open = !open }) { Text(stringResource(R.string.act_how)) }
            if (open) {
                Text(a.how, style = MaterialTheme.typography.bodySmall)
                Text(
                    stringResource(R.string.act_danger) + ": " + a.danger,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (!a.commandable) Hint(stringResource(R.string.act_not_captured_hint))
            }
        }
    }
}

/** Road speed from the engine, or null when it could not be read. */
private fun readSpeed(): Int? =
    Session.guarded { Session.diagnostics.mode01(0x0D) }?.firstOrNull()?.toInt()?.and(0xff)

/**
 * Everything a tier asks for before its first command, on one page.
 *
 * On one page rather than a wizard on purpose: somebody reading step three of
 * five is not reading steps one and two any more, and the warning is the part
 * that matters most.
 */
@Composable
private fun Gate(
    actuator: Actuators.Actuator,
    authenticate: Authenticate,
    onPassed: () -> Unit,
    onCancel: () -> Unit,
) {
    val risk = actuator.risk
    var read by remember { mutableStateOf(risk == Risk.HARMLESS) }
    val checks = remember { mutableStateOf(BooleanArray(4)) }
    var speed by remember { mutableStateOf<Int?>(null) }
    var speedRead by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    var wait by remember { mutableIntStateOf(risk.waitSeconds()) }
    var failed by remember { mutableStateOf(false) }

    val phrase = stringResource(R.string.act_phrase)
    val needsChecklist = risk >= Risk.ENGINE
    val target = when (risk) {
        Risk.LEARN -> actuator.name
        Risk.FORBIDDEN -> phrase
        else -> null
    }

    LaunchedEffect(Unit) {
        while (wait > 0) {
            delay(1000)
            wait--
        }
    }
    LaunchedEffect(needsChecklist) {
        if (needsChecklist) {
            thread {
                speed = readSpeed()
                speedRead = true
            }
        }
    }

    val checklistDone = !needsChecklist || checks.value.all { it }
    val speedOk = !needsChecklist || speed == 0
    val typedOk = target == null || typed.trim() == target
    val ready = read && checklistDone && speedOk && typedOk && wait == 0

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(actuator.name) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(risk.title()), color = risk.colour(), style = MaterialTheme.typography.labelLarge)
                Text(actuator.purpose, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                Text(
                    stringResource(R.string.act_how),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(actuator.how, style = MaterialTheme.typography.bodySmall)
                Text(
                    stringResource(R.string.act_danger),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(actuator.danger, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)

                if (risk != Risk.HARMLESS) {
                    Tick(stringResource(R.string.act_read_it), read) { read = it }
                }
                if (needsChecklist) {
                    val labels = listOf(
                        R.string.act_check_parked, R.string.act_check_neutral,
                        R.string.act_check_clear, R.string.act_check_watch,
                    )
                    labels.forEachIndexed { i, label ->
                        Tick(stringResource(label), checks.value[i]) {
                            checks.value = checks.value.copyOf().also { c -> c[i] = it }
                        }
                    }
                    Text(
                        when {
                            !speedRead -> stringResource(R.string.act_speed_check)
                            speed == null -> stringResource(R.string.act_speed_unknown)
                            speed == 0 -> stringResource(R.string.act_speed_ok)
                            else -> stringResource(R.string.act_speed_moving, speed ?: 0)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (speed == 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (target != null) {
                    Text(
                        if (risk == Risk.LEARN) stringResource(R.string.act_type_name, target)
                        else stringResource(R.string.act_type_phrase, target),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (failed) ErrorLine(stringResource(R.string.act_auth_failed))
            }
        },
        confirmButton = {
            Button(
                enabled = ready,
                onClick = {
                    failed = false
                    authenticate(risk >= Risk.ENGINE) { ok -> if (ok) onPassed() else failed = true }
                },
                colors = ButtonDefaults.buttonColors(containerColor = risk.colour()),
            ) {
                Text(
                    if (wait > 0) stringResource(R.string.act_wait, wait)
                    else stringResource(R.string.act_continue),
                    color = Color.White,
                )
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.act_cancel)) } },
    )
}

@Composable
private fun Tick(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Where the command is sent from, once the gate is passed.
 *
 * While this is open the tester is present: a TesterPresent goes out every
 * second, because a module drops a held output about five seconds after the
 * last one. Closing it hands everything back and locks actuation again, so a
 * permission never outlives the panel it was given for.
 */
@Composable
private fun ControlPanel(a: Actuators.Actuator, authenticate: Authenticate, onClose: () -> Unit) {
    val range = a.range
    var value by remember { mutableFloatStateOf((range?.min ?: 0.0).toFloat()) }
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var heldSince by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val address = a.address ?: return

    val context = LocalContext.current

    DisposableEffect(a) {
        Actuation.unlock()
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val beat = thread(name = "actuator-present", isDaemon = true) {
            while (running.get()) {
                runCatching { Thread.sleep(1000) }
                if (running.get()) Session.guarded { Session.diagnostics.keepAlive(address) }
            }
        }
        onDispose {
            running.set(false)
            beat.interrupt()
            thread {
                Session.guarded { Session.diagnostics.releaseControl(address) }
                Actuation.lock()
            }
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            now = System.currentTimeMillis()
        }
    }

    fun send() {
        busy = true
        note = null
        thread {
            try {
                // Checked again before every command, not only at the gate:
                // the gate was a minute ago and the car may have moved since.
                if (a.risk >= Risk.ENGINE) {
                    val speed = readSpeed()
                    if (speed == null) { note = context.getString(R.string.act_speed_unknown); return@thread }
                    if (speed != 0) { note = context.getString(R.string.act_speed_moving, speed); return@thread }
                }
                val request = a.request(if (range != null) value.toDouble() else null) ?: return@thread
                val r = Session.guarded { Session.diagnostics.request(address, request, timeoutMs = 1500) }
                note = when {
                    r == null -> context.getString(R.string.act_no_answer)
                    (r.firstOrNull()?.toInt()?.and(0xff)) == Actuation.POSITIVE -> {
                        heldSince = System.currentTimeMillis()
                        context.getString(R.string.act_sent)
                    }
                    r.size >= 3 && (r[0].toInt() and 0xff) == 0x7F -> context.getString(
                        R.string.act_refused,
                        String.format(java.util.Locale.ROOT, "0x%02X", r[2].toInt() and 0xff),
                    )
                    else -> context.getString(R.string.act_no_answer)
                }
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = {},
        title = { Text(a.name) },
        text = {
            Column {
                Text(stringResource(a.risk.title()), color = a.risk.colour(), style = MaterialTheme.typography.labelLarge)
                if (range != null) {
                    Text(
                        stringResource(
                            R.string.act_value,
                            String.format(java.util.Locale.ROOT, "%.0f %s", value, range.unit),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Slider(
                        value = value,
                        onValueChange = { value = it },
                        valueRange = range.min.toFloat()..range.max.toFloat(),
                    )
                }
                if (heldSince > 0) {
                    Hint(stringResource(R.string.act_held, ((now - heldSince) / 1000).toInt()))
                }
                note?.let { Hint(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Button(
                        enabled = !busy && Session.state == Session.State.CHANNEL_OPEN,
                        onClick = {
                            // Do-not-touch asks for the screen lock again before
                            // every single command, not once for the session.
                            if (a.risk == Risk.FORBIDDEN) authenticate(true) { ok -> if (ok) send() }
                            else send()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = a.risk.colour()),
                    ) { Text(stringResource(R.string.act_send), color = Color.White) }
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            thread {
                                val ok = Session.guarded { Session.diagnostics.releaseControl(address) } ?: false
                                if (ok) heldSince = 0L
                                note = null
                            }
                        },
                    ) { Text(stringResource(R.string.act_release)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) } },
    )
}
