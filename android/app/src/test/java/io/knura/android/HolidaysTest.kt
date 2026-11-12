package io.knura.android

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HolidaysTest {
    private fun on(date: String) = Holidays.on(LocalDate.parse(date))?.holiday

    @Test
    fun windowsMatchTheDashboard() {
        assertNull(on("2026-02-06"))
        assertEquals(Holidays.Holiday.VALENTINE, on("2026-02-07"))
        assertEquals(Holidays.Holiday.VALENTINE, on("2026-02-14"))
        assertEquals(Holidays.Holiday.JULY4, on("2026-06-28"))
        assertEquals(Holidays.Holiday.JULY4, on("2026-07-05"))
        assertEquals(Holidays.Holiday.HALLOWEEN, on("2026-10-17"))
        assertEquals(Holidays.Holiday.HALLOWEEN, on("2026-11-01"))
        assertNull(on("2026-11-02"))
        assertEquals(Holidays.Holiday.CHRISTMAS, on("2026-12-01"))
        assertEquals(Holidays.Holiday.CHRISTMAS, on("2026-12-28"))
        assertEquals(Holidays.Holiday.NEW_YEAR, on("2026-12-29"))
        assertEquals(Holidays.Holiday.NEW_YEAR, on("2027-01-02"))
        assertNull(on("2027-01-03"))
    }

    @Test
    fun thanksgivingFollowsTheFourthThursday() {
        // 2026: Thursday Nov 26, so Nov 16 to Nov 28. 2027: Nov 25, so Nov 15 to Nov 27.
        assertNull(on("2026-11-15"))
        assertEquals(Holidays.Holiday.THANKSGIVING, on("2026-11-16"))
        assertEquals(Holidays.Holiday.THANKSGIVING, on("2026-11-28"))
        assertNull(on("2026-11-29"))
        assertEquals(Holidays.Holiday.THANKSGIVING, on("2027-11-15"))
        assertNull(on("2027-11-28"))
    }

    @Test
    fun noTwoHolidaysOverlap() {
        var d = LocalDate.of(2025, 1, 1)
        while (d.isBefore(LocalDate.of(2036, 1, 1))) {
            val hits = Holidays.Holiday.values().count { h ->
                listOf(d.year - 1, d.year).any { y -> val (a, b) = Holidays.window(h, y); !d.isBefore(a) && !d.isAfter(b) }
            }
            assertTrue("$d is in $hits holidays", hits <= 1)
            d = d.plusDays(1)
        }
    }

    @Test
    fun statusSaysNowOrNext() {
        assertEquals("Next: Thanksgiving from Nov 16", Holidays.status(LocalDate.parse("2026-11-11")))
        assertEquals("Christmas until Dec 28", Holidays.status(LocalDate.parse("2026-12-05")))
        assertEquals("Next: Valentine’s Day from Feb 7", Holidays.status(LocalDate.parse("2027-01-03")))
    }

    @Test
    fun aHolidayWinsOverTheEverydayLook() {
        val dec = LocalDate.parse("2026-12-05")
        val a = Themes.resolve("mono", "blue", byDate = true, today = dec)
        assertEquals(Holidays.Holiday.CHRISTMAS, a.holiday)
        assertTrue(a.auto)
        assertEquals("default", a.look)
        val off = Themes.resolve("mono", "blue", byDate = false, today = dec)
        assertNull(off.holiday)
        assertEquals("mono", off.look)
        val kept = Themes.resolve("halloween", null, byDate = false, today = LocalDate.parse("2026-04-01"))
        assertEquals(Holidays.Holiday.HALLOWEEN, kept.holiday)
        assertFalse(kept.auto)
        assertEquals("default", Themes.resolve("nonsense", "green", byDate = false, today = dec).look)
        assertEquals("orange", Themes.resolve("custom", "green", byDate = false, today = dec).accent)
    }
}
