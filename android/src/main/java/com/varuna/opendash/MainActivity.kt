package com.varuna.opendash

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.ContextCompat
import com.varuna.opendash.data.Monitor
import com.varuna.opendash.data.PluginRepository
import com.varuna.opendash.data.RecordingStore
import com.varuna.opendash.data.SessionFile
import com.varuna.opendash.data.Settings
import com.varuna.opendash.ui.CataloguesScreen
import com.varuna.opendash.ui.FilesScreen
import com.varuna.opendash.ui.HealthScreen
import com.varuna.opendash.ui.IdentificationScreen
import com.varuna.opendash.ui.LinkScreen
import com.varuna.opendash.ui.LiveScreen
import com.varuna.opendash.ui.RecordingScreen
import com.varuna.opendash.ui.SettingsScreen
import com.varuna.opendash.ui.theme.OpenDashTheme

class MainActivity : AppCompatActivity() {

    private lateinit var settings: Settings
    private lateinit var store: RecordingStore
    private lateinit var plugins: PluginRepository
    private lateinit var monitor: Monitor

    override fun onCreate(savedInstanceState: Bundle?) {
        LocaleManager.restore(this)
        settings = Settings(this)
        // Set before super so AppCompat picks the right resource qualifiers
        // while inflating, which is what keeps the window background from
        // flashing the wrong colour on launch. The live theme below is what
        // actually drives the UI, so changing the setting is instant and this
        // only has to be right by the next launch.
        AppCompatDelegate.setDefaultNightMode(
            when (settings.theme) {
                Settings.ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                Settings.ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
                Settings.ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        store = RecordingStore(this, settings)
        plugins = PluginRepository(this)
        monitor = Monitor(settings, store)

        setContent {
            val dark = when (settings.theme) {
                Settings.ThemeMode.LIGHT -> false
                Settings.ThemeMode.DARK -> true
                Settings.ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            OpenDashTheme(dark = dark) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    App(settings, store, plugins, monitor, ::unlock)
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
     * pattern — rather than a password of our own, which would be one more
     * thing to forget and no safer.
     */
    private fun unlock(onResult: (Boolean) -> Unit) {
        val allowed = BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(allowed) != BiometricManager.BIOMETRIC_SUCCESS) {
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
                .setTitle(getString(R.string.settings_advanced_prompt))
                .setSubtitle(getString(R.string.settings_advanced))
                .setAllowedAuthenticators(allowed)
                .build()
        )
    }
}

/**
 * The five places the app goes, and what each is called.
 *
 * One short word each. A navigation bar splits the width evenly between its
 * items, so a longer word does not shrink the label — it wraps it, and
 * "Recordings" arriving as "Recordin" over "gs" is the result. The Spanish and
 * German strings are held to the same length for the same reason.
 */
private enum class Tab(val label: Int, val icon: ImageVector) {
    LINK(R.string.tab_link, Icons.Filled.Link),
    LIVE(R.string.tab_live, Icons.AutoMirrored.Filled.ShowChart),
    HEALTH(R.string.tab_health, Icons.Filled.MonitorHeart),
    FILES(R.string.tab_files, Icons.Filled.Folder),
    SETTINGS(R.string.tab_settings, Icons.Filled.Settings),
}

/** Screens reached from within a tab rather than from the bar. */
private enum class Detail(val title: Int) {
    CATALOGUES(R.string.catalogues),

    /** The recording viewer. Its title is the file name, not this. */
    RECORDING(R.string.files_recordings),

    IDENTIFICATION(R.string.ident_title),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(
    settings: Settings,
    store: RecordingStore,
    plugins: PluginRepository,
    monitor: Monitor,
    unlock: ((Boolean) -> Unit) -> Unit,
) {
    // Saved rather than merely remembered: changing the language recreates the
    // activity, and coming back on the tab you left is the difference between
    // a setting being applied and the app appearing to restart.
    var tabName by rememberSaveable { mutableStateOf(Tab.LINK.name) }
    var detailName by rememberSaveable { mutableStateOf<String?>(null) }
    var advanced by rememberSaveable { mutableStateOf(false) }

    // The opened recording is deliberately not saved: it is eighty thousand
    // readings, far past what an instance-state bundle will carry, so on a
    // rotation the viewer closes back to the file list rather than failing.
    var recording by remember { mutableStateOf<SessionFile.Session?>(null) }
    var recordingName by rememberSaveable { mutableStateOf("") }

    val tab = Tab.valueOf(tabName)
    // A saved route pointing at a recording that is no longer loaded resolves
    // to no route at all, which lands back on the file list.
    val detail = detailName
        ?.let { Detail.valueOf(it) }
        ?.takeUnless { it == Detail.RECORDING && recording == null }

    Scaffold(
        topBar = {
            if (detail != null) {
                TopAppBar(
                    title = {
                        Text(
                            if (detail == Detail.RECORDING && recordingName.isNotEmpty()) recordingName
                            else stringResource(detail.title),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { detailName = null }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            }
        },
        bottomBar = {
            if (detail == null) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tabName = t.name },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = {
                                Text(
                                    stringResource(t.label),
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        },
    ) { inner ->
        Box(modifier = Modifier.fillMaxSize().padding(inner)) {
            when {
                detail == Detail.CATALOGUES -> CataloguesScreen(plugins, settings)
                detail == Detail.RECORDING -> recording?.let { RecordingScreen(it) }
                detail == Detail.IDENTIFICATION -> IdentificationScreen()
                tab == Tab.LINK -> LinkScreen(settings) { detailName = Detail.IDENTIFICATION.name }
                tab == Tab.LIVE -> LiveScreen(monitor, plugins, settings)
                tab == Tab.HEALTH -> HealthScreen()
                tab == Tab.FILES -> FilesScreen(store) { session, name ->
                    recording = session
                    recordingName = name
                    detailName = Detail.RECORDING.name
                }
                tab == Tab.SETTINGS -> SettingsScreen(
                    settings = settings,
                    store = store,
                    plugins = plugins,
                    advancedEnabled = advanced,
                    onUnlockAdvanced = unlock,
                    onAdvancedChanged = { advanced = it },
                    onOpenCatalogues = { detailName = Detail.CATALOGUES.name },
                )
            }
        }
    }
}
