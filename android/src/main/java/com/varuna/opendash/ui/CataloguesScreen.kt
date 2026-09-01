package com.varuna.opendash.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.varuna.opendash.R
import com.varuna.opendash.data.PluginRepository
import com.varuna.opendash.data.PluginSource
import com.varuna.opendash.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread

/**
 * Catalogue sources, the credentials they need, and what is installed from
 * them.
 *
 * The list is the source's own index and what is on disk, merged: one row per
 * catalogue, with an arrow to fetch it or a bin to remove it. Before this you
 * had to know a directory name and type it correctly, which is a fine way to
 * ship a feature nobody can find.
 *
 * Several sources at once, each with its own credential, so a token that reads
 * one repository cannot reach another. Two kinds of credential: a token for a
 * forge over HTTPS, an SSH key for anything reached over SFTP. See
 * [com.varuna.opendash.data.SshKey] for why it is both and not one.
 */
@Composable
fun CataloguesScreen(repository: PluginRepository, settings: Settings) {
    val context = LocalContext.current

    var installed by remember { mutableStateOf(repository.installed()) }
    var sourceId by remember { mutableStateOf(repository.sources.firstOrNull()?.id) }
    var listing by remember { mutableStateOf<List<PluginRepository.Listing>>(emptyList()) }
    var browsing by remember { mutableStateOf(false) }
    var browseError by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("") }
    var refresh by remember { mutableStateOf(0) }

    val source = repository.sources.firstOrNull { it.id == sourceId }
        ?: repository.sources.firstOrNull()

    // Ask the source what it has as soon as there is one to ask, and again
    // whenever a different one is picked or the refresh is pressed.
    LaunchedEffect(source?.id, refresh) {
        val s = source ?: return@LaunchedEffect
        browsing = true
        browseError = null
        val result = withContext(Dispatchers.IO) { repository.discover(s) }
        result.fold(
            onSuccess = { listing = it },
            onFailure = {
                listing = emptyList()
                browseError = it.message ?: it.javaClass.simpleName
            },
        )
        browsing = false
    }

    fun install(name: String) {
        val s = source ?: return
        working = name
        status = ""
        thread {
            try {
                val result = repository.install(s, name, settings.catalogueLanguage) {
                    status = context.getString(R.string.catalogues_installing, it)
                }
                status = result.fold(
                    onSuccess = { n -> context.getString(R.string.catalogues_installed_ok, n) },
                    onFailure = { e -> e.message ?: e.javaClass.simpleName },
                )
                installed = repository.installed()
            } catch (e: Exception) {
                // install returns a Result, but the download underneath can
                // still throw before it gets that far. On a worker thread that
                // is a closed app rather than a failed install.
                status = e.message ?: e.javaClass.simpleName
            } finally {
                working = null
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Section(stringResource(R.string.catalogues_available), first = true)
        Panel {
            if (repository.sources.isEmpty()) {
                Text(
                    stringResource(R.string.catalogues_no_sources),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                if (repository.sources.size > 1) {
                    Combo(
                        label = stringResource(R.string.catalogues_source),
                        value = source?.id ?: "",
                        options = repository.sources.map { it.id },
                        render = { id ->
                            repository.sources.firstOrNull { it.id == id }?.name ?: id
                        },
                        onSelect = { sourceId = it },
                    )
                }

                // Everything the source offers, plus anything installed it no
                // longer lists — otherwise a catalogue from a source you since
                // deleted would be stuck on the phone with no way to remove it.
                val names = (listing.map { it.name } + installed).distinct().sorted()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(
                            R.string.settings_catalogues_summary,
                            installed.size,
                            repository.sources.size,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (browsing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(onClick = { refresh++ }) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.action_refresh),
                            )
                        }
                    }
                }

                if (names.isEmpty() && !browsing) {
                    Text(
                        stringResource(R.string.catalogues_none),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                names.forEach { name ->
                    val entry = listing.firstOrNull { it.name == name }
                    CatalogueRow(
                        name = name,
                        parameters = entry?.parameters?.takeIf { it > 0 }
                            ?: repository.size(name),
                        languages = entry?.languages.orEmpty(),
                        isInstalled = name in installed,
                        busy = working == name,
                        enabled = working == null,
                        onInstall = { install(name) },
                        onRemove = {
                            repository.remove(name)
                            installed = repository.installed()
                        },
                    )
                }

                ErrorLine(browseError)
                if (status.isNotEmpty()) {
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // A source that publishes no index is still usable if you know
                // what is in it.
                if (browseError != null) ManualInstall { install(it) }
            }
        }

        Section(stringResource(R.string.catalogues_sources))
        Panel {
            if (repository.sources.isEmpty()) {
                Text(
                    stringResource(R.string.catalogues_no_sources),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            repository.sources.forEach { s ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(s.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            s.kind.name.lowercase() + " · " + s.location +
                                if (s.isPrivate) {
                                    " · " + stringResource(R.string.source_private)
                                } else "",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = {
                        repository.removeSource(s.id)
                        if (sourceId == s.id) sourceId = repository.sources.firstOrNull()?.id
                    }) { Text(stringResource(R.string.action_remove)) }
                }
            }
        }

        Section(stringResource(R.string.catalogues_add_source))
        AddSource(repository) { sourceId = it }

        Section(stringResource(R.string.ssh_keys))
        SshKeys(repository, context)
    }
}

/** One catalogue: what it is, and the one thing you can do to it. */
@Composable
private fun CatalogueRow(
    name: String,
    parameters: Int,
    languages: List<String>,
    isInstalled: Boolean,
    busy: Boolean,
    enabled: Boolean,
    onInstall: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isInstalled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val size = if (parameters > 0) {
                stringResource(R.string.catalogues_entry, parameters)
            } else {
                ""
            }
            val detail = listOf(size, languages.joinToString(", "))
                .filter { it.isNotEmpty() }
                .joinToString(" · ")
            if (detail.isNotEmpty()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        when {
            busy -> CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
            )
            isInstalled -> IconButton(enabled = enabled, onClick = onRemove) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.action_remove),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
            else -> IconButton(enabled = enabled, onClick = onInstall) {
                Icon(
                    Icons.Filled.Download,
                    contentDescription = stringResource(R.string.catalogues_install),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** For a source that publishes no index but whose contents you know. */
@Composable
private fun ManualInstall(onInstall: (String) -> Unit) {
    var brand by remember { mutableStateOf("") }
    Text(
        stringResource(R.string.catalogues_manual),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = brand,
        onValueChange = { brand = it.trim() },
        label = { Text(stringResource(R.string.catalogues_brand)) },
        placeholder = { Text("opel-vauxhall") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(enabled = brand.isNotBlank(), onClick = { onInstall(brand) }) {
        Text(stringResource(R.string.catalogues_install))
    }
}

@Composable
private fun AddSource(repository: PluginRepository, onAdded: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(PluginSource.Kind.GITHUB) }
    var location by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("main") }
    var token by remember { mutableStateOf("") }

    Panel {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PluginSource.Kind.entries.forEach { k ->
                FilterChip(
                    selected = kind == k,
                    onClick = { kind = k },
                    label = {
                        Text(
                            stringResource(
                                when (k) {
                                    PluginSource.Kind.GITHUB -> R.string.source_kind_github
                                    PluginSource.Kind.GITLAB -> R.string.source_kind_gitlab
                                    PluginSource.Kind.GITEA -> R.string.source_kind_gitea
                                    PluginSource.Kind.HTTPS -> R.string.source_kind_https
                                    PluginSource.Kind.SSH -> R.string.source_kind_ssh
                                }
                            ),
                            maxLines = 1,
                        )
                    },
                )
            }
        }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.source_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = location,
            onValueChange = { location = it.trim() },
            label = {
                Text(
                    stringResource(
                        when (kind) {
                            PluginSource.Kind.SSH -> R.string.source_ssh_target
                            PluginSource.Kind.HTTPS -> R.string.source_url
                            else -> R.string.source_repo
                        }
                    )
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (kind != PluginSource.Kind.SSH && kind != PluginSource.Kind.HTTPS) {
            OutlinedTextField(
                value = branch,
                onValueChange = { branch = it.trim() },
                label = { Text(stringResource(R.string.source_branch)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (kind == PluginSource.Kind.SSH) {
            Hint(stringResource(R.string.source_ssh_hint))
        } else {
            OutlinedTextField(
                value = token,
                onValueChange = { token = it.trim() },
                label = { Text(stringResource(R.string.source_token)) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Hint(stringResource(R.string.source_token_hint))
        }

        Button(
            enabled = name.isNotBlank() && location.isNotBlank(),
            onClick = {
                val id = kind.name + ":" + location
                repository.addSource(
                    PluginSource(
                        id = id,
                        name = name,
                        kind = kind,
                        location = location,
                        ref = branch.ifBlank { "main" },
                        token = token,
                    )
                )
                onAdded(id)
                name = ""
                location = ""
                token = ""
            },
        ) { Text(stringResource(R.string.action_save)) }
    }
}

@Composable
private fun SshKeys(repository: PluginRepository, context: Context) {
    var publicKey by remember { mutableStateOf(repository.key.publicKeyLine()) }
    var fingerprint by remember { mutableStateOf(repository.key.fingerprint()) }
    var keyError by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }

    val keyImport = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri).use { it!!.readBytes() }
                .toString(Charsets.UTF_8)
        }.getOrNull()
        if (text == null) {
            keyError = "unreadable"
            return@rememberLauncherForActivityResult
        }
        repository.key.import(text).fold(
            onSuccess = {
                publicKey = repository.key.publicKeyLine()
                fingerprint = repository.key.fingerprint()
                keyError = null
                note = context.getString(R.string.ssh_imported)
            },
            onFailure = { keyError = it.message ?: it.javaClass.simpleName },
        )
    }

    Panel {
        if (publicKey == null) {
            Text(
                stringResource(R.string.ssh_none),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                publicKey!!,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            fingerprint?.let { Field(stringResource(R.string.ssh_fingerprint), it) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                publicKey = repository.key.generate()
                fingerprint = repository.key.fingerprint()
                keyError = null
                note = null
            }) { Text(stringResource(R.string.ssh_generate)) }
            OutlinedButton(onClick = { keyImport.launch(arrayOf("*/*")) }) {
                Text(stringResource(R.string.ssh_import))
            }
        }
        if (publicKey != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    copy(context, publicKey!!)
                    note = context.getString(R.string.ssh_copied)
                }) { Text(stringResource(R.string.action_copy)) }
                TextButton(onClick = {
                    repository.key.delete()
                    repository.forgetSshHosts()
                    publicKey = null
                    fingerprint = null
                }) { Text(stringResource(R.string.ssh_delete)) }
            }
        }
        keyError?.let { ErrorLine(stringResource(R.string.ssh_import_failed, it)) }
        note?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        Hint(stringResource(R.string.ssh_hint))
    }
}

private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("ssh", text))
}
