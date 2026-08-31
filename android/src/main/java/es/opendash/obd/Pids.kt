package es.opendash.obd

/**
 * The standard OBD-II parameters, mode 01. These are public — SAE J1979 — so
 * they ship with the app and work on any car, plugin or no plugin.
 *
 * Anything richer than this comes from a catalogue plugin.
 */
data class Pid(
    val id: Int,
    val bytes: Int,
    val name: String,
    val unit: String,
    val decode: (ByteArray) -> Double,
) {
    fun value(raw: ByteArray): Double? =
        if (raw.size < bytes) null else decode(raw)
}

private fun ByteArray.u(i: Int) = this[i].toInt() and 0xff

object Pids {

    val standard: List<Pid> = listOf(
        Pid(0x04, 1, "Calculated engine load", "%") { it.u(0) * 100.0 / 255 },
        Pid(0x05, 1, "Engine coolant temperature", "°C") { it.u(0) - 40.0 },
        Pid(0x0B, 1, "Intake manifold pressure", "kPa") { it.u(0).toDouble() },
        Pid(0x0C, 2, "Engine speed", "rpm") { (it.u(0) * 256 + it.u(1)) / 4.0 },
        Pid(0x0D, 1, "Vehicle speed", "km/h") { it.u(0).toDouble() },
        Pid(0x0F, 1, "Intake air temperature", "°C") { it.u(0) - 40.0 },
        Pid(0x10, 2, "Mass air flow", "g/s") { (it.u(0) * 256 + it.u(1)) / 100.0 },
        Pid(0x11, 1, "Throttle position", "%") { it.u(0) * 100.0 / 255 },
        Pid(0x1F, 2, "Time since engine start", "s") { (it.u(0) * 256 + it.u(1)).toDouble() },
        Pid(0x21, 2, "Distance with MIL on", "km") { (it.u(0) * 256 + it.u(1)).toDouble() },
        Pid(0x23, 2, "Fuel rail pressure", "kPa") { (it.u(0) * 256 + it.u(1)) * 10.0 },
        Pid(0x2C, 1, "Commanded EGR", "%") { it.u(0) * 100.0 / 255 },
        Pid(0x2D, 1, "EGR error", "%") { it.u(0) * 100.0 / 128 - 100 },
        Pid(0x2F, 1, "Fuel level", "%") { it.u(0) * 100.0 / 255 },
        Pid(0x31, 2, "Distance since codes cleared", "km") { (it.u(0) * 256 + it.u(1)).toDouble() },
        Pid(0x33, 1, "Barometric pressure", "kPa") { it.u(0).toDouble() },
        Pid(0x42, 2, "Control module voltage", "V") { (it.u(0) * 256 + it.u(1)) / 1000.0 },
        Pid(0x43, 2, "Absolute load", "%") { (it.u(0) * 256 + it.u(1)) * 100.0 / 255 },
        Pid(0x46, 1, "Ambient air temperature", "°C") { it.u(0) - 40.0 },
        Pid(0x49, 1, "Accelerator pedal D", "%") { it.u(0) * 100.0 / 255 },
        Pid(0x4A, 1, "Accelerator pedal E", "%") { it.u(0) * 100.0 / 255 },
        Pid(0x4C, 1, "Commanded throttle", "%") { it.u(0) * 100.0 / 255 },
        Pid(0x4D, 2, "Engine run time with MIL on", "min") { (it.u(0) * 256 + it.u(1)).toDouble() },
        Pid(0x5C, 1, "Engine oil temperature", "°C") { it.u(0) - 40.0 },
        Pid(0x62, 1, "Actual engine torque", "%") { it.u(0) - 125.0 },
    )

    val byId: Map<Int, Pid> = standard.associateBy { it.id }

    /**
     * Which PIDs this car answers. Mode 01 PID 00 returns a bitmask for
     * 0x01..0x20, PID 0x20 for the next block, and so on, so a supported set
     * is four short requests rather than 96 guesses.
     */
    fun supported(masks: Map<Int, ByteArray>): Set<Int> {
        val out = HashSet<Int>()
        for ((base, mask) in masks) {
            if (mask.size < 4) continue
            val bits = (mask.u(0).toLong() shl 24) or (mask.u(1).toLong() shl 16) or
                (mask.u(2).toLong() shl 8) or mask.u(3).toLong()
            for (i in 0 until 32) {
                if (bits and (1L shl (31 - i)) != 0L) out.add(base + i + 1)
            }
        }
        return out
    }
}
