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
     * The access point password. Every one of these adapters leaves the factory
     * with the same one, so it is filled in rather than asked for.
     */
    var wifiPassword: String by pref(KEY_PSK, prefs.getString(KEY_PSK, null) ?: "12345678") { it }

    /**
     * The access point to join, or empty for "offer everything starting with
     * [wifiPrefix]". Empty is the better default the first time: the adapter's
     * name carries its serial number, so nobody knows it before they have seen
     * it once, and the system picker shows what is in range.
     */
    var wifiSsid: String by pref(KEY_SSID, prefs.getString(KEY_SSID, null) ?: "") { it }

    /**
     * Narrows the picker to the adapter's own access points.
     *
     * `DIRECT-SCANMATIK-#<serial>` is what one of these actually calls itself:
     * a Wi-Fi Direct name, the make, and the serial number. The default used
     * to be `SM`, which matches none of it, so asking the system for "anything
     * starting with the prefix" offered an empty picker and the join was
     * refused with no way to tell that from the adapter being switched off.
     *
     * A stored `SM` is treated as that old default rather than as a choice.
     * Nobody typed it; it was never able to match anything.
     */
    var wifiPrefix: String by pref(
        KEY_PREFIX,
        prefs.getString(KEY_PREFIX, null)?.takeUnless { it == OLD_PREFIX } ?: DEFAULT_PREFIX,
    ) { it }

    var elmPort: Int by pref(KEY_ELM_PORT, prefs.getInt(KEY_ELM_PORT, 35000)) { it }

    /**
     * Which network interface the ELM327 bridge binds to.
     * "127.0.0.1" for on-device apps, or "0.0.0.0" for external connections over Wi-Fi.
     */
    var elmHost: String by pref(KEY_ELM_HOST, prefs.getString(KEY_ELM_HOST, null) ?: "127.0.0.1") { it }

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

    /**
     * Which variant of an installed catalogue to offer, or empty for all of
     * them. A brand catalogue is every module configuration the marque ever
     * shipped; one vehicle is a handful of them.
     */
    var catalogueVariant: String by pref(KEY_VARIANT, prefs.getString(KEY_VARIANT, null) ?: "") { it }

    /**
     * Which module the live screen is looking at. A brand catalogue covers every
     * module the marque fits, and without one of them chosen the parameter list
     * is the whole marque rather than this car.
     */
    var catalogueModule: String by pref(KEY_MODULE, prefs.getString(KEY_MODULE, null) ?: "") { it }

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
        const val KEY_SSID = "sm3_ssid"
        const val KEY_PREFIX = "sm3_ssid_prefix"

        /** What the adapter's own access point is called, up to the serial. */
        const val DEFAULT_PREFIX = "DIRECT-SCANMATIK"

        /** The default before anyone had read a real access point name. */
        private const val OLD_PREFIX = "SM"
        const val KEY_ELM_PORT = "elm_port"
        const val KEY_ELM_HOST = "elm_host"
        const val KEY_MODULE = "catalogue_module"
        const val KEY_TREE = "recording_tree"
        const val KEY_GZIP = "compress_recordings"
        const val KEY_CHARTS = "chart_count"
        const val KEY_POLL = "poll_interval"
        const val KEY_CATALOGUE_LANG = "catalogue_language"
        const val KEY_VARIANT = "catalogue_variant"
    }
}
