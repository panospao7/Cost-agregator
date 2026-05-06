package com.yourname.expensetracker.verification

import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.TestCurrencySettingsRepository
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.testAnalyticsCurrencyNormalizer
import com.yourname.expensetracker.testCurrencyConverter
import com.yourname.expensetracker.toAnalyticsCategoryRefs
import com.yourname.expensetracker.toExpenseSnapshots
import com.yourname.expensetracker.data.database.dao.CategoryTotal
import com.yourname.expensetracker.data.database.dao.DailyTotal
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.analytics.AdvancedAnalyticsDashboard
import com.yourname.expensetracker.domain.analytics.AdvancedAnalyticsEngine
import com.yourname.expensetracker.domain.analytics.AnalyticsPeriod
import com.yourname.expensetracker.domain.analytics.AnalyticsPeriodRange
import com.yourname.expensetracker.domain.analytics.AnomalyDetector
import com.yourname.expensetracker.domain.analytics.CategoryInsightEngine
import com.yourname.expensetracker.domain.analytics.DayOfWeekAnalyzer
import com.yourname.expensetracker.domain.analytics.InsightsEngine
import com.yourname.expensetracker.domain.analytics.MerchantInsightEngine
import com.yourname.expensetracker.domain.analytics.MonthlyComparisonCalculator
import com.yourname.expensetracker.domain.analytics.SpendingPaceCalculator
import com.yourname.expensetracker.domain.analytics.TotalsAggregationEngine
import com.yourname.expensetracker.domain.analytics.TransferDirectionAnalytics
import com.yourname.expensetracker.domain.logic.RecurringExpenseEngine
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class CrossSourceVerificationTest : AnalyticsEngineTestBase() {

    private val database = mockk<AppDatabase>(relaxed = true)
    private lateinit var repository: ExpenseRepository
    private lateinit var insightsEngine: InsightsEngine
    private lateinit var advancedAnalyticsEngine: AdvancedAnalyticsEngine
    private lateinit var dashboardEngine: AdvancedAnalyticsDashboard
    private lateinit var totalsAggregationEngine: TotalsAggregationEngine
    private lateinit var spendingPaceCalculator: SpendingPaceCalculator

    override fun setUp() {
        super.setUp()

        repository = ExpenseRepository(
            database = database,
            expenseDao = expenseDao,
            userCorrectionDao = mockk(relaxed = true),
            pendingReviewDao = mockk(relaxed = true),
            merchantCategoryRepository = mockk(relaxed = true),
            merchantNormalizer = mockk(relaxed = true),
            transferDirectionAnalytics = mockk<TransferDirectionAnalytics>(relaxed = true),
            transactionLifecycleCoordinator = mockk<TransactionLifecycleCoordinator>(relaxed = true)
        )

        val recurringExpenseEngine = mockk<RecurringExpenseEngine>(relaxed = true)
        coEvery { recurringExpenseEngine.getPatterns(any()) } returns emptyList()

        val budgetRepository = mockk<BudgetRepository>(relaxed = true)
        coEvery { budgetRepository.getActiveBudgets() } returns emptyList()
        coEvery { budgetRepository.getActiveBudgetSnapshots() } returns emptyList()
        val currencySettingsRepository = TestCurrencySettingsRepository()
        val currencyConverter = testCurrencyConverter()
        val analyticsCurrencyNormalizer = testAnalyticsCurrencyNormalizer(currencyConverter)

        spendingPaceCalculator = SpendingPaceCalculator(timeProvider)
        insightsEngine = InsightsEngine(
            expenseRepository = repository,
            recurringExpenseEngine = recurringExpenseEngine,
            timeProvider = timeProvider,
            spendingPaceCalculator = spendingPaceCalculator,
            anomalyDetector = AnomalyDetector(timeProvider = mockk()),
            monthlyComparisonCalculator = MonthlyComparisonCalculator(),
            categoryInsightEngine = CategoryInsightEngine(),
            merchantInsightEngine = MerchantInsightEngine(),
            dayOfWeekAnalyzer = DayOfWeekAnalyzer()
        )

        advancedAnalyticsEngine = AdvancedAnalyticsEngine(
            expenseRepository = repository,
            categoryRepository = categoryRepository,
            budgetRepository = budgetRepository,
            currencySettingsRepository = currencySettingsRepository,
            analyticsCurrencyNormalizer = analyticsCurrencyNormalizer,
            timeProvider = timeProvider,
            defaultDispatcher = Dispatchers.Unconfined,
            ioDispatcher = Dispatchers.Unconfined
        )

        dashboardEngine = AdvancedAnalyticsDashboard(
            expenseDao = expenseDao,
            expenseRepository = repository,
            categoryRepository = categoryRepository,
            currencySettingsRepository = currencySettingsRepository,
            analyticsCurrencyNormalizer = analyticsCurrencyNormalizer,
            timeProvider = timeProvider
        )

        totalsAggregationEngine = TotalsAggregationEngine(
            expenseRepository = repository,
            timeProvider = timeProvider,
            multiCurrencyRepository = mockk(),
            categoryRepository = mockk(),
            ioDispatcher = Dispatchers.Unconfined
        )

        coEvery { expenseDao.getMerchantStats() } returns emptyList()
        coEvery { expenseDao.getAllMerchantStats() } returns emptyList()
        coEvery { expenseDao.getTopMerchantsForPeriod(any(), any(), any()) } returns emptyList()
        coEvery { expenseDao.getLargestExpenseForMerchant(any(), any(), any()) } returns null
    }

    @Test
    fun `monthly total is consistent across repository insights advanced and dashboard`() = runTest {
        val dataset = goldenSimpleMonthPurchases()
        val marchStart = toMillis(2026, 3, 1)
        val aprilStart = toMillis(2026, 4, 1)
        val march15 = toMillis(2026, 3, 15)

        mockExpensesByRange(dataset)
        io.mockk.every { timeProvider.now() } returns march15

        val repoTotal = repository.getTotalForPeriod(marchStart, aprilStart)
        val insightsTotal = insightsEngine
            .generateInsights(testCategories.toAnalyticsCategoryRefs(), dataset.toExpenseSnapshots(), "EUR")
            .monthlyComparison
            .currentTotal
        val (advancedCategoryAnalytics, _) = advancedAnalyticsEngine
            .getCategoryAnalytics(AnalyticsPeriodRange(AnalyticsPeriod.MONTH, marchStart, aprilStart, "Mar 2026", null), "EUR")
        val advancedTotal = advancedCategoryAnalytics.sumOf { it.totalSpent }
        val dashboardTotal = dashboardEngine.generateDashboardData(marchStart, aprilStart).totalSpent

        assertApproxEquals(repoTotal, insightsTotal, 0.01, "Repository vs Insights: ")
        assertApproxEquals(repoTotal, advancedTotal, 0.01, "Repository vs Advanced Engine: ")
        assertApproxEquals(repoTotal, dashboardTotal, 0.01, "Repository vs Dashboard: ")
    }

    @Test
    fun `daily average is consistent across advanced totals-engine and manual calculation`() = runTest {
        val dataset = goldenDayOfWeekSpread()
        val start = toMillis(2026, 3, 2)
        val end = toMillis(2026, 3, 9)

        mockExpensesByRange(dataset)

        val period = AnalyticsPeriodRange(AnalyticsPeriod.WEEK, start, end, "Mar 2-8", null)
        val (statisticalInsights, _) = advancedAnalyticsEngine.getStatisticalInsights(period, "EUR")
        val advancedAvg = statisticalInsights.averageDailySpend
        val repoTotal = repository.getTotalForPeriod(start, end)
        val periodDays = ((end - start) / TimePeriodUtils.DAY_IN_MILLIS).toInt()
        val manualAvg = repoTotal / periodDays
        val totalsEngineAvg = totalsAggregationEngine.getDailyTotalsForRange(start, end)
            .first().sumOf { it.totalAmount } / periodDays

        assertApproxEquals(manualAvg, advancedAvg, 0.01, "Manual vs Advanced: ")
        assertApproxEquals(manualAvg, totalsEngineAvg, 0.01, "Manual vs TotalsAggregationEngine: ")
    }

    @Test
    fun `category totals are consistent across repository insights and dashboard`() = runTest {
        val dataset = goldenSimpleMonthPurchases()
        val marchStart = toMillis(2026, 3, 1)
        val aprilStart = toMillis(2026, 4, 1)
        val march15 = toMillis(2026, 3, 15)

        mockExpensesByRange(dataset)
        io.mockk.every { timeProvider.now() } returns march15

        val repoTotals = repository.getCategoryTotalsForPeriod(marchStart, aprilStart)
            .associate { it.categoryId to it.total }

        val insightTotals = insightsEngine.generateInsights(testCategories.toAnalyticsCategoryRefs(), dataset.toExpenseSnapshots(), "EUR")
            .categoryInsights
            .associate { it.category.id to it.currentTotal }

        val dashboardTotals = dashboardEngine.generateDashboardData(marchStart, aprilStart)
            .topCategories
            .associate { it.categoryId to it.amount }

        assertEquals(repoTotals.keys, insightTotals.keys)
        assertEquals(repoTotals.keys, dashboardTotals.keys)

        repoTotals.forEach { (categoryId, amount) ->
            assertApproxEquals(amount, insightTotals.getValue(categoryId), 0.01, "Repo vs Insights category=$categoryId: ")
            assertApproxEquals(amount, dashboardTotals.getValue(categoryId), 0.01, "Repo vs Dashboard category=$categoryId: ")
        }
    }

    @Test
    fun `spending pace percentage is consistent between insights and calculator`() = runTest {
        val dataset = goldenTwoMonthComparison()
        val march15 = toMillis(2026, 3, 15)
        val marchStart = toMillis(2026, 3, 1)
        val februaryStart = toMillis(2026, 2, 1)

        mockExpensesByRange(dataset)
        io.mockk.every { timeProvider.now() } returns march15

        val insightsPace = insightsEngine.generateInsights(testCategories.toAnalyticsCategoryRefs(), dataset.toExpenseSnapshots(), "EUR").spendingPace.pacePercentage
        val calculatorPace = spendingPaceCalculator.calculate(
            currentMonthStart = marchStart,
            previousMonthStart = februaryStart,
            previousMonthEnd = marchStart,
            allExpenses = dataset.toExpenseSnapshots(),
            displayCurrency = "EUR"
        ).pacePercentage

        // Canonical definition:
        // pace% = (currentDailyRate / baselineDailyRate) * 100
        // currentDailyRate = currentSpent / daysElapsed = 60 / 15 = 4.0
        // baselineDailyRate = previousMonthTotal / daysInPreviousMonth = 40 / 28 = 1.42857...
        // expected pace = 280%
        val expectedCanonicalPace = ((60.0 / 15.0) / (40.0 / 28.0) * 100.0).toFloat()

        assertApproxEquals(insightsPace, calculatorPace, 0.01f, "Insights vs SpendingPaceCalculator: ")
        assertApproxEquals(expectedCanonicalPace, insightsPace, 0.01f, "Canonical formula vs Insights: ")
        assertApproxEquals(expectedCanonicalPace, calculatorPace, 0.01f, "Canonical formula vs Calculator: ")
    }

    @Test
    fun `spending pace returns no baseline when previous month has no spending`() = runTest {
        val dataset = goldenSimpleMonthPurchases() // only March expenses, no February baseline
        val march15 = toMillis(2026, 3, 15)
        val marchStart = toMillis(2026, 3, 1)
        val februaryStart = toMillis(2026, 2, 1)

        mockExpensesByRange(dataset)
        io.mockk.every { timeProvider.now() } returns march15

        val insightsPace = insightsEngine.generateInsights(testCategories.toAnalyticsCategoryRefs(), dataset.toExpenseSnapshots(), "EUR").spendingPace
        val calculatorPace = spendingPaceCalculator.calculate(
            currentMonthStart = marchStart,
            previousMonthStart = februaryStart,
            previousMonthEnd = marchStart,
            allExpenses = dataset.toExpenseSnapshots(),
            displayCurrency = "EUR"
        )

        assertEquals(0f, insightsPace.pacePercentage)
        assertEquals(0f, calculatorPace.pacePercentage)
        assertEquals(com.yourname.expensetracker.domain.analytics.PaceStatus.NO_BASELINE, insightsPace.paceStatus)
        assertEquals(com.yourname.expensetracker.domain.analytics.PaceStatus.NO_BASELINE, calculatorPace.paceStatus)
    }

    @Test
    fun `transaction count is consistent across repository advanced and totals aggregation`() = runTest {
        val dataset = goldenSimpleMonthPurchases()
        val marchStart = toMillis(2026, 3, 1)
        val aprilStart = toMillis(2026, 4, 1)

        mockExpensesByRange(dataset)

        val repoCount = repository.getExpensesBetween(marchStart, aprilStart).size

        val (statisticalInsights2, _) = advancedAnalyticsEngine
            .getStatisticalInsights(AnalyticsPeriodRange(AnalyticsPeriod.MONTH, marchStart, aprilStart, "Mar 2026", null), "EUR")
        val advancedCount = statisticalInsights2.histogramBins
            .sumOf { it.count }

        val totalsEngineCount = totalsAggregationEngine
            .getDailyTotalsForRange(marchStart, aprilStart)
            .first().sumOf { it.transactionCount }

        assertEquals(repoCount, advancedCount)
        assertEquals(repoCount, totalsEngineCount)
    }

    private fun mockExpensesByRange(expenses: List<Expense>) {
        val purchases = expenses.filter { it.transactionType == TransactionType.PURCHASE && !it.isNotMine }

        fun inRange(start: Long, end: Long): List<Expense> =
            purchases.filter { it.date in start until end }

        fun categoryTotals(start: Long, end: Long): List<CategoryTotal> {
            return inRange(start, end)
                .filter { it.categoryId != null }
                .groupBy { it.categoryId!! }
                .map { (categoryId, rows) ->
                    CategoryTotal(
                        categoryId = categoryId,
                        total = rows.sumOf { it.effectiveAmount },
                        txCount = rows.size
                    )
                }
                .sortedByDescending { it.total }
        }

        fun dailyTotals(start: Long, end: Long): List<DailyTotal> {
            return inRange(start, end)
                .groupBy { TimePeriodUtils.getStartOfDay(it.date) }
                .toSortedMap()
                .map { (dayStart, rows) ->
                    DailyTotal(
                        dayEpoch = dayStart,
                        startDate = dayStart,
                        endDate = TimePeriodUtils.addDays(dayStart, 1),
                        total = rows.sumOf { it.effectiveAmount },
                        txCount = rows.size
                    )
                }
        }

        coEvery { expenseDao.getExpensesBetween(any(), any()) } answers {
            inRange(firstArg(), secondArg())
        }
        coEvery { expenseDao.getExpensesByTypeBetween(any(), any(), TransactionType.PURCHASE.name) } answers {
            inRange(firstArg(), secondArg())
        }
        io.mockk.every { expenseDao.getExpensesBetweenFlow(any(), any()) } answers {
            flowOf(inRange(firstArg(), secondArg()))
        }
        io.mockk.every { expenseDao.getExpensesByTypeBetweenFlow(any(), any(), TransactionType.PURCHASE.name) } answers {
            flowOf(inRange(firstArg(), secondArg()))
        }

        coEvery { expenseDao.getTotalForPeriod(any(), any()) } answers {
            inRange(firstArg(), secondArg()).sumOf { it.effectiveAmount }
        }
        coEvery { expenseDao.getCountForPeriod(any(), any()) } answers {
            inRange(firstArg(), secondArg()).size
        }
        coEvery { expenseDao.getCategoryTotalsForPeriod(any(), any()) } answers {
            categoryTotals(firstArg(), secondArg())
        }
        coEvery { expenseDao.getDailyTotalsWithDatesForPeriod(any(), any()) } answers {
            dailyTotals(firstArg(), secondArg())
        }
        coEvery { expenseDao.getAverageDailySpend(any(), any()) } answers {
            val totals = dailyTotals(firstArg(), secondArg())
            totals.map { it.total }.average().takeIf { !it.isNaN() }
        }

        coEvery { expenseDao.getTotalSpentBetween(any(), any()) } answers {
            inRange(firstArg(), secondArg()).sumOf { it.effectiveAmount }
        }
        coEvery { expenseDao.getCategoryTotalsBetween(any(), any()) } answers {
            categoryTotals(firstArg(), secondArg())
        }
        coEvery { expenseDao.getAll() } returns purchases
        coEvery { expenseDao.getPurchaseCount() } returns purchases.size
        coEvery { expenseDao.getOldestExpenseDate() } returns purchases.minOfOrNull { it.date }
        io.mockk.every { expenseDao.getTotalSpentFlow() } returns flowOf(purchases.sumOf { it.effectiveAmount })
    }

    private fun toMillis(year: Int, month1Based: Int, day: Int): Long {
        return LocalDate.of(year, month1Based, day)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }

    private fun goldenSimpleMonthPurchases(): List<Expense> = listOf(
        createPurchaseExpense(id = 1L, date = "2026-03-05", amount = 10.0, categoryId = 1L, merchant = "Coffee Shop"),
        createPurchaseExpense(id = 2L, date = "2026-03-15", amount = 20.0, categoryId = 2L, merchant = "Grocery Store"),
        createPurchaseExpense(id = 3L, date = "2026-03-25", amount = 30.0, categoryId = 3L, merchant = "Restaurant")
    )

    private fun goldenDayOfWeekSpread(): List<Expense> = listOf(
        createPurchaseExpense(id = 19L, date = "2026-03-02", amount = 10.0, categoryId = 1L, merchant = "Monday Shop"),
        createPurchaseExpense(id = 20L, date = "2026-03-03", amount = 20.0, categoryId = 2L, merchant = "Tuesday Store"),
        createPurchaseExpense(id = 21L, date = "2026-03-04", amount = 30.0, categoryId = 2L, merchant = "Wednesday Mart"),
        createPurchaseExpense(id = 22L, date = "2026-03-05", amount = 40.0, categoryId = 3L, merchant = "Thursday Place"),
        createPurchaseExpense(id = 23L, date = "2026-03-06", amount = 50.0, categoryId = 3L, merchant = "Friday Venue"),
        createPurchaseExpense(id = 24L, date = "2026-03-07", amount = 60.0, categoryId = 4L, merchant = "Saturday Spot"),
        createPurchaseExpense(id = 25L, date = "2026-03-08", amount = 70.0, categoryId = 4L, merchant = "Sunday Location")
    )

    private fun goldenTwoMonthComparison(): List<Expense> =
        goldenSimpleMonthPurchases() + listOf(
            createPurchaseExpense(id = 9L, date = "2026-02-05", amount = 15.0, categoryId = 1L, merchant = "Coffee Shop"),
            createPurchaseExpense(id = 10L, date = "2026-02-15", amount = 25.0, categoryId = 2L, merchant = "Grocery Store")
        )

    private fun createPurchaseExpense(
        id: Long,
        date: String,
        amount: Double,
        categoryId: Long,
        merchant: String
    ): Expense {
        return Expense(
            id = id,
            amount = amount,
            merchant = merchant,
            transactionType = TransactionType.PURCHASE,
            date = LocalDate.parse(date)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli(),
            categoryId = categoryId
        )
    }
}