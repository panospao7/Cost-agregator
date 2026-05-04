package com.yourname.expensetracker.integration

import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.TestCurrencySettingsRepository
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.testAnalyticsCurrencyNormalizer
import com.yourname.expensetracker.testCurrencyConverter
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.BudgetForecastDao
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.analytics.AdvancedAnalyticsEngine
import com.yourname.expensetracker.domain.analytics.AnalyticsPeriod
import com.yourname.expensetracker.domain.analytics.AnalyticsPeriodRange
import com.yourname.expensetracker.domain.analytics.TotalsAggregationEngine
import com.yourname.expensetracker.domain.analytics.TransferDirectionAnalytics
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class EffectiveAmountPipelineIntegrationTest : AnalyticsEngineTestBase() {

    private val database = mockk<AppDatabase>(relaxed = true)

    @Test
    fun `end_to_end_pipeline_preserves_effective_amount_and_filters`() = runTest {
        val budgetRepository = mockk<BudgetRepository>(relaxed = true)
        val budgetForecastDao = mockk<BudgetForecastDao>(relaxed = true)

        val marchStart = ms(2026, 3, 1)
        val aprilStart = ms(2026, 4, 1)
        every { timeProvider.now() } returns ms(2026, 3, 20)

        val categories = listOf(
            Category(id = 1L, name = "Food", icon = "🍽️", color = "#FF0000"),
            Category(id = 2L, name = "Rent", icon = "🏠", color = "#00FF00")
        )
        coEvery { categoryRepository.getAll() } returns categories
        coEvery { budgetRepository.getActiveBudgets() } returns emptyList()
        coEvery { budgetRepository.getActiveBudgetSnapshots() } returns emptyList()
        coEvery { budgetForecastDao.insert(any()) } returns 1L

        val expenses = listOf(
            expense(1, 100.0, TransactionType.PURCHASE, ms(2026, 3, 2), categoryId = 1L),
            expense(2, 120.0, TransactionType.PURCHASE, ms(2026, 3, 5), categoryId = 2L, isShared = true, myShare = 40.0),
            expense(3, 70.0, TransactionType.PURCHASE, ms(2026, 3, 10), categoryId = 1L, isNotMine = true),
            expense(4, 1000.0, TransactionType.DEPOSIT, ms(2026, 3, 15), categoryId = null)
        )

        coEvery { expenseDao.getExpensesBetween(any(), any()) } answers {
            val start = firstArg<Long>()
            val end = secondArg<Long>()
            expenses.filter { it.date in start until end && !it.isNotMine }
        }
        coEvery { expenseDao.getTotalForPeriod(any(), any()) } answers {
            val start = firstArg<Long>()
            val end = secondArg<Long>()
            expenses.filter { it.date in start until end && it.transactionType == TransactionType.PURCHASE && !it.isNotMine }
                .sumOf { it.effectiveAmount }
        }
        coEvery { expenseDao.getCountForPeriod(any(), any()) } answers {
            val start = firstArg<Long>()
            val end = secondArg<Long>()
            expenses.count { it.date in start until end && it.transactionType == TransactionType.PURCHASE && !it.isNotMine }
        }
        coEvery { expenseDao.getDailyTotalsWithDatesForPeriod(any(), any()) } answers {
            val start = firstArg<Long>()
            val end = secondArg<Long>()
            expenses
                .filter { it.date in start until end && it.transactionType == TransactionType.PURCHASE && !it.isNotMine }
                .groupBy { TimePeriodUtils.getStartOfDay(it.date) }
                .toSortedMap()
                .map { (dayStart, rows) ->
                    com.yourname.expensetracker.data.database.dao.DailyTotal(
                        dayEpoch = dayStart,
                        startDate = dayStart,
                        endDate = TimePeriodUtils.addDays(dayStart, 1),
                        total = rows.sumOf { it.effectiveAmount },
                        txCount = rows.size
                    )
                }
        }
        coEvery { expenseDao.getCategoryBreakdown(any(), any()) } answers {
            val start = firstArg<Long>()
            val end = secondArg<Long>()
            expenses
                .filter { it.date in start until end && it.transactionType == TransactionType.PURCHASE && !it.isNotMine && it.categoryId != null }
                .groupBy { it.categoryId!! }
                .map { (cid, rows) ->
                    val cat = categories.first { it.id == cid }
                    com.yourname.expensetracker.data.database.dao.CategoryTotalResult(
                        id = cid,
                        name = cat.name,
                        icon = cat.icon,
                        color = cat.color,
                        total = rows.sumOf { it.effectiveAmount },
                        txCount = rows.size
                    )
                }
        }
        coEvery { expenseDao.getAverageDailySpend(any(), any()) } returns 0.0

        val repository = ExpenseRepository(
            database = database,
            expenseDao = expenseDao,
            userCorrectionDao = mockk(relaxed = true),
            pendingReviewDao = mockk(relaxed = true),
            merchantCategoryRepository = mockk(relaxed = true),
            merchantNormalizer = mockk(relaxed = true),
            transferDirectionAnalytics = mockk<TransferDirectionAnalytics>(relaxed = true),
            transactionLifecycleCoordinator = mockk<TransactionLifecycleCoordinator>(relaxed = true)
        )
        val totalsEngine = TotalsAggregationEngine(repository, timeProvider, Dispatchers.Unconfined)
        val currencySettingsRepository = TestCurrencySettingsRepository()
        val currencyConverter = testCurrencyConverter()
        val advancedEngine = AdvancedAnalyticsEngine(
            repository,
            categoryRepository,
            budgetRepository,
            currencySettingsRepository,
            testAnalyticsCurrencyNormalizer(currencyConverter),
            timeProvider,
            Dispatchers.Unconfined,
            Dispatchers.Unconfined
        )

        val expected = 140.0
        val daoTotal = expenseDao.getTotalForPeriod(marchStart, aprilStart)
        val repoTotal = repository.getTotalForPeriod(marchStart, aprilStart)
        val totalsTotal = totalsEngine.getDailyTotalsForRange(marchStart, aprilStart).sumOf { it.totalAmount }
        val (advancedAnalytics, _) = advancedEngine.getCategoryAnalytics(
            AnalyticsPeriodRange(AnalyticsPeriod.CUSTOM, marchStart, aprilStart, "Mar", null),
            "EUR"
        )
        val advancedTotal = advancedAnalytics.sumOf { it.totalSpent }

        assertApproxEquals(expected, daoTotal, 0.0001)
        assertApproxEquals(expected, repoTotal, 0.0001)
        assertApproxEquals(expected, totalsTotal, 0.0001)
        assertApproxEquals(expected, advancedTotal, 0.0001)
    }

    private fun expense(
        id: Long,
        amount: Double,
        type: TransactionType,
        date: Long,
        categoryId: Long?,
        isShared: Boolean = false,
        myShare: Double? = null,
        isNotMine: Boolean = false
    ) = Expense(
        id = id,
        amount = amount,
        merchant = "M$id",
        transactionType = type,
        date = date,
        categoryId = categoryId,
        isSharedExpense = isShared,
        myShareAmount = myShare,
        isNotMine = isNotMine
    )

    private fun ms(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
}
