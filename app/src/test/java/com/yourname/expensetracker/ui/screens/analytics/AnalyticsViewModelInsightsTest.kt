package com.yourname.expensetracker.ui.screens.analytics

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.analytics.*
import com.yourname.expensetracker.domain.core.time.PeriodKind
import com.yourname.expensetracker.domain.core.time.PeriodRange
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
import com.yourname.expensetracker.domain.logic.RecurringExpenseEngine
import com.yourname.expensetracker.domain.util.TimePeriodUtils
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
        coEvery { spendingPersonalityClassifier.classify(any<NormalizedAnalyticsInput>()) } returns SpendingPersonalityProfile(
            personalityType = SpendingPersonalityType.BALANCED,
            confidence = 0.0,
            featureScores = emptyMap(),
            explanation = emptyList(),
            coachingTips = emptyList(),
            lastUpdated = 1704067200000L
        )
        every { locationInsightsEngine.computeNormalized(any()) } returns emptyList()
        coEvery { areaSpendingEngine.computeNormalized(any(), any(), any()) } returns emptyList()
        coEvery { travelDetectionEngine.computeNormalized(any(), any(), any()) } returns com.yourname.expensetracker.domain.location.NormalizedTravelInsight(
            homeLatitude = null,
            homeLongitude = null,
            homeAggregate = com.yourname.expensetracker.domain.core.money.MoneyAggregate.empty(com.yourname.expensetracker.domain.core.money.CurrencyCode.EUR),
            localAggregate = com.yourname.expensetracker.domain.core.money.MoneyAggregate.empty(com.yourname.expensetracker.domain.core.money.CurrencyCode.EUR),
            travelAggregate = com.yourname.expensetracker.domain.core.money.MoneyAggregate.empty(com.yourname.expensetracker.domain.core.money.CurrencyCode.EUR),
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

    // =========================================================================
    // PR4 — Period-aware behavior tests
    // =========================================================================

    @Test
    fun `weekInsightsUseSelectedWeekNotCurrentMonth`() = runTest(testDispatcher) {
        // Fix "now" to 2024-01-15 12:00 UTC (a Monday)
        val now = 1705320000000L // 2024-01-15 12:00:00 UTC
        every { timeProvider.now() } returns now

        // Create two expenses: one OUTSIDE the selected week (prev week, Wed Jan 10)
        // and one INSIDE the selected week (Tue Jan 16)
        val outsideWeekExpense = Expense(
            id = 100L, amount = 25.0, currency = "EUR",
            merchant = "OutsideWeekMerchant",
            transactionType = TransactionType.PURCHASE,
            date = 1704844800000L, // 2024-01-10 00:00 UTC (Wed, previous week)
            categoryId = 1L
        )
        val insideWeekExpense = Expense(
            id = 200L, amount = 50.0, currency = "EUR",
            merchant = "InsideWeekMerchant",
            transactionType = TransactionType.PURCHASE,
            date = 1705363200000L, // 2024-01-16 00:00 UTC (Tue, selected week)
            categoryId = 2L
        )
        val allExpenses = listOf(outsideWeekExpense, insideWeekExpense)

        // getAllExpenses must emit so the combine flow triggers
        every { expenseRepository.getAllExpenses() } returns flowOf(allExpenses)
        // getExpensesBetween returns all expenses regardless of range (we verify via captured period)
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns allExpenses

        // Capture the period passed to analyticsInputAssembler.build and echo it back
        val periodSlot = slot<PeriodRange>()
        coEvery { analyticsInputAssembler.build(any<List<Expense>>(), any<String>(), capture(periodSlot), any()) } answers {
            val capturedPeriod = periodSlot.captured
            NormalizedAnalyticsInput(
                period = capturedPeriod,
                homeCurrency = "EUR",
                includedExpenses = firstArg<List<Expense>>().map { exp ->
                    NormalizedExpense(
                        id = exp.id,
                        originalAmount = exp.amount,
                        originalEffectiveAmount = exp.amount,
                        originalCurrency = exp.currency,
                        normalizedAmount = exp.amount,
                        normalizedCurrency = "EUR",
                        date = exp.date,
                        merchant = exp.merchant,
                        merchantKey = null,
                        categoryId = exp.categoryId,
                        categoryNameSnapshot = null,
                        transactionType = "PURCHASE",
                        isNotMine = false,
                        isSharedExpense = false,
                        ownershipMode = null,
                        source = null
                    )
                }
            )
        }

        // Select WEEK period
        viewModel.selectPeriod(com.yourname.expensetracker.domain.analytics.TimePeriod.WEEK)

        // Subscribe to trigger computation
        val collectJob = launch { viewModel.state.collect { } }
        testDispatcher.scheduler.advanceTimeBy(400)
        advanceUntilIdle()

        // Compute the expected week range using the same utility the ViewModel uses
        val (expectedWeekStart, expectedWeekEnd) = TimePeriodUtils.getWeekRange(now, 0)

        // Verify the insights engine received a NormalizedAnalyticsInput whose period
        // matches the selected WEEK range (not the full month range)
        val inputSlot = slot<NormalizedAnalyticsInput>()
        coVerify {
            insightsEngine.generateInsights(capture(inputSlot), any(), any<List<AnalyticsCategoryRef>>(), any())
        }

        val capturedInput = inputSlot.captured
        val capturedPeriod = capturedInput.period
        assertNotNull(capturedPeriod, "currentInput.period must not be null")
        assertEquals(
            expectedWeekStart, capturedPeriod.startInclusiveMillis,
            "Period start should match selected week start, not month start"
        )
        assertEquals(
            expectedWeekEnd, capturedPeriod.endExclusiveMillis,
            "Period end should match selected week end, not month end"
        )
        assertEquals(
            PeriodKind.CUSTOM, capturedPeriod.kind,
            "ViewModel passes CUSTOM kind for all period types"
        )
        assertEquals("WEEK", capturedPeriod.label)

        collectJob.cancel()
    }

    @Test
    fun `yearInsightsUseSelectedYearNotCurrentMonth`() = runTest(testDispatcher) {
        // Fix "now" to 2024-06-15 12:00 UTC
        val now = 1718452800000L // 2024-06-15 12:00:00 UTC
        every { timeProvider.now() } returns now

        // Create expenses: one in current month (June) and one earlier in the year (January)
        val januaryExpense = Expense(
            id = 300L, amount = 120.0, currency = "EUR",
            merchant = "JanuaryMerchant",
            transactionType = TransactionType.PURCHASE,
            date = 1704067200000L, // 2024-01-01 00:00 UTC
            categoryId = 1L
        )
        val juneExpense = Expense(
            id = 400L, amount = 80.0, currency = "EUR",
            merchant = "JuneMerchant",
            transactionType = TransactionType.PURCHASE,
            date = 1717200000000L, // 2024-06-01 00:00 UTC
            categoryId = 2L
        )
        val allExpenses = listOf(januaryExpense, juneExpense)

        every { expenseRepository.getAllExpenses() } returns flowOf(allExpenses)
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns allExpenses

        val periodSlot = slot<PeriodRange>()
        coEvery { analyticsInputAssembler.build(any<List<Expense>>(), any<String>(), capture(periodSlot), any()) } answers {
            val capturedPeriod = periodSlot.captured
            NormalizedAnalyticsInput(
                period = capturedPeriod,
                homeCurrency = "EUR",
                includedExpenses = firstArg<List<Expense>>().map { exp ->
                    NormalizedExpense(
                        id = exp.id,
                        originalAmount = exp.amount,
                        originalEffectiveAmount = exp.amount,
                        originalCurrency = exp.currency,
                        normalizedAmount = exp.amount,
                        normalizedCurrency = "EUR",
                        date = exp.date,
                        merchant = exp.merchant,
                        merchantKey = null,
                        categoryId = exp.categoryId,
                        categoryNameSnapshot = null,
                        transactionType = "PURCHASE",
                        isNotMine = false,
                        isSharedExpense = false,
                        ownershipMode = null,
                        source = null
                    )
                }
            )
        }

        // Select YEAR period
        viewModel.selectPeriod(com.yourname.expensetracker.domain.analytics.TimePeriod.YEAR)

        val collectJob = launch { viewModel.state.collect { } }
        testDispatcher.scheduler.advanceTimeBy(400)
        advanceUntilIdle()

        // Compute expected year range
        val (expectedYearStart, expectedYearEnd) = TimePeriodUtils.getYearRange(now)

        // Verify the input period covers the full year, not just current month
        val inputSlot = slot<NormalizedAnalyticsInput>()
        coVerify {
            insightsEngine.generateInsights(capture(inputSlot), any(), any<List<AnalyticsCategoryRef>>(), any())
        }

        val capturedInput = inputSlot.captured
        val capturedPeriod = capturedInput.period
        assertNotNull(capturedPeriod)
        assertEquals(
            expectedYearStart, capturedPeriod.startInclusiveMillis,
            "Period start should match year start (Jan 1), not current month start"
        )
        assertEquals(
            expectedYearEnd, capturedPeriod.endExclusiveMillis,
            "Period end should match year end, not current month end"
        )
        assertEquals(PeriodKind.CUSTOM, capturedPeriod.kind)
        assertEquals("YEAR", capturedPeriod.label)

        collectJob.cancel()
    }

    @Test
    fun `allInsightsDoNotCollapseToCurrentMonth`() = runTest(testDispatcher) {
        // Fix "now" to 2024-06-15 12:00 UTC
        val now = 1718452800000L // 2024-06-15 12:00:00 UTC
        every { timeProvider.now() } returns now

        // Create expenses spanning multiple years
        val expense2022 = Expense(
            id = 500L, amount = 200.0, currency = "EUR",
            merchant = "OldMerchant2022",
            transactionType = TransactionType.PURCHASE,
            date = 1640995200000L, // 2022-01-01 00:00 UTC
            categoryId = 1L
        )
        val expense2023 = Expense(
            id = 600L, amount = 150.0, currency = "EUR",
            merchant = "OldMerchant2023",
            transactionType = TransactionType.PURCHASE,
            date = 1672531200000L, // 2023-01-01 00:00 UTC
            categoryId = 2L
        )
        val expense2024 = Expense(
            id = 700L, amount = 90.0, currency = "EUR",
            merchant = "CurrentMerchant2024",
            transactionType = TransactionType.PURCHASE,
            date = 1717200000000L, // 2024-06-01 00:00 UTC
            categoryId = 3L
        )
        val allExpenses = listOf(expense2022, expense2023, expense2024)

        every { expenseRepository.getAllExpenses() } returns flowOf(allExpenses)
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns allExpenses

        val periodSlot = slot<PeriodRange>()
        coEvery { analyticsInputAssembler.build(any<List<Expense>>(), any<String>(), capture(periodSlot), any()) } answers {
            val capturedPeriod = periodSlot.captured
            NormalizedAnalyticsInput(
                period = capturedPeriod,
                homeCurrency = "EUR",
                includedExpenses = firstArg<List<Expense>>().map { exp ->
                    NormalizedExpense(
                        id = exp.id,
                        originalAmount = exp.amount,
                        originalEffectiveAmount = exp.amount,
                        originalCurrency = exp.currency,
                        normalizedAmount = exp.amount,
                        normalizedCurrency = "EUR",
                        date = exp.date,
                        merchant = exp.merchant,
                        merchantKey = null,
                        categoryId = exp.categoryId,
                        categoryNameSnapshot = null,
                        transactionType = "PURCHASE",
                        isNotMine = false,
                        isSharedExpense = false,
                        ownershipMode = null,
                        source = null
                    )
                }
            )
        }

        // Select ALL period
        viewModel.selectPeriod(com.yourname.expensetracker.domain.analytics.TimePeriod.ALL)

        val collectJob = launch { viewModel.state.collect { } }
        testDispatcher.scheduler.advanceTimeBy(400)
        advanceUntilIdle()

        // Verify the input period starts at 0 (epoch), NOT at current month start
        val inputSlot = slot<NormalizedAnalyticsInput>()
        coVerify {
            insightsEngine.generateInsights(capture(inputSlot), any(), any<List<AnalyticsCategoryRef>>(), any())
        }

        val capturedInput = inputSlot.captured
        val capturedPeriod = capturedInput.period
        assertNotNull(capturedPeriod)
        assertEquals(
            0L, capturedPeriod.startInclusiveMillis,
            "ALL period start must be 0 (epoch), not collapsed to current month start"
        )
        // End should be "now" (the ViewModel passes `now` for ALL period end)
        assertEquals(
            now, capturedPeriod.endExclusiveMillis,
            "ALL period end should be 'now'"
        )
        assertEquals(PeriodKind.CUSTOM, capturedPeriod.kind)
        assertEquals("ALL", capturedPeriod.label)

        collectJob.cancel()
    }
}
