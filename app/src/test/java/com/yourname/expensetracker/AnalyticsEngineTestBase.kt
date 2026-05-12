package com.yourname.expensetracker

import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.time.LocalDate
import java.time.ZoneId

/**
 * Base test class for analytics engine tests.
 *
 * Provides common mocking infrastructure for:
 * - ExpenseDao (mocked with configurable return values)
 * - TimeProvider (fixed to April 1, 2026)
 * - CategoryRepository (mocked with test categories)
 *
 * Usage:
 * ```
 * class MyAnalyticsTest : AnalyticsEngineTestBase() {
 *     @Test
 *     fun testSomething() = runTest {
 *         mockExpenses(GoldenDataSets.simpleMonthPurchases)
 *
 *         // Run analytics engine
 *         val result = engine.calculate()
 *
 *         // Assert against ExpectedResults
 *         assertApproxEquals(ExpectedResults.SimpleMonth.TOTAL_SPENT, result.total)
 *     }
 * }
 * ```
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class AnalyticsEngineTestBase {

    // ============================================================================
    // Test Coroutine Support
    // ============================================================================

    /**
     * Test dispatcher for coroutine tests.
     */
    protected val testDispatcher: TestDispatcher = StandardTestDispatcher()

    /**
     * Test scope bound to the test dispatcher.
     */
    protected val testScope = TestScope(testDispatcher)

    /**
     * JUnit rule that sets up the Main dispatcher for coroutine tests.
     */
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(testDispatcher)

    // ============================================================================
    // mockk
    // ============================================================================

    /**
     * Mocked ExpenseDao for controlling database query responses.
     */
    protected lateinit var expenseDao: ExpenseDao

    /**
     * Mocked TimeProvider fixed to April 1, 2026.
     */
    protected lateinit var timeProvider: TimeProvider

    /**
     * Mocked CategoryRepository for category lookups.
     */
    protected lateinit var categoryRepository: CategoryRepository

    // ============================================================================
    // Reference "Now" - April 1, 2026
    // ============================================================================

    /**
     * Fixed "Now" timestamp for deterministic tests.
     * April 1, 2026 00:00:00 UTC
     */
    protected val fixedNow: Long = LocalDate.of(2026, 4, 1)
        .atStartOfDay(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

    /**
     * Reference month for March 2026 calculations.
     */
    protected val march2026Start: Long = LocalDate.of(2026, 3, 1)
        .atStartOfDay(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

    protected val march2026End: Long = LocalDate.of(2026, 3, 31)
        .atTime(23, 59, 59, 999_999_999)
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

    /**
     * Reference month for February 2026 calculations.
     */
    protected val february2026Start: Long = LocalDate.of(2026, 2, 1)
        .atStartOfDay(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

    protected val february2026End: Long = LocalDate.of(2026, 2, 28)
        .atTime(23, 59, 59, 999_999_999)
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

    // ============================================================================
    // Test Categories
    // ============================================================================

    /**
     * Standard test categories used across analytics tests.
     */
    protected val testCategories = listOf(
        Category(id = 1L, name = "Food & Dining", icon = "🍽️", color = "#FF5733"),
        Category(id = 2L, name = "Groceries", icon = "🛒", color = "#33FF57"),
        Category(id = 3L, name = "Entertainment", icon = "🎬", color = "#3357FF"),
        Category(id = 4L, name = "Travel", icon = "✈️", color = "#F333FF"),
        Category(id = 5L, name = "Utilities", icon = "💡", color = "#FFD700")
    )

    // ============================================================================
    // Setup
    // ============================================================================

    @Before
    open fun setUp() {
        // Create relaxed mockk
        expenseDao = mockk(relaxed = true)
        timeProvider = mockk(relaxed = true)
        categoryRepository = mockk(relaxed = true)

        // Configure fixed "Now"
        every { timeProvider.now() } returns fixedNow
        every { timeProvider.nowFormatted() } returns "2026-04-01 00:00"

        // Configure default category repository behavior
        setupDefaultCategoryMocks()

        // Configure default expense dao behavior (empty results)
        setupDefaultExpenseMocks()
    }

    // ============================================================================
    // Mock Configuration Helpers
    // ============================================================================

    /**
     * Sets up the category repository to return test categories.
     */
    private fun setupDefaultCategoryMocks() {
        // Return test categories by default
        io.mockk.coEvery { categoryRepository.getAll() } returns testCategories
        io.mockk.every { categoryRepository.allCategories } returns flowOf(testCategories)

        // Allow category lookup by name
        io.mockk.coEvery { categoryRepository.getCategoryByName(any()) } answers {
            val name = arg<String>(0)
            testCategories.find { it.name.equals(name, ignoreCase = true) }
        }
    }

    /**
     * Sets up default empty results for ExpenseDao queries.
     */
    @Suppress("DEPRECATION_ERROR")
    private fun setupDefaultExpenseMocks() {
        // Default: return empty lists
        io.mockk.coEvery { expenseDao.getExpensesBetween(any(), any()) } returns emptyList()
        io.mockk.coEvery { expenseDao.getExpensesByTypeBetween(any(), any(), any()) } returns emptyList()
        io.mockk.coEvery { expenseDao.getTotalSpentBetween(any(), any()) } returns 0.0
        io.mockk.coEvery { expenseDao.getMerchantTotalsBetween(any(), any()) } returns emptyList()
        io.mockk.coEvery { expenseDao.getCategoryTotalsBetween(any(), any()) } returns emptyList()
        io.mockk.coEvery { expenseDao.getAll() } returns emptyList()
        io.mockk.coEvery { expenseDao.getPurchaseCount() } returns 0
        io.mockk.coEvery { expenseDao.getOldestExpenseDate() } returns null

        // Flows
        io.mockk.every { expenseDao.getExpensesBetweenFlow(any(), any()) } returns flowOf(emptyList())
        io.mockk.every { expenseDao.getExpensesByTypeBetweenFlow(any(), any(), any()) } returns flowOf(emptyList())
        io.mockk.every { expenseDao.getTotalSpentFlow() } returns flowOf(0.0)

        // Multi-currency aggregate DAO methods (used by MultiCurrencyRepository → BudgetRepository, etc.)
        io.mockk.coEvery { expenseDao.getTotalSpentBetweenByCurrency(any(), any()) } returns emptyList()
        io.mockk.coEvery { expenseDao.getAllSpentBetweenByCurrency(any(), any()) } returns emptyList()
        io.mockk.coEvery { expenseDao.getAllCategoryTotalsBetweenByCurrency(any(), any()) } returns emptyList()
        io.mockk.coEvery { expenseDao.getMonthlyTotalsBetweenByCurrency(any(), any()) } returns emptyList()
        io.mockk.coEvery { expenseDao.getMerchantTotalsBetweenByCurrency(any(), any()) } returns emptyList()
    }

    /**
     * Configures the ExpenseDao to return specific expenses for all queries.
     *
     * @param expenses The expenses to return from queries
     */
    @Suppress("DEPRECATION_ERROR")
    protected fun mockExpenses(expenses: List<Expense>) {
        val purchasesOnly = expenses.filter { it.transactionType == TransactionType.PURCHASE }

        // Basic queries
        io.mockk.coEvery { expenseDao.getExpensesBetween(any(), any()) } returns purchasesOnly
        io.mockk.coEvery { expenseDao.getExpensesByTypeBetween(any(), any(), TransactionType.PURCHASE.name) } returns purchasesOnly
        io.mockk.coEvery { expenseDao.getAll() } returns purchasesOnly

        // Flow queries
        io.mockk.every { expenseDao.getExpensesBetweenFlow(any(), any()) } returns flowOf(purchasesOnly)
        io.mockk.every { expenseDao.getExpensesByTypeBetweenFlow(any(), any(), TransactionType.PURCHASE.name) } returns flowOf(purchasesOnly)

        // Totals
        val totalSpent = purchasesOnly.filter { !it.isNotMine }.sumOf { it.effectiveAmount }
        io.mockk.coEvery { expenseDao.getTotalSpentBetween(any(), any()) } returns totalSpent
        io.mockk.coEvery { expenseDao.getTotalSpentBetweenByCurrency(any(), any()) } returns
            if (totalSpent > 0.0) listOf(com.yourname.expensetracker.data.database.dao.CurrencyTotal("EUR", totalSpent, purchasesOnly.filter { !it.isNotMine }.size))
            else emptyList()

        // Count
        io.mockk.coEvery { expenseDao.getPurchaseCount() } returns purchasesOnly.size

        // Oldest date
        purchasesOnly.minOfOrNull { it.date }?.let { oldest ->
            io.mockk.coEvery { expenseDao.getOldestExpenseDate() } returns oldest
        }

        // Setup period-specific mockk
        setupPeriodSpecificMocks(expenses)
    }

    /**
     * Configures period-specific mockk based on expense dates.
     */
    @Suppress("DEPRECATION_ERROR")
    private fun setupPeriodSpecificMocks(expenses: List<Expense>) {
        // Mock March 2026 queries
        val marchExpenses = expenses.filter { expense ->
            expense.date in march2026Start..march2026End
        }
        val marchPurchases = marchExpenses.filter { it.transactionType == TransactionType.PURCHASE }

        io.mockk.coEvery { expenseDao.getExpensesBetween(eq(march2026Start), eq(march2026End)) } returns marchPurchases
        io.mockk.coEvery { expenseDao.getTotalSpentBetween(eq(march2026Start), eq(march2026End)) } returns
            marchPurchases.filter { !it.isNotMine }.sumOf { it.effectiveAmount }
        io.mockk.coEvery { expenseDao.getTotalSpentBetweenByCurrency(eq(march2026Start), eq(march2026End)) } returns
            listOf(com.yourname.expensetracker.data.database.dao.CurrencyTotal("EUR", marchPurchases.filter { !it.isNotMine }.sumOf { it.effectiveAmount }, marchPurchases.filter { !it.isNotMine }.size))

        // Mock February 2026 queries
        val februaryExpenses = expenses.filter { expense ->
            expense.date in february2026Start..february2026End
        }
        val februaryPurchases = februaryExpenses.filter { it.transactionType == TransactionType.PURCHASE }

        io.mockk.coEvery { expenseDao.getExpensesBetween(eq(february2026Start), eq(february2026End)) } returns februaryPurchases
        io.mockk.coEvery { expenseDao.getTotalSpentBetween(eq(february2026Start), eq(february2026End)) } returns
            februaryPurchases.filter { !it.isNotMine }.sumOf { it.effectiveAmount }
        io.mockk.coEvery { expenseDao.getTotalSpentBetweenByCurrency(eq(february2026Start), eq(february2026End)) } returns
            listOf(com.yourname.expensetracker.data.database.dao.CurrencyTotal("EUR", februaryPurchases.filter { !it.isNotMine }.sumOf { it.effectiveAmount }, februaryPurchases.filter { !it.isNotMine }.size))
    }

    /**
     * Configures the ExpenseDao to return specific expenses for a specific period.
     *
     * @param startMs Period start
     * @param endMs Period end
     * @param expenses Expenses to return for this period
     */
    @Suppress("DEPRECATION_ERROR")
    protected fun mockExpensesForPeriod(startMs: Long, endMs: Long, expenses: List<Expense>) {
        io.mockk.coEvery { expenseDao.getExpensesBetween(eq(startMs), eq(endMs)) } returns expenses
        val periodPurchases = expenses.filter { it.transactionType == TransactionType.PURCHASE && !it.isNotMine }
        val periodTotal = periodPurchases.sumOf { it.effectiveAmount }
        io.mockk.coEvery { expenseDao.getTotalSpentBetween(eq(startMs), eq(endMs)) } returns periodTotal
        io.mockk.coEvery { expenseDao.getTotalSpentBetweenByCurrency(eq(startMs), eq(endMs)) } returns
            if (periodTotal > 0.0) listOf(com.yourname.expensetracker.data.database.dao.CurrencyTotal("EUR", periodTotal, periodPurchases.size))
            else emptyList()

        io.mockk.every { expenseDao.getExpensesBetweenFlow(eq(startMs), eq(endMs)) } returns flowOf(expenses)
    }

    /**
     * Configures the category repository to return specific categories.
     *
     * @param categories Categories to return from repository
     */
    protected fun mockCategories(categories: List<Category>) {
        io.mockk.coEvery { categoryRepository.getAll() } returns categories
        io.mockk.every { categoryRepository.allCategories } returns flowOf(categories)
    }

    /**
     * Gets a test category by ID.
     */
    protected fun getCategory(id: Long): Category? {
        return testCategories.find { it.id == id }
    }

    /**
     * Gets a test category by name.
     */
    protected fun getCategory(name: String): Category? {
        return testCategories.find { it.name.equals(name, ignoreCase = true) }
    }

    /**
     * Creates a coroutine test scope with the test dispatcher.
     */
    protected fun runTest(block: suspend TestScope.() -> Unit) {
        kotlinx.coroutines.test.runTest(testDispatcher) {
            block()
        }
    }
}

/**
 * JUnit Rule to manage the Main dispatcher for coroutine tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    private val testDispatcher: TestDispatcher = StandardTestDispatcher()
) : TestRule {

    override fun apply(base: Statement, description: Description): Statement {
        return object : Statement() {
            override fun evaluate() {
                Dispatchers.setMain(testDispatcher)
                try {
                    base.evaluate()
                } finally {
                    Dispatchers.resetMain()
                }
            }
        }
    }
}
