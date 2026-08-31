package es.opendash.ui

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import es.opendash.R
import es.opendash.Session
import es.opendash.obd.Readiness
import kotlin.concurrent.thread

/**
 * Readiness monitors: what the car has finished testing since its codes were
 * last cleared.
 *
 * The reason a car with no faults can still fail an inspection. Clearing codes
 * resets these, and they only complete after the right mix of driving, so a
 * "not complete" here means come back later, not something is broken.
 */
@Composable
fun ReadinessScreen() {
    var readiness by remember { mutableStateOf<Readiness?>(null) }
    var busy by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            enabled = !busy && Session.state == Session.State.CHANNEL_OPEN,
            onClick = {
                busy = true
                thread {
                    readiness = Session.diagnostics.readiness()
                    busy = false
                }
            },
        ) { Text(stringResource(R.string.readiness_read)) }

        val r = readiness
        if (r == null) {
            Text(stringResource(R.string.readiness_none))
            return@Column
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (r.milOn) stringResource(R.string.readiness_mil_on)
                    else stringResource(R.string.readiness_mil_off),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (r.milOn) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
                )
                Text(stringResource(R.string.readiness_faults, r.faultCount))
                Text(
                    if (r.compressionIgnition) stringResource(R.string.readiness_diesel)
                    else stringResource(R.string.readiness_petrol),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Text(
            if (r.allComplete) stringResource(R.string.readiness_all_complete)
            else stringResource(R.string.readiness_pending),
            style = MaterialTheme.typography.titleMedium,
        )

        for ((name, supported, complete) in r.monitors) {
            if (!supported) continue
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(name, modifier = Modifier.weight(1f))
                Text(
                    if (complete) stringResource(R.string.readiness_complete)
                    else stringResource(R.string.readiness_incomplete),
                    color = if (complete) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
