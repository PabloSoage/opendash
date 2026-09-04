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
        }
    }

    /** One configuration of one module, and the parameter keys it exposes. */
    class Variant(val name: String, val module: String, val keys: List<String>)

    /** A module the marque fits, and where on the bus it answers. */
    class Module(val name: String, val address: Int, val bus: String) {
        /** Only what sits on the high-speed pair is reachable over pins 6/14. */
        val reachable: Boolean get() = bus.contains("HS_PRIMARY")
    }

    private val byKey: Map<String, Parameter> = parameters.associateBy { it.key }

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
        return keys.mapNotNull { byKey[it] }
    }

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
