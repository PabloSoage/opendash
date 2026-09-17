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
}
