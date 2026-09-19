package com.varuna.opendash.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a known-vehicle file.
 *
 * This is what lets the live screen work with no car in front of it, and it is
 * read from a file somebody else wrote — a catalogue publishes one, a phone
 * exports one. So the failure that matters is not a crash, it is a record that
 * parses into something subtly wrong: a width silently missing turns a
 * two-byte scale loose on a one-byte answer, and that reads as a pedal at
 * 798 % rather than as an error.
 */
class CarProfileTest {

    private val real = """
        # opendash known vehicles
        #
        # A header, with a blank line after it.

        vehicle${'\t'}Astra J 1.7 CDTI A17DTJ
        module${'\t'}2016
        taken${'\t'}1789674960000
        supported${'\t'}1,4,5,11,12
        asked${'\t'}4,5,11,12,13,300,400
        answered${'\t'}4:1,5:1,11:1,12:2,300:4
    """.trimIndent()

    @Test
    fun `one vehicle, every field`() {
        val records = CarProfile.parse(real)
        assertEquals(1, records.size)
        val r = records[0]
        assertEquals("Astra J 1.7 CDTI A17DTJ", r.label)
        assertEquals(0x7E0, r.module)
        assertEquals(1789674960000L, r.taken)
        assertEquals(setOf(1, 4, 5, 11, 12), r.supported)
        assertEquals(setOf(4, 5, 11, 12, 13, 300, 400), r.asked)
        assertEquals(mapOf(4 to 1, 5 to 1, 11 to 1, 12 to 2, 300 to 4), r.answered)
    }

    /** The width is the whole reason the file carries more than a list of ids. */
    @Test
    fun `widths survive`() {
        val r = CarProfile.parse(real).single()
        assertEquals(2, r.answered[12])
        assertEquals(4, r.answered[300])
        assertEquals(1, r.answered[4])
    }

    /** A catalogue may publish one record per engine. */
    @Test
    fun `several vehicles in one file`() {
        val records = CarProfile.parse(
            real + "\n\nvehicle\tSomething else\nmodule\t2021\n" +
                "taken\t1\nsupported\t\nasked\t9\nanswered\t9:1\n",
        )
        assertEquals(2, records.size)
        assertEquals("Astra J 1.7 CDTI A17DTJ", records[0].label)
        assertEquals(0x7E0, records[0].module)
        assertEquals("Something else", records[1].label)
        assertEquals(0x7E5, records[1].module)
        assertEquals(mapOf(9 to 1), records[1].answered)
        // The first record must not have leaked into the second.
        assertTrue(records[1].supported.isEmpty())
        assertEquals(setOf(9), records[1].asked)
    }

    /** `vin` is what the first version of this wrote, and files exist. */
    @Test
    fun `the older key still reads`() {
        val records = CarProfile.parse("vin\tkept\nmodule\t2016\nanswered\t4:1\n")
        assertEquals("kept", records.single().label)
    }

    /**
     * A record with nothing in it is not a vehicle. Adopting one would set the
     * remembered car to something that then lists no parameters at all, which
     * on screen is indistinguishable from a catalogue that failed to load.
     */
    @Test
    fun `records with no answers are not vehicles`() {
        assertTrue(CarProfile.parse("vehicle\tempty\nmodule\t2016\nanswered\t\n").isEmpty())
        assertTrue(CarProfile.parse("vehicle\tno module\nanswered\t4:1\n").isEmpty())
        assertTrue(CarProfile.parse("# only a comment\n").isEmpty())
        assertTrue(CarProfile.parse("").isEmpty())
    }

    /** Junk between records is skipped, not fatal. */
    @Test
    fun `lines that are not fields are ignored`() {
        val r = CarProfile.parse(
            "vehicle\tok\nnot a field line\nmodule\t2016\n" +
                "answered\t4:1,rubbish,7:,:3,9:2\n",
        ).single()
        // "7:" has no width and reads as zero; ":3" has no id and is dropped.
        assertEquals(mapOf(4 to 1, 7 to 0, 9 to 2), r.answered)
    }
}
