package com.varuna.opendash.obd

/**
 * The identification block GM modules answer to service `0x1A`.
 *
 * ## Where the names come from
 *
 * Not from a specification and not from a guess. The factory capture asks 0x1A
 * with 58 different local identifiers and the modules answer all of them, but
 * the answers carry no labels. The catalogue extracted from GDS2 does: it lists
 * those same identifiers as parameters, with a name and a byte count, and the
 * check that the join is the right one is that the catalogue's length has to
 * match the number of bytes the car actually returned. 56 of the 58 match.
 *
 * Two independent structures fall out of it, and neither could arise by
 * chance:
 *
 *  - `0xC1`–`0xCC` are part numbers and `0xD1`–`0xDC` are their alpha codes,
 *    paired one for one down the whole range.
 *  - `0x42`–`0x49` come out as Calibration Part Number 12 through 19, eight in
 *    a numbered row.
 *
 * The values agree too. `0x99` returned `20 11 10 25` from the engine and
 * `20 10 07 20` from every other module — a BCD date, and the catalogue calls
 * it Date Programmed. `0x90` is the VIN. `0x6D` came back `0x51`, and the
 * catalogue calls it Engine Oil Life Remaining: 81 %.
 *
 * ## Checked against the tool itself
 *
 * A screenshot of the manufacturer's own Identification Information screen,
 * taken while the capture was running, settles fourteen of these by value
 * rather than by inference: the number the car returned is the number on the
 * screen, so there is no doubt which identifier a row belongs to. Thirteen
 * agreed. The fourteenth did not: `0x92` returns `DENSO0100`, and the tool
 * calls that **System Identification**, not the supplier. `71-pantallas-gds2.mjs`
 * in the analysis repository keeps that comparison honest.
 *
 * ## What is deliberately missing
 *
 * The identifiers whose only catalogue match was a mode 01 PID that happens to
 * share the number — `0x22`, `0x41`, `0x3D` and a few more. A local identifier
 * and a mode 01 PID are different namespaces using the same integers, so those
 * matches are collisions, not meanings. They are read and shown raw rather than
 * labelled with something plausible and wrong.
 */
object Identifiers {

    /** How to make the bytes readable once they arrive. */
    enum class Shape {
        /** Printable ASCII: a VIN, a part number, a code. */
        TEXT,

        /** Four BCD bytes, `yyyymmdd`. */
        DATE,

        /** A 32-bit number, which is how GM writes a part number. */
        NUMBER,

        /** Anything else. Hex, and let the reader decide. */
        RAW,
    }

    class Entry(val id: Int, val label: String, val shape: Shape)

    /**
     * Read in this order. It is roughly the order GDS2 asks in, which puts the
     * things a person recognises first.
     */
    val known: List<Entry> = listOf(
        Entry(0x90, "Vehicle identification number", Shape.TEXT),
        Entry(0x92, "System identification", Shape.TEXT),
        Entry(0x97, "System name or engine type", Shape.TEXT),
        Entry(0x98, "Subscriber ID", Shape.TEXT),
        Entry(0x99, "Date programmed", Shape.DATE),
        Entry(0x9A, "Diagnostic data identifier", Shape.RAW),
        Entry(0x9B, "XML configuration compatibility identifier", Shape.RAW),
        Entry(0x9C, "XML data file part number", Shape.NUMBER),
        Entry(0x9D, "XML data file alpha code", Shape.TEXT),
        Entry(0x9F, "2nd previous subscriber ID", Shape.TEXT),
        Entry(0xA0, "Manufacturer enable counter", Shape.RAW),
        Entry(0xB0, "Module diagnostic address", Shape.RAW),
        Entry(0xB4, "Manufacturer's traceability number", Shape.TEXT),
        Entry(0xB5, "Broadcast code", Shape.TEXT),
        Entry(0xC0, "Boot software part number", Shape.NUMBER),
        Entry(0xC1, "Calibration part number 1", Shape.NUMBER),
        Entry(0xC2, "Software module 2 identifier", Shape.NUMBER),
        Entry(0xC3, "Software module 3 identifier", Shape.NUMBER),
        Entry(0xC4, "Calibration part number", Shape.NUMBER),
        Entry(0xC5, "Software module 5 identifier", Shape.NUMBER),
        Entry(0xC6, "Software module 6 identifier", Shape.NUMBER),
        Entry(0xC7, "Software module 7 identifier", Shape.NUMBER),
        Entry(0xC8, "Software module 8 identifier", Shape.NUMBER),
        Entry(0xC9, "Software module 9 identifier", Shape.NUMBER),
        Entry(0xCA, "Software module 10 identifier", Shape.NUMBER),
        Entry(0xCB, "End model part number", Shape.NUMBER),
        Entry(0xCC, "Base model part number", Shape.NUMBER),
        Entry(0xD0, "Software part number alpha code", Shape.TEXT),
        Entry(0xD1, "Software module 1 alpha code", Shape.TEXT),
        Entry(0xD2, "Software module 2 alpha code", Shape.TEXT),
        Entry(0xD3, "Software module 3 alpha code", Shape.TEXT),
        Entry(0xD4, "Software module 4 alpha code", Shape.TEXT),
        Entry(0xD5, "Software module 5 alpha code", Shape.TEXT),
        Entry(0xD6, "Software module 6 alpha code", Shape.TEXT),
        Entry(0xD7, "Software module 7 alpha code", Shape.TEXT),
        Entry(0xD8, "Software module 8 alpha code", Shape.TEXT),
        Entry(0xD9, "Software module 9 alpha code", Shape.TEXT),
        Entry(0xDA, "Supplier identification", Shape.TEXT),
        Entry(0xDB, "End model part number alpha code", Shape.TEXT),
        Entry(0xDC, "Base model part number alpha code", Shape.TEXT),
        Entry(0xDE, "GMLAN identification data, bus 1 type", Shape.RAW),
        Entry(0x42, "Calibration part number 12", Shape.NUMBER),
        Entry(0x43, "Calibration part number 13", Shape.NUMBER),
        Entry(0x44, "Calibration part number 14", Shape.NUMBER),
        Entry(0x45, "Calibration part number 15", Shape.NUMBER),
        Entry(0x46, "Calibration part number 16", Shape.NUMBER),
        Entry(0x47, "Calibration part number 17", Shape.NUMBER),
        Entry(0x48, "Calibration part number 18", Shape.NUMBER),
        Entry(0x49, "Calibration part number 19", Shape.NUMBER),
        Entry(0x6D, "Engine oil life remaining", Shape.RAW),
    )

    /**
     * The rest of what the capture shows being asked. No label, because the
     * only catalogue entries with those numbers are mode 01 PIDs that share
     * them by accident. Asked anyway: whatever comes back is a measurement, and
     * a measurement with an honest "unknown" beside it beats a plausible name.
     */
    val unlabelled: List<Int> = listOf(0x22, 0x2F, 0x30, 0x3D, 0x41, 0x5E, 0x75, 0xDF)

    /** Everything worth asking a module, labelled first. */
    val all: List<Entry> =
        known + unlabelled.map { Entry(it, "", Shape.RAW) }

    /** Turn an answer into something readable, or null when it does not fit. */
    fun render(bytes: ByteArray, shape: Shape): String? = when (shape) {
        // A Byte in Kotlin is signed, so every one of these masks to 0xff
        // first. Without it 0x99 is -103, its high nibble comes out negative,
        // and a date that is plainly a date fails the test.
        Shape.TEXT -> bytes
            .takeIf { it.isNotEmpty() && it.all { b -> (b.toInt() and 0xff) in 32..126 } }
            ?.let { String(it, Charsets.US_ASCII).trim() }
            ?.takeIf { it.isNotEmpty() }

        Shape.DATE -> bytes
            .takeIf { b ->
                b.size == 4 && b.all {
                    val v = it.toInt() and 0xff
                    (v shr 4) <= 9 && (v and 0xf) <= 9
                }
            }
            ?.let { b ->
                val d = b.joinToString("") {
                    val v = it.toInt() and 0xff
                    "" + (v shr 4) + (v and 0xf)
                }
                d.substring(0, 4) + "-" + d.substring(4, 6) + "-" + d.substring(6, 8)
            }

        Shape.NUMBER -> bytes
            .takeIf { it.size == 4 }
            ?.let { b ->
                var v = 0L
                for (x in b) v = (v shl 8) or (x.toLong() and 0xff)
                v.toString()
            }

        Shape.RAW -> null
    }

    /** Always available, whatever the shape says. */
    fun hex(bytes: ByteArray): String = bytes.joinToString(" ") {
        (it.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}
