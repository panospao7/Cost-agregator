package com.yourname.expensetracker.domain.forecasting

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.domain.logic.RecurringExpenseEngine
import com.yourname.expensetracker.domain.logic.SynthesisEngine
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

class FinancialStressForecastEngineTest {

    private lateinit var synthesisEngine: SynthesisEngine
    private lateinit var monteCarloSimulator: MonteCarloSpendingSimulator
    private lateinit var recurringExpenseEngine: RecurringExpenseEngine
    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var budgetRepository: BudgetRepository
    private lateinit var timeProvider: TimeProvider

    private lateinit var engine: FinancialStressForecastEngine

    private val now = millis(2026, Calendar.APRIL, 20)
    private val dayMs = 24L * 60L * 60L * 1000L

    private var allExpenses: List<Expense> = emptyList()
    private var allDeposits: List<Expense> = emptyList()

    @Before
    fun setup() {
        synthesisEngine = mockk(relaxed = true)
        monteCarloSimulator = mockk(relaxed = true)
        recurringExpenseEngine = mockk()
        expenseRepository = mockk()
        budgetRepository = mockk()
        timeProvider = mockk()

        every { timeProvider.now() } returns now
        coEvery { recurringExpenseEngine.getPatterns() } returns emptyList()
        every { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())

        coEvery { expenseRepository.getExpensesBetween(any(), any()) } answers {
            val start = firstArg<Long>()
            val end = secondArg<Long>()
            allExpenses.filter { it.date in start..end }
        }

        coEvery { expenseRepository.getDepositsBetween(any(), any()) } answers {
            val start = firstArg<Long>()
            val end = secondArg<Long>()
            allDeposits.filter { it.date in start..end }
        }

        engine = FinancialStressForecastEngine(
            synthesisEngine = synthesisEngine,
            monteCarloSimulator = monteCarloSimulator,
            recurringExpenseEngine = recurringExpenseEngine,
            expenseRepository = expenseRepository,
            budgetRepository = budgetRepository,
            timeProvider = timeProvider
        )
    }

    @Test
    fun `computeStressForecast empirical bootstrap is deterministic for same history`() = runTest {
        val monthlySalary = expense(1L, 3000.0, TransactionType.DEPOSIT, now - 5 * dayMs)
        val p1 = expense(2L, 20.0, TransactionType.PURCHASE, now - 10 * dayMs)
        val p2 = expense(3L, 40.0, TransactionType.PURCHASE, now - 9 * dayMs)
        val p3 = expense(4L, 60.0, TransactionType.PURCHASE, now - 8 * dayMs)
        val p4 = expense(5L, 80.0, TransactionType.PURCHASE, now - 7 * dayMs)

        allExpenses = listOf(monthlySalary, p1, p2, p3, p4)
        allDeposits = listOf(monthlySalary)
        every { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(overallBudgetStatus(1500.0, spent = 0.0)))

        val first = engine.computeStressForecast()
        val second = engine.computeStressForecast()

        assertEquals(first.overallRiskLevel, second.overallRiskLevel)
        assertEquals(first.earliestCrunchDate, second.earliestCrunchDate)

        first.horizons.zip(second.horizons).forEach { (a, b) ->
            assertEquals(a.daysAhead, b.daysAhead)
            assertApproxEquals(a.projectedBalance, b.projectedBalance, 0.01)
            assertApproxEquals(a.minProjectedBalance, b.minProjectedBalance, 0.01)
            assertApproxEquals(a.probabilityOfCrunch, b.probabilityOfCrunch, 0.01)
            assertEquals(a.riskLevel, b.riskLevel)
        }
    }

    @Test
    fun `computeStressForecast no-data fallback keeps percentile ordering consistent`() = runTest {
        allExpenses = emptyList()
        allDeposits = emptyList()
        every { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(overallBudgetStatus(900.0, spent = 0.0)))

        val result = engine.computeStressForecast()

        assertEquals(3, result.horizons.size)
        result.horizons.forEach { horizon ->
            // projectedBalance uses p50, minProjectedBalance uses p90.
            assertTrue(horizon.minProjectedBalance <= horizon.projectedBalance)
            assertTrue(horizon.probabilityOfCrunch in 0.0..1.0)
        }
    }

    @Test
    fun `classifyRiskLevel maps probabilities to all five tiers`() {
        assertEquals(StressRiskLevel.LOW, classifyRiskLevelViaReflection(0.00))
        assertEquals(StressRiskLevel.MODERATE, classifyRiskLevelViaReflection(0.10))
        assertEquals(StressRiskLevel.ELEVATED, classifyRiskLevelViaReflection(0.25))
        assertEquals(StressRiskLevel.HIGH, classifyRiskLevelViaReflection(0.50))
        assertEquals(StressRiskLevel.CRITICAL, classifyRiskLevelViaReflection(0.75))
    }

    @Test
    fun `computeStressForecast empty history edge case returns valid horizons`() = runTest {
        allExpenses = emptyList()
        allDeposits = emptyList()
        every { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())

        val result = engine.computeStressForecast()

        assertEquals(StressRiskLevel.CRITICAL, result.overallRiskLevel)
        assertNotNull(result.earliestCrunchDate)
        assertEquals(listOf(30, 60, 90), result.horizons.map { it.daysAhead })
        assertTrue(result.horizons.all { it.probabilityOfCrunch in 0.0..1.0 })
    }

    @Test
    fun `computeStressForecast extreme positive balance drives low risk with no crunch date`() = runTest {
        val hugeDeposit = expense(100L, 1_000_000.0, TransactionType.DEPOSIT, now - 1 * dayMs)
        allExpenses = listOf(hugeDeposit)
        allDeposits = listOf(hugeDeposit)
        every { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(overallBudgetStatus(1000.0, spent = 0.0)))

        val result = engine.computeStressForecast()

        assertEquals(StressRiskLevel.LOW, result.overallRiskLevel)
        assertNull(result.earliestCrunchDate)
        assertTrue(result.horizons.all { it.riskLevel == StressRiskLevel.LOW })
    }

    @Test
    fun `computeStressForecast zero discretionary expenses still produces stable output`() = runTest {
        val salary = expense(200L, 2500.0, TransactionType.DEPOSIT, now - 2 * dayMs)
        allExpenses = listOf(salary)
        allDeposits = listOf(salary)
        every { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(overallBudgetStatus(1200.0, spent = 0.0)))

        val result = engine.computeStressForecast()

        assertEquals(3, result.horizons.size)
        result.horizons.forEach {
            assertTrue(it.projectedBalance >= it.minProjectedBalance)
            assertTrue(it.discretionaryBuffer >= 0.0)
        }
    }

    private fun classifyRiskLevelViaReflection(probability: Double): StressRiskLevel {
        val method = FinancialStressForecastEngine::class.java.getDeclaredMethod(
            "classifyRiskLevel",
            Double::class.javaPrimitiveType
        )
        method.isAccessible = true
        return method.invoke(engine, probability) as StressRiskLevel
    }

    private fun overallBudgetStatus(amount: Double, spent: Double): BudgetStatus {
        val budget = Budget(
            id = 1L,
            categoryId = null,
            amount = amount,
            period = BudgetPeriod.MONTHLY,
            startDate = now - 20 * dayMs
        )
        return BudgetStatus(
            budget = budget,
            category = null,
            spentAmount = spent,
            remainingAmount = (amount - spent).coerceAtLeast(0.0),
            percentUsed = if (amount > 0) (spent / amount).toFloat() else 0f,
            healthStatus = BudgetHealthStatus.ON_TRACK,
            periodStart = now - 20 * dayMs,
            periodEnd = now + 10 * dayMs
        )
    }

    private fun expense(id: Long, amount: Double, type: TransactionType, date: Long): Expense {
        return Expense(
            id = id,
            amount = amount,
            merchant = "M$id",
            transactionType = type,
            date = date,
            categoryId = 1L,
            isNotMine = false
        )
    }

    private fun millis(year: Int, month: Int, day: Int): Long {
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
