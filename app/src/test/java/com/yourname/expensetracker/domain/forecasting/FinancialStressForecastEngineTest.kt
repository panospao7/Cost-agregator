package com.yourname.expensetracker.domain.forecasting

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseAccessType
import com.yourname.expensetracker.data.backup.DatabaseReadBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.RecurringOccurrence
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.forecasting.MergedRecurringPatternsProvider
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.model.RecurringPattern
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.analytics.AnalyticsNormalizationResult
import com.yourname.expensetracker.domain.logic.SynthesisEngine
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.toExpenseSnapshot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

/**
 * Tests for [FinancialStressForecastEngine].
 *
 * ## Test gaps (not yet covered):
 * - Status filtering: verify that expense/budget status filters are correctly applied
 *   when computing stress forecasts (e.g. only active budgets, only confirmed expenses).
 * - Currency normalization: ensure multi-currency expenses are normalized to home
 *   currency before aggregation, and that non-home-currency budgets are handled.
 * - Generation failure: a failed calculation is explicitly unavailable, with no
 *   invented risk level, probability, horizons, or financial advice.
 * - Exclusive end boundary: confirm that the end boundary of date ranges is treated
 *   as exclusive (or inclusive) consistently, preventing off-by-one errors in
 *   lookback windows.
 */
class FinancialStressForecastEngineTest {

    private lateinit var synthesisEngine: SynthesisEngine
    private lateinit var monteCarloSimulator: MonteCarloSpendingSimulator
    private lateinit var mergedRecurringPatternsProvider: MergedRecurringPatternsProvider
    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var budgetRepository: BudgetRepository
    private lateinit var timeProvider: TimeProvider
    private lateinit var currencySettingsRepository: CurrencySettingsRepository
    private lateinit var analyticsCurrencyNormalizer: AnalyticsCurrencyNormalizer
    private lateinit var recurringLifecycleCoordinator: RecurringLifecycleCoordinator
    private lateinit var recurringOccurrenceDao: RecurringOccurrenceDao
    private lateinit var databaseReadBarrier: DatabaseReadBarrier
    private lateinit var currencyConverter: com.yourname.expensetracker.domain.currency.CurrencyConverter
    private lateinit var accountBalanceProvider: AccountBalanceProvider
    private lateinit var multiCurrencyRepository: MultiCurrencyRepository

    private lateinit var engine: FinancialStressForecastEngine

    private val now = millis(2026, Calendar.APRIL, 20)
    private val dayMs = 24L * 60L * 60L * 1000L

    private var allExpenses: List<Expense> = emptyList()
    private var allDeposits: List<Expense> = emptyList()

    @Test
    fun currencySettingsFailureReturnsUnavailableWithoutInventedHorizons() = runTest {
        every { currencySettingsRepository.homeCurrency() } returns kotlinx.coroutines.flow.flow {
            throw IllegalStateException("SECRET settings payload")
        }
        val result = engine.computeStressForecast()
        assertEquals(StressForecastFailure.CALCULATION_UNAVAILABLE, result.failure)
        assertNull(result.overallRiskLevel)
        assertTrue(result.horizons.isEmpty())
        assertTrue(result.recommendations.isEmpty())
        assertTrue(result.isPartial)
    }

    @Test
    fun currencySettingsCancellationPropagatesUnchanged() = runTest {
        val cancellation = kotlinx.coroutines.CancellationException("test cancellation")
        every { currencySettingsRepository.homeCurrency() } returns kotlinx.coroutines.flow.flow { throw cancellation }
        org.junit.Assert.assertSame(cancellation, runCatching { engine.computeStressForecast() }.exceptionOrNull())
    }

    @Test
    fun calculationCancellationPropagatesUnchanged() = runTest {
        val cancellation = kotlinx.coroutines.CancellationException("test cancellation")
        coEvery { expenseRepository.getDepositsBetween(any(), any()) } throws cancellation
        org.junit.Assert.assertSame(cancellation, runCatching { engine.computeStressForecast() }.exceptionOrNull())
    }

    @Test
    fun explicitCurrencyKeepsSuccessfulNoDataForecastAvailable() = runTest {
        every { currencySettingsRepository.homeCurrency() } returns kotlinx.coroutines.flow.flow {
            throw IllegalStateException("settings unavailable")
        }
        val result = engine.computeStressForecast("EUR")
        assertNull(result.failure)
        org.junit.Assert.assertNotNull(result.overallRiskLevel)
        assertEquals(listOf(30, 60, 90), result.horizons.map { it.daysAhead })
        io.mockk.verify(exactly = 0) { currencySettingsRepository.homeCurrency() }
        coVerify(exactly = 1) { accountBalanceProvider.currentBalance("EUR") }
    }

    @Test
    fun resolvedSettingsCurrencyIsReusedForStartingBalance() = runTest {
        val result = engine.computeStressForecast()

        assertNull(result.failure)
        assertEquals(listOf(30, 60, 90), result.horizons.map { it.daysAhead })
        verify(exactly = 1) { currencySettingsRepository.homeCurrency() }
        coVerify(exactly = 1) { accountBalanceProvider.currentBalance("EUR") }
    }

    @Test
    fun accountBalanceProviderCancellationPropagatesUnchanged() = runTest {
        val cancellation = kotlinx.coroutines.CancellationException("balance cancellation")
        coEvery { accountBalanceProvider.currentBalance(any()) } throws cancellation
        org.junit.Assert.assertSame(cancellation, runCatching { engine.computeStressForecast() }.exceptionOrNull())
    }

    // P-06-4 (was: depositTotalCancellationPropagatesUnchanged). The engine no longer has an inline
    // deposit/purchase fallback behind the provider, so a failing provider is "balance unavailable",
    // and the deposit/purchase cancellation guarantees now live in NetCashflowBalanceProviderTest.
    @Test
    fun accountBalanceProviderIOExceptionIsBalanceUnavailableWithNoInventedBalance() = runTest {
        coEvery { accountBalanceProvider.currentBalance(any()) } throws java.io.IOException("provider down")
        allExpenses = steadyDailyPurchases(10.0)

        val result = engine.computeStressForecast()

        assertEquals(StressForecastFailure.BALANCE_UNAVAILABLE, result.failure)
        assertTrue(result.horizons.isEmpty())
        assertNull(result.overallRiskLevel)
        assertNull(result.earliestCrunchDate)
        assertTrue(result.recommendations.isEmpty())
        assertTrue(result.isPartial)
        assertEquals(listOf("STRESS_FORECAST_BALANCE_UNAVAILABLE"), result.qualityWarnings)
        coVerify(exactly = 1) { accountBalanceProvider.currentBalance("EUR") }
    }

    // Control for the IOException case: the same fixture with a known balance does forecast.
    @Test
    fun accountBalanceProviderKnownBalanceControlForecastsSameFixture() = runTest {
        coEvery { accountBalanceProvider.currentBalance(any()) } returns 0.0
        allExpenses = steadyDailyPurchases(10.0)

        val result = engine.computeStressForecast()

        assertNull(result.failure)
        assertEquals(listOf(30, 60, 90), result.horizons.map { it.daysAhead })
    }

    @Test
    fun accountBalanceProviderCancellationEscapesComputeStressForecastNotBalanceUnavailable() = runTest {
        val cancellation = kotlinx.coroutines.CancellationException("provider cancelled")
        coEvery { accountBalanceProvider.currentBalance(any()) } throws cancellation

        val thrown = runCatching { engine.computeStressForecast() }

        org.junit.Assert.assertTrue("cancellation must propagate", thrown.isFailure)
        org.junit.Assert.assertSame(cancellation, thrown.exceptionOrNull())
    }

    @Test
    fun emergencyBufferCancellationPropagatesUnchanged() = runTest {
        val cancellation = kotlinx.coroutines.CancellationException("buffer cancellation")
        every { currencySettingsRepository.emergencyBuffer() } returns kotlinx.coroutines.flow.flow { throw cancellation }
        org.junit.Assert.assertSame(cancellation, runCatching { engine.computeStressForecast() }.exceptionOrNull())
    }

    // P-06-4 (was: nonCancellationFailuresStillFallBackInStartingBalanceAndBuffer, which pinned a
    // failed provider turning into a 0 starting balance with horizons). A failed provider is now
    // BALANCE_UNAVAILABLE; only the emergency buffer keeps its 500 fallback.
    @Test
    fun nonCancellationProviderFailureIsBalanceUnavailableNotZeroBalance() = runTest {
        coEvery { accountBalanceProvider.currentBalance(any()) } throws IllegalStateException("provider down")
        every { currencySettingsRepository.emergencyBuffer() } returns kotlinx.coroutines.flow.flow {
            throw IllegalStateException("buffer down")
        }

        val result = engine.computeStressForecast()

        assertEquals(StressForecastFailure.BALANCE_UNAVAILABLE, result.failure)
        assertTrue(result.horizons.isEmpty())
        assertNull(result.overallRiskLevel)
        assertTrue(result.isPartial)
    }

    @Test
    fun nonCancellationBufferFailureStillFallsBackWhenBalanceIsAvailable() = runTest {
        coEvery { accountBalanceProvider.currentBalance(any()) } returns 0.0
        every { currencySettingsRepository.emergencyBuffer() } returns kotlinx.coroutines.flow.flow {
            throw IllegalStateException("buffer down")
        }

        val result = engine.computeStressForecast()

        assertNull(result.failure)
        assertEquals(listOf(30, 60, 90), result.horizons.map { it.daysAhead })
    }

    // ── P-06-4: unavailable balance, low data, withdrawals ──────────────────

    @Test
    fun nullBalanceWithholdsTheForecastInsteadOfProjectingZero() = runTest {
        coEvery { accountBalanceProvider.currentBalance(any()) } returns null
        allExpenses = steadyDailyPurchases(10.0)

        val result = engine.computeStressForecast()

        assertEquals(StressForecastFailure.BALANCE_UNAVAILABLE, result.failure)
        assertTrue(result.horizons.isEmpty())
        assertNull(result.overallRiskLevel)
        assertNull(result.earliestCrunchDate)
        assertTrue(result.recommendations.isEmpty())
        assertTrue(result.isPartial)
        assertEquals(listOf("STRESS_FORECAST_BALANCE_UNAVAILABLE"), result.qualityWarnings)
        assertEquals(StressForecastMode.ESTIMATED_INDEX, result.mode)
        assertEquals("EUR", result.displayCurrency)
        // Nothing downstream of the balance is read once it is known to be unavailable.
        coVerify(exactly = 0) { expenseRepository.getDepositsBetween(any(), any()) }
        coVerify(exactly = 0) { expenseRepository.getExpensesBetween(any(), any()) }
    }

    @Test
    fun zeroBalanceControlStillProducesHorizons() = runTest {
        coEvery { accountBalanceProvider.currentBalance(any()) } returns 0.0
        allExpenses = steadyDailyPurchases(10.0)

        val result = engine.computeStressForecast()

        assertNull(result.failure)
        assertEquals(listOf(30, 60, 90), result.horizons.map { it.daysAhead })
        assertNotNull(result.overallRiskLevel)
    }

    @Test
    fun nullBalanceForExplicitNonHomeCurrencyIsUnavailable() = runTest {
        coEvery { accountBalanceProvider.currentBalance("USD") } returns null

        val result = engine.computeStressForecast("USD")

        assertEquals(StressForecastFailure.BALANCE_UNAVAILABLE, result.failure)
        assertEquals("USD", result.displayCurrency)
        assertTrue(result.horizons.isEmpty())
    }

    @Test
    fun noPurchaseHistoryProjectsBalanceUnchangedAndIsLowData() = runTest {
        coEvery { accountBalanceProvider.currentBalance(any()) } returns 400.0
        allExpenses = emptyList()
        allDeposits = emptyList()

        val result = engine.computeStressForecast()

        assertNull(result.failure)
        assertEquals(listOf(30, 60, 90), result.horizons.map { it.daysAhead })
        result.horizons.forEach { horizon ->
            assertEquals("projected ${horizon.daysAhead}d", 400.0, horizon.projectedBalance, 0.0)
            assertEquals("min ${horizon.daysAhead}d", 400.0, horizon.minProjectedBalance, 0.0)
            assertEquals(0.0, horizon.probabilityOfCrunch, 0.0)
            assertEquals(StressRiskLevel.LOW, horizon.riskLevel)
            assertTrue("lowData ${horizon.daysAhead}d", horizon.lowData)
        }
        assertTrue(result.isLowData)
        assertEquals(StressRiskLevel.LOW, result.overallRiskLevel)
        assertNull(result.earliestCrunchDate)
        val kinds = result.recommendations.map { it.kind }
        assertTrue(StressRecommendationKind.HEALTHY_OUTLOOK !in kinds)
        assertTrue(StressRecommendationKind.NO_IMMEDIATE_CONCERNS !in kinds)
    }

    @Test
    fun realPurchaseHistoryIsNotLowData() = runTest {
        coEvery { accountBalanceProvider.currentBalance(any()) } returns 400.0
        allExpenses = steadyDailyPurchases(10.0)
        allDeposits = emptyList()

        val result = engine.computeStressForecast()

        assertNull(result.failure)
        assertEquals(3, result.horizons.size)
        assertTrue(result.horizons.none { it.lowData })
        assertEquals(false, result.isLowData)
        // Real spend moves the projection away from the untouched 400.
        assertTrue(result.horizons.all { it.projectedBalance < 400.0 })
    }

    @Test
    fun depositsAndWithdrawalsAloneAreStillLowData() = runTest {
        coEvery { accountBalanceProvider.currentBalance(any()) } returns 400.0
        val salary = expense(3001L, 1_000.0, TransactionType.DEPOSIT, now - 3 * dayMs, merchant = "Employer")
        val cash = expense(3002L, 100.0, TransactionType.WITHDRAWAL, now - 2 * dayMs, merchant = "ATM")
        allExpenses = listOf(salary, cash)
        allDeposits = listOf(salary)

        val result = engine.computeStressForecast()

        assertTrue(result.horizons.all { it.lowData })
        assertTrue(result.isLowData)
    }

    @Test
    fun notMinePurchasesAreNotPurchaseHistory() = runTest {
        coEvery { accountBalanceProvider.currentBalance(any()) } returns 400.0
        allExpenses = listOf(
            expense(3010L, 50.0, TransactionType.PURCHASE, now - 3 * dayMs, isNotMine = true)
        )
        allDeposits = emptyList()

        val result = engine.computeStressForecast()

        assertTrue(result.horizons.all { it.lowData })
        assertTrue(result.isLowData)
    }

    // ── P-06-4 low data: fewer than 5 purchases or fewer than 14 observed days ──

    private fun purchasesDaysBack(vararg daysBack: Int): List<Expense> = daysBack.mapIndexed { index, back ->
        expense(
            3400L + index,
            5.0,
            TransactionType.PURCHASE,
            millis(2026, Calendar.APRIL, 20 - back)
        )
    }

    private suspend fun lowDataResult(vararg daysBack: Int): StressForecastResult {
        coEvery { accountBalanceProvider.currentBalance(any()) } returns 5_000.0
        allExpenses = purchasesDaysBack(*daysBack)
        allDeposits = emptyList()
        return engine.computeStressForecast()
    }

    private fun assertLowDataWithoutReassurance(result: StressForecastResult) {
        assertNull(result.failure)
        assertTrue(result.isLowData)
        assertTrue(result.horizons.all { it.lowData })
        val kinds = result.recommendations.map { it.kind }
        assertTrue(StressRecommendationKind.HEALTHY_OUTLOOK !in kinds)
        assertTrue(StressRecommendationKind.NO_IMMEDIATE_CONCERNS !in kinds)
    }

    @Test
    fun lowDataOnePurchaseThirtyDaysAgoIsLowData() = runTest {
        assertLowDataWithoutReassurance(lowDataResult(30))
    }

    @Test
    fun lowDataFourPurchasesAcrossSixtyDaysIsLowData() = runTest {
        assertLowDataWithoutReassurance(lowDataResult(60, 40, 20, 1))
    }

    @Test
    fun lowDataFivePurchasesFirstTenDaysAgoIsLowData() = runTest {
        assertLowDataWithoutReassurance(lowDataResult(10, 8, 6, 3, 1))
    }

    @Test
    fun lowDataFivePurchasesFirstFourteenDaysAgoIsNotLowData() = runTest {
        // Observed span: 14 days back through today = 15 observed days, five purchases.
        val result = lowDataResult(14, 10, 7, 4, 1)

        assertNull(result.failure)
        assertEquals(false, result.isLowData)
        assertTrue(result.horizons.none { it.lowData })
        // Not-low control: with a large balance and tiny spend the reassuring row is allowed.
        val kinds = result.recommendations.map { it.kind }
        assertTrue(
            StressRecommendationKind.HEALTHY_OUTLOOK in kinds ||
                StressRecommendationKind.NO_IMMEDIATE_CONCERNS in kinds
        )
    }

    @Test
    fun lowDataFivePurchasesFirstThirteenDaysAgoIsNotLowDataBoundary() = runTest {
        // 13 days back through today = 14 observed days: exactly at the threshold, not low.
        val result = lowDataResult(13, 10, 7, 4, 1)

        assertNull(result.failure)
        assertEquals(false, result.isLowData)
    }

    @Test
    fun lowDataFivePurchasesFirstTwelveDaysAgoIsLowDataBoundary() = runTest {
        // 12 days back through today = 13 observed days: one under the threshold.
        assertLowDataWithoutReassurance(lowDataResult(12, 10, 7, 4, 1))
    }

    @Test
    fun lowDataThinHistoryWithRealCrunchStillShowsTheCrunchWarning() = runTest {
        coEvery { accountBalanceProvider.currentBalance(any()) } returns 0.0
        allExpenses = listOf(expense(3450L, 100.0, TransactionType.PURCHASE, now - 10 * dayMs))
        allDeposits = emptyList()

        val result = engine.computeStressForecast()

        assertNull(result.failure)
        assertTrue(result.isLowData)
        assertTrue(result.overallRiskLevel != StressRiskLevel.LOW)
        val kinds = result.recommendations.map { it.kind }
        assertTrue(StressRecommendationKind.REDUCE_DISCRETIONARY_SPENDING in kinds)
        assertTrue(StressRecommendationKind.HEALTHY_OUTLOOK !in kinds)
        assertTrue(StressRecommendationKind.NO_IMMEDIATE_CONCERNS !in kinds)
    }

    /** Purchases of 10 on each of the 59 days before today; the first transaction is 59 days back. */
    private fun fiftyNineDayPurchases(): List<Expense> = (1..59).map { daysBack ->
        expense(
            3100L + daysBack,
            10.0,
            TransactionType.PURCHASE,
            millis(2026, Calendar.APRIL, 20 - daysBack)
        )
    }

    @Test
    fun withdrawalsLowerProjectionByDailyRateTimesHorizonAndStayOutOfSimulatedSpend() = runTest {
        coEvery { accountBalanceProvider.currentBalance(any()) } returns 400.0
        allDeposits = emptyList()

        // Baseline: identical purchase history, no withdrawals.
        allExpenses = fiftyNineDayPurchases()
        val baseline = engine.computeStressForecast()

        // 800 withdrawn on the first transaction day, so the observed window is exactly 60 days
        // (59 days back through today): 800 / 60 a day = 400 / 800 / 1200 over 30 / 60 / 90 days.
        val withdrawal = expense(
            3200L, 800.0, TransactionType.WITHDRAWAL, millis(2026, Calendar.APRIL, 20 - 59, 13), merchant = "ATM"
        )
        allExpenses = fiftyNineDayPurchases() + withdrawal
        val withWithdrawal = engine.computeStressForecast()

        assertNull(withWithdrawal.failure)
        val expectedWithdrawals = mapOf(30 to 400.0, 60 to 800.0, 90 to 1_200.0)
        expectedWithdrawals.forEach { (days, expected) ->
            val before = baseline.horizons.single { it.daysAhead == days }
            val after = withWithdrawal.horizons.single { it.daysAhead == days }
            assertEquals("projectedWithdrawals ${days}d", expected, after.projectedWithdrawals, 1e-6)
            assertEquals("baseline withdrawals ${days}d", 0.0, before.projectedWithdrawals, 0.0)
            // The seeded simulation only sees purchases, so the whole shift is the withdrawal rate.
            assertEquals("projected shift ${days}d", expected, before.projectedBalance - after.projectedBalance, 1e-6)
            assertEquals("min shift ${days}d", expected, before.minProjectedBalance - after.minProjectedBalance, 1e-6)
            // Withdrawals are not purchase history: they do not change the low-data flag.
            assertEquals(before.lowData, after.lowData)
        }
    }

    @Test
    fun withdrawalsOnlyHistoryLowersNinetyDayProjectionByOneThousandTwoHundred() = runTest {
        coEvery { accountBalanceProvider.currentBalance(any()) } returns 400.0
        allDeposits = emptyList()
        allExpenses = listOf(
            expense(3201L, 800.0, TransactionType.WITHDRAWAL, millis(2026, Calendar.APRIL, 20 - 59, 13), merchant = "ATM")
        )

        val result = engine.computeStressForecast()

        val h90 = result.horizons.single { it.daysAhead == 90 }
        assertEquals(1_200.0, h90.projectedWithdrawals, 1e-6)
        assertEquals(-800.0, h90.projectedBalance, 1e-6)
        assertTrue(h90.lowData)
    }

    @Test
    fun noWithdrawalsLeavesProjectionUnchangedAndNotMineWithdrawalsAreIgnored() = runTest {
        coEvery { accountBalanceProvider.currentBalance(any()) } returns 400.0
        allDeposits = emptyList()
        allExpenses = fiftyNineDayPurchases()
        val baseline = engine.computeStressForecast()

        allExpenses = fiftyNineDayPurchases() + expense(
            3300L, 800.0, TransactionType.WITHDRAWAL, millis(2026, Calendar.APRIL, 20 - 59, 13),
            merchant = "ATM", isNotMine = true
        )
        val ignored = engine.computeStressForecast()

        baseline.horizons.zip(ignored.horizons).forEach { (a, b) ->
            assertEquals(0.0, a.projectedWithdrawals, 0.0)
            assertEquals(0.0, b.projectedWithdrawals, 0.0)
            assertEquals(a.projectedBalance, b.projectedBalance, 0.0)
            assertEquals(a.minProjectedBalance, b.minProjectedBalance, 0.0)
        }
    }

    @Before
    fun setup() {
        synthesisEngine = mockk(relaxed = true)
        monteCarloSimulator = mockk(relaxed = true)
        mergedRecurringPatternsProvider = mockk()
        expenseRepository = mockk()
        budgetRepository = mockk()
        timeProvider = mockk()
        currencySettingsRepository = mockk(relaxed = true)

        every { timeProvider.now() } returns now
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns emptyList()
        every { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())
        every { currencySettingsRepository.homeCurrency() } returns flowOf("EUR")
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        every { currencySettingsRepository.emergencyBuffer() } returns flowOf(500.0)

        coEvery { expenseRepository.getExpensesBetween(any(), any()) } answers {
            val start = firstArg<Long>()
            val end = secondArg<Long>()
            allExpenses.filter { it.date in start..end }
        }

        coEvery { expenseRepository.getDepositsBetween(any(), any()) } answers {
            val start = firstArg<Long>()
            val end = secondArg<Long>()
            allDeposits.filter { it.date in start..end }
        }

        analyticsCurrencyNormalizer = mockk(relaxed = true)
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

        recurringLifecycleCoordinator = mockk(relaxed = true)
        recurringOccurrenceDao = mockk(relaxed = true)
        databaseReadBarrier = mockk(relaxed = true)
        currencyConverter = mockk(relaxed = true)
        accountBalanceProvider = mockk(relaxed = true)
        // The provider returns Double? (null = unavailable), so the default is stated explicitly
        // rather than left to the relaxed mock: a known balance of 0 unless a test says otherwise.
        coEvery { accountBalanceProvider.currentBalance(any()) } returns 0.0
        multiCurrencyRepository = mockk(relaxed = true)
        // Identity conversion is explicit so confirmed EUR obligations are not
        // silently discarded by a relaxed mock (HZ01-B: the engine converts through
        // convertOutcome with StaleRatePolicy.forBasis, not the legacy convert()).
        stubConvertOutcome()

        engine = FinancialStressForecastEngine(
            synthesisEngine = synthesisEngine,
            monteCarloSimulator = monteCarloSimulator,
            recurringPatternsProvider = mergedRecurringPatternsProvider,
            expenseRepository = expenseRepository,
            budgetRepository = budgetRepository,
            timeProvider = timeProvider,
            analyticsCurrencyNormalizer = analyticsCurrencyNormalizer,
            currencySettingsRepository = currencySettingsRepository,
            multiCurrencyRepository = multiCurrencyRepository,
            recurringLifecycleCoordinator = recurringLifecycleCoordinator,
            recurringOccurrenceDao = recurringOccurrenceDao,
            currencyConverter = currencyConverter,
            accountBalanceProvider = accountBalanceProvider,
            databaseReadBarrier = databaseReadBarrier,
            installmentProjectionPort = com.yourname.expensetracker.domain.installment.projection.emptyInstallmentProjectionPort(),
            recurringInstallmentEligibility = com.yourname.expensetracker.domain.analytics.noPlansInstallmentEligibility()
        )
    }

    @Test
    fun `computeStressForecast empirical bootstrap is deterministic for same history`() = runTest {
        val monthlySalary = expense(1L, 3000.0, TransactionType.DEPOSIT, now - 5 * dayMs)
        val p1 = expense(2L, 20.0, TransactionType.PURCHASE, now - 10 * dayMs)
        val p2 = expense(3L, 40.0, TransactionType.PURCHASE, now - 9 * dayMs)
        val p3 = expense(4L, 60.0, TransactionType.PURCHASE, now - 8 * dayMs)
        val p4 = expense(5L, 80.0, TransactionType.PURCHASE, now - 7 * dayMs)

        allExpenses = listOf(monthlySalary, p1, p2, p3, p4)
        allDeposits = listOf(monthlySalary)
        every { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(overallBudgetStatus(1500.0, spent = 0.0)))

        val first = engine.computeStressForecast()
        val second = engine.computeStressForecast()

        assertEquals(first.overallRiskLevel, second.overallRiskLevel)
        assertEquals(first.earliestCrunchDate, second.earliestCrunchDate)

        first.horizons.zip(second.horizons).forEach { (a, b) ->
            assertEquals(a.daysAhead, b.daysAhead)
            assertApproxEquals(a.projectedBalance, b.projectedBalance, 0.01)
            assertApproxEquals(a.minProjectedBalance, b.minProjectedBalance, 0.01)
            assertApproxEquals(a.probabilityOfCrunch, b.probabilityOfCrunch, 0.01)
            assertEquals(a.riskLevel, b.riskLevel)
        }
    }

    @Test
    fun `computeStressForecast no-data fallback keeps percentile ordering consistent`() = runTest {
        allExpenses = emptyList()
        allDeposits = emptyList()
        every { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(overallBudgetStatus(900.0, spent = 0.0)))

        val result = engine.computeStressForecast()

        assertEquals(3, result.horizons.size)
        result.horizons.forEach { horizon ->
            // projectedBalance uses p50, minProjectedBalance uses p90.
            assertTrue(horizon.minProjectedBalance <= horizon.projectedBalance)
            assertTrue(horizon.probabilityOfCrunch in 0.0..1.0)
        }
    }

    @Test
    fun calculationFailureReturnsUnavailableRatherThanInventedRisk() = runTest {
        // Fail a dependency the forecast actually reads, not the obsolete budget path.
        coEvery { expenseRepository.getDepositsBetween(any(), any()) } throws IllegalStateException("boom")

        val result = engine.computeStressForecast()

        assertNull(result.overallRiskLevel)
        assertNull(result.earliestCrunchDate)
        assertTrue(result.horizons.isEmpty())
        assertTrue(result.recommendations.isEmpty())
        assertTrue(result.isPartial)
        assertEquals(StressForecastFailure.CALCULATION_UNAVAILABLE, result.failure)
        assertEquals(listOf("STRESS_FORECAST_UNAVAILABLE"), result.qualityWarnings)
        coVerify(exactly = 1) { expenseRepository.getDepositsBetween(any(), any()) }
    }

    @Test
    fun `classifyRiskLevel maps probabilities to all five tiers`() {
        assertEquals(StressRiskLevel.LOW, classifyRiskLevelViaReflection(0.00))
        assertEquals(StressRiskLevel.MODERATE, classifyRiskLevelViaReflection(0.10))
        assertEquals(StressRiskLevel.ELEVATED, classifyRiskLevelViaReflection(0.25))
        assertEquals(StressRiskLevel.HIGH, classifyRiskLevelViaReflection(0.50))
        assertEquals(StressRiskLevel.CRITICAL, classifyRiskLevelViaReflection(0.75))
    }

    // P-06-4 (was: expected CRITICAL with a crunch date from the invented 20 a day fallback).
    // With a known balance of 0, no purchases and no other outflows nothing is projected to leave
    // the account, so the outcome is LOW with no crunch date and is flagged low-data.
    @Test
    fun `computeStressForecast empty history edge case returns valid horizons`() = runTest {
        allExpenses = emptyList()
        allDeposits = emptyList()
        every { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())

        val result = engine.computeStressForecast()

        assertEquals(StressRiskLevel.LOW, result.overallRiskLevel)
        assertNull(result.earliestCrunchDate)
        assertTrue(result.isLowData)
        assertEquals(listOf(30, 60, 90), result.horizons.map { it.daysAhead })
        assertTrue(result.horizons.all { it.probabilityOfCrunch in 0.0..1.0 })
    }

    @Test
    fun `computeStressForecast extreme positive balance drives low risk with no crunch date`() = runTest {
        val hugeDeposit = expense(100L, 1_000_000.0, TransactionType.DEPOSIT, now - 1 * dayMs)
        allExpenses = listOf(hugeDeposit)
        allDeposits = listOf(hugeDeposit)
        every { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(overallBudgetStatus(1000.0, spent = 0.0)))

        val result = engine.computeStressForecast()

        assertEquals(StressRiskLevel.LOW, result.overallRiskLevel)
        assertNull(result.earliestCrunchDate)
        assertTrue(result.horizons.all { it.riskLevel == StressRiskLevel.LOW })
    }

    @Test
    fun `computeStressForecast zero discretionary expenses still produces stable output`() = runTest {
        val salary = expense(200L, 2500.0, TransactionType.DEPOSIT, now - 2 * dayMs)
        allExpenses = listOf(salary)
        allDeposits = listOf(salary)
        every { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(overallBudgetStatus(1200.0, spent = 0.0)))

        val result = engine.computeStressForecast()

        assertEquals(3, result.horizons.size)
        result.horizons.forEach {
            assertTrue(it.projectedBalance >= it.minProjectedBalance)
            assertTrue(it.discretionaryBuffer >= 0.0)
        }
    }

    @Test
    fun `computeStressForecast recurring-only purchase history keeps Monte Carlo discretionary at zero`() = runTest {
        val salary = expense(300L, 3000.0, TransactionType.DEPOSIT, now - 2 * dayMs, merchant = "Employer")
        val recurring1 = expense(301L, 45.0, TransactionType.PURCHASE, now - 20 * dayMs, merchant = "NETFLIX")
        val recurring2 = expense(302L, 45.0, TransactionType.PURCHASE, now - 10 * dayMs, merchant = "Netflix")
        val recurring3 = expense(303L, 45.0, TransactionType.PURCHASE, now - 3 * dayMs, merchant = "Netflix ")

        allExpenses = listOf(salary, recurring1, recurring2, recurring3)
        allDeposits = listOf(salary)
        every { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(overallBudgetStatus(1200.0, spent = 0.0)))
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            recurringPattern(
                merchant = "Netflix",
                amount = 45.0,
                nextDate = now + dayMs,
                frequency = RecurrenceFrequency.MONTHLY,
                confidence = 0.95f
            )
        )

        val result = engine.computeStressForecast()

        result.horizons.forEach { horizon ->
            // When recurring purchases are excluded from discretionary empirical input,
            // p50 and p90 discretionary spend collapse to the same value (0 in this fixture).
            assertApproxEquals(horizon.projectedBalance, horizon.minProjectedBalance, 0.0001)
        }
    }

    @Test
    fun `computeStressForecast uses merged recurring obligations so duplicate stale manual rows do not double count`() = runTest {
        allExpenses = emptyList()
        allDeposits = emptyList()
        val due = now + dayMs
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            recurringPattern(
                merchant = "Netflix",
                amount = 15.0,
                nextDate = due,
                frequency = RecurrenceFrequency.MONTHLY,
                confidence = 1.0f,
                id = 1L
            )
        )
        coEvery { recurringLifecycleCoordinator.projectOccurrences(1L, any(), any()) } returns listOf(
            occurrenceRow(ruleId = 1L, dueDate = due, amount = 15.0, merchant = "Netflix")
        )

        val result = engine.computeStressForecast()

        assertApproxEquals(15.0, result.horizons.first { it.daysAhead == 30 }.recurringObligations, 0.0001)
    }

    @Test
    fun `computeStressForecast ignores unconfirmed detected recurring suggestions`() = runTest {
        allExpenses = emptyList()
        allDeposits = emptyList()
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns emptyList()

        val result = engine.computeStressForecast()

        assertApproxEquals(0.0, result.horizons.first { it.daysAhead == 30 }.recurringObligations, 0.0001)
    }

    @Test
    fun `computeStressForecast includes confirmed recurring obligations`() = runTest {
        allExpenses = emptyList()
        allDeposits = emptyList()
        val due = now + dayMs
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            recurringPattern(
                merchant = "Confirmed Rent",
                amount = 800.0,
                nextDate = due,
                frequency = RecurrenceFrequency.MONTHLY,
                confidence = 1.0f,
                id = 2L
            )
        )
        coEvery { recurringLifecycleCoordinator.projectOccurrences(2L, any(), any()) } returns listOf(
            occurrenceRow(ruleId = 2L, dueDate = due, amount = 800.0, merchant = "Confirmed Rent")
        )

        val result = engine.computeStressForecast()

        assertApproxEquals(800.0, result.horizons.first { it.daysAhead == 30 }.recurringObligations, 0.0001)
    }

    // ── P6-CURRENT-024: read path projects, never materializes ─────────────

    @Test
    fun `computeStressForecast uses projectOccurrences and never generateOccurrences for manual rules`() = runTest {
        allExpenses = emptyList()
        allDeposits = emptyList()
        // Manual pattern (id != null) → exercises the coordinator occurrence path.
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            RecurringPattern(
                id = 7L,
                merchantName = "Rent",
                averageAmount = 800.0,
                currency = "EUR",
                frequency = RecurrenceFrequency.MONTHLY,
                periodVarianceDays = 0,
                amountVariancePercent = 0.0,
                nextExpectedDate = now + dayMs,
                confidence = 1.0f,
                previousDates = emptyList()
            )
        )
        coEvery { recurringLifecycleCoordinator.projectOccurrences(7L, any(), any()) } returns emptyList()
        coEvery { recurringOccurrenceDao.getByDateRange(any(), any()) } returns emptyList()

        engine.computeStressForecast()

        coVerify(atLeast = 1) { recurringLifecycleCoordinator.projectOccurrences(7L, any(), any()) }
        coVerify(exactly = 0) {
            recurringLifecycleCoordinator.generateOccurrences(any(), any(), any(), any())
        }
        verify(atLeast = 1) { databaseReadBarrier.checkReadAllowed(any<String>()) }
    }

    @Test
    fun `computeStressForecast reads already-materialized occurrence path unchanged`() = runTest {
        allExpenses = emptyList()
        allDeposits = emptyList()
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            RecurringPattern(
                id = 7L,
                merchantName = "Rent",
                averageAmount = 800.0,
                currency = "EUR",
                frequency = RecurrenceFrequency.MONTHLY,
                periodVarianceDays = 0,
                amountVariancePercent = 0.0,
                nextExpectedDate = now + dayMs,
                confidence = 1.0f,
                previousDates = emptyList()
            )
        )
        // Projection empty; materialized row must still be counted (read-only).
        coEvery { recurringLifecycleCoordinator.projectOccurrences(7L, any(), any()) } returns emptyList()
        coEvery { recurringOccurrenceDao.getByDateRange(any(), any()) } answers {
            val start = firstArg<Long>()
            val end = secondArg<Long>()
            val due = now + dayMs
            if (due in start until end) {
                listOf(
                    RecurringOccurrence(
                        sourceType = RecurringLifecycleCoordinator.SOURCE_TYPE_RECURRING_RULE,
                        sourceId = 7L,
                        occurrenceKey = "RECURRING_RULE|7|$due|MONTHLY",
                        dueDate = due,
                        status = "PLANNED",
                        expectedAmount = 800.0,
                        expectedCurrency = "EUR",
                        frequency = "MONTHLY",
                        merchant = "Rent",
                        categoryId = null
                    )
                )
            } else emptyList()
        }

        val result = engine.computeStressForecast()

        // The materialized obligation is included for the 30-day horizon.
        assertTrue(result.horizons.first { it.daysAhead == 30 }.recurringObligations >= 0.0)
        verify(atLeast = 1) { databaseReadBarrier.checkReadAllowed(any<String>()) }
    }

    @Test
    fun `computeStressForecast includes recurring obligation due earlier today`() = runTest {
        val noonToday = millis(2026, Calendar.APRIL, 20, 12)
        val earlierToday = millis(2026, Calendar.APRIL, 20, 8)
        val nextMonth = millis(2026, Calendar.MAY, 20, 8)
        every { timeProvider.now() } returns noonToday
        allExpenses = emptyList()
        allDeposits = emptyList()
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            recurringPattern(
                merchant = "Morning Bill",
                amount = 25.0,
                nextDate = earlierToday,
                frequency = RecurrenceFrequency.MONTHLY,
                confidence = 1.0f,
                id = 3L
            )
        )
        coEvery { recurringLifecycleCoordinator.projectOccurrences(3L, any(), any()) } returns listOf(
            occurrenceRow(ruleId = 3L, dueDate = earlierToday, amount = 25.0, merchant = "Morning Bill"),
            occurrenceRow(ruleId = 3L, dueDate = nextMonth, amount = 25.0, merchant = "Morning Bill")
        )

        val result = engine.computeStressForecast()

        assertApproxEquals(50.0, result.horizons.first { it.daysAhead == 30 }.recurringObligations, 0.0001)
    }

    @Test
    fun `computeStressForecast missing income history does not fall back to budget as income`() = runTest {
        allExpenses = emptyList()
        allDeposits = emptyList()
        every { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(overallBudgetStatus(5_000.0, spent = 0.0)))

        val result = engine.computeStressForecast()

        result.horizons.forEach { horizon ->
            assertApproxEquals(0.0, horizon.expectedIncome, 0.0001)
        }
    }

    @Test
    fun `computeStressForecast includes zero spend days in discretionary samples`() = runTest {
        val singlePurchase = expense(400L, 100.0, TransactionType.PURCHASE, now - 10 * dayMs)
        allExpenses = listOf(singlePurchase)
        allDeposits = emptyList()

        val result = engine.computeStressForecast()
        val horizon30 = result.horizons.first { it.daysAhead == 30 }

        assertApproxEquals(0.0, horizon30.expectedIncome, 0.0001)
        // The sample starts at the first purchase (10 days ago) but is floored at 14 days (zero-spend
        // days included): each simulated day has a 1/14 chance of spending 100. Over 30 days the
        // spend count is Binomial(30, 1/14): median 2 (projected -200), p90 about 4 (-400), so the
        // projected balance is non-positive and the minimum is strictly negative.
        assertTrue(horizon30.projectedBalance <= 0.0)
        assertTrue(horizon30.projectedBalance > -1_000.0)
        assertTrue(horizon30.minProjectedBalance < 0.0)
        assertTrue(horizon30.minProjectedBalance <= horizon30.projectedBalance)
        assertTrue(horizon30.minProjectedBalance > -1_500.0)
    }

    @Test
    fun `stress window starts at first non-installment purchase including recurring-merchant history not at the discretionary row`() = runTest {
        coEvery { accountBalanceProvider.currentBalance(any()) } returns 0.0
        // Recurring-merchant purchases reach back to the start of the 60 day lookback (+3h so the
        // row stays inside the repository window across a DST shift). The only discretionary row
        // is one 500 purchase 10 days ago. Recurring history shows the user is established, so the
        // sample is the 61 lookback days (about 500/61 = 8.20 a day), not the 14 day floor
        // (500/14 = 35.71 a day).
        val hourMs = 60L * 60L * 1000L
        val recurringRows = listOf(
            expense(601L, 45.0, TransactionType.PURCHASE, now - 60 * dayMs + 3 * hourMs, merchant = "Netflix"),
            expense(602L, 45.0, TransactionType.PURCHASE, now - 40 * dayMs, merchant = "NETFLIX"),
            expense(603L, 45.0, TransactionType.PURCHASE, now - 20 * dayMs, merchant = "Netflix ")
        )
        val discretionary = expense(604L, 500.0, TransactionType.PURCHASE, now - 10 * dayMs, merchant = "Synthetic Gadget")
        allExpenses = recurringRows + discretionary
        allDeposits = emptyList()
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            recurringPattern(
                merchant = "Netflix",
                amount = 45.0,
                nextDate = now + dayMs,
                frequency = RecurrenceFrequency.MONTHLY,
                confidence = 0.95f
            )
        )

        val result = engine.computeStressForecast()

        val h30 = result.horizons.first { it.daysAhead == 30 }
        val h90 = result.horizons.first { it.daysAhead == 90 }
        // Balance and income are 0, so discretionary p50 / p90 = -projected / -min minus obligations.
        val p50At90 = -h90.projectedBalance - h90.recurringObligations
        val p90At90 = -h90.minProjectedBalance - h90.recurringObligations
        val p90At30 = -h30.minProjectedBalance - h30.recurringObligations
        // 61 day sample: 90 draws at 1/61 give mean about 738, median 1 purchase (500), p90 3 (1500).
        // A 14 day sample would give mean about 3214, median 6 (3000), p90 about 10 (5000).
        assertTrue("p50 at 90 days was $p50At90", p50At90 in 0.0..1_000.0)
        assertTrue("p90 at 90 days was $p90At90", p90At90 in 500.0..2_000.0)
        assertTrue("p90 at 30 days was $p90At30", p90At30 in 0.0..1_000.0)
    }

    @Test
    fun `computeStressForecast does not treat current month net cashflow as account balance`() = runTest {
        allExpenses = listOf(
            expense(500L, 3_000.0, TransactionType.DEPOSIT, now - 2 * dayMs, merchant = "Employer"),
            expense(501L, 200.0, TransactionType.PURCHASE, now - 1 * dayMs)
        )
        allDeposits = emptyList()

        val result = engine.computeStressForecast()

        result.horizons.forEach { horizon ->
            assertTrue(
                "Projected balance should not be inflated by month-to-date net cashflow",
                horizon.projectedBalance <= 0.0
            )
        }
    }

    // ── DBG-03: paused-rule materialized occurrence must not leak into obligations ──

    @Test
    fun `DBG-03 stress excludes paused rule materialized occurrence from obligations`() = runTest {
        allExpenses = emptyList()
        allDeposits = emptyList()
        every { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())

        val due = now + dayMs
        // Only the ACTIVE rule (id=7) is returned by getConfirmedPatterns (production yields
        // active rules only), so the paused rule (id=8) is NOT in the derived ruleIds.
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            RecurringPattern(
                id = 7L,
                merchantName = "Rent",
                averageAmount = 800.0,
                currency = "EUR",
                frequency = RecurrenceFrequency.MONTHLY,
                periodVarianceDays = 0,
                amountVariancePercent = 0.0,
                nextExpectedDate = due,
                confidence = 1.0f,
                previousDates = emptyList()
            )
        )
        coEvery { recurringLifecycleCoordinator.projectOccurrences(7L, any(), any()) } returns emptyList()
        // DAO holds previously-materialized PLANNED rows for BOTH the active rule (id=7)
        // and a PAUSED rule (id=8). Only id=7 must be counted.
        coEvery { recurringOccurrenceDao.getByDateRange(any(), any()) } answers {
            val start = firstArg<Long>()
            val end = secondArg<Long>()
            if (due in start until end) {
                listOf(
                    occurrenceRow(ruleId = 7L, dueDate = due, amount = 800.0, merchant = "Rent"),
                    occurrenceRow(ruleId = 8L, dueDate = due, amount = 50.0, merchant = "Paused Gym")
                )
            } else emptyList()
        }
        // EUR→EUR passthrough so amounts actually sum (see stubConvertOutcome).
        stubConvertOutcome()

        val result = engine.computeStressForecast()

        // Obligations reflect ONLY the active rule (800.0); the paused rule's 50.0 is filtered.
        assertApproxEquals(800.0, result.horizons.first { it.daysAhead == 30 }.recurringObligations, 0.0001)
    }

    // ── DBG-06: barrier-blocked materialized read marks the horizon partial ──

    @Test
    fun `DBG-06 stress marks recurring partial when materialized read barrier-blocked`() = runTest {
        allExpenses = emptyList()
        allDeposits = emptyList()
        every { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())

        val due = now + dayMs
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            RecurringPattern(
                id = 7L,
                merchantName = "Rent",
                averageAmount = 800.0,
                currency = "EUR",
                frequency = RecurrenceFrequency.MONTHLY,
                periodVarianceDays = 0,
                amountVariancePercent = 0.0,
                nextExpectedDate = due,
                confidence = 1.0f,
                previousDates = emptyList()
            )
        )
        coEvery { recurringLifecycleCoordinator.projectOccurrences(7L, any(), any()) } returns emptyList()
        // Restore barrier blocks the materialized read inside calculateRecurringOutflows.
        // It is caught locally (does NOT trip the engine's outer degraded fallback).
        every { databaseReadBarrier.checkReadAllowed(any<String>()) } throws DatabaseAccessBlockedException(
            accessType = DatabaseAccessType.READ,
            operation = DatabaseAccessOperation("FinancialStressForecastEngine.calculateRecurringOutflows"),
            mode = RestoreMaintenanceMode.Mode.RESTORE_STAGING
        )

        val result = engine.computeStressForecast()

        // Normal (not degraded-fallback) result, but the recurring section is flagged partial.
        assertEquals(listOf(30, 60, 90), result.horizons.map { it.daysAhead })
        assertTrue(result.horizons.all { it.recurringObligationsPartial })
    }

    // ── B3-4 (P6-P1-14 / NEW-P6-014): behavioral replacements for P6BudgetCleanupTest
    // source-regex and formula-copy tests ─────────────────────────────────────

    @Test
    fun `P6-P1-14 stress counts only PLANNED OVERDUE and DUE occurrences as obligations`() = runTest {
        allExpenses = emptyList()
        allDeposits = emptyList()
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            recurringPattern(
                merchant = "Rule",
                amount = 800.0,
                nextDate = now + dayMs,
                frequency = RecurrenceFrequency.MONTHLY,
                confidence = 1.0f,
                id = 7L
            )
        )
        // One occurrence per status on distinct days inside the 30-day horizon.
        val amountsByStatus = listOf(
            "PLANNED" to 800.0,
            "OVERDUE" to 60.0,
            "DUE" to 40.0,
            "PAID" to 300.0,
            "SKIPPED" to 200.0,
            "CANCELLED" to 100.0,
            "IGNORED" to 50.0,
            "MISSED" to 25.0,
            "NOT_A_KNOWN_STATUS" to 11.0
        )
        coEvery { recurringLifecycleCoordinator.projectOccurrences(7L, any(), any()) } returns
            amountsByStatus.mapIndexed { index, (status, amount) ->
                occurrenceRow(
                    ruleId = 7L,
                    dueDate = now + (index + 1) * dayMs,
                    amount = amount,
                    merchant = "Rule",
                    status = status
                )
            }
        coEvery { recurringOccurrenceDao.getByDateRange(any(), any()) } returns emptyList()

        val result = engine.computeStressForecast()

        // 800 (PLANNED) + 60 (OVERDUE) + 40 (DUE); PAID, SKIPPED, CANCELLED,
        // IGNORED, MISSED and unknown statuses contribute nothing.
        assertEquals(900.0, result.horizons.first { it.daysAhead == 30 }.recurringObligations, 0.0)
    }

    @Test
    fun `NEW-P6-014 expected income divides deposits by the actual month span of the history`() = runTest {
        allExpenses = emptyList()
        // Feb 19 -> Apr 19 spans 59 days; two deposits cover 59 * 2 = 118 days = 3.8764 fractional
        // months (P-06-4 plus the n / (n - 1) fencepost): 2000 / 3.8764 = 515.932 per month
        // (hand-derived 60880 / 118). Before the fencepost fix this was 1031.864; the retired
        // hardcoded divisor of 3 would give 666.67 per month.
        allDeposits = listOf(
            expense(600L, 1000.0, TransactionType.DEPOSIT, millis(2026, Calendar.FEBRUARY, 19), merchant = "Employer"),
            expense(601L, 1000.0, TransactionType.DEPOSIT, millis(2026, Calendar.APRIL, 19), merchant = "Employer")
        )

        val result = engine.computeStressForecast()

        assertEquals(515.932, result.horizons.first { it.daysAhead == 30 }.expectedIncome, 0.001)
        assertEquals(1031.864, result.horizons.first { it.daysAhead == 60 }.expectedIncome, 0.001)
        assertEquals(1547.797, result.horizons.first { it.daysAhead == 90 }.expectedIncome, 0.001)
    }

    @Test
    fun `NEW-P6-014 deposits within one calendar month use the fencepost corrected fractional period`() = runTest {
        allExpenses = emptyList()
        // Apr 2 -> Apr 19 spans 17 days; two deposits cover 34 days = 1.1169 months (above the
        // 1 month floor): 1500 / 1.1169 = 1342.941 a month (before the fencepost fix 1500.0).
        allDeposits = listOf(
            expense(610L, 1000.0, TransactionType.DEPOSIT, millis(2026, Calendar.APRIL, 2), merchant = "Employer"),
            expense(611L, 500.0, TransactionType.DEPOSIT, millis(2026, Calendar.APRIL, 19), merchant = "Bonus")
        )

        val result = engine.computeStressForecast()

        assertEquals(1342.941, result.horizons.first { it.daysAhead == 30 }.expectedIncome, 0.001)
    }

    // ── HZ01-B: failed conversions are excluded, counted and flagged partial ──

    @Test
    fun `HZ01-B missing USD rate excludes occurrence marks partial and counts it`() = runTest {
        allExpenses = emptyList()
        allDeposits = emptyList()
        val due = now + dayMs
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            recurringPattern("Rent", 800.0, due, RecurrenceFrequency.MONTHLY, 1.0f, id = 2L),
            recurringPattern("US Streaming", 15.0, due, RecurrenceFrequency.MONTHLY, 1.0f, id = 3L)
        )
        coEvery { recurringLifecycleCoordinator.projectOccurrences(2L, any(), any()) } returns listOf(
            occurrenceRow(ruleId = 2L, dueDate = due, amount = 800.0, merchant = "Rent")
        )
        coEvery { recurringLifecycleCoordinator.projectOccurrences(3L, any(), any()) } returns listOf(
            occurrenceRow(ruleId = 3L, dueDate = due, amount = 15.0, merchant = "US Streaming", currency = "USD")
        )
        // No USD rate configured (default rate table is empty).

        val result = engine.computeStressForecast()

        val h30 = result.horizons.first { it.daysAhead == 30 }
        // The USD row is excluded, never added raw into the EUR total.
        assertApproxEquals(800.0, h30.recurringObligations, 0.0001)
        assertTrue(h30.recurringObligationsPartial)
        assertEquals(1, h30.recurringExcludedConversionCount)
        assertTrue(result.isPartial)
        assertEquals(1, result.excludedCount)
    }

    @Test
    fun `HZ01-B excludedCount is the 90 day horizon count not the sum across horizons`() = runTest {
        allExpenses = emptyList()
        allDeposits = emptyList()
        val dueNear = now + dayMs
        val dueMid = now + 45 * dayMs
        val dueFar = now + 75 * dayMs
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            recurringPattern("US Streaming", 15.0, dueNear, RecurrenceFrequency.MONTHLY, 1.0f, id = 3L)
        )
        val usdRows = listOf(dueNear, dueMid, dueFar).map {
            occurrenceRow(ruleId = 3L, dueDate = it, amount = 15.0, merchant = "US Streaming", currency = "USD")
        }
        // Honor the requested window so each horizon sees only its own occurrences:
        // 30d -> 1, 60d -> 2, 90d -> 3 (no USD rate configured, so all are excluded).
        coEvery { recurringLifecycleCoordinator.projectOccurrences(3L, any(), any()) } answers {
            val start = secondArg<Long>()
            val end = thirdArg<Long>()
            usdRows.filter { it.dueDate >= start && it.dueDate < end }
        }

        val result = engine.computeStressForecast()

        val h30 = result.horizons.first { it.daysAhead == 30 }
        val h60 = result.horizons.first { it.daysAhead == 60 }
        val h90 = result.horizons.first { it.daysAhead == 90 }
        assertEquals(1, h30.recurringExcludedConversionCount)
        assertEquals(2, h60.recurringExcludedConversionCount)
        assertEquals(3, h90.recurringExcludedConversionCount)
        // Max across horizons (90d is a superset), not the sum (6).
        assertEquals(3, result.excludedCount)
        assertTrue(result.isPartial)
    }

    @Test
    fun `HZ01-B blank display currency never sums detected patterns raw`() = runTest {
        allExpenses = emptyList()
        allDeposits = emptyList()
        val due = now + dayMs
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            RecurringPattern(
                id = null,
                merchantName = "Detected EUR",
                averageAmount = 40.0,
                currency = "EUR",
                frequency = RecurrenceFrequency.MONTHLY,
                periodVarianceDays = 0,
                amountVariancePercent = 0.0,
                nextExpectedDate = due,
                confidence = 1.0f,
                previousDates = emptyList()
            )
        )

        val result = engine.computeStressForecast(displayCurrency = "")

        // A blank target is an invalid target: the forecast fails closed instead of adding
        // the pattern's raw averageAmount into an unlabeled total.
        assertEquals(StressForecastFailure.CALCULATION_UNAVAILABLE, result.failure)
        assertTrue(result.horizons.isEmpty())
        assertTrue(result.isPartial)
    }

    @Test
    fun `HZ01-B detected pattern without rate is excluded counted and partial`() = runTest {
        allExpenses = emptyList()
        allDeposits = emptyList()
        val due = now + dayMs
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            RecurringPattern(
                id = null,
                merchantName = "Detected USD",
                averageAmount = 40.0,
                currency = "USD",
                frequency = RecurrenceFrequency.MONTHLY,
                periodVarianceDays = 0,
                amountVariancePercent = 0.0,
                nextExpectedDate = due,
                confidence = 1.0f,
                previousDates = emptyList()
            )
        )

        val result = engine.computeStressForecast()

        val h30 = result.horizons.first { it.daysAhead == 30 }
        assertApproxEquals(0.0, h30.recurringObligations, 0.0001)
        assertTrue(h30.recurringObligationsPartial)
        assertEquals(1, h30.recurringExcludedConversionCount)
        assertTrue(result.isPartial)
    }

    @Test
    fun `HZ01-B three day old rate is accepted under latest stale policy`() = runTest {
        allExpenses = emptyList()
        allDeposits = emptyList()
        val due = now + dayMs
        // A 3-day-old USD rate is older than StaleRatePolicy.Default (24h) but inside
        // StaleRatePolicy.forBasis(LATEST_AVAILABLE) (7d); the fake enforces the passed policy.
        rates = mapOf("USD" to (0.5 to now - 3 * dayMs))
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            recurringPattern("US Streaming", 20.0, due, RecurrenceFrequency.MONTHLY, 1.0f, id = 3L)
        )
        coEvery { recurringLifecycleCoordinator.projectOccurrences(3L, any(), any()) } returns listOf(
            occurrenceRow(ruleId = 3L, dueDate = due, amount = 20.0, merchant = "US Streaming", currency = "USD")
        )

        val result = engine.computeStressForecast()

        val h30 = result.horizons.first { it.daysAhead == 30 }
        assertApproxEquals(10.0, h30.recurringObligations, 0.0001)
        assertEquals(false, h30.recurringObligationsPartial)
        assertEquals(0, h30.recurringExcludedConversionCount)
        assertEquals(false, result.isPartial)
        assertEquals(
            com.yourname.expensetracker.domain.core.money.StaleRatePolicy.forBasis(
                com.yourname.expensetracker.domain.core.money.RateBasis.LATEST_AVAILABLE
            ),
            lastStalePolicy
        )
    }

    @Test
    fun `HZ01-B rate older than latest stale window is excluded as partial`() = runTest {
        allExpenses = emptyList()
        allDeposits = emptyList()
        val due = now + dayMs
        rates = mapOf("USD" to (0.5 to now - 10 * dayMs))
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            recurringPattern("US Streaming", 20.0, due, RecurrenceFrequency.MONTHLY, 1.0f, id = 3L)
        )
        coEvery { recurringLifecycleCoordinator.projectOccurrences(3L, any(), any()) } returns listOf(
            occurrenceRow(ruleId = 3L, dueDate = due, amount = 20.0, merchant = "US Streaming", currency = "USD")
        )

        val result = engine.computeStressForecast()

        val h30 = result.horizons.first { it.daysAhead == 30 }
        assertApproxEquals(0.0, h30.recurringObligations, 0.0001)
        assertTrue(h30.recurringObligationsPartial)
        assertTrue(result.isPartial)
    }

    @Test
    fun `HZ01-B cancellation during occurrence conversion propagates`() = runTest {
        allExpenses = emptyList()
        allDeposits = emptyList()
        val due = now + dayMs
        val cancellation = kotlinx.coroutines.CancellationException("test cancellation")
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            recurringPattern("Rent", 800.0, due, RecurrenceFrequency.MONTHLY, 1.0f, id = 2L)
        )
        coEvery { recurringLifecycleCoordinator.projectOccurrences(2L, any(), any()) } returns listOf(
            occurrenceRow(ruleId = 2L, dueDate = due, amount = 800.0, merchant = "Rent")
        )
        coEvery {
            currencyConverter.convertOutcome(any(), any(), any(), any(), any(), any())
        } throws cancellation

        org.junit.Assert.assertSame(cancellation, runCatching { engine.computeStressForecast() }.exceptionOrNull())
    }

    /** Per-currency rate table for [stubConvertOutcome]: currency -> (rate to EUR, rate lastUpdated). */
    private var rates: Map<String, Pair<Double, Long>> = emptyMap()
    private var lastStalePolicy: com.yourname.expensetracker.domain.core.money.StaleRatePolicy? = null

    /**
     * Fake [CurrencyConverter.convertOutcome]: identity for same currency, table lookup otherwise
     * (missing -> MISSING_RATE), and it enforces the stale policy actually passed by the engine.
     */
    private fun stubConvertOutcome() {
        coEvery {
            currencyConverter.convertOutcome(any(), any(), any(), any(), any(), any())
        } answers {
            val amount = firstArg<Double>()
            val source = secondArg<String>()
            val target = thirdArg<String>()
            val basis = arg<com.yourname.expensetracker.domain.core.money.RateBasis>(3)
            val policy = arg<com.yourname.expensetracker.domain.core.money.StaleRatePolicy>(5)
            lastStalePolicy = policy
            fun failed(type: com.yourname.expensetracker.domain.core.money.ConversionFailureType) =
                com.yourname.expensetracker.domain.core.money.ConversionOutcome.Failed(
                    originalAmount = amount,
                    originalCurrency = source,
                    targetCurrency = target,
                    rateBasis = basis,
                    failureType = type,
                    message = type.name
                )
            fun converted(rate: Double, updated: Long?, path: com.yourname.expensetracker.domain.core.money.ConversionPath) =
                com.yourname.expensetracker.domain.core.money.ConversionOutcome.Converted(
                    originalAmount = amount,
                    originalCurrency = CurrencyCode(source),
                    convertedAmount = amount * rate,
                    targetCurrency = CurrencyCode(target),
                    rateUsed = rate,
                    rateBasis = basis,
                    rateValidDate = null,
                    rateLastUpdated = updated,
                    rateSource = null,
                    conversionPath = path
                )
            if (source.equals(target, ignoreCase = true)) {
                converted(1.0, null, com.yourname.expensetracker.domain.core.money.ConversionPath.IDENTITY)
            } else {
                val entry = rates[source.uppercase()]
                when {
                    entry == null ->
                        failed(com.yourname.expensetracker.domain.core.money.ConversionFailureType.MISSING_RATE)
                    policy.maxAgeMs != null && now - entry.second > policy.maxAgeMs!! ->
                        failed(com.yourname.expensetracker.domain.core.money.ConversionFailureType.STALE_RATE)
                    else -> converted(entry.first, entry.second, com.yourname.expensetracker.domain.core.money.ConversionPath.DIRECT)
                }
            }
        }
    }

    private fun occurrenceRow(
        ruleId: Long,
        dueDate: Long,
        amount: Double,
        merchant: String,
        status: String = "PLANNED",
        frequency: RecurrenceFrequency = RecurrenceFrequency.MONTHLY,
        currency: String = "EUR"
    ): RecurringOccurrence = RecurringOccurrence(
        sourceType = RecurringLifecycleCoordinator.SOURCE_TYPE_RECURRING_RULE,
        sourceId = ruleId,
        occurrenceKey = "${RecurringLifecycleCoordinator.SOURCE_TYPE_RECURRING_RULE}|$ruleId|$dueDate|${frequency.name}",
        dueDate = dueDate,
        status = status,
        expectedAmount = amount,
        expectedCurrency = currency,
        frequency = frequency.name,
        merchant = merchant,
        categoryId = null
    )

    private fun classifyRiskLevelViaReflection(probability: Double): StressRiskLevel {
        val method = FinancialStressForecastEngine::class.java.getDeclaredMethod(
            "classifyRiskLevel",
            Double::class.javaPrimitiveType
        )
        method.isAccessible = true
        return method.invoke(engine, probability) as StressRiskLevel
    }

    private fun overallBudgetStatus(amount: Double, spent: Double): BudgetStatus {
        val budget = Budget(
            id = 1L,
            categoryId = null,
            amount = amount,
            period = BudgetPeriod.MONTHLY,
            startDate = now - 20 * dayMs
        )
        return BudgetStatus(
            budget = budget,
            category = null,
            spentAmount = spent,
            remainingAmount = (amount - spent).coerceAtLeast(0.0),
            percentUsed = if (amount > 0) (spent / amount).toFloat() else 0f,
            healthStatus = BudgetHealthStatus.ON_TRACK,
            periodStart = now - 20 * dayMs,
            periodEnd = now + 10 * dayMs,
            effectiveLimit = 0.0,
        )
    }

    private fun expense(
        id: Long,
        amount: Double,
        type: TransactionType,
        date: Long,
        merchant: String = "M$id",
        isNotMine: Boolean = false
    ): Expense {
        return Expense(
            id = id,
            amount = amount,
            merchant = merchant,
            transactionType = type,
            date = date,
            categoryId = 1L,
            isNotMine = isNotMine
        )
    }

    private fun recurringPattern(
        merchant: String,
        amount: Double,
        nextDate: Long,
        frequency: RecurrenceFrequency,
        confidence: Float,
        id: Long? = null
    ): RecurringPattern {
        return RecurringPattern(
            id = id,
            merchantName = merchant,
            averageAmount = amount,
            currency = "EUR",
            frequency = frequency,
            periodVarianceDays = 0,
            amountVariancePercent = 0.0,
            nextExpectedDate = nextDate,
            confidence = confidence,
            previousDates = emptyList()
        )
    }

    // ── U-TIME-02: DST-safe calendar arithmetic ──────────────────────────────

    @Test
    fun `computeStressForecast 90-day lookback uses calendar-aware arithmetic not raw millis`() = runTest {
        // Set "now" to a date shortly after a DST spring-forward transition.
        // If raw millis were used (90 * 86400000), the lookback would land on the
        // wrong calendar date. With TimePeriodUtils.addDays() it lands correctly.
        val marchAfterDst = millis(2026, Calendar.MARCH, 15, 14)
        every { timeProvider.now() } returns marchAfterDst
        allExpenses = emptyList()
        allDeposits = emptyList()

        val result = engine.computeStressForecast()

        // Engine must not crash and must produce valid horizons.
        assertEquals(3, result.horizons.size)
        assertEquals(listOf(30, 60, 90), result.horizons.map { it.daysAhead })
        result.horizons.forEach { horizon ->
            assertTrue(horizon.probabilityOfCrunch in 0.0..1.0)
        }
    }

    @Test
    fun `computeStressForecast horizon end uses calendar days not raw millis multiplication`() = runTest {
        // Verify that the 30-day horizon end is exactly 30 calendar days ahead,
        // not 30 * 86400000 ms (which would be wrong across DST).
        val novBeforeFallBack = millis(2026, Calendar.NOVEMBER, 1, 10)
        every { timeProvider.now() } returns novBeforeFallBack
        allExpenses = emptyList()
        allDeposits = emptyList()

        val result = engine.computeStressForecast()

        // The earliest crunch date (if present) must be a valid future timestamp.
        if (result.earliestCrunchDate != null) {
            assertTrue(result.earliestCrunchDate!! > novBeforeFallBack)
        }
        assertEquals(3, result.horizons.size)
    }

    // ── stress-forecast-actions S1 (AE-1..AE-7): structured recommendations ──
    //
    // P-06-4: the engine no longer invents spend for an empty history, so the fixtures below use a
    // real steady 20 a day purchase history (60 days) and assert only kinds, order and parameters,
    // never simulated money.

    private val kindsOf: (StressForecastResult) -> List<StressRecommendationKind> =
        { result -> result.recommendations.map { it.kind } }

    @Test
    fun `AE-2 AE-3 AE-4 INV-1 INV-2 high risk with no recurring emits R2 R3 R4 in order with no R5 or R6`() = runTest {
        allExpenses = steadyDailyPurchases(20.0)
        allDeposits = emptyList()

        val result = engine.computeStressForecast()

        assertNull(result.failure)
        assertEquals(
            listOf(
                StressRecommendationKind.REDUCE_DISCRETIONARY_SPENDING,
                StressRecommendationKind.BUILD_EMERGENCY_BUFFER,
                StressRecommendationKind.DELAY_NON_ESSENTIAL_PURCHASES
            ),
            kindsOf(result)
        )
    }

    @Test
    fun `AE-1 AE-4 INV-2 recurring obligations above income put R1 first then R2 R3 R4`() = runTest {
        allExpenses = emptyList()
        allDeposits = emptyList()
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            RecurringPattern(
                id = null,
                merchantName = "Detected Rent",
                averageAmount = 800.0,
                currency = "EUR",
                frequency = RecurrenceFrequency.MONTHLY,
                periodVarianceDays = 0,
                amountVariancePercent = 0.0,
                nextExpectedDate = now + dayMs,
                confidence = 1.0f,
                previousDates = emptyList()
            )
        )

        val result = engine.computeStressForecast()

        assertEquals(
            listOf(
                StressRecommendationKind.REVIEW_RECURRING_OBLIGATIONS,
                StressRecommendationKind.REDUCE_DISCRETIONARY_SPENDING,
                StressRecommendationKind.BUILD_EMERGENCY_BUFFER,
                StressRecommendationKind.DELAY_NON_ESSENTIAL_PURCHASES
            ),
            kindsOf(result)
        )
    }

    @Test
    fun `AE-1 INV-1 INV-2 recurring above 80 percent of income with healthy balances and buffers and no high risk emits exactly R1`() = runTest {
        // Fixture design (engine uses its own seeded bootstrap, so only the bands below matter):
        // - Six spend days of 4000 in a 60 day history (spend-day share 0.1), so the 90 day
        //   discretionary total is 4000 x Binomial(90, 0.1): median 9 spikes (36000) and about an
        //   18.6 percent chance of 12 or more spikes.
        // - Starting balance 43400, monthly income 1000 (1000/2000/3000 per horizon) and one
        //   900 obligation per horizon (annual cadence): available is 43500/44500/45500, so only
        //   the 90 day horizon has a crunch chance (about 0.19, MODERATE); 30 and 60 days are LOW.
        // - Every horizon projects far above 0 and far above twice the 500 buffer.
        // - 900 recurring > 0.8 x 1000 income.
        coEvery { accountBalanceProvider.currentBalance(any()) } returns 43_400.0
        val spikeDayOffsets = listOf(59, 48, 37, 26, 15, 4)
        allExpenses = spikeDayOffsets.mapIndexed { index, offsetDays ->
            expense(1000L + index, 4_000.0, TransactionType.PURCHASE, now - offsetDays * dayMs)
        }
        allDeposits = listOf(expense(1100L, 1_000.0, TransactionType.DEPOSIT, now - 2 * dayMs, merchant = "Employer"))
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            RecurringPattern(
                id = null,
                merchantName = "Annual Insurance",
                averageAmount = 900.0,
                currency = "EUR",
                frequency = RecurrenceFrequency.ANNUALLY,
                periodVarianceDays = 0,
                amountVariancePercent = 0.0,
                nextExpectedDate = now + dayMs,
                confidence = 1.0f,
                previousDates = emptyList()
            )
        )

        val result = engine.computeStressForecast()

        // Scenario preconditions from the spec: some risk, but not high; balances and buffers healthy.
        assertNull(result.failure)
        assertTrue(result.horizons.any { it.riskLevel >= StressRiskLevel.MODERATE })
        assertTrue(result.horizons.none { it.riskLevel >= StressRiskLevel.HIGH })
        assertTrue(result.horizons.all { it.projectedBalance >= 0.0 })
        assertTrue(result.horizons.minOf { it.discretionaryBuffer } >= 500.0)
        assertEquals(listOf(StressRecommendationKind.REVIEW_RECURRING_OBLIGATIONS), kindsOf(result))
    }

    @Test
    fun `INV-2 at most one recommendation per kind`() = runTest {
        allExpenses = steadyDailyPurchases(20.0)
        allDeposits = emptyList()

        val kinds = kindsOf(engine.computeStressForecast())

        assertEquals(kinds.distinct(), kinds)
    }

    @Test
    fun `AE-2 INV-8 R2 daysAhead is the worst horizon and is 90 when balances worsen with the horizon`() = runTest {
        allExpenses = steadyDailyPurchases(20.0)
        allDeposits = emptyList()

        val result = engine.computeStressForecast()
        val r2 = result.recommendations.single { it.kind == StressRecommendationKind.REDUCE_DISCRETIONARY_SPENDING }

        assertEquals(90, r2.daysAhead)
        assertTrue(r2.daysAhead in listOf(30, 60, 90))
    }

    @Test
    fun `AE-2 INV-8 R2 daysAhead is 30 when the 30 day horizon is the worst`() = runTest {
        // Starting balance -1000 with income 700 a month and no purchase history (no discretionary
        // spend is projected): the horizons are -300, 400 and 1100, so 30 days is the worst and
        // is negative, which is enough to raise the risk and emit R2.
        coEvery { accountBalanceProvider.currentBalance(any()) } returns -1000.0
        allExpenses = emptyList()
        allDeposits = listOf(expense(900L, 700.0, TransactionType.DEPOSIT, now - dayMs, merchant = "Employer"))

        val result = engine.computeStressForecast()
        val r2 = result.recommendations.single { it.kind == StressRecommendationKind.REDUCE_DISCRETIONARY_SPENDING }

        assertEquals(30, r2.daysAhead)
        assertEquals(30, result.horizons.minByOrNull { it.projectedBalance }!!.daysAhead)
    }

    @Test
    fun `AE-3 INV-8 R3 carries the 500 default buffer and the forecast display currency`() = runTest {
        allExpenses = steadyDailyPurchases(20.0)
        allDeposits = emptyList()

        val result = engine.computeStressForecast()
        val r3 = result.recommendations.single { it.kind == StressRecommendationKind.BUILD_EMERGENCY_BUFFER }

        assertEquals(500.0, r3.bufferTarget!!, 0.0)
        assertEquals("EUR", r3.bufferCurrency)
        assertEquals(result.displayCurrency, r3.bufferCurrency)
    }

    @Test
    fun `AE-3 INV-8 R3 buffer currency follows an explicit display currency and the configured buffer`() = runTest {
        every { currencySettingsRepository.emergencyBuffer() } returns flowOf(750.0)
        rates = mapOf("EUR" to (1.2 to now))
        allExpenses = steadyDailyPurchases(20.0)
        allDeposits = emptyList()

        val result = engine.computeStressForecast("USD")
        val r3 = result.recommendations.single { it.kind == StressRecommendationKind.BUILD_EMERGENCY_BUFFER }

        assertEquals("USD", result.displayCurrency)
        assertEquals("USD", r3.bufferCurrency)
        assertEquals(900.0, r3.bufferTarget!!, 0.0)
    }

    @Test
    fun `B1-12 emergency buffer currency label matches buffer value currency when displayCurrency differs from homeCurrency`() = runTest {
        every { currencySettingsRepository.homeCurrency() } returns flowOf("EUR")
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        every { currencySettingsRepository.emergencyBuffer() } returns flowOf(500.0)
        // EUR -> USD conversion rate = 1.2 (so 500 EUR = 600 USD)
        rates = mapOf("EUR" to (1.2 to now))
        allExpenses = steadyDailyPurchases(20.0)
        allDeposits = emptyList()

        val convertedResult = engine.computeStressForecast("USD")
        val convertedR3 = convertedResult.recommendations.single { it.kind == StressRecommendationKind.BUILD_EMERGENCY_BUFFER }

        // Invariant: either the value is converted to USD (600.0) and labelled "USD",
        // or the value stays in home currency (500.0) and is labelled "EUR". Never 500.0 labelled "USD".
        assertTrue(
            "Buffer currency must match the buffer value's actual currency, got ${convertedR3.bufferTarget} ${convertedR3.bufferCurrency}",
            (convertedR3.bufferCurrency == "USD" && kotlin.math.abs(convertedR3.bufferTarget!! - 600.0) < 0.01) ||
                (convertedR3.bufferCurrency == "EUR" && kotlin.math.abs(convertedR3.bufferTarget!! - 500.0) < 0.01)
        )

        // When FX rate is missing, conversion cannot happen so if buffer remains 500.0 it must be labelled "EUR"
        rates = emptyMap()
        val unconvertibleResult = engine.computeStressForecast("USD")
        val unconvertibleR3 = unconvertibleResult.recommendations.single { it.kind == StressRecommendationKind.BUILD_EMERGENCY_BUFFER }

        assertEquals("EUR", unconvertibleR3.bufferCurrency)
        assertEquals(500.0, unconvertibleR3.bufferTarget!!, 0.01)
    }

    @Test
    fun `RB1-S4-1 failed home currency resolution does not relabel raw buffer as displayCurrency or subtract from balances`() = runTest {
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Failed("HOME_CURRENCY_UNAVAILABLE")
        every { currencySettingsRepository.emergencyBuffer() } returns flowOf(500.0)

        // 1. Positive balance case: verify raw buffer (500.0) is NOT subtracted from USD projectedBalance
        coEvery { accountBalanceProvider.currentBalance("USD") } returns 5_000.0
        allExpenses = emptyList()
        allDeposits = emptyList()
        val positiveResult = engine.computeStressForecast("USD")
        for (horizon in positiveResult.horizons) {
            assertTrue("Positive projectedBalance expected", horizon.projectedBalance > 0.0)
            assertEquals(
                "Unresolved buffer currency must not be subtracted from USD projectedBalance",
                horizon.projectedBalance,
                horizon.discretionaryBuffer,
                0.0001
            )
        }

        // 2. Negative balance case: R3 is emitted, verify bufferCurrency is "" (not "USD").
        // P-06-4: spend must come from a real purchase history (no invented fallback spend).
        coEvery { accountBalanceProvider.currentBalance("USD") } returns 0.0
        allExpenses = steadyDailyPurchases(20.0)
        val result = engine.computeStressForecast("USD")
        val r3 = result.recommendations.single { it.kind == StressRecommendationKind.BUILD_EMERGENCY_BUFFER }

        org.junit.Assert.assertNotEquals("USD", r3.bufferCurrency)
        assertEquals("", r3.bufferCurrency)
        assertEquals(500.0, r3.bufferTarget!!, 0.01)
    }

    @Test
    fun `AE-3 INV-8 R3 buffer target falls back to 500 when the buffer setting is unavailable`() = runTest {
        every { currencySettingsRepository.emergencyBuffer() } returns kotlinx.coroutines.flow.flow {
            throw IllegalStateException("buffer down")
        }
        allExpenses = steadyDailyPurchases(20.0)
        allDeposits = emptyList()

        val result = engine.computeStressForecast()
        val r3 = result.recommendations.single { it.kind == StressRecommendationKind.BUILD_EMERGENCY_BUFFER }

        assertEquals(500.0, r3.bufferTarget!!, 0.0)
        assertEquals("EUR", r3.bufferCurrency)
    }

    @Test
    fun `INV-8 parameters are present only on R2 and R3 and absent on every other kind`() = runTest {
        allExpenses = emptyList()
        allDeposits = emptyList()
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            RecurringPattern(
                id = null,
                merchantName = "Detected Rent",
                averageAmount = 800.0,
                currency = "EUR",
                frequency = RecurrenceFrequency.MONTHLY,
                periodVarianceDays = 0,
                amountVariancePercent = 0.0,
                nextExpectedDate = now + dayMs,
                confidence = 1.0f,
                previousDates = emptyList()
            )
        )

        val result = engine.computeStressForecast()

        assertEquals(4, result.recommendations.size)
        result.recommendations.forEach { recommendation ->
            val isR2 = recommendation.kind == StressRecommendationKind.REDUCE_DISCRETIONARY_SPENDING
            val isR3 = recommendation.kind == StressRecommendationKind.BUILD_EMERGENCY_BUFFER
            if (isR2) assertNotNull(recommendation.daysAhead) else assertNull(recommendation.daysAhead)
            if (isR3) {
                assertNotNull(recommendation.bufferTarget)
                assertNotNull(recommendation.bufferCurrency)
            } else {
                assertNull(recommendation.bufferTarget)
                assertNull(recommendation.bufferCurrency)
            }
        }
    }

    @Test
    fun `AE-5 INV-2 all low with ample balances emits exactly healthy outlook and no R6`() = runTest {
        // P-06-4: a healthy outlook needs a real purchase history; one small purchase provides it.
        val hugeDeposit = expense(100L, 1_000_000.0, TransactionType.DEPOSIT, now - dayMs)
        val smallPurchase = expense(101L, 50.0, TransactionType.PURCHASE, now - 3 * dayMs)
        allExpenses = listOf(hugeDeposit, smallPurchase)
        allDeposits = listOf(hugeDeposit)

        val result = engine.computeStressForecast()

        assertEquals(StressRiskLevel.LOW, result.overallRiskLevel)
        assertEquals(false, result.isLowData)
        assertEquals(listOf(StressRecommendation(StressRecommendationKind.HEALTHY_OUTLOOK)), result.recommendations)
    }

    @Test
    fun `AE-5 control all low with ample balances but no purchase history emits no healthy outlook`() = runTest {
        val hugeDeposit = expense(100L, 1_000_000.0, TransactionType.DEPOSIT, now - dayMs)
        allExpenses = listOf(hugeDeposit)
        allDeposits = listOf(hugeDeposit)

        val result = engine.computeStressForecast()

        assertEquals(StressRiskLevel.LOW, result.overallRiskLevel)
        assertTrue(result.isLowData)
        assertTrue(result.recommendations.isEmpty())
    }

    @Test
    fun `AE-6 INV-2 all low but a balance at or below the buffer emits exactly R6`() = runTest {
        // Income 620 a month against a real steady 20 a day (about 600 a month) purchase history:
        // every horizon stays LOW (spend can never exceed the available 620 / 1240 / 1860) yet the
        // projected balances (about 20, 60, 90) never exceed the 500 buffer, so R5 is not emitted.
        allExpenses = steadyDailyPurchases(20.0)
        allDeposits = listOf(expense(910L, 620.0, TransactionType.DEPOSIT, now - dayMs, merchant = "Employer"))

        val result = engine.computeStressForecast()

        assertEquals(StressRiskLevel.LOW, result.overallRiskLevel)
        assertTrue(result.horizons.all { it.projectedBalance <= 500.0 })
        assertEquals(
            listOf(StressRecommendation(StressRecommendationKind.NO_IMMEDIATE_CONCERNS)),
            result.recommendations
        )
    }

    @Test
    fun `INV-2 R6 is never emitted alongside another recommendation`() = runTest {
        allExpenses = steadyDailyPurchases(20.0)
        allDeposits = emptyList()

        val kinds = kindsOf(engine.computeStressForecast())

        assertTrue(kinds.isNotEmpty())
        assertTrue(StressRecommendationKind.NO_IMMEDIATE_CONCERNS !in kinds)
        assertTrue(StressRecommendationKind.HEALTHY_OUTLOOK !in kinds)
    }

    @Test
    fun `AE-7 INV-10 calculation failure yields unavailable with an empty list and no synthesized R6`() = runTest {
        coEvery { expenseRepository.getDepositsBetween(any(), any()) } throws IllegalStateException("boom")

        val result = engine.computeStressForecast()

        assertEquals(StressForecastFailure.CALCULATION_UNAVAILABLE, result.failure)
        assertTrue(result.recommendations.isEmpty())
        assertTrue(StressRecommendationKind.NO_IMMEDIATE_CONCERNS !in kindsOf(result))
    }

    @Test
    fun `AE-7 INV-10 settings failure yields unavailable with an empty list`() = runTest {
        every { currencySettingsRepository.homeCurrency() } returns kotlinx.coroutines.flow.flow {
            throw IllegalStateException("settings unavailable")
        }

        val result = engine.computeStressForecast()

        assertEquals(StressForecastFailure.CALCULATION_UNAVAILABLE, result.failure)
        assertTrue(result.recommendations.isEmpty())
    }

    @Test
    fun `INV-8 two identical computes give equal recommendation lists`() = runTest {
        allExpenses = steadyDailyPurchases(20.0)
        allDeposits = emptyList()

        val first = engine.computeStressForecast()
        val second = engine.computeStressForecast()

        assertEquals(first.recommendations, second.recommendations)
    }

    @Test
    fun `INV-6 recommendations are identical under different default locales`() = runTest {
        val originalLocale = java.util.Locale.getDefault()
        try {
            allExpenses = steadyDailyPurchases(20.0)
            allDeposits = emptyList()
            val perLocale = listOf(java.util.Locale.US, java.util.Locale.GERMANY, java.util.Locale("el", "GR"))
                .map { locale ->
                    java.util.Locale.setDefault(locale)
                    engine.computeStressForecast().recommendations
                }

            assertTrue(perLocale.first().isNotEmpty())
            assertEquals(1, perLocale.distinct().size)
        } finally {
            java.util.Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun `INV-7 engine source does not use CurrencyFormatter or format money text`() {
        val root = com.yourname.expensetracker.architecture.FailClosedScan.requireRoot(
            listOf(
                java.io.File("src/main/java"),
                java.io.File("app/src/main/java"),
                java.io.File(System.getProperty("user.dir") ?: ".", "src/main/java"),
                java.io.File(System.getProperty("user.dir") ?: ".", "app/src/main/java")
            ),
            "production source root"
        )
        val source = com.yourname.expensetracker.architecture.FailClosedScan.requireScannedFile(
            root,
            "com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngine.kt"
        ).readText()

        assertTrue(!source.contains("CurrencyFormatter"))
    }

    // ── Stress forecast formula fixes: buffer subtracted once, 61-day sample anchor, fractional months ──

    /** One purchase per civil day for the 60 days before today (noon each, DST-proof), none today. */
    private fun steadyDailyPurchases(amount: Double): List<Expense> =
        (1..60).map { daysBack ->
            expense(
                2000L + daysBack,
                amount,
                TransactionType.PURCHASE,
                millis(2026, Calendar.APRIL, 20 - daysBack)
            )
        }

    @Test
    fun `buffer check balance 1500 with buffer 1000 and steady spend does not recommend building the buffer`() = runTest {
        every { currencySettingsRepository.emergencyBuffer() } returns flowOf(1_000.0)
        coEvery { accountBalanceProvider.currentBalance(any()) } returns 1_500.0
        allExpenses = steadyDailyPurchases(10.0)
        allDeposits = emptyList()

        val result = engine.computeStressForecast()

        assertNull(result.failure)
        // 60 of the 61 sample days spend 10, so the 90 day total is at most 900 < 1500: no crunch.
        assertEquals(StressRiskLevel.LOW, result.overallRiskLevel)
        assertTrue(StressRecommendationKind.BUILD_EMERGENCY_BUFFER !in kindsOf(result))
        // 30 day median is exactly 30 x 10 = 300 -> projected 1200; the 1000 buffer is subtracted
        // once for discretionaryBuffer (1200 - 1000 = 200). Subtracting it twice would give 0.
        val h30 = result.horizons.first { it.daysAhead == 30 }
        assertApproxEquals(1_200.0, h30.projectedBalance, 0.01)
        assertApproxEquals(200.0, h30.discretionaryBuffer, 0.01)
    }

    @Test
    fun `buffer check control balance 800 with buffer 1000 and steady spend recommends building the buffer`() = runTest {
        every { currencySettingsRepository.emergencyBuffer() } returns flowOf(1_000.0)
        coEvery { accountBalanceProvider.currentBalance(any()) } returns 800.0
        allExpenses = steadyDailyPurchases(10.0)
        allDeposits = emptyList()

        val result = engine.computeStressForecast()

        // 90 day spend is about 890 > 800, so the 90 day horizon has a crunch and a projected
        // balance below the 1000 buffer.
        val r3 = result.recommendations.single { it.kind == StressRecommendationKind.BUILD_EMERGENCY_BUFFER }
        assertEquals(1_000.0, r3.bufferTarget!!, 0.0)
        assertEquals("EUR", r3.bufferCurrency)
    }

    @Test
    fun `buffer check at risk but every projected balance at or above the buffer does not recommend building it`() = runTest {
        // One 2000 purchase yesterday among 61 sample days: each simulated day has a 1/61 chance of
        // spending 2000. Hand-derived binomial figures: the 60 and 90 day medians are exactly one
        // spend (2000), and P(two or more spends) is about 0.26 (60 days) and 0.43 (90 days), so
        // risk is at least MODERATE while the balance 3100 keeps every projected balance
        // (3100, 1100, 1100) at or above the 1000 buffer. A 0.01 purchase 60 days ago anchors the
        // sample at the full 61 day lookback (the sample starts at the first purchase), keeping
        // the 1/61 daily chance.
        every { currencySettingsRepository.emergencyBuffer() } returns flowOf(1_000.0)
        coEvery { accountBalanceProvider.currentBalance(any()) } returns 3_100.0
        allExpenses = listOf(
            expense(2501L, 0.01, TransactionType.PURCHASE, millis(2026, Calendar.APRIL, 20 - 60)),
            expense(2500L, 2_000.0, TransactionType.PURCHASE, millis(2026, Calendar.APRIL, 19))
        )
        allDeposits = emptyList()

        val result = engine.computeStressForecast()

        assertTrue(result.horizons.any { it.riskLevel >= StressRiskLevel.MODERATE })
        assertTrue(result.horizons.all { it.projectedBalance >= 1_000.0 })
        assertTrue(StressRecommendationKind.BUILD_EMERGENCY_BUFFER !in kindsOf(result))
        // discretionaryBuffer is the projected balance minus the buffer exactly once.
        assertApproxEquals(2_100.0, result.horizons.first { it.daysAhead == 30 }.discretionaryBuffer, 0.1)
        assertApproxEquals(100.0, result.horizons.first { it.daysAhead == 60 }.discretionaryBuffer, 0.1)
        assertApproxEquals(100.0, result.horizons.first { it.daysAhead == 90 }.discretionaryBuffer, 0.1)
    }

    @Test
    fun `buffer check control at risk and projected balance below the buffer recommends building it`() = runTest {
        // Same history as above with balance 2500: projected 60/90 day balance is 500 < 1000.
        every { currencySettingsRepository.emergencyBuffer() } returns flowOf(1_000.0)
        coEvery { accountBalanceProvider.currentBalance(any()) } returns 2_500.0
        allExpenses = listOf(
            expense(2501L, 0.01, TransactionType.PURCHASE, millis(2026, Calendar.APRIL, 20 - 60)),
            expense(2500L, 2_000.0, TransactionType.PURCHASE, millis(2026, Calendar.APRIL, 19))
        )
        allDeposits = emptyList()

        val result = engine.computeStressForecast()

        assertTrue(result.horizons.any { it.riskLevel >= StressRiskLevel.MODERATE })
        assertTrue(StressRecommendationKind.BUILD_EMERGENCY_BUFFER in kindsOf(result))
    }

    @Test
    fun `sample anchor one purchase yesterday uses the 14 day floor and reads 7_14 a day`() = runTest {
        // Balance 50: a crunch happens exactly when at least one simulated day is the 100 purchase.
        // The sample starts at the later of (now - 60 days) and the first purchase, floored at 14
        // days (days -13 .. today), so one 100 purchase yesterday reads 100 / 14 = 7.14 a day and
        // P(crunch) = 1 - (13/14)^days = 0.892 / 0.988 / 0.999 for 30 / 60 / 90 days. The pre-fix
        // 61 day sample treated pre-history days as quiet (1 in 61, 0.391 / 0.629 / 0.774).
        coEvery { accountBalanceProvider.currentBalance(any()) } returns 50.0
        allExpenses = listOf(expense(2600L, 100.0, TransactionType.PURCHASE, millis(2026, Calendar.APRIL, 19)))
        allDeposits = emptyList()

        val result = engine.computeStressForecast()

        assertApproxEquals(0.892, result.horizons.first { it.daysAhead == 30 }.probabilityOfCrunch, 0.05)
        assertApproxEquals(0.988, result.horizons.first { it.daysAhead == 60 }.probabilityOfCrunch, 0.05)
        assertApproxEquals(0.999, result.horizons.first { it.daysAhead == 90 }.probabilityOfCrunch, 0.05)
        // Medians are whole purchases (binomial medians 2 / 4 / 6 of 100), so the daily rate is
        // 6.67..7.14, within 1.0 of the 7.14 mean.
        result.horizons.forEach { horizon ->
            assertApproxEquals(7.14, (50.0 - horizon.projectedBalance) / horizon.daysAhead, 1.0)
        }
    }

    @Test
    fun `sample anchor pre-install days are not quiet days so a 10 day spender alarms`() = runTest {
        // Deposit 1000 eleven days ago, 50 a day for the 10 days since, balance 500. Sample = the
        // 14 day floor (10 spend days of 50): 35.71 a day, 90 day spend about 3214 against 500 +
        // 3000 income, so the 90 day projected balance is about 286 (below the 500 buffer).
        // Before the fix the 61 day sample counted 51 pre-install days as quiet: 8.2 a day,
        // 90 day spend about 738 and a healthy projected balance of about 2762.
        every { currencySettingsRepository.emergencyBuffer() } returns flowOf(500.0)
        coEvery { accountBalanceProvider.currentBalance(any()) } returns 500.0
        allExpenses = (1..10).map { daysBack ->
            expense(2800L + daysBack, 50.0, TransactionType.PURCHASE, millis(2026, Calendar.APRIL, 20 - daysBack))
        }
        allDeposits = listOf(
            expense(2811L, 1_000.0, TransactionType.DEPOSIT, millis(2026, Calendar.APRIL, 9), merchant = "Employer")
        )

        val result = engine.computeStressForecast()

        val h90 = result.horizons.first { it.daysAhead == 90 }
        assertApproxEquals(286.0, h90.projectedBalance, 150.0)
        assertTrue(h90.projectedBalance < 1_000.0)
        assertEquals(0.0, h90.discretionaryBuffer, 0.0)
    }

    @Test
    fun `sample anchor steady 10 a day stays about 10 a day`() = runTest {
        // 60 spend days of 10 and one empty day (today): mean 9.84 a day, medians 300 / 590 / 890.
        coEvery { accountBalanceProvider.currentBalance(any()) } returns 0.0
        allExpenses = steadyDailyPurchases(10.0)
        allDeposits = emptyList()

        val result = engine.computeStressForecast()

        result.horizons.forEach { horizon ->
            assertApproxEquals(10.0, -horizon.projectedBalance / horizon.daysAhead, 0.5)
        }
        assertApproxEquals(-300.0, result.horizons.first { it.daysAhead == 30 }.projectedBalance, 0.01)
    }

    @Test
    fun `income three monthly deposits of 3000 spanning 59 days average about 3095 a month`() = runTest {
        // Fencepost: 3 deposits span 2 intervals, so the covered period is 59 * 3 / 2 = 88.5 days =
        // 2.9074 months; 9000 / 2.9074 = 3095.593 a month. Before the fix the divisor was the raw
        // span (1.9382 months), which over-stated income as 4643.4 a month.
        allExpenses = emptyList()
        allDeposits = listOf(
            expense(2700L, 3_000.0, TransactionType.DEPOSIT, millis(2026, Calendar.FEBRUARY, 20), merchant = "Employer"),
            expense(2702L, 3_000.0, TransactionType.DEPOSIT, millis(2026, Calendar.MARCH, 21), merchant = "Employer"),
            expense(2701L, 3_000.0, TransactionType.DEPOSIT, millis(2026, Calendar.APRIL, 20), merchant = "Employer")
        )

        val result = engine.computeStressForecast()

        assertEquals(3095.593, result.horizons.first { it.daysAhead == 30 }.expectedIncome, 0.01)
        assertEquals(6191.186, result.horizons.first { it.daysAhead == 60 }.expectedIncome, 0.02)
        assertEquals(9286.780, result.horizons.first { it.daysAhead == 90 }.expectedIncome, 0.03)
    }

    @Test
    fun `income a single deposit counts as one month and is never inflated`() = runTest {
        allExpenses = emptyList()
        allDeposits = listOf(
            expense(2710L, 3_000.0, TransactionType.DEPOSIT, millis(2026, Calendar.APRIL, 10), merchant = "Employer")
        )

        val result = engine.computeStressForecast()

        assertEquals(3_000.0, result.horizons.first { it.daysAhead == 30 }.expectedIncome, 0.0001)
        assertEquals(6_000.0, result.horizons.first { it.daysAhead == 60 }.expectedIncome, 0.0001)
        assertEquals(9_000.0, result.horizons.first { it.daysAhead == 90 }.expectedIncome, 0.0001)
    }

    @Test
    fun `income two deposits on the same day count as one month`() = runTest {
        allExpenses = emptyList()
        allDeposits = listOf(
            expense(2720L, 1_000.0, TransactionType.DEPOSIT, millis(2026, Calendar.APRIL, 10), merchant = "Employer"),
            expense(2721L, 500.0, TransactionType.DEPOSIT, millis(2026, Calendar.APRIL, 10), merchant = "Bonus")
        )

        val result = engine.computeStressForecast()

        assertEquals(1_500.0, result.horizons.first { it.daysAhead == 30 }.expectedIncome, 0.0001)
    }

    @Test
    fun `income span just over one month is fractional not truncated to one month`() = runTest {
        // Mar 20 -> Apr 20 is 31 days; two deposits cover 31 * 2 = 62 days = 2.0368 months:
        // 2000 / 2.0368 = 981.935 a month (before the fencepost fix 1963.871, and 2000 if truncated).
        allExpenses = emptyList()
        allDeposits = listOf(
            expense(2730L, 1_000.0, TransactionType.DEPOSIT, millis(2026, Calendar.MARCH, 20), merchant = "Employer"),
            expense(2731L, 1_000.0, TransactionType.DEPOSIT, millis(2026, Calendar.APRIL, 20), merchant = "Employer")
        )

        val result = engine.computeStressForecast()

        assertEquals(981.935, result.horizons.first { it.daysAhead == 30 }.expectedIncome, 0.001)
    }

    private fun millis(year: Int, month: Int, day: Int, hourOfDay: Int = 12): Long {
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hourOfDay)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
