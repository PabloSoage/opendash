package com.varuna.opendash.ui

import android.net.Uri
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.varuna.opendash.R
import com.varuna.opendash.data.RecordingStore
import com.varuna.opendash.data.Series
import com.varuna.opendash.data.SessionFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Recordings made here, and files made elsewhere.
 *
 * Nothing on this screen asks for a path. Android has not let an app write to a
 * path someone typed for several versions now, and a picker is what the system
 * offers instead: the folder for saving comes from the tree picker, the file to
 * open comes from the document picker, and both arrive as URIs carrying their
 * own permission.
 *
 * The .sm2 format is Scanmatik's own and was worked out from the files
 * themselves, so a session recorded with the Windows software opens here.
 */
@Composable
fun FilesScreen(store: RecordingStore) {
    var entries by remember { mutableStateOf(store.list()) }
    var opened by remember { mutableStateOf<SessionFile.Session?>(null) }
    var openedName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun open(uri: Uri, name: String) {
        busy = true
        error = null
        thread {
            try {
                opened = SessionFile.read(store.read(uri))
                openedName = name
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                opened = null
            }
            busy = false
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        store.useTree(uri)
        entries = store.list()
    }

    // Any type, not just .sm2: the picker on most phones will not filter on an
    // extension it does not know, and refusing the file the user just chose is
    // worse than reading it and saying it is not an .sm2.
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) open(uri, uri.lastPathSegment?.substringAfterLast('/') ?: "")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Section(stringResource(R.string.files_recordings), first = true)
        Panel {
            Text(
                stringResource(
                    R.string.files_location,
                    store.label() ?: stringResource(R.string.settings_folder_default),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (entries.isEmpty()) {
                Text(
                    stringResource(R.string.files_none),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                entries.forEach { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { open(entry.uri, entry.name) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                entry.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                                    .format(Date(entry.modified)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            RecordingStore.humanSize(entry.bytes),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { entries = store.list() }) {
                    Text(stringResource(R.string.action_refresh))
                }
                OutlinedButton(onClick = { folderPicker.launch(null) }) {
                    Icon(
                        Icons.Filled.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                    Text(stringResource(R.string.files_choose_folder))
                }
            }
        }

        Section(stringResource(R.string.files_open_sm2))
        Panel {
            Button(
                enabled = !busy,
                onClick = { filePicker.launch(arrayOf("*/*")) },
            ) {
                Text(
                    if (busy) stringResource(R.string.state_working)
                    else stringResource(R.string.action_choose)
                )
            }
            ErrorLine(error?.let { stringResource(R.string.files_unreadable, it) })

            opened?.let { rec ->
                Text(openedName, style = MaterialTheme.typography.titleMedium)
                Text(
                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                        .format(Date(rec.startedAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val seconds = rec.durationMs / 1000
                Text(
                    stringResource(
                        R.string.files_summary,
                        rec.parameters.size,
                        rec.samples.size,
                        seconds / 60,
                        seconds % 60,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Charts for whatever the file holds, drawn from its own series.
        //
        // Built once per file rather than per recomposition: a 23-minute
        // session is eighty thousand readings, and re-bucketing them on every
        // frame would make scrolling the list unusable. Parameters with a
        // single reading are dropped here rather than skipped in the loop, so
        // the number of charts only changes when the file does.
        val charts = remember(opened) {
            val session = opened
            if (session == null) emptyList()
            else session.parameters.mapIndexedNotNull { index, name ->
                val points = session.seriesOf(index)
                if (points.size < 2) null
                else name to Series(capacity = points.size)
                    .also { s -> points.forEach { s.add(it.second) } }
            }
        }
        charts.forEachIndexed { index, (name, series) ->
            ParameterChart(name, "", series, tick = index)
        }
    }
}
