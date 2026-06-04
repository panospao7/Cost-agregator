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
import com.yourname.expensetracker.domain.core.money.ConversionOutcome
import com.yourname.expensetracker.domain.core.money.ConversionPath
import com.yourname.expensetracker.domain.core.money.RateBasis
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.MultiConversionAggregate
import com.yourname.expensetracker.domain.groups.BudgetSpendBreakdown
import com.yourname.expensetracker.domain.groups.SharedExpenseBudgetOffsetEngine
import com.yourname.expensetracker.domain.util.TimeBoundaryTicker
import com.yourname.expensetracker.domain.util.TimeProvider
import androidx.room.withTransaction
import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseAccessType
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.BudgetForecastDao
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
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
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
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

        // withTransaction inline mock removed — mockk(relaxed=true) handles underlying RoomDatabase methods

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

    @After
    fun tearDown() {
        // withTransaction inline mock removed — general mock cleanup
        unmockkAll()
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
        coEvery { expenseDao.getTotalSpentBetweenByCurrency(historicalStart, historicalEnd) } returns listOf(CurrencyTotal("EUR", 800.0, 1))

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

    @Suppress("DEPRECATION_ERROR")
    @Test
    fun `createBudgetStatus populates adjustedSpendBreakdown from offset engine`() = runTest(UnconfinedTestDispatcher()) {
        val budget = budget(amount = 1_000.0, categoryId = null)
        val evaluationTime = utcMs(2026, Calendar.MARCH, 15)
        val start = utcMs(2026, Calendar.MARCH, 1)
        val end = utcMs(2026, Calendar.APRIL, 1)

        coEvery { budgetDao.getActiveBudgets() } returns listOf(budget)
        coEvery { categoryDao.getAll() } returns emptyList()
        every { budgetCalculator.calculatePeriodRange(budget, evaluationTime) } returns (start to end)
        coEvery { expenseDao.getTotalForPeriod(start, end) } returns 500.0
        coEvery { expenseDao.getTotalSpentBetweenByCurrency(start, end) } returns listOf(CurrencyTotal("EUR", 500.0, 1))
        // P6-CURRENT-002: repository must populate the breakdown so the monitor (not just the UI) sees it.
        coEvery { offsetEngine.calculateEffectiveBudgetSpend(start, end, null) } returns BudgetSpendBreakdown(
            totalPersonalSpend = 300.0,
            totalSharedSpend = 50.0,
            totalReimbursed = 20.0,
            netSharedLiability = 50.0,
            effectiveBudgetSpend = 350.0,
            displayCurrency = "EUR"
        )

        val status = repository.getBudgetStatusesAt(evaluationTime).single()

        assertThat(status.adjustedSpendBreakdown).isNotNull()
        assertThat(status.adjustedSpendBreakdown!!.effectiveSpend).isEqualTo(350.0)
        assertThat(status.adjustedSpendBreakdown!!.netSharedLiability).isEqualTo(50.0)
    }

    @Suppress("DEPRECATION_ERROR")
    @Test
    fun `createBudgetStatus leaves adjustedSpendBreakdown null when offset engine fails`() = runTest(UnconfinedTestDispatcher()) {
        val budget = budget(amount = 1_000.0, categoryId = null)
        val evaluationTime = utcMs(2026, Calendar.MARCH, 15)
        val start = utcMs(2026, Calendar.MARCH, 1)
        val end = utcMs(2026, Calendar.APRIL, 1)

        coEvery { budgetDao.getActiveBudgets() } returns listOf(budget)
        coEvery { categoryDao.getAll() } returns emptyList()
        every { budgetCalculator.calculatePeriodRange(budget, evaluationTime) } returns (start to end)
        coEvery { expenseDao.getTotalForPeriod(start, end) } returns 0.0
        coEvery { offsetEngine.calculateEffectiveBudgetSpend(start, end, null) } throws RuntimeException("offset failure")

        val status = repository.getBudgetStatusesAt(evaluationTime).single()

        // Falls back gracefully; monitor uses gross spend.
        assertThat(status.adjustedSpendBreakdown).isNull()
    }

    @Test
    fun `addBudget rejects NaN amount`() = runTest(UnconfinedTestDispatcher()) {
        val result = repository.addBudget(budget(amount = Double.NaN, categoryId = null))
        assertThat(result).isInstanceOf(com.yourname.expensetracker.domain.model.Result.Error::class.java)
        coVerify(exactly = 0) { budgetDao.insert(any()) }
        coVerify(exactly = 0) { budgetDao.insertAndActivateOverall(any()) }
    }

    @Test
    fun `addBudget rejects infinite amount`() = runTest(UnconfinedTestDispatcher()) {
        val result = repository.addBudget(budget(amount = Double.POSITIVE_INFINITY, categoryId = null))
        assertThat(result).isInstanceOf(com.yourname.expensetracker.domain.model.Result.Error::class.java)
    }

    @Test
    fun `addBudget rejects inverted thresholds`() = runTest(UnconfinedTestDispatcher()) {
        val invalid = budget(amount = 100.0, categoryId = null).copy(
            notifyAtWarning = 0.9f,
            notifyAtCritical = 0.5f
        )
        val result = repository.addBudget(invalid)
        assertThat(result).isInstanceOf(com.yourname.expensetracker.domain.model.Result.Error::class.java)
    }

    @Test
    fun `addBudget rejects warning threshold at or below zero`() = runTest(UnconfinedTestDispatcher()) {
        // notifyAtWarning must be strictly within (0,1); the lower bound is exclusive.
        val invalid = budget(amount = 100.0, categoryId = null).copy(notifyAtWarning = 0.0f)
        val result = repository.addBudget(invalid)
        assertThat(result).isInstanceOf(com.yourname.expensetracker.domain.model.Result.Error::class.java)
    }

    @Test
    fun `addBudget rejects warning threshold at or above one`() = runTest(UnconfinedTestDispatcher()) {
        // notifyAtWarning must be strictly within (0,1); the upper bound is exclusive.
        val invalid = budget(amount = 100.0, categoryId = null).copy(notifyAtWarning = 1.0f)
        val result = repository.addBudget(invalid)
        assertThat(result).isInstanceOf(com.yourname.expensetracker.domain.model.Result.Error::class.java)
    }

    @Test
    fun `addBudget rejects critical threshold equal to warning`() = runTest(UnconfinedTestDispatcher()) {
        // notifyAtCritical must be strictly greater than notifyAtWarning.
        val invalid = budget(amount = 100.0, categoryId = null).copy(
            notifyAtWarning = 0.75f,
            notifyAtCritical = 0.75f
        )
        val result = repository.addBudget(invalid)
        assertThat(result).isInstanceOf(com.yourname.expensetracker.domain.model.Result.Error::class.java)
    }

    @Test
    fun `addBudget rejects critical threshold above one`() = runTest(UnconfinedTestDispatcher()) {
        // notifyAtCritical must be at most 1.0.
        val invalid = budget(amount = 100.0, categoryId = null).copy(
            notifyAtWarning = 0.75f,
            notifyAtCritical = 1.5f
        )
        val result = repository.addBudget(invalid)
        assertThat(result).isInstanceOf(com.yourname.expensetracker.domain.model.Result.Error::class.java)
    }

    @Test
    fun `addBudget accepts critical threshold equal to one`() = runTest(UnconfinedTestDispatcher()) {
        // notifyAtCritical == 1.0 is the inclusive upper bound and must be accepted.
        val valid = budget(amount = 100.0, categoryId = null).copy(
            notifyAtWarning = 0.75f,
            notifyAtCritical = 1.0f
        )
        val result = repository.addBudget(valid)
        assertThat(result).isInstanceOf(com.yourname.expensetracker.domain.model.Result.Success::class.java)
    }

    @Test
    fun `addBudget accepts fully valid budget`() = runTest(UnconfinedTestDispatcher()) {
        // Finite positive amount with warning in (0,1) and warning < critical <= 1.0.
        val result = repository.addBudget(budget(amount = 100.0, categoryId = null))
        assertThat(result).isInstanceOf(com.yourname.expensetracker.domain.model.Result.Success::class.java)
    }

    @Suppress("DEPRECATION_ERROR")
    @Test
    fun `budget actual spend uses period-end as-of rate not latest rate`() = runTest(UnconfinedTestDispatcher()) {
        val budget = budget(amount = 1_000.0, categoryId = null)
        val evaluationTime = utcMs(2026, Calendar.MARCH, 15)
        val start = utcMs(2026, Calendar.MARCH, 1)
        val end = utcMs(2026, Calendar.APRIL, 1)

        every { timeProvider.now() } returns evaluationTime
        coEvery { budgetDao.getActiveBudgets() } returns listOf(budget)
        coEvery { categoryDao.getAll() } returns emptyList()
        every { budgetCalculator.calculatePeriodRange(budget, evaluationTime) } returns (start to end)
        // 1000 USD of purchases in the period (foreign currency → must be converted as-of period end)
        coEvery { expenseDao.getTotalSpentBetweenByCurrency(start, end) } returns listOf(CurrencyTotal("USD", 1_000.0, 1))

        // Period-end rate = 0.80 (USD→EUR). The bounded as-of path MUST use this, not a latest rate.
        coEvery {
            currencyConverter.convertOutcome(
                amount = 1_000.0,
                fromCurrency = "USD",
                toCurrency = "EUR",
                rateBasis = RateBasis.PERIOD_END,
                atMillis = end,
                stalePolicy = any()
            )
        } returns ConversionOutcome.Converted(
            originalAmount = 1_000.0,
            originalCurrency = CurrencyCode("USD"),
            convertedAmount = 800.0,
            targetCurrency = CurrencyCode("EUR"),
            rateUsed = 0.80,
            rateBasis = RateBasis.PERIOD_END,
            rateValidDate = end,
            rateLastUpdated = end,
            rateSource = "test",
            conversionPath = ConversionPath.DIRECT
        )

        val status = repository.getBudgetStatusesAt(evaluationTime).single()

        // 1000 USD → 800 EUR at period-end rate (NOT a latest rate).
        assertThat(status.spentAmount).isEqualTo(800.0)
    }

    // ── P6-P1-06: budget limit uses period-end rate ─────────────────────────

    @Suppress("DEPRECATION_ERROR")
    @Test
    fun `budget limit converted at period-end rate not latest rate`() = runTest(UnconfinedTestDispatcher()) {
        // Budget is in USD (foreign currency); home currency is EUR
        val budget = budget(amount = 1_000.0, categoryId = null).copy(currency = "USD")
        val evaluationTime = utcMs(2026, Calendar.MARCH, 15)
        val start = utcMs(2026, Calendar.MARCH, 1)
        val end = utcMs(2026, Calendar.APRIL, 1)

        every { timeProvider.now() } returns evaluationTime
        coEvery { budgetDao.getActiveBudgets() } returns listOf(budget)
        coEvery { categoryDao.getAll() } returns emptyList()
        every { budgetCalculator.calculatePeriodRange(budget, evaluationTime) } returns (start to end)
        coEvery { expenseDao.getTotalSpentBetweenByCurrency(start, end) } returns listOf(CurrencyTotal("EUR", 0.0, 0))

        // The limit conversion must use convertAsOf (period-end), NOT convert (latest).
        coEvery {
            currencyConverter.convertAsOf(
                amount = 1_000.0,
                fromCurrency = "USD",
                toCurrency = "EUR",
                atMillis = end
            )
        } returns com.yourname.expensetracker.domain.currency.ConversionResult(
            originalAmount = 1_000.0,
            originalCurrency = "USD",
            convertedAmount = 850.0,
            targetCurrency = "EUR",
            rateUsed = 0.85,
            timestamp = end
        )

        val status = repository.getBudgetStatusesAt(evaluationTime).single()

        // Limit converted at period-end rate: 1000 USD → 850 EUR (0.85 rate)
        assertThat(status.effectiveLimit).isEqualTo(850.0)
        // period-end rate (0.85) should be used, NOT latest rate
        coVerify(exactly = 1) {
            currencyConverter.convertAsOf(1_000.0, "USD", "EUR", end)
        }
        coVerify(exactly = 0) {
            currencyConverter.convert(1_000.0, "USD", "EUR")
        }
    }

    @Suppress("DEPRECATION_ERROR")
    @Test
    fun `budget limit falls back to latest rate when historical unavailable`() = runTest(UnconfinedTestDispatcher()) {
        val budget = budget(amount = 500.0, categoryId = null).copy(currency = "GBP")
        val evaluationTime = utcMs(2026, Calendar.MARCH, 15)
        val start = utcMs(2026, Calendar.MARCH, 1)
        val end = utcMs(2026, Calendar.APRIL, 1)

        every { timeProvider.now() } returns evaluationTime
        coEvery { budgetDao.getActiveBudgets() } returns listOf(budget)
        coEvery { categoryDao.getAll() } returns emptyList()
        every { budgetCalculator.calculatePeriodRange(budget, evaluationTime) } returns (start to end)
        coEvery { expenseDao.getTotalSpentBetweenByCurrency(start, end) } returns listOf(CurrencyTotal("EUR", 0.0, 0))

        // Historical rate is unavailable (returns null)
        coEvery {
            currencyConverter.convertAsOf(any<Double>(), any<CurrencyCode>(), any<CurrencyCode>(), any<Long>())
        } returns null

        // Latest rate is available
        coEvery {
            currencyConverter.convert(500.0, "GBP", "EUR")
        } returns com.yourname.expensetracker.domain.currency.ConversionResult(
            originalAmount = 500.0,
            originalCurrency = "GBP",
            convertedAmount = 580.0,
            targetCurrency = "EUR",
            rateUsed = 1.16,
            timestamp = 0L
        )

        val status = repository.getBudgetStatusesAt(evaluationTime).single()

        // Falls back to latest rate
        assertThat(status.effectiveLimit).isEqualTo(580.0)
        // Must be marked partial with a warning since historical rate was unavailable
        assertThat(status.isPartial).isTrue()
        assertThat(status.conversionWarning).contains("latest rate")
    }

    // ── G6: delete/restore forecast policy (P6-CURRENT-005 / P6-P1-15) ─────────────
    //
    // HARNESS NOTE: this test class uses pure mockk DAOs — there is NO real Room DB,
    // so the `budget_forecasts → budgets` FK `onDelete = CASCADE` (schema v142) cannot
    // physically fire here. These tests therefore assert the REPOSITORY-LEVEL CONTRACT
    // of each delete/restore path (which statements run, in which order, under the
    // write-barrier + transaction). The actual DB-level cascade purge is validated by
    // G4's migration test against a real SQLite database.

    @Test
    fun `delete budget purges its forecasts`() = runTest(UnconfinedTestDispatcher()) {
        val target = budget(amount = 100.0, categoryId = null).copy(id = 42L)

        val result = repository.deleteBudget(target)

        assertThat(result).isInstanceOf(com.yourname.expensetracker.domain.model.Result.Success::class.java)
        // Contract: forecasts for the budget are purged, then the budget is deleted,
        // both inside the same Room transaction. The explicit forecast purge is the
        // belt-and-suspenders companion to the CASCADE FK (kept for testable control).
        coVerify(exactly = 1) { database.withTransaction(any()) }
        coVerifyOrder {
            budgetForecastDao.deleteForecastsForBudget(42L)
            budgetDao.delete(target)
        }
    }

    @Test
    fun `delete all budgets with forecasts succeeds`() = runTest(UnconfinedTestDispatcher()) {
        // With budgets+forecasts present, deleteAll() must complete without throwing.
        // Contract: a single DELETE on the parent table — the CASCADE FK purges every
        // forecast in the same step, so no explicit per-budget forecast delete is needed
        // and no orphan forecast rows can survive.
        val result = repository.deleteAll()

        assertThat(result).isInstanceOf(com.yourname.expensetracker.domain.model.Result.Success::class.java)
        coVerify(exactly = 1) { budgetDao.deleteAll() }
        // No standalone forecast-table delete is issued here; cascade does the purge.
        coVerify(exactly = 0) { budgetForecastDao.deleteForecastsForBudget(any()) }
    }

    @Test
    fun `restore debug snapshot with forecasts succeeds and stays write-barrier guarded`() =
        runTest(UnconfinedTestDispatcher()) {
            // restoreDebugSnapshot is BuildConfig.DEBUG-only; unit tests run on the debug
            // variant, but guard defensively so the test is skipped (not failed) elsewhere.
            org.junit.Assume.assumeTrue(com.yourname.expensetracker.BuildConfig.DEBUG)

            val snapshot = BudgetRepository.DebugBudgetSnapshot(
                budgets = listOf(budget(amount = 100.0, categoryId = null).copy(id = 5L))
            )

            // Writes allowed → restore succeeds with forecasts present. replaceAllAndEnforce
            // deletes all budgets first; the CASCADE FK purges all forecasts in the same step.
            val result = repository.restoreDebugSnapshot(snapshot)

            assertThat(result).isInstanceOf(com.yourname.expensetracker.domain.model.Result.Success::class.java)
            coVerify(exactly = 1) { budgetDao.replaceAllAndEnforceActiveScopes(snapshot.budgets) }

            // Guard intact: when writes are disallowed, checkWritesAllowed runs BEFORE the
            // try/catch and throws — the restore must NOT silently proceed or swallow it.
            every { writeBarrier.checkWritesAllowed("BudgetRepository.restoreDebugSnapshot") } throws
                DatabaseAccessBlockedException(
                    DatabaseAccessType.WRITE,
                    DatabaseAccessOperation("BudgetRepository.restoreDebugSnapshot"),
                    RestoreMaintenanceMode.Mode.RESTORE_PREPARING
                )

            var blocked: Throwable? = null
            try {
                repository.restoreDebugSnapshot(snapshot)
            } catch (e: DatabaseAccessBlockedException) {
                blocked = e
            }
            assertThat(blocked).isInstanceOf(DatabaseAccessBlockedException::class.java)
            // The barrier blocked the second attempt before any further DB write — the
            // budget-replace count stays at exactly 1 (from the allowed attempt above).
            coVerify(exactly = 1) { budgetDao.replaceAllAndEnforceActiveScopes(any()) }
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