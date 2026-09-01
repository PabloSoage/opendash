package com.varuna.opendash

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * Per-app language, the AndroidX way. On Android 13 and later the system keeps
 * the choice itself and shows it in the system settings; below that AppCompat
 * stores it. Either way the caller only deals with a tag.
 *
 * An empty tag means "follow the system", which is the default.
 *
 * Changing the language recreates the activity — that is how Android applies a
 * configuration change, and there is no way around it. What can be avoided is
 * landing back on the first tab afterwards, so the navigation state is saved
 * across the recreation and the screen comes back where it was.
 */
object LocaleManager {

    const val SYSTEM = ""

    val supported = listOf(SYSTEM, "en", "es", "de")

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
