package com.yourname.expensetracker.data.repository

import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.data.database.dao.BudgetDao
import com.yourname.expensetracker.data.database.dao.CategoryCurrencyTotal
import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.domain.budget.BudgetCalculator
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.MultiConversionAggregate
import com.yourname.expensetracker.domain.groups.SharedExpenseBudgetOffsetEngine
import com.yourname.expensetracker.domain.util.TimeBoundaryTicker
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.BudgetForecastDao
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

@Suppress("DEPRECATION_ERROR")
class BudgetRepositorySuggestionsBatchTest {

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
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        every { expenseDao.observeExpenseMutationClock() } returns flowOf(0)

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

    @Test
    fun `getSuggestions batches category totals in single grouped query`() = runTest {
        val now = utcMs(2026, Calendar.MARCH, 15)
        val categories = listOf(
            Category(id = 1L, name = "Food", icon = "🍔", color = "#FF0000", isDefault = false),
            Category(id = 2L, name = "Transport", icon = "🚌", color = "#00FF00", isDefault = false),
            Category(id = 3L, name = "Games", icon = "🎮", color = "#0000FF", isDefault = false)
        )

        every { timeProvider.now() } returns now
        every { categoryDao.getAllFlow() } returns flowOf(categories)
        coEvery { budgetDao.getActiveBudgets() } returns listOf(activeBudget(categoryId = 2L))
        coEvery { expenseDao.getOldestExpenseDate() } returns utcMs(2025, Calendar.DECEMBER, 1)
        coEvery {
            expenseDao.getCategoryTotalsBetweenByCurrency(
                startDate = any(),
                endDate = any()
            )
        } returns listOf(
            CategoryCurrencyTotal(categoryId = 1L, currency = "EUR", total = 300.0, txCount = 1),
            CategoryCurrencyTotal(categoryId = 3L, currency = "EUR", total = 30.0, txCount = 1)
        )

        val suggestions = repository.getSuggestions()

        assertThat(suggestions).hasSize(1)
        assertThat(suggestions.single().categoryId).isEqualTo(1L)
        assertThat(suggestions.single().categoryName).isEqualTo("Food")

        coVerify(exactly = 1) { expenseDao.getCategoryTotalsBetweenByCurrency(any(), any()) }
        coVerify(exactly = 0) { expenseDao.getCategorySpentTotalsInPeriod(any(), any(), any()) }
        coVerify(exactly = 0) { expenseDao.getCategorySpentInPeriod(any(), any(), any()) }
    }

    @Test
    fun `getSuggestions complete 300 over about one month suggests 348_75`() = runTest {
        val now = utcMs(2026, Calendar.MARCH, 15)
        every { timeProvider.now() } returns now
        every { categoryDao.getAllFlow() } returns flowOf(
            listOf(Category(id = 1L, name = "Food", icon = "F", color = "#FF0000", isDefault = false))
        )
        coEvery { budgetDao.getActiveBudgets() } returns emptyList()
        coEvery { expenseDao.getOldestExpenseDate() } returns utcMs(2026, Calendar.FEBRUARY, 12)
        coEvery { expenseDao.getCategoryTotalsBetweenByCurrency(startDate = any(), endDate = any()) } returns listOf(
            CategoryCurrencyTotal(categoryId = 1L, currency = "EUR", total = 300.0, txCount = 1)
        )

        val suggestions = repository.getSuggestions()

        assertThat(suggestions).hasSize(1)
        // Span: Feb 12 -> end-exclusive Mar 16 = 32 days; March has 31 days.
        // monthly avg = 300 / (32/31) = 290.625; x1.2 buffer = 348.75.
        assertThat(suggestions.single().suggestedAmount).isWithin(0.01).of(348.75)
    }

    @Test
    fun `getSuggestions N-08-6 partial 300 gives no suggestion while complete category still does`() = runTest {
        val now = utcMs(2026, Calendar.MARCH, 15)
        every { timeProvider.now() } returns now
        every { categoryDao.getAllFlow() } returns flowOf(
            listOf(
                Category(id = 1L, name = "Food", icon = "F", color = "#FF0000", isDefault = false),
                Category(id = 2L, name = "Transport", icon = "T", color = "#00FF00", isDefault = false)
            )
        )
        coEvery { budgetDao.getActiveBudgets() } returns emptyList()
        coEvery { expenseDao.getOldestExpenseDate() } returns utcMs(2026, Calendar.FEBRUARY, 12)

        val eur = CurrencyCode("EUR")
        val partialFood = MoneyAggregate.singleCurrency(300.0, eur, 1).copy(isPartial = true)
        val completeTransport = MoneyAggregate.singleCurrency(300.0, eur, 1)
        val stubbedMultiCurrency = mockk<MultiCurrencyRepository>(relaxed = true)
        coEvery { stubbedMultiCurrency.getHomeCurrencyPurchaseCategoryTotals(any(), any()) } returns
            mapOf(1L to partialFood, 2L to completeTransport)
        val repositoryWithStub = BudgetRepository(
            budgetDao = budgetDao,
            categoryDao = categoryDao,
            expenseDao = expenseDao,
            budgetCalculator = budgetCalculator,
            timeProvider = timeProvider,
            offsetEngine = offsetEngine,
            timeBoundaryTicker = TimeBoundaryTicker(timeProvider),
            currencyConverter = currencyConverter,
            currencySettingsRepository = currencySettingsRepository,
            multiCurrencyRepository = stubbedMultiCurrency,
            writeBarrier = writeBarrier,
            database = database,
            budgetForecastDao = budgetForecastDao,
        )

        val suggestions = repositoryWithStub.getSuggestions()

        assertThat(suggestions.map { it.categoryId }).containsExactly(2L)
        assertThat(suggestions.single().suggestedAmount).isGreaterThan(20.0)
    }

    private fun activeBudget(categoryId: Long) = Budget(
        id = 10L,
        categoryId = categoryId,
        amount = 200.0,
        period = BudgetPeriod.MONTHLY,
        startDate = utcMs(2026, Calendar.MARCH, 1),
        isActive = true
    )

    private fun utcMs(year: Int, month: Int, day: Int): Long {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(year, month, day, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}