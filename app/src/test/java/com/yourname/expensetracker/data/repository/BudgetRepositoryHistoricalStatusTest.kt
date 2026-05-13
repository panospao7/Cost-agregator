package com.yourname.expensetracker.data.repository

import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.data.database.dao.BudgetDao
import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.dao.CategoryCurrencyTotal
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class BudgetRepositoryHistoricalStatusTest {

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

    private lateinit var multiCurrencyRepository: MultiCurrencyRepository
    private lateinit var repository: BudgetRepository

    @Suppress("DEPRECATION_ERROR")
    @Before
    fun setup() {
        every { currencySettingsRepository.homeCurrency() } returns flowOf("EUR")
        every { expenseDao.getTotalSpentFlow() } returns flowOf(0.0)
        every { expenseDao.observeExpenseMutationClock() } returns flowOf(0)
        every { budgetDao.getActiveBudgetsFlow() } returns flowOf(emptyList())
        every { categoryDao.getAllFlow() } returns flowOf(emptyList())
        coEvery { budgetDao.getActiveBudgets() } returns emptyList()
        coEvery { categoryDao.getAll() } returns emptyList()
        coEvery { expenseDao.getTotalForPeriod(any(), any()) } returns 0.0
        coEvery { expenseDao.getCategoryTotalsBetweenByCurrency(any(), any()) } returns emptyList()
        coEvery { expenseDao.getTotalSpentBetweenByCurrency(any(), any()) } returns listOf(CurrencyTotal("EUR", 0.0, 0))

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
            budgetDao = budgetDao,
            categoryDao = categoryDao,
            expenseDao = expenseDao,
            budgetCalculator = budgetCalculator,
            timeProvider = timeProvider,
            offsetEngine = offsetEngine,
            timeBoundaryTicker = TimeBoundaryTicker(timeProvider),
            currencyConverter = currencyConverter,
            currencySettingsRepository = currencySettingsRepository,
            multiCurrencyRepository = multiCurrencyRepository,
            writeBarrier = writeBarrier,
            database = database,
            budgetForecastDao = budgetForecastDao,
        )
    }

    @Suppress("DEPRECATION_ERROR")
    @Test
    fun `getBudgetStatusesAt uses explicit evaluation time instead of current time`() = runTest(UnconfinedTestDispatcher()) {
        val budget = budget(amount = 1_000.0, categoryId = null)
        val historicalEvaluation = utcMs(2026, Calendar.FEBRUARY, 15)
        val currentNow = utcMs(2026, Calendar.APRIL, 15)
        val historicalStart = utcMs(2026, Calendar.FEBRUARY, 1)
        val historicalEnd = utcMs(2026, Calendar.MARCH, 1)
        val currentStart = utcMs(2026, Calendar.APRIL, 1)
        val currentEnd = utcMs(2026, Calendar.MAY, 1)

        every { timeProvider.now() } returns currentNow
        coEvery { budgetDao.getActiveBudgets() } returns listOf(budget)
        coEvery { categoryDao.getAll() } returns emptyList()
        every { budgetCalculator.calculatePeriodRange(budget, historicalEvaluation) } returns (historicalStart to historicalEnd)
        every { budgetCalculator.calculatePeriodRange(budget, currentNow) } returns (currentStart to currentEnd)
        coEvery { expenseDao.getTotalForPeriod(historicalStart, historicalEnd) } returns 800.0

        val statuses = repository.getBudgetStatusesAt(historicalEvaluation)

        assertThat(statuses).hasSize(1)
        assertThat(statuses.single().periodStart).isEqualTo(historicalStart)
        assertThat(statuses.single().periodEnd).isEqualTo(historicalEnd)
        assertThat(statuses.single().spentAmount).isEqualTo(800.0)
        assertThat(statuses.single().healthStatus).isEqualTo(BudgetHealthStatus.WARNING)
    }

    @Suppress("DEPRECATION_ERROR")
    @Test
    fun `getBudgetStatusesAt shares same derivation for category budgets`() = runTest(UnconfinedTestDispatcher()) {
        val category = Category(id = 7L, name = "Food", icon = "icon", color = "#FFFFFF", isDefault = false)
        val budget = budget(amount = 400.0, categoryId = category.id)
        val evaluationTime = utcMs(2026, Calendar.JANUARY, 20)
        val start = utcMs(2026, Calendar.JANUARY, 10)
        val end = utcMs(2026, Calendar.FEBRUARY, 10)

        coEvery { budgetDao.getActiveBudgets() } returns listOf(budget)
        coEvery { categoryDao.getAll() } returns listOf(category)
        every { budgetCalculator.calculatePeriodRange(budget, evaluationTime) } returns (start to end)
        coEvery { expenseDao.getCategoryTotalsBetweenByCurrency(any(), any()) } returns listOf(CategoryCurrencyTotal(categoryId = category.id, currency = "EUR", total = 100.0, txCount = 1))

        val statuses = repository.getBudgetStatusesAt(evaluationTime)

        assertThat(statuses).hasSize(1)
        assertThat(statuses.single().category).isEqualTo(category)
        assertThat(statuses.single().remainingAmount).isEqualTo(300.0)
        assertThat(statuses.single().healthStatus).isEqualTo(BudgetHealthStatus.ON_TRACK)
    }

    private fun budget(amount: Double, categoryId: Long?): Budget = Budget(
        id = 1L,
        categoryId = categoryId,
        amount = amount,
        period = BudgetPeriod.MONTHLY,
        startDate = utcMs(2026, Calendar.JANUARY, 1),
        notifyAtWarning = 0.75f,
        notifyAtCritical = 0.9f
    )

    private fun utcMs(year: Int, month: Int, day: Int): Long {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(year, month, day, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}