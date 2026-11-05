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
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.core.money.ConversionFailureType
import com.yourname.expensetracker.domain.core.money.ConversionOutcome
import com.yourname.expensetracker.domain.core.money.ConversionPath
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.RateBasis
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
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
import com.yourname.expensetracker.domain.model.dashboard.toBudgetStatusSnapshot
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
    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var currencyConverter: CurrencyConverter
    private lateinit var currencySettingsRepository: CurrencySettingsRepository
    private lateinit var timeProvider: TimeProvider
    private lateinit var useCase: ComputeMoneyRadarUseCase

    private val now = 1_710_000_000_000L
    private val dayMs = 24 * 60 * 60 * 1000L

    @Test
    fun failedRequiredSignalsNeverProduceHealthyZeroOrAdvice() = runTest {
        for (source in 0..2) {
            stubHealthyRadarSignals()
            val failure = IllegalStateException("SECRET financial payload")
            val input = simulatedInput(probabilityOfOverrun = 0.5, expectedOverrun = 10.0, tier = RiskTier.MEDIUM)
            when (source) {
                0 -> coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } throws failure
                1 -> coEvery { anomalyAlertRepository.getActiveAlerts() } throws failure
                // PIN CHANGED: the third required signal was the budget read. Radar no longer reads
                // budgets, so it is now the impact derivation from the dashboard's simulation result.
                2 -> every { getMonteCarloBudgetImpact(any(), any()) } throws failure
            }
            assertRadarUnavailable(useCase.compute(input))
        }
    }

    @Test
    fun requiredSignalCancellationIsNotAnUnavailableResult() = runTest {
        for (source in 0..2) {
            stubHealthyRadarSignals()
            val cancellation = kotlinx.coroutines.CancellationException("test cancellation")
            val input = simulatedInput(probabilityOfOverrun = 0.5, expectedOverrun = 10.0, tier = RiskTier.MEDIUM)
            when (source) {
                0 -> coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } throws cancellation
                1 -> coEvery { anomalyAlertRepository.getActiveAlerts() } throws cancellation
                // PIN CHANGED: budget read replaced by the impact derivation (see the test above).
                2 -> every { getMonteCarloBudgetImpact(any(), any()) } throws cancellation
            }
            val failure = runCatching { useCase.compute(input) }.exceptionOrNull()
            assertTrue(failure is kotlinx.coroutines.CancellationException)
        }
    }

    // PIN CHANGED (was missingSimulationForExistingBudgetIsUnavailableNotNoRisk): a budget whose
    // forecast is missing no longer blanks Radar; it is "not checked", never "no risk".
    @Test
    fun missingSimulationForExistingBudgetIsUnknownNotNoRisk() = runTest {
        stubHealthyRadarSignals()

        val result = useCase.compute(RadarBudgetInput.Unknown)

        assertTrue(result.urgencyLevel != UrgencyLevel.UNAVAILABLE)
        assertNotNull(result.urgencyScore)
        assertTrue(result.budgetUnknown)
        assertNull(result.budgetRisk)
    }

    // PIN CHANGED (was unsuccessfulBudgetImpactIsUnavailableNotNoRisk): a non-Success impact makes
    // only the budget part unknown; Radar keeps its other signals instead of going UNAVAILABLE.
    @Test
    fun unsuccessfulBudgetImpactIsUnknownNotNoRisk() = runTest {
        stubHealthyRadarSignals()
        val simulation = monteCarloResult(probabilityUnderBudget = 0.5)
        for (outcome in listOf(Result.Loading, Result.Duplicate, Result.Error(message = "SECRET payload"))) {
            every { getMonteCarloBudgetImpact(1000.0, simulation) } returns outcome

            val result = useCase.compute(RadarBudgetInput.Simulated(simulation))

            assertTrue(result.urgencyLevel != UrgencyLevel.UNAVAILABLE)
            assertNotNull(result.urgencyScore)
            assertTrue(result.budgetUnknown)
            assertNull(result.budgetRisk)
        }
    }

    @Test
    fun failedIncomeSignalDoesNotBecomeZeroIncome() = runTest {
        stubHealthyRadarSignals()
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns
            listOf(recurring("Bill", 10.0, now + dayMs))
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } throws IllegalStateException("private")
        assertRadarUnavailable(useCase.compute())
    }

    private fun stubHealthyRadarSignals() {
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns emptyList()
        coEvery { anomalyAlertRepository.getActiveAlerts() } returns emptyList()
    }

    /** Bills and anomaly tests carry no budget comparison. */
    private suspend fun ComputeMoneyRadarUseCase.compute(): MoneyRadarData = compute(RadarBudgetInput.NotComparable)

    /**
     * A [RadarBudgetInput.Simulated] whose simulation result carries [limit] and whose budget impact
     * (the stubbed use case Radar calls with the result's own limit) has the given figures.
     */
    private fun simulatedInput(
        probabilityOfOverrun: Double,
        expectedOverrun: Double,
        tier: RiskTier,
        limit: Double = 1000.0
    ): RadarBudgetInput.Simulated {
        val simulation = monteCarloResult(probabilityUnderBudget = 1.0 - probabilityOfOverrun).copy(budgetAmount = limit)
        every { getMonteCarloBudgetImpact(limit, simulation) } returns Result.Success(
            MonteCarloBudgetImpact(
                budgetAmount = limit,
                p50Forecast = limit + expectedOverrun,
                expectedOverrun = expectedOverrun,
                probabilityOfOverrun = probabilityOfOverrun,
                riskTier = tier
            )
        )
        return RadarBudgetInput.Simulated(simulation)
    }

    @Test
    fun `invalid home currency cannot make an existing budget look healthy`() = runTest {
        stubHealthyRadarSignals()
        every { currencySettingsRepository.homeCurrency() } returns flowOf("")

        assertRadarUnavailable(useCase.compute(simulatedInput(0.5, 10.0, RiskTier.MEDIUM)))
        io.mockk.verify(exactly = 0) { getMonteCarloBudgetImpact(any(), any()) }
    }

    // PIN CHANGED: Radar no longer reads budgets or currencies of budgets. The dashboard maps a budget
    // with unresolved currency to Unknown (BudgetSurfaceAgreementTest, "unavailable effective spend
    // or unknown percent gives Unknown"); Radar keeps bills and anomalies and says "not checked".
    @Test
    fun `unresolved budget currency gives no budget risk but keeps bills and anomalies`() = runTest {
        stubHealthyRadarSignals()
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            recurring("Bill A", 10.0, now + dayMs),
            recurring("Bill B", 20.0, now + 2 * dayMs)
        )
        coEvery { anomalyAlertRepository.getActiveAlerts() } returns listOf(
            anomaly("Odd Charge", 40.0, now - dayMs)
        )
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            expense(1000.0, TransactionType.DEPOSIT, now - dayMs)
        )

        val result = useCase.compute(RadarBudgetInput.Unknown)

        assertTrue(result.urgencyLevel != UrgencyLevel.UNAVAILABLE)
        assertNotNull(result.urgencyScore)
        assertEquals(2, result.dueBills.size)
        assertEquals(1, result.anomalyAlerts.size)
        assertNull(result.budgetRisk)
        assertTrue(result.budgetUnknown)
    }

    // PIN CHANGED (was: excluded source money made Radar UNAVAILABLE): the card gate in the dashboard
    // withholds the simulation for excluded money (BudgetSurfaceAgreementTest "round2 partial data
    // gives Unknown"), so Radar receives Unknown. It must never read as a healthy forecast.
    @Test
    fun `excluded source money cannot produce a healthy budget forecast`() = runTest {
        stubHealthyRadarSignals()

        val result = useCase.compute(RadarBudgetInput.Unknown)

        assertNull(result.budgetRisk)
        assertTrue(result.budgetUnknown)
        assertTrue(result.topReasons.none {
            it == UiText.MessageKey(DomainTextKeys.COMPUTE_MONEY_RADAR_REASON_FINANCES_HEALTHY)
        })
    }

    // PIN CHANGED: Radar no longer normalizes spending or picks the simulation currency. The
    // simulation arrives with its own currency and limit (USD, 1000); Radar derives risk from that
    // limit. Dashboard-side spend normalization stays covered by the dashboard widget tests.
    @Test
    fun `mixed source spending uses the simulation's own limit and non EUR currency`() = runTest {
        stubHealthyRadarSignals()
        every { currencySettingsRepository.homeCurrency() } returns flowOf("USD")
        val simulation = monteCarloResult(probabilityUnderBudget = 0.5).copy(displayCurrency = "USD")
        every { getMonteCarloBudgetImpact(1000.0, simulation) } returns Result.Success(
            MonteCarloBudgetImpact(1000.0, 1000.0, 0.0, 0.5, RiskTier.MEDIUM)
        )

        val result = useCase.compute(RadarBudgetInput.Simulated(simulation))

        assertNotNull(result.budgetRisk)
        assertEquals(1000.0, result.budgetRisk!!.budgetLimit!!, 0.0)
        io.mockk.verify(exactly = 1) { getMonteCarloBudgetImpact(1000.0, simulation) }
    }

    // PIN CHANGED: the neutralised foreign row is filtered by the dashboard before the simulation
    // (BudgetSurfaceAgreementTest "round2 a neutralised foreign row with no exchange rate still gives
    // Simulated"). Radar given that Simulated result is available with a budget risk.
    @Test
    fun `neutralised foreign row with no exchange rate does not make radar unavailable`() = runTest {
        stubHealthyRadarSignals()

        val result = useCase.compute(simulatedInput(0.3, 0.0, RiskTier.MEDIUM))

        assertTrue(result.urgencyLevel != UrgencyLevel.UNAVAILABLE)
        assertNotNull(result.urgencyScore)
        assertNotNull(result.budgetRisk)
    }

    // PIN CHANGED (was: a counted foreign row with a missing rate made Radar UNAVAILABLE): the card
    // gate gives Unknown (BudgetSurfaceAgreementTest "round2 partial data gives Unknown"); Radar keeps
    // its other signals and flags the budget as not checked.
    @Test
    fun `non-neutralised foreign row with missing exchange rate leaves radar available with budget unknown`() = runTest {
        stubHealthyRadarSignals()
        coEvery { anomalyAlertRepository.getActiveAlerts() } returns listOf(anomaly("Odd Charge", 40.0, now - dayMs))

        val result = useCase.compute(RadarBudgetInput.Unknown)

        assertTrue(result.urgencyLevel != UrgencyLevel.UNAVAILABLE)
        assertEquals(1, result.anomalyAlerts.size)
        assertTrue(result.budgetUnknown)
    }

    private fun assertRadarUnavailable(result: MoneyRadarData) {
        assertEquals(UrgencyLevel.UNAVAILABLE, result.urgencyLevel)
        assertNull(result.urgencyScore)
        assertTrue(result.topReasons.isEmpty())
        assertNull(result.primaryCta)
        assertNull(result.budgetRisk)
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        mergedRecurringPatternsProvider = mockk()
        anomalyAlertRepository = mockk()
        getMonteCarloBudgetImpact = mockk()
        expenseRepository = mockk()
        currencyConverter = mockk()
        currencySettingsRepository = mockk()
        timeProvider = mockk()
        every { currencySettingsRepository.homeCurrency() } returns flowOf("EUR")

        useCase = ComputeMoneyRadarUseCase(
            recurringPatternsProvider = mergedRecurringPatternsProvider,
            anomalyAlertRepository = anomalyAlertRepository,
            getMonteCarloBudgetImpact = getMonteCarloBudgetImpact,
            expenseRepository = expenseRepository,
            analyticsCurrencyNormalizer = AnalyticsCurrencyNormalizer(currencyConverter),
            currencySettingsRepository = currencySettingsRepository,
            currencyConverter = currencyConverter,
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
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            expense(1000.0, TransactionType.DEPOSIT, now - dayMs)
        )

        val result = useCase.compute(
            simulatedInput(probabilityOfOverrun = 0.80, expectedOverrun = 150.0, tier = RiskTier.CRITICAL)
        )

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

    private fun stubBudgetRiskOnly(
        probabilityOfOverrun: Double,
        expectedOverrun: Double,
        tier: RiskTier,
        limit: Double = 1000.0
    ): RadarBudgetInput.Simulated {
        coEvery { anomalyAlertRepository.getActiveAlerts() } returns emptyList()
        return simulatedInput(probabilityOfOverrun, expectedOverrun, tier, limit)
    }

    @Test
    fun `P-05-5 CRITICAL budget tier alone floors the score at 61 and level RED`() = runTest {
        // Budget part = 80 (p >= 0.75) + 10 (overrun >= 100) + 20 (CRITICAL) = 110, capped at 100,
        // weighted 0.3 => 30 (GREEN without the floor). No bills, no anomalies. The overrun 150 is
        // 15% of the 1,000 limit (>= 5%), so the floor applies => 61.
        val input = stubBudgetRiskOnly(probabilityOfOverrun = 0.80, expectedOverrun = 150.0, tier = RiskTier.CRITICAL)

        val result = useCase.compute(input)

        assertTrue(result.dueBills.isEmpty())
        assertTrue(result.anomalyAlerts.isEmpty())
        assertEquals(RiskTier.CRITICAL, result.budgetRisk?.riskTier)
        assertEquals(61, result.urgencyScore)
        assertEquals(UrgencyLevel.RED, result.urgencyLevel)
    }

    // PIN CHANGED (round 2 floor): CRITICAL floors the score only when the expected overrun is at
    // least 5% of the limit. Weighted score: p 0.80 -> 80, overrun 0.01 (> 0) -> +5, CRITICAL -> +20
    // = 105 capped at 100, weighted 0.3 => 30 (GREEN). No floor, so one cent over is not RED.
    @Test
    fun `round2 CRITICAL with overrun 0_01 on limit 1000 stays at its weighted score and is not RED`() = runTest {
        val input = stubBudgetRiskOnly(probabilityOfOverrun = 0.80, expectedOverrun = 0.01, tier = RiskTier.CRITICAL)

        val result = useCase.compute(input)

        assertEquals(RiskTier.CRITICAL, result.budgetRisk?.riskTier)
        assertEquals(30, result.urgencyScore)
        assertEquals(UrgencyLevel.GREEN, result.urgencyLevel)
    }

    // Boundary: overrun exactly 5% of the limit (50 of 1,000) gets the floor (>=). Weighted: 80 + 5 + 20
    // = 105 -> 100 -> 30; the floor lifts it to 61.
    @Test
    fun `round2 CRITICAL with overrun exactly 5 percent of the limit gets the floor`() = runTest {
        val input = stubBudgetRiskOnly(probabilityOfOverrun = 0.80, expectedOverrun = 50.0, tier = RiskTier.CRITICAL)

        val result = useCase.compute(input)

        assertEquals(61, result.urgencyScore)
        assertEquals(UrgencyLevel.RED, result.urgencyLevel)
    }

    // Just under 5% (49.99 of 1,000) is below the boundary: weighted 30, no floor.
    @Test
    fun `round2 CRITICAL with overrun just under 5 percent of the limit has no floor`() = runTest {
        val input = stubBudgetRiskOnly(probabilityOfOverrun = 0.80, expectedOverrun = 49.99, tier = RiskTier.CRITICAL)

        val result = useCase.compute(input)

        assertEquals(30, result.urgencyScore)
        assertEquals(UrgencyLevel.GREEN, result.urgencyLevel)
    }

    // A CRITICAL tier without a known limit cannot show materiality, so it gets no floor. The
    // tier is built straight into the result because Radar always carries the result's limit; a null
    // limit is only reachable through Unknown (budgetRisk null), which has no tier at all.
    @Test
    fun `round2 a missing limit gives no budget risk and therefore no CRITICAL floor`() = runTest {
        stubHealthyRadarSignals()
        val noLimit = monteCarloResult(probabilityUnderBudget = 0.2).copy(budgetAmount = null)

        val result = useCase.compute(RadarBudgetInput.Simulated(noLimit))

        assertNull(result.budgetRisk)
        assertTrue(result.budgetUnknown)
        assertEquals(0, result.urgencyScore)
        assertEquals(UrgencyLevel.GREEN, result.urgencyLevel)
    }

    @Test
    fun `P-05-5 HIGH budget tier alone floors the score at 31 and level YELLOW`() = runTest {
        // Budget part = 60 (p >= 0.50) + 10 (overrun >= 100) + 10 (HIGH) = 80, weighted 0.3 => 24
        // (23 under float truncation). Either is below the floor, so the floor of 31 applies.
        val input = stubBudgetRiskOnly(probabilityOfOverrun = 0.60, expectedOverrun = 120.0, tier = RiskTier.HIGH)

        val result = useCase.compute(input)

        assertTrue(result.dueBills.isEmpty())
        assertTrue(result.anomalyAlerts.isEmpty())
        assertEquals(RiskTier.HIGH, result.budgetRisk?.riskTier)
        assertEquals(31, result.urgencyScore)
        assertEquals(UrgencyLevel.YELLOW, result.urgencyLevel)
    }

    @Test
    fun `P-05-5 MEDIUM budget tier alone has no floor and stays GREEN`() = runTest {
        // Budget part = 30 (p in 0.25..0.50) + 0 (no overrun) + 5 (MEDIUM) = 35, weighted 0.3 => 10.5,
        // truncated to 10. No floor for MEDIUM.
        val input = stubBudgetRiskOnly(probabilityOfOverrun = 0.40, expectedOverrun = 0.0, tier = RiskTier.MEDIUM)

        val result = useCase.compute(input)

        assertTrue(result.dueBills.isEmpty())
        assertTrue(result.anomalyAlerts.isEmpty())
        assertEquals(RiskTier.MEDIUM, result.budgetRisk?.riskTier)
        assertEquals(10, result.urgencyScore)
        assertEquals(UrgencyLevel.GREEN, result.urgencyLevel)
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
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            expense(5000.0, TransactionType.DEPOSIT, now - dayMs)
        )

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
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            expense(5000.0, TransactionType.DEPOSIT, now - dayMs)
        )

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

    // MOVED: "compute passes recurring obligations into Monte Carlo knownUpcoming" tested the
    // simulator arguments Radar built itself. Radar has no simulator now; the simulation is run once
    // by the dashboard (knownUpcoming = forecast committed + likely) and shared. Shared-result
    // identity: BudgetSurfaceAgreementTest "a month-scale Available budget gives Simulated with the
    // same result object as the Monte Carlo card"; the card's own knownUpcoming:
    // RadarWeatherAgreementTest (rent 400 -> simulate(700, 400, 1000)).

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
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            expense(2_000.0, TransactionType.DEPOSIT, now - dayMs)
        )

        val result = useCase.compute()

        assertEquals(listOf("Confirmed Rent"), result.dueBills.map { it.merchant })
        assertTrue(requireNotNull(result.urgencyScore) > 0)
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
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            expense(1000.0, TransactionType.DEPOSIT, now - dayMs)
        )

        // PIN CHANGED: the knownUpcoming simulator argument (120) is no longer built by Radar; the
        // dashboard owns it. The bill-due-today and score/level assertions below are unchanged.
        val result = useCase.compute(
            simulatedInput(probabilityOfOverrun = 0.6, expectedOverrun = 120.0, tier = RiskTier.HIGH)
        )

        assertEquals(listOf("Morning Bill"), result.dueBills.map { it.merchant })
        assertNotNull(result.budgetRisk)
        // €120 is below the large-bill threshold (50% of €1,000 income), so
        // the due-bills large-bill bonus (27d85092a) does not apply.
        assertEquals(36, result.urgencyScore)
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

        io.mockk.verify(exactly = 0) { getMonteCarloBudgetImpact(any(), any()) }
    }

    // MOVED: "compute excludes future dated purchases from spent to date" tested the spentToDate Radar
    // passed to its own simulator. Radar no longer reads purchases; the dashboard builds spentToDate
    // from its period-scoped normalized aggregate ([periodStart, now)). No Radar-level subject remains
    // and no dashboard test pins future-dated exclusion in this slice (listed in the report).

    @Test
    fun `compute budget urgency changes with magnitude and risk tier`() = runTest {
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns emptyList()
        coEvery { anomalyAlertRepository.getActiveAlerts() } returns emptyList()
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            expense(1000.0, TransactionType.DEPOSIT, now - dayMs)
        )

        val lowRisk = useCase.compute(simulatedInput(0.4, 5.0, RiskTier.LOW))
        val highRisk = useCase.compute(simulatedInput(0.4, 250.0, RiskTier.CRITICAL))

        assertTrue(requireNotNull(highRisk.urgencyScore) > requireNotNull(lowRisk.urgencyScore))
        assertTrue(highRisk.urgencyLevel >= lowRisk.urgencyLevel)
    }

    @Test
    fun `compute formats string placeholder reason with scalar merchant arg`() = runTest {
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            recurring("Netflix", 19.99, now + dayMs)
        )
        coEvery { anomalyAlertRepository.getActiveAlerts() } returns emptyList()
        every { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            expense(5000.0, TransactionType.DEPOSIT, now - dayMs)
        )

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
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            expense(5000.0, TransactionType.DEPOSIT, now - dayMs)
        )

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
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            expense(1000.0, TransactionType.DEPOSIT, now - dayMs)
        )

        val input = simulatedInput(probabilityOfOverrun = 0.65, expectedOverrun = 100.0, tier = RiskTier.HIGH)
        val formattedReasons = useCase.compute(input).topReasons.map { it.asString(context) }

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

    // ---------------------------------------------------------------- P-05-5 limit derivation

    private fun stubSimulationCapturingLimit(captured: MutableList<Double>, limitForImpact: Double): MonteCarloResult {
        val simulation = monteCarloResult(probabilityUnderBudget = 0.5)
        coEvery { monteCarloSimulator.simulate(any(), any(), any(), any()) } answers {
            captured.add(thirdArg())
            simulation
        }
        every { getMonteCarloBudgetImpact(any(), any()) } returns Result.Success(
            MonteCarloBudgetImpact(limitForImpact, limitForImpact, 0.0, 0.5, RiskTier.MEDIUM)
        )
        return simulation
    }

    @Test
    fun `P-05-5 budget 500 with rollover surplus 100 simulates against 600 equal to the shared period remaining limit`() = runTest {
        stubHealthyRadarSignals()
        val status = overallBudgetStatus(500.0, effectiveLimit = 600.0)
        every { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(status))
        coEvery { expenseRepository.getExpensesSince(any()) } returns emptyList()
        val limits = mutableListOf<Double>()
        val simulation = stubSimulationCapturingLimit(limits, limitForImpact = 600.0)

        val result = useCase.compute()

        assertNotNull(result.budgetRisk)
        assertEquals(listOf(600.0), limits)
        val sharedLimit = (status.toBudgetStatusSnapshot().periodRemaining(now, "EUR")
            as com.yourname.expensetracker.domain.budget.BudgetPeriodRemaining.Available).limit
        assertEquals(sharedLimit, limits.single(), 0.0)
        io.mockk.verify(exactly = 1) { getMonteCarloBudgetImpact(600.0, simulation) }
    }

    @Test
    fun `P-05-5 weekly overall budget gives no budget comparison`() = runTest {
        stubHealthyRadarSignals()
        // Period spans 7 days: not month-scale, so a calendar-month total must not be compared.
        every { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(
            overallBudgetStatus(500.0, periodStart = now - 3 * dayMs, periodEnd = now + 4 * dayMs)
        ))
        coEvery { expenseRepository.getExpensesSince(any()) } returns emptyList()

        val result = useCase.compute()

        assertTrue("a missing comparison is not an unavailable radar", result.urgencyLevel != UrgencyLevel.UNAVAILABLE)
        assertNull(result.budgetRisk)
        coVerify(exactly = 0) { monteCarloSimulator.simulate(any(), any(), any(), any()) }
    }

    @Test
    fun `P-05-5 a budget status that is not Available keeps bills and anomalies and has no budget risk`() = runTest {
        val base = overallBudgetStatus(500.0)
        val rows = mapOf(
            "percent unknown" to base.copy(percentKnown = false, healthStatus = BudgetHealthStatus.UNKNOWN),
            "effective spend unavailable" to base.copy(adjustedSpendBreakdown = null),
            "currency provenance unknown" to base.copy(currencyAssumption = "UNKNOWN"),
            "currency differs from home" to base.copy(currency = "USD")
        )
        rows.forEach { (name, status) ->
            stubHealthyRadarSignals()
            coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns listOf(
                recurring("Bill A", 10.0, now + dayMs),
                recurring("Bill B", 20.0, now + 2 * dayMs)
            )
            coEvery { anomalyAlertRepository.getActiveAlerts() } returns listOf(
                anomaly("Odd Charge", 40.0, now - dayMs)
            )
            coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
                expense(1000.0, TransactionType.DEPOSIT, now - dayMs)
            )
            every { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(status))
            coEvery { expenseRepository.getExpensesSince(any()) } returns emptyList()

            val result = useCase.compute()

            assertTrue(name, result.urgencyLevel != UrgencyLevel.UNAVAILABLE)
            assertNotNull(name, result.urgencyScore)
            assertEquals(name, listOf("Bill A", "Bill B"), result.dueBills.map { it.merchant })
            assertEquals(name, listOf("Odd Charge"), result.anomalyAlerts.map { it.merchant })
            assertNull(name, result.budgetRisk)
        }
        coVerify(exactly = 0) { monteCarloSimulator.simulate(any(), any(), any(), any()) }
    }

    @Test
    fun `P-05-5 limit of zero or below gives no comparison and does not make Radar unavailable`() = runTest {
        // NO_POSITIVE_LIMIT means there is no budget to compare against, as the widget treats it.
        for (effectiveLimit in listOf(0.0, -50.0)) {
            stubHealthyRadarSignals()
            every { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(
                overallBudgetStatus(500.0, effectiveLimit = effectiveLimit)
            ))
            coEvery { expenseRepository.getExpensesSince(any()) } returns emptyList()

            val result = useCase.compute()

            assertNull("limit $effectiveLimit", result.budgetRisk)
            org.junit.Assert.assertNotEquals("limit $effectiveLimit", UrgencyLevel.UNAVAILABLE, result.urgencyLevel)
        }
        coVerify(exactly = 0) { monteCarloSimulator.simulate(any(), any(), any(), any()) }
    }

    /**
     * P-05-5: the radar's known upcoming is the confirmed recurring patterns due from the start
     * of today through the end of the current calendar month. The bills list and score keep the
     * 7-day window; only the overrun input covers the month.
     */
    @Test
    fun `P-05-5 radar known upcoming covers confirmed patterns through month end`() = runTest {
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            recurring("Rent", 100.0, now + dayMs),
            recurring("Insurance", 50.0, now + 5 * dayMs),
            recurring("LaterInMonth", 200.0, now + 12 * dayMs)
        )
        coEvery { anomalyAlertRepository.getActiveAlerts() } returns emptyList()
        every { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(overallBudgetStatus(1000.0)))
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            expense(1000.0, TransactionType.DEPOSIT, now - dayMs)
        )
        coEvery { expenseRepository.getExpensesSince(any()) } returns emptyList()
        var knownUpcoming = -1.0
        val simulation = monteCarloResult(probabilityUnderBudget = 0.5)
        coEvery { monteCarloSimulator.simulate(any(), any(), any(), any()) } answers {
            knownUpcoming = secondArg()
            simulation
        }
        every { getMonteCarloBudgetImpact(any(), any()) } returns Result.Success(
            MonteCarloBudgetImpact(1000.0, 1000.0, 0.0, 0.5, RiskTier.MEDIUM)
        )

        useCase.compute()

        // PIN CHANGED: the recorded 7-day difference is resolved by round 2. knownUpcoming now covers
        // confirmed recurring from start of today to month end, so the day-12 bill (2024-03-21, same
        // month as now = 2024-03-09) is included: 100 + 50 + 200 = 350 (was 150).
        assertEquals("all three bills fall inside the current month; the day-12 bill is included", 350.0, knownUpcoming, 0.0001)
    }

    // ---------------------------------------------------------------- round 2: month-end known upcoming, budgetUnknown

    private fun stubAvailableBudgetSimulation() {
        every { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(overallBudgetStatus(1000.0)))
        coEvery { expenseRepository.getExpensesSince(any()) } returns emptyList()
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            expense(5000.0, TransactionType.DEPOSIT, now - dayMs)
        )
        val simulation = monteCarloResult(probabilityUnderBudget = 0.9)
        coEvery { monteCarloSimulator.simulate(any(), any(), any(), any()) } returns simulation
        every { getMonteCarloBudgetImpact(any(), any()) } returns Result.Success(
            MonteCarloBudgetImpact(1000.0, 500.0, 0.0, 0.1, RiskTier.LOW)
        )
    }

    @Test
    fun `round2 a confirmed bill due in 12 days inside the month reaches known upcoming but not dueBills`() = runTest {
        stubHealthyRadarSignals()
        stubAvailableBudgetSimulation()
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            recurring("Rent", 400.0, now + 12 * dayMs)
        )

        val result = useCase.compute()

        assertTrue(result.dueBills.isEmpty())
        assertNotNull(result.budgetRisk)
        coVerify(exactly = 1) { monteCarloSimulator.simulate(0.0, 400.0, 1000.0, "EUR") }
    }

    @Test
    fun `round2 known upcoming excludes next month and counts start of today and the last millisecond of the month`() = runTest {
        stubHealthyRadarSignals()
        stubAvailableBudgetSimulation()
        val startOfToday = com.yourname.expensetracker.domain.util.TimePeriodUtils.getStartOfDay(now)
        val (_, monthEndExclusive) = com.yourname.expensetracker.domain.util.TimePeriodUtils.getMonthRange(now)
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            recurring("YesterdayBill", 5.0, startOfToday - 1),            // before today: excluded
            recurring("TodayStart", 10.0, startOfToday),                  // earlier today: counts
            recurring("LastMsOfMonth", 20.0, monthEndExclusive - 1),      // counts
            recurring("ExactlyNextMonth", 60.0, monthEndExclusive),       // exclusive bound: excluded
            recurring("NextMonth", 70.0, monthEndExclusive + dayMs)       // excluded
        )

        useCase.compute()

        coVerify(exactly = 1) { monteCarloSimulator.simulate(0.0, 30.0, 1000.0, "EUR") }
    }

    private fun usdOutcomeMissingRate() {
        coEvery { currencyConverter.convertOutcome(any(), "USD", "EUR", any(), any(), any()) } returns
            ConversionOutcome.Failed(
                originalAmount = 0.0,
                originalCurrency = "USD",
                targetCurrency = "EUR",
                rateBasis = RateBasis.FORECAST_DATE,
                failureType = ConversionFailureType.MISSING_RATE,
                message = "No USD->EUR rate available"
            )
    }

    @Test
    fun `round2 a bill 12 days out with no rate makes only the budget part unknown`() = runTest {
        stubHealthyRadarSignals()
        stubAvailableBudgetSimulation()
        usdOutcomeMissingRate()
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            recurring("Near", 10.0, now + 2 * dayMs),
            recurring("Far Foreign", 400.0, now + 12 * dayMs).copy(currency = "USD")
        )

        val result = useCase.compute()

        assertNull(result.budgetRisk)
        assertTrue(result.budgetUnknown)
        assertTrue(result.urgencyLevel != UrgencyLevel.UNAVAILABLE)
        assertNotNull(result.urgencyScore)
        assertEquals(listOf("Near"), result.dueBills.map { it.merchant })
        coVerify(exactly = 0) { monteCarloSimulator.simulate(any(), any(), any(), any()) }
    }

    @Test
    fun `round2 a bill 3 days out with no rate keeps the existing whole radar unavailable behaviour`() = runTest {
        stubHealthyRadarSignals()
        stubAvailableBudgetSimulation()
        usdOutcomeMissingRate()
        coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            recurring("Near Foreign", 400.0, now + 3 * dayMs).copy(currency = "USD")
        )

        assertRadarUnavailable(useCase.compute())
    }

    @Test
    fun `round2 budgetUnknown is true for every unavailable reason except no positive limit`() = runTest {
        val base = overallBudgetStatus(500.0)
        val unknownRows = mapOf(
            "CONVERSION_INCOMPLETE percent unknown" to base.copy(percentKnown = false, healthStatus = BudgetHealthStatus.UNKNOWN),
            "CONVERSION_INCOMPLETE currency differs" to base.copy(currency = "USD"),
            "CONVERSION_INCOMPLETE provenance unknown" to base.copy(currencyAssumption = "UNKNOWN"),
            "EFFECTIVE_SPEND_UNAVAILABLE" to base.copy(adjustedSpendBreakdown = null)
        )
        unknownRows.forEach { (name, status) ->
            stubHealthyRadarSignals()
            every { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(status))
            coEvery { expenseRepository.getExpensesSince(any()) } returns emptyList()
            val result = useCase.compute()
            assertTrue(name, result.budgetUnknown)
            assertNull(name, result.budgetRisk)
            assertTrue(name, result.urgencyLevel != UrgencyLevel.UNAVAILABLE)
        }
    }

    @Test
    fun `round2 budgetUnknown is false for no positive limit no overall budget non month scale and available`() = runTest {
        val base = overallBudgetStatus(500.0)
        val falseRows = mapOf(
            "NO_POSITIVE_LIMIT" to overallBudgetStatus(500.0, effectiveLimit = 0.0),
            "category budget only" to base.copy(budget = base.budget.copy(categoryId = 3L)),
            // Non-month-scale is decided before the remaining check, even for an otherwise unknown status.
            "weekly budget" to overallBudgetStatus(500.0, periodStart = now - 3 * dayMs, periodEnd = now + 4 * dayMs)
                .copy(percentKnown = false, healthStatus = BudgetHealthStatus.UNKNOWN),
            "Available" to base
        )
        falseRows.forEach { (name, status) ->
            stubHealthyRadarSignals()
            stubAvailableBudgetSimulation()
            every { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(status))
            assertTrue(name, !useCase.compute().budgetUnknown)
        }
        stubHealthyRadarSignals()
        assertTrue("no budget at all", !useCase.compute().budgetUnknown)
    }

    @Test
    fun `round2 budgetUnknown changes neither score nor level`() = runTest {
        // Derived from the score code: 3 bills -> 60 * 0.4 = 24, 2 anomalies -> 60 * 0.3 = 18,
        // budget part 0 in both cases (LOW risk p=0.1, overrun 0, or no risk info) => 42 => YELLOW (31..60).
        fun stubBusy() {
            stubHealthyRadarSignals()
            stubAvailableBudgetSimulation()
            coEvery { mergedRecurringPatternsProvider.getConfirmedPatterns() } returns listOf(
                recurring("A", 10.0, now + dayMs),
                recurring("B", 10.0, now + 2 * dayMs),
                recurring("C", 10.0, now + 3 * dayMs)
            )
            coEvery { anomalyAlertRepository.getActiveAlerts() } returns listOf(
                anomaly("X", 8.0, now - dayMs),
                anomaly("Y", 9.0, now - 2 * dayMs)
            )
        }

        stubBusy()
        val available = useCase.compute()

        stubBusy()
        every { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(
            overallBudgetStatus(1000.0).copy(adjustedSpendBreakdown = null)
        ))
        val unknown = useCase.compute()

        assertTrue(!available.budgetUnknown)
        assertNotNull(available.budgetRisk)
        assertTrue(unknown.budgetUnknown)
        assertNull(unknown.budgetRisk)
        assertEquals(42, available.urgencyScore)
        assertEquals(42, unknown.urgencyScore)
        assertEquals(UrgencyLevel.YELLOW, available.urgencyLevel)
        assertEquals(UrgencyLevel.YELLOW, unknown.urgencyLevel)

        // Nothing else on the books: both are score 0 and GREEN.
        stubHealthyRadarSignals()
        stubAvailableBudgetSimulation()
        val quietAvailable = useCase.compute()
        every { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(
            overallBudgetStatus(1000.0).copy(adjustedSpendBreakdown = null)
        ))
        val quietUnknown = useCase.compute()
        assertEquals(0, quietAvailable.urgencyScore)
        assertEquals(0, quietUnknown.urgencyScore)
        assertEquals(UrgencyLevel.GREEN, quietAvailable.urgencyLevel)
        assertEquals(UrgencyLevel.GREEN, quietUnknown.urgencyLevel)
        assertTrue(quietUnknown.budgetUnknown)
    }

    /**
     * Overall budget fixture as production maps it for a complete EUR budget: confirmed currency
     * provenance and a finite effective-spend breakdown, so [BudgetStatusSnapshot.periodRemaining]
     * is Available. [effectiveLimit] is the limit incl. rollover, as the repository computes it;
     * it is what the radar must simulate against, so it defaults to [amount] and tests that need a
     * different value pass it explicitly (a stated budget never relies on an implicit 0.0).
     */
    private fun overallBudgetStatus(
        amount: Double,
        effectiveLimit: Double = amount,
        periodStart: Long = now - 20 * dayMs,
        periodEnd: Long = now + 10 * dayMs
    ): BudgetStatus {
        val budget = Budget(
            id = 10L,
            categoryId = null,
            amount = amount,
            period = BudgetPeriod.MONTHLY,
            startDate = periodStart,
            isActive = true,
            currency = "EUR",
            currencyAssumption = "USER_CONFIRMED"
        )
        return BudgetStatus(
            budget = budget,
            category = null,
            spentAmount = 0.0,
            remainingAmount = amount,
            percentUsed = 0f,
            healthStatus = BudgetHealthStatus.ON_TRACK,
            periodStart = periodStart,
            periodEnd = periodEnd,
            effectiveLimit = effectiveLimit,
            adjustedSpendBreakdown = com.yourname.expensetracker.domain.budget.AdjustedSpendBreakdown(
                personalSpend = 0.0,
                sharedSpend = 0.0,
                reimbursedAmount = 0.0,
                netSharedLiability = 0.0,
                effectiveSpend = 0.0,
                pendingReimbursements = 0.0
            )
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
