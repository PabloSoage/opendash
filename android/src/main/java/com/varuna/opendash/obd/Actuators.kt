package com.varuna.opendash.obd

/**
 * What a car's modules can be commanded to do, and how careful to be about it.
 *
 * The list comes from the catalogue, in `actuators.tsv`, rather than from this
 * code, for one practical reason: the command for almost every output is not
 * known yet. A GMLAN output is driven with `$AE` and a CPID the module defines,
 * and the only way found to learn a CPID is to watch the factory tool send it
 * (see [Actuation]). Each one captured is a row filled in, and a row filled in
 * should not need a new release of the app.
 *
 * A row with no CPID is still listed, disabled, with what it is and how
 * dangerous it would be. That is the honest half: knowing that the window is
 * on the list and waiting for a capture is different from not knowing it
 * exists.
 */
object Actuators {

    /**
     * How much can go wrong, and therefore how much has to be done before it
     * can be sent. Each level asks for everything the one before it did.
     */
    enum class Risk(val code: String) {
        /** Seen or heard, undone by itself, no effect on the engine: a lamp, the horn. */
        HARMLESS("harmless"),

        /** Moves something real, harmless parked: a fan, a window, the EGR at idle. */
        LOW("low"),

        /** Changes how the engine runs. Parked, in neutral, warm, and watching it. */
        ENGINE("engine"),

        /** Changes a value the module keeps. Done once, with its procedure, when needed. */
        LEARN("learn"),

        /** Can damage the car or stop it starting. Not offered unless insisted on. */
        FORBIDDEN("forbidden"),
        ;

        companion object {
            fun of(code: String): Risk? = entries.firstOrNull { it.code == code.trim().lowercase() }
        }
    }

    /**
     * A commandable value: a slider from [min] to [max] in [unit], sent as
     * `round(value / scale)`.
     */
    class Range(val min: Double, val max: Double, val scale: Double, val unit: String)

    class Actuator(
        val risk: Risk,
        val module: String,
        /** Where the command goes, or null when the module's address is not known. */
        val address: Int?,
        /** The CPID, or null when it has not been captured yet. */
        val cpid: Int?,
        /**
         * The control bytes after the CPID, with `vv` where the value goes.
         * `80 vv 00 00 00` for the EGR: 0x80 is "commanded", then the value.
         */
        val control: String?,
        val range: Range?,
        val name: String,
        /** What it is, in one line. */
        val purpose: String,
        /** When it is worth doing and how, including what to watch. */
        val how: String,
        /** What can go wrong. Shown before anything is sent. */
        val danger: String,
    ) {
        /** Whether this app knows enough to send it. */
        val commandable: Boolean
            get() = address != null && cpid != null && control != null

        /** The `$AE` request for [value], or for the control as written when there is no range. */
        fun request(value: Double? = null): ByteArray? {
            if (!commandable) return null
            val raw = if (range != null && value != null) {
                Math.round(value.coerceIn(range.min, range.max) / range.scale).toInt().coerceIn(0, 255)
            } else {
                0
            }
            val bytes = control!!.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map {
                if (it.equals("vv", ignoreCase = true)) raw.toByte() else it.toInt(16).toByte()
            }
            return Actuation.request(cpid!!, bytes.toByteArray())
        }
    }

    /**
     * `actuators.tsv`: one row per output, tab-separated.
     *
     * ```
     * risk  module  address  cpid  control  range  name  purpose  how  danger
     * ```
     *
     * `-` means not known. `range` is `min:max:scale:unit`. Lines starting with
     * `#` and rows that do not parse are skipped: a catalogue with a mistake in
     * one row still offers the others, and a row that cannot be read is never
     * sent anything.
     */
    fun parse(text: String): List<Actuator> =
        text.lineSequence().mapNotNull { line ->
            if (line.isBlank() || line.startsWith("#")) return@mapNotNull null
            val f = line.split('\t')
            if (f.size < 10) return@mapNotNull null
            val risk = Risk.of(f[0]) ?: return@mapNotNull null
            fun known(s: String) = s.trim().takeUnless { it.isEmpty() || it == "-" }
            fun hex(s: String) = known(s)?.removePrefix("0x")?.toIntOrNull(16)
            val range = known(f[5])?.split(':')?.let { r ->
                if (r.size != 4) return@let null
                val lo = r[0].toDoubleOrNull() ?: return@let null
                val hi = r[1].toDoubleOrNull() ?: return@let null
                val sc = r[2].toDoubleOrNull()?.takeIf { it > 0 } ?: return@let null
                Range(lo, hi, sc, r[3])
            }
            val control = known(f[4])
            // A control written with a value slot and no range to fill it is a
            // row that would send zero without saying so. Not commandable.
            val usable = control != null && (range != null || !control.contains("vv", ignoreCase = true))
            Actuator(
                risk = risk,
                module = f[1].trim(),
                address = hex(f[2]),
                cpid = hex(f[3]),
                control = if (usable) control else null,
                range = range,
                name = f[6].trim(),
                purpose = f[7].trim(),
                how = f[8].trim(),
                danger = f[9].trim(),
            )
        }.toList()
}
