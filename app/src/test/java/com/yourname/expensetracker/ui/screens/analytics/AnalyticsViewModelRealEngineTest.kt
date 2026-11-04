package com.yourname.expensetracker.ui.screens.analytics

import com.yourname.expensetracker.TestCurrencySettingsRepository
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.analytics.AdvancedAnalyticsEngine
import com.yourname.expensetracker.domain.analytics.AnalyticsInputAssembler
import com.yourname.expensetracker.domain.analytics.BudgetVsActualEngine
import com.yourname.expensetracker.domain.analytics.DailyBucketEngine
import com.yourname.expensetracker.domain.analytics.InsightsEngine
import com.yourname.expensetracker.domain.analytics.MerchantBreakdown
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.analytics.SpendingPersonalityClassifier
import com.yourname.expensetracker.domain.analytics.SpendingPersonalityProfile
import com.yourname.expensetracker.domain.analytics.SpendingPersonalityType
import com.yourname.expensetracker.domain.analytics.NormalizedAnalyticsInput
import com.yourname.expensetracker.domain.core.time.PeriodKind
import com.yourname.expensetracker.domain.core.time.PeriodRange
import com.yourname.expensetracker.domain.logic.RecurringExpenseEngine
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.testAnalyticsCurrencyNormalizer
import com.yourname.expensetracker.toExpenseSnapshots
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N-08-7 / merchant average with the REAL [AdvancedAnalyticsEngine], the REAL
 * [AnalyticsInputAssembler] and the REAL currency normalizer (empty rate store, so a
 * USD row has no rate). Only peripheral engines and repositories are mocked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnalyticsViewModelRealEngineTest : ViewModelTestUtils() {

    private val now = 1_718_452_800_000L // 2024-06-15 12:00 UTC
    private val txDate = 1_718_366_400_000L // 2024-06-14 12:00 UTC

    private val categories = listOf(
        Category(id = 1L, name = "Food", icon = "F", color = "#111111"),
        Category(id = 2L, name = "Travel", icon = "T", color = "#222222")
    )

    private val timeProvider = object : TimeProvider {
        override fun now(): Long = now
    }

    private fun purchase(id: Long, amount: Double, currency: String, categoryId: Long?, merchant: String = "M$id", key: String? = null) =
        Expense(
            id = id, amount = amount, currency = currency, merchant = merchant, merchantKey = key,
            transactionType = TransactionType.PURCHASE, date = txDate, categoryId = categoryId
        )

    private fun viewModelFor(expenses: List<Expense>): AnalyticsViewModel {
        val expenseRepository = mockk<com.yourname.expensetracker.data.repository.ExpenseRepository>(relaxed = true)
        val categoryRepository = mockk<com.yourname.expensetracker.data.repository.CategoryRepository>(relaxed = true)
        val budgetRepository = mockk<com.yourname.expensetracker.data.repository.BudgetRepository>(relaxed = true)
        every { expenseRepository.getAllExpenses() } returns flowOf(expenses)
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } answers {
            val s = firstArg<Long>()
            val e = secondArg<Long>()
            expenses.filter { it.date in s until e }
        }
        every { categoryRepository.allCategories } returns flowOf(categories)
        coEvery { categoryRepository.getAll() } returns categories
        every { budgetRepository.allBudgets } returns flowOf(emptyList())

        val settings = TestCurrencySettingsRepository()
        val normalizer: AnalyticsCurrencyNormalizer = testAnalyticsCurrencyNormalizer()
        val assembler = AnalyticsInputAssembler(expenseRepository, normalizer, settings, timeProvider, categoryRepository)
        val engine = AdvancedAnalyticsEngine(
            expenseRepository, categoryRepository, budgetRepository, settings, normalizer, timeProvider,
            testDispatcher, testDispatcher
        )

        val insightsEngine = mockk<InsightsEngine>(relaxed = true)
        every { insightsEngine.getLegacyInsights(any(), any()) } returns emptyList()
        val recurring = mockk<RecurringExpenseEngine>(relaxed = true)
        coEvery { recurring.getPatternsFromSnapshots(any()) } returns emptyList()
        val classifier = mockk<SpendingPersonalityClassifier>(relaxed = true)
        coEvery { classifier.classify(any<NormalizedAnalyticsInput>()) } returns SpendingPersonalityProfile(
            personalityType = SpendingPersonalityType.BALANCED,
            confidence = 0.0,
            featureScores = emptyMap(),
            explanation = emptyList(),
            coachingTips = emptyList(),
            lastUpdated = now
        )

        // Location engines must return concrete values (as the other VM tests do): a relaxed mock
        // of a data-class result is not a faithful stand-in and can abort the compute path.
        val locationInsightsEngine = mockk<com.yourname.expensetracker.domain.location.LocationInsightsEngine>(relaxed = true)
        every { locationInsightsEngine.computeNormalized(any()) } returns emptyList()
        val areaSpendingEngine = mockk<com.yourname.expensetracker.domain.location.AreaSpendingEngine>(relaxed = true)
        coEvery { areaSpendingEngine.computeNormalized(any(), any(), any()) } returns emptyList()
        val travelDetectionEngine = mockk<com.yourname.expensetracker.domain.location.TravelDetectionEngine>(relaxed = true)
        val emptyEur = com.yourname.expensetracker.domain.core.money.MoneyAggregate.empty(
            com.yourname.expensetracker.domain.core.money.CurrencyCode.EUR
        )
        coEvery { travelDetectionEngine.computeNormalized(any(), any(), any()) } returns
            com.yourname.expensetracker.domain.location.NormalizedTravelInsight(
                homeLatitude = null,
                homeLongitude = null,
                homeAggregate = emptyEur,
                localAggregate = emptyEur,
                travelAggregate = emptyEur,
                travelTrips = emptyList()
            )

        return AnalyticsViewModel(
            expenseRepository,
            categoryRepository,
            budgetRepository,
            insightsEngine,
            recurring,
            mockk(relaxed = true),
            engine,
            normalizer,
            locationInsightsEngine,
            areaSpendingEngine,
            travelDetectionEngine,
            classifier,
            timeProvider,
            assembler,
            mockk(relaxed = true),
            settings,
            mockk<BudgetVsActualEngine>(relaxed = true),
            mockk<DailyBucketEngine>(relaxed = true)
        )
    }

    /** The real chain leaves the test scheduler for its IO work, so wait on the state instead of virtual time. */
    private suspend fun awaitComputed(viewModel: AnalyticsViewModel) {
        withContext(Dispatchers.Default) {
            withTimeout(30_000) { viewModel.state.first { !it.isLoading } }
        }
    }

    @Test
    fun `N-08-7 real chain USD-only Travel stays listed as unavailable and Food keeps its real amount`() = runTest(testDispatcher) {
        val viewModel = viewModelFor(listOf(purchase(1, 100.0, "EUR", 1L), purchase(2, 80.0, "USD", 2L)))

        val job = launch { viewModel.state.collect { } }
        testDispatcher.scheduler.advanceTimeBy(400)
        awaitComputed(viewModel)

        assertNull("state error, loading=${viewModel.state.value.isLoading}", viewModel.state.value.error)
        assertFalse("still loading", viewModel.state.value.isLoading)
        val breakdown = viewModel.state.value.categoryBreakdown
        val food = breakdown.single { it.category.id == 1L }
        val travel = breakdown.single { it.category.id == 2L }
        assertEquals(100.0, food.moneyTotal.amount, 0.001)
        assertFalse(food.isUnavailable)
        assertFalse(food.isPartial)
        assertTrue("Travel must not vanish and must be flagged unavailable", travel.isUnavailable)
        assertFalse("unavailable must not be conflated with partial", travel.isPartial)
        job.cancel()
    }

    @Test
    fun `N-08-7 real chain uncategorized excluded row does not break the screen and Food stays available`() = runTest(testDispatcher) {
        val viewModel = viewModelFor(listOf(purchase(1, 100.0, "EUR", 1L), purchase(3, 25.0, "USD", null)))

        val job = launch { viewModel.state.collect { } }
        testDispatcher.scheduler.advanceTimeBy(400)
        awaitComputed(viewModel)

        val state = viewModel.state.value
        assertNull("an uncategorized excluded row must not fail the compute", state.error)
        assertFalse("still loading", state.isLoading)
        val food = state.categoryBreakdown.single { it.category.id == 1L }
        assertEquals(100.0, food.moneyTotal.amount, 0.001)
        assertFalse(food.isUnavailable)
        job.cancel()
    }

    // ------------------------------------------------------------------------------------------
    // Hero percent: a partial aggregate on either side must not produce a fabricated percent.
    // ------------------------------------------------------------------------------------------

    private val prevDate = 1_715_342_400_000L // 2024-05-10 12:00 UTC (previous calendar month)

    @Test
    fun `hero percent control complete both sides shows 150 percent`() = runTest(testDispatcher) {
        val viewModel = viewModelFor(
            listOf(
                purchase(1, 250.0, "EUR", 1L),
                purchase(2, 100.0, "EUR", 1L).copy(date = prevDate)
            )
        )

        val job = launch { viewModel.state.collect { } }
        testDispatcher.scheduler.advanceTimeBy(400)
        awaitComputed(viewModel)

        val change = viewModel.state.value.changePercent
        assertTrue("complete comparison keeps its percent, was $change", change != null)
        assertEquals(150f, change!!, 0.01f)
        job.cancel()
    }

    @Test
    fun `hero percent is null when the previous period has an unrated row`() = runTest(testDispatcher) {
        val viewModel = viewModelFor(
            listOf(
                purchase(1, 250.0, "EUR", 1L),
                purchase(2, 100.0, "EUR", 1L).copy(date = prevDate),
                purchase(3, 50_000.0, "JPY", 1L).copy(date = prevDate)
            )
        )

        val job = launch { viewModel.state.collect { } }
        testDispatcher.scheduler.advanceTimeBy(400)
        awaitComputed(viewModel)

        assertNull("error: ${viewModel.state.value.error}", viewModel.state.value.error)
        assertNull("a partial previous aggregate must not yield a percent", viewModel.state.value.changePercent)
        job.cancel()
    }

    @Test
    fun `hero percent is null when the current period has an unrated row`() = runTest(testDispatcher) {
        val viewModel = viewModelFor(
            listOf(
                purchase(1, 250.0, "EUR", 1L),
                purchase(3, 50_000.0, "JPY", 1L),
                purchase(2, 100.0, "EUR", 1L).copy(date = prevDate)
            )
        )

        val job = launch { viewModel.state.collect { } }
        testDispatcher.scheduler.advanceTimeBy(400)
        awaitComputed(viewModel)

        assertNull(viewModel.state.value.changePercent)
        job.cancel()
    }

    // ------------------------------------------------------------------------------------------
    // F3: a spending-neutralised (flagged) purchase must not re-enter the merchant list.
    // ------------------------------------------------------------------------------------------

    @Test
    fun `F3 the only purchase is flagged at 1200 so no merchant row is listed`() = runTest(testDispatcher) {
        val flagged = purchase(1, 1_200.0, "EUR", 1L, merchant = "Flagged Gadgets", key = "flagged_gadgets")
            .copy(spendingExclusionReason = "TEST_NEUTRALISED")
        val viewModel = viewModelFor(listOf(flagged))

        val job = launch { viewModel.state.collect { } }
        testDispatcher.scheduler.advanceTimeBy(400)
        awaitComputed(viewModel)

        assertNull("compute must not fail", viewModel.state.value.error)
        assertFalse("still loading", viewModel.state.value.isLoading)
        assertTrue(
            "a flagged purchase must not appear as a merchant row: ${viewModel.state.value.merchantBreakdown.map { it.name }}",
            viewModel.state.value.merchantBreakdown.isEmpty()
        )
        job.cancel()
    }

    @Test
    fun `F3 a normal purchase next to a flagged one is the only merchant listed`() = runTest(testDispatcher) {
        val flagged = purchase(1, 1_200.0, "EUR", 1L, merchant = "Flagged Gadgets", key = "flagged_gadgets")
            .copy(spendingExclusionReason = "TEST_NEUTRALISED")
        val normal = purchase(2, 40.0, "EUR", 1L, merchant = "Corner Cafe", key = "corner_cafe")
        val viewModel = viewModelFor(listOf(flagged, normal))

        val job = launch { viewModel.state.collect { } }
        testDispatcher.scheduler.advanceTimeBy(400)
        awaitComputed(viewModel)

        assertNull("compute must not fail", viewModel.state.value.error)
        val merchants = viewModel.state.value.merchantBreakdown
        assertEquals(1, merchants.size)
        assertEquals("Corner Cafe", merchants.single().name)
        assertEquals(40.0, merchants.single().totalSpent, 0.001)
        job.cancel()
    }

    // ------------------------------------------------------------------------------------------
    // Merchant average (AnalyticsViewModel.buildMerchantBreakdown), real assembler + normalizer.
    // ------------------------------------------------------------------------------------------

    private suspend fun merchantGroups(expenses: List<Expense>): List<MerchantBreakdown> {
        val categoryRepository = mockk<com.yourname.expensetracker.data.repository.CategoryRepository>()
        coEvery { categoryRepository.getAll() } returns categories
        val assembler = AnalyticsInputAssembler(
            mockk(), testAnalyticsCurrencyNormalizer(), TestCurrencySettingsRepository(), timeProvider, categoryRepository
        )
        val period = PeriodRange(PeriodKind.CUSTOM, txDate - 86_400_000L, txDate + 86_400_000L, label = "TEST")
        val input = assembler.build(expenses, "EUR", period)
        return buildMerchantBreakdown(expenses.toExpenseSnapshots(), input, "EUR")
    }

    @Test
    fun `merchant average control all rows convert divides total by converted count`() = runTest {
        val groups = merchantGroups(
            listOf(
                purchase(1, 100.0, "EUR", 1L, merchant = "Acme", key = "acme"),
                purchase(2, 50.0, "EUR", 1L, merchant = "Acme", key = "acme")
            )
        )

        val acme = groups.single()
        assertEquals(150.0, acme.totalSpent, 0.001)
        assertEquals(75.0, acme.averageTransaction, 0.001)
    }

    @Test
    fun `merchant average of 100 plus an unrated USD 50 is 100 not 50`() = runTest {
        val groups = merchantGroups(
            listOf(
                purchase(1, 100.0, "EUR", 1L, merchant = "Acme", key = "acme"),
                purchase(2, 50.0, "USD", 1L, merchant = "Acme", key = "acme")
            )
        )

        val acme = groups.single { it.name == "Acme" }
        assertEquals("only the converted row is summed", 100.0, acme.totalSpent, 0.001)
        assertEquals("the unrated row must not dilute the average", 100.0, acme.averageTransaction, 0.001)
    }

    @Test
    fun `merchant with every row excluded exposes no fabricated zero average`() = runTest {
        val groups = merchantGroups(
            listOf(
                purchase(1, 100.0, "EUR", 1L, merchant = "Acme", key = "acme"),
                purchase(2, 50.0, "USD", 2L, merchant = "Ghost", key = "ghost")
            )
        )

        assertTrue(
            "a merchant whose rows are all unconvertible must be absent, not shown with average 0",
            groups.none { it.name == "Ghost" }
        )
        assertEquals(100.0, groups.single { it.name == "Acme" }.averageTransaction, 0.001)
    }
}
