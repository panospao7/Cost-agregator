package com.yourname.expensetracker.data.repository

import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.assertMoneyExact
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
import com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableException
import com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableReasonCode
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
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { database.withTransaction(any<suspend () -> Any>()) } coAnswers {
            secondArg<suspend () -> Any>().invoke()
        }
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
        unmockkStatic("androidx.room.RoomDatabaseKt")
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
        assertMoneyExact(800.0, statuses.single().spentAmount, "historical spentAmount pass-through")
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
        assertMoneyExact(300.0, statuses.single().remainingAmount, "category remainingAmount = 400 limit - 100 spent")
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
        assertMoneyExact(350.0, status.adjustedSpendBreakdown!!.effectiveSpend, "effectiveSpend pass-through")
        assertMoneyExact(50.0, status.adjustedSpendBreakdown!!.netSharedLiability, "netSharedLiability pass-through")
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

    // AE-16 (INV-16): the typed "home currency unavailable" signal maps to a null breakdown, the single
    // effective-spend-unavailable signal. Percent and health stay repository-owned and unchanged (R3).
    @Suppress("DEPRECATION_ERROR")
    @Test
    fun `AE-16 typed unavailable from offset engine yields null breakdown and unchanged percent and health`() = runTest(UnconfinedTestDispatcher()) {
        val budget = budget(amount = 1_000.0, categoryId = null)
        val evaluationTime = utcMs(2026, Calendar.MARCH, 15)
        val start = utcMs(2026, Calendar.MARCH, 1)
        val end = utcMs(2026, Calendar.APRIL, 1)

        coEvery { budgetDao.getActiveBudgets() } returns listOf(budget)
        coEvery { categoryDao.getAll() } returns emptyList()
        every { budgetCalculator.calculatePeriodRange(budget, evaluationTime) } returns (start to end)
        coEvery { expenseDao.getTotalForPeriod(start, end) } returns 500.0
        coEvery { expenseDao.getTotalSpentBetweenByCurrency(start, end) } returns listOf(CurrencyTotal("EUR", 500.0, 1))
        coEvery { offsetEngine.calculateEffectiveBudgetSpend(start, end, null) } throws
            MoneyDisplayUnavailableException(MoneyDisplayUnavailableReasonCode.HOME_CURRENCY_UNAVAILABLE)

        val status = repository.getBudgetStatusesAt(evaluationTime).single()

        assertThat(status.adjustedSpendBreakdown).isNull()
        // R3: repository-computed gross percent/health are unchanged; the card simply does not show them.
        assertMoneyExact(500.0, status.spentAmount, "gross spentAmount pass-through")
        assertThat(status.percentUsed).isWithin(1e-6f).of(0.5f)
        assertThat(status.healthStatus).isEqualTo(BudgetHealthStatus.ON_TRACK)
        assertThat(status.percentKnown).isTrue()
    }

    @Suppress("DEPRECATION_ERROR")
    @Test
    fun `AE-16 offset engine cancellation propagates instead of becoming a null breakdown`() = runTest(UnconfinedTestDispatcher()) {
        val budget = budget(amount = 1_000.0, categoryId = null)
        val evaluationTime = utcMs(2026, Calendar.MARCH, 15)
        val start = utcMs(2026, Calendar.MARCH, 1)
        val end = utcMs(2026, Calendar.APRIL, 1)

        coEvery { budgetDao.getActiveBudgets() } returns listOf(budget)
        coEvery { categoryDao.getAll() } returns emptyList()
        every { budgetCalculator.calculatePeriodRange(budget, evaluationTime) } returns (start to end)
        coEvery { expenseDao.getTotalForPeriod(start, end) } returns 0.0
        coEvery { offsetEngine.calculateEffectiveBudgetSpend(start, end, null) } throws
            kotlinx.coroutines.CancellationException("cancelled")

        var propagated: kotlinx.coroutines.CancellationException? = null
        try {
            repository.getBudgetStatusesAt(evaluationTime)
        } catch (e: kotlinx.coroutines.CancellationException) {
            propagated = e
        }

        assertThat(propagated).isNotNull()
    }

    private enum class WriteOp { ADD, UPDATE }

    private data class RejectRow(val name: String, val op: WriteOp, val budget: Budget)

    private data class AcceptRow(
        val name: String,
        val budget: Budget,
        val categoryScoped: Boolean
    )

    /** Fresh repository bound to a per-row [dao] so coVerify counts never leak between table rows. */
    private fun repositoryWith(dao: BudgetDao): BudgetRepository = BudgetRepository(
        budgetDao = dao,
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

    // Boundary validation (P6-CURRENT-029): every invalid add/update is rejected with
    // Result.Error and NO insert/update DAO statement is issued.
    @Test
    fun `budget write validation rejects invalid amount start date and thresholds without any DAO write`() =
        runTest(UnconfinedTestDispatcher()) {
            val base = budget(amount = 100.0, categoryId = null)
            val rows = listOf(
                RejectRow("add NaN amount", WriteOp.ADD, budget(amount = Double.NaN, categoryId = null)),
                RejectRow("add infinite amount", WriteOp.ADD, budget(amount = Double.POSITIVE_INFINITY, categoryId = null)),
                RejectRow("add zero amount", WriteOp.ADD, budget(amount = 0.0, categoryId = null)),
                RejectRow("add negative amount -100", WriteOp.ADD, budget(amount = -100.0, categoryId = null)),
                RejectRow("add startDate 0", WriteOp.ADD, base.copy(startDate = 0L)),
                RejectRow("add startDate -1000", WriteOp.ADD, base.copy(startDate = -1000L)),
                // notifyAtCritical must be strictly greater than notifyAtWarning.
                RejectRow("add inverted thresholds 0.9/0.5", WriteOp.ADD, base.copy(notifyAtWarning = 0.9f, notifyAtCritical = 0.5f)),
                // notifyAtWarning must be strictly within (0,1): lower bound exclusive.
                RejectRow("add warning 0.0", WriteOp.ADD, base.copy(notifyAtWarning = 0.0f)),
                // ... upper bound exclusive.
                RejectRow("add warning 1.0", WriteOp.ADD, base.copy(notifyAtWarning = 1.0f)),
                RejectRow("add critical equals warning 0.75", WriteOp.ADD, base.copy(notifyAtWarning = 0.75f, notifyAtCritical = 0.75f)),
                // notifyAtCritical must be at most 1.0.
                RejectRow("add critical 1.5", WriteOp.ADD, base.copy(notifyAtWarning = 0.75f, notifyAtCritical = 1.5f)),
                RejectRow("update zero amount", WriteOp.UPDATE, budget(amount = 0.0, categoryId = null)),
                RejectRow("update negative amount -50", WriteOp.UPDATE, budget(amount = -50.0, categoryId = null)),
            )
            org.junit.Assert.assertEquals("reject table size", 13, rows.size)

            val failures = mutableListOf<String>()
            for (row in rows) {
                try {
                    val dao = mockk<BudgetDao>(relaxed = true)
                    val repo = repositoryWith(dao)
                    val result = when (row.op) {
                        WriteOp.ADD -> repo.addBudget(row.budget)
                        WriteOp.UPDATE -> repo.updateBudget(row.budget)
                    }
                    assertThat(result)
                        .isInstanceOf(com.yourname.expensetracker.domain.model.Result.Error::class.java)
                    when (row.op) {
                        WriteOp.ADD -> {
                            coVerify(exactly = 0) { dao.insert(any()) }
                            coVerify(exactly = 0) { dao.insertAndActivateOverall(any()) }
                            coVerify(exactly = 0) { dao.insertAndActivateCategory(any()) }
                        }
                        WriteOp.UPDATE -> {
                            coVerify(exactly = 0) { dao.update(any()) }
                            coVerify(exactly = 0) { dao.updateAndEnforceActiveScope(any()) }
                        }
                    }
                } catch (e: AssertionError) {
                    failures += "[${row.name}] ${e.message}"
                }
            }
            if (failures.isNotEmpty()) {
                org.junit.Assert.fail("reject rows failed:\n" + failures.joinToString("\n"))
            }
        }

    // Inclusive boundaries and extreme-but-valid amounts are accepted and routed to the
    // matching insert path (overall vs category-scoped), returning the DAO-assigned id.
    @Test
    fun `budget add validation accepts inclusive boundaries and extreme amounts`() =
        runTest(UnconfinedTestDispatcher()) {
            val base = budget(amount = 100.0, categoryId = null)
            val rows = listOf(
                // notifyAtCritical == 1.0 is the inclusive upper bound.
                AcceptRow("critical threshold equal to one", base.copy(notifyAtWarning = 0.75f, notifyAtCritical = 1.0f), false),
                // Finite positive amount with warning in (0,1) and warning < critical <= 1.0.
                AcceptRow("fully valid overall budget", base, false),
                AcceptRow(
                    "very large yearly amount 1e9",
                    base.copy(amount = 1_000_000_000.0, period = BudgetPeriod.YEARLY, notifyAtWarning = 0.8f, notifyAtCritical = 0.95f),
                    false
                ),
                AcceptRow("very small amount 0.01", base.copy(amount = 0.01, notifyAtWarning = 0.8f, notifyAtCritical = 0.95f), false),
                AcceptRow(
                    "valid category-scoped budget",
                    base.copy(amount = 500.0, categoryId = 1L, notifyAtWarning = 0.8f, notifyAtCritical = 0.95f),
                    true
                ),
            )
            org.junit.Assert.assertEquals("accept table size", 5, rows.size)

            val failures = mutableListOf<String>()
            for (row in rows) {
                try {
                    val dao = mockk<BudgetDao>(relaxed = true)
                    coEvery { dao.insertAndActivateOverall(any()) } returns 11L
                    coEvery { dao.insertAndActivateCategory(any()) } returns 12L
                    val repo = repositoryWith(dao)

                    val result = repo.addBudget(row.budget)

                    val expectedId = if (row.categoryScoped) 12L else 11L
                    assertThat(result)
                        .isEqualTo(com.yourname.expensetracker.domain.model.Result.Success(expectedId))
                    coVerify(exactly = if (row.categoryScoped) 0 else 1) { dao.insertAndActivateOverall(any()) }
                    coVerify(exactly = if (row.categoryScoped) 1 else 0) { dao.insertAndActivateCategory(any()) }
                    coVerify(exactly = 0) { dao.insert(any()) }
                } catch (e: AssertionError) {
                    failures += "[${row.name}] ${e.message}"
                }
            }
            if (failures.isNotEmpty()) {
                org.junit.Assert.fail("accept rows failed:\n" + failures.joinToString("\n"))
            }
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
        assertMoneyExact(800.0, status.spentAmount, "spent converted at period-end rate")
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
        assertMoneyExact(850.0, status.effectiveLimit, "limit converted at period-end rate")
        // period-end rate (0.85) should be used, NOT latest rate
        coVerify(exactly = 1) {
            currencyConverter.convertAsOf(1_000.0, "USD", "EUR", end)
        }
        coVerify(exactly = 0) {
            currencyConverter.convert(1_000.0, "USD", "EUR")
        }
    }

    // ── RP-09 (P6-005 / P6-006): percentKnown + conversion-quality contract ────────

    @Suppress("DEPRECATION_ERROR")
    @Test
    fun `total limit conversion failure marks percent unknown with unknown health`() = runTest(UnconfinedTestDispatcher()) {
        val budget = budget(amount = 1_000.0, categoryId = null).copy(currency = "USD")
        val evaluationTime = utcMs(2026, Calendar.MARCH, 15)
        val start = utcMs(2026, Calendar.MARCH, 1)
        val end = utcMs(2026, Calendar.APRIL, 1)

        every { timeProvider.now() } returns evaluationTime
        coEvery { budgetDao.getActiveBudgets() } returns listOf(budget)
        coEvery { categoryDao.getAll() } returns emptyList()
        every { budgetCalculator.calculatePeriodRange(budget, evaluationTime) } returns (start to end)
        coEvery { expenseDao.getTotalSpentBetweenByCurrency(start, end) } returns listOf(CurrencyTotal("EUR", 200.0, 1))
        // No historical rate AND no latest rate — total conversion failure (UNAVAILABLE).
        coEvery {
            currencyConverter.convertAsOf(any<Double>(), any<String>(), any<String>(), any<Long>())
        } returns null
        coEvery {
            currencyConverter.convert(any<Double>(), any<String>(), any<String>())
        } returns null

        val status = repository.getBudgetStatusesAt(evaluationTime).single()

        // percentKnown=false contract: placeholder 0f, UNKNOWN health, no usable limit.
        assertThat(status.percentKnown).isFalse()
        assertThat(status.percentUsed).isEqualTo(0f)
        assertThat(status.healthStatus).isEqualTo(BudgetHealthStatus.UNKNOWN)
        assertMoneyExact(0.0, status.effectiveLimit, "unusable limit is zero, not a fabricated amount")
        assertMoneyExact(0.0, status.remainingAmount, "remaining is zero when limit unavailable")
        assertThat(status.isPartial).isTrue()
        assertThat(status.conversionWarning).isNotEmpty()
    }

    @Suppress("DEPRECATION_ERROR")
    @Test
    fun `usable latest-rate fallback keeps percent known and thresholds active`() = runTest(UnconfinedTestDispatcher()) {
        val budget = budget(amount = 500.0, categoryId = null).copy(currency = "GBP")
        val evaluationTime = utcMs(2026, Calendar.MARCH, 15)
        val start = utcMs(2026, Calendar.MARCH, 1)
        val end = utcMs(2026, Calendar.APRIL, 1)

        every { timeProvider.now() } returns evaluationTime
        coEvery { budgetDao.getActiveBudgets() } returns listOf(budget)
        coEvery { categoryDao.getAll() } returns emptyList()
        every { budgetCalculator.calculatePeriodRange(budget, evaluationTime) } returns (start to end)
        coEvery { expenseDao.getTotalSpentBetweenByCurrency(start, end) } returns listOf(CurrencyTotal("EUR", 100.0, 1))
        coEvery {
            currencyConverter.convertAsOf(any<Double>(), any<String>(), any<String>(), any<Long>())
        } returns null
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

        // Usable fallback: the limit IS home currency, so percent is known and
        // threshold checks stay active, while isPartial + warning flag the basis.
        assertThat(status.percentKnown).isTrue()
        assertThat(status.isPartial).isTrue()
        // Falls back to the latest rate (historical unavailable): limit passes through as 580 EUR
        // and the warning names the latest-rate basis.
        assertMoneyExact(580.0, status.effectiveLimit, "limit converted at latest rate")
        assertThat(status.conversionWarning).contains("latest rate")
        assertThat(status.percentUsed).isWithin(1e-6f).of((100.0 / 580.0).toFloat())
        assertThat(status.healthStatus).isEqualTo(BudgetHealthStatus.ON_TRACK)
    }

    @Suppress("DEPRECATION_ERROR")
    @Test
    fun `current period spend repository method pins period-end rate basis`() = runTest(UnconfinedTestDispatcher()) {
        // RP-09 (P6-007): the repository-owned forecast spend method must bound the
        // spend window by [periodStart, elapsedEnd) while resolving rates as of
        // periodEnd, and must report that as-of instant back to the caller.
        val start = utcMs(2026, Calendar.MARCH, 1)
        val end = utcMs(2026, Calendar.APRIL, 1)
        val elapsedEnd = utcMs(2026, Calendar.MARCH, 15)

        coEvery { expenseDao.getTotalSpentBetweenByCurrency(start, elapsedEnd) } returns listOf(CurrencyTotal("EUR", 400.0, 4))

        val result = repository.getCurrentPeriodPurchaseSpendAtPeriodEnd(
            categoryId = null,
            periodStart = start,
            periodEnd = end,
            elapsedEnd = elapsedEnd
        )

        assertThat(result.rateAsOfMillis).isEqualTo(end)
        assertMoneyExact(400.0, result.aggregate.displayAmount, "elapsed-window spend pass-through")
        assertThat(result.aggregate.rateBasis).isEqualTo(RateBasis.PERIOD_END)
        // The spend WINDOW is the elapsed window, NOT the full period.
        coVerify(exactly = 1) { expenseDao.getTotalSpentBetweenByCurrency(start, elapsedEnd) }
        coVerify(exactly = 0) { expenseDao.getTotalSpentBetweenByCurrency(start, end) }
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
