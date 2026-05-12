package com.yourname.expensetracker.data.repository

import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.data.database.dao.BudgetDao
import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.domain.budget.BudgetCalculator
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.groups.SharedExpenseBudgetOffsetEngine
import com.yourname.expensetracker.domain.util.TimeBoundaryTicker
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.BudgetForecastDao
import io.mockk.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * A.9 Batch 2 — Truncation regression tests for BudgetRepository.
 *
 * These tests prove that [BudgetRepository.getBudgetStatuses] uses aggregate SQL
 * queries ([ExpenseDao.getTotalForPeriod] / [ExpenseDao.getCategorySpentInPeriod])
 * instead of capped row-level reads (the old LIMIT 2000 contract).
 *
 * Each test sets aggregate totals that represent a dataset larger than the old
 * DAO default caps (500 / 2000 rows) and asserts that the budget status values
 * match the aggregate total — not a truncated partial sum.  Mock verification
 * confirms that no row-level expense reads were invoked.
 */
@Suppress("DEPRECATION_ERROR")
class BudgetRepositoryTruncationTest {

    private val budgetDao = mockk<BudgetDao>(relaxed = true)
    private val categoryDao = mockk<CategoryDao>(relaxed = true)
    private val expenseDao = mockk<ExpenseDao>(relaxed = true)
    private val budgetCalculator = mockk<BudgetCalculator>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private val offsetEngine = mockk<SharedExpenseBudgetOffsetEngine>(relaxed = true)
    private val currencyConverter = mockk<CurrencyConverter>(relaxed = true)
    private val currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true)
    private val writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true)
    private val database = mockk<AppDatabase>(relaxed = true)
    private val budgetForecastDao = mockk<BudgetForecastDao>(relaxed = true)

    private lateinit var repository: BudgetRepository

    @Suppress("DEPRECATION_ERROR")
    @Before
    fun setup() {
        every { currencySettingsRepository.homeCurrency() } returns flowOf("EUR")
        every { expenseDao.getTotalSpentFlow() } returns flowOf(0.0)
        coEvery { expenseDao.getTotalForPeriod(any(), any()) } returns 0.0
        coEvery { expenseDao.getCategorySpentInPeriod(any(), any(), any()) } returns 0.0

        repository = BudgetRepository(
            budgetDao, categoryDao, expenseDao, budgetCalculator,
            timeProvider, offsetEngine, TimeBoundaryTicker(timeProvider),
            currencyConverter, currencySettingsRepository,
            multiCurrencyRepository = mockk<MultiCurrencyRepository>(relaxed = true),
            writeBarrier = writeBarrier,
            database = database,
            budgetForecastDao = budgetForecastDao,
        )
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    // ── Scenario: >500-row equivalent total, whole-wallet budget ─────────────

    /**
     * If the old LIMIT 500 cap were active on a row-level query where each row
     * is ~€10, the total would cap at €5 000.  The aggregate query returns the
     * true total of €8 000 (representing ~800 rows).
     */
    @Test
    fun `truncation regression - 800-row equivalent total is not capped at 500`() = runTest {
        val now = makeUtcMs(2026, 3, 15)
        every { timeProvider.now() } returns now

        val start = makeUtcMs(2026, 3, 1)
        val end = makeUtcMs(2026, 4, 1)

        val budget = makeBudget(amount = 10_000.0, start = start)
        every { budgetCalculator.calculatePeriodRange(any(), any()) } returns (start to end)
        every { budgetDao.getActiveBudgetsFlow() } returns flowOf(listOf(budget))
        every { categoryDao.getAllFlow() } returns flowOf(emptyList<Category>())
        every { expenseDao.getTotalSpentFlow() } returns flowOf(8_000.0)
        coEvery { expenseDao.getTotalForPeriod(start, end) } returns 8_000.0

        val statuses = repository.getBudgetStatuses().first()

        assertThat(statuses).hasSize(1)
        assertThat(statuses[0].spentAmount).isEqualTo(8_000.0)
        assertThat(statuses[0].remainingAmount).isEqualTo(2_000.0)
        // 8000/10000 = 0.8 >= 0.75 (warning threshold) → WARNING
        assertThat(statuses[0].healthStatus).isEqualTo(BudgetHealthStatus.WARNING)

        // No row-level reads
        verifyNoRowLevelReads()
    }

    // ── Scenario: >2000-row equivalent total, whole-wallet budget ────────────

    /**
     * The critical regression: the old LIMIT 2000 default would truncate a total
     * of €25 000 (2 500 rows × €10) down to €20 000 (2 000 rows).  The budget
     * status would incorrectly show ON_TRACK instead of EXCEEDED.
     *
     * With aggregate queries, the correct €25 000 is returned and the budget
     * status is correctly EXCEEDED.
     */
    @Test
    fun `truncation regression - 2500-row equivalent total is not capped at 2000`() = runTest {
        val now = makeUtcMs(2026, 3, 15)
        every { timeProvider.now() } returns now

        val start = makeUtcMs(2026, 3, 1)
        val end = makeUtcMs(2026, 4, 1)

        val budget = makeBudget(amount = 20_000.0, start = start)
        every { budgetCalculator.calculatePeriodRange(any(), any()) } returns (start to end)
        every { budgetDao.getActiveBudgetsFlow() } returns flowOf(listOf(budget))
        every { categoryDao.getAllFlow() } returns flowOf(emptyList<Category>())
        every { expenseDao.getTotalSpentFlow() } returns flowOf(25_000.0)
        coEvery { expenseDao.getTotalForPeriod(start, end) } returns 25_000.0

        val statuses = repository.getBudgetStatuses().first()

        assertThat(statuses).hasSize(1)
        assertThat(statuses[0].spentAmount).isEqualTo(25_000.0)
        // Budget exceeded: 25000 / 20000 = 1.25
        assertThat(statuses[0].percentUsed).isAtLeast(1.0f)
        assertThat(statuses[0].healthStatus).isEqualTo(BudgetHealthStatus.EXCEEDED)

        verifyNoRowLevelReads()
    }

    // ── Scenario: >2000-row equivalent, category-scoped budget ───────────────

    /**
     * Category-scoped budgets use getCategorySpentInPeriod. The old approach
     * would fetch all rows and filter in-memory, subject to the row cap.
     */
    @Test
    fun `truncation regression - category budget with 3000-row equivalent uses aggregate`() = runTest {
        val now = makeUtcMs(2026, 3, 15)
        every { timeProvider.now() } returns now

        val start = makeUtcMs(2026, 3, 1)
        val end = makeUtcMs(2026, 4, 1)
        val catId = 7L

        val category = Category(catId, "Transport", "icon", "#0000FF", false)
        val budget = makeBudget(amount = 3_000.0, start = start, categoryId = catId)

        every { budgetCalculator.calculatePeriodRange(any(), any()) } returns (start to end)
        every { budgetDao.getActiveBudgetsFlow() } returns flowOf(listOf(budget))
        every { categoryDao.getAllFlow() } returns flowOf(listOf(category))
        every { expenseDao.getTotalSpentFlow() } returns flowOf(3_500.0)
        // 3 000 rows × €1.17 = €3 500 — old cap would produce €2 340
        coEvery { expenseDao.getCategorySpentInPeriod(catId, start, end) } returns 3_500.0

        val statuses = repository.getBudgetStatuses().first()

        assertThat(statuses).hasSize(1)
        assertThat(statuses[0].spentAmount).isEqualTo(3_500.0)
        assertThat(statuses[0].healthStatus).isEqualTo(BudgetHealthStatus.EXCEEDED)
        assertThat(statuses[0].category).isEqualTo(category)

        verifyNoRowLevelReads()
    }

    // ── Scenario: rollover with large history in previous periods ────────────

    /**
     * Rollover iterates through completed periods calling getAggregateSpent for
     * each.  If the old row-level approach were used, each period's spend would
     * be capped, causing incorrect surplus accumulation.
     *
     * This test uses a real BudgetCalculator and verifies:
     *   1. Aggregate queries are called for every historical window.
     *   2. The compounding surplus is computed from uncapped aggregate totals.
     *   3. No row-level expense reads are invoked.
     */
    @Test
    fun `truncation regression - rollover history uses aggregate per window not capped rows`() = runTest {
        val now = makeUtcMs(2026, 4, 10)
        every { timeProvider.now() } returns now

        val anchorMs = makeUtcMs(2026, 1, 1)
        val budget = makeBudget(
            amount = 5_000.0,
            start = anchorMs,
            rollover = true
        )

        val realCalc = BudgetCalculator(timeProvider)
        val repo = BudgetRepository(
            budgetDao, categoryDao, expenseDao, realCalc,
            timeProvider, offsetEngine, TimeBoundaryTicker(timeProvider),
            currencyConverter, currencySettingsRepository,
            multiCurrencyRepository = mockk<MultiCurrencyRepository>(relaxed = true),
            writeBarrier = writeBarrier,
            database = database,
            budgetForecastDao = budgetForecastDao,
        )

        every { budgetDao.getActiveBudgetsFlow() } returns flowOf(listOf(budget))
        every { categoryDao.getAllFlow() } returns flowOf(emptyList<Category>())
        every { expenseDao.getTotalSpentFlow() } returns flowOf(0.0)

        // Each completed period: spent €4 800 out of €5 000 → surplus €200
        // 3 completed periods (Jan, Feb, Mar), active = Apr
        // With compounding:
        //   Period 1: eff=5000, surplus=200, nextEff=5200
        //   Period 2: eff=5200, surplus=400, nextEff=5400
        //   Period 3: eff=5400, surplus=600, nextEff=5600
        // Active period spend = 0
        coEvery { expenseDao.getTotalForPeriod(any(), any()) } returns 4_800.0

        val statuses = repo.getBudgetStatuses().first()

        assertThat(statuses).hasSize(1)
        // Effective limit after 3 completed rollover periods = 5600
        assertThat(statuses[0].budget.amount).isAtLeast(5_600.0)

        // Aggregate queries called: 3 completed periods + 1 active = 4+ times
        coVerify(atLeast = 4) { expenseDao.getTotalForPeriod(any(), any()) }
        verifyNoRowLevelReads()
    }

    // ── Scenario: health-status thresholds with large totals ─────────────────

    @Test
    fun `truncation regression - WARNING threshold reached with large aggregate`() = runTest {
        val now = makeUtcMs(2026, 3, 15)
        every { timeProvider.now() } returns now

        val start = makeUtcMs(2026, 3, 1)
        val end = makeUtcMs(2026, 4, 1)

        // notifyAtWarning = 0.75, amount = 10000 → warning at 7500
        val budget = makeBudget(amount = 10_000.0, start = start)
        every { budgetCalculator.calculatePeriodRange(any(), any()) } returns (start to end)
        every { budgetDao.getActiveBudgetsFlow() } returns flowOf(listOf(budget))
        every { categoryDao.getAllFlow() } returns flowOf(emptyList<Category>())
        every { expenseDao.getTotalSpentFlow() } returns flowOf(8_000.0)
        coEvery { expenseDao.getTotalForPeriod(start, end) } returns 8_000.0

        val statuses = repository.getBudgetStatuses().first()

        // 8000/10000 = 0.8 ≥ 0.75 (warning) but < 0.9 (critical) → WARNING
        assertThat(statuses[0].healthStatus).isEqualTo(BudgetHealthStatus.WARNING)

        verifyNoRowLevelReads()
    }

    @Test
    fun `truncation regression - CRITICAL threshold reached with large aggregate`() = runTest {
        val now = makeUtcMs(2026, 3, 15)
        every { timeProvider.now() } returns now

        val start = makeUtcMs(2026, 3, 1)
        val end = makeUtcMs(2026, 4, 1)

        val budget = makeBudget(amount = 10_000.0, start = start)
        every { budgetCalculator.calculatePeriodRange(any(), any()) } returns (start to end)
        every { budgetDao.getActiveBudgetsFlow() } returns flowOf(listOf(budget))
        every { categoryDao.getAllFlow() } returns flowOf(emptyList<Category>())
        every { expenseDao.getTotalSpentFlow() } returns flowOf(9_500.0)
        coEvery { expenseDao.getTotalForPeriod(start, end) } returns 9_500.0

        val statuses = repository.getBudgetStatuses().first()

        // 9500/10000 = 0.95 ≥ 0.9 (critical) but < 1.0 → CRITICAL
        assertThat(statuses[0].healthStatus).isEqualTo(BudgetHealthStatus.CRITICAL)

        verifyNoRowLevelReads()
    }

    // ── A.10 Batch 3: Transaction-type isolation ───────────────────────────

    /**
     * A.10 Batch 3 — Proves that BudgetRepository spend surfaces delegate
     * exclusively to DAO aggregate methods that filter by
     * `transactionType = 'PURCHASE'`.
     *
     * The test configures a whole-wallet budget, sets the PURCHASE-only
     * aggregate (getTotalForPeriod) to €500, and asserts the budget status
     * reflects exactly that amount.  Because the repository calls only
     * getTotalForPeriod / getCategorySpentInPeriod — both of which have
     * `WHERE transactionType = 'PURCHASE'` baked into their SQL — deposits,
     * transfers, and withdrawals are structurally excluded; no alternative
     * code path exists that could mix them in.
     *
     * Mock verification confirms that no row-level expense reads (which
     * lack the PURCHASE filter) were invoked.
     */
    @Test
    fun `A10 Batch3 - budget spend uses only PURCHASE-filtered aggregate queries`() = runTest {
        val now = makeUtcMs(2026, 3, 15)
        every { timeProvider.now() } returns now

        val start = makeUtcMs(2026, 3, 1)
        val end = makeUtcMs(2026, 4, 1)

        val budget = makeBudget(amount = 1_000.0, start = start)
        every { budgetCalculator.calculatePeriodRange(any(), any()) } returns (start to end)
        every { budgetDao.getActiveBudgetsFlow() } returns flowOf(listOf(budget))
        every { categoryDao.getAllFlow() } returns flowOf(emptyList<Category>())
        every { expenseDao.getTotalSpentFlow() } returns flowOf(500.0)

        // Only the PURCHASE-only aggregate returns a value; any non-PURCHASE
        // pathway would need a different DAO call that is not stubbed.
        coEvery { expenseDao.getTotalForPeriod(start, end) } returns 500.0

        val statuses = repository.getBudgetStatuses().first()

        assertThat(statuses).hasSize(1)
        assertThat(statuses[0].spentAmount).isEqualTo(500.0)
        assertThat(statuses[0].remainingAmount).isEqualTo(500.0)
        // 500/1000 = 0.5 → ON_TRACK (below 0.75 warning)
        assertThat(statuses[0].healthStatus).isEqualTo(BudgetHealthStatus.ON_TRACK)

        // Confirm the aggregate SQL path was used exactly once
        coVerify(exactly = 1) { expenseDao.getTotalForPeriod(start, end) }
        verifyNoRowLevelReads()
    }

    /**
     * A.10 Batch 3 — Category-scoped budget spend uses only
     * getCategorySpentInPeriod (which has `transactionType = 'PURCHASE'`
     * in its SQL).  Deposits/transfers/withdrawals cannot leak into the
     * category spend because no alternative query path exists.
     */
    @Test
    fun `A10 Batch3 - category budget spend uses only PURCHASE-filtered category aggregate`() = runTest {
        val now = makeUtcMs(2026, 3, 15)
        every { timeProvider.now() } returns now

        val start = makeUtcMs(2026, 3, 1)
        val end = makeUtcMs(2026, 4, 1)
        val catId = 42L

        val category = Category(catId, "Groceries", "icon", "#00FF00", false)
        val budget = makeBudget(amount = 800.0, start = start, categoryId = catId)

        every { budgetCalculator.calculatePeriodRange(any(), any()) } returns (start to end)
        every { budgetDao.getActiveBudgetsFlow() } returns flowOf(listOf(budget))
        every { categoryDao.getAllFlow() } returns flowOf(listOf(category))
        every { expenseDao.getTotalSpentFlow() } returns flowOf(200.0)
        coEvery { expenseDao.getCategorySpentInPeriod(catId, start, end) } returns 200.0

        val statuses = repository.getBudgetStatuses().first()

        assertThat(statuses).hasSize(1)
        assertThat(statuses[0].spentAmount).isEqualTo(200.0)
        assertThat(statuses[0].remainingAmount).isEqualTo(600.0)
        assertThat(statuses[0].category).isEqualTo(category)

        // Confirm category aggregate was used, no whole-wallet fallback
        coVerify(exactly = 1) { expenseDao.getCategorySpentInPeriod(catId, start, end) }
        coVerify(exactly = 0) { expenseDao.getTotalForPeriod(any(), any()) }
        verifyNoRowLevelReads()
    }

    /**
     * A.10 Batch 3 — Rollover math delegates every per-period spend query to
     * getTotalForPeriod (PURCHASE-only).  Non-PURCHASE types structurally
     * cannot inflate or deflate the compounding surplus calculation.
     */
    @Test
    fun `A10 Batch3 - rollover surplus is not affected by non-PURCHASE types`() = runTest {
        val now = makeUtcMs(2026, 3, 15)
        every { timeProvider.now() } returns now

        val anchorMs = makeUtcMs(2026, 1, 1)
        val budget = makeBudget(amount = 1_000.0, start = anchorMs, rollover = true)

        val realCalc = BudgetCalculator(timeProvider)
        val repo = BudgetRepository(
            budgetDao, categoryDao, expenseDao, realCalc,
            timeProvider, offsetEngine, TimeBoundaryTicker(timeProvider),
            currencyConverter, currencySettingsRepository,
            multiCurrencyRepository = mockk<MultiCurrencyRepository>(relaxed = true),
            writeBarrier = writeBarrier,
            database = database,
            budgetForecastDao = budgetForecastDao,
        )

        every { budgetDao.getActiveBudgetsFlow() } returns flowOf(listOf(budget))
        every { categoryDao.getAllFlow() } returns flowOf(emptyList<Category>())
        every { expenseDao.getTotalSpentFlow() } returns flowOf(0.0)

        // Each completed period: spent €800 out of €1000
        // surplus = 200 each, compounding over 2 completed periods (Jan, Feb)
        //   Period 1: eff=1000, surplus=200, nextEff=1200
        //   Period 2: eff=1200, surplus=400, nextEff=1400
        // Active period (March): spend = 0
        coEvery { expenseDao.getTotalForPeriod(any(), any()) } returns 800.0

        val statuses = repo.getBudgetStatuses().first()

        assertThat(statuses).hasSize(1)
        // Effective limit: 1400 (1000 base + 200 + 200 compounding surplus)
        assertThat(statuses[0].budget.amount).isAtLeast(1_400.0)

        // Every period query used the PURCHASE-only aggregate
        coVerify(atLeast = 3) { expenseDao.getTotalForPeriod(any(), any()) }
        verifyNoRowLevelReads()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun makeBudget(
        amount: Double,
        start: Long,
        categoryId: Long? = null,
        rollover: Boolean = false
    ): Budget = Budget(
        id = 1L,
        categoryId = categoryId,
        amount = amount,
        period = BudgetPeriod.MONTHLY,
        startDate = start,
        isActive = true,
        rollover = rollover,
        notifyAtWarning = 0.75f,
        notifyAtCritical = 0.90f
    )

    private fun makeUtcMs(year: Int, month: Int, day: Int): Long {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(year, month - 1, day, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    /**
     * Asserts that no row-level expense queries were called.
     * This is the core proof that aggregate SQL is used instead of capped reads.
     */
    private fun verifyNoRowLevelReads() {
        coVerify(exactly = 0) { expenseDao.getExpensesBetween(any(), any(), any(), any()) }
        coVerify(exactly = 0) { expenseDao.getExpensesBetweenUncapped(any(), any()) }
    }
}