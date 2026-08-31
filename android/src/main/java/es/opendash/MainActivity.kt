package es.opendash

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import es.opendash.bridge.BridgeService

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        LocaleManager.restore(this)
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Home()
                }
            }
        }
    }
}

@Composable
private fun Home() {
    val context = LocalContext.current
    var running by remember { mutableStateOf(false) }
    var language by remember { mutableStateOf(LocaleManager.current()) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.bridge_title), style = MaterialTheme.typography.titleLarge)
        Text(
            if (running) stringResource(R.string.bridge_listening, "127.0.0.1", 35000)
            else stringResource(R.string.bridge_stopped)
        )
        Button(onClick = {
            if (running) BridgeService.stop(context) else BridgeService.start(context)
            running = !running
        }) {
            Text(stringResource(if (running) R.string.bridge_stop else R.string.bridge_start))
        }

        Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleLarge)
        LocaleManager.supported.forEach { tag ->
            Column(
                modifier = Modifier.selectable(
                    selected = language == tag,
                    onClick = {
                        LocaleManager.store(context, tag)
                        language = tag
                    },
                )
            ) {
                RadioButton(selected = language == tag, onClick = null)
                Text(
                    stringResource(
                        when (tag) {
                            "en" -> R.string.settings_language_en
                            "es" -> R.string.settings_language_es
                            else -> R.string.settings_language_system
                        }
                    )
                )
            }
        }
    }
}
