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
        prefs.getStringSet(namesKey(module), emptySet()).orEmpty().sorted()

    /**
     * The row keys of one preset, in the order they were written.
     *
     * Order is not decoration. It decides which identifiers share a packet and
     * which round they are declared in, and two parameters in the same packet
     * arrive in the same frame — the same instant. A profile that wants the air
     * mass and its target compared has to be able to put them next to each
     * other, and a Set threw that away.
     *
     * Presets written before this was stored come back from the old unordered
     * form rather than not at all.
     */
    fun load(module: String, name: String): List<String> {
        val ordered = prefs.getString(orderKey(module, name), null)
        if (ordered != null) {
            return ordered.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        }
        return prefs.getStringSet(entryKey(module, name), emptySet()).orEmpty().sorted()
    }

    /** Whether [name] is already stored for [module]. */
    fun has(module: String, name: String): Boolean =
        prefs.contains(orderKey(module, name)) || prefs.contains(entryKey(module, name))

    /** Store [keys] under [name], in order. An existing preset is replaced. */
    fun save(module: String, name: String, keys: List<String>) {
        if (module.isEmpty() || name.isBlank() || keys.isEmpty()) return
        val all = names(module).toMutableSet()
        all.add(name)
        prefs.edit()
            .putStringSet(namesKey(module), all)
            .putString(orderKey(module, name), keys.joinToString("\n"))
            .remove(entryKey(module, name))
            .apply()
    }

    fun forget(module: String, name: String) {
        val all = names(module).toMutableSet()
        all.remove(name)
        prefs.edit()
            .putStringSet(namesKey(module), all)
            .remove(entryKey(module, name))
            .remove(orderKey(module, name))
            .apply()
    }

    /**
     * A preset as text, to carry off the phone and onto another one.
     *
     * Deliberately the plainest thing that works: the module, the name, and the
     * row keys one per line. A row key already describes its row in full —
     * name, identifier, width, formula, unit — so an exported preset can be
     * read, checked and edited by hand, and written by something that is not
     * this app. A binary format would have wanted a version number by the
     * second week.
     */
    fun export(module: String, name: String): String {
        val keys = load(module, name)
        return buildString {
            appendLine(MAGIC + " 1")
            appendLine("module\t" + module)
            appendLine("name\t" + name)
            for (k in keys) appendLine(k)
        }
    }

    /** One preset as it appears in a file, before anything is stored. */
    class Entry(val module: String, val name: String, val keys: List<String>)

    /**
     * Every preset in [text], parsed and nothing more.
     *
     * Reading is separate from storing because a catalogue's profiles have to
     * be shown before they are taken: a list of what a catalogue offers is the
     * difference between a button that might do something and one that says
     * what it will do.
     *
     * A file may hold several, one after another, with `#` for comments.
     */
    fun read(text: String): List<Entry> {
        val out = ArrayList<Entry>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) parse(current.toString())?.let { out.add(it) }
            current.setLength(0)
        }
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#")) continue
            if (trimmed.startsWith(MAGIC)) flush()
            if (trimmed.isNotEmpty()) current.appendLine(trimmed)
        }
        flush()
        return out
    }

    /**
     * Read one back, returning the name it was stored under.
     *
     * Null when [text] is not a preset at all. That case is half the work: a
     * clipboard holds whatever was last copied, and an import that accepts
     * anything writes a preset full of somebody's shopping list.
     */
    fun import(text: String): String? {
        val entry = read(text).firstOrNull() ?: return null
        save(entry.module, entry.name, entry.keys)
        return entry.name
    }

    /**
     * Import every preset in [text], which may hold several.
     *
     * This is what a catalogue publishes in `profiles.txt`: the same format the
     * app exports. Returns the names stored, so a screen can say what arrived
     * rather than only that something did.
     */
    fun importAll(text: String): List<String> =
        read(text).map { save(it.module, it.name, it.keys); it.name }

    private fun parse(block: String): Entry? {
        val lines = block.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.firstOrNull()?.startsWith(MAGIC) != true) return null
        var module = ""
        var name = ""
        val keys = LinkedHashSet<String>()
        for (line in lines.drop(1)) {
            val tab = line.indexOf('\t')
            val head = if (tab > 0) line.substring(0, tab) else ""
            val rest = if (tab > 0) line.substring(tab + 1) else ""
            when (head) {
                "module" -> module = rest
                "name" -> name = rest
                else -> keys.add(line)
            }
        }
        if (module.isEmpty() || name.isEmpty() || keys.isEmpty()) return null
        return Entry(module, name, keys.toList())
    }

    private fun namesKey(module: String) = "names|$module"

    private fun entryKey(module: String, name: String) = "set|$module|$name"

    private fun orderKey(module: String, name: String) = "order|$module|$name"

    private companion object {
        const val MAGIC = "opendash-preset"
    }
}
