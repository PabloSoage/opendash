package es.opendash

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * Per-app language, the AndroidX way. On Android 13 and later the system keeps
 * the choice itself and shows it in Settings; below that AppCompat stores it.
 * Either way the caller only deals with a tag.
 *
 * An empty tag means "follow the system", which is the default and what most
 * people want. The picker exists for the case it isn't — testing the Spanish
 * strings on an English phone, mostly.
 */
object LocaleManager {

    const val SYSTEM = ""

    val supported = listOf(SYSTEM, "en", "es")

    fun current(): String =
        AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore(',')

    fun apply(tag: String) {
        AppCompatDelegate.setApplicationLocales(
            if (tag == SYSTEM) LocaleListCompat.getEmptyLocaleList()
            else LocaleListCompat.forLanguageTags(tag)
        )
    }

    /** Remembered across restarts so the choice survives an app kill. */
    fun restore(context: Context) {
        val stored = prefs(context).getString(KEY, SYSTEM) ?: SYSTEM
        if (stored != current()) apply(stored)
    }

    fun store(context: Context, tag: String) {
        prefs(context).edit().putString(KEY, tag).apply()
        apply(tag)
    }

    private const val KEY = "locale"

    private fun prefs(context: Context) =
        context.getSharedPreferences("opendash", Context.MODE_PRIVATE)
}
