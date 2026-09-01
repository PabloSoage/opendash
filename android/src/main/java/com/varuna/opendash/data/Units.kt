package com.varuna.opendash.data

/**
 * The unit for a parameter whose file does not carry one.
 *
 * A `.sm2` stores thirty names and not one unit — checked on a real recording:
 * the header holds exactly as many text records as there are channels, and each
 * one is a name. So a chart drawn from a `.sm2` has a number and nothing to say
 * what it is a number of.
 *
 * These names come from the SAE J1979 parameter list, which is public and fixed,
 * so the unit follows from the name. The rules below are only the ones that
 * follow unambiguously, matched on the whole name rather than on a keyword: a
 * unit invented for a diagnostic reading is worse than no unit at all, and
 * anything not listed here gets nothing rather than a guess.
 *
 * A recording this app writes carries its unit in the file and never comes
 * through here.
 */
object Units {

    fun forParameter(name: String): String = EXACT[normalise(name)] ?: ""

    private fun normalise(name: String) =
        name.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    /**
     * Keyed on the normalised name. Every entry is a J1979 parameter whose
     * unit is fixed by the standard; the two time ones are minutes and not
     * seconds, which is the sort of thing a keyword rule would get wrong.
     */
    private val EXACT: Map<String, String> = mapOf(
        "calculated load value" to "%",
        "engine coolant temperature" to "°C",
        "intake manifold absolute pressure" to "kPa",
        "engine rpm" to "rpm",
        "vehicle speed sensor" to "km/h",
        "vehicle speed" to "km/h",
        "intake air temperature" to "°C",
        "air flow rate from maf sensor" to "g/s",
        "time since engine start" to "s",
        "distance while mil is activated" to "km",
        "distance travelled while mil is activated" to "km",
        "fuel rail pressure diesel gdi" to "kPa",
        "commanded egr" to "%",
        "egr control error" to "%",
        "fuel level input" to "%",
        "warm ups since dtc clear" to "",
        "distance since dtc clear" to "km",
        "barometric pressure" to "kPa",
        "catalyst temperature b1s1" to "°C",
        "catalyst temperature b1s2" to "°C",
        "catalyst temperature b2s1" to "°C",
        "catalyst temperature b2s2" to "°C",
        "control module voltage" to "V",
        "ambient air temperature" to "°C",
        "accelerator pedal position d" to "%",
        "accelerator pedal position e" to "%",
        "accelerator pedal position f" to "%",
        "commanded throttle actuator control" to "%",
        "throttle position" to "%",
        "absolute throttle position b" to "%",
        "engine run time while mil activated" to "min",
        "time since dtc cleared" to "min",
        "wr oxygen sensor voltage lc1" to "V",
        "wr oxygen sensor current b1s1" to "mA",
        "engine fuel rate" to "L/h",
        "absolute load value" to "%",
        "engine oil temperature" to "°C",
        "fuel injection timing" to "°",
        "hybrid battery pack remaining life" to "%",
        // Deliberately absent: "equivalence ratio (lambda)" and "commanded
        // equivalence ratio", which are dimensionless, and anything whose
        // name did not appear in a recording we have actually read.
    )
}
