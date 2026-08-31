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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.varuna.opendash.R
import com.varuna.opendash.data.Series
import com.varuna.opendash.data.Settings
import com.varuna.opendash.data.Sm2Reader
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Recordings made here, and the ones the Windows software made.
 *
 * The .sm2 format is proprietary and was worked out from the files themselves,
 * so a recording taken with the manufacturer's tool can be opened here rather
 * than only there.
 */
@Composable
fun RecordingsScreen(settings: Settings) {
    var files by remember { mutableStateOf(listFiles(settings)) }
    var path by remember { mutableStateOf("") }
    var opened by remember { mutableStateOf<Sm2Reader.Recording?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.recordings_title), style = MaterialTheme.typography.titleLarge)
        Text(settings.recordingPath, style = MaterialTheme.typography.bodySmall)

        if (files.isEmpty()) {
            Text(stringResource(R.string.recordings_none))
        } else {
            files.forEach { f ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(f.name, modifier = Modifier.weight(1f))
                    Text(
                        (f.length() / 1024).toString() + " kB",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        Button(onClick = { files = listFiles(settings) }) {
            Text(stringResource(R.string.recordings_refresh))
        }

        Text(stringResource(R.string.sm2_title), style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = path,
            onValueChange = { path = it },
            label = { Text(stringResource(R.string.sm2_path)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Button(
            enabled = !busy && path.isNotBlank(),
            onClick = {
                busy = true
                error = null
                thread {
                    try {
                        opened = Sm2Reader.read(File(path))
                    } catch (e: Exception) {
                        error = e.message ?: e.javaClass.simpleName
                        opened = null
                    }
                    busy = false
                }
            },
        ) { Text(stringResource(R.string.sm2_open)) }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        opened?.let { rec ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(rec.startedAt)),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    val seconds = rec.durationMs / 1000
                    Text(
                        stringResource(
                            R.string.sm2_summary,
                            rec.parameters.size,
                            rec.samples.size,
                            seconds / 60,
                            seconds % 60,
                        )
                    )
                }
            }
            // Charts for whatever the recording holds, drawn from its own series.
            rec.parameters.forEachIndexed { index, name ->
                val points = rec.seriesOf(index)
                if (points.size < 2) return@forEachIndexed
                val series = Series(capacity = maxOf(points.size, 2))
                points.forEach { series.add(it.second) }
                ParameterChart(name, "", series)
            }
        }
    }
}

private fun listFiles(settings: Settings): List<File> =
    settings.recordingDirectory().listFiles()
        ?.filter { it.isFile }
        ?.sortedByDescending { it.lastModified() }
        ?: emptyList()
