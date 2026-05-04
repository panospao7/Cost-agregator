package com.yourname.expensetracker.e2e

import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.createExpense
import com.yourname.expensetracker.toAnalyticsCategoryRefs
import com.yourname.expensetracker.toExpenseSnapshots
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.CategoryTotal
import com.yourname.expensetracker.data.database.dao.MerchantStats
import com.yourname.expensetracker.data.database.dao.ManualRecurringExpenseDao
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.RecurringExpenseRepository
import com.yourname.expensetracker.domain.analytics.AnomalyDetector
import com.yourname.expensetracker.domain.analytics.AnomalyMethod
import com.yourname.expensetracker.domain.analytics.CategoryInsightEngine
import com.yourname.expensetracker.domain.analytics.DayOfWeekAnalyzer
import com.yourname.expensetracker.domain.analytics.InsightsEngine
import com.yourname.expensetracker.domain.analytics.MerchantInsightEngine
import com.yourname.expensetracker.domain.analytics.MonthlyComparisonCalculator
import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.analytics.SpendingPaceCalculator
import com.yourname.expensetracker.domain.analytics.TransferDirectionAnalytics
import com.yourname.expensetracker.domain.logic.RecurringExpenseEngine
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar

class AnalyticsPipelineTest : AnalyticsEngineTestBase() {

    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var insightsEngine: InsightsEngine
    private lateinit var analyticsCategories: List<Category>

    @Before
    override fun setUp() {
        super.setUp()

        val recurringExpenseDao = mockk<ManualRecurringExpenseDao>(relaxed = true)
        coEvery { recurringExpenseDao.getAll() } returns emptyList()

        expenseRepository = ExpenseRepository(
            database = mockk<AppDatabase>(relaxed = true),
            expenseDao = expenseDao,
            userCorrectionDao = mockk(relaxed = true),
            pendingReviewDao = mockk(relaxed = true),
            merchantCategoryRepository = mockk(relaxed = true),
            merchantNormalizer = mockk(relaxed = true),
            transferDirectionAnalytics = TransferDirectionAnalytics(),
            transactionLifecycleCoordinator = mockk<TransactionLifecycleCoordinator>(relaxed = true)
        )

        val recurringExpenseRepository = RecurringExpenseRepository(recurringExpenseDao)
        val recurringExpenseEngine = RecurringExpenseEngine(
            expenseRepository = expenseRepository,
            recurringExpenseRepository = recurringExpenseRepository,
            timeProvider = timeProvider
        )

        insightsEngine = InsightsEngine(
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

        analyticsCategories = testCategories + Category(
            id = 99L,
            name = "Rent",
            icon = "🏠",
            color = "#607D8B",
            isDefault = true
        )
    }

    @Test
    fun `golden march expenses flow through insights with expected purchase total`() = runTest {
        every { timeProvider.now() } returns dateToMillisWithTime("2026-03-31", 23, 59, 59)
        val allExpenses = goldenMarchAndFebruaryExpenses()
        stubAnalyticsDao(allExpenses)

        val snapshot = insightsEngine.generateInsights(analyticsCategories.toAnalyticsCategoryRefs(), allExpenses.toExpenseSnapshots(), "EUR")

        assertApproxEquals(1283.59, snapshot.monthlyComparison.currentTotal, 0.01)
        assertApproxEquals(1058.00, snapshot.monthlyComparison.previousTotal ?: 0.0, 0.01)
        assertEquals(12, snapshot.monthlyComparison.currentCount)
        assertEquals(5, snapshot.monthlyComparison.previousCount)
    }

    @Test
    fun `category breakdown matches golden grocery dining rent totals and grocery percentage`() = runTest {
        every { timeProvider.now() } returns dateToMillisWithTime("2026-03-31", 23, 59, 59)
        val allExpenses = goldenMarchAndFebruaryExpenses()
        stubAnalyticsDao(allExpenses)

        val snapshot = insightsEngine.generateInsights(analyticsCategories.toAnalyticsCategoryRefs(), allExpenses.toExpenseSnapshots(), "EUR")
        val byCategoryId = snapshot.categoryInsights.associateBy { it.category.id }

        val groceries = byCategoryId.getValue(2L)
        val dining = byCategoryId.getValue(1L)
        val rent = byCategoryId.getValue(99L)

        assertApproxEquals(136.10, groceries.currentTotal, 0.01)
        assertApproxEquals(10.60f, groceries.percentageOfTotal, 0.01f)
        assertApproxEquals(46.80, dining.currentTotal, 0.01)
        assertApproxEquals(800.00, rent.currentTotal, 0.01)
    }

    @Test
    fun `spending pace is correct for march day 15`() = runTest {
        every { timeProvider.now() } returns dateToMillisWithTime("2026-03-15", 23, 59, 59)
        val allExpenses = goldenMarchAndFebruaryExpenses()
        stubAnalyticsDao(allExpenses)

        val snapshot = insightsEngine.generateInsights(analyticsCategories.toAnalyticsCategoryRefs(), allExpenses.toExpenseSnapshots(), "EUR")
        val pace = snapshot.spendingPace

        assertApproxEquals(991.79, pace.currentMonthSpent, 0.01)
        assertApproxEquals(15.0, pace.daysElapsed.toDouble(), 0.0)
        assertApproxEquals(2049.03, pace.projectedTotal, 0.01)
        assertApproxEquals(175.0f, pace.pacePercentage, 0.1f)
        assertEquals(PaceStatus.OVER_PACE, pace.paceStatus)
    }

    @Test
    fun `golden march baseline produces no anomalies`() = runTest {
        every { timeProvider.now() } returns dateToMillisWithTime("2026-03-31", 23, 59, 59)
        val allExpenses = goldenMarchAndFebruaryExpenses()
        stubAnalyticsDao(allExpenses)

        val snapshot = insightsEngine.generateInsights(analyticsCategories.toAnalyticsCategoryRefs(), allExpenses.toExpenseSnapshots(), "EUR")

        assertTrue(snapshot.anomalies.isEmpty())
    }

    @Test
    fun `extreme merchant outlier is detected as anomaly`() = runTest {
        every { timeProvider.now() } returns dateToMillisWithTime("2026-03-31", 23, 59, 59)

        val outlier = createExpense(
            date = "2026-03-16",
            amount = 5000.0,
            merchant = "Luxury Purchase",
            category = "utilities",
            id = 999L
        )

        val allExpenses = goldenMarchAndFebruaryExpenses() + outlier
        stubAnalyticsDao(allExpenses)

        val merchantKey = outlier.merchantKey ?: MerchantKeyGenerator.generate(outlier.merchant)
        coEvery { expenseDao.getMerchantStats() } returns listOf(
            MerchantStats(
                merchantName = merchantKey,
                displayName = outlier.merchant,
                totalAmount = 3000.0,
                transactionCount = 6,
                averageAmount = 500.0,
                minAmount = 400.0,
                maxAmount = 650.0,
                firstDate = february2026Start,
                lastDate = march2026Start
            )
        )
        coEvery { expenseDao.getTopMerchantsForPeriod(any(), any(), any()) } returns listOf(
            MerchantStats(
                merchantName = merchantKey,
                displayName = outlier.merchant,
                totalAmount = outlier.effectiveAmount,
                transactionCount = 1,
                averageAmount = outlier.effectiveAmount,
                minAmount = outlier.effectiveAmount,
                maxAmount = outlier.effectiveAmount,
                firstDate = outlier.date,
                lastDate = outlier.date
            )
        )
        coEvery { expenseDao.getLargestExpenseForMerchant(merchantKey, any(), any()) } returns outlier

        val snapshot = insightsEngine.generateInsights(analyticsCategories.toAnalyticsCategoryRefs(), allExpenses.toExpenseSnapshots(), "EUR")
        val detected = snapshot.anomalies.firstOrNull { it.expense.id == outlier.id }

        assertTrue(detected != null)
        assertEquals(AnomalyMethod.MULTIPLIER, detected?.detectionMethod)
        assertApproxEquals(5000.0, detected?.expense?.effectiveAmount ?: 0.0, 0.01)
    }

    private fun stubAnalyticsDao(allExpenses: List<Expense>) {
        fun purchasesInRange(startMs: Long, endMs: Long): List<Expense> = allExpenses.filter {
            it.date >= startMs &&
                it.date < endMs &&
                it.transactionType == TransactionType.PURCHASE &&
                !it.isNotMine
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
                .map { (categoryId, rows) ->
                    CategoryTotal(
                        categoryId = categoryId,
                        total = rows.sumOf { it.effectiveAmount },
                        txCount = rows.size
                    )
                }
                .sortedByDescending { it.total }
        }
        coEvery { expenseDao.getAllMerchantStats() } returns emptyList()
        coEvery { expenseDao.getMerchantStats() } returns emptyList()
        coEvery { expenseDao.getTopMerchantsForPeriod(any(), any(), any()) } returns emptyList()
        coEvery { expenseDao.getLargestExpenseForPeriod(any(), any()) } answers {
            purchasesInRange(firstArg(), secondArg()).maxByOrNull { it.amount }
        }
        coEvery { expenseDao.getLargestExpenseForMerchant(any(), any(), any()) } returns null
    }

    private fun goldenMarchAndFebruaryExpenses(): List<Expense> = listOf(
        createExpense("2026-03-01", 800.00, merchant = "Rent Co", id = 1L).copy(categoryId = 99L),
        createExpense("2026-03-02", 45.30, merchant = "Lidl", category = "groceries", id = 2L),
        createExpense("2026-03-05", 62.50, merchant = "Shell Gas", category = "travel", id = 3L),
        createExpense("2026-03-07", 15.99, merchant = "Netflix", category = "entertainment", id = 4L),
        createExpense("2026-03-10", 38.70, merchant = "Lidl", category = "groceries", id = 5L),
        createExpense("2026-03-12", 24.50, merchant = "Restaurant A", category = "dining", id = 6L),
        createExpense("2026-03-15", 2500.00, type = TransactionType.DEPOSIT, merchant = "Salary", id = 7L),
        createExpense("2026-03-15", 4.80, merchant = "Coffee Shop", category = "dining", id = 8L),
        createExpense("2026-03-18", 52.10, merchant = "Lidl", category = "groceries", id = 9L),
        createExpense("2026-03-20", 89.90, merchant = "Zara", category = "entertainment", id = 10L),
        createExpense("2026-03-22", 12.30, merchant = "Pharmacy", category = "utilities", id = 11L),
        createExpense(
            date = "2026-03-25",
            amount = 35.00,
            effectiveAmount = 17.50,
            merchant = "Friend Lunch",
            category = "dining",
            id = 12L,
            isSharedExpense = true,
            mySharePercentage = 50
        ),
        createExpense("2026-03-28", 120.00, merchant = "Utilities", category = "utilities", id = 13L),
        createExpense("2026-03-30", 500.00, type = TransactionType.DEPOSIT, merchant = "Bonus", id = 14L),

        createExpense("2026-02-01", 800.00, merchant = "Rent Co", id = 101L).copy(categoryId = 99L),
        createExpense("2026-02-05", 55.00, merchant = "Lidl", category = "groceries", id = 102L),
        createExpense("2026-02-10", 58.00, merchant = "Shell Gas", category = "travel", id = 103L),
        createExpense("2026-02-15", 2500.00, type = TransactionType.DEPOSIT, merchant = "Salary", id = 104L),
        createExpense("2026-02-18", 30.00, merchant = "Restaurant B", category = "dining", id = 105L),
        createExpense("2026-02-25", 115.00, merchant = "Utilities", category = "utilities", id = 106L)
    )

    private fun dateToMillisWithTime(date: String, hour: Int, minute: Int, second: Int): Long {
        val start = LocalDate.parse(date)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        val calendar = Calendar.getInstance().apply {
            timeInMillis = start
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, second)
            set(Calendar.MILLISECOND, 0)
        }

        return calendar.timeInMillis
    }
}
