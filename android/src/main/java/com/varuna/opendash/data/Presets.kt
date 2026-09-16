package com.varuna.opendash.data

import android.content.Context

/**
 * Named sets of parameters, per module.
 *
 * Selecting what to watch is the slow part of using this. A module offers
 * hundreds of rows, the ones worth watching together are always the same
 * handful — a turbo group, a DPF group, an injection group — and rebuilding
 * that by hand every time, through a search box, is how a session starts.
 *
 * Kept by module rather than globally: "Turbo" on the engine and "Turbo" on
 * something else are not the same selection, and a preset offered against a
 * module that has none of its rows is worse than no preset at all.
 *
 * Rows are named by [Catalogue.Parameter.rowKey], the same identity the list,
 * the values and the recording use, so a preset survives the catalogue being
 * reinstalled as long as the row itself still exists. Rows that no longer exist
 * are dropped on load rather than restored as blanks.
 */
class Presets(context: Context) {

    private val prefs = context.getSharedPreferences("presets", Context.MODE_PRIVATE)

    /** Preset names for [module], in the order a person would read them. */
    fun names(module: String): List<String> =
        prefs.getStringSet(key(module), emptySet()).orEmpty().sorted()

    /** The row keys of one preset, or empty if there is no such preset. */
    fun load(module: String, name: String): Set<String> =
        prefs.getStringSet(entry(module, name), emptySet()).orEmpty()

    /** Store [keys] under [name]. An existing preset of that name is replaced. */
    fun save(module: String, name: String, keys: Set<String>) {
        if (module.isEmpty() || name.isBlank() || keys.isEmpty()) return
        val all = names(module).toMutableSet()
        all.add(name)
        prefs.edit()
            .putStringSet(key(module), all)
            .putStringSet(entry(module, name), keys)
            .apply()
    }

    fun forget(module: String, name: String) {
        val all = names(module).toMutableSet()
        all.remove(name)
        prefs.edit()
            .putStringSet(key(module), all)
            .remove(entry(module, name))
            .apply()
    }

    private fun key(module: String) = "names|$module"

    private fun entry(module: String, name: String) = "set|$module|$name"
}
