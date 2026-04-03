package com.yourname.expensetracker.e2e

import com.yourname.expensetracker.TEST_CATEGORIES
import com.yourname.expensetracker.data.database.dao.CategoryTotal
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.AnalyticsRepository
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.MerchantCategoryRepository
import com.yourname.expensetracker.domain.analytics.AdvancedAnalyticsEngine
import com.yourname.expensetracker.domain.analytics.AnomalyDetector
import com.yourname.expensetracker.domain.analytics.CategoryInsightEngine
import com.yourname.expensetracker.domain.analytics.DayOfWeekAnalyzer
import com.yourname.expensetracker.domain.analytics.InsightsEngine
import com.yourname.expensetracker.domain.analytics.MerchantInsightEngine
import com.yourname.expensetracker.domain.analytics.MonthlyComparisonCalculator
import com.yourname.expensetracker.domain.analytics.SpendingPaceCalculator
import com.yourname.expensetracker.domain.analytics.SpendingPersonalityClassifier
import com.yourname.expensetracker.domain.location.AreaSpendingEngine
import com.yourname.expensetracker.domain.location.LocationInsightsEngine
import com.yourname.expensetracker.domain.location.TravelDetectionEngine
import com.yourname.expensetracker.domain.location.TravelInsight
import com.yourname.expensetracker.domain.logic.RecurringExpenseEngine
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.ui.screens.analytics.AnalyticsViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.TestDispatcher

internal data class FlowPipeline(
    val expenseDao: ExpenseDao,
    val expenseRepository: ExpenseRepository,
    val analyticsRepository: AnalyticsRepository,
    val insightsEngine: InsightsEngine,
    val advancedAnalyticsEngine: AdvancedAnalyticsEngine,
    val viewModel: AnalyticsViewModel,
    val timeProvider: TimeProvider
)

internal suspend fun FlowPipeline.awaitViewModelState(testDispatcher: TestDispatcher) : com.yourname.expensetracker.ui.screens.analytics.AnalyticsState {
    return coroutineScope {
        val awaited = async {
            viewModel.state.first { !it.isLoading }
        }

        testDispatcher.scheduler.advanceTimeBy(1_500)
        testDispatcher.scheduler.advanceUntilIdle()
        awaited.await()
    }
}

internal fun buildPipeline(
    expenses: List<Expense>,
    nowMs: Long,
    categories: List<Category> = TEST_CATEGORIES
): FlowPipeline {
    val expenseDao = mockk<ExpenseDao>(relaxed = true)
    val categoryRepository = mockk<CategoryRepository>(relaxed = true)
    val budgetRepository = mockk<BudgetRepository>(relaxed = true)

    val userCorrectionDao = mockk<com.yourname.expensetracker.data.database.dao.UserCorrectionDao>(relaxed = true)
    val pendingReviewDao = mockk<com.yourname.expensetracker.data.database.dao.PendingReviewDao>(relaxed = true)
    val merchantCategoryRepository = mockk<MerchantCategoryRepository>(relaxed = true)
    val merchantNormalizer = mockk<MerchantNormalizer>(relaxed = true)

    val recurringExpenseEngine = mockk<RecurringExpenseEngine>(relaxed = true)
    val locationInsightsEngine = mockk<LocationInsightsEngine>(relaxed = true)
    val areaSpendingEngine = mockk<AreaSpendingEngine>(relaxed = true)
    val travelDetectionEngine = mockk<TravelDetectionEngine>(relaxed = true)

    val timeProvider = FakeTimeProvider(nowMs)

    stubDao(expenseDao, expenses)

    every { categoryRepository.allCategories } returns flowOf(categories)
    coEvery { categoryRepository.getAll() } returns categories

    every { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())
    coEvery { budgetRepository.getActiveBudgets() } returns emptyList()

    coEvery { recurringExpenseEngine.getPatterns(any<List<Expense>>()) } returns emptyList()
    every { locationInsightsEngine.compute(any()) } returns emptyList()
    every { areaSpendingEngine.compute(any()) } returns emptyList()
    every { travelDetectionEngine.compute(any()) } returns TravelInsight(
        homeLatitude = null,
        homeLongitude = null,
        homeSpend = 0.0,
        localSpend = 0.0,
        travelSpend = 0.0,
        travelTrips = emptyList()
    )

    val expenseRepository = ExpenseRepository(
        expenseDao = expenseDao,
        userCorrectionDao = userCorrectionDao,
        pendingReviewDao = pendingReviewDao,
        merchantCategoryRepository = merchantCategoryRepository,
        merchantNormalizer = merchantNormalizer
    )

    val analyticsRepository = AnalyticsRepository(expenseDao, categoryRepository)
    val spendingPersonalityClassifier = mockk<SpendingPersonalityClassifier>(relaxed = true)

    val insightsEngine = InsightsEngine(
        expenseRepository = expenseRepository,
        recurringExpenseEngine = recurringExpenseEngine,
        timeProvider = timeProvider,
        spendingPaceCalculator = SpendingPaceCalculator(timeProvider),
        anomalyDetector = AnomalyDetector(),
        monthlyComparisonCalculator = MonthlyComparisonCalculator(),
        categoryInsightEngine = CategoryInsightEngine(),
        merchantInsightEngine = MerchantInsightEngine(),
        dayOfWeekAnalyzer = DayOfWeekAnalyzer()
    )

    val advancedAnalyticsEngine = AdvancedAnalyticsEngine(
        expenseRepository = expenseRepository,
        categoryRepository = categoryRepository,
        budgetRepository = budgetRepository,
        timeProvider = timeProvider
    )

    val viewModel = AnalyticsViewModel(
        expenseRepository = expenseRepository,
        categoryRepository = categoryRepository,
        budgetRepository = budgetRepository,
        insightsEngine = insightsEngine,
        recurringExpenseEngine = recurringExpenseEngine,
        analyticsRepository = analyticsRepository,
        advancedAnalyticsEngine = advancedAnalyticsEngine,
        locationInsightsEngine = locationInsightsEngine,
        areaSpendingEngine = areaSpendingEngine,
        travelDetectionEngine = travelDetectionEngine,
        spendingPersonalityClassifier = spendingPersonalityClassifier,
        timeProvider = timeProvider
    )

    return FlowPipeline(
        expenseDao = expenseDao,
        expenseRepository = expenseRepository,
        analyticsRepository = analyticsRepository,
        insightsEngine = insightsEngine,
        advancedAnalyticsEngine = advancedAnalyticsEngine,
        viewModel = viewModel,
        timeProvider = timeProvider
    )
}

private fun stubDao(expenseDao: ExpenseDao, allExpenses: List<Expense>) {
    fun inRange(start: Long, end: Long): List<Expense> = allExpenses.filter {
        it.date >= start && it.date < end
    }

    fun purchasesInRange(start: Long, end: Long): List<Expense> = inRange(start, end).filter {
        it.transactionType == TransactionType.PURCHASE && !it.isNotMine
    }

    every { expenseDao.getAllFlow(any()) } returns flowOf(allExpenses)
    coEvery { expenseDao.getAll() } returns allExpenses

    coEvery { expenseDao.getExpensesBetween(any(), any()) } answers {
        purchasesInRange(firstArg(), secondArg())
    }
    every { expenseDao.getExpensesBetweenFlow(any(), any()) } answers {
        flowOf(purchasesInRange(firstArg(), secondArg()))
    }

    coEvery { expenseDao.getExpensesByTypeBetween(any(), any(), TransactionType.PURCHASE.name) } answers {
        purchasesInRange(firstArg(), secondArg())
    }
    every { expenseDao.getExpensesByTypeBetweenFlow(any(), any(), TransactionType.PURCHASE.name) } answers {
        flowOf(purchasesInRange(firstArg(), secondArg()))
    }

    coEvery { expenseDao.getTotalForPeriod(any(), any()) } answers {
        purchasesInRange(firstArg(), secondArg()).sumOf { it.effectiveAmount }
    }
    coEvery { expenseDao.getCountForPeriod(any(), any()) } answers {
        purchasesInRange(firstArg(), secondArg()).size
    }

    coEvery { expenseDao.getCategoryTotalsForPeriod(any(), any()) } answers {
        purchasesInRange(firstArg(), secondArg())
            .filter { it.categoryId != null }
            .groupBy { it.categoryId!! }
            .map { (categoryId, exps) ->
                CategoryTotal(
                    categoryId = categoryId,
                    total = exps.sumOf { it.effectiveAmount },
                    txCount = exps.size
                )
            }
            .sortedByDescending { it.total }
    }

    coEvery { expenseDao.getExpensesSince(any()) } answers {
        val since = firstArg<Long>()
        allExpenses.filter { it.date >= since && !it.isNotMine }
    }

    coEvery { expenseDao.getMerchantStats() } returns emptyList()
    coEvery { expenseDao.getAllMerchantStats() } returns emptyList()
    coEvery { expenseDao.getTopMerchantsForPeriod(any(), any(), any()) } returns emptyList()
    coEvery { expenseDao.getLargestExpenseForPeriod(any(), any()) } returns null
    coEvery { expenseDao.getLargestExpenseForMerchant(any(), any(), any()) } returns null
    coEvery { expenseDao.getOldestExpenseDate() } returns allExpenses.minOfOrNull { it.date }
    coEvery { expenseDao.getPurchaseCount() } returns allExpenses.count {
        it.transactionType == TransactionType.PURCHASE && !it.isNotMine
    }
}
