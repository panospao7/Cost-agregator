package com.yourname.expensetracker.domain.model

import org.junit.Assert.*
import org.junit.Test

class PeriodTotalTest {

    @Test
    fun `PeriodTotal stores values correctly`() {
        val period = PeriodTotal(
            periodLabel = "Jan",
            periodKey = "2026-01",
            totalAmount = 150.0,
            transactionCount = 5,
            periodType = PeriodType.MONTH,
            startDateMs = 1735689600000L,
            endDateMs = 1738281600000L,
            status = PeriodStatus.UNDER_AVERAGE
        )

        assertEquals("Jan", period.periodLabel)
        assertEquals("2026-01", period.periodKey)
        assertEquals(150.0, period.totalAmount, 0.01)
        assertEquals(5, period.transactionCount)
        assertEquals(PeriodType.MONTH, period.periodType)
        assertEquals(1735689600000L, period.startDateMs)
        assertEquals(1738281600000L, period.endDateMs)
        assertEquals(PeriodStatus.UNDER_AVERAGE, period.status)
    }

    @Test
    fun `PeriodTotal is immutable`() {
        val period = PeriodTotal(
            periodLabel = "Jan",
            periodKey = "2026-01",
            totalAmount = 150.0,
            transactionCount = 5,
            periodType = PeriodType.MONTH,
            startDateMs = 1735689600000L,
            endDateMs = 1738281600000L,
            status = PeriodStatus.UNDER_AVERAGE
        )

        assertTrue(period is PeriodTotal)
    }

    @Test
    fun `PeriodTotal copy preserves values`() {
        val original = PeriodTotal(
            periodLabel = "Jan",
            periodKey = "2026-01",
            totalAmount = 150.0,
            transactionCount = 5,
            periodType = PeriodType.MONTH,
            startDateMs = 1735689600000L,
            endDateMs = 1738281600000L,
            status = PeriodStatus.UNDER_AVERAGE
        )

        val copy = original.copy()

        assertEquals(original.periodLabel, copy.periodLabel)
        assertEquals(original.periodKey, copy.periodKey)
        assertEquals(original.totalAmount, copy.totalAmount, 0.01)
        assertEquals(original.transactionCount, copy.transactionCount)
        assertEquals(original.periodType, copy.periodType)
        assertEquals(original.startDateMs, copy.startDateMs)
        assertEquals(original.endDateMs, copy.endDateMs)
        assertEquals(original.status, copy.status)
    }

    @Test
    fun `PeriodTotal copy with modified values`() {
        val original = PeriodTotal(
            periodLabel = "Jan",
            periodKey = "2026-01",
            totalAmount = 150.0,
            transactionCount = 5,
            periodType = PeriodType.MONTH,
            startDateMs = 1735689600000L,
            endDateMs = 1738281600000L,
            status = PeriodStatus.UNDER_AVERAGE
        )

        val modified = original.copy(
            totalAmount = 200.0,
            status = PeriodStatus.OVER_AVERAGE
        )

        assertEquals(200.0, modified.totalAmount, 0.01)
        assertEquals(PeriodStatus.OVER_AVERAGE, modified.status)
        assertEquals("Jan", modified.periodLabel)
    }

    @Test
    fun `PeriodStatus enum has correct values`() {
        val values = PeriodStatus.entries
        assertEquals(4, values.size)
        assertTrue(values.contains(PeriodStatus.UNDER_AVERAGE))
        assertTrue(values.contains(PeriodStatus.OVER_AVERAGE))
        assertTrue(values.contains(PeriodStatus.CURRENT))
        assertTrue(values.contains(PeriodStatus.NO_DATA))
    }

    @Test
    fun `PeriodStatus enum ordinals are correct`() {
        assertEquals(0, PeriodStatus.UNDER_AVERAGE.ordinal)
        assertEquals(1, PeriodStatus.OVER_AVERAGE.ordinal)
        assertEquals(2, PeriodStatus.CURRENT.ordinal)
        assertEquals(3, PeriodStatus.NO_DATA.ordinal)
    }

    @Test
    fun `PeriodType enum has correct values`() {
        val values = PeriodType.entries
        assertEquals(4, values.size)
        assertTrue(values.contains(PeriodType.YEAR))
        assertTrue(values.contains(PeriodType.MONTH))
        assertTrue(values.contains(PeriodType.WEEK))
        assertTrue(values.contains(PeriodType.DAY))
    }

    @Test
    fun `PeriodType enum ordinals are correct`() {
        assertEquals(0, PeriodType.YEAR.ordinal)
        assertEquals(1, PeriodType.MONTH.ordinal)
        assertEquals(2, PeriodType.WEEK.ordinal)
        assertEquals(3, PeriodType.DAY.ordinal)
    }

    @Test
    fun `PeriodTotal with all period types`() {
        val yearPeriod = PeriodTotal(
            periodLabel = "2026",
            periodKey = "2026",
            totalAmount = 1000.0,
            transactionCount = 50,
            periodType = PeriodType.YEAR,
            startDateMs = 0L,
            endDateMs = 0L,
            status = PeriodStatus.NO_DATA
        )
        assertEquals(PeriodType.YEAR, yearPeriod.periodType)

        val monthPeriod = PeriodTotal(
            periodLabel = "Feb",
            periodKey = "2026-02",
            totalAmount = 200.0,
            transactionCount = 10,
            periodType = PeriodType.MONTH,
            startDateMs = 0L,
            endDateMs = 0L,
            status = PeriodStatus.UNDER_AVERAGE
        )
        assertEquals(PeriodType.MONTH, monthPeriod.periodType)

        val weekPeriod = PeriodTotal(
            periodLabel = "W1",
            periodKey = "2026-W1",
            totalAmount = 50.0,
            transactionCount = 3,
            periodType = PeriodType.WEEK,
            startDateMs = 0L,
            endDateMs = 0L,
            status = PeriodStatus.OVER_AVERAGE
        )
        assertEquals(PeriodType.WEEK, weekPeriod.periodType)

        val dayPeriod = PeriodTotal(
            periodLabel = "Mon",
            periodKey = "20260112",
            totalAmount = 25.0,
            transactionCount = 2,
            periodType = PeriodType.DAY,
            startDateMs = 0L,
            endDateMs = 0L,
            status = PeriodStatus.UNDER_AVERAGE
        )
        assertEquals(PeriodType.DAY, dayPeriod.periodType)
    }

    @Test
    fun `PeriodTotal equals and hashCode`() {
        val period1 = PeriodTotal(
            periodLabel = "Jan",
            periodKey = "2026-01",
            totalAmount = 150.0,
            transactionCount = 5,
            periodType = PeriodType.MONTH,
            startDateMs = 1735689600000L,
            endDateMs = 1738281600000L,
            status = PeriodStatus.UNDER_AVERAGE
        )

        val period2 = PeriodTotal(
            periodLabel = "Jan",
            periodKey = "2026-01",
            totalAmount = 150.0,
            transactionCount = 5,
            periodType = PeriodType.MONTH,
            startDateMs = 1735689600000L,
            endDateMs = 1738281600000L,
            status = PeriodStatus.UNDER_AVERAGE
        )

        assertEquals(period1, period2)
        assertEquals(period1.hashCode(), period2.hashCode())
    }

    @Test
    fun `PeriodTotal toString contains key fields`() {
        val period = PeriodTotal(
            periodLabel = "Jan",
            periodKey = "2026-01",
            totalAmount = 150.0,
            transactionCount = 5,
            periodType = PeriodType.MONTH,
            startDateMs = 1735689600000L,
            endDateMs = 1738281600000L,
            status = PeriodStatus.UNDER_AVERAGE
        )

        val str = period.toString()
        assertTrue(str.contains("Jan"))
        assertTrue(str.contains("2026-01"))
        assertTrue(str.contains("150.0"))
    }

    @Test
    fun `PeriodTotal with zero transaction count`() {
        val period = PeriodTotal(
            periodLabel = "Feb",
            periodKey = "2026-02",
            totalAmount = 0.0,
            transactionCount = 0,
            periodType = PeriodType.MONTH,
            startDateMs = 0L,
            endDateMs = 0L,
            status = PeriodStatus.NO_DATA
        )

        assertEquals(0.0, period.totalAmount, 0.01)
        assertEquals(0, period.transactionCount)
    }

    @Test
    fun `PeriodStatus valueOf works correctly`() {
        assertEquals(PeriodStatus.UNDER_AVERAGE, PeriodStatus.valueOf("UNDER_AVERAGE"))
        assertEquals(PeriodStatus.OVER_AVERAGE, PeriodStatus.valueOf("OVER_AVERAGE"))
        assertEquals(PeriodStatus.NO_DATA, PeriodStatus.valueOf("NO_DATA"))
    }

    @Test
    fun `PeriodType valueOf works correctly`() {
        assertEquals(PeriodType.YEAR, PeriodType.valueOf("YEAR"))
        assertEquals(PeriodType.MONTH, PeriodType.valueOf("MONTH"))
        assertEquals(PeriodType.WEEK, PeriodType.valueOf("WEEK"))
        assertEquals(PeriodType.DAY, PeriodType.valueOf("DAY"))
    }
}
