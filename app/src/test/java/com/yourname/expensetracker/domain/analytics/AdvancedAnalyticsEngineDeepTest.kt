package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.TestCurrencySettingsRepository
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.createExpense
import com.yourname.expensetracker.testAnalyticsCurrencyNormalizer
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.model.BudgetSnapshot
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class AdvancedAnalyticsEngineDeepTest {

    private lateinit var engine: AdvancedAnalyticsEngine
    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var categoryRepository: CategoryRepository
    private lateinit var budgetRepository: BudgetRepository
    private lateinit var timeProvider: TimeProvider
    private val currencySettingsRepository = TestCurrencySettingsRepository()
    private val analyticsCurrencyNormalizer = testAnalyticsCurrencyNormalizer()

    private val food = Category(id = 1L, name = "Food", icon = "🍽️", color = "#FF0000")

    @Before
    fun setup() {
        expenseRepository = mockk(relaxed = true)
        categoryRepository = mockk(relaxed = true)
        budgetRepository = mockk(relaxed = true)
        timeProvider = mockk(relaxed = true)

        engine = AdvancedAnalyticsEngine(
            expenseRepository,
            categoryRepository,
            budgetRepository,
            currencySettingsRepository,
            analyticsCurrencyNormalizer,
            timeProvider,
            Dispatchers.Unconfined,
            Dispatchers.Unconfined
        )

        coEvery { categoryRepository.getAll() } returns listOf(food)
        every { categoryRepository.allCategories } returns flowOf(listOf(food))
        coEvery { budgetRepository.getActiveBudgetSnapshots() } returns listOf(
            BudgetSnapshot(categoryId = 1L, amount = 1000.0, currency = "EUR")
        )
    }

    @Test
    fun `category analytics computes sum avg median and percentiles with interpolation`() = runTest {
        every { timeProvider.now() } returns dateMs(2026, 4, 20)
        val period = AnalyticsPeriodRange(AnalyticsPeriod.MONTH, dateMs(2026, 4, 1), dateMs(2026, 5, 1), "Apr", null)

        val current = listOf(
            createExpense(date = "2026-04-01", amount = 10.0, category = "Food"),
            createExpense(date = "2026-04-02", amount = 20.0, category = "Food"),
            createExpense(date = "2026-04-03", amount = 30.0, category = "Food"),
            createExpense(date = "2026-04-04", amount = 40.0, category = "Food")
        ).map { it.toSnapshot() }
        coEvery { expenseRepository.getExpenseSnapshotsBetween(period.startMs, period.endMs) } returns current
        coEvery { expenseRepository.getExpenseSnapshotsBetween(any(), any()) } returns current

        val result = engine.getCategoryAnalytics(period, displayCurrency = "EUR").first

        assertApproxEquals(100.0, result.totalSpent)
        assertApproxEquals(25.0, result.averagePerTransaction)
        assertApproxEquals(25.0, result.medianTransaction)
        assertApproxEquals(17.5, result.percentile25)
        assertApproxEquals(32.5, result.percentile75)
    }

    @Test
    fun `category analytics sparkline includes current day in current period`() = runTest {
        val now = dateMs(2026, 4, 20, 9)
        every { timeProvider.now() } returns now
        val period = AnalyticsPeriodRange(
            AnalyticsPeriod.MONTH,
            dateMs(2026, 4, 1),
            dateMs(2026, 5, 1),
            "Apr",
            null
        )

        val current = listOf(
            createExpenseAt(dateMs(2026, 4, 19, 10), 10.0),
            createExpenseAt(dateMs(2026, 4, 20, 8), 15.0)
        )
        coEvery { expenseRepository.getExpenseSnapshotsBetween(period.startMs, period.endMs) } returns current
        coEvery { expenseRepository.getExpenseSnapshotsBetween(any(), any()) } returns current

        val result = engine.getCategoryAnalytics(period, displayCurrency = "EUR").first

        assertFalse(result.sparklineData.isEmpty())
        // Apr 1..Apr 20 inclusive => 20 points when current day is included.
        assertEquals(20, result.sparklineData.size)
        assertApproxEquals(25.0, result.sparklineData.last())
    }

    @Test
    fun `category analytics sparkline does not include today when period is entirely before today`() = runTest {
        val now = dateMs(2026, 4, 20, 9)
        every { timeProvider.now() } returns now
        val period = AnalyticsPeriodRange(
            AnalyticsPeriod.MONTH,
            dateMs(2026, 4, 1),
            dateMs(2026, 4, 10),
            "Apr-early",
            null
        )

        val current = listOf(
            createExpenseAt(dateMs(2026, 4, 2, 10), 10.0),
            createExpenseAt(dateMs(2026, 4, 9, 8), 15.0)
        )
        coEvery { expenseRepository.getExpenseSnapshotsBetween(period.startMs, period.endMs) } returns current
        coEvery { expenseRepository.getExpenseSnapshotsBetween(any(), any()) } returns current

        val result = engine.getCategoryAnalytics(period, displayCurrency = "EUR").first

        // Apr 1..Apr 9 inclusive => 9 points; no implicit extension to Apr 20.
        assertEquals(9, result.sparklineData.size)
        assertApproxEquals(25.0, result.sparklineData.last())
    }

    @Test
    fun `category analytics sparkline does not include today when period is entirely after today`() = runTest {
        val now = dateMs(2026, 4, 20, 9)
        every { timeProvider.now() } returns now
        val period = AnalyticsPeriodRange(
            AnalyticsPeriod.MONTH,
            dateMs(2026, 4, 25),
            dateMs(2026, 5, 5),
            "Apr-late",
            null
        )

        val current = listOf(
            createExpenseAt(dateMs(2026, 4, 26, 10), 10.0),
            createExpenseAt(dateMs(2026, 5, 1, 8), 15.0)
        )
        coEvery { expenseRepository.getExpenseSnapshotsBetween(period.startMs, period.endMs) } returns current
        coEvery { expenseRepository.getExpenseSnapshotsBetween(any(), any()) } returns current

        val result = engine.getCategoryAnalytics(period, displayCurrency = "EUR").first

        // Apr 25..May 4 inclusive => 10 points; no inclusion of Apr 20.
        assertEquals(10, result.sparklineData.size)
        assertApproxEquals(25.0, result.sparklineData.last())
    }

    @Test
    fun `statistical insights computes sample stddev cv volatility and day counters`() = runTest {
        val period = AnalyticsPeriodRange(AnalyticsPeriod.WEEK, dateMs(2026, 4, 1), dateMs(2026, 4, 5), "4d", null)
        val expenses = listOf(
            createExpense(date = "2026-04-01", amount = 10.0),
            createExpense(date = "2026-04-01", amount = 20.0),
            createExpense(date = "2026-04-03", amount = 30.0),
            createExpense(date = "2026-04-03", amount = 40.0)
        ).map { it.toSnapshot() }
        coEvery { expenseRepository.getExpenseSnapshotsBetween(period.startMs, period.endMs) } returns expenses

        val stats = engine.getStatisticalInsights(period, displayCurrency = "EUR")

        // mean=25, sample stddev=sqrt(500/3)=12.9099
        assertApproxEquals(25.0, stats.meanTransaction)
        assertApproxEquals(12.9099, stats.standardDeviation, 0.01)
        assertApproxEquals((12.9099 / 25.0).toFloat(), stats.coefficientOfVariation, 0.01f)
        assertApproxEquals(((12.9099 / 25.0) * 100).toFloat(), stats.volatilityIndex, 0.5f)
        assertEquals(2, stats.daysWithSpending)
        assertEquals(2, stats.daysWithoutSpending)
        assertApproxEquals(70.0, stats.maxDailySpend)
        // canonical should be total/periodDays = 100/4 = 25
        assertApproxEquals(25.0, stats.averageDailySpend, 0.01)
    }

    @Test
    fun `histogram bins include max value and preserve total count`() = runTest {
        val period = AnalyticsPeriodRange(AnalyticsPeriod.WEEK, dateMs(2026, 4, 1), dateMs(2026, 4, 8), "week", null)
        val expenses = listOf(
            createExpense(date = "2026-04-01", amount = 0.0),
            createExpense(date = "2026-04-02", amount = 50.0),
            createExpense(date = "2026-04-03", amount = 100.0)
        ).map { it.toSnapshot() }
        coEvery { expenseRepository.getExpenseSnapshotsBetween(period.startMs, period.endMs) } returns expenses

        val stats = engine.getStatisticalInsights(period, displayCurrency = "EUR")
        val totalCount = stats.histogramBins.sumOf { it.count }

        assertEquals(3, totalCount)
        assertTrue(stats.histogramBins.last().count >= 1) // max=100 must be in last bin
    }

    @Test
    fun `spending patterns map day of week weekend split and time slots`() = runTest {
        val period = AnalyticsPeriodRange(AnalyticsPeriod.WEEK, dateMs(2026, 4, 6), dateMs(2026, 4, 13), "week", null)
        val expenses = listOf(
            // Monday 08:00 -> EARLY_MORNING
            createExpenseAt(dateMs(2026, 4, 6, 8), 20.0),
            // Saturday 13:00 -> AFTERNOON
            createExpenseAt(dateMs(2026, 4, 11, 13), 50.0),
            // Sunday 22:00 -> NIGHT
            createExpenseAt(dateMs(2026, 4, 12, 22), 30.0)
        )
        coEvery { expenseRepository.getExpenseSnapshotsBetween(period.startMs, period.endMs) } returns expenses

        val patterns = engine.getSpendingPatterns(period, displayCurrency = "EUR")

        assertApproxEquals(20.0, patterns.dayOfWeekStats[0]?.totalSpent ?: 0.0)
        assertApproxEquals(20.0, patterns.weekendVsWeekday.weekdayTotal)
        assertApproxEquals(80.0, patterns.weekendVsWeekday.weekendTotal)
        assertTrue((patterns.timeOfDayDistribution[TimeSlot.EARLY_MORNING] ?: 0.0) > 0)
        assertTrue((patterns.timeOfDayDistribution[TimeSlot.AFTERNOON] ?: 0.0) > 0)
        assertTrue((patterns.timeOfDayDistribution[TimeSlot.NIGHT] ?: 0.0) > 0)
    }

    @Test
    fun `merchant analytics computes visit frequency average days between and loyalty score`() = runTest {
        val period = AnalyticsPeriodRange(AnalyticsPeriod.YEAR, dateMs(2025, 5, 1), dateMs(2026, 5, 1), "year", null)
        val visits = (0..11).map { idx ->
            createExpense(date = LocalDate.of(2025, 5, 1).plusMonths(idx.toLong()).toString(), amount = 50.0, merchant = "Cafe")
        }.map { it.toSnapshot() }
        coEvery { expenseRepository.getExpenseSnapshotsBetween(period.startMs, period.endMs) } returns visits
        coEvery { expenseRepository.getExpenseSnapshotsBetween(any(), any()) } answers {
            val start = firstArg<Long>()
            val end = secondArg<Long>()
            visits.filter { it.date >= start && it.date < end }
        }

        val merchant = engine.getMerchantAnalytics(period, 1).first()

        assertEquals(MerchantVisitFrequency.MONTHLY, merchant.visitFrequency)
        assertApproxEquals(30.0, merchant.averageDaysBetweenVisits ?: 0.0, 2.0)
        assertApproxEquals(85f, merchant.loyaltyScore, 0.1f)
    }

    @Test
    fun `merchant analytics canonicalizes aliases and caps history at period end`() = runTest {
        val period = AnalyticsPeriodRange(AnalyticsPeriod.MONTH, dateMs(2026, 4, 1), dateMs(2026, 5, 1), "Apr", null)

        val current = listOf(
            createExpense(date = "2026-04-03", amount = 20.0, merchant = "Netflix").toSnapshot().copy(merchantKey = "netflix"),
            createExpense(date = "2026-04-17", amount = 30.0, merchant = "NETFLIX").toSnapshot().copy(merchantKey = "netflix")
        )
        val historical = listOf(
            createExpense(date = "2026-03-10", amount = 10.0, merchant = "Netflix").toSnapshot().copy(merchantKey = "netflix"),
            createExpense(date = "2026-04-20", amount = 12.0, merchant = "NETFLIX").toSnapshot().copy(merchantKey = "netflix"),
            // Must be excluded because it is after period.endMs
            createExpense(date = "2026-05-05", amount = 100.0, merchant = "Netflix").toSnapshot().copy(merchantKey = "netflix")
        )

        coEvery { expenseRepository.getExpenseSnapshotsBetween(any(), any()) } answers {
            val start = firstArg<Long>()
            val end = secondArg<Long>()
            when {
                start == period.startMs && end == period.endMs -> current
                end == period.endMs -> historical.filter { it.date >= start && it.date < end }
                else -> emptyList()
            }
        }

        val merchant = engine.getMerchantAnalytics(period, 1).first()

        assertEquals(1, engine.getMerchantAnalytics(period, 10).size)
        assertApproxEquals(50.0, merchant.totalSpent)
        assertApproxEquals(20f, merchant.priceChangePercent ?: 0f, 0.01f)
    }

    @Test
    fun `empty datasets return safe zeros`() = runTest {
        val period = AnalyticsPeriodRange(AnalyticsPeriod.MONTH, dateMs(2026, 4, 1), dateMs(2026, 5, 1), "apr", null)
        coEvery { expenseRepository.getExpenseSnapshotsBetween(period.startMs, period.endMs) } returns emptyList()
        coEvery { expenseRepository.getExpenseSnapshotsBetween(any(), any()) } returns emptyList()

        val patterns = engine.getSpendingPatterns(period, displayCurrency = "EUR")
        val stats = engine.getStatisticalInsights(period, displayCurrency = "EUR")

        assertTrue(patterns.dayOfWeekStats.isEmpty())
        assertEquals(0, stats.daysWithSpending)
        assertApproxEquals(0.0, stats.meanTransaction)
    }

    private fun dateMs(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun dateMs(year: Int, month: Int, day: Int, hour: Int): Long =
        LocalDate.of(year, month, day).atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun createExpenseAt(dateMs: Long, amount: Double): ExpenseSnapshot =
        ExpenseSnapshot(
            id = dateMs,
            amount = amount,
            effectiveAmount = amount,
            currency = "EUR",
            merchant = "M",
            merchantKey = null,
            transactionType = DomainTransactionType.PURCHASE,
            date = dateMs,
            categoryId = null,
            isNotMine = false,
            transferDirection = null,
            notes = null
        )

    private fun Expense.toSnapshot(): ExpenseSnapshot =
        ExpenseSnapshot(
            id = id,
            amount = amount,
            effectiveAmount = effectiveAmount,
            currency = currency,
            merchant = merchant,
            merchantKey = merchantKey,
            transactionType = when (transactionType) {
                com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE -> DomainTransactionType.PURCHASE
                com.yourname.expensetracker.data.database.entity.TransactionType.WITHDRAWAL -> DomainTransactionType.WITHDRAWAL
                com.yourname.expensetracker.data.database.entity.TransactionType.TRANSFER -> DomainTransactionType.TRANSFER
                com.yourname.expensetracker.data.database.entity.TransactionType.DEPOSIT -> DomainTransactionType.DEPOSIT
                com.yourname.expensetracker.data.database.entity.TransactionType.UNKNOWN -> DomainTransactionType.UNKNOWN
            },
            date = date,
            categoryId = categoryId,
            isNotMine = isNotMine,
            transferDirection = null,
            notes = notes
        )
}