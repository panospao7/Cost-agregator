package com.yourname.expensetracker.domain.core.time

import com.yourname.expensetracker.domain.util.TimePeriodUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Contract tests verifying that [PeriodKind.toPeriodRange] behaves consistently
 * with [TimePeriodUtils] and satisfies the half-open contract for all period kinds.
 */
class PeriodKindContractTest {

    private val utc = ZoneId.of("UTC")

    @Test(expected = IllegalStateException::class)
    fun `customPeriodZonedWithoutBoundsThrows`() {
        PeriodKind.CUSTOM.toPeriodRangeZoned(nowMillis = 1234567890L, zoneId = utc)
    }

    @Test
    fun `last7Days_matchesTimePeriodUtilsGetLastNCalendarDaysRange`() {
        val ld = LocalDate.of(2026, 6, 15)
        val now = ld.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 3600_000L

        val range = PeriodKind.LAST_7_DAYS.toPeriodRange(now)
        val expected = TimePeriodUtils.getLastNCalendarDaysRange(now, 7)

        assertEquals("start should match TimePeriodUtils", expected.first, range.startInclusiveMillis)
        assertEquals("end should match TimePeriodUtils", expected.second, range.endExclusiveMillis)
    }

    @Test
    fun `last30Days_matchesTimePeriodUtilsGetLastNCalendarDaysRange`() {
        val ld = LocalDate.of(2026, 6, 15)
        val now = ld.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 3600_000L

        val range = PeriodKind.LAST_30_DAYS.toPeriodRange(now)
        val expected = TimePeriodUtils.getLastNCalendarDaysRange(now, 30)

        assertEquals("start should match TimePeriodUtils", expected.first, range.startInclusiveMillis)
        assertEquals("end should match TimePeriodUtils", expected.second, range.endExclusiveMillis)
    }

    @Test
    fun `allCalendarPeriods_areHalfOpen`() {
        val ld = LocalDate.of(2026, 6, 15)
        val now = ld.atTime(10, 30, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

        val calendarKinds = listOf(
            PeriodKind.TODAY,
            PeriodKind.THIS_WEEK,
            PeriodKind.LAST_WEEK,
            PeriodKind.THIS_MONTH,
            PeriodKind.LAST_MONTH,
            PeriodKind.THIS_QUARTER,
            PeriodKind.LAST_QUARTER,
            PeriodKind.THIS_YEAR,
            PeriodKind.LAST_YEAR
        )
        for (kind in calendarKinds) {
            val range = kind.toPeriodRange(now, zoneId = utc)
            assertTrue("$kind end must be > start", range.endExclusiveMillis > range.startInclusiveMillis)
            assertTrue("$kind must contain now", range.contains(now))
        }
    }
}
