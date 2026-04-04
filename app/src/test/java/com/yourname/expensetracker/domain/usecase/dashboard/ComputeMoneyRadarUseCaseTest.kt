package com.yourname.expensetracker.domain.usecase.dashboard

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.dao.AnomalyAlertDao
import com.yourname.expensetracker.data.database.entity.AnomalyAlert
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.domain.forecasting.ConfidenceLevel
import com.yourname.expensetracker.domain.forecasting.MonteCarloResult
import com.yourname.expensetracker.domain.forecasting.MonteCarloSpendingSimulator
import com.yourname.expensetracker.domain.forecasting.SimulationConfidence
import com.yourname.expensetracker.domain.forecasting.SimulationMetadata
import com.yourname.expensetracker.domain.logic.RecurringExpenseEngine
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.model.RecurringPattern
import com.yourname.expensetracker.domain.model.Result
import com.yourname.expensetracker.domain.model.budget.MonteCarloBudgetImpact
import com.yourname.expensetracker.domain.model.budget.MonteCarloBudgetImpact.RiskTier
import com.yourname.expensetracker.domain.usecase.budget.GetMonteCarloBudgetImpactUseCase
import com.yourname.expensetracker.domain.util.TimeProvider
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

class ComputeMoneyRadarUseCaseTest {

    private lateinit var recurringExpenseEngine: RecurringExpenseEngine
    private lateinit var anomalyAlertDao: AnomalyAlertDao
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
        recurringExpenseEngine = mockk()
        anomalyAlertDao = mockk()
        getMonteCarloBudgetImpact = mockk()
        monteCarloSimulator = mockk()
        expenseRepository = mockk()
        budgetRepository = mockk()
        timeProvider = mockk()

        useCase = ComputeMoneyRadarUseCase(
            recurringExpenseEngine = recurringExpenseEngine,
            anomalyAlertDao = anomalyAlertDao,
            getMonteCarloBudgetImpact = getMonteCarloBudgetImpact,
            monteCarloSimulator = monteCarloSimulator,
            expenseRepository = expenseRepository,
            budgetRepository = budgetRepository,
            timeProvider = timeProvider
        )

        every { timeProvider.now() } returns now
    }

    @Test
    fun `compute applies weighted urgency factors and emits RED with critical budget CTA`() = runTest {
        coEvery { recurringExpenseEngine.getPatterns() } returns listOf(
            recurring("Rent", 600.0, now + dayMs),
            recurring("Internet", 50.0, now + 2 * dayMs),
            recurring("Gym", 40.0, now + 3 * dayMs)
        )
        coEvery { anomalyAlertDao.getActiveAlerts() } returns listOf(
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
            riskTier = RiskTier.CRITICAL,
            displayMessage = "Very likely to exceed budget by €150.00",
            formattedOverrun = "€150.00"
        )
        every { getMonteCarloBudgetImpact(1000.0, mcResult) } returns Result.Success(impact)

        val result = useCase.compute()

        // Due bills: 3 with large-bill bonus => 80, anomalies: 2 => 60, budget risk: 80
        // Weighted = 80*0.4 + 60*0.3 + 80*0.3 = 74
        assertEquals(74, result.urgencyScore)
        assertEquals(UrgencyLevel.RED, result.urgencyLevel)
        assertTrue(result.topReasons.any { it.contains("3 bills due") })
        assertTrue(result.topReasons.any { it.contains("2 unusual charges") })
        assertTrue(result.topReasons.any { it.contains("Critical budget overrun risk") })
        assertTrue(result.primaryCta is MoneyRadarAction.AdjustBudget)
    }

    @Test
    fun `compute includes only bills due within next seven days`() = runTest {
        coEvery { recurringExpenseEngine.getPatterns() } returns listOf(
            recurring("DueToday", 20.0, now),
            recurring("DueInSeven", 30.0, now + 7 * dayMs),
            recurring("TooLate", 40.0, now + 8 * dayMs),
            recurring("AlreadyPast", 50.0, now - dayMs)
        )
        coEvery { anomalyAlertDao.getActiveAlerts() } returns emptyList()
        every { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())
        coEvery { expenseRepository.getTotalDepositsForPeriod(any(), any()) } returns 5000.0

        val result = useCase.compute()

        assertEquals(listOf("DueToday", "DueInSeven"), result.dueBills.map { it.merchant })
        assertEquals(listOf(0, 7), result.dueBills.map { it.daysUntilDue })
    }

    @Test
    fun `compute aggregates unresolved anomalies from last thirty days only`() = runTest {
        coEvery { recurringExpenseEngine.getPatterns() } returns emptyList()
        coEvery { anomalyAlertDao.getActiveAlerts() } returns listOf(
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
        coEvery { recurringExpenseEngine.getPatterns() } returns listOf(r1, r2, outside)
        coEvery { anomalyAlertDao.getActiveAlerts() } returns emptyList()
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
                riskTier = RiskTier.MEDIUM,
                displayMessage = "You may exceed your budget by €0.00",
                formattedOverrun = "€0.00"
            )
        )

        val result = useCase.compute()

        assertNotNull(result.budgetRisk)
        assertApproxEquals(300.0, capturedSpentToDate, 0.0001)
        assertApproxEquals(150.0, capturedKnownUpcoming, 0.0001)
        assertApproxEquals(1000.0, capturedBudgetAmount ?: 0.0, 0.0001)
    }

    @Test
    fun `compute returns GREEN with healthy message when no bills alerts or budget`() = runTest {
        coEvery { recurringExpenseEngine.getPatterns() } returns emptyList()
        coEvery { anomalyAlertDao.getActiveAlerts() } returns emptyList()
        every { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())

        val result = useCase.compute()

        assertEquals(0, result.urgencyScore)
        assertEquals(UrgencyLevel.GREEN, result.urgencyLevel)
        assertEquals(emptyList<UpcomingBill>(), result.dueBills)
        assertEquals(emptyList<AnomalyAlertSummary>(), result.anomalyAlerts)
        assertNull(result.budgetRisk)
        assertEquals(listOf("Your finances look healthy"), result.topReasons)
        assertNull(result.primaryCta)

        coVerify(exactly = 0) { monteCarloSimulator.simulate(any(), any(), any()) }
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

    private fun anomaly(merchant: String, amount: Double, alertedAt: Long): AnomalyAlert {
        return AnomalyAlert(
            id = 0,
            expenseId = 1L,
            merchant = merchant,
            category = null,
            amount = amount,
            anomalyReason = "Unexpected amount",
            severity = "MEDIUM",
            alertedAt = alertedAt,
            dismissed = false,
            dismissedAt = null,
            userFeedback = null
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
            )
        )
    }
}
