package com.yourname.expensetracker.domain.budget

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.dao.BudgetForecastDao
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.analytics.AnalyticsNormalizationResult
import com.yourname.expensetracker.domain.analytics.NormalizedExpenseSnapshot
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * RP-08 (slice C2) plan-mandated rewrite of the former stale boundary test.
 *
 * The old version stubbed [com.yourname.expensetracker.data.database.dao.ExpenseDao.getMonthlySpendingTotalsByCategoryBetween],
 * a deprecated aggregate query the forecasting engine no longer calls (the
 * engine consumes normalized snapshots through [AnalyticsCurrencyNormalizer],
 * then [BudgetHistorySeriesBuilder]). Those stubs fed a dead code path, so the
 * assertions there were vacuous. This rewrite drives the SAME ±10% boundary
 * values through the live snapshot→normalizer→series path and asserts the
 * trend multipliers, with the incomplete trailing edge month excluded
 * (P6-004) — no assertions were weakened relative to the original intent.
 */
class BudgetTrendBoundaryTest {

    private val budgetRepository = mockk<BudgetRepository>(relaxed = true)
    private val budgetForecastDao = mockk<BudgetForecastDao>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private val expenseRepository = mockk<ExpenseRepository>(relaxed = true)

    private lateinit var engine: BudgetForecastingEngine

    private val now = ms(2026, 4, 15)

    private fun snapshot(monthKey: String, total: Double): ExpenseSnapshot {
        val parts = monthKey.split("-")
        val date = LocalDate.of(parts[0].toInt(), parts[1].toInt(), 15)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        return ExpenseSnapshot(
            id = 0L,
            amount = total,
            effectiveAmount = total,
            currency = "EUR",
            merchant = "Test",
            merchantKey = null,
            transactionType = DomainTransactionType.PURCHASE,
            date = date,
            categoryId = 1L,
            isNotMine = false,
            transferDirection = null,
            notes = null
        )
    }

    private fun stubMonthlyHistory(totals: List<Pair<String, Double>>) {
        coEvery { expenseRepository.getExpenseSnapshotsBetween(any(), any()) } returns
            totals.map { (monthKey, total) -> snapshot(monthKey, total) }
    }

    @Test
    fun `budget_trend_boundary_exactly_10_percent_is_stable`() = runTest {
        every { timeProvider.now() } returns now
        coEvery { budgetForecastDao.insertWithDeactivation(any()) } returns 1L

        val normalizer = mockk<AnalyticsCurrencyNormalizer>(relaxed = true)
        coEvery { normalizer.normalizeSnapshots(any(), any()) } answers {
            val expenses = firstArg<List<ExpenseSnapshot>>()
            val homeCurrency = secondArg<String>()
            AnalyticsNormalizationResult(
                homeCurrency = homeCurrency,
                normalizedExpenses = expenses.map {
                    NormalizedExpenseSnapshot(it, it.currency, it.effectiveAmount, it.effectiveAmount)
                },
                includedExpenses = expenses,
                warnings = emptyList(),
                latestRateTimestamp = null,
                totalInputCount = expenses.size
            )
        }

        engine = BudgetForecastingEngine(
            expenseDao = mockk(relaxed = true),
            budgetRepository = budgetRepository,
            budgetForecastDao = budgetForecastDao,
            timeProvider = timeProvider,
            ioDispatcher = Dispatchers.Unconfined,
            analyticsCurrencyNormalizer = normalizer,
            expenseRepository = expenseRepository,
            currencySettingsRepository = mockk<CurrencySettingsRepository>().also {
                every { it.homeCurrency() } returns flowOf("EUR")
                coEvery { it.resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
            },
            currencyConverter = mockk<CurrencyConverter>(relaxed = true),
            writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true),
        )

        val budget = Budget(categoryId = 1L, amount = 10_000.0, period = BudgetPeriod.MONTHLY, startDate = now)

        // RP-08 (P6-004): the window [Jan 15, Apr 15) trims to [Feb 1, Apr 1) —
        // the leading partial January month is excluded, so the series is
        // exactly [Feb, Mar]. The boundary probes therefore place their values
        // in Feb/Mar:
        //
        // Feb=100, Mar=110 → older avg=100, recent avg=110 → exactly +10% → STABLE.
        stubMonthlyHistory(listOf("2026-02" to 100.0, "2026-03" to 110.0))
        val exactlyPlus10 = engine.generateForecast(budget)

        // Feb=100, Mar=90 → recent avg=90 → exactly -10% → STABLE.
        stubMonthlyHistory(listOf("2026-02" to 100.0, "2026-03" to 90.0))
        val exactlyMinus10 = engine.generateForecast(budget)

        // Feb=100, Mar=110.01 → ratio 1.1001 → INCREASING.
        stubMonthlyHistory(listOf("2026-02" to 100.0, "2026-03" to 110.01))
        val plus1001 = engine.generateForecast(budget)

        // Feb=100, Mar=89.99 → ratio 0.8999 → DECREASING.
        stubMonthlyHistory(listOf("2026-02" to 100.0, "2026-03" to 89.99))
        val minus1001 = engine.generateForecast(budget)

        val remainingMonths = java.time.temporal.ChronoUnit.DAYS.between(
            LocalDate.of(2026, 4, 15),
            LocalDate.of(2026, 5, 15)
        ) / 30.0

        // Exactly ±10% remains STABLE (no 1.1/0.9 multiplier).
        assertApproxEquals(((100.0 + 110.0) / 2.0) * remainingMonths, exactlyPlus10.predictedSpending, 0.0001)
        assertApproxEquals(((100.0 + 90.0) / 2.0) * remainingMonths, exactlyMinus10.predictedSpending, 0.0001)

        // ±10.01% triggers INCREASING/DECREASING multipliers.
        val plus1001Average = ((100.0 + 110.01) / 2.0) * remainingMonths
        val minus1001Average = ((100.0 + 89.99) / 2.0) * remainingMonths
        assertApproxEquals(plus1001Average * 1.1, plus1001.predictedSpending, 0.0001)
        assertApproxEquals(minus1001Average * 0.9, minus1001.predictedSpending, 0.0001)

        assertTrue(plus1001.predictedSpending > plus1001Average)
        assertTrue(minus1001.predictedSpending < minus1001Average)
    }

    private fun ms(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
}
