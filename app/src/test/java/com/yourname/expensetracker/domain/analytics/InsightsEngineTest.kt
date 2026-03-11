package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.data.database.dao.MerchantStats
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.util.TimeProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import io.mockk.mockk
import io.mockk.every
import io.mockk.coEvery
import kotlinx.coroutines.test.runTest

class InsightsEngineTest {
    private lateinit var engine: InsightsEngine
    private lateinit var expenseRepository: ExpenseRepository
    private val timeProvider = mockk<TimeProvider>(relaxed = true)

    @Before
    fun setup() {
        expenseRepository = mockk<ExpenseRepository>(relaxed = true)
        val recurringEngine = mockk<com.yourname.expensetracker.domain.logic.RecurringExpenseEngine>(relaxed = true)
        val spendingPaceCalculator = mockk<SpendingPaceCalculator>(relaxed = true)
        val anomalyDetector = mockk<AnomalyDetector>(relaxed = true)
        val monthlyComparisonCalculator = mockk<MonthlyComparisonCalculator>(relaxed = true)
        val categoryInsightEngine = mockk<CategoryInsightEngine>(relaxed = true)
        val dayOfWeekAnalyzer = mockk<DayOfWeekAnalyzer>(relaxed = true)

        coEvery { recurringEngine.getPatterns(any()) } returns emptyList()
        every { timeProvider.now() } returns System.currentTimeMillis()

        engine = InsightsEngine(
            expenseRepository = expenseRepository,
            recurringExpenseEngine = recurringEngine,
            timeProvider = timeProvider,
            spendingPaceCalculator = spendingPaceCalculator,
            anomalyDetector = anomalyDetector,
            monthlyComparisonCalculator = monthlyComparisonCalculator,
            categoryInsightEngine = categoryInsightEngine,
            dayOfWeekAnalyzer = dayOfWeekAnalyzer
        )
    }

    private val dayMs = 86_400_000L

    private fun makeExpense(
        id: Long = 0,
        merchant: String,
        merchantKey: String? = null,
        amount: Double,
        daysAgo: Int = 5
    ) = Expense(
        id = id,
        amount = amount,
        currency = "EUR",
        merchant = merchant,
        merchantKey = merchantKey,
        transactionType = TransactionType.PURCHASE,
        date = System.currentTimeMillis() - daysAgo * dayMs
    )

    // ── buildDailyTotals (existing) ───────────────────────────────────────────

    @Test
    fun `buildDailyTotals includes all requested days`() {
        val expenses = listOf(
            makeExpense(merchant = "Shop", amount = 10.00, daysAgo = 0),
            makeExpense(merchant = "Shop", amount = 20.00, daysAgo = 1)
        )
        val totals = engine.buildDailyTotals(expenses, 7)
        assertEquals(7, totals.size)
    }

    @Test
    fun `buildDailyTotals sums same-day purchases`() {
        val now = System.currentTimeMillis()
        val expenses = listOf(
            Expense(id = 1, amount = 10.0, currency = "EUR", merchant = "A", transactionType = TransactionType.PURCHASE, date = now),
            Expense(id = 2, amount = 20.0, currency = "EUR", merchant = "B", transactionType = TransactionType.PURCHASE, date = now)
        )
        val totals = engine.buildDailyTotals(expenses, 1)
        val todayTotal = totals.values.last()
        assertEquals(30.0, todayTotal, 0.01)
    }

    @Test
    fun `buildDailyTotals ignores non-purchase types`() {
        val now = System.currentTimeMillis()
        val expenses = listOf(
            Expense(id = 1, amount = 10.0, currency = "EUR", merchant = "A", transactionType = TransactionType.PURCHASE, date = now),
            Expense(id = 2, amount = 100.0, currency = "EUR", merchant = "B", transactionType = TransactionType.DEPOSIT, date = now)
        )
        val totals = engine.buildDailyTotals(expenses, 1)
        val todayTotal = totals.values.last()
        assertEquals(10.0, todayTotal, 0.01)
    }

    // ── generateInsights → buildMerchantInsights (P1) ─────────────────────────

    /**
     * Stubs every expenseRepository call used inside generateInsights() except
     * getAllMerchantStats(), which each test configures individually.
     */
    private fun stubRepositoryForInsights() {
        coEvery { expenseRepository.getTotalForPeriod(any(), any()) } returns 0.0
        coEvery { expenseRepository.getCountForPeriod(any(), any()) } returns 0
        coEvery { expenseRepository.getCategoryTotalsForPeriod(any(), any()) } returns emptyList()
        coEvery { expenseRepository.getDayOfWeekPattern(any(), any(), any()) } returns emptyList()
        coEvery { expenseRepository.getLargestExpenseForPeriod(any(), any()) } returns null
        coEvery { expenseRepository.getMerchantStats() } returns emptyList()
        coEvery { expenseRepository.getTopMerchantsForPeriod(any(), any(), any()) } returns emptyList()
    }

    @Test
    fun `generateInsights topMerchants uses displayName not canonical key`() = runTest {
        val stats = listOf(
            MerchantStats(
                merchantName     = "sklavenitis",   // canonical key (GROUP BY merchantKey)
                displayName      = "Σκλαβενίτης",   // MIN(merchant) — human-readable
                totalAmount      = 100.0,
                transactionCount = 2,
                averageAmount    = 50.0,
                minAmount        = 40.0,
                maxAmount        = 60.0,
                firstDate        = 1_000_000L,
                lastDate         = 2_000_000L
            )
        )
        coEvery { expenseRepository.getAllMerchantStats() } returns stats
        stubRepositoryForInsights()

        val expenses = listOf(
            makeExpense(id = 1L, merchant = "Σκλαβενίτης", merchantKey = "sklavenitis", amount = 40.0),
            makeExpense(id = 2L, merchant = "Σκλαβενίτης", merchantKey = "sklavenitis", amount = 60.0)
        )

        val snapshot = engine.generateInsights(categories = emptyList(), allExpenses = expenses)

        val insight = snapshot.topMerchants.firstOrNull()
        assertNotNull("Expected at least one merchant insight", insight)
        // UI must show displayName, not the canonical key
        assertEquals("Σκλαβενίτης", insight!!.merchant)
    }

    @Test
    fun `generateInsights collapses two expenses with different merchants but same merchantKey into one MerchantInsight`() = runTest {
        val sharedKey = "sklavenitis"
        val stats = listOf(
            MerchantStats(
                merchantName     = sharedKey,
                displayName      = "Σκλαβενίτης",
                totalAmount      = 90.0,
                transactionCount = 2,
                averageAmount    = 45.0,
                minAmount        = 30.0,
                maxAmount        = 60.0,
                firstDate        = 1_000_000L,
                lastDate         = 2_000_000L
            )
        )
        coEvery { expenseRepository.getAllMerchantStats() } returns stats
        stubRepositoryForInsights()

        val expenses = listOf(
            makeExpense(id = 1L, merchant = "Σκλαβενίτης", merchantKey = sharedKey, amount = 30.0),
            makeExpense(id = 2L, merchant = "ΣΚΛΑΒΕΝΙΤΗΣ",  merchantKey = sharedKey, amount = 60.0)
        )

        val snapshot = engine.generateInsights(categories = emptyList(), allExpenses = expenses)

        // DAO groups by merchantKey → one stats row → exactly one MerchantInsight
        assertEquals(1, snapshot.topMerchants.size)
        assertEquals(90.0, snapshot.topMerchants.first().totalSpent, 0.01)
    }
}
