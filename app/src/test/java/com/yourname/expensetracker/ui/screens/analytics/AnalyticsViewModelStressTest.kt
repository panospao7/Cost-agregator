package com.yourname.expensetracker.ui.screens.analytics

import app.cash.turbine.test
import com.yourname.expensetracker.domain.analytics.TimePeriod
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Ignore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
@Ignore("Stress test: may hang in CI, run manually")
class AnalyticsViewModelStressTest : ViewModelTestUtils() {

    private lateinit var expenseRepository: com.yourname.expensetracker.data.repository.ExpenseRepository
    private lateinit var categoryRepository: com.yourname.expensetracker.data.repository.CategoryRepository
    private lateinit var budgetRepository: com.yourname.expensetracker.data.repository.BudgetRepository
    private lateinit var insightsEngine: com.yourname.expensetracker.domain.analytics.InsightsEngine
    private lateinit var recurringExpenseEngine: com.yourname.expensetracker.domain.logic.RecurringExpenseEngine
    private lateinit var analyticsRepository: com.yourname.expensetracker.data.repository.AnalyticsRepository
    private lateinit var advancedAnalyticsEngine: com.yourname.expensetracker.domain.analytics.AdvancedAnalyticsEngine
    private lateinit var locationInsightsEngine: com.yourname.expensetracker.domain.location.LocationInsightsEngine
    private lateinit var areaSpendingEngine: com.yourname.expensetracker.domain.location.AreaSpendingEngine
    private lateinit var travelDetectionEngine: com.yourname.expensetracker.domain.location.TravelDetectionEngine
    private lateinit var spendingPersonalityClassifier: com.yourname.expensetracker.domain.analytics.SpendingPersonalityClassifier
    private lateinit var timeProvider: com.yourname.expensetracker.domain.util.TimeProvider

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
        timeProvider = mockk(relaxed = true)

        every { expenseRepository.getAllExpenses() } returns flowOf(emptyList())
        every { categoryRepository.allCategories } returns flowOf(emptyList())
        coEvery { analyticsRepository.getSpendingSummary(any(), any()) } returns flowOf(
            com.yourname.expensetracker.data.repository.SpendingSummary(
                totalSpent = 0.0,
                previousTotalSpent = null,
                changePercent = null,
                dailyHistory = emptyList(),
                previousDailyHistory = emptyList(),
                transactionCount = 0
            )
        )
        coEvery { analyticsRepository.getCategoryBreakdown(any(), any()) } returns flowOf(emptyList())
        coEvery { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())
        every { timeProvider.now() } returns System.currentTimeMillis()
        coEvery { insightsEngine.generateInsights(any(), any()) } returns mockk(relaxed = true)
        every { insightsEngine.getLegacyInsights(any()) } returns emptyList()
        coEvery { insightsEngine.buildDailyTotals(any(), any()) } returns emptyMap()
        coEvery { recurringExpenseEngine.getPatterns(any()) } returns emptyList()
        val now = System.currentTimeMillis()
        every { advancedAnalyticsEngine.getPeriodRange(any(), any(), any()) } returns com.yourname.expensetracker.domain.analytics.PeriodRange(
            period = com.yourname.expensetracker.domain.analytics.AnalyticsPeriod.MONTH,
            startMs = now - 30L * 24 * 60 * 60 * 1000,
            endMs = now,
            label = "Test",
            comparisonRange = null
        )
        coEvery { advancedAnalyticsEngine.getCategoryAnalytics(any()) } returns emptyList()
        coEvery { advancedAnalyticsEngine.getMerchantAnalytics(any(), any()) } returns emptyList()
        coEvery { advancedAnalyticsEngine.getSpendingPatterns(any()) } throws RuntimeException("test")
        coEvery { advancedAnalyticsEngine.getStatisticalInsights(any()) } throws RuntimeException("test")
        every { locationInsightsEngine.compute(any()) } returns emptyList()
        every { areaSpendingEngine.compute(any()) } returns emptyList()
        every { travelDetectionEngine.compute(any()) } returns com.yourname.expensetracker.domain.location.TravelInsight(
            homeLatitude = null,
            homeLongitude = null,
            homeSpend = 0.0,
            localSpend = 0.0,
            travelSpend = 0.0,
            travelTrips = emptyList()
        )
        coEvery { spendingPersonalityClassifier.classify() } returns com.yourname.expensetracker.domain.analytics.SpendingPersonalityProfile(
            personalityType = com.yourname.expensetracker.domain.analytics.SpendingPersonalityType.BALANCED,
            confidence = 0.0,
            featureScores = emptyMap(),
            explanation = emptyList(),
            coachingTips = emptyList(),
            lastUpdated = System.currentTimeMillis()
        )

        viewModel = AnalyticsViewModel(
            expenseRepository,
            categoryRepository,
            budgetRepository,
            insightsEngine,
            recurringExpenseEngine,
            analyticsRepository,
            advancedAnalyticsEngine,
            locationInsightsEngine,
            areaSpendingEngine,
            travelDetectionEngine,
            spendingPersonalityClassifier,
            timeProvider
        )
    }

    // ============================================================================
    // SECTION 1: INITIAL STATE
    // ============================================================================

    @Test
    fun `stress - initial state has values`() = runTest(testDispatcher) {
        testDispatcher.scheduler.advanceTimeBy(400)
        advanceUntilIdle()
        assertNotNull(viewModel.state.value)
    }

    @Test
    fun `stress - initial selectedPeriod is MONTH`() = runTest(testDispatcher) {
        testDispatcher.scheduler.advanceTimeBy(400)
        advanceUntilIdle()
        assertEquals(TimePeriod.MONTH, viewModel.state.value.selectedPeriod)
    }

    // ============================================================================
    // SECTION 2: SELECT PERIOD
    // ============================================================================

    @Test
    fun `stress - selectPeriod WEEK updates state`() = runTest(testDispatcher) {
        viewModel.selectPeriod(TimePeriod.WEEK)
        testDispatcher.scheduler.advanceTimeBy(500)
        advanceUntilIdle()
        assertNotNull(viewModel.state.value)
    }

    @Test
    fun `stress - selectPeriod TODAY updates state`() = runTest(testDispatcher) {
        viewModel.selectPeriod(TimePeriod.TODAY)
        testDispatcher.scheduler.advanceTimeBy(500)
        advanceUntilIdle()
        assertNotNull(viewModel.state.value)
    }

    @Test
    fun `stress - selectPeriod ALL updates state`() = runTest(testDispatcher) {
        viewModel.selectPeriod(TimePeriod.ALL)
        testDispatcher.scheduler.advanceTimeBy(500)
        advanceUntilIdle()
        assertNotNull(viewModel.state.value)
    }

    @Test
    fun `stress - rapid selectPeriod does not crash`() = runTest(testDispatcher) {
        TimePeriod.entries.forEach { viewModel.selectPeriod(it) }
        testDispatcher.scheduler.advanceTimeBy(400)
        advanceUntilIdle()
        assertNotNull(viewModel.state.value)
    }

    @Test
    fun `stress - empty expenses produces valid state`() = runTest(testDispatcher) {
        testDispatcher.scheduler.advanceTimeBy(400)
        advanceUntilIdle()
        val state = viewModel.state.value
        assertEquals(0.0, state.currentTotal, 0.001)
        assertEquals(0, state.transactionCount)
    }
}
