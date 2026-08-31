package es.opendash.data

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * What the app remembers between runs.
 *
 * The recording directory defaults to the shared Documents folder rather than
 * app-private storage: a recording is worth keeping and worth pulling off the
 * phone, and app-private files vanish with the app.
 */
class Settings(private val context: Context) {

    private val prefs = context.getSharedPreferences("opendash", Context.MODE_PRIVATE)

    var recordingPath: String
        get() = prefs.getString(KEY_PATH, null) ?: defaultRecordingDirectory().absolutePath
        set(value) = prefs.edit().putString(KEY_PATH, value).apply()

    var catalogueLanguage: String
        get() = prefs.getString(KEY_CATALOGUE_LANG, "en") ?: "en"
        set(value) = prefs.edit().putString(KEY_CATALOGUE_LANG, value).apply()

    var elmPort: Int
        get() = prefs.getInt(KEY_PORT, 35000)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    /** How many parameters to chart at once. More is legible on a tablet. */
    var chartCount: Int
        get() = prefs.getInt(KEY_CHARTS, 4)
        set(value) = prefs.edit().putInt(KEY_CHARTS, value.coerceIn(1, 12)).apply()

    /**
     * Extra delay between requests, on top of the round trip.
     *
     * Zero by default, and that is the right default: the exchange is
     * synchronous — send, wait for the answer, send the next — so the device
     * already sets the pace. A sleep on top only makes it slower.
     *
     * The floor measured over USB is about 35 ms per exchange, but that figure
     * includes the tracing overhead of how it was measured, so the device is
     * probably quicker. Over its own Wi-Fi the round trip is whatever the link
     * gives. Either way, waiting for the reply is the honest throttle.
     *
     * The setting exists for the case where you want to be gentle with a
     * module that is busy doing something else.
     */
    var pollIntervalMs: Int
        get() = prefs.getInt(KEY_POLL, 0)
        set(value) = prefs.edit().putInt(KEY_POLL, value.coerceIn(0, 5000)).apply()

    fun recordingDirectory(): File = File(recordingPath).also { it.mkdirs() }

    private fun defaultRecordingDirectory(): File {
        val shared = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        return File(shared ?: context.filesDir, "recordings")
    }

    private companion object {
        const val KEY_PATH = "recording_path"
        const val KEY_CATALOGUE_LANG = "catalogue_language"
        const val KEY_PORT = "elm_port"
        const val KEY_CHARTS = "chart_count"
        const val KEY_POLL = "poll_interval"
    }
}
