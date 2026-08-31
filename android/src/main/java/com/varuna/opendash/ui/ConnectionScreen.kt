package com.varuna.opendash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import kotlin.concurrent.thread

/**
 * Link and vehicle: is the device there, is a channel open, and what car is it.
 *
 * Everything that touches the socket runs off the main thread — a poll can take
 * a second and a half when the bus is quiet, and blocking the UI for that is
 * how an app gets killed for not responding.
 */
@Composable
fun ConnectionScreen() {
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var bridgeRunning by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.device_title), style = MaterialTheme.typography.titleLarge)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    when (Session.state) {
                        Session.State.DISCONNECTED -> stringResource(R.string.device_disconnected)
                        Session.State.CONNECTING -> stringResource(R.string.device_connecting)
                        Session.State.CONNECTED -> stringResource(R.string.device_connected, Session.serial)
                        Session.State.CHANNEL_OPEN -> stringResource(R.string.device_channel_open)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (Session.firmware.isNotEmpty()) {
                    Text(stringResource(R.string.device_firmware, Session.firmware))
                }
                Session.lastError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    thread {
                        Session.openChannel()
                        busy = false
                    }
                },
            ) { Text(stringResource(R.string.device_connect)) }

            OutlinedButton(
                enabled = !busy && Session.state != Session.State.DISCONNECTED,
                onClick = { Session.disconnect() },
            ) { Text(stringResource(R.string.device_disconnect)) }
        }

        Text(stringResource(R.string.vehicle_title), style = MaterialTheme.typography.titleLarge)

        val v = Session.vehicle
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (v == null || !v.known) {
                    Text(stringResource(R.string.vehicle_unknown))
                } else {
                    Field(stringResource(R.string.vehicle_vin), v.vin)
                    Field(stringResource(R.string.vehicle_system), v.system)
                    Field(stringResource(R.string.vehicle_engine), v.engine)
                    Field(stringResource(R.string.vehicle_calibration), v.calibration)
                }
            }
        }

        Button(
            enabled = !busy && Session.state == Session.State.CHANNEL_OPEN,
            onClick = {
                busy = true
                thread {
                    Session.vehicle = Session.diagnostics.identify()
                    busy = false
                }
            },
        ) { Text(stringResource(R.string.vehicle_identify)) }

        Text(
            stringResource(R.string.vehicle_identify_hint),
            style = MaterialTheme.typography.bodySmall,
        )

        Text(stringResource(R.string.bridge_title), style = MaterialTheme.typography.titleLarge)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (bridgeRunning) stringResource(R.string.bridge_listening, "127.0.0.1", 35000)
                    else stringResource(R.string.bridge_stopped)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !bridgeRunning,
                        onClick = {
                            BridgeService.start(context)
                            bridgeRunning = true
                        },
                    ) { Text(stringResource(R.string.bridge_start)) }
                    OutlinedButton(
                        enabled = bridgeRunning,
                        onClick = {
                            BridgeService.stop(context)
                            bridgeRunning = false
                        },
                    ) { Text(stringResource(R.string.bridge_stop)) }
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
