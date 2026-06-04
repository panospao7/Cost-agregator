package com.yourname.expensetracker.ui.screens.analytics

import com.yourname.expensetracker.domain.analytics.*
import com.yourname.expensetracker.domain.core.time.PeriodKind
import com.yourname.expensetracker.domain.core.time.PeriodRange
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
import com.yourname.expensetracker.domain.logic.RecurringExpenseEngine
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AnalyticsViewModelInsightsTest : ViewModelTestUtils() {

    private lateinit var expenseRepository: com.yourname.expensetracker.data.repository.ExpenseRepository
    private lateinit var categoryRepository: com.yourname.expensetracker.data.repository.CategoryRepository
    private lateinit var budgetRepository: com.yourname.expensetracker.data.repository.BudgetRepository
    private lateinit var insightsEngine: InsightsEngine
    private lateinit var recurringExpenseEngine: com.yourname.expensetracker.domain.logic.RecurringExpenseEngine
    private lateinit var analyticsRepository: com.yourname.expensetracker.data.repository.AnalyticsRepository
    private lateinit var advancedAnalyticsEngine: AdvancedAnalyticsEngine
    private lateinit var analyticsCurrencyNormalizer: AnalyticsCurrencyNormalizer
    private lateinit var locationInsightsEngine: com.yourname.expensetracker.domain.location.LocationInsightsEngine
    private lateinit var areaSpendingEngine: com.yourname.expensetracker.domain.location.AreaSpendingEngine
    private lateinit var travelDetectionEngine: com.yourname.expensetracker.domain.location.TravelDetectionEngine
    private lateinit var spendingPersonalityClassifier: SpendingPersonalityClassifier
    private lateinit var timeProvider: com.yourname.expensetracker.domain.util.TimeProvider
    private lateinit var analyticsInputAssembler: AnalyticsInputAssembler
    private lateinit var currencyConverter: com.yourname.expensetracker.domain.currency.CurrencyConverter
    private lateinit var currencySettingsRepository: com.yourname.expensetracker.TestCurrencySettingsRepository
    private lateinit var budgetVsActualEngine: BudgetVsActualEngine
    private lateinit var dailyBucketEngine: DailyBucketEngine

    private lateinit var viewModel: AnalyticsViewModel

    @Before
    override fun setup() {
        super.setup()
        expenseRepository = mockk(relaxed = true)
        categoryRepository = mockk(relaxed = true)
        budgetRepository = mockk(relaxed = true)
        insightsEngine = mockk(relaxed = true)
        recurringExpenseEngine = mockk(relaxed = true)
        analyticsRepository = mockk(relaxed = true)
        advancedAnalyticsEngine = mockk(relaxed = true)
        analyticsCurrencyNormalizer = mockk(relaxed = true)
        locationInsightsEngine = mockk(relaxed = true)
        areaSpendingEngine = mockk(relaxed = true)
        travelDetectionEngine = mockk(relaxed = true)
        spendingPersonalityClassifier = mockk(relaxed = true)
        timeProvider = mockk(relaxed = true)
        analyticsInputAssembler = mockk<AnalyticsInputAssembler>(relaxed = true)
        currencyConverter = mockk(relaxed = true)
        currencySettingsRepository = com.yourname.expensetracker.TestCurrencySettingsRepository()
        budgetVsActualEngine = mockk<BudgetVsActualEngine>(relaxed = true)
        dailyBucketEngine = mockk<DailyBucketEngine>(relaxed = true)

        every { expenseRepository.getAllExpenses() } returns flowOf(emptyList())
        every { categoryRepository.allCategories } returns flowOf(emptyList())
        every { budgetRepository.allBudgets } returns flowOf(emptyList())
        every { timeProvider.now() } returns 1704067200000L
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns emptyList()
        coEvery { recurringExpenseEngine.getPatternsFromSnapshots(any()) } returns emptyList()
        coEvery { spendingPersonalityClassifier.classify() } returns SpendingPersonalityProfile(
            personalityType = SpendingPersonalityType.BALANCED,
            confidence = 0.0,
            featureScores = emptyMap(),
            explanation = emptyList(),
            coachingTips = emptyList(),
            lastUpdated = 1704067200000L
        )
        every { locationInsightsEngine.computeNormalized(any()) } returns emptyList()
        every { areaSpendingEngine.compute(any()) } returns emptyList()
        every { travelDetectionEngine.compute(any()) } returns com.yourname.expensetracker.domain.location.TravelInsight(
            homeLatitude = null,
            homeLongitude = null,
            homeSpend = 0.0,
            localSpend = 0.0,
            travelSpend = 0.0,
            travelTrips = emptyList()
        )

        viewModel = AnalyticsViewModel(
            expenseRepository,
            categoryRepository,
            budgetRepository,
            insightsEngine,
            recurringExpenseEngine,
            analyticsRepository,
            advancedAnalyticsEngine,
            analyticsCurrencyNormalizer,
            locationInsightsEngine,
            areaSpendingEngine,
            travelDetectionEngine,
            spendingPersonalityClassifier,
            timeProvider,
            analyticsInputAssembler,
            currencyConverter,
            currencySettingsRepository,
            budgetVsActualEngine,
            dailyBucketEngine
        )
    }

    @Test
    fun `normalized generateInsights overload is called instead of legacy`() = runTest(testDispatcher) {
        // Subscribe to state flow to trigger the upstream computation
        val collectJob = launch {
            viewModel.state.collect { }
        }

        // Advance past the 300ms debounce so the computation pipeline runs
        testDispatcher.scheduler.advanceTimeBy(400)
        advanceUntilIdle()

        // The 4-param normalized overload (currentInput, historicalInput, categories, conversionWarnings) must be called
        coVerify(exactly = 1) {
            insightsEngine.generateInsights(
                any<NormalizedAnalyticsInput>(),
                any(),
                any<List<AnalyticsCategoryRef>>(),
                any<List<AnalyticsConversionWarning>>()
            )
        }

        // The 2-param normalized overload must NOT be called
        coVerify(exactly = 0) {
            insightsEngine.generateInsights(
                any<NormalizedAnalyticsInput>(),
                any<List<AnalyticsCategoryRef>>()
            )
        }

        // The legacy overload (raw expenses) must NOT be called
        coVerify(exactly = 0) {
            insightsEngine.generateInsights(
                any<List<AnalyticsCategoryRef>>(),
                any<List<ExpenseSnapshot>>(),
                any<String>(),
                any<List<AnalyticsConversionWarning>>()
            )
        }

        collectJob.cancel()
    }

    @Test
    fun `generateInsights merges expenses from both inputs and preserves explicit conversion warnings`() = runTest(testDispatcher) {
        // Build a real InsightsEngine with real sub-engines (mocked timeProvider/recurring)
        // so we can test the expense-merging and conversion-warning logic directly.
        val tp = mockk<TimeProvider>(relaxed = true)
        every { tp.now() } returns 1704067200000L
        val recurringMock = mockk<RecurringExpenseEngine>(relaxed = true)
        coEvery { recurringMock.getPatternsFromSnapshots(any()) } returns emptyList()

        val engine = InsightsEngine(
            expenseRepository = mockk(relaxed = true),
            recurringExpenseEngine = recurringMock,
            timeProvider = tp,
            spendingPaceCalculator = SpendingPaceCalculator(tp),
            anomalyDetector = AnomalyDetector(tp),
            monthlyComparisonCalculator = MonthlyComparisonCalculator(),
            categoryInsightEngine = CategoryInsightEngine(),
            merchantInsightEngine = MerchantInsightEngine(),
            dayOfWeekAnalyzer = DayOfWeekAnalyzer()
        )

        val now = 1704067200000L
        val dayMs = 86400000L
        val periodRange = PeriodRange(
            kind = PeriodKind.CUSTOM,
            startInclusiveMillis = now - 30 * dayMs,
            endExclusiveMillis = now,
            label = "TEST"
        )

        // Two expenses with different ids and in different months
        val currentExpense = NormalizedExpense(
            id = 1L,
            originalAmount = 100.0, originalEffectiveAmount = 100.0,
            originalCurrency = "EUR", normalizedAmount = 100.0,
            normalizedCurrency = "EUR", date = now - 5 * dayMs,
            merchant = "Current Merchant", merchantKey = "current_merchant",
            categoryId = 1L, categoryNameSnapshot = "Cat1",
            transactionType = "PURCHASE",
            isNotMine = false, isSharedExpense = false,
            ownershipMode = null, source = null
        )

        val historicalExpense = NormalizedExpense(
            id = 2L,
            originalAmount = 75.0, originalEffectiveAmount = 75.0,
            originalCurrency = "EUR", normalizedAmount = 75.0,
            normalizedCurrency = "EUR", date = now - 400 * dayMs,
            merchant = "Historical Merchant", merchantKey = "historical_merchant",
            categoryId = 2L, categoryNameSnapshot = "Cat2",
            transactionType = "PURCHASE",
            isNotMine = false, isSharedExpense = false,
            ownershipMode = null, source = null
        )

        val currentInput = NormalizedAnalyticsInput(
            period = periodRange,
            homeCurrency = "EUR",
            includedExpenses = listOf(currentExpense),
            dataQuality = AnalyticsDataQuality(
                conversionWarnings = listOf("Input-level warning")
            )
        )

        val historicalInput = NormalizedAnalyticsInput(
            period = null,
            homeCurrency = "EUR",
            includedExpenses = listOf(historicalExpense),
            dataQuality = AnalyticsDataQuality(
                conversionWarnings = listOf("Historical warning")
            )
        )

        val categories = listOf(
            AnalyticsCategoryRef(id = 1L, name = "Cat1", icon = "🛒", color = "#FF0000"),
            AnalyticsCategoryRef(id = 2L, name = "Cat2", icon = "🚗", color = "#00FF00")
        )

        val explicitWarnings = listOf(
            AnalyticsConversionWarning(
                type = AnalyticsConversionWarningType.STALE_EXCHANGE_RATE,
                message = "Explicit merged warning",
                affectedTransactionCount = 3
            )
        )

        val result = engine.generateInsights(
            currentInput = currentInput,
            historicalInput = historicalInput,
            categories = categories,
            conversionWarnings = explicitWarnings
        )

        // ── Assertions ────────────────────────────────────────────────

        // 1. Explicit conversion warnings must be preserved (not the input-level ones)
        assertEquals(1, result.conversionWarnings.size)
        assertEquals("Explicit merged warning", result.conversionWarnings[0].message)
        assertTrue(
            result.conversionWarnings.none { it.message == "Input-level warning" },
            "Input-level warnings should NOT appear when explicit param is passed"
        )
        assertTrue(
            result.conversionWarnings.none { it.message == "Historical warning" },
            "Historical warnings should NOT appear when explicit param is passed"
        )

        // 2. Expenses from both inputs are merged into the snapshot.
        // totalMonthsOfData counts distinct year-months across ALL snapshots.
        // With two expenses in different months (now-5d = Dec 2023, now-400d = Nov 2022),
        // there should be exactly 2 distinct months.
        assertEquals(
            2,
            result.totalMonthsOfData,
            "totalMonthsOfData should reflect expenses from both inputs"
        )

        // 3. Display currency is carried through from the input
        assertEquals("EUR", result.displayCurrency)
    }

    @Test
    fun `generateInsights with null historicalInput does not crash`() = runTest(testDispatcher) {
        val tp = mockk<TimeProvider>(relaxed = true)
        every { tp.now() } returns 1704067200000L
        val recurringMock = mockk<RecurringExpenseEngine>(relaxed = true)
        coEvery { recurringMock.getPatternsFromSnapshots(any()) } returns emptyList()

        val engine = InsightsEngine(
            expenseRepository = mockk(relaxed = true),
            recurringExpenseEngine = recurringMock,
            timeProvider = tp,
            spendingPaceCalculator = SpendingPaceCalculator(tp),
            anomalyDetector = AnomalyDetector(tp),
            monthlyComparisonCalculator = MonthlyComparisonCalculator(),
            categoryInsightEngine = CategoryInsightEngine(),
            merchantInsightEngine = MerchantInsightEngine(),
            dayOfWeekAnalyzer = DayOfWeekAnalyzer()
        )

        val now = 1704067200000L
        val dayMs = 86400000L

        val currentExpense = NormalizedExpense(
            id = 10L,
            originalAmount = 50.0, originalEffectiveAmount = 50.0,
            originalCurrency = "USD", normalizedAmount = 50.0,
            normalizedCurrency = "USD", date = now - 5 * dayMs,
            merchant = "Test Merchant", merchantKey = "test_merchant",
            categoryId = 1L, categoryNameSnapshot = "Cat1",
            transactionType = "PURCHASE",
            isNotMine = false, isSharedExpense = false,
            ownershipMode = null, source = null
        )

        val currentInput = NormalizedAnalyticsInput(
            period = PeriodRange(
                PeriodKind.CUSTOM,
                startInclusiveMillis = now - 30 * dayMs,
                endExclusiveMillis = now,
                label = "TEST"
            ),
            homeCurrency = "USD",
            includedExpenses = listOf(currentExpense),
            dataQuality = AnalyticsDataQuality()
        )

        val categories = listOf(
            AnalyticsCategoryRef(id = 1L, name = "Cat1", icon = "🛒", color = "#FF0000")
        )

        val conversionWarnings = listOf(
            AnalyticsConversionWarning(
                type = AnalyticsConversionWarningType.STALE_EXCHANGE_RATE,
                message = "Warning from null historical test",
                affectedTransactionCount = 1
            )
        )

        val result = engine.generateInsights(
            currentInput = currentInput,
            historicalInput = null,
            categories = categories,
            conversionWarnings = conversionWarnings
        )

        assertNotNull(result, "Result must not be null when historicalInput is null")
        assertEquals(1, result.totalMonthsOfData, "totalMonthsOfData should only reflect current input expenses")
        assertTrue(result.totalMonthsOfData > 0, "totalMonthsOfData must be positive with current expenses present")
    }
}
