package com.yourname.expensetracker.domain.budget

import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.dao.BudgetForecastDao
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetForecast
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.ForecastRiskLevel
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.analytics.AnalyticsConversionWarning
import com.yourname.expensetracker.domain.analytics.AnalyticsConversionWarningType
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.analytics.AnalyticsNormalizationResult
import com.yourname.expensetracker.domain.analytics.NormalizedExpenseSnapshot
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Tests for [BudgetForecastingEngine].
 *
 * ## Test gaps (not yet covered):
 * - Mixed-currency normalization: ensure that when expense history contains multiple
 *   currencies, the forecast normalizes all amounts to the home currency before
 *   computing averages, trends, and confidence scores.
 * - Non-home-currency budget: test a budget whose currency differs from the home
 *   currency, verifying that conversion is applied and the overspend probability
 *   correctly reflects the converted amounts.
 */
@Suppress("DEPRECATION_ERROR")
class BudgetForecastingEngineTest : AnalyticsEngineTestBase() {

    private lateinit var budgetRepository: BudgetRepository
    private lateinit var budgetForecastDao: BudgetForecastDao
    private lateinit var engine: BudgetForecastingEngine
    private lateinit var mockExpenseRepo: ExpenseRepository
    private lateinit var mockCurrencyNormalizer: AnalyticsCurrencyNormalizer
    private lateinit var mockCurrencySettingsRepo: CurrencySettingsRepository
    private lateinit var mockConverter: CurrencyConverter
    private lateinit var mockWriteBarrier: DatabaseWriteBarrier

    private val now = LocalDate.of(2026, 4, 15)
        .atStartOfDay(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

    @Before
    override fun setUp() {
        super.setUp()
        budgetRepository = mockk(relaxed = true)
        budgetForecastDao = mockk(relaxed = true)
        every { timeProvider.now() } returns now
        coEvery { budgetForecastDao.insert(any()) } returns 1L
        coEvery { budgetForecastDao.insertWithDeactivation(any()) } returns 1L

        // Mock the new code path used by production BudgetForecastingEngine.
        // The engine now goes through expenseRepository + analyticsCurrencyNormalizer
        // instead of raw expenseDao aggregate queries.
        mockExpenseRepo = mockk<ExpenseRepository>(relaxed = true)
        mockCurrencyNormalizer = mockk<AnalyticsCurrencyNormalizer>(relaxed = true)
        mockCurrencySettingsRepo = mockk<CurrencySettingsRepository>(relaxed = true)
        mockConverter = mockk<CurrencyConverter>(relaxed = true)
        mockWriteBarrier = mockk(relaxed = true)

        // Default: return empty snapshots (tests override this per-scenario)
        coEvery { mockExpenseRepo.getExpenseSnapshotsBetween(any(), any()) } returns emptyList()

        // Pass-through normalizer: wraps input snapshots into AnalyticsNormalizationResult
        coEvery { mockCurrencyNormalizer.normalizeSnapshots(any(), any()) } answers {
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
        every { mockCurrencySettingsRepo.homeCurrency() } returns flowOf("EUR")
        coEvery { mockCurrencySettingsRepo.resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))

        engine = BudgetForecastingEngine(
            expenseDao = expenseDao,
            budgetRepository = budgetRepository,
            budgetForecastDao = budgetForecastDao,
            timeProvider = timeProvider,
            ioDispatcher = Dispatchers.Unconfined,
            analyticsCurrencyNormalizer = mockCurrencyNormalizer,
            expenseRepository = mockExpenseRepo,
            currencySettingsRepository = mockCurrencySettingsRepo,
            currencyConverter = mockConverter,
            writeBarrier = mockWriteBarrier
        )
    }

    // Helper: create an ExpenseSnapshot for a given month with the specified total amount.
    // The production engine groups snapshots by month key (yyyy-MM) and sums effectiveAmount,
    // so one snapshot per month with the expected total is sufficient.
    private fun snapshot(monthKey: String, total: Double, categoryId: Long? = 1L): ExpenseSnapshot {
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
            categoryId = categoryId,
            isNotMine = false,
            transferDirection = null,
            notes = null
        )
    }

    @Test
    fun `historical average stddev trend and prediction are calculated correctly`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = now)
        // Monthly totals: Jan=100, Feb=200, Mar=300
        coEvery { mockExpenseRepo.getExpenseSnapshotsBetween(any(), any()) } returns listOf(
            snapshot("2026-01", 100.0, 1L),
            snapshot("2026-02", 200.0, 1L),
            snapshot("2026-03", 300.0, 1L)
        )

        val forecast = engine.generateForecast(budget, forecastPeriodDays = 30)

        // Lookback window is zero-filled through the current month: Jan=100, Feb=200, Mar=300, Apr=0
        // avg=150, older avg=150, recent avg=150 => STABLE, seasonal factor remains neutral
        assertApproxEquals(220.0, forecast.predictedSpending, 0.01)
        // Confidence is driven by completeness (3 observed months out of desired 4) plus variance adjustment
        assertApproxEquals(0.70, forecast.confidenceScore, 0.01)
        assertEquals(ForecastRiskLevel.CRITICAL, forecast.riskLevel)
        assertApproxEquals(1.0, forecast.overspendProbability, 0.01) // 0.05 * 0.50
    }

    @Test
    fun `single month history yields stable trend and zero stddev path`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 500.0, period = BudgetPeriod.MONTHLY, startDate = now)
        coEvery { mockExpenseRepo.getExpenseSnapshotsBetween(any(), any()) } returns listOf(
            snapshot("2026-03", 120.0, 1L)
        )

        val forecast = engine.generateForecast(budget)

        // Zero-filled history: Jan=0, Feb=0, Mar=120, Apr=0 => avg=30
        assertApproxEquals(120.0, forecast.predictedSpending, 0.01)
        assertApproxEquals(0.4666666666666667, forecast.confidenceScore, 0.01)
        assertEquals(ForecastRiskLevel.CRITICAL, forecast.riskLevel)
    }

    @Test
    fun `all months same amount keeps stddev zero and confidence bounded`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 400.0, period = BudgetPeriod.MONTHLY, startDate = now)
        coEvery { mockExpenseRepo.getExpenseSnapshotsBetween(any(), any()) } returns listOf(
            snapshot("2026-01", 100.0, 1L),
            snapshot("2026-02", 100.0, 1L),
            snapshot("2026-03", 100.0, 1L)
        )

        val forecast = engine.generateForecast(budget)

        // Zero-filled Apr bucket lowers the average and creates a decreasing recent-vs-older comparison.
        assertApproxEquals(100.0, forecast.predictedSpending, 0.01)
        assertTrue("confidence in [0,1]", forecast.confidenceScore in 0.0..1.0)
        assertEquals(ForecastRiskLevel.CRITICAL, forecast.riskLevel)
    }

    @Test
    fun `budget zero still forecasts history and is critical risk`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 0.0, period = BudgetPeriod.MONTHLY, startDate = now)
        coEvery { mockExpenseRepo.getExpenseSnapshotsBetween(any(), any()) } returns listOf(
            snapshot("2026-01", 100.0, 1L),
            snapshot("2026-02", 100.0, 1L),
            snapshot("2026-03", 100.0, 1L)
        )

        val forecast = engine.generateForecast(budget)

        assertApproxEquals(100.0, forecast.predictedSpending, 0.01)
        assertEquals(ForecastRiskLevel.CRITICAL, forecast.riskLevel)
        assertTrue(forecast.overspendProbability in 0.0..1.0)
    }

    @Test
    fun `seasonal adjustment stays neutral in december`() = runTest {
        // December path must be driven by injected timeProvider, but no month gets a special uplift.
        val decemberNow = LocalDate.of(2026, 12, 15)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        every { timeProvider.now() } returns decemberNow

        val budget = Budget(categoryId = 1L, amount = 2000.0, period = BudgetPeriod.MONTHLY, startDate = decemberNow)
        // 6 months of flat 100 each
        coEvery { mockExpenseRepo.getExpenseSnapshotsBetween(any(), any()) } returns listOf(
            snapshot("2026-06", 100.0, 1L),
            snapshot("2026-07", 100.0, 1L),
            snapshot("2026-08", 100.0, 1L),
            snapshot("2026-09", 100.0, 1L),
            snapshot("2026-10", 100.0, 1L),
            snapshot("2026-11", 100.0, 1L)
        )

        val forecast = engine.generateForecast(budget, forecastPeriodDays = 30)

        // Lookback is zero-filled through December: Sep=100, Oct=100, Nov=100, Dec=0.
        // The result should not receive any December-specific seasonal multiplier.
        assertApproxEquals(103.33333333333334, forecast.predictedSpending, 0.01)
    }

    @Test
    fun `two month history increasing trend applies increasing multiplier`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = now)
        coEvery { mockExpenseRepo.getExpenseSnapshotsBetween(any(), any()) } returns listOf(
            snapshot("2026-02", 100.0, 1L),
            snapshot("2026-03", 130.0, 1L)
        )

        val forecast = engine.generateForecast(budget, forecastPeriodDays = 30)

        // Zero-filled history: Jan=0, Feb=100, Mar=130, Apr=0 => avg=57.5, increasing trend => *1.1
        assertApproxEquals(126.5, forecast.predictedSpending, 0.01)
    }

    @Test
    fun `two month history decreasing trend applies decreasing multiplier`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = now)
        coEvery { mockExpenseRepo.getExpenseSnapshotsBetween(any(), any()) } returns listOf(
            snapshot("2026-02", 130.0, 1L),
            snapshot("2026-03", 100.0, 1L)
        )

        val forecast = engine.generateForecast(budget, forecastPeriodDays = 30)

        // Zero-filled history: Jan=0, Feb=130, Mar=100, Apr=0 => avg=57.5, decreasing trend => *0.9
        assertApproxEquals(103.5, forecast.predictedSpending, 0.01)
    }

    @Test
    fun `two month history stable trend keeps base prediction`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = now)
        coEvery { mockExpenseRepo.getExpenseSnapshotsBetween(any(), any()) } returns listOf(
            snapshot("2026-02", 100.0, 1L),
            snapshot("2026-03", 105.0, 1L)
        )

        val forecast = engine.generateForecast(budget, forecastPeriodDays = 30)

        // Zero-filled history: Jan=0, Feb=100, Mar=105, Apr=0 => avg=51.25, stable trend => unchanged
        assertApproxEquals(102.5, forecast.predictedSpending, 0.01)
    }

    @Test
    fun `forecast uses remaining active period duration instead of requested approximation`() = runTest {
        val budget = Budget(
            categoryId = 1L,
            amount = 1000.0,
            period = BudgetPeriod.MONTHLY,
            startDate = LocalDate.of(2026, 4, 1)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        )
        coEvery { mockExpenseRepo.getExpenseSnapshotsBetween(any(), any()) } returns listOf(
            snapshot("2026-01", 300.0, 1L),
            snapshot("2026-02", 300.0, 1L),
            snapshot("2026-03", 300.0, 1L)
        )

        val forecast = engine.generateForecast(budget, forecastPeriodDays = 60)
        val remainingDays = ChronoUnit.DAYS.between(
            LocalDate.of(2026, 4, 15),
            LocalDate.of(2026, 5, 1)
        ).toDouble()

        // Historical months are Jan=300, Feb=300, Mar=300, Apr=0 => avg=225, decreasing trend => *0.9
        assertApproxEquals(160.0, forecast.predictedSpending, 0.01)
        assertEquals(
            LocalDate.of(2026, 4, 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            forecast.targetPeriodStart
        )
        assertEquals(
            LocalDate.of(2026, 5, 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            forecast.targetPeriodEnd
        )
    }

    @Test
    fun `projected overspend stays deterministic even with subunit confidence`() = runTest {
        val budget = Budget(
            categoryId = null,
            amount = 100.0,
            period = BudgetPeriod.MONTHLY,
            startDate = LocalDate.of(2026, 4, 1)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        )
        // Provide all snapshots (historical + current period) in one call.
        // Production code uses these for both getHistoricalSpendingData and getSpentAmount,
        // filtering by month internally.
        // Use a custom April snapshot with date before `now` (Apr 15) so it falls within
        // the current period [periodStart, elapsedEnd) for spentToDate computation.
        val aprilSnapshot = snapshot("2026-04", 50.0, null).copy(
            date = LocalDate.of(2026, 4, 10)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        )
        coEvery { mockExpenseRepo.getExpenseSnapshotsBetween(any(), any()) } returns listOf(
            snapshot("2026-02", 50.0, null),
            snapshot("2026-03", 200.0, null),
            aprilSnapshot
        )

        val forecast = engine.generateForecast(budget)

        assertApproxEquals(0.70, forecast.confidenceScore, 0.01)
        assertApproxEquals(58.66666666666667, forecast.predictedSpending, 0.01)
        assertTrue(forecast.predictedSpending + 50.0 > budget.amount)
        assertApproxEquals(1.0, forecast.overspendProbability, 0.01)
    }

    @Test
    fun `calendar yearly budgets forecast against remaining calendar year window`() = runTest {
        val budget = Budget(
            categoryId = 1L,
            amount = 5_000.0,
            period = BudgetPeriod.YEARLY,
            periodMode = "CALENDAR",
            startDate = LocalDate.of(2023, 8, 20)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        )
        coEvery { mockExpenseRepo.getExpenseSnapshotsBetween(any(), any()) } returns listOf(
            snapshot("2026-01", 100.0, 1L),
            snapshot("2026-02", 100.0, 1L),
            snapshot("2026-03", 100.0, 1L)
        )

        val forecast = engine.generateForecast(budget)
        val yearStart = LocalDate.of(2026, 1, 1)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val yearEnd = LocalDate.of(2027, 1, 1)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val remainingDays = ChronoUnit.DAYS.between(
            LocalDate.of(2026, 4, 15),
            LocalDate.of(2027, 1, 1)
        ).toDouble()

        assertEquals(yearStart, forecast.targetPeriodStart)
        assertEquals(yearEnd, forecast.targetPeriodEnd)
        // Historical months are Jan=100, Feb=100, Mar=100, Apr=0 => avg=75, decreasing trend => *0.9
        assertApproxEquals(870.0, forecast.predictedSpending, 0.01)
    }

    // =========================================================================
    // A.1 effectiveAmount regression — aggregate SQL now handles effective-amount
    // computation in the DAO layer.  These tests verify the engine correctly
    // consumes pre-aggregated monthly totals that already reflect effective-amount
    // semantics (shared expenses, isNotMine, percentage shares).
    // =========================================================================

    @Test
    fun `historical data uses effectiveAmount for shared expenses not raw amount`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = now)
        // Effective amounts: Jan=100, Feb=80, Mar=120
        coEvery { mockExpenseRepo.getExpenseSnapshotsBetween(any(), any()) } returns listOf(
            snapshot("2026-01", 100.0, 1L),
            snapshot("2026-02", 80.0, 1L),
            snapshot("2026-03", 120.0, 1L)
        )

        val forecast = engine.generateForecast(budget, forecastPeriodDays = 30)

        // Zero-filled Apr bucket: Jan=100, Feb=80, Mar=120, Apr=0 -> avg=75
        // older=90, recent=60 -> DECREASING -> *0.9
        assertApproxEquals(100.0, forecast.predictedSpending, 0.01)
    }

    @Test
    fun `historical data uses effectiveAmount for percentage shared expenses`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = now)
        // Effective amounts: Jan=100, Feb=50, Mar=100
        coEvery { mockExpenseRepo.getExpenseSnapshotsBetween(any(), any()) } returns listOf(
            snapshot("2026-01", 100.0, 1L),
            snapshot("2026-02", 50.0, 1L),
            snapshot("2026-03", 100.0, 1L)
        )

        val forecast = engine.generateForecast(budget, forecastPeriodDays = 30)

        // Zero-filled Apr bucket: Jan=100, Feb=50, Mar=100, Apr=0 -> avg=62.5
        // older=75, recent=50 -> DECREASING -> *0.9
        assertApproxEquals(75.0, forecast.predictedSpending, 0.01)
    }

    @Test
    fun `historical data excludes isNotMine expenses and zero fills missing months`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 500.0, period = BudgetPeriod.MONTHLY, startDate = now)
        // Snapshots with effective amounts: Jan=60, Mar=60 (Feb is a gap, zero-filled by engine)
        coEvery { mockExpenseRepo.getExpenseSnapshotsBetween(any(), any()) } returns listOf(
            snapshot("2026-01", 60.0, 1L),
            snapshot("2026-03", 60.0, 1L)
        )

        val forecast = engine.generateForecast(budget, forecastPeriodDays = 30)

        // Jan=60, Feb=0, Mar=60, Apr=0 -> avg=30, stable trend
        assertApproxEquals(36.0, forecast.predictedSpending, 0.01)
    }

    @Test
    fun `mixed shared and isNotMine with regular expenses forecast correctly`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = now)
        // Effective amounts: Jan=100, Feb=40, Mar=100
        coEvery { mockExpenseRepo.getExpenseSnapshotsBetween(any(), any()) } returns listOf(
            snapshot("2026-01", 100.0, 1L),
            snapshot("2026-02", 40.0, 1L),
            snapshot("2026-03", 100.0, 1L)
        )

        val forecast = engine.generateForecast(budget, forecastPeriodDays = 30)

        // Zero-filled Apr bucket: Jan=100, Feb=40, Mar=100, Apr=0 -> avg=60
        // older=70, recent=50 -> DECREASING -> *0.9
        assertApproxEquals(72.0, forecast.predictedSpending, 0.01)
    }

    // =========================================================================
    // Null-category budget path (filtering by categoryId = null in the snapshot stream)
    // =========================================================================

    @Test
    fun `null category budget uses uncapped monthly aggregate without category filter`() = runTest {
        val budget = Budget(categoryId = null, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = now)
        coEvery { mockExpenseRepo.getExpenseSnapshotsBetween(any(), any()) } returns listOf(
            snapshot("2026-01", 200.0, null),
            snapshot("2026-02", 200.0, null),
            snapshot("2026-03", 200.0, null)
        )

        val forecast = engine.generateForecast(budget, forecastPeriodDays = 30)

        // Zero-filled Apr bucket: Jan=200, Feb=200, Mar=200, Apr=0 -> avg=150, decreasing trend => *0.9
        assertApproxEquals(200.0, forecast.predictedSpending, 0.01)
    }

    // =========================================================================
    // Sparse-history gap filling coverage
    // Gap months with no qualifying rows are synthesized as explicit zero-spend
    // buckets before averages and trends are calculated.
    // =========================================================================

    @Test
    fun `sparse months are zero filled before averaging`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = now)
        // Snapshots for Jan and Mar only; Feb and Apr are gap-filled as zeros by the engine.
        coEvery { mockExpenseRepo.getExpenseSnapshotsBetween(any(), any()) } returns listOf(
            snapshot("2026-01", 90.0, 1L),
            snapshot("2026-03", 90.0, 1L)
        )

        val forecast = engine.generateForecast(budget, forecastPeriodDays = 30)

        // Jan=90, Feb=0, Mar=90, Apr=0 -> avg=45, stable trend
        assertApproxEquals(54.0, forecast.predictedSpending, 0.01)
    }

    @Test
    fun `multi-month gaps outside returned data collapse to zero-filled lookback window`() = runTest {
        val budget = Budget(categoryId = null, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = now)
        // Snapshots outside the Jan-Apr lookback (the engine's 3-month window from threeMonthsAgo to now).
        // Since all dates are before the lookback window, the full window is zero-filled.
        coEvery { mockExpenseRepo.getExpenseSnapshotsBetween(any(), any()) } returns listOf(
            snapshot("2025-01", 120.0, null),
            snapshot("2025-06", 120.0, null)
        )

        val forecast = engine.generateForecast(budget, forecastPeriodDays = 30)

        assertApproxEquals(0.0, forecast.predictedSpending, 0.01)
    }

    @Test
    fun `contiguous observed months still include zero filled current month`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = now)
        coEvery { mockExpenseRepo.getExpenseSnapshotsBetween(any(), any()) } returns listOf(
            snapshot("2026-01", 100.0, 1L),
            snapshot("2026-02", 100.0, 1L),
            snapshot("2026-03", 100.0, 1L)
        )

        val forecast = engine.generateForecast(budget, forecastPeriodDays = 30)

        // Jan=100, Feb=100, Mar=100, Apr=0 -> avg=75, decreasing trend => *0.9
        assertApproxEquals(100.0, forecast.predictedSpending, 0.01)
    }

    // =========================================================================
    // B7: Transactional deactivate+insert (unique-index safety)
    // =========================================================================

    @Test
    fun `generateForecast calls insertWithDeactivation not plain insert`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = now)
        coEvery { mockExpenseRepo.getExpenseSnapshotsBetween(any(), any()) } returns listOf(
            snapshot("2026-03", 100.0, 1L)
        )

        engine.generateForecast(budget)

        coVerify(exactly = 1) { budgetForecastDao.insertWithDeactivation(any()) }
        coVerify(exactly = 0) { budgetForecastDao.insert(any()) }
    }

    @Test
    fun `regenerating forecast for same period deactivates previous via DAO`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = now)
        coEvery { mockExpenseRepo.getExpenseSnapshotsBetween(any(), any()) } returns listOf(
            snapshot("2026-03", 100.0, 1L)
        )

        // Generate twice for the same period
        engine.generateForecast(budget)
        engine.generateForecast(budget)

        coVerify(exactly = 2) { budgetForecastDao.insertWithDeactivation(any()) }
        coVerify(exactly = 0) { budgetForecastDao.insert(any()) }
    }

    // =========================================================================
    // P6-CURRENT-008: REPLACE -> ABORT + typed conflict, WITHOUT re-breaking refresh.
    //
    // The unique index is (budgetId, targetPeriodStart, forecastDate). A normal
    // refresh stamps a fresh forecastDate and never collides, so ABORT only fires
    // on a genuine same-millisecond duplicate. History is preserved by the
    // deactivate-then-insert transaction (insertWithDeactivation), not by REPLACE.
    // =========================================================================

    @Test
    fun `forecast_refresh_at_new_millisecond_keeps_history_one_active`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = now)
        coEvery { mockExpenseRepo.getExpenseSnapshotsBetween(any(), any()) } returns listOf(
            snapshot("2026-03", 100.0, 1L)
        )

        // In-memory store mimicking insertWithDeactivation's @Transaction:
        // deactivate the prior active row for the same (budgetId, targetPeriodStart,
        // targetPeriodEnd) then insert the new row as active. This models the real DB
        // behaviour where two rows with DIFFERENT forecastDate values coexist under the
        // (budgetId, targetPeriodStart, forecastDate) unique index — i.e. ABORT does NOT
        // collide on a normal refresh.
        val store = mutableListOf<BudgetForecast>()
        var nextId = 1L
        coEvery { budgetForecastDao.insertWithDeactivation(any()) } answers {
            val f = firstArg<BudgetForecast>()
            for (i in store.indices) {
                val existing = store[i]
                if (existing.budgetId == f.budgetId &&
                    existing.targetPeriodStart == f.targetPeriodStart &&
                    existing.targetPeriodEnd == f.targetPeriodEnd &&
                    existing.isActive
                ) {
                    store[i] = existing.copy(isActive = false)
                }
            }
            val assignedId = nextId++
            store.add(f.copy(id = assignedId, isActive = true))
            assignedId
        }

        // First generation at forecastDate = now.
        val first = engine.generateForecastResult(budget)
        // Refresh: forecastDate = now + 1ms (distinct index key, no collision).
        every { timeProvider.now() } returns now + 1
        val second = engine.generateForecastResult(budget)

        assertTrue("first generation available", first is BudgetForecastResult.Available)
        assertTrue("refresh available (ABORT did not re-break refresh)", second is BudgetForecastResult.Available)
        assertEquals("history preserved: two rows persisted", 2, store.size)
        assertEquals("exactly one active forecast", 1, store.count { it.isActive })
        assertEquals(
            "the newest forecastDate is the active one",
            now + 1,
            store.single { it.isActive }.forecastDate
        )
    }

    @Test
    fun `forecast_insert_same_millisecond_returns_conflict_not_replace`() = runTest {
        val existingRow = BudgetForecast(
            id = 99L,
            budgetId = 1L,
            forecastDate = now,
            targetPeriodStart = now,
            targetPeriodEnd = now + 1_000L,
            predictedSpending = 123.0,
            predictedRemaining = 877.0,
            confidenceScore = 0.5,
            riskLevel = ForecastRiskLevel.LOW,
            overspendProbability = 0.1,
            isActive = true
        )
        // Witness for "no overwrite": the mock NEVER mutates this because the insert throws.
        val store = mutableListOf(existingRow)

        // Same-instant duplicate: the (budgetId, targetPeriodStart, forecastDate) unique
        // index rejects the row under OnConflictStrategy.ABORT, surfaced as a constraint
        // violation from the transactional insert.
        coEvery { budgetForecastDao.insertWithDeactivation(any()) } throws
            android.database.sqlite.SQLiteConstraintException(
                "UNIQUE constraint failed: budget_forecasts.budgetId, " +
                    "budget_forecasts.targetPeriodStart, budget_forecasts.forecastDate"
            )

        val duplicateAttempt = existingRow.copy(id = 0L)
        val result = engine.insertForecast(duplicateAttempt)

        // Typed conflict — not a crash, not a silent REPLACE.
        assertEquals(ForecastInsertResult.DuplicateInSameInstant, result)
        // No existing row was overwritten: the engine performed no update / raw insert fallback.
        coVerify(exactly = 0) { budgetForecastDao.update(any()) }
        coVerify(exactly = 0) { budgetForecastDao.insert(any()) }
        assertEquals(1, store.size)
        assertEquals(99L, store.single().id)
        assertEquals(123.0, store.single().predictedSpending, 0.0)
        assertTrue(store.single().isActive)
    }

    @Test
    fun `forecast_insert_propagates_non_constraint_exceptions`() = runTest {
        // The wrapper must catch ONLY SQLiteConstraintException; critical errors must propagate.
        coEvery { budgetForecastDao.insertWithDeactivation(any()) } throws
            IllegalStateException("write barrier closed")

        val attempt = BudgetForecast(
            budgetId = 1L,
            forecastDate = now,
            targetPeriodStart = now,
            targetPeriodEnd = now + 1_000L,
            predictedSpending = 1.0,
            predictedRemaining = 1.0,
            confidenceScore = 0.5,
            riskLevel = ForecastRiskLevel.LOW,
            overspendProbability = 0.1
        )

        var thrown: Throwable? = null
        try {
            engine.insertForecast(attempt)
        } catch (e: IllegalStateException) {
            thrown = e
        }
        assertTrue("non-constraint exception must propagate", thrown is IllegalStateException)
        assertEquals("write barrier closed", thrown?.message)
    }

    // =========================================================================
    // P6-CURRENT-010: Forecast data-quality columns + exclusion-proportional
    // confidence reduction.
    //
    // The engine sources excluded counts / warnings from the SAME
    // AnalyticsCurrencyNormalizer pass that gathers history
    // (getHistoricalSpendingData). When the normalizer drops historical expenses
    // (e.g. FX conversion failed) the persisted forecast must record isPartial,
    // excludedExpenseCount, a non-empty qualityWarningsJson, and a confidence
    // strictly below the equivalent no-exclusion case.
    // =========================================================================

    @Test
    fun `budget_forecast_confidence_reduced_when_historical_expenses_excluded`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = now)
        val includedSnapshots = listOf(
            snapshot("2026-01", 100.0, 1L),
            snapshot("2026-02", 200.0, 1L),
            snapshot("2026-03", 300.0, 1L)
        )
        coEvery { mockExpenseRepo.getExpenseSnapshotsBetween(any(), any()) } returns includedSnapshots

        // Baseline: default pass-through normalizer (no exclusions, no warnings).
        val baseline = engine.generateForecast(budget, forecastPeriodDays = 30)
        assertEquals(false, baseline.isPartial)
        assertEquals(0, baseline.excludedExpenseCount)
        assertEquals(null, baseline.qualityWarningsJson)

        // Exclusion case: the normalizer keeps the SAME included expenses (so the base
        // confidence is identical) but reports 2 additional inputs as excluded plus a
        // conversion warning — so only the exclusion penalty differs from the baseline.
        coEvery { mockCurrencyNormalizer.normalizeSnapshots(any(), any()) } answers {
            val expenses = firstArg<List<ExpenseSnapshot>>()
            val homeCurrency = secondArg<String>()
            AnalyticsNormalizationResult(
                homeCurrency = homeCurrency,
                normalizedExpenses = expenses.map {
                    NormalizedExpenseSnapshot(it, it.currency, it.effectiveAmount, it.effectiveAmount)
                },
                includedExpenses = expenses,
                warnings = listOf(
                    AnalyticsConversionWarning(
                        type = AnalyticsConversionWarningType.MISSING_EXCHANGE_RATE,
                        message = "Analytics excluded transaction(s) because exchange rates were unavailable.",
                        affectedTransactionCount = 2,
                        sourceCurrencies = listOf("JPY")
                    )
                ),
                latestRateTimestamp = null,
                // 2 inputs excluded => excludedCount = totalInputCount - normalizedExpenses.size = 2
                totalInputCount = expenses.size + 2
            )
        }

        val partial = engine.generateForecast(budget, forecastPeriodDays = 30)

        assertTrue(
            "confidence must be reduced when historical expenses are excluded",
            partial.confidenceScore < baseline.confidenceScore
        )
        assertTrue("confidence stays in [0,1]", partial.confidenceScore in 0.0..1.0)
        assertEquals(true, partial.isPartial)
        assertEquals(2, partial.excludedExpenseCount)
        val json = partial.qualityWarningsJson
        assertTrue(
            "qualityWarningsJson must be non-null and non-empty",
            json != null && json.isNotBlank() && json != "[]"
        )
        // Confirm the FX RateBasis used for spend normalization is recorded.
        assertEquals("TRANSACTION_DATE", partial.rateBasis)
    }
}
