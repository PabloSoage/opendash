package com.varuna.opendash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.varuna.opendash.obd.Diagnostics
import com.varuna.opendash.obd.Identifiers
import com.varuna.opendash.ui.theme.ValueStyle
import java.util.Locale
import kotlin.concurrent.thread

/**
 * What every module on the bus says about itself.
 *
 * The factory tool reads a block of 58 local identifiers to fill its ECU ID
 * screen — part numbers, alpha codes, the programming date, the broadcast code,
 * the traceability number. Fifty of them have a name, taken from the GDS2
 * catalogue and checked against the length of the answer the car gave; the rest
 * are asked anyway and shown as bytes.
 *
 * That last part is the point of the screen. The only way to find out what an
 * unlabelled identifier holds is to read it on a car and look, and the raw hex
 * is always shown next to whatever it was decoded into, so a wrong label can
 * never hide the number underneath it.
 *
 * Everything here is service 0x1A. It reads and does nothing else.
 */
@Composable
fun IdentificationScreen() {
    val results = remember { mutableStateListOf<Diagnostics.Identification>() }
    var busy by remember { mutableStateOf(false) }
    var cancel by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<String?>(null) }
    var fraction by remember { mutableStateOf(0f) }
    var modules by remember { mutableStateOf(0) }

    fun scan() {
        busy = true
        cancel = false
        results.clear()
        modules = 0
        thread {
            try {
                // A sweep is minutes of questions and the link can go at any
                // point in them — the adapter loses power with the ignition.
                // Guarded so that ends the sweep and says so, instead of ending
                // the app.
                Session.guarded {
                    val present = Session.diagnostics.modulesPresent { module ->
                        progress = "0x" + module.toString(16).uppercase()
                        fraction = 0f
                    }
                    modules = present.size
                    present.forEachIndexed { index, module ->
                        if (cancel) return@forEachIndexed
                        val name = "0x" + module.toString(16).uppercase()
                        val found = Session.diagnostics.identification(
                            module = module,
                            stop = { cancel },
                            onProgress = { done, total ->
                                progress = name
                                fraction = (index + done.toFloat() / total) / present.size
                            },
                        )
                        results.addAll(found)
                    }
                }
            } finally {
                progress = null
                busy = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Panel {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    enabled = !busy && Session.state == Session.State.CHANNEL_OPEN,
                    onClick = { scan() },
                ) { Text(stringResource(R.string.ident_scan)) }
                if (busy) {
                    OutlinedButton(onClick = { cancel = true }) {
                        Text(stringResource(R.string.ident_stop))
                    }
                }
            }
            if (busy) {
                Text(
                    if (fraction == 0f) {
                        stringResource(R.string.ident_probing, progress ?: "")
                    } else {
                        stringResource(
                            R.string.ident_reading,
                            progress ?: "",
                            results.size,
                            modules,
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (modules > 0) {
                Text(
                    stringResource(R.string.ident_found, modules),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Hint(stringResource(R.string.ident_hint))
        }

        if (results.isEmpty() && !busy) {
            Hint(
                if (Session.state == Session.State.CHANNEL_OPEN) {
                    stringResource(R.string.ident_none)
                } else {
                    stringResource(R.string.error_no_channel)
                },
                modifier = Modifier.padding(top = 16.dp),
            )
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
            items(results.toList(), key = { it.module * 256 + it.id }) { row ->
                IdentificationRow(row)
            }
        }
    }
}

@Composable
private fun IdentificationRow(row: Diagnostics.Identification) {
    val shape = Identifiers.known.firstOrNull { it.id == row.id }?.shape ?: Identifiers.Shape.RAW
    val decoded = Identifiers.render(row.bytes, shape)

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                String.format(Locale.ROOT, "0x%02X  %04X", row.id, row.module),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "  " + row.label.ifEmpty { stringResource(R.string.ident_unlabelled) },
                style = MaterialTheme.typography.labelMedium,
                color = if (row.label.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (decoded != null) {
            Text(decoded, style = ValueStyle, color = MaterialTheme.colorScheme.primary)
        }
        // The bytes are always shown, decoded or not: a label that turns out to
        // be wrong must not be able to hide what the module actually said.
        Text(
            row.hex,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
