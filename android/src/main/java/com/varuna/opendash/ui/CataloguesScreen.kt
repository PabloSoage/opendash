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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.varuna.opendash.R
import com.varuna.opendash.data.PluginRepository
import com.varuna.opendash.data.PluginSource
import com.varuna.opendash.data.Settings
import kotlin.concurrent.thread

/**
 * Catalogue sources, the credentials they need, and what is installed from
 * them.
 *
 * Several sources at once, each with its own credential, so a token that reads
 * one repository cannot reach another. Two kinds of credential: a token for a
 * forge over HTTPS, an SSH key for anything reached over SFTP. See
 * [com.varuna.opendash.data.SshKey] for why it is both and not one.
 */
@Composable
fun CataloguesScreen(repository: PluginRepository, settings: Settings) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var installed by remember { mutableStateOf(repository.installed()) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(PluginSource.Kind.GITHUB) }
    var location by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("main") }
    var token by remember { mutableStateOf("") }
    var brand by remember { mutableStateOf("") }

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Section(stringResource(R.string.catalogues_installed), first = true)
        Panel {
            if (installed.isEmpty()) {
                Text(
                    stringResource(R.string.catalogues_none),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                installed.forEach { entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val catalogue = repository.load(entry, settings.catalogueLanguage)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(entry, style = MaterialTheme.typography.bodyMedium)
                            if (catalogue != null) {
                                Text(
                                    stringResource(
                                        R.string.catalogues_entry,
                                        catalogue.parameters.size,
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        TextButton(onClick = {
                            repository.remove(entry)
                            installed = repository.installed()
                        }) { Text(stringResource(R.string.action_remove)) }
                    }
                }
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
            repository.sources.forEach { source ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(source.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            source.kind.name.lowercase() + " · " + source.location +
                                if (source.isPrivate) {
                                    " · " + stringResource(R.string.source_private)
                                } else "",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = { repository.removeSource(source.id) }) {
                        Text(stringResource(R.string.action_remove))
                    }
                }
            }
        }

        Section(stringResource(R.string.catalogues_add_source))
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
                    repository.addSource(
                        PluginSource(
                            id = kind.name + ":" + location,
                            name = name,
                            kind = kind,
                            location = location,
                            ref = branch.ifBlank { "main" },
                            token = token,
                        )
                    )
                    name = ""
                    location = ""
                    token = ""
                },
            ) { Text(stringResource(R.string.action_save)) }
        }

        Section(stringResource(R.string.ssh_keys))
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
                    }) {
                        Text(stringResource(R.string.action_copy))
                    }
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

        Section(stringResource(R.string.catalogues_install))
        Panel {
            OutlinedTextField(
                value = brand,
                onValueChange = { brand = it.trim() },
                label = { Text(stringResource(R.string.catalogues_brand)) },
                placeholder = { Text("opel-vauxhall") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = !busy && brand.isNotBlank() && repository.sources.isNotEmpty(),
                onClick = {
                    busy = true
                    status = ""
                    val source = repository.sources.first()
                    thread {
                        val result = repository.install(source, brand, settings.catalogueLanguage) {
                            status = context.getString(R.string.catalogues_installing, it)
                        }
                        status = result.fold(
                            onSuccess = { n -> context.getString(R.string.catalogues_installed_ok, n) },
                            onFailure = { e -> e.message ?: e.javaClass.simpleName },
                        )
                        installed = repository.installed()
                        busy = false
                    }
                },
            ) {
                Text(
                    if (busy) stringResource(R.string.state_working)
                    else stringResource(R.string.catalogues_install)
                )
            }
            if (status.isNotEmpty()) {
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("ssh", text))
}
