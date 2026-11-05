package com.yourname.expensetracker.domain.forecasting

import com.yourname.expensetracker.data.backup.DatabaseReadBarrier
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.data.database.entity.RecurringOccurrence
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.analytics.AnalyticsNormalizationResult
import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.analytics.SpendingPace
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentEntryLinkReader
import com.yourname.expensetracker.domain.logic.SynthesisEngine
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.model.RecurringPattern
import com.yourname.expensetracker.domain.model.dashboard.BudgetStatusSnapshot
import com.yourname.expensetracker.domain.recurring.OccurrenceConflictResolver
import com.yourname.expensetracker.domain.recurring.RecurringInstallmentEligibility
import com.yourname.expensetracker.domain.recurring.RecurringOccurrenceExpander
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringOccurrenceMaterializer
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringRuleLifecycleCoordinator
import com.yourname.expensetracker.domain.transaction.DomainTransactionRunner
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Calendar

/**
 * FC-PAID-UNLINKED (fix-1.0.4) acceptance: a bill already paid by a purchase that is not linked to
 * its occurrence yet must not be committed again by the forecast.
 *
 * Real: [OccurrenceConflictResolver] inside a real [RecurringLifecycleCoordinator] (used for
 * `dropSettled`), the real [ForecastInputAssembler] and the real [SynthesisEngine]. Mocked: the DAOs,
 * clock, normalizer and converter. The coordinator handed to the assembler is a thin mock whose
 * `dropSettled` delegates to the real coordinator and whose `projectOccurrences` returns no rows, so
 * the occurrence set comes from the occurrence DAO (the materialized rows) in every test.
 *
 * Scenario: monthly limit 1,000, spent 700 including rent 400 paid on the 25th, rent due the 27th.
 */
class PaidUnlinkedAcceptanceTest {

    private lateinit var timeProvider: TimeProvider
    private lateinit var expenseDao: ExpenseDao
    private lateinit var occurrenceDao: RecurringOccurrenceDao
    private lateinit var realCoordinator: RecurringLifecycleCoordinator
    private lateinit var assemblerCoordinator: RecurringLifecycleCoordinator
    private lateinit var assembler: ForecastInputAssembler
    private lateinit var engine: SynthesisEngine

    @Before
    fun setup() {
        timeProvider = mockk()
        every { timeProvider.now() } returns ms(2026, Calendar.JUNE, 26, 12)
        expenseDao = mockk(relaxed = true)
        occurrenceDao = mockk(relaxed = true)
        val restoreMaintenanceMode = mockk<RestoreMaintenanceMode>(relaxed = true)
        every { restoreMaintenanceMode.isWritesAllowed() } returns true
        val reader = object : InstallmentEntryLinkReader {
            override suspend fun linkedEntryIdFor(expenseId: Long): Long? = null
            override suspend fun isPlanPurchase(expenseId: Long): Boolean = false
            override suspend fun linkedExpenseIds(): Set<Long> = emptySet()
            override suspend fun planPurchaseExpenseIds(): Set<Long> = emptySet()
        }
        val eligibility = RecurringInstallmentEligibility(dagger.Lazy { reader })
        realCoordinator = RecurringLifecycleCoordinator(
            database = mockk<AppDatabase>(relaxed = true),
            expander = mockk<RecurringOccurrenceExpander>(relaxed = true),
            resolver = OccurrenceConflictResolver(eligibility),
            materializer = mockk<RecurringOccurrenceMaterializer>(relaxed = true),
            occurrenceDao = occurrenceDao,
            expenseDao = expenseDao,
            timeProvider = timeProvider,
            manualRecurringExpenseDao = mockk(relaxed = true),
            reminderDeliveryDao = mockk(relaxed = true),
            lifecycleEventDao = mockk(relaxed = true),
            restoreMaintenanceMode = restoreMaintenanceMode,
            writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true),
            plannedExpenseDao = mockk(relaxed = true),
            transactionRunner = mockk<DomainTransactionRunner>(relaxed = true),
            eventWriter = mockk(relaxed = true),
            installmentEligibility = eligibility,
            ruleCoordinator = dagger.Lazy { mockk<RecurringRuleLifecycleCoordinator>(relaxed = true) }
        )
        assemblerCoordinator = mockk()
        coEvery { assemblerCoordinator.projectOccurrences(any(), any(), any()) } returns emptyList()
        coEvery { assemblerCoordinator.dropSettled(any()) } coAnswers { realCoordinator.dropSettled(firstArg()) }

        val normalizer = mockk<AnalyticsCurrencyNormalizer>()
        coEvery { normalizer.normalizeSnapshots(any(), any()) } returns AnalyticsNormalizationResult(
            homeCurrency = "EUR",
            normalizedExpenses = emptyList(),
            includedExpenses = emptyList(),
            warnings = emptyList(),
            latestRateTimestamp = null,
            totalInputCount = 0
        )
        assembler = ForecastInputAssembler(
            timeProvider,
            analyticsCurrencyNormalizer = normalizer,
            currencySettingsRepository = mockk<CurrencySettingsRepository>(),
            currencyConverter = mockk<CurrencyConverter>(relaxed = true),
            recurringLifecycleCoordinator = assemblerCoordinator,
            recurringOccurrenceDao = occurrenceDao,
            databaseReadBarrier = mockk<DatabaseReadBarrier>(relaxed = true)
        )
        engine = SynthesisEngine(timeProvider, currencyConverter = mockk(relaxed = true))
    }

    // a. Rent paid on the 25th by an unlinked purchase, due the 27th, now the 26th.
    @Test
    fun rentPaidUnlinkedIsNotCommittedAgain() = runTest {
        stubMonthlyRentWithPurchase(purchaseAmount = 400.0)

        val forecast = engine.synthesize(assembleRent().withRentCase())

        assertEquals(0.0, forecast.components.totalCommitted, 0.001)
    }

    // b. Same, but the settled read fails: fail towards counting the bill.
    @Test
    fun settledReadFailureKeepsRentCommitted() = runTest {
        stubMonthlyRentWithPurchase(purchaseAmount = 400.0)
        coEvery { expenseDao.getExpensesBetween(any(), any()) } throws IllegalStateException("synthetic read failure")

        val forecast = engine.synthesize(assembleRent().withRentCase())

        assertEquals(400.0, forecast.components.totalCommitted, 0.001)
    }

    // c. The purchase is 450, more than 10% away from the expected 400: it does not settle the bill.
    @Test
    fun purchaseMoreThanTenPercentOffDoesNotSettleRent() = runTest {
        stubMonthlyRentWithPurchase(purchaseAmount = 450.0)

        val forecast = engine.synthesize(assembleRent().withRentCase())

        assertEquals(400.0, forecast.components.totalCommitted, 0.001)
    }

    // d. Weekly bill of 100 due and paid today (June 8, unlinked). The occurrences of the month are
    // June 8, 15, 22 and 29; today's is settled, so the remaining three are committed: 300.
    // (The plan figure of 100 is not what the code produces: expansion yields 3 x 100 = 300.)
    @Test
    fun weeklyBillPaidTodayCountsOnlyTheRemainingOccurrences() = runTest {
        every { timeProvider.now() } returns ms(2026, Calendar.JUNE, 8, 12)
        val weekly = listOf(8, 15, 22, 29).map {
            occurrence(due = ms(2026, Calendar.JUNE, it, 0), amount = 100.0, merchant = "Gym Club", frequency = "WEEKLY")
        }
        coEvery { occurrenceDao.getByDateRange(any(), any()) } returns weekly
        coEvery { occurrenceDao.getLinkedExpenseIds(any()) } returns emptyList()
        coEvery { expenseDao.getExpensesBetween(any(), any()) } returns listOf(
            purchase(1L, ms(2026, Calendar.JUNE, 8, 9), 100.0, "Gym Club")
        )
        val rule = ManualRecurringExpense(
            id = 1L, merchant = "Gym Club", amount = 100.0,
            frequency = RecurrenceFrequency.WEEKLY, nextDate = ms(2026, Calendar.JUNE, 8, 0), isActive = true
        )

        val forecast = engine.synthesize(assemble(rule).withRentCase())

        assertEquals(300.0, forecast.components.totalCommitted, 0.001)
    }

    // d, control: with no purchase, all four weekly occurrences from today on are committed.
    @Test
    fun weeklyBillNotPaidCountsTodayAndTheRemainingOccurrences() = runTest {
        every { timeProvider.now() } returns ms(2026, Calendar.JUNE, 8, 12)
        val weekly = listOf(8, 15, 22, 29).map {
            occurrence(due = ms(2026, Calendar.JUNE, it, 0), amount = 100.0, merchant = "Gym Club", frequency = "WEEKLY")
        }
        coEvery { occurrenceDao.getByDateRange(any(), any()) } returns weekly
        coEvery { occurrenceDao.getLinkedExpenseIds(any()) } returns emptyList()
        coEvery { expenseDao.getExpensesBetween(any(), any()) } returns emptyList()
        val rule = ManualRecurringExpense(
            id = 1L, merchant = "Gym Club", amount = 100.0,
            frequency = RecurrenceFrequency.WEEKLY, nextDate = ms(2026, Calendar.JUNE, 8, 0), isActive = true
        )

        val forecast = engine.synthesize(assemble(rule).withRentCase())

        assertEquals(400.0, forecast.components.totalCommitted, 0.001)
    }

    // e. Dashboard path (assembleNormalized): the settled rent pattern moves past the month end and
    // the engine commits nothing for it.
    @Test
    fun dashboardSettledRentPatternMovesPastMonthEndAndCommitsZero() = runTest {
        stubDashboardRent(purchaseAmount = 400.0)

        val input = assembler.assembleNormalized(normalizedInput(rentPattern(ruleId = 7L)))

        assertEquals(ms(2026, Calendar.JULY, 27, 0), input.recurringPatterns.single().nextExpectedDate)
        val forecast = engine.synthesize(input.withRentCase())
        assertEquals(0.0, forecast.components.totalCommitted, 0.001)
    }

    // e, boundary: a purchase more than 10% off leaves the pattern on the 27th and the bill committed.
    @Test
    fun dashboardUnsettledRentPatternStaysInMonthAndCommits400() = runTest {
        stubDashboardRent(purchaseAmount = 450.0)

        val input = assembler.assembleNormalized(normalizedInput(rentPattern(ruleId = 7L)))

        assertEquals(ms(2026, Calendar.JUNE, 27, 0), input.recurringPatterns.single().nextExpectedDate)
        val forecast = engine.synthesize(input.withRentCase())
        assertEquals(400.0, forecast.components.totalCommitted, 0.001)
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private fun stubMonthlyRentWithPurchase(purchaseAmount: Double) {
        coEvery { occurrenceDao.getByDateRange(any(), any()) } returns listOf(
            occurrence(due = ms(2026, Calendar.JUNE, 27, 0), amount = 400.0, merchant = "Landlord", frequency = "MONTHLY")
        )
        coEvery { occurrenceDao.getLinkedExpenseIds(any()) } returns emptyList()
        coEvery { expenseDao.getExpensesBetween(any(), any()) } returns listOf(
            purchase(1L, ms(2026, Calendar.JUNE, 25, 12), purchaseAmount, "Landlord"),
            purchase(2L, ms(2026, Calendar.JUNE, 10, 12), 300.0, "Grocer")
        )
    }

    private fun stubDashboardRent(purchaseAmount: Double) {
        coEvery { assemblerCoordinator.projectOccurrences(7L, any(), any()) } returns listOf(
            occurrence(due = ms(2026, Calendar.JUNE, 27, 0), amount = 400.0, merchant = "Rent", frequency = "MONTHLY", ruleId = 7L)
        )
        coEvery { occurrenceDao.getByDateRange(any(), any()) } returns emptyList()
        coEvery { occurrenceDao.getLinkedExpenseIds(any()) } returns emptyList()
        coEvery { expenseDao.getExpensesBetween(any(), any()) } returns listOf(
            purchase(1L, ms(2026, Calendar.JUNE, 25, 12), purchaseAmount, "Rent")
        )
    }

    private suspend fun assembleRent(): ForecastInputAssembler.ForecastInput = assemble(
        ManualRecurringExpense(
            id = 1L, merchant = "Landlord", amount = 400.0,
            frequency = RecurrenceFrequency.MONTHLY, nextDate = ms(2026, Calendar.JUNE, 27, 0), isActive = true
        )
    )

    private suspend fun assemble(rule: ManualRecurringExpense) = assembler.assemble(
        expenses = emptyList(),
        manualRecurringEntities = listOf(rule),
        detectedRecurringPatterns = emptyList(),
        plannedExpenses = emptyList(),
        savingsGoals = emptyList(),
        budgetStatuses = emptyList(),
        homeCurrency = "EUR"
    )

    /** Limit 1,000 and 700 spent (which includes the rent); the engine gets the same figures in every case. */
    private fun ForecastInputAssembler.ForecastInput.withRentCase() = copy(
        budgetStatuses = listOf(budgetStatus(limit = 1000.0, spent = 700.0)),
        spendingPace = SpendingPace(700.0, 26, 30, 800.0, null, null, 0.0f, PaceStatus.ON_PACE, "EUR")
    )

    private fun budgetStatus(limit: Double, spent: Double) = BudgetStatusSnapshot(
        budgetCategoryId = null,
        budgetAmount = limit,
        categoryName = null,
        spentAmount = spent,
        remainingAmount = limit - spent,
        percentUsed = spent / limit * 100.0,
        healthStatus = BudgetHealthStatus.ON_TRACK,
        periodStart = 0,
        periodEnd = 0,
        currency = "EUR",
        currencyAssumption = "USER_CONFIRMED",
        effectiveSpend = spent,
        effectiveHealthStatus = BudgetHealthStatus.ON_TRACK
    )

    private fun normalizedInput(patterns: List<RecurringPattern>) = NormalizedForecastInput(
        homeCurrency = CurrencyCode("EUR"),
        normalizedExpenses = emptyList(),
        pastSumDaily = emptyList(),
        recurringPatterns = patterns,
        plannedExpenses = emptyList(),
        savingsGoals = emptyList(),
        budgetStatuses = emptyList(),
        spendingPace = mockk(relaxed = true),
        dataQuality = ForecastDataQuality()
    )

    private fun rentPattern(ruleId: Long) = RecurringPattern(
        id = ruleId,
        merchantName = "Rent",
        averageAmount = 400.0,
        currency = "EUR",
        frequency = RecurrenceFrequency.MONTHLY,
        periodVarianceDays = 0,
        amountVariancePercent = 0.0,
        nextExpectedDate = ms(2026, Calendar.JUNE, 27, 0),
        confidence = 1.0f,
        previousDates = emptyList()
    )

    private fun occurrence(
        due: Long,
        amount: Double,
        merchant: String,
        frequency: String,
        ruleId: Long = 1L
    ) = RecurringOccurrence(
        sourceType = RecurringLifecycleCoordinator.SOURCE_TYPE_RECURRING_RULE,
        sourceId = ruleId,
        occurrenceKey = "${RecurringLifecycleCoordinator.SOURCE_TYPE_RECURRING_RULE}|$ruleId|$due|$frequency",
        dueDate = due,
        status = "PLANNED",
        expectedAmount = amount,
        expectedCurrency = "EUR",
        frequency = frequency,
        merchant = merchant,
        categoryId = null
    )

    private fun purchase(id: Long, date: Long, amount: Double, merchant: String) = Expense(
        id = id,
        amount = amount,
        currency = "EUR",
        merchant = merchant,
        transactionType = TransactionType.PURCHASE,
        date = date,
        createdAt = date,
        source = "test",
    )

    private fun ms(year: Int, month: Int, day: Int, hour: Int): Long = Calendar.getInstance().apply {
        set(Calendar.YEAR, year)
        set(Calendar.MONTH, month)
        set(Calendar.DAY_OF_MONTH, day)
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
