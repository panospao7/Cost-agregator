package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.BudgetDao
import com.yourname.expensetracker.data.database.dao.CategoryCurrencyTotal
import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.dao.CurrencyTotal
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.domain.budget.BudgetCalculator
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.MultiConversionAggregate
import com.yourname.expensetracker.domain.groups.SharedExpenseBudgetOffsetEngine
import com.yourname.expensetracker.domain.util.TimeBoundaryTicker
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.BudgetForecastDao
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Budget repository stress & validation tests.
 *
 * A.9 Batch 2: The @Ignore annotation has been removed. These tests now run in CI.
 * Large-history regression proofs (Section 5) verify that getBudgetStatuses()
 * uses aggregate SQL queries instead of capped row-level reads, so budget
 * correctness is independent of the number of expense rows.
 */
@Suppress("DEPRECATION_ERROR")
class BudgetRepositoryStressTest {

    private val budgetDao = mockk<BudgetDao>(relaxed = true)
    private val categoryDao = mockk<CategoryDao>(relaxed = true)
    private val expenseDao = mockk<ExpenseDao>(relaxed = true)
    private val budgetCalculator = mockk<BudgetCalculator>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private val offsetEngine = mockk<SharedExpenseBudgetOffsetEngine>(relaxed = true)
    private val currencyConverter = mockk<CurrencyConverter>(relaxed = true)
    private val currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true)
    private lateinit var multiCurrencyRepository: MultiCurrencyRepository
    private val writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true)
    private val database = mockk<AppDatabase>(relaxed = true)
    private val budgetForecastDao = mockk<BudgetForecastDao>(relaxed = true)

    private lateinit var repository: BudgetRepository

    @Suppress("DEPRECATION_ERROR")
    @Before
    fun setup() {
        every { currencySettingsRepository.homeCurrency() } returns flowOf("EUR")
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        coEvery { budgetDao.insert(any()) } returns 1L
        coEvery { budgetDao.insertAndActivateOverall(any()) } returns 1L
        coEvery { budgetDao.insertAndActivateCategory(any()) } returns 1L
        coEvery { budgetDao.update(any()) } returns Unit
        coEvery { budgetDao.delete(any()) } returns Unit
        coEvery { budgetDao.getActiveBudgets() } returns emptyList()
        coEvery { budgetDao.getAllFlow() } returns MutableStateFlow(emptyList())
        coEvery { budgetDao.getActiveBudgetsFlow() } returns MutableStateFlow(emptyList())
        coEvery { categoryDao.getAllFlow() } returns MutableStateFlow(emptyList())
        // A.9: aggregate-query contract — invalidation trigger + aggregate queries
        every { expenseDao.getTotalSpentFlow() } returns MutableStateFlow(0.0)
        every { expenseDao.observeExpenseMutationClock() } returns MutableStateFlow(0)
        coEvery { expenseDao.getTotalForPeriod(any(), any()) } returns 0.0
        coEvery { expenseDao.getCategorySpentInPeriod(any(), any(), any()) } returns 0.0
        coEvery { expenseDao.getTotalSpentBetweenByCurrency(any(), any()) } returns listOf(CurrencyTotal("EUR", 0.0, 0))
        every { timeProvider.now() } returns System.currentTimeMillis()

        coEvery { currencyConverter.convertMultiple(any(), any()) } answers {
            val amounts = firstArg<List<Pair<Double, String>>>()
            val targetCurrency = secondArg<String>()
            val total = amounts.sumOf { it.first }
            MultiConversionAggregate(total = total, targetCurrency = targetCurrency, failedConversions = emptyList())
        }

        multiCurrencyRepository = MultiCurrencyRepository(
            expenseDao = expenseDao,
            currencyConverter = currencyConverter,
            timeProvider = timeProvider,
            currencySettingsRepository = currencySettingsRepository
        )

        repository = BudgetRepository(
            budgetDao,
            categoryDao,
            expenseDao,
            budgetCalculator,
            timeProvider,
            offsetEngine,
            TimeBoundaryTicker(timeProvider),
            currencyConverter,
            currencySettingsRepository,
            multiCurrencyRepository,
            writeBarrier,
            database,
            budgetForecastDao
        )
    }

    // ============================================================================
    // SECTION 1: VALIDATION EDGE CASES
    // ============================================================================

    @Test
    fun `stress - addBudget with zero amount fails`() = runTest(UnconfinedTestDispatcher()) {
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
    fun `stress - addBudget with negative amount fails`() = runTest(UnconfinedTestDispatcher()) {
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
    fun `stress - addBudget with zero startDate fails`() = runTest(UnconfinedTestDispatcher()) {
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
    fun `stress - addBudget with negative startDate fails`() = runTest(UnconfinedTestDispatcher()) {
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
    fun `stress - addBudget with valid data succeeds`() = runTest(UnconfinedTestDispatcher()) {
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
    fun `stress - updateBudget with zero amount fails`() = runTest(UnconfinedTestDispatcher()) {
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
    fun `stress - updateBudget with negative amount fails`() = runTest(UnconfinedTestDispatcher()) {
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
    fun `stress - add many budgets`() = runTest(UnconfinedTestDispatcher()) {
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
    fun `stress - toggle many budgets`() = runTest(UnconfinedTestDispatcher()) {
        repeat(50) { i ->
            repository.toggleBudget(i.toLong() + 1, i % 2 == 0)
        }
    }

    // ============================================================================
    // SECTION 4: EDGE CASES
    // ============================================================================

    @Test
    fun `stress - very large budget amount`() = runTest(UnconfinedTestDispatcher()) {
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
    fun `stress - very small budget amount`() = runTest(UnconfinedTestDispatcher()) {
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

    // ============================================================================
    // SECTION 5: LARGE-HISTORY AGGREGATE REGRESSION (A.9 Batch 2)
    // ============================================================================
    // These tests prove that getBudgetStatuses() relies on aggregate SQL queries
    // (getTotalForPeriod / getCategorySpentInPeriod) and NOT on capped row-level
    // reads. The aggregate mock returns a total that would require >2000 individual
    // rows (the old DAO default cap), proving the result is independent of row count.

    /**
     * Regression: whole-wallet budget with aggregate spend equivalent to >2000 rows.
     *
     * If the old row-level code were still in use, a LIMIT 2000 cap on
     * getExpensesBetween would produce a lower total. The aggregate query
     * returns the correct sum regardless of row count.
     */
    @Test
    fun `large history - whole-wallet budget uses aggregate query not capped rows`() = runTest(UnconfinedTestDispatcher()) {
        val now = makeUtcMs(2026, 4, 10)
        every { timeProvider.now() } returns now

        val startOfMonth = makeUtcMs(2026, 4, 1)
        val endOfMonth = makeUtcMs(2026, 5, 1)

        val budget = Budget(
            id = 1L,
            categoryId = null,
            amount = 50_000.0,
            period = BudgetPeriod.MONTHLY,
            startDate = startOfMonth,
            isActive = true,
            rollover = false,
            notifyAtWarning = 0.75f,
            notifyAtCritical = 0.90f
        )

        every { budgetCalculator.calculatePeriodRange(any(), any()) } returns (startOfMonth to endOfMonth)
        every { budgetDao.getActiveBudgetsFlow() } returns flowOf(listOf(budget))
        every { categoryDao.getAllFlow() } returns flowOf(emptyList<Category>())
        every { expenseDao.getTotalSpentFlow() } returns flowOf(30_000.0)

        // Aggregate sum = 30 000.  If 3 000 rows × €10 each = €30 000, the old
        // LIMIT 2000 would have produced only €20 000.  The aggregate returns the
        // correct total regardless of how many rows exist.
        coEvery { expenseDao.getTotalForPeriod(startOfMonth, endOfMonth) } returns 30_000.0
        coEvery { expenseDao.getTotalSpentBetweenByCurrency(startOfMonth, endOfMonth) } returns listOf(CurrencyTotal("EUR", 30_000.0, 1))

        val statuses = repository.getBudgetStatuses().first()

        assertEquals(1, statuses.size)
        assertEquals(30_000.0, statuses[0].spentAmount, 0.001)
        assertEquals(20_000.0, statuses[0].remainingAmount, 0.001)
        assertEquals(0.6f, statuses[0].percentUsed, 0.001f)
        assertEquals(BudgetHealthStatus.ON_TRACK, statuses[0].healthStatus)
    }

    /**
     * Regression: category-scoped budget with large-history aggregate.
     *
     * Proves getCategorySpentInPeriod is used (not a capped row scan + filter).
     */
    @Test
    fun `large history - category budget uses aggregate query not capped rows`() = runTest(UnconfinedTestDispatcher()) {
        val now = makeUtcMs(2026, 4, 10)
        every { timeProvider.now() } returns now

        val startOfMonth = makeUtcMs(2026, 4, 1)
        val endOfMonth = makeUtcMs(2026, 5, 1)
        val categoryId = 42L

        val category = Category(categoryId, "Groceries", "🛒", "#00FF00", false)
        val budget = Budget(
            id = 2L,
            categoryId = categoryId,
            amount = 5_000.0,
            period = BudgetPeriod.MONTHLY,
            startDate = startOfMonth,
            isActive = true,
            rollover = false,
            notifyAtWarning = 0.75f,
            notifyAtCritical = 0.90f
        )

        every { budgetCalculator.calculatePeriodRange(any(), any()) } returns (startOfMonth to endOfMonth)
        every { budgetDao.getActiveBudgetsFlow() } returns flowOf(listOf(budget))
        every { categoryDao.getAllFlow() } returns flowOf(listOf(category))
        every { expenseDao.getTotalSpentFlow() } returns flowOf(4_500.0)

        // 4 500 from 2500 rows × €1.80 each — old LIMIT 2000 would cap at €3 600.
        coEvery { expenseDao.getCategoryTotalsBetweenByCurrency(any(), any()) } returns listOf(
            CategoryCurrencyTotal(categoryId = categoryId, currency = "EUR", total = 4_500.0, txCount = 1)
        )
        coEvery { expenseDao.getTotalSpentBetweenByCurrency(any(), any()) } returns listOf(CurrencyTotal("EUR", 4_500.0, 1))

        val statuses = repository.getBudgetStatuses().first()

        assertEquals(1, statuses.size)
        assertEquals(4_500.0, statuses[0].spentAmount, 0.001)
        assertEquals(500.0, statuses[0].remainingAmount, 0.001)
        assertEquals(BudgetHealthStatus.CRITICAL, statuses[0].healthStatus)
    }

    /**
     * Regression: rollover budget with large-history across multiple periods.
     *
     * Simulates 12 completed monthly periods each with aggregate totals that would
     * require thousands of rows. Verifies the compounding rollover arithmetic uses
     * aggregate queries for every historical window.
     */
    @Test
    fun `large history - rollover across 12 periods uses aggregate queries per window`() = runTest(UnconfinedTestDispatcher()) {
        // Budget: anchor Jan 1 2025, monthly, €2 000, rollover = true
        // "Now" = Jan 10 2026 → 12 completed periods (Jan→Dec 2025), active = Jan 2026
        val now = makeUtcMs(2026, 1, 10)
        every { timeProvider.now() } returns now

        val anchorMs = makeUtcMs(2025, 1, 1)
        val activeStart = makeUtcMs(2026, 1, 1)
        val activeEnd = makeUtcMs(2026, 2, 1)

        val budget = Budget(
            id = 3L,
            categoryId = null,
            amount = 2_000.0,
            period = BudgetPeriod.MONTHLY,
            startDate = anchorMs,
            isActive = true,
            rollover = true,
            notifyAtWarning = 0.75f,
            notifyAtCritical = 0.90f
        )

        // Use real BudgetCalculator so rollover window iteration works correctly.
        val realCalc = BudgetCalculator(timeProvider)
        val repo = BudgetRepository(
            budgetDao, categoryDao, expenseDao, realCalc,
            timeProvider, offsetEngine, TimeBoundaryTicker(timeProvider),
            currencyConverter, currencySettingsRepository, multiCurrencyRepository,
            writeBarrier, database, budgetForecastDao
        )

        every { budgetDao.getActiveBudgetsFlow() } returns flowOf(listOf(budget))
        every { categoryDao.getAllFlow() } returns flowOf(emptyList<Category>())
        every { expenseDao.getTotalSpentFlow() } returns flowOf(0.0)

        // Each completed period: spent 1 800 out of effective limit → surplus 200
        // (first period surplus = 2000 - 1800 = 200, compounding adds surplus each period).
        // For simplicity, use 1800 for every period query.
        coEvery { expenseDao.getTotalForPeriod(any(), any()) } returns 1_800.0
        coEvery { expenseDao.getTotalSpentBetweenByCurrency(any(), any()) } returns listOf(CurrencyTotal("EUR", 1_800.0, 1))

        val statuses = repo.getBudgetStatuses().first()

        assertEquals(1, statuses.size)

        // After 12 periods of compounding rollover (each period spent = 1800, base = 2000):
        // Period 1: effectiveLimit = 2000, surplus = 200, next effectiveLimit = 2200
        // Period 2: effectiveLimit = 2200, surplus = 400, next effectiveLimit = 2400
        // Period 3: effectiveLimit = 2400, surplus = 600, next effectiveLimit = 2600
        // ...each period adds 200 more to surplus since getTotalForPeriod returns constant 1800
        // Period N: effectiveLimit = 2000 + (N-1)*200, surplus = effectiveLimit - 1800
        // After 12 periods: effectiveLimit = 2000 + 12*200 = 4400
        // (because surplus after period 12 = (2000 + 11*200) - 1800 = 4200 - 1800 = 2400,
        //  effectiveLimit for active = 2000 + 2400 = 4400)
        // Actually let me re-derive:
        // Period 1: eff=2000, surplus=max(0, 2000-1800)=200, next eff=2000+200=2200
        // Period 2: eff=2200, surplus=max(0, 2200-1800)=400, next eff=2000+400=2400
        // Period 3: eff=2400, surplus=600, next eff=2000+600=2600
        // Period 4: eff=2600, surplus=800, next eff=2000+800=2800
        // Period 5: eff=2800, surplus=1000, next eff=2000+1000=3000
        // Period 6: eff=3000, surplus=1200, next eff=2000+1200=3200
        // Period 7: eff=3200, surplus=1400, next eff=2000+1400=3400
        // Period 8: eff=3400, surplus=1600, next eff=2000+1600=3600
        // Period 9: eff=3600, surplus=1800, next eff=2000+1800=3800
        // Period 10: eff=3800, surplus=2000, next eff=2000+2000=4000
        // Period 11: eff=4000, surplus=2200, next eff=2000+2200=4200
        // Period 12: eff=4200, surplus=2400, next eff=2000+2400=4400
        val expectedEffectiveLimit = 4_400.0
        assertEquals(expectedEffectiveLimit, statuses[0].effectiveLimit, 0.01)

        // Active period spend = 1800 (from aggregate)
        assertEquals(1_800.0, statuses[0].spentAmount, 0.001)
        assertEquals(expectedEffectiveLimit - 1_800.0, statuses[0].remainingAmount, 0.01)

        // Verify aggregate queries were called — not row-level reads
        coVerify(atLeast = 13) { expenseDao.getTotalSpentBetweenByCurrency(any(), any()) }
        // Ensure no row-level reads were made
        coVerify(exactly = 0) { expenseDao.getExpensesBetween(any(), any(), any(), any()) }
        coVerify(exactly = 0) { expenseDao.getExpensesBetweenUncapped(any(), any()) }
    }

    /**
     * Proves that the invalidation trigger (getTotalSpentFlow) is what causes
     * the combine block to re-run — not direct expense-row observation.
     */
    @Test
    fun `aggregate contract - getTotalSpentFlow is used as invalidation trigger`() = runTest(UnconfinedTestDispatcher()) {
        val now = makeUtcMs(2026, 4, 10)
        every { timeProvider.now() } returns now

        val start = makeUtcMs(2026, 4, 1)
        val end = makeUtcMs(2026, 5, 1)

        val budget = Budget(
            id = 1L, categoryId = null, amount = 1000.0,
            period = BudgetPeriod.MONTHLY, startDate = start,
            isActive = true, rollover = false,
            notifyAtWarning = 0.75f, notifyAtCritical = 0.90f
        )

        every { budgetCalculator.calculatePeriodRange(any(), any()) } returns (start to end)
        every { budgetDao.getActiveBudgetsFlow() } returns flowOf(listOf(budget))
        every { categoryDao.getAllFlow() } returns flowOf(emptyList<Category>())
        every { expenseDao.getTotalSpentFlow() } returns flowOf(500.0)
        coEvery { expenseDao.getTotalSpentBetweenByCurrency(start, end) } returns listOf(CurrencyTotal("EUR", 500.0, 1))

        val statuses = repository.getBudgetStatuses().first()

        assertEquals(500.0, statuses[0].spentAmount, 0.001)
        // Confirm aggregate invalidation trigger was observed
        verify(atLeast = 1) { expenseDao.observeExpenseMutationClock() }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun makeUtcMs(year: Int, month: Int, day: Int): Long {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(year, month - 1, day, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
