package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.MonthMoneyAggregate
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.data.repository.PeriodMoneyAggregate
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.model.PeriodStatus
import com.yourname.expensetracker.domain.model.PeriodType
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * P5-014 (RP-07): status badges must compare against COMPLETED-history only.
 *
 * Plan contract (RP-07-runway-trend-polish §P5-014): `excludeCurrent = true`
 * is required for a historical comparison/status baseline; `false` is allowed
 * only for a surface explicitly labelled as including the current partial
 * period. The caller matrix is pinned here at the [TotalsAggregationEngine]
 * surface:
 *
 * - [TotalsAggregationEngine.getMonthlyTotals] — monthly status badges use a
 *   completed-month baseline, so the in-progress month never drags it down
 *   (partial current month, day-two month, zero-spend completed month, and a
 *   partial-conversion month are each covered).
 * - [TotalsAggregationEngine.getWeeklyTotals] — the current week is excluded
 *   from the baseline ONLY when it is part of the displayed month; a
 *   historical month's completed weeks keep their full weight.
 * - [TotalsAggregationEngine.getAverageForPeriodType] with
 *   `excludeCurrent = false` — pins the documented include-current contract
 *   that an explicitly-labelled "including current" display relies on.
 *
 * Each status fixture is a flip test: with the old include-current baseline
 * the asserted period would read the OPPOSITE status, so weakening the
 * baseline policy or the assertion cannot pass silently.
 */
class TotalsAggregationEngineStatusBaselineTest {

    private lateinit var engine: TotalsAggregationEngine
    private val expenseRepository = mockk<ExpenseRepository>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private val multiCurrencyRepo = mockk<MultiCurrencyRepository>()
    private val categoryRepository = mockk<CategoryRepository>(relaxed = true)

    @Before
    fun setup() {
        coEvery { multiCurrencyRepo.getHomeCurrencyPurchaseTotalHistoricalResult(any(), any()) } returns
            com.yourname.expensetracker.domain.core.money.MoneyAggregateResult.Available(
                MoneyAggregate.empty(CurrencyCode("EUR"))
            )
        coEvery { multiCurrencyRepo.getMonthlyAggregatesHistorical(any(), any()) } returns emptyList()
        coEvery { multiCurrencyRepo.getWeeklyAggregatesHistorical(any(), any()) } returns emptyList()
        every { expenseRepository.getTotalSpent() } returns flowOf(null)

        engine = TotalsAggregationEngine(expenseRepository, timeProvider, multiCurrencyRepo, categoryRepository, Dispatchers.Unconfined)
    }

    // ── getMonthlyTotals: completed-month baseline ──────────────────────────

    @Test
    fun `partial current month does not drag the monthly status baseline down`() = runTest {
        // now = Sep 15 2026; the in-progress month (2026-09, partial, 300) must
        // stay out of the baseline. Completed months: 11×1200 + August 1150.
        // excludeCurrent=true → baseline = (11*1200 + 1150) / 12 = 1195.83 →
        // August (1150) reads UNDER_AVERAGE. The old include-current baseline
        // (13 rows incl. 300) = 1126.92 would read August OVER_AVERAGE — the
        // exact false "over average" the plan forbids.
        every { timeProvider.now() } returns epochMs(2026, 9, 15, 12, 0)
        coEvery { multiCurrencyRepo.getMonthlyAggregatesHistorical(any(), any()) } returns listOf(
            MonthMoneyAggregate("2025-09", MoneyAggregate.singleCurrency(1200.0, CurrencyCode("EUR"), 10)),
            MonthMoneyAggregate("2025-10", MoneyAggregate.singleCurrency(1200.0, CurrencyCode("EUR"), 10)),
            MonthMoneyAggregate("2025-11", MoneyAggregate.singleCurrency(1200.0, CurrencyCode("EUR"), 10)),
            MonthMoneyAggregate("2025-12", MoneyAggregate.singleCurrency(1200.0, CurrencyCode("EUR"), 10)),
            MonthMoneyAggregate("2026-01", MoneyAggregate.singleCurrency(1200.0, CurrencyCode("EUR"), 10)),
            MonthMoneyAggregate("2026-02", MoneyAggregate.singleCurrency(1200.0, CurrencyCode("EUR"), 10)),
            MonthMoneyAggregate("2026-03", MoneyAggregate.singleCurrency(1200.0, CurrencyCode("EUR"), 10)),
            MonthMoneyAggregate("2026-04", MoneyAggregate.singleCurrency(1200.0, CurrencyCode("EUR"), 10)),
            MonthMoneyAggregate("2026-05", MoneyAggregate.singleCurrency(1200.0, CurrencyCode("EUR"), 10)),
            MonthMoneyAggregate("2026-06", MoneyAggregate.singleCurrency(1200.0, CurrencyCode("EUR"), 10)),
            MonthMoneyAggregate("2026-07", MoneyAggregate.singleCurrency(1200.0, CurrencyCode("EUR"), 10)),
            MonthMoneyAggregate("2026-08", MoneyAggregate.singleCurrency(1150.0, CurrencyCode("EUR"), 9)),
            monthAggregate("2026-09", 300.0, isPartial = true, warning = "2 conversions unavailable")
        )

        val result = engine.getMonthlyTotals(2026).first()

        assertEquals(12, result.size)
        assertEquals(
            "August must be judged against completed history only",
            PeriodStatus.UNDER_AVERAGE,
            result.first { it.periodKey == "2026-08" }.status
        )
        assertEquals(
            "completed months at the baseline level stay OVER (>= baseline)",
            PeriodStatus.OVER_AVERAGE,
            result.first { it.periodKey == "2026-01" }.status
        )
        // Partial-state propagation is preserved on the current month's row.
        val current = result.first { it.periodKey == "2026-09" }
        assertEquals(300.0, current.totalAmount, 0.01)
        assertEquals(true, current.isPartial)
        assertEquals("2 conversions unavailable", current.warningMessage)
    }

    @Test
    fun `day-two month cannot skew the monthly status baseline`() = runTest {
        // now = Sep 2 2026: the current month holds only two days of data — but
        // whatever its size, it must not move the completed-history baseline.
        // With an outsized early total (2600): include-current would give
        // (12*1200 + 2600) / 13 = 1307.7, falsely reading May (1200) as
        // UNDER_AVERAGE. excludeCurrent=true → baseline = 1200 exactly →
        // May reads OVER_AVERAGE (>= baseline).
        every { timeProvider.now() } returns epochMs(2026, 9, 2, 12, 0)
        coEvery { multiCurrencyRepo.getMonthlyAggregatesHistorical(any(), any()) } returns
            monthRows("2025-09".."2026-08", 1200.0) +
                MonthMoneyAggregate("2026-09", MoneyAggregate.singleCurrency(2600.0, CurrencyCode("EUR"), 3))

        val result = engine.getMonthlyTotals(2026).first()

        assertEquals(
            "a day-two partial month must not flip completed months to UNDER",
            PeriodStatus.OVER_AVERAGE,
            result.first { it.periodKey == "2026-05" }.status
        )
    }

    @Test
    fun `zero-spend completed month stays in the monthly baseline mean`() = runTest {
        // A recorded zero-spend month is real history and counts in the mean
        // (it is NOT the same as a missing rate). Baseline over completed
        // months = (1200 + 0 + 900) / 3 = 700 → August (900) reads OVER_AVERAGE.
        // Dropping the zero month would give 1050 and flip August to UNDER.
        every { timeProvider.now() } returns epochMs(2026, 9, 15, 12, 0)
        coEvery { multiCurrencyRepo.getMonthlyAggregatesHistorical(any(), any()) } returns listOf(
            MonthMoneyAggregate("2026-06", MoneyAggregate.singleCurrency(1200.0, CurrencyCode("EUR"), 10)),
            MonthMoneyAggregate("2026-07", MoneyAggregate.singleCurrency(0.0, CurrencyCode("EUR"), 0)),
            MonthMoneyAggregate("2026-08", MoneyAggregate.singleCurrency(900.0, CurrencyCode("EUR"), 8)),
            MonthMoneyAggregate("2026-09", MoneyAggregate.singleCurrency(300.0, CurrencyCode("EUR"), 3))
        )

        val result = engine.getMonthlyTotals(2026).first()

        assertEquals(
            "the recorded zero month must keep its weight in the baseline",
            PeriodStatus.OVER_AVERAGE,
            result.first { it.periodKey == "2026-08" }.status
        )
    }

    @Test
    fun `partial-conversion current month is excluded from baseline but keeps its warning`() = runTest {
        // Uniform completed history at 1200 → baseline exactly 1200; July (1200)
        // reads OVER_AVERAGE (>= baseline). The in-progress month carries a
        // conversion gap (isPartial + warning, understated 300) — it is excluded
        // from the baseline AND its partial-state still propagates to its row,
        // proving exclusion is not treated as a missing rate for display.
        every { timeProvider.now() } returns epochMs(2026, 9, 15, 12, 0)
        coEvery { multiCurrencyRepo.getMonthlyAggregatesHistorical(any(), any()) } returns
            monthRows("2025-09".."2026-08", 1200.0) +
                monthAggregate("2026-09", 300.0, isPartial = true, warning = "1 conversion unavailable")

        val result = engine.getMonthlyTotals(2026).first()

        assertEquals(
            PeriodStatus.OVER_AVERAGE,
            result.first { it.periodKey == "2026-07" }.status
        )
        val current = result.first { it.periodKey == "2026-09" }
        assertEquals(true, current.isPartial)
        assertEquals("1 conversion unavailable", current.warningMessage)
    }

    // ── getWeeklyTotals: current week excluded only when displayed ──────────

    @Test
    fun `current week is excluded from the weekly baseline when the displayed month contains it`() = runTest {
        // now = Sep 15 2026 (inside ISO-style week W38, Mon Sep 14–Sun Sep 20).
        // Displayed month = September 2026 → contains the current week → the
        // in-progress week (W38, 50) is excluded from the baseline:
        // baseline = (300 + 260) / 2 = 280 → W37 (260) reads UNDER_AVERAGE.
        // The old include-current baseline (50 included) = 203.33 would read
        // W37 OVER_AVERAGE — the false badge this fix removes.
        every { timeProvider.now() } returns epochMs(2026, 9, 15, 12, 0)
        coEvery { multiCurrencyRepo.getWeeklyAggregatesHistorical(any(), any()) } returns listOf(
            PeriodMoneyAggregate("2026-W36", MoneyAggregate.singleCurrency(300.0, CurrencyCode("EUR"), 5)),
            PeriodMoneyAggregate("2026-W37", MoneyAggregate.singleCurrency(260.0, CurrencyCode("EUR"), 4)),
            PeriodMoneyAggregate("2026-W38", MoneyAggregate.singleCurrency(50.0, CurrencyCode("EUR"), 1))
        )

        val result = engine.getWeeklyTotals(2026, 9).first()

        assertEquals(3, result.size)
        assertEquals(
            PeriodStatus.OVER_AVERAGE,
            result.first { it.periodKey == "2026-W36" }.status
        )
        assertEquals(
            "the completed week before the in-progress week must be judged on completed history",
            PeriodStatus.UNDER_AVERAGE,
            result.first { it.periodKey == "2026-W37" }.status
        )
    }

    @Test
    fun `historical month weeks keep their full weight in the weekly baseline`() = runTest {
        // Displayed month = May 2026; none of its weeks contains now, so no
        // exclusion happens: baseline = (300 + 260 + 400) / 3 = 320 over ALL
        // weeks. W19 (300) reads UNDER_AVERAGE and W21 (400) OVER_AVERAGE —
        // proving a historical month's completed weeks are not dropped.
        every { timeProvider.now() } returns epochMs(2026, 9, 15, 12, 0)
        coEvery { multiCurrencyRepo.getWeeklyAggregatesHistorical(any(), any()) } returns listOf(
            PeriodMoneyAggregate("2026-W19", MoneyAggregate.singleCurrency(300.0, CurrencyCode("EUR"), 5)),
            PeriodMoneyAggregate("2026-W20", MoneyAggregate.singleCurrency(260.0, CurrencyCode("EUR"), 4)),
            PeriodMoneyAggregate("2026-W21", MoneyAggregate.singleCurrency(400.0, CurrencyCode("EUR"), 6))
        )

        val result = engine.getWeeklyTotals(2026, 5).first()

        assertEquals(3, result.size)
        assertEquals(
            PeriodStatus.UNDER_AVERAGE,
            result.first { it.periodKey == "2026-W19" }.status
        )
        assertEquals(
            PeriodStatus.OVER_AVERAGE,
            result.first { it.periodKey == "2026-W21" }.status
        )
    }

    // ── Documented include-current display contract ─────────────────────────

    @Test
    fun `explicit include-current average keeps the partial current month in the mean`() = runTest {
        // A surface that explicitly labels itself "including current" may pass
        // excludeCurrent = false — documented in the getAverageForPeriodType
        // KDoc. This pins that contract: the partial current month (300) IS
        // part of the mean → (1200 + 1200 + 300) / 3 = 900.0 exactly.
        every { timeProvider.now() } returns epochMs(2026, 9, 15, 12, 0)
        coEvery { multiCurrencyRepo.getMonthlyAggregatesHistorical(any(), any()) } returns listOf(
            MonthMoneyAggregate("2026-07", MoneyAggregate.singleCurrency(1200.0, CurrencyCode("EUR"), 10)),
            MonthMoneyAggregate("2026-08", MoneyAggregate.singleCurrency(1200.0, CurrencyCode("EUR"), 10)),
            MonthMoneyAggregate("2026-09", MoneyAggregate.singleCurrency(300.0, CurrencyCode("EUR"), 3))
        )

        val average = engine.getAverageForPeriodType(PeriodType.MONTH, excludeCurrent = false)

        assertEquals(900.0, average, 0.01)
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** Builds completed-month rows [from]..[to] (inclusive, "YYYY-MM" keys) at [amount]. */
    private fun monthRows(range: ClosedRange<String>, amount: Double): List<MonthMoneyAggregate> {
        val rows = mutableListOf<MonthMoneyAggregate>()
        var (year, month) = range.start.substring(0, 4).toInt() to range.start.substring(5, 7).toInt()
        while (true) {
            val key = "%04d-%02d".format(year, month)
            rows += MonthMoneyAggregate(key, MoneyAggregate.singleCurrency(amount, CurrencyCode("EUR"), 10))
            if (key == range.endInclusive) break
            month += 1
            if (month > 12) { month = 1; year += 1 }
        }
        return rows
    }

    private fun monthAggregate(key: String, amount: Double, isPartial: Boolean, warning: String): MonthMoneyAggregate =
        MonthMoneyAggregate(
            key,
            MoneyAggregate.singleCurrency(amount, CurrencyCode("EUR"), 3)
                .copy(isPartial = isPartial, warningMessage = warning)
        )

    private fun epochMs(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
}
