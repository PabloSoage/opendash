package com.varuna.opendash.data

import java.io.File

/**
 * A parameter catalogue, as shipped in a plugin.
 *
 * The tab-separated files come out of a GDS2 installation. Names and units are
 * localised; keys, pids, formulas and ranges are the same in every language, so
 * a plugin can carry one or several without the numbers ever diverging.
 *
 * ## The module is the thing
 *
 * A brand catalogue is every configuration the marque ever shipped: 21 382
 * parameters for Opel, spread over 221 variants. Offered as a flat list of
 * variant names — "Amplifier - NGI", "Battery Sensor Module$IBS - PSA - GME
 * Initialize EPID List" — it is unusable, and that is what this screen used to
 * offer. It also threw away the one column that makes sense of it.
 *
 * `variants.tsv` has three columns, not two: the variant, **the module it
 * belongs to**, and the key. And `modules.tsv` gives every module a CAN address
 * in decimal — 2016 is 0x7E0, the engine. So the catalogue already knows which
 * module owns a parameter and where that module answers, which is enough to
 * turn the list into something a person can read and a car can be asked about:
 * the four modules that answer on this vehicle account for 5 941 parameters,
 * and the engine alone for 4 080.
 *
 * It also fixes a quieter fault. Every catalogue parameter used to be requested
 * from the engine, whatever module it belonged to, so a body module parameter
 * was asked of something that had never heard of it and was silently dropped.
 */
class Catalogue(
    val brand: String,
    val parameters: List<Parameter>,
    val variants: List<Variant>,
    val modules: List<Module>,
) {
    class Parameter(
        val key: String,
        val name: String,
        val pid: Int,
        val bytes: Int,
        val formula: String,
        val unit: String,
        val min: Double,
        val max: Double,
    ) {
        /**
         * What makes this row different from every other one on screen.
         *
         * Not the key: a catalogue key is nowhere near unique. Of this marque's
         * 11 480 keys, 2 859 carry more than one distinct parameter — key
         * 8483_6763 is twenty-eight different "Vehicle Speed" rows, each with
         * its own identifier and its own scaling, and 5857_51909 is thirty-seven
         * "Dummy EPID". A key names a concept; this names a row.
         */
        val signature: String
            get() = "$name|$pid|$bytes|$formula|$unit"

        /**
         * How a parameter is named in every map that holds one — the selection,
         * the live values, the chart series, the list itself.
         *
         * Written once because it has to agree in all of them: the screen looks
         * up a row's value by this, and if the monitor spells it any other way
         * the numbers simply never arrive, with nothing on screen to say why.
         */
        val rowKey: String
            get() = "cat:$signature"

        /** How the identifier is written wherever a row has to name it. */
        val identifierText: String
            get() = String.format(java.util.Locale.ROOT, "0x%04X", pid)

        /**
         * True when this row is one bit of a packed byte — a state, not a
         * measurement.
         *
         * Most of the catalogue is these: 16 347 of Opel's 21 381 rows carry no
         * unit, and the great majority of those are masks of a single bit with
         * names like "A/C Compressor Clutch Relay Command". Shown as 1.00 and
         * 0.00 with no unit they look like a measurement whose unit nobody
         * could determine, which is unsettling enough to leave unticked. They
         * are on and off.
         */
        val isFlag: Boolean
            get() = SINGLE_BIT.matches(formula)

        /**
         * Apply the catalogue's own scaling. The grammar is small — a factor
         * and an offset, or a shift and a mask for status bits — and anything
         * outside it returns the raw value rather than a wrong one.
         */
        fun scale(raw: Long): Double? {
            LINEAR.matchEntire(formula)?.let { m ->
                val (factor, offset) = m.destructured
                return raw * factor.toDouble() + offset.toDouble()
            }
            BITS.matchEntire(formula)?.let { m ->
                val (shift, mask) = m.destructured
                return ((raw shr shift.toInt()) and mask.removePrefix("0x").toLong(16)).toDouble()
            }
            return null
        }

        private companion object {
            val LINEAR = Regex("""\(X\(0\)\*(-?[\d.]+)\)([+-][\d.]+)""")
            val BITS = Regex("""\(X\(0\)>>(\d+)\)&(0x[0-9a-fA-F]+)""")
            val SINGLE_BIT = Regex("""\(X\(0\)>>\d+\)&0x1""")
        }
    }

    /** One configuration of one module, and the parameter keys it exposes. */
    class Variant(val name: String, val module: String, val keys: List<String>)

    /** A module the marque fits, and where on the bus it answers. */
    class Module(val name: String, val address: Int, val bus: String) {
        /** Only what sits on the high-speed pair is reachable over pins 6/14. */
        val reachable: Boolean get() = bus.contains("HS_PRIMARY")
    }

    /**
     * A key names a *quantity*, not a row, and several rows can carry it.
     *
     * `parameters.tsv` holds 21 382 rows under 11 480 distinct keys: 3 879 keys
     * appear more than once, because the same quantity is read differently by
     * different configurations of a module — a different identifier, a
     * different width, a different scale, sometimes a different unit for the
     * same sensor (`MAF Sensor` comes in g/s and in Hz).
     *
     * This used to be an `associateBy`, which keeps the last row for each key
     * and drops the rest without a word. That is not a smaller list, it is an
     * arbitrary one: the surviving row can carry the identifier or the width of
     * a configuration this car does not have, and reading two bytes where the
     * module sends one shifts every value after it in a streamed packet.
     */
    private val byKey: Map<String, List<Parameter>> = parameters.groupBy { it.key }

    /** Where each module answers. A name can appear at more than one address. */
    val addressesByModule: Map<String, List<Int>> =
        modules.groupBy { it.name }.mapValues { (_, m) -> m.map { it.address }.distinct().sorted() }

    /** Which modules sit at an address. More than one shares 0x7E0. */
    val modulesByAddress: Map<Int, List<String>> =
        modules.groupBy { it.address }.mapValues { (_, m) -> m.map { it.name }.distinct().sorted() }

    private val variantsByModule: Map<String, List<Variant>> = variants.groupBy { it.module }

    /** Modules that have parameters and a known address, in reading order. */
    val namedModules: List<String> =
        variantsByModule.keys.filter { it in addressesByModule }.sorted()

    fun variantsOf(module: String): List<Variant> =
        variantsByModule[module].orEmpty().sortedBy { it.name }

    /**
     * Parameters of one module, or of one variant of it when [variant] names
     * one. A module with no variant chosen is every parameter any of its
     * configurations exposes, which is the honest superset to start from.
     */
    fun parametersFor(module: String, variant: String = ""): List<Parameter> {
        val pool = variantsByModule[module].orEmpty()
        val chosen = if (variant.isEmpty()) pool else pool.filter { it.name == variant }
        val keys = LinkedHashSet<String>()
        for (v in chosen) keys.addAll(v.keys)
        // Every row for those keys, then one row per thing a person could tell
        // apart on screen. Twenty-nine engine variants between them name 4 080
        // keys carrying 9 844 rows, of which 2 606 are exact repeats — the same
        // name, identifier, width, formula and unit, listed once per variant
        // that happens to include it. Showing those is what made the list look
        // like it was full of duplicates, because it was.
        val seen = HashSet<String>()
        val out = ArrayList<Parameter>()
        for (k in keys) {
            for (p in byKey[k].orEmpty()) {
                if (seen.add(p.signature)) {
                    out.add(p)
                }
            }
        }
        return out
    }

    /**
     * The same list, kept to the identifiers [answered] by the car in front of
     * you.
     *
     * This is the only filter in this class that is not a fact about the
     * catalogue. A brand catalogue describes every configuration the marque
     * ever shipped, and no amount of reading it will say which one is parked
     * outside: the GDS2 packages carry no model-to-variant table, which is why
     * the factory tool asks the car and matches at run time. So does this.
     *
     * What that removes is not cosmetic. Without it an Astra with a four
     * cylinder A17DTJ is offered cylinders five to eight, actuator commands its
     * engine has no actuator for, and quantities from twenty-eight other
     * engines — all of them rows that would sit on screen never moving, with
     * nothing to say whether that means zero or means nobody answered.
     */
    fun keptTo(parameters: List<Parameter>, answered: Set<Int>): List<Parameter> =
        parameters.filter { it.pid in answered }

    companion object {
        /** Load a brand directory: parameters, variants and modules. */
        fun load(directory: File, language: String = "en"): Catalogue? {
            val suffix = if (language == "en") "" else ".$language"
            val params = File(directory, "parameters$suffix.tsv")
                .takeIf { it.exists() } ?: File(directory, "parameters.tsv")
            if (!params.exists()) return null

            val parameters = params.useLines { lines ->
                lines.drop(1).mapNotNull { parseParameter(it) }.toList()
            }

            val variantsFile = File(directory, "variants$suffix.tsv")
                .takeIf { it.exists() } ?: File(directory, "variants.tsv")
            // variant -> module, and variant -> keys. Keyed by variant name
            // because the same name never belongs to two modules, and reading
            // the module from the row is what makes the grouping possible.
            val ownerOf = HashMap<String, String>()
            val keysOf = HashMap<String, MutableList<String>>()
            if (variantsFile.exists()) {
                variantsFile.useLines { lines ->
                    lines.drop(1).forEach { line ->
                        val c = line.split('\t')
                        if (c.size >= 3 && c[0].isNotEmpty()) {
                            ownerOf.putIfAbsent(c[0], c[1])
                            keysOf.getOrPut(c[0]) { ArrayList() }.add(c[2])
                        }
                    }
                }
            }
            val variants = keysOf.map { (name, keys) ->
                Variant(name, ownerOf[name].orEmpty(), keys)
            }

            val modules = File(directory, "modules.tsv").let { f ->
                if (!f.exists()) emptyList() else f.useLines { lines ->
                    lines.drop(1).mapNotNull { line ->
                        val c = line.split('\t')
                        val address = c.getOrNull(1)?.trim()?.toIntOrNull()
                        if (c.size >= 3 && address != null) Module(c[0], address, c[2]) else null
                    }.distinctBy { it.name to it.address }.toList()
                }
            }

            return Catalogue(directory.name, parameters, variants, modules)
        }

        private fun parseParameter(line: String): Parameter? {
            val c = line.split('\t')
            if (c.size < 8) return null
            return Parameter(
                key = c[0],
                name = c[1],
                pid = c[2].toIntOrNull() ?: return null,
                bytes = c[3].toIntOrNull() ?: return null,
                formula = c[4],
                unit = c[5],
                min = c[6].toDoubleOrNull() ?: 0.0,
                max = c[7].toDoubleOrNull() ?: 0.0,
            )
        }
    }
}
