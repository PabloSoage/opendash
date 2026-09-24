package com.varuna.opendash.obd

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActuatorsTest {

    private val tsv = listOf(
        "# comment",
        "low\tEngine Control Module\t0x7E0\t0x1C\t80 vv 00 00 00\t0:100:0.3922:%\tEGR valve\tp\th\td",
        "harmless\tBody Control Module\t-\t-\t-\t-\tHorn\tp\th\td",
        "engine\tEngine Control Module\t0x7E0\t0x2A\t80 vv\t-\tNo range\tp\th\td",
        "nonsense\tX\t-\t-\t-\t-\tBad risk\tp\th\td",
        "low\ttoo\tfew",
    ).joinToString("\n")

    @Test
    fun rowsThatDoNotParseAreSkipped() {
        val all = Actuators.parse(tsv)
        assertEquals(listOf("EGR valve", "Horn", "No range"), all.map { it.name })
    }

    @Test
    fun theEgrIsBuiltExactlyAsCapturedFromGds2() {
        val egr = Actuators.parse(tsv).first()
        assertTrue(egr.commandable)
        // 50 % is 0x7F and 95 % is 0xF2: the two bytes seen leaving GDS2.
        assertArrayEquals(byteArrayOf(0xAE.toByte(), 0x1C, 0x80.toByte(), 0x7F, 0, 0, 0), egr.request(50.0))
        assertEquals(0xF2.toByte(), egr.request(95.0)!![3])
        // Clamped to the range, never past it.
        assertEquals(0xFF.toByte(), egr.request(500.0)!![3])
    }

    @Test
    fun withoutACaptureThereIsNothingToSend() {
        val horn = Actuators.parse(tsv)[1]
        assertFalse(horn.commandable)
        assertNull(horn.request())
    }

    @Test
    fun aValueSlotWithNoRangeIsNeverSent() {
        val broken = Actuators.parse(tsv)[2]
        assertFalse("would send a zero nobody chose", broken.commandable)
    }
}
