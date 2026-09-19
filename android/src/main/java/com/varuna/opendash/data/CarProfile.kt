package com.varuna.opendash.data

import android.content.Context

/**
 * What one car answered when it was asked, kept so it only has to be asked
 * once.
 *
 * ## Why this exists
 *
 * A brand catalogue is every configuration the marque ever shipped. For
 * Opel-Vauxhall that is 21 382 parameters over 221 variants, and the engine
 * alone has 29 variants naming 4 080 keys. Nothing in the package says which of
 * them is the car in front of you: the GDS2 deliverables carry no
 * model-to-variant table, and the two strings that would identify this
 * engine — `DENSO0100` and `A17DTJ` — appear in none of the five packages. The
 * factory tool asks the car and matches at run time.
 *
 * Offering the unfiltered list instead is what put cylinders five to eight in
 * front of a four cylinder engine, alongside actuator commands for actuators it
 * does not have and quantities belonging to twenty-eight other engines. Those
 * rows do not fail loudly; they sit there never moving, and a row that never
 * moves looks exactly like a sensor reading zero.
 *
 * ## What is stored
 *
 * Per vehicle and module, the identifiers the module answered. Keyed by VIN so
 * that changing cars does not inherit the previous one's answers, and so that
 * coming back to a car it has already met costs nothing.
 *
 * This is a **measurement, not a fact about the catalogue**, and it can go
 * stale: a module reprogrammed with different software answers differently.
 * Hence [when] — a profile carries the day it was taken so a screen can offer
 * to take it again rather than quietly trusting something from months ago.
 */
class CarProfile(context: Context) {

    private val prefs = context.getSharedPreferences("opendash", Context.MODE_PRIVATE)

    /** The identifiers [module] answered on the car with this [vin]. */
    fun answered(vin: String, module: Int): Set<Int>? = read(key(vin, module))

    /**
     * The identifiers that were *asked*, answered or not.
     *
     * Kept because the answers alone do not say which configuration a module
     * is. Scoring the catalogue's variants by how many of their identifiers
     * answered rewards the big ones, which contain the small ones: on this car
     * twelve answers put three variants in a dead heat, the right one among
     * them but not above them. What separates them is the other half — how many
     * of a variant's identifiers were asked and came back refused — and that
     * cannot be recovered later, because a missing identifier is otherwise
     * indistinguishable from one nobody ever asked about.
     */
    fun asked(vin: String, module: Int): Set<Int>? = read(key(vin, module) + ".asked")

    private fun read(k: String): Set<Int>? {
        val raw = prefs.getString(k, null) ?: return null
        if (raw.isEmpty()) return emptySet()
        return raw.split(',').mapNotNullTo(LinkedHashSet()) { it.toIntOrNull() }
    }

    /** When that profile was taken, or null if there is none. */
    fun taken(vin: String, module: Int): Long? =
        prefs.getLong(key(vin, module) + ".when", 0L).takeIf { it > 0L }

    /**
     * How wide the module's answer was, per identifier.
     *
     * The catalogue lists rows of several widths under one identifier and only
     * the module knows which applies. Stored as `id:bytes` pairs beside the
     * answers themselves.
     */
    fun widths(vin: String, module: Int): Map<Int, Int> {
        val raw = prefs.getString(key(vin, module) + ".widths", null) ?: return emptyMap()
        val out = LinkedHashMap<Int, Int>()
        for (pair in raw.split(',')) {
            val colon = pair.indexOf(':')
            if (colon <= 0) continue
            val id = pair.substring(0, colon).toIntOrNull() ?: continue
            val bytes = pair.substring(colon + 1).toIntOrNull() ?: continue
            out[id] = bytes
        }
        return out
    }

    fun save(vin: String, module: Int, asked: Set<Int>, answered: Map<Int, Int>) {
        prefs.edit()
            .putString(key(vin, module), answered.keys.joinToString(","))
            .putString(
                key(vin, module) + ".widths",
                answered.entries.joinToString(",") { it.key.toString() + ":" + it.value },
            )
            .putString(key(vin, module) + ".asked", asked.joinToString(","))
            .putLong(key(vin, module) + ".when", System.currentTimeMillis())
            .apply()
    }

    fun forget(vin: String, module: Int) {
        prefs.edit()
            .remove(key(vin, module))
            .remove(key(vin, module) + ".asked")
            .remove(key(vin, module) + ".widths")
            .remove(key(vin, module) + ".when")
            .apply()
    }

    private fun key(vin: String, module: Int): String =
        "profile." + vin.ifBlank { "unknown" } + "." + module

    // ── what makes the screens usable with no car in front of them ─────

    /**
     * The last vehicle this app talked to.
     *
     * Everything above is keyed by VIN, which is right — and useless on a
     * kitchen table, because the VIN comes from the car. Remembering the last
     * one is what lets a screen open the right profile when there is nothing to
     * ask.
     */
    var lastVin: String
        get() = prefs.getString("profile.last", "").orEmpty()
        set(value) {
            if (value.isNotBlank()) prefs.edit().putString("profile.last", value).apply()
        }

    /** Every vehicle there is a stored profile for, newest first. */
    fun vehicles(): List<String> =
        prefs.all.keys
            .filter { it.startsWith("profile.") && it.endsWith(".when") }
            .mapNotNull { it.removePrefix("profile.").removeSuffix(".when").substringBeforeLast('.').ifBlank { null } }
            .distinct()

    /**
     * Which standard OBD PIDs the car advertised.
     *
     * Asked live on every rebuild until now, which is fine with the car
     * connected and is the one thing standing between the live screen and
     * being useful on a sofa. It is four short requests and it does not change,
     * so it is worth keeping.
     */
    fun supported(vin: String): Set<Int>? = read("supported." + vin.ifBlank { "unknown" })

    fun saveSupported(vin: String, pids: Set<Int>) {
        prefs.edit()
            .putString("supported." + vin.ifBlank { "unknown" }, pids.joinToString(","))
            .apply()
    }

    /**
     * A stored profile as text, so it can leave the phone.
     *
     * The point is not backup. Scanning one module is a few thousand requests
     * and several minutes sitting in a cold car; being able to carry the answer
     * to another phone, or into a repository beside the catalogue it belongs
     * to, is the difference between designing a recording at the kerb and
     * designing it at a desk.
     *
     * **The VIN is not written unless it is asked for.** It identifies the
     * vehicle and, through it, its owner, and the whole reason to export this
     * is to give it to somebody. [asVin] is what the file is keyed by instead.
     */
    fun export(vin: String, module: Int, asVin: String = "vehicle"): String {
        val answeredWidths = widths(vin, module)
        val answeredIds = answered(vin, module).orEmpty()
        val out = StringBuilder()
        out.append("# opendash car profile\n")
        out.append("vin\t").append(asVin).append('\n')
        out.append("module\t").append(module).append('\n')
        out.append("taken\t").append(taken(vin, module) ?: 0L).append('\n')
        out.append("supported\t").append(supported(vin).orEmpty().joinToString(",")).append('\n')
        out.append("asked\t").append(asked(vin, module).orEmpty().joinToString(",")).append('\n')
        out.append("answered\t")
        out.append(answeredIds.joinToString(",") { it.toString() + ":" + (answeredWidths[it] ?: 0) })
        out.append('\n')
        return out.toString()
    }

    /** Reads back what [export] wrote. Returns the VIN and module it was for. */
    fun import(text: String): Pair<String, Int>? {
        var vin = ""
        var module = -1
        var taken = 0L
        var supported = emptySet<Int>()
        var asked = emptySet<Int>()
        val answered = LinkedHashMap<Int, Int>()
        for (line in text.lineSequence()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            val value = line.substring(tab + 1)
            when (line.substring(0, tab)) {
                "vin" -> vin = value.trim()
                "module" -> module = value.trim().toIntOrNull() ?: -1
                "taken" -> taken = value.trim().toLongOrNull() ?: 0L
                "supported" ->
                    supported = value.split(',').mapNotNullTo(LinkedHashSet()) { it.toIntOrNull() }
                "asked" ->
                    asked = value.split(',').mapNotNullTo(LinkedHashSet()) { it.toIntOrNull() }
                "answered" -> for (pair in value.split(',')) {
                    val colon = pair.indexOf(':')
                    if (colon <= 0) continue
                    val id = pair.substring(0, colon).toIntOrNull() ?: continue
                    answered[id] = pair.substring(colon + 1).toIntOrNull() ?: 0
                }
            }
        }
        if (vin.isBlank() || module < 0 || answered.isEmpty()) return null
        save(vin, module, asked, answered)
        if (supported.isNotEmpty()) saveSupported(vin, supported)
        // The day it was taken travels with it. A profile that came from
        // somewhere else is exactly the case where "how old is this" matters,
        // and stamping it with today would be a lie that reads as reassurance.
        if (taken > 0L) prefs.edit().putLong(key(vin, module) + ".when", taken).apply()
        lastVin = vin
        return vin to module
    }
}
