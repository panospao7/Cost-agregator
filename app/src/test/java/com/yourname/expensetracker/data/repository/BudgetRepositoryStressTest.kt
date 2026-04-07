package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.BudgetDao
import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.domain.budget.BudgetCalculator
import com.yourname.expensetracker.domain.groups.SharedExpenseBudgetOffsetEngine
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Ignore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@Ignore("Stress test: may hang in CI, run manually")
class BudgetRepositoryStressTest {

    private val budgetDao = mockk<BudgetDao>(relaxed = true)
    private val categoryDao = mockk<CategoryDao>(relaxed = true)
    private val expenseDao = mockk<ExpenseDao>(relaxed = true)
    private val budgetCalculator = mockk<BudgetCalculator>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private val offsetEngine = mockk<SharedExpenseBudgetOffsetEngine>(relaxed = true)

    private lateinit var repository: BudgetRepository

    @Before
    fun setup() {
        coEvery { budgetDao.insert(any()) } returns 1L
        coEvery { budgetDao.update(any()) } returns Unit
        coEvery { budgetDao.delete(any()) } returns Unit
        coEvery { budgetDao.getActiveBudgets() } returns emptyList()
        coEvery { budgetDao.getAllFlow() } returns MutableStateFlow(emptyList())
        coEvery { budgetDao.getActiveBudgetsFlow() } returns MutableStateFlow(emptyList())
        coEvery { categoryDao.getAllFlow() } returns MutableStateFlow(emptyList())
        coEvery { expenseDao.getExpensesBetweenFlow(any(), any()) } returns MutableStateFlow(emptyList())
        every { timeProvider.now() } returns System.currentTimeMillis()

        repository = BudgetRepository(
            budgetDao,
            categoryDao,
            expenseDao,
            budgetCalculator,
            timeProvider,
            offsetEngine
        )
    }

    // ============================================================================
    // SECTION 1: VALIDATION EDGE CASES
    // ============================================================================

    @Test
    fun `stress - addBudget with zero amount fails`() = runTest {
        val budget = Budget(
            id = 0,
            categoryId = null,
            amount = 0.0,
            period = BudgetPeriod.MONTHLY,
            startDate = System.currentTimeMillis(),
            isActive = true,
            rollover = false,
            notifyAtWarning = 0.8f,
            notifyAtCritical = 0.95f
        )

        val result = repository.addBudget(budget)
        assertTrue(result is com.yourname.expensetracker.domain.model.Result.Error)
    }

    @Test
    fun `stress - addBudget with negative amount fails`() = runTest {
        val budget = Budget(
            id = 0,
            categoryId = null,
            amount = -100.0,
            period = BudgetPeriod.MONTHLY,
            startDate = System.currentTimeMillis(),
            isActive = true,
            rollover = false,
            notifyAtWarning = 0.8f,
            notifyAtCritical = 0.95f
        )

        val result = repository.addBudget(budget)
        assertTrue(result is com.yourname.expensetracker.domain.model.Result.Error)
    }

    @Test
    fun `stress - addBudget with zero startDate fails`() = runTest {
        val budget = Budget(
            id = 0,
            categoryId = null,
            amount = 100.0,
            period = BudgetPeriod.MONTHLY,
            startDate = 0,
            isActive = true,
            rollover = false,
            notifyAtWarning = 0.8f,
            notifyAtCritical = 0.95f
        )

        val result = repository.addBudget(budget)
        assertTrue(result is com.yourname.expensetracker.domain.model.Result.Error)
    }

    @Test
    fun `stress - addBudget with negative startDate fails`() = runTest {
        val budget = Budget(
            id = 0,
            categoryId = null,
            amount = 100.0,
            period = BudgetPeriod.MONTHLY,
            startDate = -1000,
            isActive = true,
            rollover = false,
            notifyAtWarning = 0.8f,
            notifyAtCritical = 0.95f
        )

        val result = repository.addBudget(budget)
        assertTrue(result is com.yourname.expensetracker.domain.model.Result.Error)
    }

    @Test
    fun `stress - addBudget with valid data succeeds`() = runTest {
        val budget = Budget(
            id = 0,
            categoryId = 1,
            amount = 500.0,
            period = BudgetPeriod.MONTHLY,
            startDate = System.currentTimeMillis(),
            isActive = true,
            rollover = false,
            notifyAtWarning = 0.8f,
            notifyAtCritical = 0.95f
        )

        val result = repository.addBudget(budget)
        assertTrue(result is com.yourname.expensetracker.domain.model.Result.Success)
    }

    // ============================================================================
    // SECTION 2: UPDATE VALIDATION
    // ============================================================================

    @Test
    fun `stress - updateBudget with zero amount fails`() = runTest {
        val budget = Budget(
            id = 1,
            categoryId = null,
            amount = 0.0,
            period = BudgetPeriod.MONTHLY,
            startDate = System.currentTimeMillis(),
            isActive = true,
            rollover = false,
            notifyAtWarning = 0.8f,
            notifyAtCritical = 0.95f
        )

        val result = repository.updateBudget(budget)
        assertTrue(result is com.yourname.expensetracker.domain.model.Result.Error)
    }

    @Test
    fun `stress - updateBudget with negative amount fails`() = runTest {
        val budget = Budget(
            id = 1,
            categoryId = null,
            amount = -50.0,
            period = BudgetPeriod.MONTHLY,
            startDate = System.currentTimeMillis(),
            isActive = true,
            rollover = false,
            notifyAtWarning = 0.8f,
            notifyAtCritical = 0.95f
        )

        val result = repository.updateBudget(budget)
        assertTrue(result is com.yourname.expensetracker.domain.model.Result.Error)
    }

    // ============================================================================
    // SECTION 3: BULK OPERATIONS
    // ============================================================================

    @Test
    fun `stress - add many budgets`() = runTest {
        repeat(50) { i ->
            val budget = Budget(
                id = 0,
                categoryId = null,
                amount = 100.0 + i,
                period = BudgetPeriod.MONTHLY,
                startDate = System.currentTimeMillis(),
                isActive = true,
                rollover = false,
                notifyAtWarning = 0.8f,
                notifyAtCritical = 0.95f
            )
            repository.addBudget(budget)
        }
    }

    @Test
    fun `stress - toggle many budgets`() = runTest {
        repeat(50) { i ->
            repository.toggleBudget(i.toLong() + 1, i % 2 == 0)
        }
    }

    // ============================================================================
    // SECTION 4: EDGE CASES
    // ============================================================================

    @Test
    fun `stress - very large budget amount`() = runTest {
        val budget = Budget(
            id = 0,
            categoryId = null,
            amount = 1_000_000_000.0,
            period = BudgetPeriod.YEARLY,
            startDate = System.currentTimeMillis(),
            isActive = true,
            rollover = false,
            notifyAtWarning = 0.8f,
            notifyAtCritical = 0.95f
        )

        val result = repository.addBudget(budget)
        assertTrue(result is com.yourname.expensetracker.domain.model.Result.Success)
    }

    @Test
    fun `stress - very small budget amount`() = runTest {
        val budget = Budget(
            id = 0,
            categoryId = null,
            amount = 0.01,
            period = BudgetPeriod.MONTHLY,
            startDate = System.currentTimeMillis(),
            isActive = true,
            rollover = false,
            notifyAtWarning = 0.8f,
            notifyAtCritical = 0.95f
        )

        val result = repository.addBudget(budget)
        assertTrue(result is com.yourname.expensetracker.domain.model.Result.Success)
    }
}
