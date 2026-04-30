package com.yourname.expensetracker.domain.usecase.dashboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.R
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.domain.forecasting.MergedRecurringPatternsProvider
import com.yourname.expensetracker.domain.forecasting.ConfidenceLevel
import com.yourname.expensetracker.domain.forecasting.MonteCarloResult
import com.yourname.expensetracker.domain.forecasting.MonteCarloSpendingSimulator
import com.yourname.expensetracker.domain.forecasting.SimulationConfidence
import com.yourname.expensetracker.domain.forecasting.SimulationMetadata
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.model.RecurringPattern
import com.yourname.expensetracker.domain.model.Result
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.model.budget.MonteCarloBudgetImpact
import com.yourname.expensetracker.domain.model.budget.MonteCarloBudgetImpact.RiskTier
import com.yourname.expensetracker.domain.text.DomainTextKeys
import com.yourname.expensetracker.domain.usecase.budget.GetMonteCarloBudgetImpactUseCase
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.ui.components.asString
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ComputeMoneyRadarUseCaseTest {

    private lateinit var mergedRecurringPatternsProvider: MergedRecurringPatternsProvider
    private lateinit var context: Context
    private lateinit var anomalyAlertRepository: AnomalyAlertRepository
    private lateinit var getMonteCarloBudgetImpact: GetMonteCarloBudgetImpactUseCase
    private lateinit var monteCarloSimulator: MonteCarloSpendingSimulator
    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var budgetRepository: BudgetRepository
    private lateinit var timeProvider: TimeProvider
    private lateinit var useCase: ComputeMoneyRadarUseCase

    private val now = 1_710_000_000_000L
    private val dayMs = 24 * 60 * 60 * 1000L

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        mergedRecurringPatternsProvider = mockk()
        anomalyAlertRepository = mockk()
        getMonteCarloBudgetImpact = mockk()
        monteCarloSimulator = mockk()
        expenseRepository = mockk()
        budgetRepository = mockk()
        timeProvider = mockk()

        useCase = ComputeMoneyRadarUseCase(
            recurringPatternsProvider = mergedRecurringPatternsProvider,
            anomalyAlertRepository = anomalyAlertRepository,
            getMonteCarloBudgetImpact = getMonteCarloBudgetImpact,
            monteCarloSimulator = monteCarloSimulator,
            expenseRepository = expenseRepository,
            budgetRepository = budgetRepository,
            timeProvider = timeProvider
        )

        every { timeProvider.now() } returns now
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns emptyList()
    }

    @Test
    fun `compute applies weighted urgency factors and emits RED with critical budget CTA`() = runTest {
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            recurring("Rent", 600.0, now + dayMs),
            recurring("Internet", 50.0, now + 2 * dayMs),
            recurring("Gym", 40.0, now + 3 * dayMs)
        )
        coEvery { anomalyAlertRepository.getActiveAlerts() } returns listOf(
            anomaly("Odd Coffee", 8.0, now - dayMs),
            anomaly("Unknown Charge", 120.0, now - 2 * dayMs)
        )
        every { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(overallBudgetStatus(1000.0)))
        coEvery { expenseRepository.getExpensesSince(any()) } returns listOf(
            expense(300.0, TransactionType.PURCHASE, now - 5 * dayMs)
        )
        coEvery { expenseRepository.getTotalDepositsForPeriod(any(), any()) } returns 1000.0

        val mcResult = monteCarloResult(probabilityUnderBudget = 0.20)
        coEvery { monteCarloSimulator.simulate(any(), any(), any()) } returns mcResult

        val impact = MonteCarloBudgetImpact(
            budgetAmount = 1000.0,
            p50Forecast = 1150.0,
            expectedOverrun = 150.0,
            probabilityOfOverrun = 0.80,
            riskTier = RiskTier.CRITICAL
        )
        every { getMonteCarloBudgetImpact(1000.0, mcResult) } returns Result.Success(impact)

        val result = useCase.compute()

        // Due bills: 3 with large-bill bonus => 80, anomalies: 2 => 60,
        // budget risk: probability 80 + magnitude 10 + critical 20 => capped at 100.
        // Weighted = 80*0.4 + 60*0.3 + 100*0.3 = 80
        assertEquals(80, result.urgencyScore)
        assertEquals(UrgencyLevel.RED, result.urgencyLevel)
        assertTrue(result.topReasons.any {
            it == UiText.MessageKey(
                DomainTextKeys.COMPUTE_MONEY_RADAR_REASON_MULTIPLE_BILLS_DUE_FORMAT,
                listOf(3, 7)
            )
        })
        assertTrue(result.topReasons.any {
            it == UiText.MessageKey(
                DomainTextKeys.COMPUTE_MONEY_RADAR_REASON_MULTIPLE_ANOMALIES_FORMAT,
                listOf(2)
            )
        })
        assertTrue(result.topReasons.any {
            it == UiText.MessageKey(
                DomainTextKeys.COMPUTE_MONEY_RADAR_REASON_BUDGET_RISK_CRITICAL_FORMAT,
                listOf(80)
            )
        })
        assertTrue(result.primaryCta is MoneyRadarAction.AdjustBudget)
    }

    @Test
    fun `compute includes only bills due within next seven days`() = runTest {
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            recurring("DueToday", 20.0, now),
            recurring("DueInSeven", 30.0, now + 7 * dayMs),
            recurring("TooLate", 40.0, now + 8 * dayMs),
            recurring("AlreadyPast", 50.0, now - dayMs)
        )
        coEvery { anomalyAlertRepository.getActiveAlerts() } returns emptyList()
        every { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())
        coEvery { expenseRepository.getTotalDepositsForPeriod(any(), any()) } returns 5000.0

        val result = useCase.compute()

        assertEquals(listOf("DueToday", "DueInSeven"), result.dueBills.map { it.merchant })
        assertEquals(listOf(0, 7), result.dueBills.map { it.daysUntilDue })
    }

    @Test
    fun `compute includes recurring bill due earlier today as due today`() = runTest {
        val noonToday = millis(2026, java.util.Calendar.MARCH, 9, 12)
        val earlierToday = millis(2026, java.util.Calendar.MARCH, 9, 8)
        every { timeProvider.now() } returns noonToday
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            recurring("Morning Bill", 20.0, earlierToday)
        )
        coEvery { anomalyAlertRepository.getActiveAlerts() } returns emptyList()
        every { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())

        val result = useCase.compute()

        assertEquals(listOf("Morning Bill"), result.dueBills.map { it.merchant })
        assertEquals(listOf(0), result.dueBills.map { it.daysUntilDue })
    }

    @Test
    fun `compute aggregates unresolved anomalies from last thirty days only`() = runTest {
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns emptyList()
        coEvery { anomalyAlertRepository.getActiveAlerts() } returns listOf(
            anomaly("Recent", 12.0, now - dayMs),
            anomaly("OlderButValid", 25.0, now - 10 * dayMs),
            anomaly("TooOld", 99.0, now - 40 * dayMs)
        )
        every { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())

        val result = useCase.compute()

        assertEquals(2, result.anomalyAlerts.size)
        assertEquals(listOf("Recent", "OlderButValid"), result.anomalyAlerts.map { it.merchant })
        assertEquals(listOf(1, 10), result.anomalyAlerts.map { it.daysAgo })
    }

    @Test
    fun `compute passes recurring obligations into Monte Carlo knownUpcoming`() = runTest {
        val r1 = recurring("Rent", 100.0, now + dayMs)
        val r2 = recurring("Insurance", 50.0, now + 5 * dayMs)
        val outside = recurring("LateBill", 200.0, now + 12 * dayMs)
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns listOf(r1, r2, outside)
        coEvery { anomalyAlertRepository.getActiveAlerts() } returns emptyList()
        every { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(overallBudgetStatus(1000.0)))
        coEvery { expenseRepository.getTotalDepositsForPeriod(any(), any()) } returns 1000.0
        coEvery { expenseRepository.getExpensesSince(any()) } returns listOf(
            expense(300.0, TransactionType.PURCHASE, now - 4 * dayMs),
            expense(100.0, TransactionType.PURCHASE, now - 2 * dayMs, isNotMine = true),
            expense(999.0, TransactionType.DEPOSIT, now - 2 * dayMs)
        )

        val mcResult = monteCarloResult(probabilityUnderBudget = 0.6)
        var capturedSpentToDate = -1.0
        var capturedKnownUpcoming = -1.0
        var capturedBudgetAmount: Double? = null
        coEvery { monteCarloSimulator.simulate(any(), any(), any()) } answers {
            capturedSpentToDate = firstArg()
            capturedKnownUpcoming = secondArg()
            capturedBudgetAmount = thirdArg()
            mcResult
        }

        every { getMonteCarloBudgetImpact(any(), any()) } returns Result.Success(
            MonteCarloBudgetImpact(
                budgetAmount = 1000.0,
                p50Forecast = 980.0,
                expectedOverrun = 0.0,
                probabilityOfOverrun = 0.4,
                riskTier = RiskTier.MEDIUM
            )
        )

        val result = useCase.compute()

        assertNotNull(result.budgetRisk)
        assertApproxEquals(300.0, capturedSpentToDate, 0.0001)
        assertApproxEquals(150.0, capturedKnownUpcoming, 0.0001)
        assertApproxEquals(1000.0, capturedBudgetAmount ?: 0.0, 0.0001)
    }

    @Test
    fun `compute ignores unconfirmed detected recurring suggestions`() = runTest {
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns emptyList()
        coEvery { anomalyAlertRepository.getActiveAlerts() } returns emptyList()
        every { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())

        val result = useCase.compute()

        assertTrue(result.dueBills.isEmpty())
        assertEquals(0, result.urgencyScore)
        coVerify(exactly = 0) { mergedRecurringPatternsProvider.getPatterns() }
    }

    @Test
    fun `compute includes confirmed recurring obligations in money radar`() = runTest {
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            recurring("Confirmed Rent", 700.0, now + dayMs)
        )
        coEvery { anomalyAlertRepository.getActiveAlerts() } returns emptyList()
        every { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())
        coEvery { expenseRepository.getTotalDepositsForPeriod(any(), any()) } returns 2_000.0

        val result = useCase.compute()

        assertEquals(listOf("Confirmed Rent"), result.dueBills.map { it.merchant })
        assertTrue(result.urgencyScore > 0)
    }

    @Test
    fun `compute includes recurring bill due earlier today in budget risk urgency path`() = runTest {
        val noonToday = millis(2026, java.util.Calendar.MARCH, 9, 12)
        val earlierToday = millis(2026, java.util.Calendar.MARCH, 9, 8)
        every { timeProvider.now() } returns noonToday
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            recurring("Morning Bill", 120.0, earlierToday)
        )
        coEvery { anomalyAlertRepository.getActiveAlerts() } returns emptyList()
        every { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(overallBudgetStatus(1000.0)))
        coEvery { expenseRepository.getExpensesSince(any()) } returns listOf(
            expense(300.0, TransactionType.PURCHASE, earlierToday - dayMs)
        )
        coEvery { expenseRepository.getTotalDepositsForPeriod(any(), any()) } returns 1000.0

        val mcResult = monteCarloResult(probabilityUnderBudget = 0.4)
        var capturedKnownUpcoming = -1.0
        coEvery { monteCarloSimulator.simulate(any(), any(), any()) } answers {
            capturedKnownUpcoming = secondArg()
            mcResult
        }
        every { getMonteCarloBudgetImpact(1000.0, mcResult) } returns Result.Success(
            MonteCarloBudgetImpact(
                budgetAmount = 1000.0,
                p50Forecast = 1120.0,
                expectedOverrun = 120.0,
                probabilityOfOverrun = 0.6,
                riskTier = RiskTier.HIGH
            )
        )

        val result = useCase.compute()

        assertEquals(listOf("Morning Bill"), result.dueBills.map { it.merchant })
        assertApproxEquals(120.0, capturedKnownUpcoming, 0.0001)
        assertNotNull(result.budgetRisk)
        assertEquals(51, result.urgencyScore)
        assertEquals(UrgencyLevel.YELLOW, result.urgencyLevel)
        assertTrue(result.topReasons.any {
            it == UiText.MessageKey(
                DomainTextKeys.COMPUTE_MONEY_RADAR_REASON_BUDGET_RISK_HIGH_FORMAT,
                listOf(60)
            )
        })
    }

    @Test
    fun `compute returns GREEN with healthy message when no bills alerts or budget`() = runTest {
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns emptyList()
        coEvery { anomalyAlertRepository.getActiveAlerts() } returns emptyList()
        every { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())

        val result = useCase.compute()

        assertEquals(0, result.urgencyScore)
        assertEquals(UrgencyLevel.GREEN, result.urgencyLevel)
        assertEquals(emptyList<UpcomingBill>(), result.dueBills)
        assertEquals(emptyList<AnomalyAlertSummary>(), result.anomalyAlerts)
        assertNull(result.budgetRisk)
        assertEquals(
            listOf(UiText.MessageKey(DomainTextKeys.COMPUTE_MONEY_RADAR_REASON_FINANCES_HEALTHY)),
            result.topReasons
        )
        assertNull(result.primaryCta)

        coVerify(exactly = 0) { monteCarloSimulator.simulate(any(), any(), any()) }
    }

    @Test
    fun `compute excludes future dated purchases from spent to date`() = runTest {
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns emptyList()
        coEvery { anomalyAlertRepository.getActiveAlerts() } returns emptyList()
        every { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(overallBudgetStatus(1000.0)))
        coEvery { expenseRepository.getExpensesSince(any()) } returns listOf(
            expense(300.0, TransactionType.PURCHASE, now - dayMs),
            expense(999.0, TransactionType.PURCHASE, now + dayMs)
        )
        coEvery { expenseRepository.getTotalDepositsForPeriod(any(), any()) } returns 1000.0

        var capturedSpentToDate = -1.0
        val mcResult = monteCarloResult(probabilityUnderBudget = 0.5)
        coEvery { monteCarloSimulator.simulate(any(), any(), any()) } answers {
            capturedSpentToDate = firstArg()
            mcResult
        }
        every { getMonteCarloBudgetImpact(any(), any()) } returns Result.Success(
            MonteCarloBudgetImpact(
                budgetAmount = 1000.0,
                p50Forecast = 980.0,
                expectedOverrun = 10.0,
                probabilityOfOverrun = 0.4,
                riskTier = RiskTier.MEDIUM
            )
        )

        useCase.compute()

        assertApproxEquals(300.0, capturedSpentToDate, 0.0001)
    }

    @Test
    fun `compute budget urgency changes with magnitude and risk tier`() = runTest {
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns emptyList()
        coEvery { anomalyAlertRepository.getActiveAlerts() } returns emptyList()
        every { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(overallBudgetStatus(1000.0)))
        coEvery { expenseRepository.getExpensesSince(any()) } returns listOf(
            expense(300.0, TransactionType.PURCHASE, now - dayMs)
        )
        coEvery { expenseRepository.getTotalDepositsForPeriod(any(), any()) } returns 1000.0
        val mcResult = monteCarloResult(probabilityUnderBudget = 0.6)
        coEvery { monteCarloSimulator.simulate(any(), any(), any()) } returns mcResult

        every { getMonteCarloBudgetImpact(1000.0, mcResult) } returns Result.Success(
            MonteCarloBudgetImpact(
                budgetAmount = 1000.0,
                p50Forecast = 1000.0,
                expectedOverrun = 5.0,
                probabilityOfOverrun = 0.4,
                riskTier = RiskTier.LOW
            )
        ) andThen Result.Success(
            MonteCarloBudgetImpact(
                budgetAmount = 1000.0,
                p50Forecast = 1300.0,
                expectedOverrun = 250.0,
                probabilityOfOverrun = 0.4,
                riskTier = RiskTier.CRITICAL
            )
        )

        val lowRisk = useCase.compute()
        val highRisk = useCase.compute()

        assertTrue(highRisk.urgencyScore > lowRisk.urgencyScore)
        assertTrue(highRisk.urgencyLevel >= lowRisk.urgencyLevel)
    }

    @Test
    fun `compute formats string placeholder reason with scalar merchant arg`() = runTest {
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            recurring("Netflix", 19.99, now + dayMs)
        )
        coEvery { anomalyAlertRepository.getActiveAlerts() } returns emptyList()
        every { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())

        val result = useCase.compute()

        assertEquals(
            "1 bill due soon: Netflix",
            result.topReasons.first().asString(context)
        )
    }

    @Test
    fun `compute uses merged recurring result to avoid duplicate stale bill inflation`() = runTest {
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            recurring("Netflix", 15.0, now + dayMs)
        )
        coEvery { anomalyAlertRepository.getActiveAlerts() } returns emptyList()
        every { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())

        val result = useCase.compute()

        assertEquals(listOf("Netflix"), result.dueBills.map { it.merchant })
        assertApproxEquals(15.0, result.dueBills.single().amount, 0.0001)
        assertEquals(
            "1 bill due soon: Netflix",
            result.topReasons.first().asString(context)
        )
    }

    @Test
    fun `compute formats integer placeholder reasons with scalar numeric args`() = runTest {
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns emptyList()
        coEvery { anomalyAlertRepository.getActiveAlerts() } returns listOf(
            anomaly("Coffee Shop", 8.0, now - dayMs),
            anomaly("Gas Station", 60.0, now - 2 * dayMs)
        )
        every { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(overallBudgetStatus(1000.0)))
        coEvery { expenseRepository.getExpensesSince(any()) } returns listOf(
            expense(300.0, TransactionType.PURCHASE, now - 5 * dayMs)
        )
        coEvery { expenseRepository.getTotalDepositsForPeriod(any(), any()) } returns 1000.0

        val mcResult = monteCarloResult(probabilityUnderBudget = 0.4)
        coEvery { monteCarloSimulator.simulate(any(), any(), any()) } returns mcResult
        every { getMonteCarloBudgetImpact(1000.0, mcResult) } returns Result.Success(
            MonteCarloBudgetImpact(
                budgetAmount = 1000.0,
                p50Forecast = 1100.0,
                expectedOverrun = 100.0,
                probabilityOfOverrun = 0.65,
                riskTier = RiskTier.HIGH
            )
        )

        val formattedReasons = useCase.compute().topReasons.map { it.asString(context) }

        assertTrue(formattedReasons.contains("2 unusual charges need review"))
        assertTrue(formattedReasons.contains("High risk of exceeding budget (65%)"))
    }

    private fun recurring(merchant: String, amount: Double, date: Long): RecurringPattern {
        return RecurringPattern(
            merchantName = merchant,
            averageAmount = amount,
            currency = "EUR",
            frequency = RecurrenceFrequency.MONTHLY,
            periodVarianceDays = 0,
            amountVariancePercent = 0.0,
            nextExpectedDate = date,
            confidence = 0.9f,
            previousDates = emptyList(),
            categoryId = null,
            id = null
        )
    }

    private fun anomaly(merchant: String, amount: Double, alertedAt: Long): AnomalyAlertRecord {
        return AnomalyAlertRecord(
            merchant = merchant,
            amount = amount,
            anomalyReason = "Unexpected amount",
            alertedAt = alertedAt
        )
    }

    private fun overallBudgetStatus(amount: Double): BudgetStatus {
        val budget = Budget(
            id = 10L,
            categoryId = null,
            amount = amount,
            period = BudgetPeriod.MONTHLY,
            startDate = now - 20 * dayMs,
            isActive = true
        )
        return BudgetStatus(
            budget = budget,
            category = null,
            spentAmount = 0.0,
            remainingAmount = amount,
            percentUsed = 0f,
            healthStatus = BudgetHealthStatus.ON_TRACK,
            periodStart = now - 20 * dayMs,
            periodEnd = now + 10 * dayMs
        )
    }

    private fun expense(
        amount: Double,
        type: TransactionType,
        date: Long,
        isNotMine: Boolean = false
    ): Expense {
        return Expense(
            id = date,
            amount = amount,
            currency = "EUR",
            merchant = "M",
            transactionType = type,
            date = date,
            categoryId = null,
            paymentMethod = PaymentMethod.CARD,
            isNotMine = isNotMine
        )
    }

    private fun monteCarloResult(probabilityUnderBudget: Double?): MonteCarloResult {
        return MonteCarloResult(
            percentile10 = 900.0,
            percentile25 = 950.0,
            percentile50 = 1000.0,
            percentile75 = 1100.0,
            percentile90 = 1200.0,
            probabilityUnderBudget = probabilityUnderBudget,
            budgetAmount = 1000.0,
            spentToDate = 300.0,
            knownUpcoming = 150.0,
            confidence = SimulationConfidence(
                score = 0.7,
                level = ConfidenceLevel.HIGH,
                reason = "sufficient history"
            ),
            metadata = SimulationMetadata(
                qualifyingWeeks = 10,
                totalWeeksExamined = 12,
                iterations = 1000,
                logNormalMu = 0.0,
                logNormalSigma = 1.0,
                daysRemaining = 10,
                computedAt = now
            ),
            displayCurrency = "EUR"
        )
    }

    private fun millis(year: Int, month: Int, day: Int, hourOfDay: Int): Long {
        return java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.YEAR, year)
            set(java.util.Calendar.MONTH, month)
            set(java.util.Calendar.DAY_OF_MONTH, day)
            set(java.util.Calendar.HOUR_OF_DAY, hourOfDay)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
