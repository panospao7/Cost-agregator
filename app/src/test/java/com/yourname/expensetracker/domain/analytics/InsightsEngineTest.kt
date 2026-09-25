package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.toAnalyticsCategoryRefs
import com.yourname.expensetracker.toExpenseSnapshots
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
import kotlin.coroutines.cancellation.CancellationException
import timber.log.Timber
import org.junit.After
import kotlin.test.assertFailsWith

class InsightsEngineTest {
    private lateinit var engine: InsightsEngine
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private val recurringEngine = mockk<com.yourname.expensetracker.domain.logic.RecurringExpenseEngine>(relaxed = true)
    private val monthlyCalculator = mockk<MonthlyComparisonCalculator>(relaxed = true)
    private val categoryCalculator = mockk<CategoryInsightEngine>(relaxed = true)
    private val merchantCalculator = mockk<MerchantInsightEngine>(relaxed = true)
    private val paceCalculator = mockk<SpendingPaceCalculator>(relaxed = true)
    private val anomalyCalculator = mockk<AnomalyDetector>(relaxed = true)
    private val weekdayCalculator = mockk<DayOfWeekAnalyzer>(relaxed = true)
    private val logs = mutableListOf<Pair<Throwable?, String>>()
    private val logTree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            logs += t to message
        }
    }

    @After fun tearDown() { Timber.uproot(logTree) }

    @Before
    fun setup() {
        val expenseRepository = mockk<ExpenseRepository>(relaxed = true)
        Timber.plant(logTree)
        coEvery { recurringEngine.getPatterns(any()) } returns emptyList()
        coEvery { recurringEngine.getPatternsFromSnapshots(any()) } returns emptyList()
        every { timeProvider.now() } returns System.currentTimeMillis()
        
        engine = InsightsEngine(
            expenseRepository = expenseRepository,
            recurringExpenseEngine = recurringEngine,
            timeProvider = timeProvider,
            spendingPaceCalculator = paceCalculator,
            anomalyDetector = anomalyCalculator,
            monthlyComparisonCalculator = monthlyCalculator,
            categoryInsightEngine = categoryCalculator,
            merchantInsightEngine = merchantCalculator,
            dayOfWeekAnalyzer = weekdayCalculator
        )
    }

    private val dayMs = 86_400_000L

    private suspend fun assertCancellationWithoutFallbackLog() {
        assertFailsWith<CancellationException> { engine.generateInsights(emptyList(), emptyList(), "EUR") }
        assertTrue(logs.none { it.first != null || it.second.contains("UNKNOWN_ERROR") || it.second.contains("private") })
    }

    @Test
    fun `monthly comparison cancellation propagates without fallback log`() = runTest {
        every { monthlyCalculator.calculate(any(), any(), any(), any()) } throws CancellationException("private receipt")
        assertCancellationWithoutFallbackLog()
    }

    @Test
    fun `category insights cancellation propagates without fallback log`() = runTest {
        every { categoryCalculator.calculate(any(), any(), any(), any(), any()) } throws CancellationException("private receipt")
        assertCancellationWithoutFallbackLog()
    }

    @Test
    fun `top merchants cancellation propagates without fallback log`() = runTest {
        every { merchantCalculator.calculate(any(), any()) } throws CancellationException("private receipt")
        assertCancellationWithoutFallbackLog()
    }

    @Test
    fun `spending pace cancellation propagates without fallback log`() = runTest {
        every { paceCalculator.calculate(any(), any(), any(), any(), any(), any()) } throws CancellationException("private receipt")
        assertCancellationWithoutFallbackLog()
    }

    @Test
    fun `anomalies cancellation propagates without fallback log`() = runTest {
        every { anomalyCalculator.detect(any(), any(), any(), any(), any()) } throws CancellationException("private receipt")
        assertCancellationWithoutFallbackLog()
    }

    @Test
    fun `weekday cancellation propagates without fallback log`() = runTest {
        every { weekdayCalculator.analyze(any(), any(), any(), any()) } throws CancellationException("private receipt")
        assertCancellationWithoutFallbackLog()
    }

    @Test
    fun `all seven insight boundaries degrade without leaking exception text`() = runTest {
        val hostile = IllegalStateException("merchant=private /storage/secret.db amount=918")
        every { monthlyCalculator.calculate(any(), any(), any(), any()) } throws hostile
        every { categoryCalculator.calculate(any(), any(), any(), any(), any()) } throws hostile
        every { merchantCalculator.calculate(any(), any()) } throws hostile
        every { paceCalculator.calculate(any(), any(), any(), any(), any(), any()) } throws hostile
        every { anomalyCalculator.detect(any(), any(), any(), any(), any()) } throws hostile
        coEvery { recurringEngine.getPatternsFromSnapshots(any()) } throws hostile
        every { weekdayCalculator.analyze(any(), any(), any(), any()) } throws hostile

        val snapshot = engine.generateInsights(emptyList(), emptyList(), "EUR")
        assertEquals(0.0, snapshot.monthlyComparison.currentTotal, 0.0)
        assertTrue(snapshot.categoryInsights.isEmpty())
        assertTrue(snapshot.topMerchants.isEmpty())
        assertEquals(PaceStatus.NO_BASELINE, snapshot.spendingPace.paceStatus)
        assertTrue(snapshot.anomalies.isEmpty())
        assertTrue(snapshot.recurringExpenses.isEmpty())
        assertTrue(snapshot.dayOfWeekPattern.isEmpty())
        for (stage in listOf("monthlyComparison", "categoryInsights", "topMerchants", "spendingPace", "anomalies", "recurringExpenses", "dayOfWeekPattern")) {
            assertTrue("missing $stage: $logs", logs.any { it.second == "InsightsEngine: UNKNOWN_ERROR stage=$stage class=IllegalStateException" })
        }
        assertTrue(logs.all { it.first == null && !it.second.contains("private") && !it.second.contains("918") })
    }

    private fun makeExpense(merchant: String, amount: Double, daysAgo: Int) = Expense(
        id = 0,
        amount = amount,
        currency = "EUR",
        merchant = merchant,
        transactionType = TransactionType.PURCHASE,
        date = System.currentTimeMillis() - daysAgo * dayMs
    )

    @Test
    fun `buildDailyTotals includes all requested days`() {
        val expenses = listOf(
            makeExpense("Shop", 10.00, 0),
            makeExpense("Shop", 20.00, 1)
        )
        val totals = engine.buildDailyTotals(expenses.toExpenseSnapshots(), 7)
        assertEquals(7, totals.size)
    }

    @Test
    fun `buildDailyTotals sums same-day purchases`() {
        val now = System.currentTimeMillis()
        val expenses = listOf(
            Expense(id = 1, amount = 10.0, currency = "EUR", merchant = "A", transactionType = TransactionType.PURCHASE, date = now),
            Expense(id = 2, amount = 20.0, currency = "EUR", merchant = "B", transactionType = TransactionType.PURCHASE, date = now)
        )
        val totals = engine.buildDailyTotals(expenses.toExpenseSnapshots(), 1)
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
        val totals = engine.buildDailyTotals(expenses.toExpenseSnapshots(), 1)
        val todayTotal = totals.values.last()
        assertEquals(10.0, todayTotal, 0.01)
    }

    @Test
    fun `generateInsights propagates CancellationException instead of returning degraded snapshot`() = runTest {
        // Arrange: make a repository call throw CancellationException
        val expenseRepository = mockk<ExpenseRepository>(relaxed = true)
        val cancellation = CancellationException("private receipt cancellation")
        coEvery { recurringEngine.getPatternsFromSnapshots(any()) } throws cancellation

        val cancelEngine = InsightsEngine(
            expenseRepository = expenseRepository,
            recurringExpenseEngine = recurringEngine,
            timeProvider = timeProvider,
            spendingPaceCalculator = mockk(relaxed = true),
            anomalyDetector = mockk(relaxed = true),
            monthlyComparisonCalculator = mockk(relaxed = true),
            categoryInsightEngine = mockk(relaxed = true),
            merchantInsightEngine = mockk(relaxed = true),
            dayOfWeekAnalyzer = mockk(relaxed = true)
        )

        // Act + Assert: CancellationException must propagate, not be swallowed
        try {
            cancelEngine.generateInsights(emptyList(), emptyList(), "EUR")
            fail("Expected CancellationException to propagate")
        } catch (e: CancellationException) {
            assertEquals(CancellationException::class.java, e::class.java)
        }
        assertTrue(logs.none { it.second.contains("private") || it.second.contains("UNKNOWN_ERROR") || it.first != null })
    }
}
