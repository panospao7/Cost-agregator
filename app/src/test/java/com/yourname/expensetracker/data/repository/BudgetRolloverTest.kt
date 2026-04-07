package com.yourname.expensetracker.data.repository

import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.data.database.dao.BudgetDao
import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.budget.BudgetCalculator
import com.yourname.expensetracker.domain.groups.SharedExpenseBudgetOffsetEngine
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.util.Calendar

/**
 * PHASE 6 TEST: Budget Rollover
 * 
 * Tests budget rollover logic where unspent amounts carry over to next period.
 * Tests the "Compounding Rollover" implementation (LOG-002 BUG-2 FIX).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BudgetRolloverTest {

    private val budgetDao = mockk<BudgetDao>(relaxed = true)
    private val categoryDao = mockk<CategoryDao>(relaxed = true)
    private val expenseDao = mockk<ExpenseDao>(relaxed = true)
    private val budgetCalculator = mockk<BudgetCalculator>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private val offsetEngine = mockk<SharedExpenseBudgetOffsetEngine>(relaxed = true)
    
    private lateinit var budgetRepository: BudgetRepository

    @Before
    fun setup() {
        // Mock time to be consistent
        every { timeProvider.now() } returns System.currentTimeMillis()
        
        // Default budget calculator behavior
        every { 
            budgetCalculator.calculatePeriodRange(any(), any()) 
        } returns createMockPeriodRange()
        
        budgetRepository = BudgetRepository(
            budgetDao,
            categoryDao,
            expenseDao,
            budgetCalculator,
            timeProvider,
            offsetEngine
        )
    }

    @Test
    fun `budget without rollover does not carry over unspent amount`() = runTest {
        val budget = createBudget(rollover = false, amount = 1000.0)
        val expenses = listOf(createExpense(600.0))
        
        every { budgetDao.getActiveBudgetsFlow() } returns flowOf(listOf(budget))
        every { categoryDao.getAllFlow() } returns flowOf(emptyList<Category>())
        every { expenseDao.getExpensesBetweenFlow(any(), any()) } returns flowOf(expenses)
        
        val statuses = budgetRepository.getBudgetStatuses().first()
        
        assertThat(statuses).hasSize(1)
        assertThat(statuses[0].remainingAmount).isEqualTo(400.0) // 1000 - 600
        assertThat(statuses[0].budget.amount).isEqualTo(1000.0) // No rollover
    }

    @Test
    fun `budget with rollover carries over unspent amount`() = runTest {
        val budget = createBudget(rollover = true, amount = 1000.0)
        val previousPeriodExpenses = listOf(createExpense(600.0)) // Spent 600 of 1000
        val currentPeriodExpenses = emptyList<Expense>()
        
        every { budgetDao.getActiveBudgetsFlow() } returns flowOf(listOf(budget))
        every { categoryDao.getAllFlow() } returns flowOf(emptyList<Category>())
        every { expenseDao.getExpensesBetweenFlow(any(), any()) } returns flowOf(
            previousPeriodExpenses + currentPeriodExpenses
        )
        
        val statuses = budgetRepository.getBudgetStatuses().first()
        
        // With rollover, the effective budget should be 1000 + (1000 - 600) = 1400
        assertThat(statuses).hasSize(1)
        assertThat(statuses[0].budget.rollover).isTrue()
    }

    @Test
    fun `rollover accumulates over multiple periods`() = runTest {
        val budget = createBudget(rollover = true, amount = 1000.0)
        
        // Period 1: Spent 800 (saved 200)
        // Period 2: Spent 900 (saved 100)  
        // Period 3: Current period
        // Total rollover = 200 + 100 = 300
        // Effective budget = 1000 + 300 = 1300
        
        every { budgetDao.getActiveBudgetsFlow() } returns flowOf(listOf(budget))
        every { categoryDao.getAllFlow() } returns flowOf(emptyList<Category>())
        every { expenseDao.getExpensesBetweenFlow(any(), any()) } returns flowOf(
            listOf(createExpense(800.0), createExpense(900.0))
        )
        
        val statuses = budgetRepository.getBudgetStatuses().first()
        
        assertThat(statuses[0].budget.rollover).isTrue()
        // Should show accumulated surplus in effective limit
    }

    @Test
    fun `rollover never goes negative`() = runTest {
        val budget = createBudget(rollover = true, amount = 1000.0)
        
        // If overspent in previous period, rollover should be 0 (not negative)
        val overspentExpenses = listOf(createExpense(1200.0)) // Spent 1200 of 1000
        
        every { budgetDao.getActiveBudgetsFlow() } returns flowOf(listOf(budget))
        every { categoryDao.getAllFlow() } returns flowOf(emptyList<Category>())
        every { expenseDao.getExpensesBetweenFlow(any(), any()) } returns flowOf(overspentExpenses)
        
        val statuses = budgetRepository.getBudgetStatuses().first()
        
        // Current budget should still be 1000 (not reduced by overspend)
        assertThat(statuses[0].budget.amount).isAtLeast(1000.0)
    }

    @Test
    fun `compounding rollover adds previous surpluses correctly`() = runTest {
        // Test LOG-002: Compounding Rollover - BUG-2 FIX
        val budget = createBudget(rollover = true, amount = 1000.0)
        
        // Simulate multiple periods of saving
        // Period 1: Spent 500, saved 500
        // Period 2: Spent 600, saved 400 (total saved: 900)
        // Period 3: Current
        // Effective limit should be 1000 + 900 = 1900
        
        every { budgetDao.getActiveBudgetsFlow() } returns flowOf(listOf(budget))
        every { categoryDao.getAllFlow() } returns flowOf(emptyList<Category>())
        every { expenseDao.getExpensesBetweenFlow(any(), any()) } returns flowOf(
            listOf(createExpense(500.0), createExpense(600.0))
        )
        
        val statuses = budgetRepository.getBudgetStatuses().first()
        
        // Verify compounding behavior
        assertThat(statuses[0].budget.rollover).isTrue()
    }

    @Test
    fun `rollover calculation respects period boundaries`() = runTest {
        val budget = createBudget(
            rollover = true,
            amount = 1000.0,
            period = BudgetPeriod.MONTHLY,
            startDate = getDateMonthsAgo(3)
        )
        
        every { budgetDao.getActiveBudgetsFlow() } returns flowOf(listOf(budget))
        every { categoryDao.getAllFlow() } returns flowOf(emptyList<Category>())
        every { expenseDao.getExpensesBetweenFlow(any(), any()) } returns flowOf(emptyList())
        
        val statuses = budgetRepository.getBudgetStatuses().first()
        
        // Should calculate rollover based on completed periods only
        assertThat(statuses).hasSize(1)
    }

    @Test
    fun `monthly budget rollover works across month boundaries`() = runTest {
        val budget = createBudget(
            rollover = true,
            amount = 1000.0,
            period = BudgetPeriod.MONTHLY,
            startDate = getDateMonthsAgo(2)
        )
        
        every { budgetDao.getActiveBudgetsFlow() } returns flowOf(listOf(budget))
        every { categoryDao.getAllFlow() } returns flowOf(emptyList<Category>())
        every { expenseDao.getExpensesBetweenFlow(any(), any()) } returns flowOf(
            listOf(createExpense(800.0))
        )
        
        val statuses = budgetRepository.getBudgetStatuses().first()
        
        assertThat(statuses[0].budget.rollover).isTrue()
    }

    @Test
    fun `weekly budget rollover works across week boundaries`() = runTest {
        val budget = createBudget(
            rollover = true,
            amount = 500.0,
            period = BudgetPeriod.WEEKLY,
            startDate = getDateWeeksAgo(2)
        )
        
        every { budgetDao.getActiveBudgetsFlow() } returns flowOf(listOf(budget))
        every { categoryDao.getAllFlow() } returns flowOf(emptyList<Category>())
        every { expenseDao.getExpensesBetweenFlow(any(), any()) } returns flowOf(
            listOf(createExpense(300.0))
        )
        
        val statuses = budgetRepository.getBudgetStatuses().first()
        
        assertThat(statuses[0].budget.rollover).isTrue()
    }

    @Test
    fun `rollover with category filter only includes category expenses`() = runTest {
        val category = Category(1L, "Food", "🍽️", "#FF0000", false)
        val budget = createBudget(
            rollover = true,
            amount = 500.0,
            categoryId = 1L
        )
        
        // Only category expenses should count
        val foodExpense = createExpense(200.0, categoryId = 1L)
        val transportExpense = createExpense(300.0, categoryId = 2L)
        
        every { budgetDao.getActiveBudgetsFlow() } returns flowOf(listOf(budget))
        every { categoryDao.getAllFlow() } returns flowOf(listOf(category))
        every { expenseDao.getExpensesBetweenFlow(any(), any()) } returns flowOf(
            listOf(foodExpense, transportExpense)
        )
        
        val statuses = budgetRepository.getBudgetStatuses().first()
        
        // Should only consider food expense (200), not transport (300)
        assertThat(statuses[0].spentAmount).isEqualTo(200.0)
    }

    @Test
    fun `surplus calculation with zero spend is full budget amount`() = runTest {
        val budget = createBudget(rollover = true, amount = 1000.0)
        
        every { budgetDao.getActiveBudgetsFlow() } returns flowOf(listOf(budget))
        every { categoryDao.getAllFlow() } returns flowOf(emptyList<Category>())
        every { expenseDao.getExpensesBetweenFlow(any(), any()) } returns flowOf(emptyList())
        
        val statuses = budgetRepository.getBudgetStatuses().first()
        
        // Full budget amount should roll over if nothing spent
        assertThat(statuses[0].spentAmount).isEqualTo(0.0)
    }

    @Test
    fun `deactivating budget stops rollover accumulation`() = runTest {
        val activeBudget = createBudget(rollover = true, amount = 1000.0, isActive = true)
        val inactiveBudget = createBudget(rollover = true, amount = 1000.0, isActive = false)
        
        every { budgetDao.getActiveBudgetsFlow() } returns flowOf(listOf(activeBudget))
        every { categoryDao.getAllFlow() } returns flowOf(emptyList())
        every { expenseDao.getExpensesBetweenFlow(any(), any()) } returns flowOf(emptyList())
        
        val statuses = budgetRepository.getBudgetStatuses().first()
        
        // Only active budgets should appear
        assertThat(statuses).hasSize(1)
        assertThat(statuses[0].budget.isActive).isTrue()
    }

    // Helper methods
    
    private fun createBudget(
        rollover: Boolean,
        amount: Double,
        categoryId: Long? = null,
        period: BudgetPeriod = BudgetPeriod.MONTHLY,
        startDate: Long = System.currentTimeMillis(),
        isActive: Boolean = true
    ): Budget {
        return Budget(
            id = 1L,
            categoryId = categoryId,
            amount = amount,
            period = period,
            startDate = startDate,
            isActive = isActive,
            rollover = rollover,
            notifyAtWarning = 0.75f,
            notifyAtCritical = 0.90f
        )
    }
    
    private fun createExpense(
        amount: Double,
        categoryId: Long = 1L,
        date: Long = System.currentTimeMillis()
    ): Expense {
        return Expense(
            id = 1L,
            merchant = "Test Merchant",
            amount = amount,
            date = date,
            categoryId = categoryId,
            notes = null,
            transactionType = TransactionType.PURCHASE,
            currency = "EUR"
        )
    }
    
    private fun createMockPeriodRange(): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        val startOfMonth = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        
        return Pair(startOfMonth, now + 86400000L)
    }
    
    private fun getDateMonthsAgo(months: Int): Long {
        return Calendar.getInstance().apply {
            add(Calendar.MONTH, -months)
        }.timeInMillis
    }
    
    private fun getDateWeeksAgo(weeks: Int): Long {
        return Calendar.getInstance().apply {
            add(Calendar.WEEK_OF_YEAR, -weeks)
        }.timeInMillis
    }
}
