package com.yourname.expensetracker.domain.forecasting

import com.yourname.expensetracker.data.backup.DatabaseReadBarrier
import com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.analytics.AnalyticsNormalizationResult
import com.yourname.expensetracker.domain.analytics.noPlansInstallmentEligibility
import com.yourname.expensetracker.domain.core.money.ConversionOutcome
import com.yourname.expensetracker.domain.core.money.ConversionPath
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentEntryLinkReader
import com.yourname.expensetracker.domain.installment.model.InstallmentCurrency
import com.yourname.expensetracker.domain.installment.model.InstallmentEntryKind
import com.yourname.expensetracker.domain.installment.model.InstallmentEntryStatus
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanStatus
import com.yourname.expensetracker.domain.installment.projection.InstallmentProjectionPort
import com.yourname.expensetracker.domain.installment.projection.ProjectionEntryInput
import com.yourname.expensetracker.domain.installment.projection.ProjectionPlanInput
import com.yourname.expensetracker.domain.installment.projection.emptyInstallmentProjectionPort
import com.yourname.expensetracker.domain.installment.projection.fakeInstallmentProjectionPort
import com.yourname.expensetracker.domain.recurring.RecurringInstallmentEligibility
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.toExpenseSnapshot
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Calendar

/**
 * BL-09: the withdrawal rate and the purchase Monte Carlo sample share ONE observed window.
 *
 * Spec rule: the window starts at the later of (now - 60 days) and the earliest outflow
 * (PURCHASE or WITHDRAWAL, not isNotMine) that is not installment-linked while installments are
 * projected; deposits never stretch it. The withdrawal rate is
 * `withdrawn total / max(14, observed days) * horizon days`, where observed days count the start day
 * through today inclusive.
 *
 * Both windows are observed through public output only:
 * - the withdrawal window through [StressHorizon.projectedWithdrawals];
 * - the Monte Carlo window through [StressHorizon.lowData] (it is set when there are fewer than 5
 *   baseline purchases or fewer than 14 observed days, measured on the Monte Carlo window).
 *
 * Fixed now = 2026-04-20 12:00. Balance is 5000 so no fixture is near a crunch. Synthetic data only.
 */
class FinancialStressForecastSharedWindowTest {

    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var timeProvider: TimeProvider
    private lateinit var currencyConverter: CurrencyConverter
    private lateinit var patternsProvider: MergedRecurringPatternsProvider
    private lateinit var currencySettingsRepository: CurrencySettingsRepository
    private lateinit var analyticsCurrencyNormalizer: AnalyticsCurrencyNormalizer
    private lateinit var accountBalanceProvider: AccountBalanceProvider

    private val now = millis(2026, Calendar.APRIL, 20)
    private val todayEpochDay: Long =
        Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

    private var allExpenses: List<Expense> = emptyList()
    private var allDeposits: List<Expense> = emptyList()

    @Before
    fun setUp() {
        expenseRepository = mockk()
        timeProvider = mockk()
        patternsProvider = mockk()
        currencySettingsRepository = mockk(relaxed = true)
        analyticsCurrencyNormalizer = mockk(relaxed = true)
        accountBalanceProvider = mockk()
        currencyConverter = mockk(relaxed = true)

        every { timeProvider.now() } returns now
        coEvery { patternsProvider.getConfirmedPatterns() } returns emptyList()
        every { currencySettingsRepository.homeCurrency() } returns flowOf("EUR")
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        every { currencySettingsRepository.emergencyBuffer() } returns flowOf(500.0)
        coEvery { accountBalanceProvider.currentBalance(any()) } returns 5_000.0
        coEvery { expenseRepository.getDepositsBetween(any(), any()) } answers {
            val start = firstArg<Long>()
            val end = secondArg<Long>()
            allDeposits.filter { it.date in start..end }
        }
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } answers {
            val start = firstArg<Long>()
            val end = secondArg<Long>()
            allExpenses.filter { it.date in start..end }
        }
        coEvery { analyticsCurrencyNormalizer.normalizeExpenses(any(), any()) } answers {
            val expenses = firstArg<List<Expense>>()
            AnalyticsNormalizationResult(
                homeCurrency = "EUR",
                normalizedExpenses = emptyList(),
                includedExpenses = expenses.map { it.toExpenseSnapshot() },
                warnings = emptyList(),
                latestRateTimestamp = null,
                totalInputCount = expenses.size
            )
        }
        // Everything is EUR, so conversion is identity; stated explicitly instead of a relaxed mock.
        coEvery {
            currencyConverter.convertOutcome(any(), any(), any(), any(), any(), any())
        } answers {
            ConversionOutcome.Converted(
                originalAmount = firstArg<Double>(),
                originalCurrency = CurrencyCode(secondArg<String>()),
                convertedAmount = firstArg<Double>(),
                targetCurrency = CurrencyCode(thirdArg<String>()),
                rateUsed = 1.0,
                rateBasis = arg<com.yourname.expensetracker.domain.core.money.RateBasis>(3),
                rateValidDate = null,
                rateLastUpdated = null,
                rateSource = null,
                conversionPath = ConversionPath.DIRECT
            )
        }
    }

    // ── fixtures ───────────────────────────────────────────────────────

    private fun engine(
        port: InstallmentProjectionPort = emptyInstallmentProjectionPort(),
        eligibility: RecurringInstallmentEligibility = noPlansInstallmentEligibility()
    ) = FinancialStressForecastEngine(
        synthesisEngine = mockk(relaxed = true),
        monteCarloSimulator = mockk(relaxed = true),
        recurringPatternsProvider = patternsProvider,
        expenseRepository = expenseRepository,
        budgetRepository = mockk<BudgetRepository>(relaxed = true),
        timeProvider = timeProvider,
        analyticsCurrencyNormalizer = analyticsCurrencyNormalizer,
        currencySettingsRepository = currencySettingsRepository,
        multiCurrencyRepository = mockk<MultiCurrencyRepository>(relaxed = true),
        recurringLifecycleCoordinator = mockk<RecurringLifecycleCoordinator>(relaxed = true),
        recurringOccurrenceDao = mockk<RecurringOccurrenceDao>(relaxed = true),
        currencyConverter = currencyConverter,
        accountBalanceProvider = accountBalanceProvider,
        databaseReadBarrier = mockk<DatabaseReadBarrier>(relaxed = true),
        installmentProjectionPort = port,
        recurringInstallmentEligibility = eligibility
    )

    private fun millis(year: Int, month: Int, day: Int, hourOfDay: Int = 12): Long =
        Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hourOfDay)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun daysBack(back: Int): Long = millis(2026, Calendar.APRIL, 20 - back)

    private fun outflow(
        id: Long,
        amount: Double,
        type: TransactionType,
        back: Int,
        isNotMine: Boolean = false
    ) = Expense(
        id = id,
        amount = amount,
        merchant = if (type == TransactionType.WITHDRAWAL) "ATM" else "Shop$id",
        transactionType = type,
        date = daysBack(back),
        categoryId = 1L,
        isNotMine = isNotMine
    )

    /** One 5.0 purchase on each listed day back, ids 1000+. */
    private fun purchases(vararg back: Int): List<Expense> = back.mapIndexed { index, b ->
        outflow(1000L + index, 5.0, TransactionType.PURCHASE, b)
    }

    private fun withdrawal(amount: Double, back: Int, id: Long = 2000L, isNotMine: Boolean = false) =
        outflow(id, amount, TransactionType.WITHDRAWAL, back, isNotMine)

    private fun deposit(back: Int): Expense = outflow(3000L, 1_000.0, TransactionType.DEPOSIT, back)

    private suspend fun forecast(
        expenses: List<Expense>,
        deposits: List<Expense> = emptyList(),
        engine: FinancialStressForecastEngine = engine()
    ): StressForecastResult {
        allExpenses = expenses
        allDeposits = deposits
        val result = engine.computeStressForecast()
        assertNull(result.failure)
        return result
    }

    private fun StressForecastResult.horizon(days: Int) = horizons.first { it.daysAhead == days }

    /** Projected withdrawals per horizon for a given constant daily withdrawal rate. */
    private fun assertWithdrawalRate(result: StressForecastResult, dailyRate: Double) {
        for (days in listOf(30, 60, 90)) {
            assertEquals(
                "projectedWithdrawals ${days}d",
                dailyRate * days,
                result.horizon(days).projectedWithdrawals,
                1e-6
            )
        }
    }

    private fun assertLowData(result: StressForecastResult, expected: Boolean) {
        for (days in listOf(30, 60, 90)) {
            assertEquals("lowData ${days}d", expected, result.horizon(days).lowData)
        }
    }

    // ── withdrawals only ───────────────────────────────────────────────

    @Test
    fun `BL-09 withdrawals only - window starts at the first withdrawal and no purchase history is low data`() = runTest {
        // 210 withdrawn 20 days back: the start day through today is 21 days, so 10 a day.
        val result = forecast(listOf(withdrawal(210.0, back = 20)))

        assertWithdrawalRate(result, dailyRate = 10.0)
        assertLowData(result, expected = true)
    }

    @Test
    fun `BL-09 withdrawals only - a not-mine withdrawal neither counts nor stretches the window`() = runTest {
        val result = forecast(
            listOf(
                withdrawal(500.0, back = 30, id = 2001L, isNotMine = true),
                withdrawal(210.0, back = 20, id = 2002L)
            )
        )

        assertWithdrawalRate(result, dailyRate = 10.0)
    }

    // ── purchases only ─────────────────────────────────────────────────

    @Test
    fun `BL-09 purchases only - no withdrawals project zero and the window starts at the first purchase`() = runTest {
        // First purchase 10 days back: 11 observed days, below the 14 day threshold.
        val thin = forecast(purchases(10, 8, 6, 3, 1))
        assertWithdrawalRate(thin, dailyRate = 0.0)
        assertLowData(thin, expected = true)

        // Control: first purchase 13 days back is exactly 14 observed days, not low data.
        val enough = forecast(purchases(13, 10, 7, 4, 1))
        assertWithdrawalRate(enough, dailyRate = 0.0)
        assertLowData(enough, expected = false)
    }

    @Test
    fun `BL-09 purchases only - a not-mine purchase does not stretch the window`() = runTest {
        val result = forecast(
            purchases(10, 8, 6, 3, 1) + outflow(1900L, 5.0, TransactionType.PURCHASE, back = 30, isNotMine = true)
        )

        assertLowData(result, expected = true)
    }

    // ── a withdrawal earlier than the first purchase extends both windows ──

    @Test
    fun `BL-09 a withdrawal earlier than the first purchase extends both windows`() = runTest {
        val history = purchases(10, 8, 6, 3, 1)

        // Control: no withdrawal. The window starts at the first purchase (11 days): low data.
        val control = forecast(history)
        assertLowData(control, expected = true)
        assertWithdrawalRate(control, dailyRate = 0.0)

        // A withdrawal 20 days back moves the shared start to 21 days for BOTH windows:
        // 210 / 21 = 10 a day for the withdrawals, and 21 observed days (>= 14) for the sample.
        val extended = forecast(history + withdrawal(210.0, back = 20))
        assertWithdrawalRate(extended, dailyRate = 10.0)
        assertLowData(extended, expected = false)
    }

    @Test
    fun `BL-09 a withdrawal later than the first purchase does not shrink the window`() = runTest {
        // First purchase 20 days back, withdrawal 5 days back: the window still starts 20 days back.
        val result = forecast(purchases(20, 15, 10, 5, 1) + withdrawal(210.0, back = 5))

        assertWithdrawalRate(result, dailyRate = 10.0)
        assertLowData(result, expected = false)
    }

    @Test
    fun `BL-09 a withdrawal on the first purchase day shares the same start`() = runTest {
        // Both start 13 days back: 14 observed days, 140 / 14 = 10 a day, sample not low data.
        val result = forecast(purchases(13, 10, 7, 4, 1) + withdrawal(140.0, back = 13))

        assertWithdrawalRate(result, dailyRate = 10.0)
        assertLowData(result, expected = false)
    }

    @Test
    fun `BL-09 below the minimum sample days the withdrawal rate uses 14 days while low data uses the real span`() = runTest {
        // Start 12 days back is 13 observed days: the rate floor is 14 days, the sample is low data.
        val result = forecast(purchases(12, 9, 6, 3, 1) + withdrawal(140.0, back = 12))

        assertWithdrawalRate(result, dailyRate = 10.0)
        assertLowData(result, expected = true)
    }

    // ── a deposit as the first row ─────────────────────────────────────

    @Test
    fun `BL-09 a deposit as the first row does not stretch either window - withdrawals`() = runTest {
        // Deposit 40 days back, withdrawal 20 days back: the window starts at the withdrawal.
        val result = forecast(
            expenses = listOf(deposit(back = 40), withdrawal(210.0, back = 20)),
            deposits = listOf(deposit(back = 40))
        )

        assertWithdrawalRate(result, dailyRate = 10.0)
    }

    @Test
    fun `BL-09 a deposit as the first row does not stretch either window - purchases`() = runTest {
        // A deposit 40 days back must not turn 11 purchase days into 41 observed days.
        val result = forecast(
            expenses = listOf(deposit(back = 40)) + purchases(10, 8, 6, 3, 1),
            deposits = listOf(deposit(back = 40))
        )

        assertWithdrawalRate(result, dailyRate = 0.0)
        assertLowData(result, expected = true)
    }

    @Test
    fun `BL-09 a deposit as the first row with purchases and a withdrawal shares one start`() = runTest {
        val result = forecast(
            expenses = listOf(deposit(back = 40)) + purchases(13, 10, 7, 4, 1) + withdrawal(140.0, back = 13),
            deposits = listOf(deposit(back = 40))
        )

        assertWithdrawalRate(result, dailyRate = 10.0)
        assertLowData(result, expected = false)
    }

    // ── installment-linked purchases ───────────────────────────────────

    private fun entry(id: Long, dueInDays: Long) = ProjectionEntryInput(
        entryId = id,
        seq = id.toInt(),
        kind = InstallmentEntryKind.REGULAR,
        status = InstallmentEntryStatus.SCHEDULED,
        dueEpochDay = todayEpochDay + dueInDays,
        scheduledMinor = 5_000L,
        recordGeneration = 0
    )

    private fun projectedPlanPort(): InstallmentProjectionPort = fakeInstallmentProjectionPort(
        listOf(
            ProjectionPlanInput(
                planId = 1L,
                planUuid = "plan-uuid-1",
                displayName = "Synthetic plan",
                currency = InstallmentCurrency.EUR,
                status = InstallmentPlanStatus.ACTIVE,
                entries = listOf(entry(1L, 5))
            )
        ),
        todayEpochDay
    )

    private fun eligibilityLinking(vararg linkedExpenseIds: Long): RecurringInstallmentEligibility {
        val reader = mockk<InstallmentEntryLinkReader>()
        coEvery { reader.linkedExpenseIds() } returns linkedExpenseIds.toSet()
        coEvery { reader.planPurchaseExpenseIds() } returns emptySet()
        return RecurringInstallmentEligibility(dagger.Lazy { reader })
    }

    private val linkedOldPurchase get() = outflow(101L, 50.0, TransactionType.PURCHASE, back = 40)

    @Test
    fun `BL-09 installment-linked purchases do not stretch either window while installments are projected`() = runTest {
        // Linked 50.00 payment 40 days back; the user's own history starts 10 days back (11 days).
        val expenses = listOf(linkedOldPurchase) + purchases(10, 8, 6, 3, 1) + withdrawal(140.0, back = 10)

        val result = forecast(
            expenses = expenses,
            engine = engine(projectedPlanPort(), eligibilityLinking(101L))
        )

        // 11 observed days floored to 14 for the rate: 140 / 14 = 10 a day. The sample stays low data.
        assertWithdrawalRate(result, dailyRate = 10.0)
        assertLowData(result, expected = true)
    }

    @Test
    fun `BL-09 control without projected installments the old payment is ordinary history and stretches both windows`() = runTest {
        val expenses = listOf(linkedOldPurchase) + purchases(10, 8, 6, 3, 1) + withdrawal(140.0, back = 10)

        val result = forecast(expenses = expenses, engine = engine())

        // Nothing is projected, so nothing leaves the baseline: the window starts 40 days back
        // (41 observed days): 140 / 41 a day, and six purchases over 41 days is not low data.
        assertWithdrawalRate(result, dailyRate = 140.0 / 41.0)
        assertLowData(result, expected = false)
        assertFalse(result.isLowData)
    }

    @Test
    fun `BL-09 a linked purchase earlier than a real withdrawal does not move the withdrawal window`() = runTest {
        // Only the real withdrawal (20 days back, 21 observed days) defines the shared start.
        val expenses = listOf(linkedOldPurchase, withdrawal(210.0, back = 20))

        val result = forecast(
            expenses = expenses,
            engine = engine(projectedPlanPort(), eligibilityLinking(101L))
        )

        assertWithdrawalRate(result, dailyRate = 10.0)
        assertTrue(result.horizon(30).lowData)
    }
}
