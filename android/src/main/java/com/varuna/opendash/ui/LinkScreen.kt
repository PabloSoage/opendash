package com.varuna.opendash.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings as AndroidSettings
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.varuna.opendash.R
import com.varuna.opendash.Session
import com.varuna.opendash.bridge.BridgeService
import com.varuna.opendash.data.Settings
import java.util.Locale
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The adapter, the vehicle, and the bridge: three questions in the order they
 * get asked.
 *
 * Everything that touches the socket runs off the main thread. A poll can take
 * over a second when the bus is quiet, and blocking the UI thread for that is
 * how an app gets killed for not responding.
 */
@Composable
fun LinkScreen(settings: Settings) {
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var identifying by remember { mutableStateOf(false) }
    var bridgeRunning by remember { mutableStateOf(false) }
    var showAddress by remember { mutableStateOf(false) }

    // The voltage comes off the adapter, not the bus, so it is readable the
    // moment the socket is up. Polled here rather than during composition,
    // which happens on the main thread and must not touch a socket.
    LaunchedEffect(Session.state) {
        while (Session.state != Session.State.DISCONNECTED) {
            withContext(Dispatchers.IO) { Session.refreshBattery() }
            delay(2000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Section(stringResource(R.string.link_adapter), first = true)
        Panel {
            val state = Session.state
            StatusLine(
                text = when (state) {
                    Session.State.DISCONNECTED -> stringResource(R.string.link_disconnected)
                    Session.State.CONNECTING -> stringResource(R.string.link_connecting)
                    Session.State.CONNECTED -> stringResource(R.string.link_connected)
                    Session.State.CHANNEL_OPEN -> stringResource(R.string.link_channel_open)
                },
                colour = when (state) {
                    Session.State.DISCONNECTED -> MaterialTheme.colorScheme.error
                    Session.State.CONNECTING -> MaterialTheme.colorScheme.secondary
                    Session.State.CONNECTED -> MaterialTheme.colorScheme.secondary
                    Session.State.CHANNEL_OPEN -> MaterialTheme.colorScheme.tertiary
                },
                busy = busy,
            )
            if (Session.serial.isNotEmpty()) {
                Field(stringResource(R.string.link_serial), Session.serial)
            }
            if (Session.firmware.isNotEmpty()) {
                Field(stringResource(R.string.link_firmware), Session.firmware)
            }
            if (Session.batteryMillivolts > 0) {
                Field(
                    stringResource(R.string.link_voltage),
                    String.format(Locale.ROOT, "%.2f V", Session.batteryMillivolts / 1000.0),
                )
            }
            ErrorLine(Session.lastError)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        thread {
                            Session.configure(settings.host, settings.port)
                            Session.openChannel()
                            busy = false
                        }
                    },
                ) { Text(stringResource(R.string.action_connect)) }

                OutlinedButton(
                    enabled = !busy && Session.state != Session.State.DISCONNECTED,
                    onClick = { Session.disconnect() },
                ) { Text(stringResource(R.string.action_disconnect)) }
            }

            TextButton(onClick = { showAddress = !showAddress }) {
                Text(settings.host + ":" + settings.port)
            }
            if (showAddress) {
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
                OutlinedTextField(
                    value = settings.wifiPassword,
                    onValueChange = { settings.wifiPassword = it },
                    label = { Text(stringResource(R.string.link_psk)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Hint(stringResource(R.string.link_psk_hint))
                OutlinedButton(onClick = { copyThenOpenWifi(context, settings.wifiPassword) }) {
                    Text(stringResource(R.string.action_copy))
                }
            }
        }

        Section(stringResource(R.string.vehicle))
        Panel {
            val v = Session.vehicle
            if (v == null || !v.known) {
                Text(
                    stringResource(R.string.vehicle_unknown),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Field(stringResource(R.string.vehicle_vin), v.vin)
                Field(stringResource(R.string.vehicle_system), v.system)
                Field(stringResource(R.string.vehicle_engine), v.engine)
                Field(stringResource(R.string.vehicle_calibration), v.calibration)
            }
            Button(
                enabled = !identifying && Session.state == Session.State.CHANNEL_OPEN,
                onClick = {
                    identifying = true
                    thread {
                        Session.vehicle = Session.diagnostics.identify()
                        identifying = false
                    }
                },
            ) {
                Text(
                    if (identifying) stringResource(R.string.state_working)
                    else stringResource(R.string.vehicle_identify)
                )
            }
            Hint(stringResource(R.string.vehicle_hint))
        }

        Section(stringResource(R.string.bridge))
        Panel {
            Text(
                if (bridgeRunning) {
                    stringResource(R.string.bridge_listening, "127.0.0.1", settings.elmPort)
                } else {
                    stringResource(R.string.bridge_stopped)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !bridgeRunning,
                    onClick = {
                        BridgeService.start(context, settings.elmPort)
                        bridgeRunning = true
                    },
                ) { Text(stringResource(R.string.action_start)) }
                OutlinedButton(
                    enabled = bridgeRunning,
                    onClick = {
                        BridgeService.stop(context)
                        bridgeRunning = false
                    },
                ) { Text(stringResource(R.string.action_stop)) }
            }
            Hint(stringResource(R.string.bridge_hint))
        }
    }
}

/**
 * Put the access point password on the clipboard and open the Wi-Fi settings.
 *
 * Joining a network on the app's behalf needs location permission and an API
 * that behaves differently on every Android version. Two taps with the password
 * already copied is less code, fewer permissions, and works everywhere.
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
