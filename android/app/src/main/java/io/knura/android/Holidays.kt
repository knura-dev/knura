package io.knura.android

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * The holiday themes and when each one is on: the same rules as the self-hosted dashboard
 * (knurd's dashboard/themes.js). Every window is inclusive and no two overlap.
 */
object Holidays {
    enum class Holiday(val id: String, val label: String, val greeting: String) {
        VALENTINE("valentine", "Valentine’s Day", "Happy Valentine’s Day"),
        JULY4("july4", "4th of July", "Happy 4th of July"),
        HALLOWEEN("halloween", "Halloween", "Happy Halloween"),
        THANKSGIVING("thanksgiving", "Thanksgiving", "Happy Thanksgiving"),
        CHRISTMAS("christmas", "Christmas", "Happy holidays"),
        NEW_YEAR("newyear", "New Year’s", "Happy New Year");

        companion object {
            fun byId(id: String?): Holiday? = values().firstOrNull { it.id == id }
        }
    }

    /** The first and last day of a holiday's window that starts in [year]. */
    fun window(h: Holiday, year: Int): Pair<LocalDate, LocalDate> = when (h) {
        Holiday.VALENTINE -> LocalDate.of(year, 2, 7) to LocalDate.of(year, 2, 14)
        Holiday.JULY4 -> LocalDate.of(year, 6, 28) to LocalDate.of(year, 7, 5)
        Holiday.HALLOWEEN -> LocalDate.of(year, 10, 17) to LocalDate.of(year, 11, 1)
        Holiday.THANKSGIVING -> {
            // US Thanksgiving, the fourth Thursday of November: 10 days before to 2 days after.
            val t = LocalDate.of(year, 11, 1).with(TemporalAdjusters.dayOfWeekInMonth(4, DayOfWeek.THURSDAY))
            t.minusDays(10) to t.plusDays(2)
        }
        Holiday.CHRISTMAS -> LocalDate.of(year, 12, 1) to LocalDate.of(year, 12, 28)
        Holiday.NEW_YEAR -> LocalDate.of(year, 12, 29) to LocalDate.of(year + 1, 1, 2)
    }

    data class On(val holiday: Holiday, val until: LocalDate)

    /** The holiday on [date], with the last day of its window, or null. */
    fun on(date: LocalDate): On? {
        for (h in Holiday.values()) {
            for (year in listOf(date.year - 1, date.year)) {
                val (a, b) = window(h, year)
                if (!date.isBefore(a) && !date.isAfter(b)) return On(h, b)
            }
        }
        return null
    }

    /** The next holiday that starts after [date], with its first day. */
    fun next(date: LocalDate): Pair<Holiday, LocalDate>? {
        for (i in 1..370L) {
            val d = date.plusDays(i)
            val today = on(d) ?: continue
            if (on(d.minusDays(1))?.holiday != today.holiday) return today.holiday to d
        }
        return null
    }

    private val DAY = DateTimeFormatter.ofPattern("MMM d", Locale.US)

    /** "Christmas until Dec 28", or "Next: Thanksgiving from Nov 16". */
    fun status(date: LocalDate): String {
        on(date)?.let { return "${it.holiday.label} until ${DAY.format(it.until)}" }
        val n = next(date) ?: return ""
        return "Next: ${n.first.label} from ${DAY.format(n.second)}"
    }

    /** "Dec 1 to Dec 28" for this year's window. */
    fun dates(h: Holiday, date: LocalDate): String {
        val (a, b) = window(h, date.year)
        return "${DAY.format(a)} to ${DAY.format(b)}"
    }
}
