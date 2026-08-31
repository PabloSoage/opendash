package es.opendash.data

import java.io.File

/**
 * A parameter catalogue, as shipped in a plugin.
 *
 * The tab-separated files come out of a GDS2 installation. Names and units are
 * localised; keys, pids, formulas and ranges are the same in every language, so
 * a plugin can carry one or several without the numbers ever diverging.
 */
class Catalogue(
    val brand: String,
    val parameters: List<Parameter>,
    /** variant name to the keys it exposes */
    val variants: Map<String, List<String>>,
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

    class Module(val name: String, val address: String, val bus: String, val variant: String) {
        /** Only what sits on the high-speed pair is reachable over pins 6/14. */
        val reachable: Boolean get() = bus.contains("HS_PRIMARY")
    }

    fun forVariant(variant: String): List<Parameter> {
        val keys = variants[variant]?.toSet() ?: return emptyList()
        return parameters.filter { it.key in keys }
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
            val variants = HashMap<String, MutableList<String>>()
            if (variantsFile.exists()) {
                variantsFile.useLines { lines ->
                    lines.drop(1).forEach { line ->
                        val c = line.split('\t')
                        if (c.size >= 3) variants.getOrPut(c[0]) { ArrayList() }.add(c[2])
                    }
                }
            }

            val modules = File(directory, "modules.tsv").let { f ->
                if (!f.exists()) emptyList() else f.useLines { lines ->
                    lines.drop(1).mapNotNull { line ->
                        val c = line.split('\t')
                        if (c.size >= 4) Module(c[0], c[1], c[2], c[3]) else null
                    }.toList()
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
