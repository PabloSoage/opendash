package com.varuna.opendash.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.varuna.opendash.LocaleManager
import com.varuna.opendash.R
import com.varuna.opendash.data.PluginRepository
import com.varuna.opendash.data.RecordingStore
import com.varuna.opendash.data.Settings
import com.varuna.opendash.update.AppUpdate
import com.varuna.opendash.update.UpdateDialog

/**
 * Where the app is configured, including the one switch that deserves a lock.
 *
 * @param onUnlockAdvanced asks for the device credential and reports whether it
 *        was given. Left to the caller because it needs an activity.
 */
@Composable
fun SettingsScreen(
    settings: Settings,
    store: RecordingStore,
    plugins: PluginRepository,
    advancedEnabled: Boolean,
    onUnlockAdvanced: (onResult: (Boolean) -> Unit) -> Unit,
    onAdvancedChanged: (Boolean) -> Unit,
    onOpenCatalogues: () -> Unit,
) {
    val context = LocalContext.current
    var language by remember { mutableStateOf(LocaleManager.current()) }
    var folder by remember { mutableStateOf(store.label()) }
    var noLock by remember { mutableStateOf(false) }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        store.useTree(uri)
        folder = store.label()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Section(stringResource(R.string.settings_appearance), first = true)
        Panel {
            Text(
                stringResource(R.string.settings_theme),
                style = MaterialTheme.typography.bodyMedium,
            )
            Settings.ThemeMode.entries.forEach { mode ->
                Choice(
                    label = stringResource(
                        when (mode) {
                            Settings.ThemeMode.SYSTEM -> R.string.settings_theme_system
                            Settings.ThemeMode.LIGHT -> R.string.settings_theme_light
                            Settings.ThemeMode.DARK -> R.string.settings_theme_dark
                        }
                    ),
                    selected = settings.theme == mode,
                    onSelect = { settings.theme = mode },
                )
            }
        }

        Panel(modifier = Modifier.padding(top = 8.dp)) {
            Text(
                stringResource(R.string.settings_language),
                style = MaterialTheme.typography.bodyMedium,
            )
            LocaleManager.supported.forEach { tag ->
                Choice(
                    label = stringResource(
                        when (tag) {
                            "en" -> R.string.settings_language_en
                            "es" -> R.string.settings_language_es
                            "de" -> R.string.settings_language_de
                            else -> R.string.settings_language_system
                        }
                    ),
                    selected = language == tag,
                    onSelect = {
                        language = tag
                        LocaleManager.store(context, tag)
                    },
                )
            }
        }

        Section(stringResource(R.string.settings_recording))
        Panel {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.settings_folder),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        folder ?: stringResource(R.string.settings_folder_default),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = { folderPicker.launch(null) }) {
                    Text(stringResource(R.string.action_choose))
                }
            }
            Hint(stringResource(R.string.settings_folder_hint))

            Toggle(
                label = stringResource(R.string.settings_compress),
                checked = settings.compressRecordings,
                onChange = { settings.compressRecordings = it },
            )
            Hint(stringResource(R.string.settings_compress_hint))
        }

        Section(stringResource(R.string.settings_polling))
        Panel {
            Text(
                if (settings.pollIntervalMs == 0) {
                    stringResource(R.string.settings_poll_none)
                } else {
                    stringResource(R.string.settings_poll_interval, settings.pollIntervalMs)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = settings.pollIntervalMs.toFloat(),
                onValueChange = { settings.pollIntervalMs = it.toInt() },
                valueRange = 0f..1000f,
            )
            Hint(stringResource(R.string.settings_poll_hint))

            Toggle(
                label = stringResource(R.string.settings_rotate),
                checked = settings.streamRotate,
                onChange = { settings.streamRotate = it },
            )
            Hint(stringResource(R.string.settings_rotate_hint))
            if (settings.streamRotate) {
                Text(
                    stringResource(R.string.settings_dwell, settings.streamDwellMs / 1000.0),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = settings.streamDwellMs.toFloat(),
                    onValueChange = { settings.streamDwellMs = (it.toInt() / 250) * 250 },
                    valueRange = 500f..6000f,
                )
                Hint(stringResource(R.string.settings_dwell_hint))
            }
        }

        Section(stringResource(R.string.catalogues))
        Panel {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenCatalogues() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.catalogues),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(
                            R.string.settings_catalogues_summary,
                            plugins.installed().size,
                            plugins.sources.size,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Section(stringResource(R.string.settings_advanced))
        Panel {
            Toggle(
                label = stringResource(R.string.settings_advanced_toggle),
                checked = advancedEnabled,
                onChange = { wanted ->
                    noLock = false
                    if (!wanted) {
                        onAdvancedChanged(false)
                    } else {
                        onUnlockAdvanced { granted ->
                            onAdvancedChanged(granted)
                            noLock = !granted
                        }
                    }
                },
            )
            if (noLock) ErrorLine(stringResource(R.string.settings_advanced_unavailable))
            Hint(stringResource(R.string.settings_advanced_warning))
        }

        Section(stringResource(R.string.settings_about))
        Panel {
            Text(
                stringResource(
                    R.string.settings_version,
                    AppUpdate.installedVersion(context).ifEmpty { "?" },
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Updates(settings)
        }
    }
}

/** Where a new release is offered, checked for by hand, and the check at start switched off. */
@Composable
private fun Updates(settings: Settings) {
    val context = LocalContext.current
    var showing by remember { mutableStateOf(false) }
    when (val s = AppUpdate.status) {
        is AppUpdate.Status.Available -> {
            Text(
                stringResource(R.string.update_new, s.update.title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            OutlinedButton(onClick = { showing = true }) { Text(stringResource(R.string.update_see)) }
            if (showing) UpdateDialog(s.update, onDismiss = { showing = false })
        }
        AppUpdate.Status.UpToDate -> Hint(stringResource(R.string.update_up_to_date))
        AppUpdate.Status.Failed -> ErrorLine(stringResource(R.string.update_check_failed))
        else -> Unit
    }
    if (AppUpdate.status !is AppUpdate.Status.Available) {
        OutlinedButton(
            onClick = { AppUpdate.check(context.applicationContext) },
            enabled = AppUpdate.status != AppUpdate.Status.Checking,
        ) {
            Text(
                stringResource(
                    if (AppUpdate.status == AppUpdate.Status.Checking) R.string.update_checking
                    else R.string.update_check,
                ),
            )
        }
    }
    Toggle(
        label = stringResource(R.string.update_check_on_start),
        checked = settings.checkUpdates,
        onChange = { settings.checkUpdates = it },
    )
}

@Composable
private fun Choice(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
