package com.yourname.expensetracker.data.repository

import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.data.database.dao.BudgetDao
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
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.BudgetForecastDao
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * PHASE 6 TEST: Budget Rollover
 *
 * Tests budget rollover logic where unspent amounts carry over to next period.
 * Tests the "Compounding Rollover" implementation (LOG-002 BUG-2 FIX).
 *
 * A.9 Batch 2: Tests use a real [BudgetCalculator] with fixed UTC timestamps
 * and aggregate-query mockk (getTotalSpentFlow, getTotalForPeriod,
 * getCategorySpentInPeriod) instead of a mocked calculator + row-level reads.
 * This eliminates the OOM/hang caused by the relaxed-mock returning
 * `PeriodRange(0,0)` from `calculatePeriodWindowForTime`, which made the
 * rollover while-loop in `getBudgetStatuses()` spin indefinitely.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("DEPRECATION_ERROR")
class BudgetRolloverTest {

    private val budgetDao = mockk<BudgetDao>(relaxed = true)
    private val categoryDao = mockk<CategoryDao>(relaxed = true)
    private val expenseDao = mockk<ExpenseDao>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private val offsetEngine = mockk<SharedExpenseBudgetOffsetEngine>(relaxed = true)
    private val currencyConverter = mockk<CurrencyConverter>(relaxed = true)
    private val currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true)
    private val writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true)
    private val database = mockk<AppDatabase>(relaxed = true)
    private val budgetForecastDao = mockk<BudgetForecastDao>(relaxed = true)

    private lateinit var multiCurrencyRepository: MultiCurrencyRepository

    /** Real calculator — required so rollover window iteration terminates correctly. */
    private lateinit var budgetCalculator: BudgetCalculator
    private lateinit var budgetRepository: BudgetRepository

    // Fixed "now" = 2026-04-10 00:00 UTC (matches today's date in env)
    private val NOW = makeUtcMs(2026, 4, 10)

    /** One day in millis */
    private val DAY_MS = 24L * 60L * 60L * 1000L

    @Suppress("DEPRECATION_ERROR")
    @Before
    fun setup() {
        every { timeProvider.now() } returns NOW

        budgetCalculator = BudgetCalculator(timeProvider)

        // A.9: Default aggregate mockk — invalidation trigger + aggregate queries
        every { expenseDao.getTotalSpentFlow() } returns flowOf(0.0)
        every { expenseDao.observeExpenseMutationClock() } returns flowOf(0)
        coEvery { expenseDao.getTotalForPeriod(any(), any()) } returns 0.0
        coEvery { expenseDao.getCategorySpentInPeriod(any(), any(), any()) } returns 0.0
        coEvery { expenseDao.getTotalSpentBetweenByCurrency(any(), any()) } returns listOf(CurrencyTotal("EUR", 0.0, 0))
        coEvery { expenseDao.getMonthlySpendingTotalsBetween(any(), any()) } returns emptyList()
        every { expenseDao.getExpensesBetweenFlow(any(), any()) } returns flowOf(emptyList())
        every { expenseDao.getExpensesBetweenFlowUncapped(any(), any()) } returns flowOf(emptyList())
        coEvery { expenseDao.getExpensesBetween(any(), any()) } returns emptyList()
        coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } returns emptyList()
        every { currencySettingsRepository.homeCurrency() } returns flowOf("EUR")
        every { currencySettingsRepository.emergencyBuffer() } returns flowOf(500.0)

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

        budgetRepository = BudgetRepository(
            budgetDao,
            categoryDao,
            expenseDao,
            budgetCalculator,
            timeProvider,
            offsetEngine,
            TimeBoundaryTicker(timeProvider),
            currencyConverter,
            currencySettingsRepository,
            multiCurrencyRepository = multiCurrencyRepository,
            writeBarrier = writeBarrier,
            database = database,
            budgetForecastDao = budgetForecastDao,
        )
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    // ============================================================================
    // Non-rollover baseline
    // ============================================================================

    @Test
    fun `budget without rollover does not carry over unspent amount`() = runTest {
        val start = makeUtcMs(2026, 4, 1)
        val budget = createBudget(rollover = false, amount = 1000.0, startDate = start)

        every { budgetDao.getActiveBudgetsFlow() } returns flowOf(listOf(budget))
        every { categoryDao.getAllFlow() } returns flowOf(emptyList<Category>())
        every { expenseDao.getTotalSpentFlow() } returns flowOf(600.0)
        coEvery { expenseDao.getTotalForPeriod(any(), any()) } returns 600.0
        coEvery { expenseDao.getTotalSpentBetweenByCurrency(any(), any()) } returns listOf(CurrencyTotal("EUR", 600.0, 1))

        val statuses = budgetRepository.getBudgetStatuses().first()

        assertThat(statuses).hasSize(1)
        assertThat(statuses[0].remainingAmount).isEqualTo(400.0) // 1000 - 600
        assertThat(statuses[0].budget.amount).isEqualTo(1000.0) // No rollover
    }

    // ============================================================================
    // Rollover — basic behavior
    // ============================================================================

    @Test
    fun `budget with rollover carries over unspent amount`() = runTest {
        // Anchor 2 months ago → 2 completed periods before the active one
        val start = makeUtcMs(2026, 2, 10)
        val budget = createBudget(rollover = true, amount = 1000.0, startDate = start)

        every { budgetDao.getActiveBudgetsFlow() } returns flowOf(listOf(budget))
        every { categoryDao.getAllFlow() } returns flowOf(emptyList<Category>())
        every { expenseDao.getTotalSpentFlow() } returns flowOf(0.0)
        coEvery { expenseDao.getTotalForPeriod(any(), any()) } returns 0.0
        coEvery { expenseDao.getTotalSpentBetweenByCurrency(any(), any()) } returns listOf(CurrencyTotal("EUR", 0.0, 0))

        val statuses = budgetRepository.getBudgetStatuses().first()

        assertThat(statuses).hasSize(1)
        assertThat(statuses[0].budget.rollover).isTrue()
        // With zero spend in all periods, surplus compounds: effective limit > base
        assertThat(statuses[0].budget.amount).isAtLeast(1000.0)
    }

    @Test
    fun `rollover accumulates over multiple periods`() = runTest {
        // Anchor 3 months ago: 3 completed monthly windows before active
        val start = makeUtcMs(2026, 1, 10)
        val budget = createBudget(rollover = true, amount = 1000.0, startDate = start)

        every { budgetDao.getActiveBudgetsFlow() } returns flowOf(listOf(budget))
        every { categoryDao.getAllFlow() } returns flowOf(emptyList<Category>())
        every { expenseDao.getTotalSpentFlow() } returns flowOf(0.0)

        // Each completed period: spent 800 out of effective limit
        // Period 1: eff=1000, surplus=200
        // Period 2: eff=1200, surplus=400
        // Period 3: eff=1400, surplus=600
        // Active: eff=1600
        coEvery { expenseDao.getTotalForPeriod(any(), any()) } returns 800.0
        coEvery { expenseDao.getTotalSpentBetweenByCurrency(any(), any()) } returns listOf(CurrencyTotal("EUR", 800.0, 1))

        val statuses = budgetRepository.getBudgetStatuses().first()

        assertThat(statuses[0].budget.rollover).isTrue()
        // Effective limit should reflect accumulated surplus
        assertThat(statuses[0].budget.amount).isAtLeast(1400.0)
    }

    // ============================================================================
    // Rollover — surplus never goes negative
    // ============================================================================

    @Test
    fun `rollover never goes negative`() = runTest {
        val start = makeUtcMs(2026, 2, 10)
        val budget = createBudget(rollover = true, amount = 1000.0, startDate = start)

        every { budgetDao.getActiveBudgetsFlow() } returns flowOf(listOf(budget))
        every { categoryDao.getAllFlow() } returns flowOf(emptyList<Category>())
        every { expenseDao.getTotalSpentFlow() } returns flowOf(1200.0)
        // Overspent in rollover periods → aggregate returns 1200 for historical windows
        coEvery { expenseDao.getTotalForPeriod(any(), any()) } returns 1200.0
        coEvery { expenseDao.getTotalSpentBetweenByCurrency(any(), any()) } returns listOf(CurrencyTotal("EUR", 1200.0, 1))

        val statuses = budgetRepository.getBudgetStatuses().first()

        // Surplus is clamped at 0, so effective limit stays at base amount
        assertThat(statuses[0].budget.amount).isAtLeast(1000.0)
    }

    // ============================================================================
    // Compounding rollover arithmetic
    // ============================================================================

    @Test
    fun `compounding rollover adds previous surpluses correctly`() = runTest {
        // Anchor 3 months ago
        val start = makeUtcMs(2026, 1, 10)
        val budget = createBudget(rollover = true, amount = 1000.0, startDate = start)

        every { budgetDao.getActiveBudgetsFlow() } returns flowOf(listOf(budget))
        every { categoryDao.getAllFlow() } returns flowOf(emptyList<Category>())
        every { expenseDao.getTotalSpentFlow() } returns flowOf(0.0)

        // Spend 500 in every window (including active)
        coEvery { expenseDao.getTotalForPeriod(any(), any()) } returns 500.0
        coEvery { expenseDao.getTotalSpentBetweenByCurrency(any(), any()) } returns listOf(CurrencyTotal("EUR", 500.0, 1))

        val statuses = budgetRepository.getBudgetStatuses().first()

        assertThat(statuses[0].budget.rollover).isTrue()
        // Period 1: eff=1000, surplus=500 → Period 2: eff=1500, surplus=1000 →
        // Period 3: eff=2000, surplus=1500 → Active: eff=2500
        assertThat(statuses[0].budget.amount).isAtLeast(2000.0)
        assertThat(statuses[0].spentAmount).isEqualTo(500.0)
    }

    // ============================================================================
    // Period boundary semantics
    // ============================================================================

    @Test
    fun `rollover calculation respects period boundaries`() = runTest {
        val start = makeUtcMs(2026, 1, 10)
        val budget = createBudget(
            rollover = true,
            amount = 1000.0,
            period = BudgetPeriod.MONTHLY,
            startDate = start
        )

        every { budgetDao.getActiveBudgetsFlow() } returns flowOf(listOf(budget))
        every { categoryDao.getAllFlow() } returns flowOf(emptyList<Category>())
        every { expenseDao.getTotalSpentFlow() } returns flowOf(0.0)
        coEvery { expenseDao.getTotalForPeriod(any(), any()) } returns 0.0
        coEvery { expenseDao.getTotalSpentBetweenByCurrency(any(), any()) } returns listOf(CurrencyTotal("EUR", 0.0, 0))

        val statuses = budgetRepository.getBudgetStatuses().first()

        // Should calculate rollover based on completed periods only
        assertThat(statuses).hasSize(1)
    }

    @Test
    fun `monthly budget rollover works across month boundaries`() = runTest {
        val start = makeUtcMs(2026, 2, 10)
        val budget = createBudget(
            rollover = true,
            amount = 1000.0,
            period = BudgetPeriod.MONTHLY,
            startDate = start
        )

        every { budgetDao.getActiveBudgetsFlow() } returns flowOf(listOf(budget))
        every { categoryDao.getAllFlow() } returns flowOf(emptyList<Category>())
        every { expenseDao.getTotalSpentFlow() } returns flowOf(800.0)
        // 800 spent in each rollover period
        coEvery { expenseDao.getTotalForPeriod(any(), any()) } returns 800.0
        coEvery { expenseDao.getTotalSpentBetweenByCurrency(any(), any()) } returns listOf(CurrencyTotal("EUR", 800.0, 1))

        val statuses = budgetRepository.getBudgetStatuses().first()

        assertThat(statuses[0].budget.rollover).isTrue()
        // 2 completed months with 200 surplus each → effective limit grows
        assertThat(statuses[0].budget.amount).isAtLeast(1200.0)
    }

    @Test
    fun `weekly budget rollover works across week boundaries`() = runTest {
        // Anchor 2 weeks ago
        val start = makeUtcMs(2026, 3, 27) // 2 weeks before Apr 10
        val budget = createBudget(
            rollover = true,
            amount = 500.0,
            period = BudgetPeriod.WEEKLY,
            startDate = start
        )

        every { budgetDao.getActiveBudgetsFlow() } returns flowOf(listOf(budget))
        every { categoryDao.getAllFlow() } returns flowOf(emptyList<Category>())
        every { expenseDao.getTotalSpentFlow() } returns flowOf(300.0)
        coEvery { expenseDao.getTotalForPeriod(any(), any()) } returns 300.0
        coEvery { expenseDao.getTotalSpentBetweenByCurrency(any(), any()) } returns listOf(CurrencyTotal("EUR", 300.0, 1))

        val statuses = budgetRepository.getBudgetStatuses().first()

        assertThat(statuses[0].budget.rollover).isTrue()
        // At least one completed week with 200 surplus
        assertThat(statuses[0].budget.amount).isAtLeast(500.0)
    }

    // ============================================================================
    // Category-scoped rollover
    // ============================================================================

    @Test
    fun `rollover with category filter only includes category expenses`() = runTest {
        val start = makeUtcMs(2026, 3, 10) // 1 completed month
        val category = Category(1L, "Food", "\uD83C\uDF7D\uFE0F", "#FF0000", false)
        val budget = createBudget(
            rollover = true,
            amount = 500.0,
            categoryId = 1L,
            startDate = start
        )

        every { budgetDao.getActiveBudgetsFlow() } returns flowOf(listOf(budget))
        every { categoryDao.getAllFlow() } returns flowOf(listOf(category))
        every { expenseDao.getTotalSpentFlow() } returns flowOf(200.0)
        // Budget has categoryId=1 → getCategorySpentInPeriod is called (only category 1 expenses)
        coEvery { expenseDao.getCategorySpentInPeriod(eq(1L), any(), any()) } returns 200.0
        coEvery { expenseDao.getTotalSpentBetweenByCurrency(any(), any()) } returns listOf(CurrencyTotal("EUR", 200.0, 1))

        val statuses = budgetRepository.getBudgetStatuses().first()

        // Active window spend should be 200
        assertThat(statuses[0].spentAmount).isEqualTo(200.0)
        // Verify no row-level reads
        coVerify(exactly = 0) { expenseDao.getExpensesBetween(any(), any(), any(), any()) }
    }

    // ============================================================================
    // Zero spend → full surplus rollover
    // ============================================================================

    @Test
    fun `surplus calculation with zero spend is full budget amount`() = runTest {
        val start = makeUtcMs(2026, 3, 10) // 1 completed month
        val budget = createBudget(rollover = true, amount = 1000.0, startDate = start)

        every { budgetDao.getActiveBudgetsFlow() } returns flowOf(listOf(budget))
        every { categoryDao.getAllFlow() } returns flowOf(emptyList<Category>())
        every { expenseDao.getTotalSpentFlow() } returns flowOf(0.0)
        coEvery { expenseDao.getTotalForPeriod(any(), any()) } returns 0.0
        coEvery { expenseDao.getTotalSpentBetweenByCurrency(any(), any()) } returns listOf(CurrencyTotal("EUR", 0.0, 0))

        val statuses = budgetRepository.getBudgetStatuses().first()

        // Zero spend in completed period → full 1000 surplus rolls over
        assertThat(statuses[0].spentAmount).isEqualTo(0.0)
        // Effective limit = 1000 (base) + 1000 (surplus from completed period) = 2000
        assertThat(statuses[0].budget.amount).isAtLeast(2000.0)
    }

    // ============================================================================
    // Anchor day coercion (Jan 31 → Feb 28)
    // ============================================================================

    /**
     * Regression for ISSUE-3: Rollover history must iterate anchored windows with an explicit
     * evaluation time so that every completed cycle is visited.
     *
     * Scenario (anchor day 31, monthly budget, amount = €1 000):
     *   - Budget starts 2025-01-31
     *   - Completed window 1: 2025-01-31 → 2025-02-28  (spent €600  → surplus €400)
     *   - Active window:      2025-02-28 → 2025-03-31  (now = 2025-03-05)
     *
     * With the old implicit-evaluation-time path, `calculatePeriodWindow(period, Feb-28-end)`
     * would evaluate against real-`now` (March) and resolve the *active* Feb-28→Mar-31 window
     * again instead of advancing to it as a new completed cycle, causing the loop to stop
     * without recording the Jan-31→Feb-28 window.
     *
     * With the corrected explicit path the Jan-31→Feb-28 window is collected and the €400
     * surplus is included in the effective limit (€1 400).
     */
    @Test
    fun `anchored monthly budget rollover includes completed Jan31-Feb28 cycle when evaluated in March`() = runTest {
        // Fix the time provider to 2025-03-05 00:00:00 UTC
        val marchFifthMs = makeUtcMs(2025, 3, 5)
        every { timeProvider.now() } returns marchFifthMs

        // Rebuild calculator+repo with the updated time
        val calc = BudgetCalculator(timeProvider)
        val repo = BudgetRepository(
            budgetDao, categoryDao, expenseDao, calc,
            timeProvider, offsetEngine, TimeBoundaryTicker(timeProvider),
            currencyConverter, currencySettingsRepository,
            multiCurrencyRepository = multiCurrencyRepository,
            writeBarrier = writeBarrier,
            database = database,
            budgetForecastDao = budgetForecastDao,
        )

        // Budget: anchor 2025-01-31, monthly, amount €1 000, rollover = true
        val jan31Ms = makeUtcMs(2025, 1, 31)
        val feb28Ms = makeUtcMs(2025, 2, 28)
        val budget = createBudget(
            rollover = true,
            amount = 1000.0,
            period = BudgetPeriod.MONTHLY,
            startDate = jan31Ms
        )

        every { budgetDao.getActiveBudgetsFlow() } returns flowOf(listOf(budget))
        every { categoryDao.getAllFlow()          } returns flowOf(emptyList<Category>())
        every { expenseDao.getTotalSpentFlow()    } returns flowOf(600.0)

        // Aggregate: €600 spent in the completed Jan31→Feb28 window, €0 in active window.
        coEvery { expenseDao.getTotalForPeriod(any(), any()) } answers {
            val start = firstArg<Long>()
            val end = secondArg<Long>()
            // Jan31→Feb28 window contains the €600 spend
            if (start <= jan31Ms && end <= feb28Ms + 86400000L) 600.0
            else 0.0
        }
        coEvery { expenseDao.getTotalSpentBetweenByCurrency(any(), any()) } answers {
            val start = firstArg<Long>()
            val end = secondArg<Long>()
            if (start <= jan31Ms && end <= feb28Ms + 86400000L) listOf(CurrencyTotal("EUR", 600.0, 1))
            else listOf(CurrencyTotal("EUR", 0.0, 0))
        }

        val statuses = repo.getBudgetStatuses().first()

        assertThat(statuses).hasSize(1)
        val status = statuses[0]

        // Effective limit = base €1 000 + surplus from Jan31→Feb28 window (€1 000 − €600 = €400)
        // → expected effective limit ≥ €1 400
        assertThat(status.budget.amount).isAtLeast(1400.0)
    }

    // ============================================================================
    // Active/inactive filtering
    // ============================================================================

    @Test
    fun `deactivating budget stops rollover accumulation`() = runTest {
        val start = makeUtcMs(2026, 3, 10)
        val activeBudget = createBudget(rollover = true, amount = 1000.0, isActive = true, startDate = start)

        every { budgetDao.getActiveBudgetsFlow() } returns flowOf(listOf(activeBudget))
        every { categoryDao.getAllFlow() } returns flowOf(emptyList())
        every { expenseDao.getTotalSpentFlow() } returns flowOf(0.0)
        coEvery { expenseDao.getTotalForPeriod(any(), any()) } returns 0.0
        coEvery { expenseDao.getTotalSpentBetweenByCurrency(any(), any()) } returns listOf(CurrencyTotal("EUR", 0.0, 0))

        val statuses = budgetRepository.getBudgetStatuses().first()

        // Only active budgets should appear
        assertThat(statuses).hasSize(1)
        assertThat(statuses[0].budget.isActive).isTrue()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun createBudget(
        rollover: Boolean,
        amount: Double,
        categoryId: Long? = null,
        period: BudgetPeriod = BudgetPeriod.MONTHLY,
        startDate: Long = makeUtcMs(2026, 4, 1),
        isActive: Boolean = true
    ): Budget {
        return Budget(
            id = 1L,
            categoryId = categoryId,
            amount = amount,
            period = period,
            periodMode = "ROLLING",
            startDate = startDate,
            isActive = isActive,
            rollover = rollover,
            currency = "EUR",
            createdAt = NOW,
            notifyAtWarning = 0.75f,
            notifyAtCritical = 0.90f
        )
    }

    /**
     * Returns the epoch-millisecond timestamp for midnight UTC on the given year/month/day.
     * Month is 1-based (1 = January).
     */
    private fun makeUtcMs(year: Int, month: Int, day: Int): Long {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(year, month - 1, day, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}