package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.assertMoneyExact
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.BudgetDao
import com.yourname.expensetracker.data.database.dao.BudgetForecastDao
import com.yourname.expensetracker.data.database.dao.CategoryCurrencyTotal
import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.dao.CurrencyTotal
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.domain.budget.BudgetCalculator
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.currency.MultiConversionAggregate
import com.yourname.expensetracker.domain.groups.SharedExpenseBudgetOffsetEngine
import com.yourname.expensetracker.domain.util.TimeBoundaryTicker
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * A.9 Batch 2 — Truncation regression tests for BudgetRepository.
 *
 * These tests prove that [BudgetRepository.getBudgetStatuses] uses aggregate SQL
 * queries (per-currency aggregates) instead of capped row-level reads (the old
 * LIMIT 500 / LIMIT 2000 contract).
 *
 * Each table row sets aggregate totals that represent a dataset larger than the old
 * DAO default caps and asserts that the budget status values match the aggregate
 * total — not a truncated partial sum. Mock verification confirms that no row-level
 * expense reads were invoked. Every row builds its own mocks and repository so
 * coVerify counts never leak between rows.
 *
 * The compounding-rollover rows live in [BudgetRepositoryStressTest] (rollover table,
 * rows 2 and 3 carry the former Truncation rollover scenarios with exact assertions).
 */
@Suppress("DEPRECATION_ERROR")
class BudgetRepositoryTruncationTest {

    /** One fresh set of collaborators (and a repository wired to them) per table row. */
    private class Rig {
        val budgetDao = mockk<BudgetDao>(relaxed = true)
        val categoryDao = mockk<CategoryDao>(relaxed = true)
        val expenseDao = mockk<ExpenseDao>(relaxed = true)
        val budgetCalculator = mockk<BudgetCalculator>(relaxed = true)
        val timeProvider = mockk<TimeProvider>(relaxed = true)
        val offsetEngine = mockk<SharedExpenseBudgetOffsetEngine>(relaxed = true)
        val currencyConverter = mockk<CurrencyConverter>(relaxed = true)
        val currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true)
        val writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true)
        val database = mockk<AppDatabase>(relaxed = true)
        val budgetForecastDao = mockk<BudgetForecastDao>(relaxed = true)
        val repository: BudgetRepository

        init {
            every { currencySettingsRepository.homeCurrency() } returns flowOf("EUR")
            coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
                HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
            every { currencySettingsRepository.emergencyBuffer() } returns flowOf(500.0)
            every { expenseDao.getTotalSpentFlow() } returns flowOf(0.0)
            every { expenseDao.observeExpenseMutationClock() } returns flowOf(0)
            coEvery { expenseDao.getTotalForPeriod(any(), any()) } returns 0.0
            coEvery { expenseDao.getCategorySpentInPeriod(any(), any(), any()) } returns 0.0
            coEvery { expenseDao.getTotalSpentBetweenByCurrency(any(), any()) } returns
                listOf(CurrencyTotal("EUR", 0.0, 0))

            coEvery { currencyConverter.convertMultiple(any(), any()) } answers {
                val amounts = firstArg<List<Pair<Double, String>>>()
                val targetCurrency = secondArg<String>()
                val total = amounts.sumOf { it.first }
                MultiConversionAggregate(
                    total = total,
                    targetCurrency = targetCurrency,
                    failedConversions = emptyList()
                )
            }

            val multiCurrencyRepository = MultiCurrencyRepository(
                expenseDao = expenseDao,
                currencyConverter = currencyConverter,
                timeProvider = timeProvider,
                currencySettingsRepository = currencySettingsRepository,
                applicationScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
            )

            repository = BudgetRepository(
                budgetDao, categoryDao, expenseDao, budgetCalculator,
                timeProvider, offsetEngine, TimeBoundaryTicker(timeProvider),
                currencyConverter, currencySettingsRepository,
                multiCurrencyRepository = multiCurrencyRepository,
                writeBarrier = writeBarrier,
                database = database,
                budgetForecastDao = budgetForecastDao,
            )
        }
    }

    private data class WalletRow(
        val name: String,
        val amount: Double,
        val spent: Double,
        /** Exact remaining; over-limit rows are clamped to exactly 0.0 by production. */
        val expectedRemaining: Double,
        val expectedHealth: BudgetHealthStatus,
        /** When set, percentUsed must be at least this ratio (over-limit rows). */
        val minPercentUsed: Float?
    )

    private data class CategoryRow(
        val name: String,
        val categoryId: Long,
        val categoryName: String,
        val amount: Double,
        val spent: Double,
        /** Exact remaining; over-limit rows are clamped to exactly 0.0 by production. */
        val expectedRemaining: Double,
        val expectedHealth: BudgetHealthStatus
    )

    // ── Whole-wallet budget: totals above the former 500 / 2000 row caps ─────

    /**
     * Wallet table. Thresholds: notifyAtWarning = 0.75, notifyAtCritical = 0.90.
     *  - 8 000 of 10 000 (~800 rows x EUR 10; old LIMIT 500 would show 5 000) -> 0.80 WARNING.
     *  - 25 000 of 20 000 (2 500 rows x EUR 10; old LIMIT 2000 would show 20 000 and miss the
     *    overrun) -> EXCEEDED, percent >= 1.
     *  - 9 500 of 10 000 -> 0.95 CRITICAL (>= 0.9, < 1.0).
     *  - 500 of 1 000 -> 0.5 ON_TRACK; the PURCHASE-filtered per-currency aggregate is read
     *    exactly once (deposits/transfers/withdrawals cannot reach it by construction).
     */
    @Test
    fun `truncation regression - wallet budget status derives from uncapped aggregate total`() =
        runTest(UnconfinedTestDispatcher()) {
            val rows = listOf(
                WalletRow(
                    name = "800-row equivalent 8000 of 10000 not capped at 500 -> WARNING",
                    amount = 10_000.0, spent = 8_000.0, expectedRemaining = 2_000.0,
                    expectedHealth = BudgetHealthStatus.WARNING, minPercentUsed = null
                ),
                WalletRow(
                    name = "2500-row equivalent 25000 of 20000 not capped at 2000 -> EXCEEDED",
                    amount = 20_000.0, spent = 25_000.0, expectedRemaining = 0.0,
                    expectedHealth = BudgetHealthStatus.EXCEEDED, minPercentUsed = 1.0f
                ),
                WalletRow(
                    name = "9500 of 10000 -> CRITICAL",
                    amount = 10_000.0, spent = 9_500.0, expectedRemaining = 500.0,
                    expectedHealth = BudgetHealthStatus.CRITICAL, minPercentUsed = null
                ),
                WalletRow(
                    name = "500 of 1000 -> ON_TRACK",
                    amount = 1_000.0, spent = 500.0, expectedRemaining = 500.0,
                    expectedHealth = BudgetHealthStatus.ON_TRACK, minPercentUsed = null
                ),
            )
            assertEquals("wallet table size", 4, rows.size)

            val failures = mutableListOf<String>()
            for (row in rows) {
                try {
                    val rig = Rig()
                    val start = makeUtcMs(2026, 3, 1)
                    val end = makeUtcMs(2026, 4, 1)
                    val budget = makeBudget(amount = row.amount, start = start)

                    every { rig.timeProvider.now() } returns makeUtcMs(2026, 3, 15)
                    every { rig.budgetCalculator.calculatePeriodRange(any(), any()) } returns (start to end)
                    every { rig.budgetDao.getActiveBudgetsFlow() } returns flowOf(listOf(budget))
                    every { rig.categoryDao.getAllFlow() } returns flowOf(emptyList<Category>())
                    every { rig.expenseDao.getTotalSpentFlow() } returns flowOf(row.spent)
                    coEvery { rig.expenseDao.getTotalForPeriod(start, end) } returns row.spent
                    coEvery { rig.expenseDao.getTotalSpentBetweenByCurrency(start, end) } returns
                        listOf(CurrencyTotal("EUR", row.spent, 1))

                    val statuses = rig.repository.getBudgetStatuses().first()

                    assertEquals("statuses size", 1, statuses.size)
                    assertMoneyExact(row.spent, statuses[0].spentAmount, "spentAmount pass-through")
                    assertMoneyExact(row.expectedRemaining, statuses[0].remainingAmount, "remainingAmount")
                    assertEquals("healthStatus", row.expectedHealth, statuses[0].healthStatus)
                    row.minPercentUsed?.let {
                        assertTrue(
                            "percentUsed ${statuses[0].percentUsed} must be >= $it",
                            statuses[0].percentUsed >= it
                        )
                    }

                    // The aggregate path was used exactly once and no row-level read happened.
                    coVerify(exactly = 1) { rig.expenseDao.getTotalSpentBetweenByCurrency(start, end) }
                    verifyNoRowLevelReads(rig)
                } catch (e: AssertionError) {
                    failures += "[${row.name}] ${e.message}"
                }
            }
            if (failures.isNotEmpty()) {
                fail("wallet rows failed:\n" + failures.joinToString("\n"))
            }
        }

    // ── Category-scoped budget: totals above the former row caps ─────────────

    /**
     * Category table. Category budgets read the per-category per-currency aggregate exactly
     * once and never fall back to the whole-wallet aggregate (getTotalForPeriod) or to
     * row-level reads.
     *  - Transport 3 000 budget, 3 500 spent (3 000+-row equivalent) -> EXCEEDED.
     *  - Groceries 800 budget, 200 spent -> remaining 600, ON_TRACK (0.25).
     *  - Groceries 5 000 budget, 4 500 spent (2 500 rows x EUR 1.80; old LIMIT 2000 would cap
     *    at 3 600) -> remaining 500, 0.90 CRITICAL.
     */
    @Test
    fun `truncation regression - category budget status derives from uncapped category aggregate`() =
        runTest(UnconfinedTestDispatcher()) {
            val rows = listOf(
                CategoryRow(
                    name = "Transport 3500 of 3000 -> EXCEEDED",
                    categoryId = 7L, categoryName = "Transport",
                    amount = 3_000.0, spent = 3_500.0, expectedRemaining = 0.0,
                    expectedHealth = BudgetHealthStatus.EXCEEDED
                ),
                CategoryRow(
                    name = "Groceries 200 of 800 -> remaining 600 ON_TRACK",
                    categoryId = 42L, categoryName = "Groceries",
                    amount = 800.0, spent = 200.0, expectedRemaining = 600.0,
                    expectedHealth = BudgetHealthStatus.ON_TRACK
                ),
                CategoryRow(
                    name = "Groceries 4500 of 5000 -> remaining 500 CRITICAL",
                    categoryId = 42L, categoryName = "Groceries",
                    amount = 5_000.0, spent = 4_500.0, expectedRemaining = 500.0,
                    expectedHealth = BudgetHealthStatus.CRITICAL
                ),
            )
            assertEquals("category table size", 3, rows.size)

            val failures = mutableListOf<String>()
            for (row in rows) {
                try {
                    val rig = Rig()
                    val start = makeUtcMs(2026, 3, 1)
                    val end = makeUtcMs(2026, 4, 1)
                    val category = Category(row.categoryId, row.categoryName, "icon", "#00FF00", false)
                    val budget = makeBudget(amount = row.amount, start = start, categoryId = row.categoryId)

                    every { rig.timeProvider.now() } returns makeUtcMs(2026, 3, 15)
                    every { rig.budgetCalculator.calculatePeriodRange(any(), any()) } returns (start to end)
                    every { rig.budgetDao.getActiveBudgetsFlow() } returns flowOf(listOf(budget))
                    every { rig.categoryDao.getAllFlow() } returns flowOf(listOf(category))
                    every { rig.expenseDao.getTotalSpentFlow() } returns flowOf(row.spent)
                    coEvery { rig.expenseDao.getCategoryTotalsBetweenByCurrency(any(), any()) } returns listOf(
                        CategoryCurrencyTotal(
                            categoryId = row.categoryId, currency = "EUR", total = row.spent, txCount = 1
                        )
                    )
                    coEvery { rig.expenseDao.getTotalSpentBetweenByCurrency(any(), any()) } returns
                        listOf(CurrencyTotal("EUR", row.spent, 1))

                    val statuses = rig.repository.getBudgetStatuses().first()

                    assertEquals("statuses size", 1, statuses.size)
                    assertMoneyExact(row.spent, statuses[0].spentAmount, "spentAmount pass-through")
                    assertMoneyExact(row.expectedRemaining, statuses[0].remainingAmount, "remainingAmount")
                    assertEquals("healthStatus", row.expectedHealth, statuses[0].healthStatus)
                    assertEquals("category", category, statuses[0].category)

                    // Category aggregate used once, no whole-wallet fallback, no row-level reads.
                    coVerify(exactly = 1) { rig.expenseDao.getCategoryTotalsBetweenByCurrency(any(), any()) }
                    coVerify(exactly = 0) { rig.expenseDao.getTotalForPeriod(any(), any()) }
                    verifyNoRowLevelReads(rig)
                } catch (e: AssertionError) {
                    failures += "[${row.name}] ${e.message}"
                }
            }
            if (failures.isNotEmpty()) {
                fail("category rows failed:\n" + failures.joinToString("\n"))
            }
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
     * Asserts that no row-level expense queries were called on this row's DAO.
     * This is the core proof that aggregate SQL is used instead of capped reads.
     */
    private fun verifyNoRowLevelReads(rig: Rig) {
        coVerify(exactly = 0) { rig.expenseDao.getExpensesBetween(any(), any(), any(), any()) }
        coVerify(exactly = 0) { rig.expenseDao.getExpensesBetweenUncapped(any(), any()) }
    }
}
