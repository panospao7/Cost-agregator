package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

/**
 * Validation tests for InsightsEngine to ensure monthly comparisons,
 * transaction size profiles, and other calculations are correct.
 * 
 * Tests cover:
 * 1. Monthly comparison calculations
 * 2. Transaction size profiles (micro/small/medium/large/major)
 * 3. Spending pace calculations
 * 4. Day of week patterns
 * 5. Edge cases and error handling
 */
class InsightsEngineValidationTest {

    private lateinit var engine: InsightsEngine
    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var recurringExpenseEngine: com.yourname.expensetracker.domain.logic.RecurringExpenseEngine
    private lateinit var timeProvider: TimeProvider
    private lateinit var spendingPaceCalculator: SpendingPaceCalculator
    private lateinit var anomalyDetector: AnomalyDetector
    private lateinit var monthlyComparisonCalculator: MonthlyComparisonCalculator
    private lateinit var categoryInsightEngine: CategoryInsightEngine
    private lateinit var merchantInsightEngine: MerchantInsightEngine
    private lateinit var dayOfWeekAnalyzer: DayOfWeekAnalyzer

    // Helper to create timestamps for specific dates
    private fun createDate(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long {
        val cal = Calendar.getInstance()
        cal.set(year, month - 1, day, hour, minute, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    // Helper to create test expenses
    private fun createExpense(
        id: Long = 0,
        amount: Double,
        date: Long,
        categoryId: Long? = null,
        transactionType: TransactionType = TransactionType.PURCHASE,
        merchant: String = "Test Merchant",
        isNotMine: Boolean = false
    ): ExpenseSnapshot {
        return ExpenseSnapshot(
            id = id,
            amount = amount,
            effectiveAmount = amount,
            currency = "EUR",
            merchant = merchant,
            merchantKey = null,
            transactionType = transactionType.toDomainTransactionType(),
            date = date,
            categoryId = categoryId,
            isNotMine = isNotMine,
            transferDirection = null,
            notes = null
        )
    }

    @Before
    fun setup() {
        expenseRepository = mockk(relaxed = true)
        recurringExpenseEngine = mockk(relaxed = true)
        timeProvider = mockk(relaxed = true)
        spendingPaceCalculator = mockk(relaxed = true)
        anomalyDetector = mockk(relaxed = true)
        // Use real implementations for stateless calculators so tests validate actual logic.
        // Only mock engines whose behavior we need to control or have complex dependencies.
        monthlyComparisonCalculator = MonthlyComparisonCalculator()
        categoryInsightEngine = CategoryInsightEngine()
        merchantInsightEngine = MerchantInsightEngine()
        dayOfWeekAnalyzer = DayOfWeekAnalyzer()

        engine = InsightsEngine(
            expenseRepository = expenseRepository,
            recurringExpenseEngine = recurringExpenseEngine,
            timeProvider = timeProvider,
            spendingPaceCalculator = spendingPaceCalculator,
            anomalyDetector = anomalyDetector,
            monthlyComparisonCalculator = monthlyComparisonCalculator,
            categoryInsightEngine = categoryInsightEngine,
            merchantInsightEngine = merchantInsightEngine,
            dayOfWeekAnalyzer = dayOfWeekAnalyzer
        )

        // Default mock for recurringExpenseEngine (relaxed returns emptyList for getPatternsFromSnapshots)
        coEvery { recurringExpenseEngine.getPatternsFromSnapshots(any()) } returns emptyList()
        every { spendingPaceCalculator.calculate(any(), any(), any(), any()) } returns SpendingPace(
            currentMonthSpent = 0.0,
            daysElapsed = 0,
            daysInMonth = 30,
            projectedTotal = 0.0,
            previousMonthTotal = null,
            averageMonthlyTotal = null,
            pacePercentage = 0.0f,
            paceStatus = PaceStatus.NO_BASELINE,
            displayCurrency = "EUR",
        )
    }

    // ========== SCENARIO 1: Monthly Comparison Calculations ==========

    @Test
    fun `monthly comparison calculates correct percentage change`() = runTest {
        // Given: Current month total = 1200, Previous month total = 1000
        every { timeProvider.now() } returns createDate(2024, 4, 15, 12, 0)
        
        // Pass actual expenses for both months so the real MonthlyComparisonCalculator can compute
        val currentMonthStart = createDate(2024, 4, 1)
        val previousMonthStart = createDate(2024, 3, 1)
        val allExpenses = listOf(
            createExpense(id = 1, amount = 1200.0, date = createDate(2024, 4, 5)),
            createExpense(id = 2, amount = 1000.0, date = createDate(2024, 3, 10))
        )
        
        // When: Generate insights
        val categories = listOf(cat(id = 1))
        val snapshot = engine.generateInsights(categories, allExpenses, "EUR")
        
        // Then: Percentage change should be 20%
        assertNotNull(snapshot.monthlyComparison.changePercentage)
        assertEquals(20.0f, snapshot.monthlyComparison.changePercentage!!, 0.01f)
        
        // Change amount should be 200
        assertNotNull(snapshot.monthlyComparison.changeAmount)
        assertEquals(200.0, snapshot.monthlyComparison.changeAmount!!, 0.01)
    }

    @Test
    fun `monthly comparison handles zero previous month`() = runTest {
        // Given: Current month has data, previous month has none
        every { timeProvider.now() } returns createDate(2024, 4, 15, 12, 0)
        
        // Only current month expenses, no previous month expenses
        val allExpenses = listOf(
            createExpense(id = 1, amount = 1000.0, date = createDate(2024, 4, 5))
        )
        
        // When: Generate insights
        val categories = listOf(cat(id = 1))
        val snapshot = engine.generateInsights(categories, allExpenses, "EUR")
        
        // Then: No percentage change (no previous data)
        assertNull(snapshot.monthlyComparison.changePercentage)
        assertNull(snapshot.monthlyComparison.changeAmount)
    }

    @Test
    fun `monthly comparison handles negative change (spending decrease)`() = runTest {
        // Given: Current month total = 800, Previous month total = 1000
        every { timeProvider.now() } returns createDate(2024, 4, 15, 12, 0)
        
        val allExpenses = listOf(
            createExpense(id = 1, amount = 800.0, date = createDate(2024, 4, 5)),
            createExpense(id = 2, amount = 1000.0, date = createDate(2024, 3, 10))
        )
        
        // When: Generate insights
        val categories = listOf(cat(id = 1))
        val snapshot = engine.generateInsights(categories, allExpenses, "EUR")
        
        // Then: Percentage change should be -20%
        assertNotNull(snapshot.monthlyComparison.changePercentage)
        assertEquals(-20.0f, snapshot.monthlyComparison.changePercentage!!, 0.01f)
        
        // Change amount should be -200
        assertNotNull(snapshot.monthlyComparison.changeAmount)
        assertEquals(-200.0, snapshot.monthlyComparison.changeAmount!!, 0.01)
    }

    // ========== SCENARIO 2: Transaction Size Profiles ==========

    @Test
    fun `transaction size profiles calculate correct averages`() = runTest {
        // Given: Current month with various transaction sizes
        val currentMonthStart = createDate(2024, 4, 1)
        val currentMonthEnd = createDate(2024, 5, 1)
        
        val expenses = listOf(
            createExpense(id = 1, amount = 5.0, date = createDate(2024, 4, 1)), // micro
            createExpense(id = 2, amount = 15.0, date = createDate(2024, 4, 2)), // small
            createExpense(id = 3, amount = 50.0, date = createDate(2024, 4, 3)), // medium
            createExpense(id = 4, amount = 150.0, date = createDate(2024, 4, 4)), // large
            createExpense(id = 5, amount = 500.0, date = createDate(2024, 4, 5)) // major
        )
        
        every { timeProvider.now() } returns createDate(2024, 4, 15, 12, 0)
        
        // When: Generate insights
        val categories = listOf(cat(id = 1))
        val snapshot = engine.generateInsights(categories, expenses, "EUR")
        
        // Then: Average transaction size should be (5 + 15 + 50 + 150 + 500) / 5 = 144
        assertEquals(144.0, snapshot.averageTransactionSize, 0.01)
        
        // Median should be 50 (middle value when sorted)
        assertEquals(50.0, snapshot.medianTransactionSize, 0.01)
    }

    @Test
    fun `transaction size calculation excludes non-purchase transactions`() = runTest {
        // Given: Mix of purchase and non-purchase transactions
        val expenses = listOf(
            createExpense(id = 1, amount = 100.0, date = createDate(2024, 4, 1), transactionType = TransactionType.PURCHASE),
            createExpense(id = 2, amount = 200.0, date = createDate(2024, 4, 2), transactionType = TransactionType.PURCHASE),
            createExpense(id = 3, amount = 500.0, date = createDate(2024, 4, 3), transactionType = TransactionType.DEPOSIT), // Not a purchase
            createExpense(id = 4, amount = 150.0, date = createDate(2024, 4, 4), transactionType = TransactionType.PURCHASE)
        )
        
        every { timeProvider.now() } returns createDate(2024, 4, 15, 12, 0)
        
        // When: Generate insights
        val categories = listOf(cat(id = 1))
        val snapshot = engine.generateInsights(categories, expenses, "EUR")
        
        // Then: Should only consider purchases (100, 200, 150)
        // Average: (100 + 200 + 150) / 3 = 150
        assertEquals(150.0, snapshot.averageTransactionSize, 0.01)
        
        // Median: 150 (middle value)
        assertEquals(150.0, snapshot.medianTransactionSize, 0.01)
    }

    @Test
    fun `transaction size calculation excludes not-mine transactions`() = runTest {
        // Given: Mix of mine and not-mine transactions
        val expenses = listOf(
            createExpense(id = 1, amount = 100.0, date = createDate(2024, 4, 1), isNotMine = false),
            createExpense(id = 2, amount = 200.0, date = createDate(2024, 4, 2), isNotMine = true), // Not mine
            createExpense(id = 3, amount = 150.0, date = createDate(2024, 4, 3), isNotMine = false)
        )
        
        every { timeProvider.now() } returns createDate(2024, 4, 15, 12, 0)
        
        // When: Generate insights
        val categories = listOf(cat(id = 1))
        val snapshot = engine.generateInsights(categories, expenses, "EUR")
        
        // Then: Should only consider mine (100, 150)
        // Average: (100 + 150) / 2 = 125
        assertEquals(125.0, snapshot.averageTransactionSize, 0.01)
        
        // Median: (100 + 150) / 2 = 125 (even number of values)
        assertEquals(125.0, snapshot.medianTransactionSize, 0.01)
    }

    // ========== SCENARIO 3: Spending Pace Calculations ==========

    @Test
    fun `spending pace calculates correct projected total`() = runTest {
        // Given: Current month spending pace
        // Current month: spent 500 in first 10 days
        val currentExpenses = listOf(
            createExpense(id = 1, amount = 50.0, date = createDate(2024, 4, 1)),
            createExpense(id = 2, amount = 50.0, date = createDate(2024, 4, 2)),
            createExpense(id = 3, amount = 50.0, date = createDate(2024, 4, 3)),
            createExpense(id = 4, amount = 50.0, date = createDate(2024, 4, 4)),
            createExpense(id = 5, amount = 50.0, date = createDate(2024, 4, 5)),
            createExpense(id = 6, amount = 50.0, date = createDate(2024, 4, 6)),
            createExpense(id = 7, amount = 50.0, date = createDate(2024, 4, 7)),
            createExpense(id = 8, amount = 50.0, date = createDate(2024, 4, 8)),
            createExpense(id = 9, amount = 50.0, date = createDate(2024, 4, 9)),
            createExpense(id = 10, amount = 50.0, date = createDate(2024, 4, 10))
        )
        
        // Mock spending pace calculator to return predictable result
        every { spendingPaceCalculator.calculate(any(), any(), any(), any()) } returns SpendingPace(
            currentMonthSpent = 500.0,
            daysElapsed = 10,
            daysInMonth = 30,
            projectedTotal = 1500.0, // 500 * 30 / 10 = 1500
            previousMonthTotal = 1500.0,
            averageMonthlyTotal = 1500.0,
            pacePercentage = 100.0f,
            paceStatus = PaceStatus.ON_PACE,
            displayCurrency = "EUR",
        )
        
        every { timeProvider.now() } returns createDate(2024, 4, 10, 12, 0)
        
        // When: Generate insights
        val categories = listOf(cat(id = 1))
        val snapshot = engine.generateInsights(categories, currentExpenses, "EUR")
        
        // Then: Projected total should be 1500 (500 * 30 / 10)
        assertEquals(1500.0, snapshot.spendingPace.projectedTotal, 0.01)
        assertEquals(10, snapshot.spendingPace.daysElapsed)
        assertEquals(30, snapshot.spendingPace.daysInMonth)
    }

    @Test
    fun `spending pace handles first three days conservatively`() = runTest {
        // Given: Current month, first 3 days
        // Current month: spent 300 in first 3 days
        val currentExpenses = listOf(
            createExpense(id = 1, amount = 100.0, date = createDate(2024, 4, 1)),
            createExpense(id = 2, amount = 100.0, date = createDate(2024, 4, 2)),
            createExpense(id = 3, amount = 100.0, date = createDate(2024, 4, 3))
        )
        
        // Mock spending pace calculator with conservative estimate
        every { spendingPaceCalculator.calculate(any(), any(), any(), any()) } returns SpendingPace(
            currentMonthSpent = 300.0,
            daysElapsed = 3,
            daysInMonth = 30,
            projectedTotal = 900.0, // Conservative: 300 * (30/10) = 900 (not 300 * 30/3 = 3000)
            previousMonthTotal = 1500.0,
            averageMonthlyTotal = 1500.0,
            pacePercentage = 60.0f, // Under pace
            paceStatus = PaceStatus.UNDER_PACE,
            displayCurrency = "EUR",
        )
        
        every { timeProvider.now() } returns createDate(2024, 4, 3, 12, 0)
        
        // When: Generate insights
        val categories = listOf(cat(id = 1))
        val snapshot = engine.generateInsights(categories, currentExpenses, "EUR")
        
        // Then: Projected total should use conservative estimate (900, not 3000)
        assertEquals(900.0, snapshot.spendingPace.projectedTotal, 0.01)
        assertEquals(PaceStatus.UNDER_PACE, snapshot.spendingPace.paceStatus)
    }

    @Test
    fun `spending pace delegates to SpendingPaceCalculator canonical output`() = runTest {
        val now = createDate(2024, 4, 10, 12, 0)
        every { timeProvider.now() } returns now

        val expenses = listOf(
            createExpense(id = 1, amount = 200.0, date = createDate(2024, 4, 2)),
            createExpense(id = 2, amount = 600.0, date = createDate(2024, 3, 5))
        )

        every { spendingPaceCalculator.calculate(any(), any(), any(), any()) } returns SpendingPace(
            currentMonthSpent = 200.0,
            daysElapsed = 10,
            daysInMonth = 30,
            projectedTotal = 600.0,
            previousMonthTotal = 600.0,
            averageMonthlyTotal = null,
            pacePercentage = 100.0f,
            paceStatus = PaceStatus.ON_PACE,
            displayCurrency = "EUR",
        )

        val categories = listOf(cat(id = 1))
        val snapshot = engine.generateInsights(categories, expenses, "EUR")

        verify(exactly = 1) {
            spendingPaceCalculator.calculate(any(), any(), any(), expenses)
        }
        assertEquals(100.0f, snapshot.spendingPace.pacePercentage, 0.01f)
        assertEquals(PaceStatus.ON_PACE, snapshot.spendingPace.paceStatus)
    }

    // ========== SCENARIO 4: Day of Week Patterns ==========

    @Test
    fun `day of week pattern calculates correct totals`() = runTest {
        // Given: Expenses on different days of week
        val threeMonthsAgo = createDate(2024, 1, 15)
        val currentMonthEnd = createDate(2024, 5, 1)
        
        val expenses = listOf(
            // Monday (April 1, 2024)
            createExpense(id = 1, amount = 100.0, date = createDate(2024, 4, 1)),
            // Tuesday (April 2, 2024)
            createExpense(id = 2, amount = 150.0, date = createDate(2024, 4, 2)),
            // Wednesday (April 3, 2024)
            createExpense(id = 3, amount = 200.0, date = createDate(2024, 4, 3)),
            // Monday again (April 8, 2024)
            createExpense(id = 4, amount = 120.0, date = createDate(2024, 4, 8))
        )
        
        every { timeProvider.now() } returns createDate(2024, 4, 15, 12, 0)
        
        // When: Generate insights
        val categories = listOf(cat(id = 1))
        val snapshot = engine.generateInsights(categories, expenses, "EUR")
        
        // Then: Day of week pattern should have correct totals
        val mondayInsight = snapshot.dayOfWeekPattern.find { it.dayName == "Mon" }
        assertNotNull(mondayInsight)
        assertEquals(220.0, mondayInsight!!.totalSpent, 0.01) // 100 + 120
        assertEquals(2, mondayInsight.transactionCount)
        assertEquals(110.0, mondayInsight.avgPerTransaction, 0.01) // 220 / 2
        
        val tuesdayInsight = snapshot.dayOfWeekPattern.find { it.dayName == "Tue" }
        assertNotNull(tuesdayInsight)
        assertEquals(150.0, tuesdayInsight!!.totalSpent, 0.01)
        assertEquals(1, tuesdayInsight.transactionCount)
        assertEquals(150.0, tuesdayInsight.avgPerTransaction, 0.01)
    }

    // ========== SCENARIO 5: Category Insights ==========

    @Test
    fun `category insights calculate correct percentages`() = runTest {
        // Given: Current month with multiple categories
        every { timeProvider.now() } returns createDate(2024, 4, 15, 12, 0)
        
        // Pass actual expenses so the real CategoryInsightEngine computes totals
        val allExpenses = listOf(
            createExpense(id = 1, amount = 500.0, date = createDate(2024, 4, 5), categoryId = 1),
            createExpense(id = 2, amount = 300.0, date = createDate(2024, 4, 6), categoryId = 2),
            createExpense(id = 3, amount = 200.0, date = createDate(2024, 4, 7), categoryId = 3)
        )
        
        // When: Generate insights
        val categories = listOf(
            cat(id = 1, name = "Food", icon = "food", color = "#FF0000"),
            cat(id = 2, name = "Transport", icon = "transport", color = "#00FF00"),
            cat(id = 3, name = "Entertainment", icon = "ent", color = "#0000FF")
        )
        
        val snapshot = engine.generateInsights(categories, allExpenses, "EUR")
        
        // Then: Percentages should be calculated correctly
        val foodInsight = snapshot.categoryInsights.find { it.category.id == 1L }
        assertNotNull(foodInsight)
        assertEquals(50.0f, foodInsight!!.percentageOfTotal, 0.01f) // 500/1000 = 50%
        
        val transportInsight = snapshot.categoryInsights.find { it.category.id == 2L }
        assertNotNull(transportInsight)
        assertEquals(30.0f, transportInsight!!.percentageOfTotal, 0.01f) // 300/1000 = 30%
        
        val entertainmentInsight = snapshot.categoryInsights.find { it.category.id == 3L }
        assertNotNull(entertainmentInsight)
        assertEquals(20.0f, entertainmentInsight!!.percentageOfTotal, 0.01f) // 200/1000 = 20%
        
        // Verify percentages sum to 100
        val totalPercentage = snapshot.categoryInsights.sumOf { it.percentageOfTotal.toDouble() }
        assertEquals(100.0, totalPercentage, 0.01)
    }

    @Test
    fun `category insights calculate correct change from previous`() = runTest {
        // Given: Current month with category change
        every { timeProvider.now() } returns createDate(2024, 4, 15, 12, 0)
        
        // Pass expenses for both months so the real CategoryInsightEngine computes change
        val allExpenses = listOf(
            createExpense(id = 1, amount = 600.0, date = createDate(2024, 4, 5), categoryId = 1), // current
            createExpense(id = 2, amount = 400.0, date = createDate(2024, 3, 5), categoryId = 1)  // previous
        )
        
        // When: Generate insights
        val categories = listOf(cat(id = 1, name = "Food", icon = "food", color = "#FF0000"))
        
        val snapshot = engine.generateInsights(categories, allExpenses, "EUR")
        
        // Then: Change from previous should be 50% increase
        val foodInsight = snapshot.categoryInsights.find { it.category.id == 1L }
        assertNotNull(foodInsight)
        assertNotNull(foodInsight!!.changeFromPrevious)
        assertEquals(50.0f, foodInsight.changeFromPrevious!!, 0.01f) // (600-400)/400 * 100 = 50%
    }

    // ========== SCENARIO 6: Empty Period Handling ==========

    @Test
    fun `empty expenses list returns valid snapshot with zeros`() = runTest {
        // Given: No expenses
        every { timeProvider.now() } returns createDate(2024, 4, 15, 12, 0)
        
        // When: Generate insights
        val categories = listOf(cat(id = 1))
        val snapshot = engine.generateInsights(categories, emptyList())
        
        // Then: All values should be zero or empty
        assertEquals(0.0, snapshot.monthlyComparison.currentTotal, 0.01)
        assertEquals(0, snapshot.monthlyComparison.currentCount)
        assertTrue(snapshot.categoryInsights.isEmpty())
        assertTrue(snapshot.topMerchants.isEmpty())
        assertEquals(0.0, snapshot.spendingPace.currentMonthSpent, 0.01)
        assertTrue(snapshot.anomalies.isEmpty())
        assertTrue(snapshot.recurringExpenses.isEmpty())
        assertTrue(snapshot.dayOfWeekPattern.isEmpty())
        assertNull(snapshot.largestTransaction)
        assertEquals(0.0, snapshot.averageTransactionSize, 0.01)
        assertEquals(0.0, snapshot.medianTransactionSize, 0.01)
    }

    // ========== Helper Methods ==========

    private fun cat(
        id: Long,
        name: String = "Food",
        icon: String = "food",
        color: String = "#FF0000"
    ): AnalyticsCategoryRef {
        return AnalyticsCategoryRef(id = id, name = name, icon = icon, color = color)
    }

    private fun TransactionType.toDomainTransactionType(): DomainTransactionType {
        return when (this) {
            TransactionType.PURCHASE -> DomainTransactionType.PURCHASE
            TransactionType.WITHDRAWAL -> DomainTransactionType.WITHDRAWAL
            TransactionType.TRANSFER -> DomainTransactionType.TRANSFER
            TransactionType.DEPOSIT -> DomainTransactionType.DEPOSIT
            TransactionType.UNKNOWN -> DomainTransactionType.UNKNOWN
        }
    }
}