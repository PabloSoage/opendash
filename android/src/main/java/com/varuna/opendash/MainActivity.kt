package com.varuna.opendash

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.ContextCompat
import com.varuna.opendash.data.Monitor
import com.varuna.opendash.data.PluginRepository
import com.varuna.opendash.data.RecordingStore
import com.varuna.opendash.data.SessionFile
import com.varuna.opendash.data.Settings
import com.varuna.opendash.obd.Actuation
import com.varuna.opendash.ui.CataloguesScreen
import com.varuna.opendash.ui.FilesScreen
import com.varuna.opendash.ui.HealthScreen
import com.varuna.opendash.ui.IdentificationScreen
import com.varuna.opendash.ui.LinkScreen
import com.varuna.opendash.ui.LiveScreen
import com.varuna.opendash.ui.RecordingScreen
import com.varuna.opendash.ui.SettingsScreen
import com.varuna.opendash.ui.theme.OpenDashTheme
import com.varuna.opendash.update.AppUpdate
import com.varuna.opendash.update.UpdateDialog

class MainActivity : AppCompatActivity() {

    private lateinit var settings: Settings
    private lateinit var store: RecordingStore
    private lateinit var plugins: PluginRepository
    private lateinit var monitor: Monitor

    override fun onCreate(savedInstanceState: Bundle?) {
        LocaleManager.restore(this)
        // One of each per process, not per window. See [kept].
        val held = kept ?: Kept(Settings(applicationContext)).also { kept = it }
        settings = held.settings
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

        store = held.store
        plugins = PluginRepository(this)
        monitor = held.monitor
        askForNotifications()

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

    /**
     * A window going away is not a run ending.
     *
     * This used to stop the monitor every time, and onDestroy is not only
     * called when somebody closes the app: the system also destroys and
     * rebuilds the activity for configuration changes it is not told to leave
     * alone, and destroys it outright to reclaim memory while the process lives
     * on in its foreground service. Either way the recording ended mid-drive,
     * with the phone in a pocket and nobody having pressed anything.
     *
     * So a recording is only ever stopped by Stop, or by the module going
     * silent for good. A live view with no file behind it is still let go when
     * the app is really being closed, because then it is only holding the bus
     * and a wake lock for a screen nobody can see.
     */
    override fun onDestroy() {
        if (isFinishing && monitor.isRunning && !monitor.isRecording) monitor.stop()
        if (!monitor.isRunning) handBackTheAdapter()
        super.onDestroy()
    }

    /**
     * Say goodbye to the adapter when the app is really going away.
     *
     * The device takes one client and does not notice a socket that simply
     * disappears, so an app that is swiped shut leaves the session held and
     * the next application to try — the manufacturer's own included — finds it
     * refusing everything until it is unplugged and back in.
     *
     * Not on a rotation, which also calls this, and not while the bridge is
     * still serving somebody over the same link. On a worker because saying
     * goodbye is socket writes and those are not allowed on the main thread,
     * and waited for briefly because the process may not outlive this method.
     */
    private fun handBackTheAdapter() {
        if (!isFinishing || Session.bridgePort > 0) return
        if (Session.state == Session.State.DISCONNECTED) return
        val worker = Thread { runCatching { Session.disconnect() } }
        worker.start()
        worker.join(GOODBYE_MS)
    }

    /**
     * What has to outlive the window: the monitor, and what it was built with.
     *
     * The monitor owns the worker thread that reads the module and the file it
     * writes to. Built per activity, a rebuilt activity got a fresh monitor
     * that knew nothing of the run still going in the old one: the screen said
     * nothing was recording while something was, and Stop could not reach it.
     */
    private class Kept(val settings: Settings) {
        val store = RecordingStore(settings.context, settings)
        val monitor = Monitor(settings, store)
    }

    private companion object {
        private var kept: Kept? = null

        /**
         * Four short messages at 400 ms each, with room for the client to open
         * one fresh link if the old one had already gone. Not long enough for
         * that whole second attempt — this runs on the main thread while the
         * app is closing, and holding it much longer is how a shutdown turns
         * into "the app is not responding". The disconnect button, which is not
         * on this path, gets as long as it needs.
         */
        const val GOODBYE_MS = 2500L
    }

    /**
     * Ask for the device credential before letting the actuation menu appear.
     *
     * Whatever the phone is already set up with — fingerprint, face, PIN or
     * pattern — rather than a password of our own, which would be one more
     * thing to forget and no safer.
     */
    /**
     * Ask once for the notification permission.
     *
     * The bridge runs as a foreground service and a foreground service is its
     * notification: without permission the service still runs but nothing shows
     * it, so there is no way to tell from a locked phone whether the link is up.
     * Asked here rather than when the bridge starts, because a permission
     * dialog appearing over the car screen at the moment of connecting is the
     * worst possible time for one.
     */
    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * Registered as a field so it exists before the activity is started, which
     * is the only point at which registering is allowed.
     */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

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
    // Not remembered anywhere. Actuation.unlocked is the single copy, because
    // Diagnostics consults it before it will emit a command and a second copy
    // that drifts from it is a lock that is open on one side only. It is also
    // deliberately not saved across a rotation: re-answering the device lock
    // costs a thumb, and an unlock that outlives the screen it was granted on
    // is an unlock nobody remembers granting.
    val advanced = Actuation.unlocked

    // The opened recording is deliberately not saved: it is eighty thousand
    // readings, far past what an instance-state bundle will carry, so on a
    // rotation the viewer closes back to the file list rather than failing.
    var recording by remember { mutableStateOf<SessionFile.Session?>(null) }
    var recordingName by rememberSaveable { mutableStateOf("") }

    // Once per process, and only offered, never forced. Not while a run is
    // going: a dialog over the live screen in the middle of a drive is the
    // worst moment there is for one, and Settings has it whenever it is wanted.
    val context = LocalContext.current
    var updateDismissed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (settings.checkUpdates) AppUpdate.checkOnStart(context.applicationContext)
    }
    (AppUpdate.status as? AppUpdate.Status.Available)?.let { available ->
        if (!updateDismissed && !monitor.isRunning && tabName != Tab.SETTINGS.name) {
            UpdateDialog(available.update, onDismiss = { updateDismissed = true })
        }
    }

    val tab = Tab.valueOf(tabName)
    // A saved route pointing at a recording that is no longer loaded resolves
    // to no route at all, which lands back on the file list.
    val detail = detailName
        ?.let { Detail.valueOf(it) }
        ?.takeUnless { it == Detail.RECORDING && recording == null }

    // Back closes the screen you are on, not the app.
    //
    // The arrow in the bar already did this; the system gesture did not, so
    // opening a recording and swiping back left the app — losing the session,
    // the selection and, while one was running, the link. Enabled only while
    // there is somewhere to go back to, so on a top-level tab back still means
    // leave, which is what it should mean there.
    BackHandler(enabled = detail != null) { detailName = null }

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
                    onAdvancedChanged = { if (it) Actuation.unlock() else Actuation.lock() },
                    onOpenCatalogues = { detailName = Detail.CATALOGUES.name },
                )
            }
        }
    }
}
