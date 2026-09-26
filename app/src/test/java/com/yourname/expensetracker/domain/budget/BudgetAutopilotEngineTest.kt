package com.yourname.expensetracker.domain.budget

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.dao.BudgetForecastDao
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.domain.forecasting.MonteCarloSpendingSimulator
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.BudgetTrend
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.domain.analytics.InsightsEngine
import com.yourname.expensetracker.domain.analytics.SpendingPaceCalculator
import com.yourname.expensetracker.domain.core.money.CategoryMonthlySpend
import com.yourname.expensetracker.domain.core.money.ConversionFailure
import com.yourname.expensetracker.domain.core.money.ConversionFailureType
import com.yourname.expensetracker.domain.core.money.ConversionOutcome
import com.yourname.expensetracker.domain.core.money.ConversionPath
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.FailureReason
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.core.money.MoneyBucket
import com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableException
import com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableReasonCode
import com.yourname.expensetracker.domain.core.money.RateBasis
import com.yourname.expensetracker.domain.core.money.SpendScope
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

/**
 * RP-08 (slice C2): BudgetAutopilotEngine contract tests.
 *
 * The engine no longer reflects on MultiCurrencyRepository's private DAO nor
 * calls the deprecated ExpenseDao aggregate methods; it consumes the typed
 * [MultiCurrencyRepository.getHistoricalCategoryMonthlySpend] API directly
 * (P6-002/P6-003). The old 85.0 empty-history pin is invalidated per RP-08
 * plan line 174: empty/low history now yields an identity recommendation with
 * LOW_HISTORY quality and isActionable=false (P6-004).
 */
class BudgetAutopilotEngineTest {

    private lateinit var budgetRepository: BudgetRepository
    private lateinit var expenseDao: com.yourname.expensetracker.data.database.dao.ExpenseDao
    private lateinit var categoryRepository: CategoryRepository
    private lateinit var insightsEngine: InsightsEngine
    private lateinit var spendingPaceCalculator: SpendingPaceCalculator
    private lateinit var monteCarloSimulator: MonteCarloSpendingSimulator
    private lateinit var timeProvider: TimeProvider
    private lateinit var multiCurrencyRepository: MultiCurrencyRepository
    private lateinit var currencySettingsRepository: CurrencySettingsRepository
    private lateinit var currencyConverter: CurrencyConverter

    private lateinit var engine: BudgetAutopilotEngine

    private val now = millis(2026, Calendar.APRIL, 15)
    private val dayMs = 24L * 60L * 60L * 1000L

    @Before
    fun setup() {
        budgetRepository = mockk()
        expenseDao = mockk(relaxed = true)
        categoryRepository = mockk()
        insightsEngine = mockk(relaxed = true)
        spendingPaceCalculator = mockk(relaxed = true)
        monteCarloSimulator = mockk(relaxed = true)
        timeProvider = mockk()

        every { timeProvider.now() } returns now
        every { categoryRepository.allCategories } returns flowOf(
            listOf(
                Category(id = 1L, name = "Food", icon = "🍽️", color = "#FF5733"),
                Category(id = 2L, name = "Travel", icon = "✈️", color = "#3357FF")
            )
        )

        coEvery { budgetRepository.getActiveBudgets() } returns emptyList()

        // RP-09 9c: the forecasting engine sources period spend through the
        // repository's period-end aggregate; the strict mock needs a default
        // empty COMPLETE aggregate (spentToDate = 0.0, matching pre-RP-09
        // behavior) or uncoached generateForecast paths error out.
        coEvery {
            budgetRepository.getCurrentPeriodPurchaseSpendAtPeriodEnd(any(), any(), any(), any())
        } returns BudgetRepository.CurrentPeriodSpendAtPeriodEnd(
            aggregate = MoneyAggregate.empty(CurrencyCode("EUR"), RateBasis.PERIOD_END),
            rateAsOfMillis = now
        )

        currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true).also {
            coEvery { it.resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        }
        currencyConverter = mockk()
        coEvery {
            currencyConverter.convertOutcome(
                any(), any(), any(), RateBasis.LATEST_AVAILABLE, null, any()
            )
        } answers {
            val amount = firstArg<Double>()
            val source = secondArg<String>().uppercase()
            val target = thirdArg<String>().uppercase()
            ConversionOutcome.Converted(
                originalAmount = amount,
                originalCurrency = CurrencyCode(source),
                convertedAmount = amount,
                targetCurrency = CurrencyCode(target),
                rateUsed = 1.0,
                rateBasis = if (source == target) RateBasis.IDENTITY else RateBasis.LATEST_AVAILABLE,
                rateValidDate = null,
                rateLastUpdated = now,
                rateSource = null,
                conversionPath = if (source == target) ConversionPath.IDENTITY else ConversionPath.DIRECT
            )
        }
        every { currencyConverter.reverseDisplayQuote(any(), any()) } answers {
            val displayAmount = firstArg<Double>()
            val forward = secondArg<ConversionOutcome.Converted>()
            ConversionOutcome.Converted(
                originalAmount = displayAmount,
                originalCurrency = forward.targetCurrency,
                convertedAmount = displayAmount / forward.rateUsed,
                targetCurrency = forward.originalCurrency,
                rateUsed = 1.0 / forward.rateUsed,
                rateBasis = forward.rateBasis,
                rateValidDate = forward.rateValidDate,
                rateLastUpdated = forward.rateLastUpdated,
                rateSource = forward.rateSource,
                conversionPath = forward.conversionPath
            )
        }

        // The engine consumes the typed repository API directly — stub it as a
        // mock (P6-002: no reflection, no real MultiCurrencyRepository needed).
        multiCurrencyRepository = mockk()
        coEvery { multiCurrencyRepository.getHistoricalCategoryMonthlySpend(any(), any()) } returns emptyList()

        engine = BudgetAutopilotEngine(
            budgetRepository = budgetRepository,
            multiCurrencyRepository = multiCurrencyRepository,
            currencySettingsRepository = currencySettingsRepository,
            currencyConverter = currencyConverter,
            categoryRepository = categoryRepository,
            insightsEngine = insightsEngine,
            spendingPaceCalculator = spendingPaceCalculator,
            monteCarloSimulator = monteCarloSimulator,
            timeProvider = timeProvider
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** Stub the C1 repository API with EUR rows for one scope. */
    private fun stubHistory(scope: SpendScope, rows: List<Pair<String, MoneyAggregate>>) {
        coEvery {
            multiCurrencyRepository.getHistoricalCategoryMonthlySpend(any(), any())
        } answers {
            val start = firstArg<Long>()
            val end = secondArg<Long>()
            if (end <= start) {
                emptyList()
            } else {
                rows.map { (monthKey, aggregate) ->
                    CategoryMonthlySpend(scope = scope, monthKey = monthKey, aggregate = aggregate)
                }
            }
        }
    }

    private fun completeAggregate(amount: Double): MoneyAggregate = MoneyAggregate.singleCurrency(
        amount = amount,
        currency = CurrencyCode("EUR"),
        transactionCount = 1
    )

    private fun partialAggregate(included: Double): MoneyAggregate = MoneyAggregate.partial(
        displayAmount = included,
        displayCurrency = CurrencyCode("EUR"),
        sourceBuckets = listOf(MoneyBucket(CurrencyCode("EUR"), included, 1)),
        failures = listOf(
            ConversionFailure(
                originalAmount = com.yourname.expensetracker.domain.core.money.MoneyAmount(
                    1000.0,
                    CurrencyCode("JPY")
                ),
                targetCurrency = CurrencyCode("EUR"),
                reason = FailureReason.MISSING_RATE,
                transactionCount = 1
            )
        ),
        rateBasis = RateBasis.TRANSACTION_DATE
    )

    // ── P6-004 low-history contract ─────────────────────────────────────────

    @Test
    fun `empty history keeps current budget as identity with LOW_HISTORY and not actionable`() = runTest {
        coEvery { budgetRepository.getActiveBudgets() } returns listOf(
            budget(id = 1L, categoryId = 1L, amount = 100.0)
        )
        stubHistory(SpendScope.Category(1L), emptyList())

        val rec = engine.generateRecommendations().categoryRecommendations.single()

        // RP-08 plan line 174: the old 85.0 (-15% ratchet) pin is invalid.
        assertApproxEquals(100.0, rec.recommendedBudget, 0.01)
        assertApproxEquals(0.0, rec.delta, 0.01)
        assertEquals(BudgetRecommendationQuality.LOW_HISTORY, rec.quality)
        assertFalse(rec.isActionable)
        assertEquals(BudgetTrend.STABLE, rec.trend)
    }

    @Test
    fun `single complete month keeps current budget as identity with LOW_HISTORY`() = runTest {
        coEvery { budgetRepository.getActiveBudgets() } returns listOf(
            budget(id = 1L, categoryId = 1L, amount = 100.0)
        )
        // April window edge is mid-month, so April is not a complete month;
        // only March counts → 1 complete month < 2 → LOW_HISTORY.
        val marchKey = TimePeriodUtils.formatMonthKey(TimePeriodUtils.addMonths(now, -1))
        stubHistory(SpendScope.Category(1L), listOf(marchKey to completeAggregate(120.0)))

        val rec = engine.generateRecommendations().categoryRecommendations.single()

        assertApproxEquals(100.0, rec.recommendedBudget, 0.01)
        assertEquals(BudgetRecommendationQuality.LOW_HISTORY, rec.quality)
        assertFalse(rec.isActionable)
    }

    @Test
    fun `two complete months exercise the bounded adjustment path`() = runTest {
        coEvery { budgetRepository.getActiveBudgets() } returns listOf(
            budget(id = 1L, categoryId = 1L, amount = 100.0)
        )
        // Feb + Mar are fully covered by [threeMonthsAgo, Apr 15); April is not.
        val febKey = TimePeriodUtils.formatMonthKey(TimePeriodUtils.addMonths(now, -2))
        val marKey = TimePeriodUtils.formatMonthKey(TimePeriodUtils.addMonths(now, -1))
        stubHistory(
            SpendScope.Category(1L),
            listOf(febKey to completeAggregate(300.0), marKey to completeAggregate(300.0))
        )

        val rec = engine.generateRecommendations().categoryRecommendations.single()

        // Stable flat history → average 300 → capped at +15% = 115.
        assertApproxEquals(115.0, rec.recommendedBudget, 0.01)
        assertEquals(BudgetRecommendationQuality.COMPLETE, rec.quality)
        assertTrue(rec.isActionable)
    }

    @Test
    fun `display recommendations reverse through the captured quote into source budget units`() = runTest {
        coEvery { budgetRepository.getActiveBudgets() } returns listOf(
            budget(id = 1L, categoryId = 1L, amount = 100.0, currency = "USD")
        )
        coEvery {
            currencyConverter.convertOutcome(
                100.0,
                "USD",
                "EUR",
                RateBasis.LATEST_AVAILABLE,
                null,
                any()
            )
        } returns ConversionOutcome.Converted(
            originalAmount = 100.0,
            originalCurrency = CurrencyCode("USD"),
            convertedAmount = 90.0,
            targetCurrency = CurrencyCode("EUR"),
            rateUsed = 0.9,
            rateBasis = RateBasis.LATEST_AVAILABLE,
            rateValidDate = null,
            rateLastUpdated = now,
            rateSource = "test",
            conversionPath = ConversionPath.DIRECT
        )
        val febKey = TimePeriodUtils.formatMonthKey(TimePeriodUtils.addMonths(now, -2))
        val marKey = TimePeriodUtils.formatMonthKey(TimePeriodUtils.addMonths(now, -1))
        stubHistory(
            SpendScope.Category(1L),
            listOf(febKey to completeAggregate(90.0), marKey to completeAggregate(90.0))
        )

        val result = engine.generateRecommendations()
        val recommendation = result.categoryRecommendations.single()

        assertEquals("EUR", result.displayCurrency)
        assertEquals("EUR", recommendation.displayCurrency)
        assertApproxEquals(90.0, recommendation.currentBudget, 0.0001)
        assertApproxEquals(90.0, recommendation.recommendedBudget, 0.0001)
        assertApproxEquals(100.0, recommendation.sourceAmountToApply.amount, 0.0001)
        assertEquals(CurrencyCode("USD"), recommendation.sourceAmountToApply.currency)
        coVerify(exactly = 1) {
            currencyConverter.convertOutcome(
                100.0,
                "USD",
                "EUR",
                RateBasis.LATEST_AVAILABLE,
                null,
                any()
            )
        }
    }

    @Test
    fun `missing display quote fails closed before exposing recommendations`() = runTest {
        coEvery { budgetRepository.getActiveBudgets() } returns listOf(
            budget(id = 1L, categoryId = 1L, amount = 100.0, currency = "USD")
        )
        coEvery {
            currencyConverter.convertOutcome(
                100.0,
                "USD",
                "EUR",
                RateBasis.LATEST_AVAILABLE,
                null,
                any()
            )
        } returns ConversionOutcome.Failed(
            originalAmount = 100.0,
            originalCurrency = "USD",
            targetCurrency = "EUR",
            rateBasis = RateBasis.LATEST_AVAILABLE,
            failureType = ConversionFailureType.MISSING_RATE,
            message = "MISSING_RATE"
        )

        val failure = runCatching { engine.generateRecommendations() }.exceptionOrNull()

        assertTrue(failure is MoneyDisplayUnavailableException)
        assertEquals(
            MoneyDisplayUnavailableReasonCode.DISPLAY_CONVERSION_UNAVAILABLE,
            (failure as MoneyDisplayUnavailableException).reasonCode
        )
    }

    @Test
    fun `history in a stale display currency fails closed`() = runTest {
        coEvery { budgetRepository.getActiveBudgets() } returns listOf(
            budget(id = 1L, categoryId = 1L, amount = 100.0)
        )
        coEvery {
            multiCurrencyRepository.getHistoricalCategoryMonthlySpend(any(), any())
        } returns listOf(
            CategoryMonthlySpend(
                scope = SpendScope.Category(1L),
                monthKey = "2026-03",
                aggregate = MoneyAggregate.singleCurrency(
                    amount = 100.0,
                    currency = CurrencyCode("USD"),
                    transactionCount = 1
                )
            )
        )

        val failure = runCatching { engine.generateRecommendations() }.exceptionOrNull()

        assertTrue(failure is MoneyDisplayUnavailableException)
        assertEquals(
            MoneyDisplayUnavailableReasonCode.DISPLAY_CONVERSION_UNAVAILABLE,
            (failure as MoneyDisplayUnavailableException).reasonCode
        )
        coVerify(exactly = 0) {
            currencyConverter.convertOutcome(any(), any(), any(), any(), any(), any())
        }
    }

    // ── P6-003 partial-data contract ────────────────────────────────────────

    @Test
    fun `partial aggregate from conversion failure is PARTIAL_DATA and keeps the bounded floor`() = runTest {
        coEvery { budgetRepository.getActiveBudgets() } returns listOf(
            budget(id = 1L, categoryId = 1L, amount = 100.0)
        )
        // Two complete months where every month had a failed conversion: the
        // partial aggregates still count as observed spend — they must NOT be
        // read as zero spend nor trigger LOW_HISTORY.
        val febKey = TimePeriodUtils.formatMonthKey(TimePeriodUtils.addMonths(now, -2))
        val marKey = TimePeriodUtils.formatMonthKey(TimePeriodUtils.addMonths(now, -1))
        stubHistory(
            SpendScope.Category(1L),
            listOf(febKey to partialAggregate(50.0), marKey to partialAggregate(50.0))
        )

        val rec = engine.generateRecommendations().categoryRecommendations.single()

        assertEquals(BudgetRecommendationQuality.PARTIAL_DATA, rec.quality)
        assertTrue(rec.isActionable)
        // The bounded recommendation is retained, not the identity value: the
        // flat 50/month history drives the normal path and the −15% delta cap
        // floors the recommendation at 85.0 — pinning the floor catches any
        // coercion regression (e.g. an accidental LOW_HISTORY identity at 100.0).
        assertApproxEquals(85.0, rec.recommendedBudget, 0.01)
    }

    // ── scope routing (P6-002) ──────────────────────────────────────────────

    @Test
    fun `overall budget reads SpendScope Overall rows`() = runTest {
        coEvery { budgetRepository.getActiveBudgets() } returns listOf(
            budget(id = 1L, categoryId = null, amount = 1000.0)
        )
        val febKey = TimePeriodUtils.formatMonthKey(TimePeriodUtils.addMonths(now, -2))
        val marKey = TimePeriodUtils.formatMonthKey(TimePeriodUtils.addMonths(now, -1))
        stubHistory(
            SpendScope.Overall,
            listOf(febKey to completeAggregate(1000.0), marKey to completeAggregate(1000.0))
        )

        val rec = engine.generateRecommendations().categoryRecommendations.single()

        assertEquals(BudgetTrend.STABLE, rec.trend)
        assertApproxEquals(1000.0, rec.recommendedBudget, 0.01)
        assertEquals(BudgetRecommendationQuality.COMPLETE, rec.quality)
    }

    @Test
    fun `category budget reads its own scope not Overall`() = runTest {
        coEvery { budgetRepository.getActiveBudgets() } returns listOf(
            budget(id = 1L, categoryId = 1L, amount = 100.0)
        )
        val febKey = TimePeriodUtils.formatMonthKey(TimePeriodUtils.addMonths(now, -2))
        val marKey = TimePeriodUtils.formatMonthKey(TimePeriodUtils.addMonths(now, -1))
        // Only Overall rows exist; the category scope must see nothing → LOW_HISTORY identity.
        stubHistory(
            SpendScope.Overall,
            listOf(febKey to completeAggregate(500.0), marKey to completeAggregate(500.0))
        )

        val rec = engine.generateRecommendations().categoryRecommendations.single()

        assertApproxEquals(100.0, rec.recommendedBudget, 0.01)
        assertEquals(BudgetRecommendationQuality.LOW_HISTORY, rec.quality)
        assertFalse(rec.isActionable)
    }

    // ── typed failure propagation (P6-002) ──────────────────────────────────

    @Test
    fun `repository typed failure propagates and is never swallowed to empty history`() = runTest {
        coEvery { budgetRepository.getActiveBudgets() } returns listOf(
            budget(id = 1L, categoryId = 1L, amount = 100.0)
        )
        coEvery {
            multiCurrencyRepository.getHistoricalCategoryMonthlySpend(any(), any())
        } throws com.yourname.expensetracker.data.repository.HomeCurrencyUnavailableException("no home currency")

        var thrown: Throwable? = null
        try {
            engine.generateRecommendations()
        } catch (t: Throwable) {
            thrown = t
        }

        assertTrue(
            "typed repository failure must propagate",
            thrown is com.yourname.expensetracker.data.repository.HomeCurrencyUnavailableException
        )
    }

    // ── regression: summary & caps unchanged ────────────────────────────────

    @Test
    fun `generateRecommendations edge case empty budgets returns empty recommendations`() = runTest {
        coEvery { budgetRepository.getActiveBudgets() } returns emptyList()

        val result = engine.generateRecommendations()

        assertTrue(result.categoryRecommendations.isEmpty())
        assertApproxEquals(0.0, result.totalCurrentBudget, 0.01)
        assertApproxEquals(0.0, result.totalRecommendedBudget, 0.01)
        assertApproxEquals(0.0, result.overallDelta, 0.01)
        assertApproxEquals(0.0, result.confidence, 0.01)
    }

    @Test
    fun `generateRecommendations with zero current budget uses safe initial budget phrasing`() = runTest {
        coEvery { budgetRepository.getActiveBudgets() } returns listOf(
            budget(id = 1L, categoryId = 1L, amount = 0.0)
        )
        val febKey = TimePeriodUtils.formatMonthKey(TimePeriodUtils.addMonths(now, -2))
        val marKey = TimePeriodUtils.formatMonthKey(TimePeriodUtils.addMonths(now, -1))
        stubHistory(
            SpendScope.Category(1L),
            listOf(febKey to completeAggregate(100.0), marKey to completeAggregate(300.0))
        )

        val rec = engine.generateRecommendations().categoryRecommendations.single()

        assertEquals(BudgetTrend.INCREASING, rec.trend)
        assertTrue(rec.reason.contains("setting an initial budget", ignoreCase = true))
        assertTrue(!rec.reason.contains("NaN"))
        assertTrue(!rec.reason.contains("Infinity"))
    }

    @Test
    fun `generateRecommendations enforces plus fifteen percent delta cap with complete history`() = runTest {
        coEvery { budgetRepository.getActiveBudgets() } returns listOf(
            budget(id = 1L, categoryId = 1L, amount = 100.0)
        )
        val febKey = TimePeriodUtils.formatMonthKey(TimePeriodUtils.addMonths(now, -2))
        val marKey = TimePeriodUtils.formatMonthKey(TimePeriodUtils.addMonths(now, -1))
        stubHistory(
            SpendScope.Category(1L),
            listOf(febKey to completeAggregate(300.0), marKey to completeAggregate(300.0))
        )

        val rec = engine.generateRecommendations().categoryRecommendations.single()

        assertApproxEquals(115.0, rec.recommendedBudget, 0.01)
        assertEquals(BudgetRecommendationQuality.COMPLETE, rec.quality)
    }

    // ── parity: autopilot & forecasting share the same series semantics ─────

    @Test
    fun `autopilot history series and forecasting series agree for same input`() = runTest {
        val parityNow = millis(2026, Calendar.APRIL, 1) - (2L * 60L * 60L * 1000L) // 2026-04-01 10:00
        every { timeProvider.now() } returns parityNow

        val budget = budget(id = 1L, categoryId = 1L, amount = 100.0)
        coEvery { budgetRepository.getActiveBudgets() } returns listOf(budget)

        // Feb + Mar complete; April (mid-month edge) is excluded by both engines.
        val febKey = TimePeriodUtils.formatMonthKey(TimePeriodUtils.addMonths(parityNow, -2))
        val marKey = TimePeriodUtils.formatMonthKey(TimePeriodUtils.addMonths(parityNow, -1))
        stubHistory(
            SpendScope.Category(1L),
            listOf(febKey to completeAggregate(100.0), marKey to completeAggregate(100.0))
        )

        val autopilotRecommendation = engine.generateRecommendations().categoryRecommendations.single()

        val budgetForecastDao = mockk<BudgetForecastDao>(relaxed = true)
        coEvery { budgetForecastDao.insertWithDeactivation(any()) } returns 1L
        // Pass-through normalizer so snapshots flow into monthly buckets unchanged.
        val normalizer = mockk<com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer>(relaxed = true)
        coEvery { normalizer.normalizeSnapshots(any(), any()) } answers {
            val expenses = firstArg<List<com.yourname.expensetracker.domain.model.ExpenseSnapshot>>()
            com.yourname.expensetracker.domain.analytics.AnalyticsNormalizationResult(
                homeCurrency = secondArg(),
                normalizedExpenses = expenses.map {
                    com.yourname.expensetracker.domain.analytics.NormalizedExpenseSnapshot(
                        it, it.currency, it.effectiveAmount, it.effectiveAmount
                    )
                },
                includedExpenses = expenses,
                warnings = emptyList(),
                latestRateTimestamp = null,
                totalInputCount = expenses.size
            )
        }
        // Same window semantics for the forecasting engine: Feb+Mar snapshots
        // only; April has no snapshot so no partial trailing bucket arises.
        coEvery { forecastingExpenseRepo.getExpenseSnapshotsBetween(any(), any()) } returns listOf(
            snapshot(febKey, 100.0, 1L),
            snapshot(marKey, 100.0, 1L)
        )
        val forecastingEngine = BudgetForecastingEngine(
            expenseDao = expenseDao,
            budgetRepository = budgetRepository,
            budgetForecastDao = budgetForecastDao,
            timeProvider = timeProvider,
            ioDispatcher = Dispatchers.Unconfined,
            analyticsCurrencyNormalizer = normalizer,
            expenseRepository = forecastingExpenseRepo,
            currencySettingsRepository = mockk<CurrencySettingsRepository>().also {
                every { it.homeCurrency() } returns flowOf("EUR")
                coEvery { it.resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
            },
            currencyConverter = mockk(relaxed = true),
            writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true),
        )
        val forecast = forecastingEngine.generateForecast(budget)

        // Both engines must agree on the same two complete months → same
        // monthly average → comparable predicted spending.
        val windowStart = TimePeriodUtils.addMonths(parityNow, -3)
        val normalized = BudgetHistorySeriesBuilder.build(
            monthlyTotals = listOf(
                com.yourname.expensetracker.data.database.dao.MonthlySpendingTotal(febKey, 100.0, 1),
                com.yourname.expensetracker.data.database.dao.MonthlySpendingTotal(marKey, 100.0, 1)
            ),
            windowStartInclusive = windowStart,
            windowEndExclusive = parityNow
        )
        assertEquals(listOf(febKey, marKey), normalized.monthKeys)
        assertEquals(2, normalized.completeMonthCount)

        val (_, periodEnd) = BudgetCalculator(timeProvider).calculatePeriodRange(budget, parityNow)
        // The engine derives remaining months via integer calendar days
        // (TimePeriodUtils.daysBetween(elapsedEnd, periodEnd) / 30.0).
        val forecastMonths = TimePeriodUtils.daysBetween(parityNow, periodEnd).coerceAtLeast(0).toDouble() / 30.0
        val trendMultiplier = when (autopilotRecommendation.trend) {
            BudgetTrend.INCREASING -> 1.1
            BudgetTrend.DECREASING -> 0.9
            BudgetTrend.STABLE -> 1.0
        }
        val expectedFromSharedSeries = normalized.values.average() * forecastMonths * trendMultiplier

        assertEquals(BudgetTrend.STABLE, autopilotRecommendation.trend)
        assertApproxEquals(expectedFromSharedSeries, forecast.predictedSpending, 0.01)
        assertApproxEquals(100.0, normalized.values.average(), 0.0001)
    }

    private val forecastingExpenseRepo: com.yourname.expensetracker.data.repository.ExpenseRepository =
        mockk(relaxed = true)

    private fun snapshot(monthKey: String, total: Double, categoryId: Long?): com.yourname.expensetracker.domain.model.ExpenseSnapshot {
        val parts = monthKey.split("-")
        val date = java.time.LocalDate.of(parts[0].toInt(), parts[1].toInt(), 15)
            .atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        return com.yourname.expensetracker.domain.model.ExpenseSnapshot(
            id = 0L,
            amount = total,
            effectiveAmount = total,
            currency = "EUR",
            merchant = "Test",
            merchantKey = null,
            transactionType = com.yourname.expensetracker.domain.model.DomainTransactionType.PURCHASE,
            date = date,
            categoryId = categoryId,
            isNotMine = false,
            transferDirection = null,
            notes = null
        )
    }

    private fun budget(
        id: Long,
        categoryId: Long?,
        amount: Double,
        currency: String = "EUR"
    ): Budget {
        return Budget(
            id = id,
            categoryId = categoryId,
            amount = amount,
            period = BudgetPeriod.MONTHLY,
            startDate = now - 60 * dayMs,
            currency = currency
        )
    }

    private fun millis(year: Int, month: Int, day: Int): Long {
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
