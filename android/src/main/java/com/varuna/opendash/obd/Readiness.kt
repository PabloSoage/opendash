package com.varuna.opendash.obd

/**
 * Readiness monitors, from mode 01 PID 01.
 *
 * Four bytes: the MIL and the fault count, then which self-tests this car runs
 * and which of them have finished since the codes were cleared. It is the
 * first thing anyone checks before an inspection, and the reason a car that
 * "has no faults" can still fail one.
 */
class Readiness(raw: ByteArray) {

    val milOn: Boolean = raw.isNotEmpty() && (raw[0].toInt() and 0x80) != 0
    val faultCount: Int = if (raw.isNotEmpty()) raw[0].toInt() and 0x7f else 0
    val compressionIgnition: Boolean = raw.size > 1 && (raw[1].toInt() and 0x08) != 0

    /** name to (supported, complete) */
    val monitors: List<Triple<String, Boolean, Boolean>> = buildList {
        if (raw.size < 4) return@buildList
        val b1 = raw[1].toInt() and 0xff
        val b2 = raw[2].toInt() and 0xff
        val b3 = raw[3].toInt() and 0xff

        // The three that every engine has, in byte 1: bit set means supported,
        // and the bit four places up means NOT complete.
        add(Triple("Misfire", b1 and 0x01 != 0, b1 and 0x10 == 0))
        add(Triple("Fuel system", b1 and 0x02 != 0, b1 and 0x20 == 0))
        add(Triple("Components", b1 and 0x04 != 0, b1 and 0x40 == 0))

        // The rest depend on the engine type, which byte 1 bit 3 selects.
        val names = if (compressionIgnition) DIESEL else PETROL
        for (i in names.indices) {
            add(Triple(names[i], b2 and (1 shl i) != 0, b3 and (1 shl i) == 0))
        }
    }

    val allComplete: Boolean get() = monitors.none { it.second && !it.third }

    private companion object {
        val PETROL = listOf(
            "Catalyst", "Heated catalyst", "Evaporative system", "Secondary air",
            "A/C refrigerant", "Oxygen sensor", "Oxygen sensor heater", "EGR system",
        )
        val DIESEL = listOf(
            "NMHC catalyst", "NOx aftertreatment", "Boost pressure", "reserved",
            "Exhaust gas sensor", "PM filter", "EGR/VVT system", "reserved",
        )
    }
}
