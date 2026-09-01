package com.varuna.opendash.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.varuna.opendash.R
import com.varuna.opendash.Session
import com.varuna.opendash.obd.Dtc
import com.varuna.opendash.obd.Readiness
import kotlin.concurrent.thread

/**
 * Fault codes first, then readiness monitors.
 *
 * That order because it is the order of the questions people arrive with: what
 * is wrong, and will it pass an inspection. Readiness is the second one — a car
 * with no faults still fails if its monitors have not completed since the codes
 * were cleared, and the only cure for that is more driving.
 */
@Composable
fun HealthScreen() {
    var readiness by remember { mutableStateOf<Readiness?>(null) }
    var stored by remember { mutableStateOf<List<Dtc>>(emptyList()) }
    var pending by remember { mutableStateOf<List<Dtc>>(emptyList()) }
    var read by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Button(
            enabled = !busy && Session.state == Session.State.CHANNEL_OPEN,
            onClick = {
                busy = true
                thread {
                    try {
                        // Guarded because this runs on a worker thread, and an
                        // exception with nobody to catch it takes the process
                        // down rather than the screen.
                        Session.guarded {
                            readiness = Session.diagnostics.readiness()
                            stored = Session.diagnostics.storedFaults()
                            pending = Session.diagnostics.pendingFaults()
                            read = true
                        }
                    } finally {
                        busy = false
                    }
                }
            },
        ) {
            Text(
                if (busy) stringResource(R.string.state_working)
                else stringResource(R.string.health_read)
            )
        }
        ErrorLine(Session.lastError)

        if (!read) {
            Hint(
                if (Session.state == Session.State.CHANNEL_OPEN) {
                    stringResource(R.string.health_empty)
                } else {
                    stringResource(R.string.error_no_channel)
                },
                modifier = Modifier.padding(top = 16.dp),
            )
            return@Column
        }

        Section(stringResource(R.string.faults))
        Panel {
            if (stored.isEmpty() && pending.isEmpty()) {
                Text(
                    stringResource(R.string.faults_none),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                stored.forEach { FaultRow(it.code, stringResource(R.string.faults_stored), true) }
                pending.forEach { FaultRow(it.code, stringResource(R.string.faults_pending), false) }
            }
            Hint(stringResource(R.string.faults_hint))
        }

        val r = readiness ?: return@Column

        Section(stringResource(R.string.monitors))
        Panel {
            StatusLine(
                text = if (r.milOn) stringResource(R.string.health_mil_on)
                else stringResource(R.string.health_mil_off),
                colour = if (r.milOn) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.tertiary,
            )
            Text(
                stringResource(R.string.health_fault_count, r.faultCount),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                if (r.compressionIgnition) stringResource(R.string.health_diesel)
                else stringResource(R.string.health_petrol),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (r.allComplete) stringResource(R.string.monitors_all_complete)
                else stringResource(R.string.monitors_pending),
                style = MaterialTheme.typography.bodyMedium,
                color = if (r.allComplete) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Panel(modifier = Modifier.padding(top = 8.dp)) {
            for ((name, supported, complete) in r.monitors) {
                if (!supported) continue
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        name,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (complete) stringResource(R.string.monitors_complete)
                        else stringResource(R.string.monitors_incomplete),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (complete) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun FaultRow(code: String, kind: String, severe: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            code,
            style = MaterialTheme.typography.titleMedium,
            color = if (severe) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            kind,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
