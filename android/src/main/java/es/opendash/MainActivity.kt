package es.opendash

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import es.opendash.data.Monitor
import es.opendash.data.PluginRepository
import es.opendash.data.Settings
import es.opendash.ui.ConnectionScreen
import es.opendash.ui.LiveScreen
import es.opendash.ui.PluginsScreen
import es.opendash.ui.ReadinessScreen
import es.opendash.ui.RecordingsScreen
import es.opendash.ui.SettingsScreen

class MainActivity : AppCompatActivity() {

    private lateinit var settings: Settings
    private lateinit var plugins: PluginRepository
    private lateinit var monitor: Monitor

    override fun onCreate(savedInstanceState: Bundle?) {
        LocaleManager.restore(this)
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        plugins = PluginRepository(this)
        monitor = Monitor(settings)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    App(settings, plugins, monitor, ::unlock)
                }
            }
        }
    }

    override fun onDestroy() {
        monitor.stop()
        super.onDestroy()
    }

    /**
     * Ask for the device credential before letting the actuation menu appear.
     *
     * Whatever the phone is already set up with — fingerprint, face, PIN or
     * pattern — rather than a password of our own. A password we invented would
     * be one more thing to forget, and no safer.
     */
    private fun unlock(onResult: (Boolean) -> Unit) {
        val allowed = BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(allowed) != BiometricManager.BIOMETRIC_SUCCESS) {
            // No screen lock at all: refuse rather than silently open it up.
            onResult(false)
            return
        }
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) =
                    onResult(true)

                override fun onAuthenticationError(code: Int, message: CharSequence) = onResult(false)
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.settings_advanced))
                .setSubtitle(getString(R.string.settings_advanced_prompt))
                .setAllowedAuthenticators(allowed)
                .build()
        )
    }
}

private enum class Tab(val label: Int) {
    CONNECTION(R.string.tab_connection),
    LIVE(R.string.tab_live),
    READINESS(R.string.tab_readiness),
    RECORDINGS(R.string.tab_recordings),
    PLUGINS(R.string.tab_plugins),
    SETTINGS(R.string.tab_settings),
}

@Composable
private fun App(
    settings: Settings,
    plugins: PluginRepository,
    monitor: Monitor,
    unlock: ((Boolean) -> Unit) -> Unit,
) {
    var tab by remember { mutableStateOf(Tab.CONNECTION) }
    var advanced by remember { mutableStateOf(false) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {},
                        label = { Text(stringResource(t.label)) },
                    )
                }
            }
        }
    ) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            when (tab) {
                Tab.CONNECTION -> ConnectionScreen()
                Tab.LIVE -> LiveScreen(monitor)
                Tab.READINESS -> ReadinessScreen()
                Tab.RECORDINGS -> RecordingsScreen(settings)
                Tab.PLUGINS -> PluginsScreen(plugins, settings)
                Tab.SETTINGS -> SettingsScreen(
                    settings = settings,
                    advancedEnabled = advanced,
                    onUnlockAdvanced = unlock,
                    onAdvancedChanged = { advanced = it },
                )
            }
        }
    }
}
