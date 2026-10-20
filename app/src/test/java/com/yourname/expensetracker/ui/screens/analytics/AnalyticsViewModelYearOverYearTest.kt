package com.yourname.expensetracker.ui.screens.analytics

import com.yourname.expensetracker.TestCurrencySettingsRepository
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.AnalyticsRepository
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.analytics.AdvancedAnalyticsEngine
import com.yourname.expensetracker.domain.analytics.AnalyticsConversionWarning
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.analytics.AnalyticsInputAssembler
import com.yourname.expensetracker.domain.analytics.BudgetVsActualEngine
import com.yourname.expensetracker.domain.analytics.DailyBucketEngine
import com.yourname.expensetracker.domain.analytics.EnhancedMerchantAnalytics
import com.yourname.expensetracker.domain.analytics.InsightsEngine
import com.yourname.expensetracker.domain.analytics.NormalizedAnalyticsInput
import com.yourname.expensetracker.domain.analytics.NormalizedExpense
import com.yourname.expensetracker.domain.analytics.SpendingPatternAnalysis
import com.yourname.expensetracker.domain.analytics.SpendingPersonalityClassifier
import com.yourname.expensetracker.domain.analytics.SpendingPersonalityProfile
import com.yourname.expensetracker.domain.analytics.SpendingPersonalityType
import com.yourname.expensetracker.domain.analytics.StatisticalInsights
import com.yourname.expensetracker.domain.analytics.TimePeriod
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.core.time.PeriodRange
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.location.AreaSpendingEngine
import com.yourname.expensetracker.domain.location.LocationInsightsEngine
import com.yourname.expensetracker.domain.location.NormalizedTravelInsight
import com.yourname.expensetracker.domain.location.TravelDetectionEngine
import com.yourname.expensetracker.domain.logic.RecurringExpenseEngine
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Year-over-year and empty-state behaviour of [AnalyticsViewModel], driven through a
 * pass-through [AnalyticsInputAssembler] so exact amounts flow from repository rows to state.
 *
 * Fixed clock: 2024-06-15 12:00 UTC. All fixture dates sit at 12:00 UTC on a day that stays in
 * the same calendar month for every UTC offset, so the assertions are time-zone independent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnalyticsViewModelYearOverYearTest : ViewModelTestUtils() {

    companion object {
        /** 2024-06-15 12:00:00 UTC. */
        private const val NOW = 1_718_452_800_000L

        /** 2024-06-14 12:00:00 UTC: inside the selected MONTH period (June 2024). */
        private const val CURRENT_MONTH_DATE = 1_718_366_400_000L

        /** 2023-06-14 12:00:00 UTC: same calendar month one year earlier, outside MONTH. */
        private const val PRIOR_YEAR_DATE = 1_686_744_000_000L

        private const val JUNE_INDEX = 5
    }

    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var categoryRepository: CategoryRepository
    private lateinit var budgetRepository: BudgetRepository
    private lateinit var insightsEngine: InsightsEngine
    private lateinit var recurringExpenseEngine: RecurringExpenseEngine
    private lateinit var analyticsRepository: AnalyticsRepository
    private lateinit var advancedAnalyticsEngine: AdvancedAnalyticsEngine
    private lateinit var locationInsightsEngine: LocationInsightsEngine
    private lateinit var areaSpendingEngine: AreaSpendingEngine
    private lateinit var travelDetectionEngine: TravelDetectionEngine
    private lateinit var spendingPersonalityClassifier: SpendingPersonalityClassifier
    private lateinit var analyticsInputAssembler: AnalyticsInputAssembler
    private lateinit var timeProvider: FakeTimeProvider

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
        locationInsightsEngine = mockk(relaxed = true)
        areaSpendingEngine = mockk(relaxed = true)
        travelDetectionEngine = mockk(relaxed = true)
        spendingPersonalityClassifier = mockk(relaxed = true)
        analyticsInputAssembler = mockk(relaxed = true)
        timeProvider = FakeTimeProvider(NOW)

        every { expenseRepository.getAllExpenses() } returns flowOf(emptyList())
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns emptyList()
        every { categoryRepository.allCategories } returns flowOf(emptyList())
        every { budgetRepository.allBudgets } returns flowOf(emptyList())
        every { insightsEngine.getLegacyInsights(any(), any()) } returns emptyList()
        coEvery { recurringExpenseEngine.getPatternsFromSnapshots(any()) } returns emptyList()
        coEvery { advancedAnalyticsEngine.getCategoryAnalytics(any(), any(), any(), any(), any()) } returns
            Pair(emptyList(), emptyList())
        val merchantResult: Pair<List<EnhancedMerchantAnalytics>, List<AnalyticsConversionWarning>> =
            Pair(emptyList(), emptyList())
        coEvery {
            advancedAnalyticsEngine.getMerchantAnalytics(
                any<NormalizedAnalyticsInput>(),
                any<NormalizedAnalyticsInput>(),
                any()
            )
        } returns merchantResult
        val patternResult: Pair<SpendingPatternAnalysis, List<AnalyticsConversionWarning>> =
            Pair(mockk(relaxed = true), emptyList())
        coEvery { advancedAnalyticsEngine.getSpendingPatterns(any<NormalizedAnalyticsInput>()) } returns patternResult
        val statsResult: Pair<StatisticalInsights, List<AnalyticsConversionWarning>> =
            Pair(mockk(relaxed = true), emptyList())
        coEvery { advancedAnalyticsEngine.getStatisticalInsights(any<NormalizedAnalyticsInput>()) } returns statsResult
        every { locationInsightsEngine.computeNormalized(any()) } returns emptyList()
        coEvery { areaSpendingEngine.computeNormalized(any(), any(), any()) } returns emptyList()
        coEvery { travelDetectionEngine.computeNormalized(any(), any(), any()) } returns NormalizedTravelInsight(
            homeLatitude = null,
            homeLongitude = null,
            homeAggregate = MoneyAggregate.empty(CurrencyCode.EUR),
            localAggregate = MoneyAggregate.empty(CurrencyCode.EUR),
            travelAggregate = MoneyAggregate.empty(CurrencyCode.EUR),
            travelTrips = emptyList()
        )
        coEvery { spendingPersonalityClassifier.classify(any<NormalizedAnalyticsInput>()) } returns
            SpendingPersonalityProfile(
                personalityType = SpendingPersonalityType.BALANCED,
                confidence = 0.0,
                featureScores = emptyMap(),
                explanation = emptyList(),
                coachingTips = emptyList(),
                lastUpdated = NOW
            )

        // Pass-through assembler: every EUR expense becomes a NormalizedExpense with the same amount.
        coEvery { analyticsInputAssembler.build(any<List<Expense>>(), any<String>(), any(), any()) } answers {
            NormalizedAnalyticsInput(
                period = arg<PeriodRange>(2),
                homeCurrency = "EUR",
                includedExpenses = firstArg<List<Expense>>().map { it.toNormalized() }
            )
        }

        viewModel = AnalyticsViewModel(
            expenseRepository,
            categoryRepository,
            budgetRepository,
            insightsEngine,
            recurringExpenseEngine,
            analyticsRepository,
            advancedAnalyticsEngine,
            mockk<AnalyticsCurrencyNormalizer>(relaxed = true),
            locationInsightsEngine,
            areaSpendingEngine,
            travelDetectionEngine,
            spendingPersonalityClassifier,
            timeProvider,
            analyticsInputAssembler,
            mockk<CurrencyConverter>(relaxed = true),
            TestCurrencySettingsRepository(),
            mockk<BudgetVsActualEngine>(relaxed = true),
            mockk<DailyBucketEngine>(relaxed = true)
        )
    }

    private fun stubExpenses(all: List<Expense>) {
        every { expenseRepository.getAllExpenses() } returns flowOf(all)
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } answers {
            val start = firstArg<Long>()
            val end = secondArg<Long>()
            all.filter { it.date in start until end }
        }
    }

    @Test
    fun `empty expenses produce computed zero state without year over year`() = runTest(testDispatcher) {
        val collectJob = launch { viewModel.state.collect { } }
        testDispatcher.scheduler.advanceTimeBy(400)
        advanceUntilIdle()

        val state = viewModel.state.value
        // referenceNowMillis is only populated by the pipeline, so this proves the state is computed
        // rather than the initial AnalyticsState() default.
        assertEquals(NOW, state.referenceNowMillis)
        assertNull(state.error)
        assertEquals(TimePeriod.MONTH, state.selectedPeriod)
        assertEquals(0.0, state.currentTotal, 0.001)
        assertEquals(0, state.transactionCount)
        assertNull(state.yearOverYear)
        collectJob.cancel()
    }

    @Test
    fun `month period keeps current period totals while loading prior year for yoy`() = runTest(testDispatcher) {
        stubExpenses(
            listOf(
                expense(id = 1L, amount = 120.0, date = CURRENT_MONTH_DATE),
                expense(id = 2L, amount = 75.0, date = PRIOR_YEAR_DATE)
            )
        )

        val collectJob = launch { viewModel.state.collect { } }
        testDispatcher.scheduler.advanceTimeBy(400)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertNull(state.error)
        assertEquals(120.0, state.currentTotal, 0.001)
        assertEquals(1, state.transactionCount)

        val yoy = state.yearOverYear
        assertNotNull(yoy)
        yoy!!
        assertEquals(2024, yoy.currentYear)
        assertEquals(2023, yoy.priorYear)
        assertEquals(120.0, yoy.currentYearTotal, 0.001)
        assertEquals(75.0, yoy.priorYearTotal, 0.001)
        assertNotNull(yoy.changePercent)
        assertEquals(60.0f, yoy.changePercent!!, 0.001f)
        assertEquals(1, yoy.currentYearMonths.size)
        assertEquals(JUNE_INDEX, yoy.currentYearMonths.single().month)
        assertEquals(1, yoy.currentYearMonths.single().transactionCount)
        assertEquals(1, yoy.priorYearMonths.size)
        assertEquals(JUNE_INDEX, yoy.priorYearMonths.single().month)
        assertEquals(1, yoy.priorYearMonths.single().transactionCount)
        assertEquals(1, yoy.deltaByMonth.size)
        assertEquals("Jun", yoy.deltaByMonth.single().first)
        assertEquals(120.0, yoy.deltaByMonth.single().second, 0.001)
        assertEquals(75.0, yoy.deltaByMonth.single().third, 0.001)
        // NOW is minute-aligned, so the comparison minute equals NOW.
        coVerify { advancedAnalyticsEngine.getCategoryAnalytics(any(), any(), any(), any(), NOW) }
        collectJob.cancel()
    }

    @Test
    fun `yoy with only prior year data reports zero current total and full decrease`() = runTest(testDispatcher) {
        stubExpenses(listOf(expense(id = 2L, amount = 75.0, date = PRIOR_YEAR_DATE)))

        val collectJob = launch { viewModel.state.collect { } }
        testDispatcher.scheduler.advanceTimeBy(400)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertNull(state.error)
        assertEquals(0.0, state.currentTotal, 0.001)
        assertEquals(0, state.transactionCount)

        val yoy = state.yearOverYear
        assertNotNull(yoy)
        yoy!!
        assertEquals(0.0, yoy.currentYearTotal, 0.001)
        assertEquals(75.0, yoy.priorYearTotal, 0.001)
        assertEquals(-100.0f, yoy.changePercent!!, 0.001f)
        assertEquals(0, yoy.currentYearMonths.size)
        assertEquals(1, yoy.priorYearMonths.size)
        collectJob.cancel()
    }

    private fun expense(id: Long, amount: Double, date: Long): Expense = Expense(
        id = id,
        amount = amount,
        currency = "EUR",
        merchant = "Test Merchant",
        transactionType = TransactionType.PURCHASE,
        date = date
    )

    private fun Expense.toNormalized(): NormalizedExpense = NormalizedExpense(
        id = id,
        originalAmount = amount,
        originalEffectiveAmount = amount,
        originalCurrency = currency,
        normalizedAmount = amount,
        normalizedCurrency = "EUR",
        date = date,
        merchant = merchant,
        merchantKey = null,
        categoryId = categoryId,
        categoryNameSnapshot = null,
        transactionType = "PURCHASE",
        isNotMine = false,
        isSharedExpense = false,
        ownershipMode = null,
        source = null
    )
}
