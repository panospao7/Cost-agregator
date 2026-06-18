package com.yourname.expensetracker.ui.screens.analytics

import com.yourname.expensetracker.TestCurrencySettingsRepository
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.analytics.AnalyticsInputAssembler
import com.yourname.expensetracker.domain.analytics.BudgetVsActualEngine
import com.yourname.expensetracker.domain.analytics.DailyBucketEngine
import com.yourname.expensetracker.domain.analytics.InsightsEngine
import com.yourname.expensetracker.domain.analytics.NormalizedAnalyticsInput
import com.yourname.expensetracker.domain.analytics.TimePeriod
import com.yourname.expensetracker.domain.currency.ConversionResult
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.model.BudgetSnapshot
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
import kotlin.test.assertTrue

/**
 * Engine 2 PR3 — Budget-vs-actual FX basis.
 *
 * Verifies that [AnalyticsViewModel.convertBudgetAmountToHomeCurrency] uses
 * [CurrencyConverter.convertAsOf] (with the period-end timestamp) instead of
 * [CurrencyConverter.convert], so budget limits are converted at the rate
 * valid at the end of the reporting period rather than the latest available rate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BudgetVsActualFxBasisTest : ViewModelTestUtils() {

    private lateinit var expenseRepository: com.yourname.expensetracker.data.repository.ExpenseRepository
    private lateinit var categoryRepository: com.yourname.expensetracker.data.repository.CategoryRepository
    private lateinit var budgetRepository: com.yourname.expensetracker.data.repository.BudgetRepository
    private lateinit var insightsEngine: InsightsEngine
    private lateinit var recurringExpenseEngine: com.yourname.expensetracker.domain.logic.RecurringExpenseEngine
    private lateinit var analyticsRepository: com.yourname.expensetracker.data.repository.AnalyticsRepository
    private lateinit var advancedAnalyticsEngine: com.yourname.expensetracker.domain.analytics.AdvancedAnalyticsEngine
    private lateinit var analyticsCurrencyNormalizer: AnalyticsCurrencyNormalizer
    private lateinit var locationInsightsEngine: com.yourname.expensetracker.domain.location.LocationInsightsEngine
    private lateinit var areaSpendingEngine: com.yourname.expensetracker.domain.location.AreaSpendingEngine
    private lateinit var travelDetectionEngine: com.yourname.expensetracker.domain.location.TravelDetectionEngine
    private lateinit var spendingPersonalityClassifier: com.yourname.expensetracker.domain.analytics.SpendingPersonalityClassifier
    private lateinit var timeProvider: com.yourname.expensetracker.domain.util.TimeProvider
    private lateinit var analyticsInputAssembler: AnalyticsInputAssembler
    private lateinit var currencyConverter: CurrencyConverter
    private lateinit var currencySettingsRepository: TestCurrencySettingsRepository
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
        currencySettingsRepository = TestCurrencySettingsRepository()
        budgetVsActualEngine = mockk<BudgetVsActualEngine>(relaxed = true)
        dailyBucketEngine = mockk<DailyBucketEngine>(relaxed = true)

        // Required base flows so the ViewModel pipeline can start
        every { expenseRepository.getAllExpenses() } returns flowOf(emptyList())
        every { categoryRepository.allCategories } returns flowOf(emptyList())
        every { budgetRepository.allBudgets } returns flowOf(emptyList())
        // Provide a fixed "now" so period-end is deterministic
        every { timeProvider.now() } returns 1700000000001L
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns emptyList()
        coEvery { recurringExpenseEngine.getPatternsFromSnapshots(any()) } returns emptyList()
        coEvery { spendingPersonalityClassifier.classify(any<NormalizedAnalyticsInput>()) } returns com.yourname.expensetracker.domain.analytics.SpendingPersonalityProfile(
            personalityType = com.yourname.expensetracker.domain.analytics.SpendingPersonalityType.BALANCED,
            confidence = 0.0,
            featureScores = emptyMap(),
            explanation = emptyList(),
            coachingTips = emptyList(),
            lastUpdated = 1700000000001L
        )
        every { locationInsightsEngine.compute(any()) } returns emptyList()
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
    fun `convertBudgetAmountToHomeCurrency uses convertAsOf with period end`() = runTest(testDispatcher) {
        // ── Given ────────────────────────────────────────────────────────────

        // Set home currency to EUR (the default from TestCurrencySettingsRepository)
        // and provide a budget in USD so conversion across currencies is needed.
        val budgetSnapshot = BudgetSnapshot(
            categoryId = 1L,
            amount = 100.0,
            currency = "USD"
        )
        coEvery { budgetRepository.getActiveBudgetSnapshots() } returns listOf(budgetSnapshot)

        // Mock convertAsOf to return a known result when called with the
        // period-end timestamp.  With TimePeriod.ALL and now = 1700000000001L:
        //   currentEnd = now = 1700000000001L
        //   periodEndMillis = currentEnd - 1 = 1700000000000L
        val expectedConversion = ConversionResult(
            originalAmount = 100.0,
            originalCurrency = "USD",
            convertedAmount = 85.0,
            targetCurrency = "EUR",
            rateUsed = 0.85,
            timestamp = 1700000000000L
        )
        coEvery {
            currencyConverter.convertAsOf(
                any<Double>(), any<String>(), any<String>(), any<Long>()
            )
        } returns expectedConversion

        // Use TimePeriod.ALL so that currentEnd == now (no month-boundary math),
        // which gives us full control over periodEndMillis.
        viewModel.selectPeriod(TimePeriod.ALL)

        // ── When ─────────────────────────────────────────────────────────────
        // Subscribe to state to kick off the analytics pipeline.
        val collectJob = launch { viewModel.state.collect { } }

        // Advance past the 300 ms debounce so the pipeline runs.
        testDispatcher.scheduler.advanceTimeBy(400)
        advanceUntilIdle()

        // ── Then ─────────────────────────────────────────────────────────────
        // convertAsOf must have been called (not convert) with a period-end timestamp.
        coVerify(exactly = 1) {
            currencyConverter.convertAsOf(
                any<Double>(), any<String>(), any<String>(), any<Long>()
            )
        }

        // convert (latest rate) must NOT have been called.
        coVerify(exactly = 0) {
            currencyConverter.convert(any<Double>(), any<String>(), any<String>())
        }

        // The ConversionResult.convertedAmount should flow through to
        // the BudgetVsActualItem returned by BudgetVsActualEngine.
        coVerify(exactly = 1) {
            budgetVsActualEngine.compute(
                any(),
                match { snapshots ->
                    snapshots.any { it.amount == 85.0 }
                },
                any()
            )
        }

        collectJob.cancel()
    }
}
