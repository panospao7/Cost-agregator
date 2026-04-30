package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.TestCurrencySettingsRepository
import com.yourname.expensetracker.testAnalyticsCurrencyNormalizer
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.domain.analytics.SpendingPatternType
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.every
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Calendar

class AdvancedAnalyticsEngineTest {

    private lateinit var engine: AdvancedAnalyticsEngine
    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var categoryRepository: CategoryRepository
    private lateinit var budgetRepository: BudgetRepository
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private val currencySettingsRepository = TestCurrencySettingsRepository()
    private val analyticsCurrencyNormalizer = testAnalyticsCurrencyNormalizer()

    @Before
    fun setup() {
        expenseRepository = mockk()
        categoryRepository = mockk()
        budgetRepository = mockk()
        every { timeProvider.now() } returns 1705320000000L // Jan 15, 2024
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
    }

    @Test
    fun `test getPeriodRange for WEEK`() {
        val cal = Calendar.getInstance()
        cal.set(2023, Calendar.OCTOBER, 26, 12, 0) // A Thursday
        val periodRange = engine.getPeriodRange(AnalyticsPeriod.WEEK, cal.timeInMillis)
        
        // Expected start: Monday Oct 23
        // Expected end: Monday Oct 30
        
        val startCal = Calendar.getInstance().apply { timeInMillis = periodRange.startMs }
        val endCal = Calendar.getInstance().apply { timeInMillis = periodRange.endMs }
        
        assertEquals(Calendar.MONDAY, startCal.get(Calendar.DAY_OF_WEEK))
        assertEquals(23, startCal.get(Calendar.DAY_OF_MONTH))
        
        // Comparison range should be previous week
        val comparison = periodRange.comparisonRange
        assertEquals(periodRange.startMs - 7 * 24 * 3600 * 1000, comparison?.startMs)
    }

    @Test
    fun `test getSpendingPatterns detects Weekend Warrior`() = runTest {
        // Setup a weekend-heavy spending scenario
        val satDate = Calendar.getInstance().apply { set(2023, Calendar.OCTOBER, 28, 14, 0) }.timeInMillis // Saturday
        val sunDate = Calendar.getInstance().apply { set(2023, Calendar.OCTOBER, 29, 14, 0) }.timeInMillis // Sunday
        val monDate = Calendar.getInstance().apply { set(2023, Calendar.OCTOBER, 30, 14, 0) }.timeInMillis // Monday
        
        val expenses = listOf(
            snapshot(id = 1L, amount = 100.0, date = satDate, merchant = "SatShop", categoryId = 1L),
            snapshot(id = 2L, amount = 100.0, date = sunDate, merchant = "SunShop", categoryId = 1L),
            snapshot(id = 3L, amount = 50.0, date = monDate, merchant = "MonShop", categoryId = 1L)
        )

        coEvery { expenseRepository.getExpenseSnapshotsBetween(any(), any()) } returns expenses
        
        val period = AnalyticsPeriodRange(AnalyticsPeriod.WEEK, 0, 0, "Test", null)
        val analysis = engine.getSpendingPatterns(period)
        
        val weekendWarrior = analysis.detectedPatterns.find { it.type == SpendingPatternType.WEEKEND_WARRIOR }
        assert(weekendWarrior != null)
        assertEquals(80.0f, weekendWarrior?.confidence ?: 0f, 0.1f)
    }

    @Test
    fun `test getStatisticalInsights calculations`() = runTest {
        val expenses = listOf(
            snapshot(id = 1L, amount = 10.0, date = 1000, merchant = "A", categoryId = 1L),
            snapshot(id = 2L, amount = 20.0, date = 1000 + 86400000, merchant = "B", categoryId = 1L),
            snapshot(id = 3L, amount = 30.0, date = 1000 + 2 * 86400000, merchant = "C", categoryId = 1L)
        )

        coEvery { expenseRepository.getExpenseSnapshotsBetween(any(), any()) } returns expenses
        
        val period = AnalyticsPeriodRange(AnalyticsPeriod.WEEK, 0, 0, "Test", null)
        val stats = engine.getStatisticalInsights(period)
        
        assertEquals(20.0, stats.meanTransaction, 0.01)
        assertEquals(30.0, stats.maxDailySpend, 0.01) // Assuming different days or same day summation
        assertEquals(10.0, stats.smallestTransaction!!.amount, 0.01)
        assertEquals(30.0, stats.largestTransaction!!.amount, 0.01)
    }

    private fun snapshot(
        id: Long,
        amount: Double,
        date: Long,
        merchant: String,
        categoryId: Long?
    ): ExpenseSnapshot = ExpenseSnapshot(
        id = id,
        amount = amount,
        effectiveAmount = amount,
        currency = "EUR",
        merchant = merchant,
        merchantKey = null,
        transactionType = DomainTransactionType.PURCHASE,
        date = date,
        categoryId = categoryId,
        isNotMine = false,
        transferDirection = null,
        notes = null
    )
}
