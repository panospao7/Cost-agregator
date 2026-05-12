package com.yourname.expensetracker.domain.budget

import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.dao.CurrencyTotal
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@Suppress("DEPRECATION_ERROR")
class SharedBudgetManagerTest : AnalyticsEngineTestBase() {

    private lateinit var budgetRepository: BudgetRepository
    private lateinit var budgetCalculator: BudgetCalculator
    private lateinit var manager: SharedBudgetManager

    @Before
    override fun setUp() {
        super.setUp()
        budgetRepository = mockk(relaxed = true)
        budgetCalculator = BudgetCalculator(timeProvider)
        manager = SharedBudgetManager(
            budgetRepository = budgetRepository,
            expenseDao = expenseDao,
            budgetCalculator = budgetCalculator,
            timeProvider = timeProvider,
            ioDispatcher = Dispatchers.Unconfined
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
        coEvery {
            expenseDao.getCategorySpentInPeriod(2L, startOfMonth(now), now)
        } returns 100.0
        coEvery { expenseDao.getTotalSpentBetweenByCurrency(any(), any()) } returns listOf(CurrencyTotal("EUR", 100.0, 1))

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
    fun `get shared budget progress with null category budget counts whole wallet spend and can exceed`() = runTest {
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
        coEvery {
            expenseDao.getTotalForPeriod(startOfMonth(now), now)
        } returns 40.0
        coEvery { expenseDao.getTotalSpentBetweenByCurrency(startOfMonth(now), now) } returns listOf(CurrencyTotal("EUR", 40.0, 1))

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
    fun `shared budget progress uses effectiveAmount for fixed share expense`() = runTest {
        val now = atDateTime(2026, 4, 15, 10, 0)
        every { timeProvider.now() } returns now

        val budget = Budget(
            id = 10L,
            categoryId = 2L,
            amount = 500.0,
            period = BudgetPeriod.MONTHLY,
            startDate = atDateTime(2026, 4, 1, 0, 0)
        )
        coEvery { budgetRepository.getById(10L) } returns budget
        coEvery {
            expenseDao.getCategorySpentInPeriod(2L, startOfMonth(now), now)
        } returns 100.0
        coEvery { expenseDao.getTotalSpentBetweenByCurrency(any(), any()) } returns listOf(CurrencyTotal("EUR", 100.0, 1))

        val progress = manager.getSharedBudgetProgress(10L, listOf("a", "b"))

        assertApproxEquals(100.0, progress.totalSpent, 0.01)
        assertApproxEquals(400.0, progress.remaining, 0.01)
        assertApproxEquals(20.0, progress.percentUsed, 0.01)
        assertApproxEquals(50.0, progress.perMemberAverage, 0.01)
        assertFalse(progress.isOverBudget)
    }

    @Test
    fun `shared budget progress uses effectiveAmount for percentage share expense`() = runTest {
        val now = atDateTime(2026, 4, 15, 10, 0)
        every { timeProvider.now() } returns now

        val budget = Budget(
            id = 11L,
            categoryId = 2L,
            amount = 200.0,
            period = BudgetPeriod.MONTHLY,
            startDate = atDateTime(2026, 4, 1, 0, 0)
        )
        coEvery { budgetRepository.getById(11L) } returns budget
        coEvery {
            expenseDao.getCategorySpentInPeriod(2L, startOfMonth(now), now)
        } returns 100.0
        coEvery { expenseDao.getTotalSpentBetweenByCurrency(any(), any()) } returns listOf(CurrencyTotal("EUR", 100.0, 1))

        val progress = manager.getSharedBudgetProgress(11L, listOf("x"))

        assertApproxEquals(100.0, progress.totalSpent, 0.01)
        assertApproxEquals(100.0, progress.remaining, 0.01)
        assertApproxEquals(50.0, progress.percentUsed, 0.01)
        assertFalse(progress.isOverBudget)
    }

    @Test
    fun `shared budget progress treats isNotMine expense as zero`() = runTest {
        val now = atDateTime(2026, 4, 15, 10, 0)
        every { timeProvider.now() } returns now

        val budget = Budget(
            id = 12L,
            categoryId = 2L,
            amount = 100.0,
            period = BudgetPeriod.MONTHLY,
            startDate = atDateTime(2026, 4, 1, 0, 0)
        )
        coEvery { budgetRepository.getById(12L) } returns budget
        coEvery {
            expenseDao.getCategorySpentInPeriod(2L, startOfMonth(now), now)
        } returns 30.0
        coEvery { expenseDao.getTotalSpentBetweenByCurrency(any(), any()) } returns listOf(CurrencyTotal("EUR", 30.0, 1))

        val progress = manager.getSharedBudgetProgress(12L, listOf("u1", "u2"))

        assertApproxEquals(30.0, progress.totalSpent, 0.01)
        assertApproxEquals(70.0, progress.remaining, 0.01)
        assertApproxEquals(30.0, progress.percentUsed, 0.01)
        assertApproxEquals(15.0, progress.perMemberAverage, 0.01)
        assertFalse(progress.isOverBudget)
    }

    @Test
    fun `shared budget progress mixed shared and isNotMine triggers overbudget correctly`() = runTest {
        val now = atDateTime(2026, 4, 15, 10, 0)
        every { timeProvider.now() } returns now

        val budget = Budget(
            id = 13L,
            categoryId = 2L,
            amount = 50.0,
            period = BudgetPeriod.MONTHLY,
            startDate = atDateTime(2026, 4, 1, 0, 0)
        )
        coEvery { budgetRepository.getById(13L) } returns budget
        coEvery {
            expenseDao.getCategorySpentInPeriod(2L, startOfMonth(now), now)
        } returns 60.0
        coEvery { expenseDao.getTotalSpentBetweenByCurrency(any(), any()) } returns listOf(CurrencyTotal("EUR", 60.0, 1))

        val progress = manager.getSharedBudgetProgress(13L, listOf("a"))

        assertApproxEquals(60.0, progress.totalSpent, 0.01)
        assertTrue(progress.isOverBudget) // 60 > 50
        assertApproxEquals(-10.0, progress.remaining, 0.01)
    }

    @Test
    fun `shared budget progress is not truncated when expense count exceeds old LIMIT 2000`() = runTest {
        val now = atDateTime(2026, 4, 15, 10, 0)
        every { timeProvider.now() } returns now

        val budget = Budget(
            id = 14L,
            categoryId = 5L,
            amount = 50000.0,
            period = BudgetPeriod.MONTHLY,
            startDate = atDateTime(2026, 4, 1, 0, 0)
        )
        coEvery { budgetRepository.getById(14L) } returns budget

        coEvery {
            expenseDao.getCategorySpentInPeriod(5L, startOfMonth(now), now)
        } returns 25000.0
        coEvery { expenseDao.getTotalSpentBetweenByCurrency(any(), any()) } returns listOf(CurrencyTotal("EUR", 25000.0, 1))

        val progress = manager.getSharedBudgetProgress(14L, listOf("a"))

        assertApproxEquals(25000.0, progress.totalSpent, 0.01)
        assertApproxEquals(25000.0, progress.remaining, 0.01)
        assertApproxEquals(50.0, progress.percentUsed, 0.01)
        assertFalse(progress.isOverBudget)
    }

    @Test
    fun `shared budget progress calls aggregate helper with correct category and date range`() = runTest {
        val now = atDateTime(2026, 4, 15, 10, 0)
        every { timeProvider.now() } returns now
        val som = startOfMonth(now)

        val budget = Budget(
            id = 20L,
            categoryId = 7L,
            amount = 300.0,
            period = BudgetPeriod.MONTHLY,
            startDate = atDateTime(2026, 4, 1, 0, 0)
        )
        coEvery { budgetRepository.getById(20L) } returns budget
        coEvery {
            expenseDao.getCategorySpentInPeriod(7L, som, now)
        } returns 150.0
        coEvery { expenseDao.getTotalSpentBetweenByCurrency(any(), any()) } returns listOf(CurrencyTotal("EUR", 150.0, 1))

        manager.getSharedBudgetProgress(20L, listOf("a"))

        coVerify(exactly = 1) {
            expenseDao.getCategorySpentInPeriod(7L, som, now)
        }
    }

    @Test
    fun `shared budget progress calls whole wallet aggregate helper for overall budget`() = runTest {
        val now = atDateTime(2026, 4, 20, 18, 0)
        every { timeProvider.now() } returns now
        val som = startOfMonth(now)

        val budget = Budget(
            id = 21L,
            categoryId = null,
            amount = 500.0,
            period = BudgetPeriod.MONTHLY,
            startDate = atDateTime(2026, 4, 1, 0, 0)
        )
        coEvery { budgetRepository.getById(21L) } returns budget
        coEvery {
            expenseDao.getTotalForPeriod(som, now)
        } returns 200.0
        coEvery { expenseDao.getTotalSpentBetweenByCurrency(som, now) } returns listOf(CurrencyTotal("EUR", 200.0, 1))

        val progress = manager.getSharedBudgetProgress(21L, listOf("a", "b"))

        coVerify(exactly = 1) {
            expenseDao.getTotalForPeriod(som, now)
        }
        coVerify(exactly = 0) {
            expenseDao.getCategorySpentInPeriod(any(), any(), any())
        }
        assertApproxEquals(200.0, progress.totalSpent, 0.01)
        assertEquals("Overall Budget", progress.budgetName)
    }

    @Test
    fun `shared budget progress preserves purchase-only budget spend semantics`() = runTest {
        val now = atDateTime(2026, 4, 15, 10, 0)
        every { timeProvider.now() } returns now

        val budget = Budget(
            id = 22L,
            categoryId = 3L,
            amount = 1000.0,
            period = BudgetPeriod.MONTHLY,
            startDate = atDateTime(2026, 4, 1, 0, 0)
        )
        coEvery { budgetRepository.getById(22L) } returns budget
        coEvery {
            expenseDao.getCategorySpentInPeriod(3L, startOfMonth(now), now)
        } returns 750.0
        coEvery { expenseDao.getTotalSpentBetweenByCurrency(any(), any()) } returns listOf(CurrencyTotal("EUR", 750.0, 1))

        val progress = manager.getSharedBudgetProgress(22L, listOf("x"))

        assertApproxEquals(750.0, progress.totalSpent, 0.01)
        coVerify(exactly = 0) {
            expenseDao.getExpensesBetweenUncapped(any(), any())
        }
    }

    @Test
    fun `shared budget progress uses active rolling weekly window instead of month to date`() = runTest {
        val now = atDateTime(2026, 4, 17, 9, 0)
        every { timeProvider.now() } returns now
        val anchor = atDateTime(2026, 4, 8, 0, 0)
        val expectedStart = atDateTime(2026, 4, 15, 0, 0)
        val expectedEnd = now

        val budget = Budget(
            id = 23L,
            categoryId = 4L,
            amount = 300.0,
            period = BudgetPeriod.WEEKLY,
            startDate = anchor,
            periodMode = "ROLLING"
        )
        coEvery { budgetRepository.getById(23L) } returns budget
        coEvery {
            expenseDao.getCategorySpentInPeriod(4L, expectedStart, expectedEnd)
        } returns 90.0
        coEvery { expenseDao.getTotalSpentBetweenByCurrency(any(), any()) } returns listOf(CurrencyTotal("EUR", 90.0, 1))

        val progress = manager.getSharedBudgetProgress(23L, listOf("alice", "bob", "carol"))

        assertApproxEquals(90.0, progress.totalSpent, 0.01)
        assertApproxEquals(30.0, progress.perMemberAverage, 0.01)
        coVerify(exactly = 1) {
            expenseDao.getCategorySpentInPeriod(4L, expectedStart, expectedEnd)
        }
        coVerify(exactly = 0) {
            expenseDao.getCategorySpentInPeriod(4L, startOfMonth(now), now)
        }
    }

    @Test
    fun `shared budget progress uses calendar monthly window for calendar budgets`() = runTest {
        val now = atDateTime(2026, 4, 15, 10, 0)
        every { timeProvider.now() } returns now
        val monthStart = TimePeriodUtils.getMonthRange(now).first

        val budget = Budget(
            id = 24L,
            categoryId = 6L,
            amount = 400.0,
            period = BudgetPeriod.MONTHLY,
            startDate = atDateTime(2026, 1, 22, 0, 0),
            periodMode = "CALENDAR"
        )
        coEvery { budgetRepository.getById(24L) } returns budget
        coEvery {
            expenseDao.getCategorySpentInPeriod(6L, monthStart, now)
        } returns 120.0
        coEvery { expenseDao.getTotalSpentBetweenByCurrency(any(), any()) } returns listOf(CurrencyTotal("EUR", 120.0, 1))

        val progress = manager.getSharedBudgetProgress(24L, listOf("solo"))

        assertApproxEquals(120.0, progress.totalSpent, 0.01)
        assertApproxEquals(30.0, progress.percentUsed, 0.01)
        coVerify(exactly = 1) {
            expenseDao.getCategorySpentInPeriod(6L, monthStart, now)
        }
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
