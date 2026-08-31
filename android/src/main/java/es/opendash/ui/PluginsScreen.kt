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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import es.opendash.R
import es.opendash.data.PluginRepository
import es.opendash.data.PluginSource
import es.opendash.data.Settings
import kotlin.concurrent.thread

/**
 * Catalogue sources and what is installed from them.
 *
 * Several sources can be configured at once — a shared public one and a private
 * repository of your own. Credentials are per source, so a token that reads one
 * repository cannot reach another.
 */
@Composable
fun PluginsScreen(repository: PluginRepository, settings: Settings) {
    var installed by remember { mutableStateOf(repository.installed()) }
    var sources by remember { mutableStateOf(repository.sources) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    var name by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var brand by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.plugins_title), style = MaterialTheme.typography.titleLarge)

        if (installed.isEmpty()) {
            Text(stringResource(R.string.plugins_none))
        } else {
            installed.forEach { b ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp)) {
                        val c = repository.load(b, settings.catalogueLanguage)
                        Text(
                            if (c == null) b
                            else stringResource(R.string.plugins_installed, c.parameters.size, b),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = {
                            repository.remove(b)
                            installed = repository.installed()
                        }) { Text(stringResource(R.string.plugins_remove)) }
                    }
                }
            }
        }

        Text(stringResource(R.string.plugins_sources), style = MaterialTheme.typography.titleLarge)
        sources.forEach { s ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(s.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        s.kind.name + "  " + s.location +
                            if (s.isPrivate) "  " + stringResource(R.string.plugins_private) else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedButton(onClick = {
                    repository.removeSource(s.id)
                    sources = repository.sources
                }) { Text(stringResource(R.string.plugins_remove)) }
            }
        }

        Text(stringResource(R.string.plugins_add_source), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.plugins_source_name)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = location,
            onValueChange = { location = it },
            label = { Text(stringResource(R.string.plugins_source_repo)) },
            placeholder = { Text("PabloSoage/opendash-plugins") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text(stringResource(R.string.plugins_source_token)) },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Text(
            stringResource(R.string.plugins_token_hint),
            style = MaterialTheme.typography.bodySmall,
        )
        Button(
            enabled = name.isNotBlank() && location.isNotBlank(),
            onClick = {
                repository.addSource(
                    PluginSource(
                        id = location,
                        name = name,
                        kind = PluginSource.Kind.GITHUB,
                        location = location,
                        token = token,
                    )
                )
                sources = repository.sources
                name = ""
                location = ""
                token = ""
            },
        ) { Text(stringResource(R.string.plugins_save_source)) }

        Text(stringResource(R.string.plugins_install), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = brand,
            onValueChange = { brand = it },
            label = { Text(stringResource(R.string.plugins_brand)) },
            placeholder = { Text("opel-vauxhall") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Button(
            enabled = !busy && brand.isNotBlank() && sources.isNotEmpty(),
            onClick = {
                busy = true
                status = ""
                val source = sources.first()
                thread {
                    val result = repository.install(source, brand, settings.catalogueLanguage) {
                        status = it
                    }
                    status = result.fold(
                        onSuccess = { n -> "$n" },
                        onFailure = { e -> e.message ?: e.javaClass.simpleName },
                    )
                    installed = repository.installed()
                    busy = false
                }
            },
        ) { Text(stringResource(R.string.plugins_download)) }

        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
    }
}
