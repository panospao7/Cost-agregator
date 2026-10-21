package com.yourname.expensetracker.domain.budget

import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.util.GlobalTimeZoneTestLock
import com.yourname.expensetracker.domain.util.PinnedUtcTimeZoneRule
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.ClassRule
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/**
 * B6-4: fixed-length (DAILY / WEEKLY) window rows plus the convenience-wrapper delegation.
 *
 * MONTHLY / YEARLY anchored windows (anchor-day coercion, leap years, year wrap, historical /
 * next / future cycles) live in [BudgetCalculatorTimeBoundaryTest]; `calculatePeriodRange`
 * mode routing (CALENDAR vs ROLLING) lives in [BudgetCalculatorBoundaryTest].
 *
 * Row-tag legend (shared by the three BudgetCalculator classes): `BCT` = row that came from the
 * former BudgetCalculatorTest, `B` = former BudgetCalculatorBoundaryTest, `TB` = row authored
 * for the time-boundary suite (including the neighbour boundaries added in B6-4), `P6-016` =
 * B3-4 weekly rows.
 *
 * The JVM default zone is pinned to UTC for the class ([PinnedUtcTimeZoneRule]); fixtures use
 * an explicit zone. Zone-switching rows (DST) hold [GlobalTimeZoneTestLock] and restore UTC.
 */
class BudgetCalculatorTest {

    companion object {
        // Zone pinned to UTC for the whole class: BudgetCalculator reads ZoneId.systemDefault().
        @JvmField
        @ClassRule
        val utcZone = PinnedUtcTimeZoneRule()

        private val UTC: ZoneId = ZoneId.of("UTC")
        private val ATHENS: ZoneId = ZoneId.of("Europe/Athens")
        private const val HOUR_MS = 60L * 60L * 1000L
    }

    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private lateinit var calculator: BudgetCalculator

    @Before
    fun setup() {
        every { timeProvider.now() } returns 0L
        calculator = BudgetCalculator(timeProvider)
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0, zone: ZoneId = UTC): Long =
        LocalDate.of(year, month, day).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun dayStart(year: Int, month: Int, day: Int, zone: ZoneId = UTC): Long =
        LocalDate.of(year, month, day).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun <T> withDefaultTimeZone(zoneId: String, block: () -> T): T =
        GlobalTimeZoneTestLock.withLock {
            val previous = TimeZone.getDefault()
            TimeZone.setDefault(TimeZone.getTimeZone(zoneId))
            try {
                block()
            } finally {
                TimeZone.setDefault(previous)
            }
        }

    private data class WindowRow(
        val name: String,
        val period: BudgetPeriod,
        val anchor: Long,
        val evaluationTime: Long,
        val expectedStart: Long,
        val expectedEndExclusive: Long
    )

    /** Evaluates all rows and reports every mismatching row (named) in one failure. */
    private fun assertWindowTable(table: String, expectedRowCount: Int, rows: List<WindowRow>) {
        assertEquals("$table: row count guard", expectedRowCount, rows.size)
        assertEquals("$table: row names must be unique", rows.size, rows.map { it.name }.toSet().size)
        val failures = mutableListOf<String>()
        for (row in rows) {
            val window = calculator.calculatePeriodWindowForTime(row.period, row.anchor, row.evaluationTime)
            if (window.startInclusiveMillis != row.expectedStart) {
                failures += "[$table/${row.name}] start expected ${Instant.ofEpochMilli(row.expectedStart)} " +
                    "but was ${Instant.ofEpochMilli(window.startInclusiveMillis)}"
            }
            if (window.endExclusiveMillis != row.expectedEndExclusive) {
                failures += "[$table/${row.name}] end expected ${Instant.ofEpochMilli(row.expectedEndExclusive)} " +
                    "but was ${Instant.ofEpochMilli(window.endExclusiveMillis)}"
            }
            if (!window.contains(row.evaluationTime)) {
                failures += "[$table/${row.name}] window [${Instant.ofEpochMilli(window.startInclusiveMillis)}, " +
                    "${Instant.ofEpochMilli(window.endExclusiveMillis)}) must contain the evaluation time " +
                    "${Instant.ofEpochMilli(row.evaluationTime)}"
            }
        }
        if (failures.isNotEmpty()) {
            fail(failures.joinToString(separator = "\n"))
        }
    }

    @Test
    fun `calculatePeriodWindow DAILY returns 24h window table`() {
        assertWindowTable(
            table = "daily",
            expectedRowCount = 2,
            rows = listOf(
                // Start is start of the evaluation day; end is exactly 24 hours later.
                WindowRow("BCT mid-morning", BudgetPeriod.DAILY, 0L, at(2026, 1, 15, 10, 30),
                    dayStart(2026, 1, 15), dayStart(2026, 1, 16)),
                // Half-open neighbour: the instant of midnight belongs to the next day.
                WindowRow("TB exactly midnight belongs to the new day", BudgetPeriod.DAILY, 0L, at(2026, 1, 16, 0, 0),
                    dayStart(2026, 1, 16), dayStart(2026, 1, 17))
            )
        )
        // The DAILY window in a fixed-offset zone is exactly 24 hours.
        val window = calculator.calculatePeriodWindowForTime(BudgetPeriod.DAILY, 0L, at(2026, 1, 15, 10, 30))
        assertEquals(24L * HOUR_MS, window.endExclusiveMillis - window.startInclusiveMillis)
    }

    @Test
    fun `calculatePeriodWindow WEEKLY aligns to anchor weekday table`() {
        assertWindowTable(
            table = "weekly",
            expectedRowCount = 5,
            rows = listOf(
                // Anchor Monday Jan 1 2024, evaluated Wednesday Jan 3 -> Mon Jan 1 .. Mon Jan 8 (7 days).
                WindowRow("BCT 7 day window aligned to Monday anchor", BudgetPeriod.WEEKLY,
                    at(2024, 1, 1, 10, 0), at(2024, 1, 3, 15, 0),
                    dayStart(2024, 1, 1), dayStart(2024, 1, 8)),
                WindowRow("B weekly period returns monday to monday window", BudgetPeriod.WEEKLY,
                    at(2026, 1, 5, 9, 0), at(2026, 3, 11, 14, 0),
                    dayStart(2026, 3, 9), dayStart(2026, 3, 16)),
                // Dec 27 2026 is a Sunday. The old Calendar-based formula DayOfWeek.of(calDay - 1)
                // would have thrown DateTimeException for Sunday (Calendar.SUNDAY == 1).
                WindowRow("P6-016 rolling weekly Sunday anchor spans Sunday to Sunday across the year boundary",
                    BudgetPeriod.WEEKLY, at(2026, 12, 27, 9, 0), at(2026, 12, 31, 14, 0),
                    dayStart(2026, 12, 27), dayStart(2027, 1, 3)),
                WindowRow("P6-016 Sunday anchor evaluated Saturday late evening stays in the current window",
                    BudgetPeriod.WEEKLY, at(2026, 12, 27, 9, 0), at(2027, 1, 2, 23, 59),
                    dayStart(2026, 12, 27), dayStart(2027, 1, 3)),
                // Half-open [start, end): the instant Sunday Jan 3 00:00 belongs to the NEW window.
                WindowRow("P6-016 Sunday anchor evaluated at Sunday midnight starts the next window",
                    BudgetPeriod.WEEKLY, at(2026, 12, 27, 9, 0), at(2027, 1, 3, 0, 0),
                    dayStart(2027, 1, 3), dayStart(2027, 1, 10))
            )
        )
    }

    @Test
    fun `daily period across athens dst transitions table`() {
        data class DstRow(
            val name: String,
            val evalYear: Int, val evalMonth: Int, val evalDay: Int, val evalHour: Int,
            val expectedDurationMs: Long
        )
        val rows = listOf(
            // 2026-03-29 03:00 Athens does not exist (clocks jump 03:00 -> 04:00); the original
            // fixture resolved 03:00 forward to 04:00. The day is 23 hours long.
            DstRow("B spring forward 2026-03-29 has 23 hour duration", 2026, 3, 29, 4, 23L * HOUR_MS),
            // Fall back 2026-10-25 (04:00 EEST -> 03:00 EET) -> a 25 hour day.
            DstRow("TB fall back 2026-10-25 has 25 hour duration", 2026, 10, 25, 12, 25L * HOUR_MS)
        )
        assertEquals("dst: row count guard", 2, rows.size)
        assertEquals("dst: row names must be unique", rows.size, rows.map { it.name }.toSet().size)

        val failures = mutableListOf<String>()
        for (row in rows) {
            withDefaultTimeZone("Europe/Athens") {
                val fakeTimeProvider = FakeTimeProvider(
                    at(row.evalYear, row.evalMonth, row.evalDay, row.evalHour, 0, ATHENS)
                )
                val dstCalculator = BudgetCalculator(fakeTimeProvider)

                val window = dstCalculator.calculatePeriodWindow(BudgetPeriod.DAILY, 0L)
                val expectedStart = dayStart(row.evalYear, row.evalMonth, row.evalDay, ATHENS)
                val expectedEnd = LocalDate.of(row.evalYear, row.evalMonth, row.evalDay).plusDays(1)
                    .atStartOfDay(ATHENS).toInstant().toEpochMilli()
                if (window.startInclusiveMillis != expectedStart) {
                    failures += "[dst/${row.name}] start expected ${Instant.ofEpochMilli(expectedStart)} " +
                        "but was ${Instant.ofEpochMilli(window.startInclusiveMillis)}"
                }
                if (window.endExclusiveMillis != expectedEnd) {
                    failures += "[dst/${row.name}] end expected ${Instant.ofEpochMilli(expectedEnd)} " +
                        "but was ${Instant.ofEpochMilli(window.endExclusiveMillis)}"
                }
                val duration = window.endExclusiveMillis - window.startInclusiveMillis
                if (duration != row.expectedDurationMs) {
                    failures += "[dst/${row.name}] duration expected ${row.expectedDurationMs} but was $duration"
                }
            }
        }
        if (failures.isNotEmpty()) {
            fail(failures.joinToString(separator = "\n"))
        }
    }

    @Test
    fun `convenience calculatePeriodWindow delegates to calculatePeriodWindowForTime with timeProvider now`() {
        val now = at(2026, 4, 10, 14, 0)
        every { timeProvider.now() } returns now
        val anchorDate = at(2026, 1, 5, 0, 0)

        val convenienceResult = calculator.calculatePeriodWindow(BudgetPeriod.MONTHLY, anchorDate)
        val explicitResult = calculator.calculatePeriodWindowForTime(BudgetPeriod.MONTHLY, anchorDate, now)

        assertEquals(explicitResult.startInclusiveMillis, convenienceResult.startInclusiveMillis)
        assertEquals(explicitResult.endExclusiveMillis, convenienceResult.endExclusiveMillis)
        // Exact values: anchor day 5, evaluated Apr 10 -> Apr 5 .. May 5.
        assertEquals(dayStart(2026, 4, 5), convenienceResult.startInclusiveMillis)
        assertEquals(dayStart(2026, 5, 5), convenienceResult.endExclusiveMillis)
    }
}
