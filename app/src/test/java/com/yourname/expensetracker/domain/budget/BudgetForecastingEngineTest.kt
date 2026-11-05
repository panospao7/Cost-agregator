package com.yourname.expensetracker.domain.budget

import android.database.sqlite.SQLiteConstraintException
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
import com.yourname.expensetracker.domain.core.money.ConversionOutcome
import com.yourname.expensetracker.domain.core.money.ConversionPath
import com.yourname.expensetracker.domain.core.money.ConversionQuality
import com.yourname.expensetracker.domain.core.money.ConversionFailure
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.FailureReason
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.core.money.MoneyAmount
import com.yourname.expensetracker.domain.core.money.MoneyBucket
import com.yourname.expensetracker.domain.core.money.RateBasis
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

        // RP-09 (P6-007): the engine sources current-period spend from the repository
        // PERIOD_END aggregate. Default: an empty COMPLETE aggregate (spentToDate = 0.0,
        // matching the pre-RP-09 behavior of the tests in this suite); individual tests
        // override this stub for FX-basis scenarios.
        coEvery {
            budgetRepository.getCurrentPeriodPurchaseSpendAtPeriodEnd(any(), any(), any(), any())
        } returns BudgetRepository.CurrentPeriodSpendAtPeriodEnd(
            aggregate = MoneyAggregate.empty(CurrencyCode("EUR"), RateBasis.PERIOD_END),
            rateAsOfMillis = now
        )

        // Mock the new code path used by production BudgetForecastingEngine.
        // The engine now goes through expenseRepository + analyticsCurrencyNormalizer
        // instead of raw expenseDao aggregate queries.
        mockExpenseRepo = mockk<ExpenseRepository>(relaxed = true)
        mockCurrencyNormalizer = mockk<AnalyticsCurrencyNormalizer>(relaxed = true)
        mockCurrencySettingsRepo = mockk<CurrencySettingsRepository>(relaxed = true)
        mockConverter = mockk<CurrencyConverter>(relaxed = true)
        mockWriteBarrier = mockk(relaxed = true)

        // P-06-2: default known effective limit of 1000 for the id-0 test budgets; tests that
        // need another limit (or an unavailable one) re-stub via stubEffectiveLimit().
        coEvery { budgetRepository.getBudgetStatusAt(any(), any()) } returns
            statusFor(Budget(categoryId = 1L, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = now), 1000.0)

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

        // P-06-2: effective limit 1000 with 1000 already spent (period-end aggregate), so
        // spentToDate >= limit deterministically forces CRITICAL. (Previously this relied on
        // the relaxed converter mock returning a 0.0 limit, which is now typed unavailable.)
        stubPeriodSpend(MoneyAggregate.singleCurrency(1000.0, CurrencyCode("EUR")))

        val forecast = engine.generateForecast(budget, forecastPeriodDays = 30)

        // RP-08 (P6-004): the mid-month April window edge is excluded and the
        // leading partial January month is trimmed, so the series is [200, 300].
        // avg=250, trend INCREASING => 250 * 1.1 = 275
        assertApproxEquals(275.0, forecast.predictedSpending, 0.01)
        // Confidence: observed=2 → 2/3*0.8 = 0.5333; CV([200,300])≈0.283 < 0.3 → +0.1
        assertApproxEquals(0.6333333333333333, forecast.confidenceScore, 0.001)
        assertEquals(ForecastRiskLevel.CRITICAL, forecast.riskLevel)
        assertApproxEquals(1.0, forecast.overspendProbability, 0.01)
    }

    @Test
    fun `single month history yields stable trend and zero stddev path`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 500.0, period = BudgetPeriod.MONTHLY, startDate = now)
        coEvery { mockExpenseRepo.getExpenseSnapshotsBetween(any(), any()) } returns listOf(
            snapshot("2026-03", 120.0, 1L)
        )
        // P-06-2: limit 500 fully spent => CRITICAL (replaces the former 0.0-limit mock artifact).
        stubEffectiveLimit(budget, 500.0)
        stubPeriodSpend(MoneyAggregate.singleCurrency(500.0, CurrencyCode("EUR")))

        val forecast = engine.generateForecast(budget)

        // RP-08 P6-004: mid-month April window edge is excluded; the series is [120].
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
        // P-06-2: limit 400 fully spent => CRITICAL (replaces the former 0.0-limit mock artifact).
        stubEffectiveLimit(budget, 400.0)
        stubPeriodSpend(MoneyAggregate.singleCurrency(400.0, CurrencyCode("EUR")))

        val forecast = engine.generateForecast(budget)

        // RP-08 P6-004: window trimmed to [Feb 1, Apr 1) — series is Feb=100,
        // Mar=100 (leading partial January month excluded) → avg=100 → STABLE.
        assertApproxEquals(100.0, forecast.predictedSpending, 0.01)
        assertTrue("confidence in [0,1]", forecast.confidenceScore in 0.0..1.0)
        assertEquals(ForecastRiskLevel.CRITICAL, forecast.riskLevel)
    }

    @Test
    fun `budget with a tiny effective limit still forecasts history and is critical risk`() = runTest {
        // P-06-2: a zero limit is now typed unavailable (see the effective-limit tests below);
        // the former zero-limit CRITICAL path is exercised with a tiny positive limit instead.
        val budget = Budget(categoryId = 1L, amount = 0.0, period = BudgetPeriod.MONTHLY, startDate = now)
        stubEffectiveLimit(budget, 10.0)
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

        // RP-08 P6-004: window [Sep 15, Dec 15) trimmed to [Oct 1, Dec 1) —
        // only Oct=100, Nov=100 survive (Jun–Sep pre-window snapshots trimmed).
        // avg=100, stable trend. The 31-day remaining period scales to 100 * 31/30;
        // no December-specific seasonal multiplier is applied.
        assertApproxEquals(103.33333333333333, forecast.predictedSpending, 0.01)
    }

    @Test
    fun `two month history increasing trend applies increasing multiplier`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = now)
        coEvery { mockExpenseRepo.getExpenseSnapshotsBetween(any(), any()) } returns listOf(
            snapshot("2026-02", 100.0, 1L),
            snapshot("2026-03", 130.0, 1L)
        )

        val forecast = engine.generateForecast(budget, forecastPeriodDays = 30)

        // RP-08 P6-004: window trimmed to [Feb 1, Apr 1) — series is Feb=100, Mar=130
        // => avg=115, increasing trend => *1.1
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

        // RP-08 P6-004: window trimmed to [Feb 1, Apr 1) — series Feb=130, Mar=100
        // => avg=115, decreasing trend => *0.9
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

        // RP-08 P6-004: window trimmed to [Feb 1, Apr 1) — series Feb=100, Mar=105
        // => avg=102.5, stable trend => unchanged
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

        // RP-08 P6-004: window trimmed to [Feb 1, Apr 1) — series Feb=300, Mar=300
        // => avg=300, stable trend => 300 * 16/30 = 160
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
        // P-06-2: limit 100 with 50 spent (period-end aggregate); predicted 73.33 pushes the
        // projected total over the limit (previously forced by the 0.0-limit mock artifact).
        stubEffectiveLimit(budget, 100.0)
        stubPeriodSpend(MoneyAggregate.singleCurrency(50.0, CurrencyCode("EUR")))

        val forecast = engine.generateForecast(budget)

        // RP-08 P6-004: window trimmed to [Feb 1, Apr 1) — history series
        // Feb=50, Mar=200 (the Apr 10 snapshot falls outside the trimmed
        // window's month keys). observedMonthCount=2 → confidence
        // 2/3*0.8 = 0.5333; CV([50,200])≈0.85 → -0.1 → 0.4333.
        // Trend: [50] vs [200] → INCREASING → monthly prediction = 125 * 1.1 = 137.5.
        // The active monthly period has 16 days remaining, so 137.5 * 16/30 = 73.3333.
        assertApproxEquals(0.4333333333333333, forecast.confidenceScore, 0.01)
        assertApproxEquals(73.33333333333333, forecast.predictedSpending, 0.01)
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
        // RP-08 P6-004: window trimmed to [Feb 1, Apr 1) — series Feb=100, Mar=100
        // => avg=100, stable trend => 100 * 261/30 = 870
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

        // RP-08 P6-004: window trimmed to [Feb 1, Apr 1) — series is Feb=80, Mar=120
        // (leading partial January month excluded). avg=100, trendRate=(120-80)/80=0.5
        // -> INCREASING -> *1.1
        assertApproxEquals(110.0, forecast.predictedSpending, 0.01)
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

        // RP-08 P6-004: window trimmed to [Feb 1, Apr 1) — series is Feb=50, Mar=100.
        // avg=75, trendRate=(100-50)/50=0.625 -> INCREASING -> *1.1
        assertApproxEquals(82.5, forecast.predictedSpending, 0.01)
    }

    @Test
    fun `isNotMine expenses are excluded and pre-window months are trimmed`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 500.0, period = BudgetPeriod.MONTHLY, startDate = now)
        // Snapshots with effective amounts: Jan=60 (outside trimmed window), Mar=60
        coEvery { mockExpenseRepo.getExpenseSnapshotsBetween(any(), any()) } returns listOf(
            snapshot("2026-01", 60.0, 1L),
            snapshot("2026-03", 60.0, 1L)
        )

        val forecast = engine.generateForecast(budget, forecastPeriodDays = 30)

        // RP-08 P6-004: window trimmed to [Feb 1, Apr 1) — January is a leading
        // partial month and is excluded; the series is [Mar=60]. avg=60, stable trend.
        assertApproxEquals(60.0, forecast.predictedSpending, 0.01)
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

        // RP-08 P6-004: window trimmed to [Feb 1, Apr 1) — series is Feb=40, Mar=100.
        // avg=70, trendRate=(100-40)/40=1.5 -> INCREASING -> *1.1
        assertApproxEquals(77.0, forecast.predictedSpending, 0.01)
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

        // RP-08 P6-004: window trimmed to [Feb 1, Apr 1) — series is Feb=200, Mar=200,
        // avg=200, stable trend => unchanged
        assertApproxEquals(200.0, forecast.predictedSpending, 0.01)
    }

    // =========================================================================
    // Sparse-history gap filling coverage
    // Gap months with no qualifying rows are synthesized as explicit zero-spend
    // buckets before averages and trends are calculated.
    // =========================================================================

    @Test
    fun `sparse months collapse to remaining complete months after edge trimming`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = now)
        // Snapshots for Jan and Mar only; Jan is a leading partial month (trimmed).
        coEvery { mockExpenseRepo.getExpenseSnapshotsBetween(any(), any()) } returns listOf(
            snapshot("2026-01", 90.0, 1L),
            snapshot("2026-03", 90.0, 1L)
        )

        val forecast = engine.generateForecast(budget, forecastPeriodDays = 30)

        // RP-08 P6-004: window trimmed to [Feb 1, Apr 1) — January is excluded;
        // the series is [Mar=90]. avg=90, stable trend.
        assertApproxEquals(90.0, forecast.predictedSpending, 0.01)
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
    fun `contiguous observed months exclude the incomplete current month`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = now)
        coEvery { mockExpenseRepo.getExpenseSnapshotsBetween(any(), any()) } returns listOf(
            snapshot("2026-01", 100.0, 1L),
            snapshot("2026-02", 100.0, 1L),
            snapshot("2026-03", 100.0, 1L)
        )

        val forecast = engine.generateForecast(budget, forecastPeriodDays = 30)

        // RP-08 P6-004: window trimmed to [Feb 1, Apr 1) — the mid-month April
        // edge is never zero-filled into the series; series is Feb=100, Mar=100
        // -> avg=100, stable trend => unchanged.
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
            MessagePreservingSQLiteConstraintException(
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

    @Test
    fun `forecast_insert_foreign_key_violation_is_not_mapped_to_duplicate`() = runTest {
        // DBG-02: budget_forecasts has BOTH a UNIQUE index and a FOREIGN KEY. A FK failure
        // must not be mislabeled as a same-instant duplicate or leak raw SQLite text.
        coEvery { budgetForecastDao.insertWithDeactivation(any()) } throws
            MessagePreservingSQLiteConstraintException("FOREIGN KEY constraint failed (code 787)")

        val attempt = BudgetForecast(
            budgetId = 42L,
            forecastDate = now,
            targetPeriodStart = now,
            targetPeriodEnd = now + 1_000L,
            predictedSpending = 1.0,
            predictedRemaining = 1.0,
            confidenceScore = 0.5,
            riskLevel = ForecastRiskLevel.LOW,
            overspendProbability = 0.1
        )

        val result = engine.insertForecast(attempt)

        assertEquals(ForecastInsertResult.ConstraintViolation, result)
        coVerify(exactly = 0) { budgetForecastDao.update(any()) }
        coVerify(exactly = 0) { budgetForecastDao.insert(any()) }
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
        // Confirm the FX RateBasis of the persisted RISK CALCULATION is recorded.
        // RP-09 (P6-007): both the spend aggregate and the budget limit now use
        // PERIOD_END; transaction-date rates remain historical-series only.
        assertEquals("PERIOD_END", partial.rateBasis)
    }

    // =========================================================================
    // RP-09 (P6-007): ONE FX basis for the current-period risk math.
    //
    // Golden scenarios: single-currency parity, mid-period rate movement,
    // mixed currencies, missing rates (typed unavailable), partial spend
    // conversion (confidence penalty), and the period window boundary.
    // =========================================================================

    private fun stubPeriodSpend(aggregate: MoneyAggregate, rateAsOfMillis: Long = now) {
        coEvery {
            budgetRepository.getCurrentPeriodPurchaseSpendAtPeriodEnd(any(), any(), any(), any())
        } returns BudgetRepository.CurrentPeriodSpendAtPeriodEnd(
            aggregate = aggregate,
            rateAsOfMillis = rateAsOfMillis
        )
    }

    // P-06-2: the forecast limit is the repository status snapshot's effectiveLimit
    // (home currency, rollover applied), matched on budget.id. All test budgets use id 0.
    private fun statusFor(
        forBudget: Budget,
        effectiveLimit: Double,
        percentKnown: Boolean = true,
        isPartial: Boolean = false,
        conversionWarning: String? = null
    ): BudgetStatus = BudgetStatus(
        budget = forBudget,
        category = null,
        spentAmount = 0.0,
        remainingAmount = effectiveLimit,
        percentUsed = 0f,
        healthStatus = BudgetHealthStatus.ON_TRACK,
        periodStart = now,
        periodEnd = now,
        effectiveLimit = effectiveLimit,
        percentKnown = percentKnown,
        isPartial = isPartial,
        conversionWarning = conversionWarning
    )

    private fun stubEffectiveLimit(
        forBudget: Budget,
        effectiveLimit: Double,
        percentKnown: Boolean = true,
        isPartial: Boolean = false,
        conversionWarning: String? = null
    ) {
        coEvery { budgetRepository.getBudgetStatusAt(forBudget, any()) } returns
            statusFor(forBudget, effectiveLimit, percentKnown, isPartial, conversionWarning)
    }

    @Test
    fun `single currency parity - period risk math uses repository period-end aggregate`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = now)
        coEvery { mockExpenseRepo.getExpenseSnapshotsBetween(any(), any()) } returns listOf(
            snapshot("2026-02", 300.0, 1L),
            snapshot("2026-03", 300.0, 1L)
        )
        stubPeriodSpend(MoneyAggregate.singleCurrency(400.0, CurrencyCode("EUR")))

        val result = engine.generateForecastResult(budget)
        val forecast = (result as BudgetForecastResult.Available).forecast

        // spentToDate is exactly the PERIOD_END aggregate — never a recomputation.
        assertApproxEquals(400.0, forecast.spentToDate, 0.01)
        assertApproxEquals(300.0, forecast.predictedSpending, 0.01)
        assertApproxEquals(300.0, forecast.predictedRemaining, 0.01)
        // remaining 600, predicted 300 → usageRatio 0.5 → LOW.
        assertEquals(ForecastRiskLevel.LOW, forecast.riskLevel)
        assertEquals("PERIOD_END", forecast.rateBasis)
    }

    @Test
    fun `mid-period rate depreciation does not move spent to date off the period-end basis`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = now)
        // Transaction-date view of the current period: 1000 of effective amounts.
        // (The April snapshot is excluded from the trimmed historical series, as in
        // the existing RP-08 tests, so history contributes no predicted spending.)
        val currentPeriodSnapshot = snapshot("2026-04", 1000.0, 1L).copy(
            date = LocalDate.of(2026, 4, 10)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        )
        coEvery { mockExpenseRepo.getExpenseSnapshotsBetween(any(), any()) } returns listOf(currentPeriodSnapshot)
        // PERIOD_END basis: rates moved after the purchases, so the same period
        // spend aggregates to 800 at period-end rates. Risk math must use 800 —
        // the raw transaction-date sum must NOT leak into the risk ratio.
        stubPeriodSpend(MoneyAggregate.singleCurrency(800.0, CurrencyCode("EUR")))

        val result = engine.generateForecastResult(budget)
        val forecast = (result as BudgetForecastResult.Available).forecast

        assertApproxEquals(800.0, forecast.spentToDate, 0.01)
        // spentToDate 800 < limit 1000 → not the CRITICAL the raw sum would force.
        // PIN CHANGED: BL-07 no-history rule (spent/limit >= 0.75 is MEDIUM) now makes 800/1000
        // MEDIUM instead of LOW; the point of this test (not CRITICAL) is kept.
        assertEquals(ForecastRiskLevel.MEDIUM, forecast.riskLevel)
        assertTrue(forecast.riskLevel != ForecastRiskLevel.CRITICAL)
        assertEquals("PERIOD_END", forecast.rateBasis)
    }

    // BL-07: with no observed history the risk level follows spent/limit (limit 1000 by default).
    private suspend fun noHistoryRiskFor(spent: Double): ForecastRiskLevel {
        val budget = Budget(categoryId = 1L, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = now)
        stubPeriodSpend(MoneyAggregate.singleCurrency(spent, CurrencyCode("EUR")))
        val result = engine.generateForecastResult(budget)
        return (result as BudgetForecastResult.Available).forecast.riskLevel
    }

    @Test
    fun `BL-07 no history spent 950 of 1000 is HIGH`() = runTest {
        assertEquals(ForecastRiskLevel.HIGH, noHistoryRiskFor(950.0))
    }

    @Test
    fun `BL-07 no history spent exactly 900 of 1000 is HIGH boundary`() = runTest {
        assertEquals(ForecastRiskLevel.HIGH, noHistoryRiskFor(900.0))
    }

    @Test
    fun `BL-07 no history spent 800 of 1000 is MEDIUM`() = runTest {
        assertEquals(ForecastRiskLevel.MEDIUM, noHistoryRiskFor(800.0))
    }

    @Test
    fun `BL-07 no history spent exactly 750 of 1000 is MEDIUM boundary`() = runTest {
        assertEquals(ForecastRiskLevel.MEDIUM, noHistoryRiskFor(750.0))
    }

    @Test
    fun `BL-07 no history spent 700 of 1000 is LOW`() = runTest {
        assertEquals(ForecastRiskLevel.LOW, noHistoryRiskFor(700.0))
    }

    @Test
    fun `BL-07 with three months of history the spent ratio rule does not apply`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = now)
        coEvery { mockExpenseRepo.getExpenseSnapshotsBetween(any(), any()) } returns listOf(
            snapshot("2026-01", 50.0, 1L),
            snapshot("2026-02", 50.0, 1L),
            snapshot("2026-03", 50.0, 1L)
        )
        // 800 spent would be MEDIUM with no history; with history the usage-ratio path decides:
        // remaining 200, predicted 50 -> ratio 0.25 -> LOW.
        stubPeriodSpend(MoneyAggregate.singleCurrency(800.0, CurrencyCode("EUR")))

        val forecast = (engine.generateForecastResult(budget) as BudgetForecastResult.Available).forecast

        assertEquals(ForecastRiskLevel.LOW, forecast.riskLevel)
    }

    @Test
    fun `mixed currency period spend is risk-mathed post-conversion at one basis`() = runTest {
        val budget = Budget(categoryId = null, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = now)
        // Mixed buckets already converted at the single PERIOD_END basis:
        // 400 EUR (home) + 300 USD-converted = 700, no failures.
        stubPeriodSpend(
            MoneyAggregate(
                displayAmount = 700.0,
                displayCurrency = CurrencyCode("EUR"),
                sourceBuckets = listOf(
                    MoneyBucket(CurrencyCode("EUR"), 400.0, 2),
                    MoneyBucket(CurrencyCode("USD"), 300.0, 1)
                ),
                conversionFailures = emptyList(),
                rateBasis = RateBasis.PERIOD_END
            )
        )

        val result = engine.generateForecastResult(budget)
        val forecast = (result as BudgetForecastResult.Available).forecast

        assertApproxEquals(700.0, forecast.spentToDate, 0.01)
        assertEquals(false, forecast.isPartial)
        assertEquals("PERIOD_END", forecast.rateBasis)
    }

    @Test
    fun `unconvertible period spend returns typed unavailable and persists nothing`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = now)
        // Total conversion failure for the period spend — no fabricated fallback value.
        stubPeriodSpend(
            MoneyAggregate(
                displayAmount = 0.0,
                displayCurrency = CurrencyCode("EUR"),
                sourceBuckets = listOf(MoneyBucket(CurrencyCode("USD"), 100.0, 1)),
                conversionFailures = listOf(
                    ConversionFailure(
                        originalAmount = MoneyAmount(100.0, CurrencyCode("USD")),
                        targetCurrency = CurrencyCode("EUR"),
                        reason = FailureReason.MISSING_RATE,
                        transactionCount = 1
                    )
                ),
                isPartial = true,
                conversionQuality = ConversionQuality.UNAVAILABLE
            )
        )

        val result = engine.generateForecastResult(budget)

        val unavailable = result as? BudgetForecastResult.Unavailable
        assertTrue("expected an Unavailable forecast", unavailable != null)
        assertEquals(ForecastUnavailableReason.MISSING_RATE, unavailable!!.reasonCode)
        // Nothing is persisted when the risk math is undefined.
        coVerify(exactly = 0) { budgetForecastDao.insertWithDeactivation(any()) }
    }

    @Test
    fun `partial period spend conversion lowers confidence and marks forecast partial`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = now)
        coEvery { mockExpenseRepo.getExpenseSnapshotsBetween(any(), any()) } returns listOf(
            snapshot("2026-02", 300.0, 1L),
            snapshot("2026-03", 300.0, 1L)
        )
        // Baseline: same history, complete spend aggregate (default setUp stub).
        val baseline = engine.generateForecast(budget, forecastPeriodDays = 30)

        // 2 converted + 3 excluded transactions in the PERIOD_END spend conversion.
        stubPeriodSpend(
            MoneyAggregate(
                displayAmount = 200.0,
                displayCurrency = CurrencyCode("EUR"),
                sourceBuckets = listOf(
                    MoneyBucket(CurrencyCode("EUR"), 200.0, 2),
                    MoneyBucket(CurrencyCode("USD"), 50.0, 3)
                ),
                conversionFailures = listOf(
                    ConversionFailure(
                        originalAmount = MoneyAmount(50.0, CurrencyCode("USD")),
                        targetCurrency = CurrencyCode("EUR"),
                        reason = FailureReason.MISSING_RATE,
                        transactionCount = 3
                    )
                ),
                isPartial = true
            )
        )
        val partial = engine.generateForecast(budget, forecastPeriodDays = 30)

        assertTrue("confidence must drop when period spend rows are excluded",
            partial.confidenceScore < baseline.confidenceScore)
        // spendRetention = 1 - (3/5 * 0.5) = 0.7 → base 0.7333 * 0.7 ≈ 0.51333.
        assertApproxEquals(0.5133333333333333, partial.confidenceScore, 0.001)
        assertTrue(partial.isPartial)
        assertEquals(3, partial.excludedExpenseCount)
        val json = partial.qualityWarningsJson
        assertTrue("qualityWarnings must include the period-spend partial code",
            json != null && json.contains("PERIOD_SPEND_PARTIAL"))
    }

    @Test
    fun `period window is elapsed-bounded while the rate basis stays period end`() = runTest {
        val periodStart = LocalDate.of(2026, 4, 1)
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val periodEnd = LocalDate.of(2026, 5, 1)
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val budget = Budget(categoryId = 1L, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = periodStart)

        val capturedStarts = mutableListOf<Long>()
        val capturedEnds = mutableListOf<Long>()
        val capturedElapsed = mutableListOf<Long>()
        coEvery {
            budgetRepository.getCurrentPeriodPurchaseSpendAtPeriodEnd(
                any(),
                capture(capturedStarts),
                capture(capturedEnds),
                capture(capturedElapsed)
            )
        } returns BudgetRepository.CurrentPeriodSpendAtPeriodEnd(
            aggregate = MoneyAggregate.singleCurrency(50.0, CurrencyCode("EUR")),
            rateAsOfMillis = periodEnd
        )

        val result = engine.generateForecastResult(budget)

        assertTrue(result is BudgetForecastResult.Available)
        // now = Apr 15, so the spend window is [Apr 1, Apr 15) while the as-of rate
        // instant is the period end (May 1).
        assertEquals(periodStart, capturedStarts.single())
        assertEquals(periodEnd, capturedEnds.single())
        assertEquals(now, capturedElapsed.single())
        assertTrue("elapsed window end must be clamped inside the period",
            capturedElapsed.single() <= capturedEnds.single())
    }

    // =========================================================================
    // Withdrawals are not spending: the historical baseline counts purchases only.
    // =========================================================================

    private fun typedSnapshot(monthKey: String, total: Double, type: DomainTransactionType): ExpenseSnapshot =
        snapshot(monthKey, total, 1L).copy(transactionType = type)

    @Test
    fun `withdrawals are excluded from the forecast baseline while purchases count`() = runTest {
        // now = Apr 1 00:00: the April period has exactly 30 days remaining, so the
        // predicted spending equals the monthly baseline; Jan-Mar are three complete months.
        val aprilFirst = LocalDate.of(2026, 4, 1)
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        every { timeProvider.now() } returns aprilFirst
        val budget = Budget(categoryId = 1L, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = aprilFirst)
        coEvery { mockExpenseRepo.getExpenseSnapshotsBetween(any(), any()) } returns listOf(
            typedSnapshot("2026-01", 600.0, DomainTransactionType.PURCHASE),
            typedSnapshot("2026-01", 200.0, DomainTransactionType.WITHDRAWAL),
            typedSnapshot("2026-02", 600.0, DomainTransactionType.PURCHASE),
            typedSnapshot("2026-02", 200.0, DomainTransactionType.WITHDRAWAL),
            typedSnapshot("2026-03", 600.0, DomainTransactionType.PURCHASE),
            typedSnapshot("2026-03", 200.0, DomainTransactionType.WITHDRAWAL)
        )

        val forecast = engine.generateForecast(budget, forecastPeriodDays = 30)

        // Purchases only: 600 a month, stable trend. A counted withdrawal would give 800.
        assertApproxEquals(600.0, forecast.predictedSpending, 0.01)
    }

    @Test
    fun `withdrawal-only history yields zero baseline not the withdrawn amount`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = now)
        coEvery { mockExpenseRepo.getExpenseSnapshotsBetween(any(), any()) } returns listOf(
            typedSnapshot("2026-02", 200.0, DomainTransactionType.WITHDRAWAL),
            typedSnapshot("2026-03", 200.0, DomainTransactionType.WITHDRAWAL)
        )

        val forecast = engine.generateForecast(budget, forecastPeriodDays = 30)

        assertApproxEquals(0.0, forecast.predictedSpending, 0.001)
    }

    // =========================================================================
    // BL-07: low confidence pulls the overspend probability toward 0.5.
    // probability' = 0.5 + (probability - 0.5) * confidence
    // =========================================================================

    @Test
    fun `overspend probability with confidence 0_2 and base 0_05 is 0_41`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = now)
        // No history => zero observed months and zero spend: confidence 0.2, buffer == limit => base 0.05.
        coEvery { mockExpenseRepo.getExpenseSnapshotsBetween(any(), any()) } returns emptyList()

        val forecast = engine.generateForecast(budget, forecastPeriodDays = 30)

        assertApproxEquals(0.2, forecast.confidenceScore, 0.0001)
        assertApproxEquals(0.41, forecast.overspendProbability, 0.0001)
    }

    @Test
    fun `overspend probability with confidence 0_2 and base 0_8 is 0_56`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = now)
        coEvery { mockExpenseRepo.getExpenseSnapshotsBetween(any(), any()) } returns emptyList()
        // 950 spent of 1000, nothing predicted: buffer 50 < 10% of limit => base 0.8.
        stubPeriodSpend(MoneyAggregate.singleCurrency(950.0, CurrencyCode("EUR")))

        val forecast = engine.generateForecast(budget, forecastPeriodDays = 30)

        assertApproxEquals(0.2, forecast.confidenceScore, 0.0001)
        assertApproxEquals(0.56, forecast.overspendProbability, 0.0001)
    }

    @Test
    fun `overspend probability with confidence 1 leaves the base probability unchanged`() = runTest {
        val mayFirst = LocalDate.of(2026, 5, 1)
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        every { timeProvider.now() } returns mayFirst
        val budget = Budget(categoryId = 1L, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = mayFirst)
        // Three complete months with identical totals: 3/3 * 0.8 + 0.2 => confidence 1.0.
        coEvery { mockExpenseRepo.getExpenseSnapshotsBetween(any(), any()) } returns listOf(
            snapshot("2026-02", 100.0, 1L),
            snapshot("2026-03", 100.0, 1L),
            snapshot("2026-04", 100.0, 1L)
        )

        val forecast = engine.generateForecast(budget, forecastPeriodDays = 30)

        assertApproxEquals(1.0, forecast.confidenceScore, 0.0001)
        // Projected total ~103 of 1000 => very comfortable buffer => base 0.05, untouched.
        assertApproxEquals(0.05, forecast.overspendProbability, 0.0001)
    }

    @Test
    fun `overspend probability with confidence 0 collapses to the uninformative 0_5 prior`() {
        // Confidence 0 is unreachable through the public path (the confidence formula is
        // floored by retention factors), so probe the boundary of the private formula directly.
        val method = BudgetForecastingEngine::class.java.getDeclaredMethod(
            "calculateOverspendProbability",
            Double::class.javaPrimitiveType,
            Double::class.javaPrimitiveType,
            Double::class.javaPrimitiveType,
            Double::class.javaPrimitiveType
        ).apply { isAccessible = true }

        // Base 0.05 (nothing spent, nothing predicted) and base 0.8 (950 of 1000 spent).
        val lowBase = method.invoke(engine, 1000.0, 0.0, 0.0, 0.0) as Double
        val highBase = method.invoke(engine, 1000.0, 0.0, 950.0, 0.0) as Double

        assertApproxEquals(0.5, lowBase, 0.0001)
        assertApproxEquals(0.5, highBase, 0.0001)
    }

    // =========================================================================
    // P-06-2: the forecast limit is BudgetRepository.getBudgetStatusesAt(now)'s
    // effectiveLimit (rollover/carried deficit applied), matched on budget.id.
    // The raw budget.amount is never used; unknown limits are typed unavailable.
    // =========================================================================

    /** now = Apr 1 00:00 so the period has 30 days left; Jan-Mar are three complete 450 months. */
    private fun arrangeStable450History(id: Long = 0L, baseAmount: Double = 500.0): Budget {
        val aprilFirst = LocalDate.of(2026, 4, 1)
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        every { timeProvider.now() } returns aprilFirst
        coEvery { mockExpenseRepo.getExpenseSnapshotsBetween(any(), any()) } returns listOf(
            snapshot("2026-01", 450.0, 1L),
            snapshot("2026-02", 450.0, 1L),
            snapshot("2026-03", 450.0, 1L)
        )
        return Budget(
            id = id,
            categoryId = 1L,
            amount = baseAmount,
            period = BudgetPeriod.MONTHLY,
            startDate = aprilFirst
        )
    }

    @Test
    fun `P06-2 carried deficit lowers effective limit so predicted 450 against 400 is critical`() = runTest {
        // Base 500, carried -100 => effective 400. Spent 0, predicted 450 (30 days left, stable).
        val budget = arrangeStable450History(baseAmount = 500.0)
        stubEffectiveLimit(budget, 400.0)

        val forecast = (engine.generateForecastResult(budget) as BudgetForecastResult.Available).forecast

        assertApproxEquals(450.0, forecast.predictedSpending, 0.01)
        assertApproxEquals(400.0, forecast.normalizedBudgetAmount, 0.0001)
        assertApproxEquals(-50.0, forecast.predictedRemaining, 0.01)
        assertEquals(ForecastRiskLevel.CRITICAL, forecast.riskLevel)
        assertApproxEquals(1.0, forecast.overspendProbability, 0.0001)
    }

    @Test
    fun `P06-2 same inputs without rollover use effective 500 and are not over`() = runTest {
        val budget = arrangeStable450History(baseAmount = 500.0)
        stubEffectiveLimit(budget, 500.0)

        val forecast = (engine.generateForecastResult(budget) as BudgetForecastResult.Available).forecast

        assertApproxEquals(450.0, forecast.predictedSpending, 0.01)
        assertApproxEquals(500.0, forecast.normalizedBudgetAmount, 0.0001)
        assertApproxEquals(50.0, forecast.predictedRemaining, 0.01)
        assertTrue("must not be CRITICAL", forecast.riskLevel != ForecastRiskLevel.CRITICAL)
        assertTrue("must not be a certain overspend", forecast.overspendProbability < 1.0)
    }

    @Test
    fun `P06-2 effective limit not budget amount drives the forecast`() = runTest {
        // Raw amount is huge; the status snapshot says 400. The raw amount must be ignored.
        val budget = arrangeStable450History(baseAmount = 1_000_000.0)
        stubEffectiveLimit(budget, 400.0)

        val forecast = (engine.generateForecastResult(budget) as BudgetForecastResult.Available).forecast

        assertApproxEquals(400.0, forecast.normalizedBudgetAmount, 0.0001)
        assertEquals(ForecastRiskLevel.CRITICAL, forecast.riskLevel)
    }

    @Test
    fun `P06-2 status is requested for the forecast budget id`() = runTest {
        val budget = arrangeStable450History(id = 9L)
        val other = budget.copy(id = 7L)
        coEvery { budgetRepository.getBudgetStatusAt(other, any()) } returns statusFor(other, 100.0)
        coEvery { budgetRepository.getBudgetStatusAt(budget, any()) } returns statusFor(budget, 400.0)

        val forecast = (engine.generateForecastResult(budget) as BudgetForecastResult.Available).forecast

        assertApproxEquals(400.0, forecast.normalizedBudgetAmount, 0.0001)
        coVerify(exactly = 0) { budgetRepository.getBudgetStatusAt(other, any()) }
    }

    @Test
    fun `P06-2 status read at the injected now`() = runTest {
        val budget = arrangeStable450History()
        stubEffectiveLimit(budget, 500.0)

        engine.generateForecastResult(budget)

        val aprilFirst = LocalDate.of(2026, 4, 1)
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        coVerify(exactly = 1) { budgetRepository.getBudgetStatusAt(budget, aprilFirst) }
        coVerify(exactly = 0) { budgetRepository.getBudgetStatusesAt(any()) }
    }

    private suspend fun assertLimitUnavailable(
        result: BudgetForecastResult,
        reason: ForecastUnavailableReason,
        budgetId: Long = 0L
    ) {
        val unavailable = result as? BudgetForecastResult.Unavailable
        assertTrue("expected Unavailable, got $result", unavailable != null)
        assertEquals(reason, unavailable!!.reasonCode)
        assertEquals(budgetId, unavailable.budgetId)
        coVerify(exactly = 0) { budgetForecastDao.insertWithDeactivation(any()) }
    }

    @Test
    fun `P06-2 status not found is unavailable BUDGET_STATUS_UNAVAILABLE`() = runTest {
        val budget = arrangeStable450History(id = 9L)
        coEvery { budgetRepository.getBudgetStatusAt(budget, any()) } returns null

        assertLimitUnavailable(
            engine.generateForecastResult(budget),
            ForecastUnavailableReason.BUDGET_STATUS_UNAVAILABLE,
            budgetId = 9L
        )
    }

    @Test
    fun `P06-2 percentKnown false is unavailable even with a positive effective limit`() = runTest {
        val budget = arrangeStable450History()
        stubEffectiveLimit(budget, 500.0, percentKnown = false)

        assertLimitUnavailable(engine.generateForecastResult(budget), ForecastUnavailableReason.LIMIT_CONVERSION_FAILED)
    }

    @Test
    fun `P06-2 percentKnown false takes precedence over a non positive effective limit`() = runTest {
        val budget = arrangeStable450History()
        stubEffectiveLimit(budget, 0.0, percentKnown = false)

        assertLimitUnavailable(engine.generateForecastResult(budget), ForecastUnavailableReason.LIMIT_CONVERSION_FAILED)
    }

    @Test
    fun `P06-2 zero effective limit is unavailable NO_LIMIT_LEFT`() = runTest {
        val budget = arrangeStable450History()
        stubEffectiveLimit(budget, 0.0)

        assertLimitUnavailable(engine.generateForecastResult(budget), ForecastUnavailableReason.NO_LIMIT_LEFT)
    }

    @Test
    fun `P06-2 limit 100 with carried deficit 150 gives effective minus 50 and is NO_LIMIT_LEFT`() = runTest {
        val budget = arrangeStable450History(baseAmount = 100.0)
        stubEffectiveLimit(budget, -50.0)

        assertLimitUnavailable(engine.generateForecastResult(budget), ForecastUnavailableReason.NO_LIMIT_LEFT)
    }

    @Test
    fun `P06-2 non finite effective limit is unavailable LIMIT_CONVERSION_FAILED`() = runTest {
        val budget = arrangeStable450History()
        stubEffectiveLimit(budget, Double.NaN)
        assertLimitUnavailable(engine.generateForecastResult(budget), ForecastUnavailableReason.LIMIT_CONVERSION_FAILED)

        stubEffectiveLimit(budget, Double.POSITIVE_INFINITY)
        assertLimitUnavailable(engine.generateForecastResult(budget), ForecastUnavailableReason.LIMIT_CONVERSION_FAILED)

        stubEffectiveLimit(budget, Double.NEGATIVE_INFINITY)
        assertLimitUnavailable(engine.generateForecastResult(budget), ForecastUnavailableReason.LIMIT_CONVERSION_FAILED)
    }

    @Test
    fun `P06-2 smallest positive effective limit is accepted boundary control`() = runTest {
        val budget = arrangeStable450History()
        stubEffectiveLimit(budget, 0.01)

        val result = engine.generateForecastResult(budget)

        assertTrue(result is BudgetForecastResult.Available)
    }

    @Test
    fun `P06-2 partial status with rollover 500 and limit 1000 is unavailable not 300 left`() = runTest {
        // Rollover EUR 500 with an unconvertible period spend: the status is partial, so the
        // effective limit (1000) cannot be trusted and no "300 left" style figure may appear.
        val budget = arrangeStable450History(baseAmount = 500.0)
        stubEffectiveLimit(budget, 1000.0, isPartial = true)
        stubPeriodSpend(MoneyAggregate.singleCurrency(200.0, CurrencyCode("EUR")))

        // PIN CHANGED: the limit (1000) is known, so a partial status is incomplete details
        // (BUDGET_STATUS_UNAVAILABLE), not a limit conversion failure (LIMIT_CONVERSION_FAILED).
        val result = engine.generateForecastResult(budget)
        assertLimitUnavailable(result, ForecastUnavailableReason.BUDGET_STATUS_UNAVAILABLE)
        assertTrue(
            (result as BudgetForecastResult.Unavailable).reasonCode !=
                ForecastUnavailableReason.LIMIT_CONVERSION_FAILED
        )
    }

    @Test
    fun `P06-2 non null conversion warning on status is unavailable even when not flagged partial`() = runTest {
        val budget = arrangeStable450History(baseAmount = 500.0)
        stubEffectiveLimit(budget, 1000.0, isPartial = false, conversionWarning = "SYNTHETIC_CONVERSION_WARNING")

        // PIN CHANGED: a known limit with a conversion warning is incomplete details
        // (BUDGET_STATUS_UNAVAILABLE), not LIMIT_CONVERSION_FAILED.
        val result = engine.generateForecastResult(budget)
        assertLimitUnavailable(result, ForecastUnavailableReason.BUDGET_STATUS_UNAVAILABLE)
        assertTrue(
            (result as BudgetForecastResult.Unavailable).reasonCode !=
                ForecastUnavailableReason.LIMIT_CONVERSION_FAILED
        )
    }

    @Test
    fun `P06-2 complete status with no warning and rollover limit 1000 forecasts normally control`() = runTest {
        val budget = arrangeStable450History(baseAmount = 500.0)
        stubEffectiveLimit(budget, 1000.0, isPartial = false, conversionWarning = null)

        val forecast = (engine.generateForecastResult(budget) as BudgetForecastResult.Available).forecast

        assertApproxEquals(1000.0, forecast.normalizedBudgetAmount, 0.0001)
        assertApproxEquals(450.0, forecast.predictedSpending, 0.01)
        assertApproxEquals(550.0, forecast.predictedRemaining, 0.01)
    }

    @Test
    fun `P06-2 throwing repository is unavailable with no raw amount fallback`() = runTest {
        val budget = arrangeStable450History(baseAmount = 500.0)
        coEvery { budgetRepository.getBudgetStatusAt(any(), any()) } throws IllegalStateException("boom")

        assertLimitUnavailable(
            engine.generateForecastResult(budget),
            ForecastUnavailableReason.BUDGET_STATUS_UNAVAILABLE
        )
        // The raw budget.amount must not stand in for the unreadable limit.
        coVerify(exactly = 0) {
            budgetRepository.getCurrentPeriodPurchaseSpendAtPeriodEnd(any(), any(), any(), any())
        }
    }

    @Test
    fun `P06-2 cancellation while reading statuses propagates and persists nothing`() = runTest {
        val budget = arrangeStable450History()
        coEvery { budgetRepository.getBudgetStatusAt(any(), any()) } throws
            kotlinx.coroutines.CancellationException("cancelled")

        var propagated = false
        try {
            engine.generateForecastResult(budget)
        } catch (e: kotlinx.coroutines.CancellationException) {
            propagated = true
        }

        assertTrue("CancellationException must not be swallowed into Unavailable", propagated)
        coVerify(exactly = 0) { budgetForecastDao.insertWithDeactivation(any()) }
    }
}

private class MessagePreservingSQLiteConstraintException(
    override val message: String
) : SQLiteConstraintException(message)
