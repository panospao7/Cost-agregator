package com.yourname.expensetracker.domain.budget

import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.BudgetRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SharedBudgetManagerTest : AnalyticsEngineTestBase() {

    private lateinit var budgetRepository: BudgetRepository
    private lateinit var manager: SharedBudgetManager

    @Before
    override fun setUp() {
        super.setUp()
        budgetRepository = mockk(relaxed = true)
        manager = SharedBudgetManager(
            budgetRepository = budgetRepository,
            expenseDao = expenseDao,
            timeProvider = timeProvider
        )
    }

    @Test
    fun `get shared budget progress returns category scoped totals and per member average`() = runTest {
        val now = atDateTime(2026, 4, 15, 10, 0)
        every { timeProvider.now() } returns now

        val budget = Budget(
            id = 7L,
            categoryId = 2L,
            amount = 200.0,
            period = BudgetPeriod.MONTHLY,
            startDate = atDateTime(2026, 4, 1, 0, 0)
        )
        coEvery { budgetRepository.getById(7L) } returns budget
        coEvery { expenseDao.getExpensesBetween(startOfMonth(now), now) } returns listOf(
            expense(id = 1L, amount = 40.0, categoryId = 2L),
            expense(id = 2L, amount = 60.0, categoryId = 2L),
            expense(id = 3L, amount = 25.0, categoryId = 3L)
        )

        val progress = manager.getSharedBudgetProgress(7L, listOf("a", "b"))

        assertEquals(7L, progress.budgetId)
        assertEquals("Category 2 Budget", progress.budgetName)
        assertApproxEquals(200.0, progress.totalAmount, 0.01)
        assertApproxEquals(100.0, progress.totalSpent, 0.01)
        assertApproxEquals(100.0, progress.remaining, 0.01)
        assertApproxEquals(50.0, progress.percentUsed, 0.01)
        assertEquals(2, progress.memberCount)
        assertApproxEquals(50.0, progress.perMemberAverage, 0.01)
        assertFalse(progress.isOverBudget)
    }

    @Test
    fun `get shared budget progress with null category budget counts null category expenses and can exceed`() = runTest {
        val now = atDateTime(2026, 4, 20, 18, 0)
        every { timeProvider.now() } returns now

        val budget = Budget(
            id = 8L,
            categoryId = null,
            amount = 35.0,
            period = BudgetPeriod.MONTHLY,
            startDate = atDateTime(2026, 4, 1, 0, 0)
        )
        coEvery { budgetRepository.getById(8L) } returns budget
        coEvery { expenseDao.getExpensesBetween(startOfMonth(now), now) } returns listOf(
            expense(id = 11L, amount = 30.0, categoryId = 1L),
            expense(id = 12L, amount = 25.0, categoryId = 2L),
            expense(id = 13L, amount = 40.0, categoryId = null)
        )

        val progress = manager.getSharedBudgetProgress(8L, listOf("u1", "u2", "u3"))

        assertApproxEquals(40.0, progress.totalSpent, 0.01)
        assertApproxEquals(-5.0, progress.remaining, 0.01)
        assertApproxEquals(114.29, progress.percentUsed, 0.01)
        assertApproxEquals(13.33, progress.perMemberAverage, 0.01)
        assertTrue(progress.isOverBudget)
    }

    @Test
    fun `get shared budget progress throws when budget is missing`() = runTest {
        coEvery { budgetRepository.getById(404L) } returns null

        var thrown: Throwable? = null
        try {
            manager.getSharedBudgetProgress(404L, listOf("x"))
        } catch (t: Throwable) {
            thrown = t
        }

        assertTrue(thrown is IllegalArgumentException)
        assertEquals("Budget not found", thrown?.message)
    }

    @Test
    fun `get member contributions returns zero placeholders for all members`() = runTest {
        val contributions = manager.getMemberContributions(1L, listOf("alice", "bob"))

        assertEquals(2, contributions.size)
        assertEquals("alice", contributions[0].memberId)
        assertEquals("Member alice", contributions[0].memberName)
        assertApproxEquals(0.0, contributions[0].amountSpent, 0.0)
        assertApproxEquals(0.0, contributions[0].percentOfTotal, 0.0)
        assertApproxEquals(0.0, contributions[0].remainingAllowance, 0.0)

        assertEquals("bob", contributions[1].memberId)
        assertEquals("Member bob", contributions[1].memberName)
        assertApproxEquals(0.0, contributions[1].amountSpent, 0.0)
        assertApproxEquals(0.0, contributions[1].percentOfTotal, 0.0)
        assertApproxEquals(0.0, contributions[1].remainingAllowance, 0.0)
    }

    private fun expense(id: Long, amount: Double, categoryId: Long?): Expense {
        return Expense(
            id = id,
            amount = amount,
            merchant = "Merchant $id",
            transactionType = TransactionType.PURCHASE,
            date = atDateTime(2026, 4, 10, 9, 0),
            categoryId = categoryId
        )
    }

    private fun startOfMonth(timestamp: Long): Long {
        val calendar = java.util.Calendar.getInstance().apply {
            timeInMillis = timestamp
            set(java.util.Calendar.DAY_OF_MONTH, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }

    private fun atDateTime(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        val calendar = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.YEAR, year)
            set(java.util.Calendar.MONTH, month - 1)
            set(java.util.Calendar.DAY_OF_MONTH, day)
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }
}
