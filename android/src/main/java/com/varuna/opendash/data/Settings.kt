package com.varuna.opendash.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * What the app remembers between runs.
 *
 * Every field is backed by both a preference and a Compose state, so a screen
 * that changes a setting redraws immediately instead of waiting to be left and
 * re-entered. The preference is the durable copy; the state is what the UI
 * observes.
 */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("opendash", Context.MODE_PRIVATE)

    // ── appearance ────────────────────────────────────────────────────────

    enum class ThemeMode { SYSTEM, LIGHT, DARK }

    var theme: ThemeMode by pref(
        KEY_THEME,
        ThemeMode.entries.firstOrNull { it.name == prefs.getString(KEY_THEME, null) }
            ?: ThemeMode.DARK,
    ) { it.name }

    // ── adapter ───────────────────────────────────────────────────────────

    /** The SM3 serves on this address once the phone has joined its access point. */
    var host: String by pref(KEY_HOST, prefs.getString(KEY_HOST, null) ?: "192.168.81.1") { it }

    var port: Int by pref(KEY_PORT, prefs.getInt(KEY_PORT, 777)) { it }

    /**
     * The access point password, kept only so it can be copied into the system
     * Wi-Fi dialog. Joining a network on the app's behalf needs location
     * permission and a suggestion API that behaves differently on every
     * Android version; opening the Wi-Fi panel with the password on the
     * clipboard is one tap and always works.
     */
    var wifiPassword: String by pref(KEY_PSK, prefs.getString(KEY_PSK, null) ?: "12345678") { it }

    var elmPort: Int by pref(KEY_ELM_PORT, prefs.getInt(KEY_ELM_PORT, 35000)) { it }

    // ── recording ─────────────────────────────────────────────────────────

    /**
     * A document tree the user picked, or empty for app storage.
     *
     * A path typed by hand is not usable on modern Android — an app cannot
     * write to an arbitrary path even when the user knows it — so the folder
     * comes from the system picker and is remembered as a permission-carrying
     * URI.
     */
    var recordingTree: String by pref(KEY_TREE, prefs.getString(KEY_TREE, null) ?: "") { it }

    var compressRecordings: Boolean by pref(KEY_GZIP, prefs.getBoolean(KEY_GZIP, true)) { it }

    // ── live data ─────────────────────────────────────────────────────────

    /** How many parameters to chart at once. The rest still show as numbers. */
    var chartCount: Int by pref(KEY_CHARTS, prefs.getInt(KEY_CHARTS, 4)) { it }

    /**
     * Extra delay between requests, on top of the round trip.
     *
     * Zero by default, and that is the right default: the exchange is
     * synchronous, so the adapter already sets the pace and a sleep on top only
     * makes it slower. The setting exists for being gentle with a module that
     * is busy doing something else.
     */
    var pollIntervalMs: Int by pref(KEY_POLL, prefs.getInt(KEY_POLL, 0)) { it }

    var catalogueLanguage: String by pref(
        KEY_CATALOGUE_LANG,
        prefs.getString(KEY_CATALOGUE_LANG, null) ?: "en",
    ) { it }

    // ── plumbing ──────────────────────────────────────────────────────────

    /**
     * A property that is a Compose state and a preference at once. [encode]
     * turns the value into something SharedPreferences can hold; the type of
     * the initial value decides which put is used.
     */
    private fun <T : Any> pref(key: String, initial: T, encode: (T) -> Any) =
        object : kotlin.properties.ReadWriteProperty<Any?, T> {
            private var state by mutableStateOf(initial)

            override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): T = state

            override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: T) {
                state = value
                val editor = prefs.edit()
                when (val encoded = encode(value)) {
                    is Int -> editor.putInt(key, encoded)
                    is Boolean -> editor.putBoolean(key, encoded)
                    else -> editor.putString(key, encoded.toString())
                }
                editor.apply()
            }
        }

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_HOST = "sm3_host"
        const val KEY_PORT = "sm3_port"
        const val KEY_PSK = "sm3_psk"
        const val KEY_ELM_PORT = "elm_port"
        const val KEY_TREE = "recording_tree"
        const val KEY_GZIP = "compress_recordings"
        const val KEY_CHARTS = "chart_count"
        const val KEY_POLL = "poll_interval"
        const val KEY_CATALOGUE_LANG = "catalogue_language"
    }
}
