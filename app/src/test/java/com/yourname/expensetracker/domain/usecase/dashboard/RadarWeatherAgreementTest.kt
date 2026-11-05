package com.yourname.expensetracker.domain.usecase.dashboard

import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.analytics.SpendingPace
import com.yourname.expensetracker.domain.budget.AdjustedSpendBreakdown
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.forecasting.DataQualityAssessor
import com.yourname.expensetracker.domain.forecasting.HistoricalDistributionResult
import com.yourname.expensetracker.domain.forecasting.HistoricalSpendingDistribution
import com.yourname.expensetracker.domain.forecasting.MergedRecurringPatternsProvider
import com.yourname.expensetracker.domain.forecasting.MonteCarloSpendingSimulator
import com.yourname.expensetracker.domain.logic.NarrativeGenerator
import com.yourname.expensetracker.domain.logic.SynthesisEngine
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.model.RecurringPattern
import com.yourname.expensetracker.domain.model.Result
import com.yourname.expensetracker.domain.model.budget.MonteCarloBudgetImpact.RiskTier
import com.yourname.expensetracker.domain.model.dashboard.WeatherState
import com.yourname.expensetracker.domain.model.dashboard.toBudgetStatusSnapshot
import com.yourname.expensetracker.domain.usecase.budget.GetMonteCarloBudgetImpactUseCase
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Round 2 (P-05-5) agreement evidence: limit 1,000, spent 700, confirmed rent 400 due in 12 days.
 * Real [SynthesisEngine] and [NarrativeGenerator] for the weather, real
 * [ComputeMoneyRadarUseCase], [MonteCarloSpendingSimulator] and [GetMonteCarloBudgetImpactUseCase]
 * for Radar. Only the data layer, the converter and the historical distribution (no fit) are mocked.
 *
 * Radar's simulation total is at least spent + known upcoming = 1,100 > 1,000, so under any fit the
 * probability of staying under the limit is 0; the no-fit degraded path gives the same verdict
 * deterministically.
 *
 * "Now" is 2026-06-15 12:00 in the JVM default zone; the rent is due 2026-06-27 12:00.
 */
class RadarWeatherAgreementTest {

    private val nowMs = at(15)
    private val rentDueMs = at(27)
    private val timeProvider = object : TimeProvider {
        override fun now(): Long = nowMs
    }
    private val currencyConverter = mockk<CurrencyConverter>(relaxed = true)

    private fun at(day: Int): Long =
        LocalDateTime.of(2026, 6, day, 12, 0, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun date(day: Int, month: Int = 6): Long =
        LocalDateTime.of(2026, month, day, 0, 0, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private val rent = RecurringPattern(
        merchantName = "Rent",
        averageAmount = 400.0,
        currency = "EUR",
        frequency = RecurrenceFrequency.MONTHLY,
        periodVarianceDays = 0,
        amountVariancePercent = 0.0,
        nextExpectedDate = rentDueMs,
        confidence = 0.95f,
        previousDates = emptyList(),
        categoryId = null,
        id = 1L
    )

    private val budgetStatus = BudgetStatus(
        budget = Budget(
            id = 1L, categoryId = null, amount = 1_000.0, period = BudgetPeriod.MONTHLY,
            startDate = date(1), notifyAtWarning = 0.75f, notifyAtCritical = 0.9f,
            currency = "EUR", currencyAssumption = "USER_CONFIRMED"
        ),
        category = null,
        spentAmount = 700.0,
        remainingAmount = 300.0,
        percentUsed = 0.7f,
        healthStatus = BudgetHealthStatus.ON_TRACK,
        periodStart = date(1),
        periodEnd = date(1, month = 7),
        effectiveLimit = 1_000.0,
        adjustedSpendBreakdown = AdjustedSpendBreakdown(
            personalSpend = 700.0, sharedSpend = 0.0, reimbursedAmount = 0.0,
            netSharedLiability = 0.0, effectiveSpend = 700.0, pendingReimbursements = 0.0
        )
    )

    private fun purchase700() = Expense(
        id = 1L, amount = 700.0, currency = "EUR", merchant = "Shops",
        transactionType = TransactionType.PURCHASE, date = at(2), categoryId = null,
        paymentMethod = PaymentMethod.CARD, isNotMine = false
    )

    @Test
    fun `limit 1000 spent 700 and confirmed rent 400 in 12 days weather and radar agree on obligations over the limit`() = runTest {
        // ---- Weather: real engine, as the weather repository does (synthesize, then narrate).
        val snapshot = budgetStatus.toBudgetStatusSnapshot()
        val forecast = SynthesisEngine(timeProvider, currencyConverter = currencyConverter).synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = listOf(rent),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = listOf(snapshot),
            spendingPace = SpendingPace(
                currentMonthSpent = 700.0, daysElapsed = 15, daysInMonth = 30, projectedTotal = 1_400.0,
                previousMonthTotal = null, averageMonthlyTotal = null, pacePercentage = 100.0f,
                paceStatus = PaceStatus.ON_PACE, displayCurrency = "EUR"
            ),
            displayCurrency = "EUR"
        )
        val weather = NarrativeGenerator().generate(forecast, listOf(snapshot), "EUR")

        assertEquals("obligations (400) exceed what is left (300): nothing available", 0.0, forecast.components.discretionaryBudget, 1e-6)
        assertEquals(WeatherState.STORMY, weather.state)

        // ---- The Monte Carlo card: the dashboard runs ONE simulation (spent 700, known upcoming 400,
        // limit 1,000) and hands that same result to Radar. Real simulator on the deterministic
        // no-fit path; Radar never simulates.
        val distribution = mockk<HistoricalSpendingDistribution>()
        coEvery { distribution.computeDistributionResult(homeCurrency = any()) } returns HistoricalDistributionResult.NoFit()
        val simulator = MonteCarloSpendingSimulator(distribution, DataQualityAssessor(), timeProvider)
        val card = requireNotNull(simulator.simulate(700.0, 400.0, 1_000.0, "EUR"))
        assertEquals(1_000.0, card.budgetAmount!!, 0.0)
        assertEquals("700 + 400 can never fit under 1,000", 0.0, card.probabilityUnderBudget!!, 1e-9)
        val cardImpact = (GetMonteCarloBudgetImpactUseCase()(budgetAmount = 1_000.0, monteCarloResult = card)
            as Result.Success<com.yourname.expensetracker.domain.model.budget.MonteCarloBudgetImpact>).data

        val expenseRepository = mockk<ExpenseRepository>()
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns emptyList()
        val recurringProvider = mockk<MergedRecurringPatternsProvider>()
        coEvery { recurringProvider.getConfirmedPatterns() } returns listOf(rent)
        val anomalies = mockk<AnomalyAlertRepository>()
        coEvery { anomalies.getActiveAlerts() } returns emptyList()
        val settings = mockk<CurrencySettingsRepository>()
        every { settings.homeCurrency() } returns flowOf("EUR")

        val radar = ComputeMoneyRadarUseCase(
            recurringPatternsProvider = recurringProvider,
            anomalyAlertRepository = anomalies,
            getMonteCarloBudgetImpact = GetMonteCarloBudgetImpactUseCase(),
            expenseRepository = expenseRepository,
            analyticsCurrencyNormalizer = AnalyticsCurrencyNormalizer(currencyConverter),
            currencySettingsRepository = settings,
            currencyConverter = currencyConverter,
            timeProvider = timeProvider
        ).compute(RadarBudgetInput.Simulated(card))

        // PIN CHANGED: Radar reads the card's own result, so its figures equal the card's by
        // construction instead of coming from a second run with a month-end window.
        // The rent is outside the 7-day bills window but is in the shared overrun input.
        assertTrue(radar.dueBills.isEmpty())
        val risk = radar.budgetRisk!!
        assertEquals(cardImpact.expectedOverrun, risk.expectedOverrun, 0.0)
        assertEquals(cardImpact.probabilityOfOverrun, risk.probabilityOfOverrun, 0.0)
        assertEquals(card.budgetAmount!!, risk.budgetLimit!!, 0.0)
        assertTrue("Radar's overrun figure is above 0", risk.expectedOverrun > 0.0)
        assertEquals(100.0, risk.expectedOverrun, 1e-6) // 700 + 400 - 1,000
        assertEquals(1.0, risk.probabilityOfOverrun, 1e-9)
        assertEquals(RiskTier.CRITICAL, risk.riskTier)
        assertTrue(!radar.budgetUnknown)

        // PIN CHANGED: the weighted score is 30 (budget part 80 + 10 + 20 capped at 100, weighted 0.3;
        // no bills, no anomalies), but the CRITICAL tier sets a score floor of 61 because the overrun
        // 100 is at least 5% of the 1,000 limit, so the Radar level is RED and agrees with the
        // weather surface, which also warns (STORMY above).
        // FC-PAID-UNLINKED (see fix-1.0.4-round2-home-verdicts-plan.md) is a shared, recorded
        // limitation: if the 400 rent was already paid inside the 700 but not linked to its rule, the
        // card, the weather and Radar all count it twice. They still agree with each other.
        assertEquals(61, radar.urgencyScore)
        assertEquals(UrgencyLevel.RED, radar.urgencyLevel)
        assertTrue(radar.primaryCta is MoneyRadarAction.AdjustBudget)
    }

    /**
     * Characterization of a KNOWN DEFECT, not desired behavior (FC-PAID-UNLINKED, recorded in
     * fix-1.0.4-round2-home-verdicts-plan.md, pre-existing). Spent 700 already includes a paid
     * rent of 400, but the rule is still PLANNED and due later this month: the forecast's committed
     * total is still 400, so the true month total of 700 reads as 1,100. When the defect is fixed
     * this test must change to expect 0.
     */
    @Test
    fun `KNOWN DEFECT FC-PAID-UNLINKED pins a paid but unlinked rent still counted as committed upcoming`() = runTest {
        val forecast = SynthesisEngine(timeProvider, currencyConverter = currencyConverter).synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = listOf(rent),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = listOf(budgetStatus.toBudgetStatusSnapshot()),
            spendingPace = SpendingPace(
                currentMonthSpent = 700.0, daysElapsed = 15, daysInMonth = 30, projectedTotal = 1_400.0,
                previousMonthTotal = null, averageMonthlyTotal = null, pacePercentage = 100.0f,
                paceStatus = PaceStatus.ON_PACE, displayCurrency = "EUR"
            ),
            displayCurrency = "EUR"
        )

        assertEquals(
            "known defect: the 400 rent is counted although it is already inside the 700 spent",
            400.0, forecast.components.totalCommitted, 1e-6
        )
    }
}
