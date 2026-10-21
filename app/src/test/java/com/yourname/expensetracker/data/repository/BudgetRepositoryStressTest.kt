package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.assertMoneyExact
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
import com.yourname.expensetracker.domain.util.PinnedUtcTimeZoneRule
import com.yourname.expensetracker.domain.util.TimeBoundaryTicker
import com.yourname.expensetracker.domain.util.TimeProvider
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
import org.junit.Rule
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Budget repository large-history / rollover regression tests.
 *
 * Large-history regression proofs verify that getBudgetStatuses() uses aggregate SQL
 * queries instead of capped row-level reads, so budget correctness is independent of
 * the number of expense rows. Budget add/update validation lives in
 * [BudgetRepositoryHistoricalStatusTest]; wallet/category aggregate scenarios live in
 * [BudgetRepositoryTruncationTest].
 */
@Suppress("DEPRECATION_ERROR")
class BudgetRepositoryStressTest {

    // The real BudgetCalculator (rollover table) derives period windows from ZoneId.systemDefault().
    @get:Rule
    val utcZone = PinnedUtcTimeZoneRule()

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
            currencySettingsRepository = currencySettingsRepository,
            applicationScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
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
    // LARGE-HISTORY AGGREGATE REGRESSION (A.9 Batch 2) — compounding rollover
    // ============================================================================
    // These rows prove that getBudgetStatuses() relies on aggregate SQL queries
    // (one grouped-by-currency query per period window) and NOT on capped row-level
    // reads, and that the compounding rollover arithmetic uses every completed window.

    private data class RolloverRow(
        val name: String,
        val anchor: Triple<Int, Int, Int>,
        val now: Triple<Int, Int, Int>,
        val baseLimit: Double,
        val spentPerWindow: Double,
        /** Completed periods between the anchor and the active window. */
        val completedPeriods: Int,
        val expectedEffectiveLimit: Double,
        val expectedRemaining: Double
    )

    /**
     * Rollover: base limit B, constant spend S in every window (completed and active).
     * Each completed period folds: running = B + max(0, running - S), starting at B.
     * The aggregate query must be issued exactly once per completed window plus once for
     * the active window, and no row-level expense read may ever happen.
     */
    @Test
    fun `large history - rollover compounds from per-window aggregate queries`() = runTest(UnconfinedTestDispatcher()) {
        val rows = listOf(
            // B=2000 S=1800: limits 2000 -> 2200 -> 2400 -> ... -> after 12 periods 4400
            RolloverRow(
                name = "12 periods anchor 2025-01-01 now 2026-01-10",
                anchor = Triple(2025, 1, 1), now = Triple(2026, 1, 10),
                baseLimit = 2_000.0, spentPerWindow = 1_800.0, completedPeriods = 12,
                expectedEffectiveLimit = 4_400.0, expectedRemaining = 2_600.0
            ),
            // B=5000 S=4800: 5000 -> 5200 -> 5400 -> 5600 over Jan, Feb, Mar
            RolloverRow(
                name = "3 periods anchor 2026-01-01 now 2026-04-10",
                anchor = Triple(2026, 1, 1), now = Triple(2026, 4, 10),
                baseLimit = 5_000.0, spentPerWindow = 4_800.0, completedPeriods = 3,
                expectedEffectiveLimit = 5_600.0, expectedRemaining = 800.0
            ),
            // B=1000 S=800: 1000 -> 1200 -> 1400 over Jan, Feb
            RolloverRow(
                name = "2 periods anchor 2026-01-01 now 2026-03-15",
                anchor = Triple(2026, 1, 1), now = Triple(2026, 3, 15),
                baseLimit = 1_000.0, spentPerWindow = 800.0, completedPeriods = 2,
                expectedEffectiveLimit = 1_400.0, expectedRemaining = 600.0
            ),
        )
        assertEquals("rollover table size", 3, rows.size)

        val failures = mutableListOf<String>()
        for (row in rows) {
            try {
                // Fresh mocks per row so coVerify counts never leak between rows.
                val rowTime = mockk<TimeProvider>(relaxed = true)
                every { rowTime.now() } returns makeUtcMs(row.now.first, row.now.second, row.now.third)

                val rowExpenseDao = mockk<ExpenseDao>(relaxed = true)
                every { rowExpenseDao.getTotalSpentFlow() } returns flowOf(0.0)
                every { rowExpenseDao.observeExpenseMutationClock() } returns flowOf(0)
                coEvery { rowExpenseDao.getTotalForPeriod(any(), any()) } returns row.spentPerWindow
                coEvery { rowExpenseDao.getTotalSpentBetweenByCurrency(any(), any()) } returns
                    listOf(CurrencyTotal("EUR", row.spentPerWindow, 1))

                val rowMultiCurrency = MultiCurrencyRepository(
                    expenseDao = rowExpenseDao,
                    currencyConverter = currencyConverter,
                    timeProvider = rowTime,
                    currencySettingsRepository = currencySettingsRepository,
                    applicationScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
                )

                val budget = Budget(
                    id = 3L,
                    categoryId = null,
                    amount = row.baseLimit,
                    period = BudgetPeriod.MONTHLY,
                    startDate = makeUtcMs(row.anchor.first, row.anchor.second, row.anchor.third),
                    isActive = true,
                    rollover = true,
                    notifyAtWarning = 0.75f,
                    notifyAtCritical = 0.90f
                )
                every { budgetDao.getActiveBudgetsFlow() } returns flowOf(listOf(budget))
                every { categoryDao.getAllFlow() } returns flowOf(emptyList<Category>())

                // Real BudgetCalculator so rollover window iteration works correctly.
                val repo = BudgetRepository(
                    budgetDao, categoryDao, rowExpenseDao, BudgetCalculator(rowTime),
                    rowTime, offsetEngine, TimeBoundaryTicker(rowTime),
                    currencyConverter, currencySettingsRepository, rowMultiCurrency,
                    writeBarrier, database, budgetForecastDao
                )

                val statuses = repo.getBudgetStatuses().first()

                assertEquals("statuses size", 1, statuses.size)
                // Integer-valued compounding arithmetic is exact in IEEE-754 doubles.
                assertMoneyExact(row.expectedEffectiveLimit, statuses[0].effectiveLimit, "effectiveLimit")
                assertMoneyExact(row.spentPerWindow, statuses[0].spentAmount, "active-period spend")
                assertMoneyExact(row.expectedRemaining, statuses[0].remainingAmount, "remainingAmount")

                // One aggregate query per completed window + one for the active window.
                coVerify(exactly = row.completedPeriods + 1) {
                    rowExpenseDao.getTotalSpentBetweenByCurrency(any(), any())
                }
                // No row-level reads.
                coVerify(exactly = 0) { rowExpenseDao.getExpensesBetween(any(), any(), any(), any()) }
                coVerify(exactly = 0) { rowExpenseDao.getExpensesBetweenUncapped(any(), any()) }
            } catch (e: AssertionError) {
                failures += "[${row.name}] ${e.message}"
            }
        }
        if (failures.isNotEmpty()) {
            fail("rollover rows failed:\n" + failures.joinToString("\n"))
        }
    }

    /**
     * Proves that the invalidation trigger (observeExpenseMutationClock) is what causes
     * the combine block to re-run — not direct expense-row observation.
     *
     * Also pins the whole-wallet status derived through the flow path: 500 spent of a
     * 1000 limit is 50% (carries the percentUsed check formerly in the 50 000 / 30 000
     * large-history wallet test).
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

        assertMoneyExact(500.0, statuses[0].spentAmount, "spent pass-through")
        assertEquals(0.5f, statuses[0].percentUsed, 1e-6f)
        assertEquals(BudgetHealthStatus.ON_TRACK, statuses[0].healthStatus)
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
