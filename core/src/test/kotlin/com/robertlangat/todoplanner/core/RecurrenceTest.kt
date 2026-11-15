package com.robertlangat.todoplanner.core

import com.robertlangat.todoplanner.core.recurrence.Frequency
import com.robertlangat.todoplanner.core.recurrence.RecurrenceEnd
import com.robertlangat.todoplanner.core.recurrence.RecurrenceEngine
import com.robertlangat.todoplanner.core.recurrence.RecurrenceRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class RecurrenceTest {
    private fun d(s: String) = LocalDate.parse(s)

    @Test fun monthlyOn31stClampsToMonthEndWithoutDrifting() {
        val rule = RecurrenceRule(Frequency.MONTHLY)
        val got = RecurrenceEngine.occurrencesBetween(d("2027-01-31"), rule, d("2027-01-01"), d("2027-06-30"))
        assertEquals(
            listOf("2027-01-31", "2027-02-28", "2027-03-31", "2027-04-30", "2027-05-31", "2027-06-30").map(::d),
            got,
        )
    }

    @Test fun monthlyOn31stInLeapYearUsesFeb29() {
        val got = RecurrenceEngine.occurrencesBetween(d("2028-01-31"), RecurrenceRule(Frequency.MONTHLY), d("2028-02-01"), d("2028-02-29"))
        assertEquals(listOf(d("2028-02-29")), got)
    }

    @Test fun everyTwoMonths() {
        val got = RecurrenceEngine.series(d("2026-11-30"), RecurrenceRule(Frequency.MONTHLY, interval = 2)).take(3).toList()
        assertEquals(listOf("2026-11-30", "2027-01-30", "2027-03-30").map(::d), got)
    }

    @Test fun yearlyBirthdayOnFeb29FallsOnFeb28InCommonYears() {
        val got = RecurrenceEngine.series(d("2024-02-29"), RecurrenceRule(Frequency.YEARLY)).take(5).toList()
        assertEquals(listOf("2024-02-29", "2025-02-28", "2026-02-28", "2027-02-28", "2028-02-29").map(::d), got)
    }

    @Test fun yearlyAnniversary() {
        val got = RecurrenceEngine.occurrencesBetween(d("2020-06-15"), RecurrenceRule(Frequency.YEARLY), d("2026-01-01"), d("2027-12-31"))
        assertEquals(listOf(d("2026-06-15"), d("2027-06-15")), got)
    }

    @Test fun weekdaysSkipWeekend() {
        // 2026-11-13 is a Friday
        val got = RecurrenceEngine.series(d("2026-11-13"), RecurrenceRule(Frequency.WEEKDAYS)).take(3).toList()
        assertEquals(listOf("2026-11-13", "2026-11-16", "2026-11-17").map(::d), got)
    }

    @Test fun customEveryTwoWeeksOnMonWed() {
        val rule = RecurrenceRule(Frequency.WEEKLY, interval = 2, byDays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY))
        // start on Wednesday 2026-11-18; the Monday before it is not part of the series
        val got = RecurrenceEngine.series(d("2026-11-18"), rule).take(4).toList()
        assertEquals(listOf("2026-11-18", "2026-11-30", "2026-12-02", "2026-12-14").map(::d), got)
    }

    @Test fun countIncludesExcludedDates() {
        val rule = RecurrenceRule(Frequency.DAILY, end = RecurrenceEnd.Count(3), exDates = setOf(d("2026-11-16")))
        val got = RecurrenceEngine.occurrencesBetween(d("2026-11-15"), rule, d("2026-11-01"), d("2026-12-31"))
        assertEquals(listOf(d("2026-11-15"), d("2026-11-17")), got)
    }

    @Test fun untilIsInclusive() {
        val rule = RecurrenceRule(Frequency.DAILY, end = RecurrenceEnd.Until(d("2026-11-17")))
        assertEquals(3, RecurrenceEngine.occurrencesBetween(d("2026-11-15"), rule, d("2026-11-01"), d("2027-01-01")).size)
    }

    @Test fun formatParseRoundTrip() {
        val rule = RecurrenceRule(
            Frequency.WEEKLY, 3, setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY),
            RecurrenceEnd.Count(12), setOf(d("2026-12-25")),
        )
        assertEquals(rule, RecurrenceRule.parse(rule.format()))
        assertEquals("FREQ=WEEKLY;INTERVAL=3;BYDAY=MO,FR;COUNT=12;EXDATE=2026-12-25", rule.format())
        assertNull(RecurrenceRule.parse("garbage"))
        assertNull(RecurrenceRule.parse(""))
    }

    @Test fun splitThisAndFutureKeepsTotalCount() {
        val start = d("2026-11-01")
        val rule = RecurrenceRule(Frequency.DAILY, end = RecurrenceEnd.Count(10))
        val (old, new) = RecurrenceEngine.split(start, rule, d("2026-11-04"))
        assertEquals(RecurrenceEnd.Count(3), old!!.end)
        assertEquals(RecurrenceEnd.Count(7), new.end)
        val oldDates = RecurrenceEngine.series(start, old).toList()
        val newDates = RecurrenceEngine.series(d("2026-11-04"), new).toList()
        assertEquals(10, (oldDates + newDates).distinct().size)
        assertTrue(oldDates.none { it in newDates })
    }

    @Test fun splitWithoutEndUsesUntil() {
        val (old, _) = RecurrenceEngine.split(d("2026-11-01"), RecurrenceRule(Frequency.WEEKLY), d("2026-11-22"))
        assertEquals(RecurrenceEnd.Until(d("2026-11-21")), old!!.end)
    }

    @Test fun splitAtFirstOccurrenceReplacesWholeSeries() {
        val (old, _) = RecurrenceEngine.split(d("2026-11-01"), RecurrenceRule(Frequency.DAILY), d("2026-11-01"))
        assertNull(old)
    }

    @Test fun isOccurrenceRespectsExDates() {
        val rule = RecurrenceRule(Frequency.DAILY).withExDate(d("2026-11-20"))
        assertTrue(RecurrenceEngine.isOccurrence(d("2026-11-15"), rule, d("2026-11-19")))
        assertFalse(RecurrenceEngine.isOccurrence(d("2026-11-15"), rule, d("2026-11-20")))
        assertEquals(d("2026-11-21"), RecurrenceEngine.nextOnOrAfter(d("2026-11-15"), rule, d("2026-11-20")))
    }
}
