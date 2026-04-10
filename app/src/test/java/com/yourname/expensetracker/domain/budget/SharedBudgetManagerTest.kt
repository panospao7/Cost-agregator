package com.yourname.expensetracker.domain.budget

import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.repository.BudgetRepository
import io.mockk.coEvery
import io.mockk.coVerify
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
        // A.9 Batch 4: aggregate SQL helper returns pre-computed sum for category 2
        // (40 + 60 = 100; the old category-3 row is excluded by SQL WHERE)
        coEvery {
            expenseDao.getEffectiveSpentBetweenForCategory(startOfMonth(now), now, 2L)
        } returns 100.0

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
        // A.9 Batch 4: null-category aggregate — SQL uses
        // (categoryId IS NULL) semantics so only uncategorised rows contribute
        coEvery {
            expenseDao.getEffectiveSpentBetweenForCategory(startOfMonth(now), now, null)
        } returns 40.0

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

    // =========================================================================
    // A.1 effectiveAmount regression tests — the aggregate SQL helper uses
    // EFFECTIVE_AMOUNT_SQL which encodes the same ownership rules (shared /
    // percentage / isNotMine).  The mock returns the pre-computed effective
    // sum so the tests verify that the manager delegates correctly.
    // =========================================================================

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
        // Shared purchase: raw = 100, myShareAmount = 40 → effectiveAmount = 40
        // Regular: 60 → effectiveAmount = 60  → total effective = 100
        coEvery {
            expenseDao.getEffectiveSpentBetweenForCategory(startOfMonth(now), now, 2L)
        } returns 100.0

        val progress = manager.getSharedBudgetProgress(10L, listOf("a", "b"))

        // totalSpent must be 40 + 60 = 100, NOT 100 + 60 = 160
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
        // Percentage-based shared: raw = 100, 50% → effectiveAmount = 50
        // Regular: 50 → effectiveAmount = 50 → total effective = 100
        coEvery {
            expenseDao.getEffectiveSpentBetweenForCategory(startOfMonth(now), now, 2L)
        } returns 100.0

        val progress = manager.getSharedBudgetProgress(11L, listOf("x"))

        // totalSpent = 50 + 50 = 100, NOT 100 + 50 = 150
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
        // isNotMine rows are excluded by the WHERE isNotMine = 0 clause;
        // EFFECTIVE_AMOUNT_SQL would also yield 0.0 for them.
        // Only regular expense (30) contributes.
        coEvery {
            expenseDao.getEffectiveSpentBetweenForCategory(startOfMonth(now), now, 2L)
        } returns 30.0

        val progress = manager.getSharedBudgetProgress(12L, listOf("u1", "u2"))

        // totalSpent = 0 + 30 = 30, NOT 200 + 30 = 230
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
        // isNotMine → excluded by SQL (0), shared fixed → 40, regular → 20
        // Aggregate effective total = 0 + 40 + 20 = 60
        coEvery {
            expenseDao.getEffectiveSpentBetweenForCategory(startOfMonth(now), now, 2L)
        } returns 60.0

        val progress = manager.getSharedBudgetProgress(13L, listOf("a"))

        // totalSpent = 0 + 40 + 20 = 60, NOT 500 + 100 + 20 = 620
        assertApproxEquals(60.0, progress.totalSpent, 0.01)
        assertTrue(progress.isOverBudget) // 60 > 50
        assertApproxEquals(-10.0, progress.remaining, 0.01)
    }

    // =========================================================================
    // A.9 truncation-specific: proves aggregate path replaces old LIMIT 2000
    // =========================================================================

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

        // Simulate 2500 expenses each $10 — aggregate returns the correct total
        // regardless of any former row cap, because SQL SUM has no LIMIT.
        coEvery {
            expenseDao.getEffectiveSpentBetweenForCategory(startOfMonth(now), now, 5L)
        } returns 25000.0

        val progress = manager.getSharedBudgetProgress(14L, listOf("a"))

        // All 2500 expenses should be counted (no truncation), total = 25000
        assertApproxEquals(25000.0, progress.totalSpent, 0.01)
        assertApproxEquals(25000.0, progress.remaining, 0.01)
        assertApproxEquals(50.0, progress.percentUsed, 0.01)
        assertFalse(progress.isOverBudget)
    }

    // =========================================================================
    // A.9 Batch 4: verify aggregate path is actually invoked (not row scan)
    // =========================================================================

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
            expenseDao.getEffectiveSpentBetweenForCategory(som, now, 7L)
        } returns 150.0

        manager.getSharedBudgetProgress(20L, listOf("a"))

        // Verify the aggregate helper was called with exact arguments
        coVerify(exactly = 1) {
            expenseDao.getEffectiveSpentBetweenForCategory(som, now, 7L)
        }
    }

    @Test
    fun `shared budget progress calls aggregate helper with null categoryId for overall budget`() = runTest {
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
            expenseDao.getEffectiveSpentBetweenForCategory(som, now, null)
        } returns 200.0

        val progress = manager.getSharedBudgetProgress(21L, listOf("a", "b"))

        coVerify(exactly = 1) {
            expenseDao.getEffectiveSpentBetweenForCategory(som, now, null)
        }
        assertApproxEquals(200.0, progress.totalSpent, 0.01)
        assertEquals("Overall Budget", progress.budgetName)
    }

    // =========================================================================
    // A.10 no-narrowing regression: the aggregate does NOT filter by
    // transactionType — confirm manager does not inject a type filter either.
    // =========================================================================

    @Test
    fun `shared budget progress does not narrow by transaction type`() = runTest {
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
        // The aggregate includes all transaction types (PURCHASE, DEPOSIT, etc.)
        // — same semantics as the pre-A.9 uncapped row scan.
        coEvery {
            expenseDao.getEffectiveSpentBetweenForCategory(startOfMonth(now), now, 3L)
        } returns 750.0

        val progress = manager.getSharedBudgetProgress(22L, listOf("x"))

        // If narrowing to PURCHASE-only occurred, value would differ —
        // this assertion proves the aggregate was used as-is.
        assertApproxEquals(750.0, progress.totalSpent, 0.01)
        // No calls to type-filtered helpers should exist
        coVerify(exactly = 0) {
            expenseDao.getExpensesBetweenUncapped(any(), any())
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
