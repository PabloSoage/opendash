package es.opendash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import es.opendash.LocaleManager
import es.opendash.R
import es.opendash.data.Settings

/**
 * Where the app is configured, including the one switch that deserves a lock.
 *
 * @param onUnlockAdvanced asks for the device credential and reports whether it
 *        was given. Left to the caller because it needs an activity.
 */
@Composable
fun SettingsScreen(
    settings: Settings,
    advancedEnabled: Boolean,
    onUnlockAdvanced: (onResult: (Boolean) -> Unit) -> Unit,
    onAdvancedChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    var language by remember { mutableStateOf(LocaleManager.current()) }
    var path by remember { mutableStateOf(settings.recordingPath) }
    var poll by remember { mutableStateOf(settings.pollIntervalMs.toFloat()) }
    var compress by remember { mutableStateOf(settings.compressRecordings) }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleLarge)
        LocaleManager.supported.forEach { tag ->
            Row(
                modifier = Modifier.fillMaxWidth().selectable(
                    selected = language == tag,
                    onClick = {
                        LocaleManager.store(context, tag)
                        language = tag
                    },
                ).padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = language == tag, onClick = null)
                Text(
                    stringResource(
                        when (tag) {
                            "en" -> R.string.settings_language_en
                            "es" -> R.string.settings_language_es
                            else -> R.string.settings_language_system
                        }
                    ),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        Text(stringResource(R.string.settings_recording), style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = path,
            onValueChange = {
                path = it
                settings.recordingPath = it
            },
            label = { Text(stringResource(R.string.settings_recording_path)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Text(
            stringResource(R.string.settings_recording_hint),
            style = MaterialTheme.typography.bodySmall,
        )

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = compress,
                onCheckedChange = {
                    compress = it
                    settings.compressRecordings = it
                },
            )
            Text(
                stringResource(R.string.settings_compress),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Text(
            stringResource(R.string.settings_compress_hint),
            style = MaterialTheme.typography.bodySmall,
        )

        Text(stringResource(R.string.settings_polling), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.settings_poll_interval, poll.toInt()))
        Slider(
            value = poll,
            onValueChange = { poll = it },
            onValueChangeFinished = { settings.pollIntervalMs = poll.toInt() },
            valueRange = 0f..2000f,
        )
        Text(
            stringResource(R.string.settings_poll_hint),
            style = MaterialTheme.typography.bodySmall,
        )

        Text(stringResource(R.string.settings_advanced), style = MaterialTheme.typography.titleLarge)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Switch(
                checked = advancedEnabled,
                onCheckedChange = { wanted ->
                    if (!wanted) {
                        onAdvancedChanged(false)
                    } else {
                        onUnlockAdvanced { granted -> onAdvancedChanged(granted) }
                    }
                },
            )
            Text(
                stringResource(R.string.settings_advanced_toggle),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Text(
            stringResource(R.string.settings_advanced_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
