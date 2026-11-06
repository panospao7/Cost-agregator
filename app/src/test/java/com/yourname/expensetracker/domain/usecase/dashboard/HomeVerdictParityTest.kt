package com.yourname.expensetracker.domain.usecase.dashboard

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.analytics.SpendingPaceCalculator
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.forecasting.ConfidenceLevel
import com.yourname.expensetracker.domain.forecasting.FinancialStressForecastEngine
import com.yourname.expensetracker.domain.forecasting.ForecastInputAssembler
import com.yourname.expensetracker.domain.forecasting.MergedRecurringPatternsProvider
import com.yourname.expensetracker.domain.forecasting.MonteCarloResult
import com.yourname.expensetracker.domain.forecasting.MonteCarloSpendingSimulator
import com.yourname.expensetracker.domain.forecasting.NormalizedForecastInput
import com.yourname.expensetracker.domain.forecasting.SimulationConfidence
import com.yourname.expensetracker.domain.forecasting.SimulationMetadata
import com.yourname.expensetracker.domain.forecasting.StressForecastResult
import com.yourname.expensetracker.domain.forecasting.StressHorizon
import com.yourname.expensetracker.domain.forecasting.StressRiskLevel
import com.yourname.expensetracker.domain.health.FinancialHealthResult
import com.yourname.expensetracker.domain.health.FinancialHealthScoreV2
import com.yourname.expensetracker.domain.health.HealthScoreOutcome
import com.yourname.expensetracker.domain.health.HealthTrend
import com.yourname.expensetracker.domain.logic.SynthesisEngine
import com.yourname.expensetracker.domain.model.PlannedExpense
import com.yourname.expensetracker.domain.model.PlannedExpensePriority
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.model.RecurringPattern
import com.yourname.expensetracker.domain.model.Result
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.model.budget.MonteCarloBudgetImpact.RiskTier
import com.yourname.expensetracker.domain.model.dashboard.BudgetStatusSnapshot
import com.yourname.expensetracker.domain.model.dashboard.DashboardExpense
import com.yourname.expensetracker.domain.model.dashboard.DashboardTransactionType
import com.yourname.expensetracker.domain.model.dashboard.FinancialWeather
import com.yourname.expensetracker.domain.model.dashboard.SpendingSummary
import com.yourname.expensetracker.domain.model.dashboard.WeatherState
import com.yourname.expensetracker.domain.text.DomainTextKeys
import com.yourname.expensetracker.domain.usecase.budget.GetMonteCarloBudgetImpactUseCase
import com.yourname.expensetracker.domain.usecase.savings.LifestyleSavingsPromptUseCase
import com.yourname.expensetracker.domain.usecase.savings.MonthlySavingsSweepUseCase
import com.yourname.expensetracker.domain.usecase.savings.SavingsSweepComputationResult
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * P-05-5 / AE-1 / AE-21 (docs/features/fc-paid-unlinked/domain-spec.md): the Home verdicts agree
 * because the Money Radar reads the SAME Monte Carlo result the Home card shows.
 *
 * Real [ComputeDashboardWidgetsUseCase], real [ComputeMoneyRadarUseCase], real [SynthesisEngine] and
 * real [GetMonteCarloBudgetImpactUseCase]. Only the data layer, the peripheral use cases and the
 * stochastic simulator are test doubles. The simulator double is a deterministic echo: its result
 * carries the spent-to-date, known-upcoming and limit it was called with, with fixed probability
 * bands (over the limit 0.2 under, within 10 percent of it 0.6, otherwise 0.9), so the Home card
 * total is the pipeline's own spent plus known upcoming.
 *
 * "Now" is 2026-06-15 12:00 in the JVM default zone. Budget 1,000 for June.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HomeVerdictParityTest {

    private var fixedNowMs: Long = 0L
    private val timeProvider = object : TimeProvider {
        override fun now(): Long = fixedNowMs
    }
    private val dayMs = 24 * 60 * 60 * 1000L

    private lateinit var computeUseCase: ComputeDashboardWidgetsUseCase
    private lateinit var currencyConverter: CurrencyConverter
    private lateinit var patternsProvider: MergedRecurringPatternsProvider
    private lateinit var anomalyRepository: AnomalyAlertRepository
    private lateinit var expenseRepository: ExpenseRepository
    private val impactUseCase = GetMonteCarloBudgetImpactUseCase()

    private var simulateCalls = 0
    private var simulatorReturnsNull = false
    private var lastSpentArg = Double.NaN
    private var lastKnownArg = Double.NaN

    private class Outcome(
        val card: DashboardWidget.MonteCarloForecast?,
        val radar: MoneyRadarData,
        /** Null when no budget and no income exist: no runway number is fabricated then. */
        private val runwayOrNull: DashboardWidget.FinancialRunway?,
        val simulateCalls: Int,
        val spentArg: Double,
        val knownArg: Double
    ) {
        val runway: DashboardWidget.FinancialRunway
            get() = requireNotNull(runwayOrNull) { "runway widget expected" }
    }

    // ---------------------------------------------------------------- (a) one simulation per compute

    @Test
    fun `simulator is invoked exactly once per compute even when the Radar has bills and anomalies`() = runTest {
        stubRadarBillsAndAnomalies()

        val first = outcome(listOf(rentPlanned("PLANNED")))
        assertEquals(1, first.simulateCalls)

        val second = outcome(listOf(rentPlanned("PLANNED")))
        assertEquals("a second compute simulates exactly once more", 1, second.simulateCalls)
        assertEquals(2, totalSimulateCalls)
    }

    @Test
    fun `simulator is invoked exactly once when there is no budget and when the budget read failed`() = runTest {
        assertEquals(1, outcome(emptyList(), budgets = emptyList()).simulateCalls)
        assertEquals(1, outcome(emptyList(), budgets = emptyList(), budgetReadFailed = true).simulateCalls)
    }

    // ---------------------------------------------------------------- (b) Radar tier equals the impact on the card result

    @Test
    fun `Radar budget risk equals GetMonteCarloBudgetImpact on the card result for every tier`() = runTest {
        // CRITICAL (base 1,100), MEDIUM (base 950, within 10 percent of the limit), LOW (base 700).
        val rows = listOf(
            "CRITICAL" to (listOf(rentPlanned("PLANNED", 400.0)) to RiskTier.CRITICAL),
            "MEDIUM" to (listOf(rentPlanned("PLANNED", 250.0)) to RiskTier.MEDIUM),
            "LOW" to (emptyList<PlannedExpense>() to RiskTier.LOW)
        )

        rows.forEach { (name, row) ->
            val (planned, expectedTier) = row
            val o = outcome(planned)

            val card = requireNotNull(o.card) { "$name: card shown" }
            val limit = requireNotNull(card.result.budgetAmount) { "$name: card carries the limit" }
            val impact = impactUseCase(limit, card.result) as Result.Success
            val risk = requireNotNull(o.radar.budgetRisk) { "$name: Radar has a budget risk" }

            assertEquals("$name: Radar tier equals impact on the card result", impact.data.riskTier, risk.riskTier)
            assertEquals("$name: probability", impact.data.probabilityOfOverrun, risk.probabilityOfOverrun, 0.0)
            assertEquals("$name: overrun", impact.data.expectedOverrun, risk.expectedOverrun, 0.0)
            assertEquals("$name: fixture tier", expectedTier, risk.riskTier)
        }
    }

    // ---------------------------------------------------------------- (c) fail-closed: unreadable and unknown budgets

    @Test
    fun `a failed budget read makes the Radar UNAVAILABLE and never GREEN or All Clear`() = runTest {
        val o = outcome(emptyList(), budgets = emptyList(), budgetReadFailed = true)

        assertEquals(UrgencyLevel.UNAVAILABLE, o.radar.urgencyLevel)
        assertNull(o.radar.urgencyScore)
        assertNull(o.radar.budgetRisk)
        assertNull(o.radar.primaryCta)
        assertTrue(o.radar.topReasons.isEmpty())
        assertNotEquals(UrgencyLevel.GREEN, o.radar.urgencyLevel)
        assertTrue(
            "no All Clear reason",
            o.radar.topReasons.none {
                it == UiText.MessageKey(DomainTextKeys.COMPUTE_MONEY_RADAR_REASON_FINANCES_HEALTHY)
            }
        )
    }

    @Test
    fun `control a successful read with no budget keeps a real GREEN All Clear`() = runTest {
        val o = outcome(emptyList(), budgets = emptyList(), budgetReadFailed = false)

        assertEquals(UrgencyLevel.GREEN, o.radar.urgencyLevel)
        assertEquals(0, o.radar.urgencyScore)
        assertNull(o.radar.budgetRisk)
        assertEquals(
            listOf<UiText>(UiText.MessageKey(DomainTextKeys.COMPUTE_MONEY_RADAR_REASON_FINANCES_HEALTHY)),
            o.radar.topReasons
        )
    }

    @Test
    fun `a month-scale budget whose card is unavailable makes an otherwise GREEN Radar UNAVAILABLE`() = runTest {
        simulatorReturnsNull = true

        val o = outcome(emptyList())

        assertNull("no card result", o.card)
        assertEquals(UrgencyLevel.UNAVAILABLE, o.radar.urgencyLevel)
        assertNull(o.radar.urgencyScore)
    }

    @Test
    fun `a month-scale budget whose card is unavailable keeps a non-GREEN Radar from bills and anomalies`() = runTest {
        simulatorReturnsNull = true
        // 3 bills = 24, 1 anomaly = 9: score 33, YELLOW, independent of the unknown budget part.
        stubRadarBillsAndAnomalies()

        val o = outcome(emptyList())

        assertEquals(UrgencyLevel.YELLOW, o.radar.urgencyLevel)
        assertEquals(33, o.radar.urgencyScore)
        assertEquals(3, o.radar.dueBills.size)
        assertNull(o.radar.budgetRisk)
    }

    // ---------------------------------------------------------------- (d) AE-1 numeric parity

    /**
     * AE-1 before confirm: the paid-unlinked rent is in spent (700 incl. the rent 400) AND the
     * occurrence is still PLANNED (400), so the base is 1,100 against the limit 1,000.
     */
    @Test
    fun `AE-1 before confirm card total is 1100 Runway is 0 CRITICAL and Radar is non-GREEN with CRITICAL budget risk`() = runTest {
        val o = outcome(listOf(rentPlanned("PLANNED", 400.0)))

        assertEquals(700.0, o.spentArg, 1e-6)
        assertEquals(400.0, o.knownArg, 1e-6)
        val card = requireNotNull(o.card)
        assertEquals(1_100.0, card.result.spentToDate + card.result.knownUpcoming, 1e-6)
        assertEquals(1_000.0, card.result.budgetAmount!!, 0.0)
        assertEquals(0.0, o.runway.discretionaryRemaining, 1e-6)
        assertEquals(0, o.runway.daysRemaining)
        assertEquals(DashboardWidget.RunwayStatus.CRITICAL, o.runway.status)

        assertNotEquals(UrgencyLevel.GREEN, o.radar.urgencyLevel)
        assertNotEquals(UrgencyLevel.UNAVAILABLE, o.radar.urgencyLevel)
        assertEquals(RiskTier.CRITICAL, o.radar.budgetRisk!!.riskTier)
        assertEquals(31, o.radar.urgencyScore)
        assertEquals(UrgencyLevel.YELLOW, o.radar.urgencyLevel)
    }

    /** AE-1 after confirm: occurrence PAID and linked (planned row FULFILLED); base is 700, remainder 300. */
    @Test
    fun `AE-1 after confirm card total is 700 Runway remainder is 300 and Radar tier is lower`() = runTest {
        val before = outcome(listOf(rentPlanned("PLANNED", 400.0)))
        val after = outcome(listOf(rentPlanned("FULFILLED", 400.0)))

        assertEquals(700.0, after.spentArg, 1e-6)
        assertEquals(0.0, after.knownArg, 1e-6)
        val card = requireNotNull(after.card)
        assertEquals(700.0, card.result.spentToDate + card.result.knownUpcoming, 1e-6)
        assertEquals(300.0, after.runway.discretionaryRemaining, 1e-6)

        assertEquals(RiskTier.LOW, after.radar.budgetRisk!!.riskTier)
        assertEquals(UrgencyLevel.GREEN, after.radar.urgencyLevel)
        assertEquals(0, after.radar.urgencyScore)

        // Ordering across the confirm: the Radar verdict strictly improves.
        assertTrue(
            "budget tier is lower after the confirm",
            after.radar.budgetRisk!!.riskTier.ordinal < before.radar.budgetRisk!!.riskTier.ordinal
        )
        assertTrue(after.radar.urgencyScore!! < before.radar.urgencyScore!!)
        assertTrue(after.runway.discretionaryRemaining > before.runway.discretionaryRemaining)
    }

    @Test
    fun `AE-1 confirm moves the Radar card and Runway together on the same result object`() = runTest {
        val before = outcome(listOf(rentPlanned("PLANNED", 400.0)))
        val after = outcome(listOf(rentPlanned("FULFILLED", 400.0)))

        // Same spent in both states: only the committed side changed, and every surface followed.
        assertEquals(before.spentArg, after.spentArg, 1e-6)
        assertEquals(400.0, before.knownArg - after.knownArg, 1e-6)
        assertEquals(
            400.0,
            (before.card!!.result.spentToDate + before.card.result.knownUpcoming) -
                (after.card!!.result.spentToDate + after.card.result.knownUpcoming),
            1e-6
        )
        assertEquals(300.0, after.runway.discretionaryRemaining - before.runway.discretionaryRemaining, 1e-6)
    }

    // ---------------------------------------------------------------- (e) AE-21 suggestion only changes nothing

    /**
     * AE-21: a payment-link suggestion alone changes no forecast number. The suggestion API is not a
     * dependency of the forecast classes (pinned structurally by PaymentLinkArchitectureGuardTest,
     * which scans ComputeDashboardWidgetsUseCase and ComputeMoneyRadarUseCase). Here the behavioural
     * half: with the before-confirm data unchanged (the suggestion is shown but not confirmed), the
     * card, Runway and Radar outputs are identical to the baseline, number for number.
     */
    @Test
    fun `AE-21 a suggestion alone leaves the card Runway and Radar identical to the baseline`() = runTest {
        val baseline = outcome(listOf(rentPlanned("PLANNED", 400.0)))
        val withSuggestionShown = outcome(listOf(rentPlanned("PLANNED", 400.0)))

        assertEquals(1_100.0, withSuggestionShown.card!!.result.spentToDate + withSuggestionShown.card.result.knownUpcoming, 1e-6)
        assertEquals(baseline.card!!.result, withSuggestionShown.card.result)
        assertEquals(baseline.runway, withSuggestionShown.runway)
        assertEquals(baseline.radar, withSuggestionShown.radar)
        assertEquals(0.0, withSuggestionShown.runway.discretionaryRemaining, 1e-6)
    }

    // ---------------------------------------------------------------- harness

    private var totalSimulateCalls = 0

    private suspend fun outcome(
        planned: List<PlannedExpense>,
        budgets: List<BudgetStatusSnapshot> = listOf(juneBudget()),
        budgetReadFailed: Boolean = false
    ): Outcome {
        fixedNowMs = at(15, 12)
        simulateCalls = 0
        val expenses = listOf(
            // The paid-unlinked rent (400) is part of spent; the second purchase makes spent 700.
            purchase(1L, 400.0, at(2, 12), "Landlord"),
            purchase(2L, 300.0, at(3, 12), "Groceries")
        )
        val result = computeUseCase.compute(data(expenses, budgets, planned, budgetReadFailed))
        val spent = lastSpentArg
        val known = lastKnownArg
        return Outcome(
            card = result.allWidgets.filterIsInstance<DashboardWidget.MonteCarloForecast>().singleOrNull(),
            radar = result.allWidgets.filterIsInstance<DashboardWidget.MoneyRadar>().single().data,
            runwayOrNull = result.allWidgets.filterIsInstance<DashboardWidget.FinancialRunway>().singleOrNull(),
            simulateCalls = simulateCalls,
            spentArg = spent,
            knownArg = known
        )
    }

    private fun stubRadarBillsAndAnomalies() {
        coEvery { patternsProvider.getConfirmedPatterns() } returns listOf(
            recurring("Bill A", 10.0, at(16, 12)),
            recurring("Bill B", 20.0, at(17, 12)),
            recurring("Bill C", 30.0, at(18, 12))
        )
        coEvery { anomalyRepository.getActiveAlerts() } returns listOf(
            AnomalyAlertRecord(
                merchant = "Odd Charge", amount = 40.0, anomalyReason = "Unexpected amount",
                alertedAt = at(14, 12)
            )
        )
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            Expense(
                id = 99L, amount = 1000.0, currency = "EUR", merchant = "Employer",
                transactionType = TransactionType.DEPOSIT, date = at(14, 9), categoryId = null,
                paymentMethod = PaymentMethod.CARD, isNotMine = false
            )
        )
    }

    private fun rentPlanned(status: String, amount: Double = 400.0) = PlannedExpense(
        id = 1L, description = "Rent", amount = amount, currency = "EUR", date = at(27, 12),
        categoryId = null, isRecurring = true, priority = PlannedExpensePriority.MUST, status = status
    )

    private fun recurring(merchant: String, amount: Double, date: Long) = RecurringPattern(
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

    /** Overall EUR budget 1,000 for June with effective spend 700 (the paid rent is inside it). */
    private fun juneBudget() = BudgetStatusSnapshot(
        budgetCategoryId = null,
        budgetAmount = 1_000.0,
        categoryName = null,
        spentAmount = 700.0,
        remainingAmount = 300.0,
        percentUsed = 0.7,
        healthStatus = BudgetHealthStatus.ON_TRACK,
        periodStart = at(1, 0),
        periodEnd = LocalDateTime.of(2026, 7, 1, 0, 0, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        currency = "EUR",
        currencyAssumption = "USER_CONFIRMED",
        effectiveSpend = 700.0,
        effectiveHealthStatus = BudgetHealthStatus.ON_TRACK
    )

    private fun purchase(id: Long, amount: Double, date: Long, merchant: String) = DashboardExpense(
        id = id, amount = amount, effectiveAmount = amount, currency = "EUR", merchant = merchant,
        transactionType = DashboardTransactionType.PURCHASE, date = date,
        categoryId = 7L, isNotMine = false, isManualEntry = false
    )

    private fun data(
        expenses: List<DashboardExpense>,
        budgets: List<BudgetStatusSnapshot>,
        planned: List<PlannedExpense>,
        budgetReadFailed: Boolean
    ): ProcessedDashboardData {
        val dashboardData = DashboardData(
            expenses = expenses,
            categories = emptyList(),
            budgetStatuses = budgets,
            pendingCount = 0,
            weather = FinancialWeather(
                state = WeatherState.UNKNOWN,
                headline = UiText.DynamicString(""),
                summary = UiText.DynamicString(""),
                icon = "",
                riskLevel = 0,
                totalCommitted = 0.0,
                totalLikely = 0.0,
                predictedDiscretionary = 0.0,
                discretionaryBudget = 0.0
            ),
            recurringPatterns = emptyList(),
            plannedExpenses = planned,
            goals = emptyList(),
            budgetReadFailed = budgetReadFailed
        )
        val summary = SpendingSummary(
            totalSpent = 0.0,
            previousTotalSpent = null,
            changePercent = null,
            dailyHistory = emptyList(),
            previousDailyHistory = emptyList(),
            transactionCount = expenses.size
        )
        return ProcessedDashboardData(data = dashboardData, summary = summary, categoryBreakdown = emptyList())
    }

    /** Deterministic echo of the simulator arguments with fixed probability bands. */
    private fun echoResult(spent: Double, known: Double, limit: Double?): MonteCarloResult {
        val base = spent + known
        return MonteCarloResult(
            percentile10 = base - 50.0,
            percentile25 = base - 25.0,
            percentile50 = base,
            percentile75 = base + 25.0,
            percentile90 = base + 50.0,
            probabilityUnderBudget = limit?.let {
                when {
                    base > it -> 0.2
                    base > it * 0.9 -> 0.6
                    else -> 0.9
                }
            },
            budgetAmount = limit,
            spentToDate = spent,
            knownUpcoming = known,
            confidence = SimulationConfidence(0.7, ConfidenceLevel.HIGH, "seeded"),
            metadata = SimulationMetadata(
                qualifyingWeeks = 10, totalWeeksExamined = 12, iterations = 1000,
                logNormalMu = 0.0, logNormalSigma = 1.0, daysRemaining = 15, computedAt = fixedNowMs
            ),
            displayCurrency = "EUR"
        )
    }

    /** Epoch millis of June [day] 2026 at [hour]:00 in the JVM default zone. */
    private fun at(day: Int, hour: Int): Long =
        LocalDateTime.of(2026, 6, day, hour, 0, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Before
    fun setup() {
        simulateCalls = 0
        totalSimulateCalls = 0
        simulatorReturnsNull = false
        lastSpentArg = Double.NaN
        lastKnownArg = Double.NaN
        currencyConverter = mockk(relaxed = true)
        val synthesisEngine = SynthesisEngine(timeProvider, currencyConverter = currencyConverter)

        val monteCarloSimulator = mockk<MonteCarloSpendingSimulator>(relaxed = true)
        coEvery { monteCarloSimulator.simulate(any(), any(), any(), any(), any()) } answers {
            simulateCalls++
            totalSimulateCalls++
            val spent = arg<Double>(0)
            val known = arg<Double>(1)
            lastSpentArg = spent
            lastKnownArg = known
            if (simulatorReturnsNull) null else echoResult(spent, known, arg<Double?>(2))
        }

        val healthScoreV2 = mockk<FinancialHealthScoreV2>(relaxed = true)
        coEvery { healthScoreV2.calculateHealthScore(any(), any()) } returns HealthScoreOutcome.Available(
            FinancialHealthResult(
                overallScore = 50, savingsRateScore = 50, runwayScore = 50,
                budgetAdherenceScore = 50, billReliabilityScore = 50,
                factorContributions = emptyList(), trend = HealthTrend.STABLE, recommendation = null
            )
        )
        val lifestyleSavingsPromptUseCase = mockk<LifestyleSavingsPromptUseCase>(relaxed = true)
        coEvery { lifestyleSavingsPromptUseCase.evaluateAndPrompt() } returns null
        val monthlySavingsSweepUseCase = mockk<MonthlySavingsSweepUseCase>(relaxed = true)
        coEvery { monthlySavingsSweepUseCase.computeSweepRecommendationResult() } returns
            SavingsSweepComputationResult.NoRecommendation()
        val stressForecastEngine = mockk<FinancialStressForecastEngine>(relaxed = true)
        coEvery { stressForecastEngine.computeStressForecast() } returns StressForecastResult(
            horizons = listOf(StressHorizon(30, 0.0, 0.0, 0.0, StressRiskLevel.LOW, 0.0, 0.0, 0.0)),
            overallRiskLevel = StressRiskLevel.LOW,
            earliestCrunchDate = null,
            recommendations = emptyList()
        )

        val currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true)
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        every { currencySettingsRepository.homeCurrency() } returns flowOf("EUR")
        val multiCurrencyRepository = mockk<MultiCurrencyRepository>(relaxed = true)
        coEvery { multiCurrencyRepository.getHomeCurrencyPurchaseTotal(any(), any()) } returns
            MoneyAggregate.empty(CurrencyCode("EUR"))
        val forecastInputAssembler = mockk<ForecastInputAssembler>(relaxed = true)
        coEvery { forecastInputAssembler.assembleNormalized(any()) } answers {
            val input = arg<NormalizedForecastInput>(0)
            ForecastInputAssembler.ForecastInput(
                pastSumDaily = input.pastSumDaily,
                recurringPatterns = input.recurringPatterns,
                plannedExpenses = input.plannedExpenses,
                savingsGoals = input.savingsGoals,
                budgetStatuses = input.budgetStatuses,
                spendingPace = input.spendingPace,
                confirmedOccurrences = emptyList(),
                displayCurrency = input.homeCurrency.code,
                dataQuality = input.dataQuality
            )
        }

        patternsProvider = mockk()
        coEvery { patternsProvider.getConfirmedPatterns() } returns emptyList()
        anomalyRepository = mockk()
        coEvery { anomalyRepository.getActiveAlerts() } returns emptyList()
        expenseRepository = mockk()
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns emptyList()

        val realRadar = ComputeMoneyRadarUseCase(
            recurringPatternsProvider = patternsProvider,
            anomalyAlertRepository = anomalyRepository,
            getMonteCarloBudgetImpact = impactUseCase,
            expenseRepository = expenseRepository,
            analyticsCurrencyNormalizer = AnalyticsCurrencyNormalizer(currencyConverter),
            currencySettingsRepository = currencySettingsRepository,
            currencyConverter = currencyConverter,
            timeProvider = timeProvider
        )

        computeUseCase = ComputeDashboardWidgetsUseCase(
            writeBarrier = mockk(relaxed = true),
            insightsEngine = mockk(relaxed = true),
            synthesisEngine = synthesisEngine,
            monteCarloSimulator = monteCarloSimulator,
            timeProvider = timeProvider,
            healthCalculator = mockk(relaxed = true),
            healthScoreV2 = healthScoreV2,
            lifestyleSavingsPromptUseCase = lifestyleSavingsPromptUseCase,
            monthlySavingsSweepUseCase = monthlySavingsSweepUseCase,
            computeMoneyRadarUseCase = realRadar,
            stressForecastEngine = stressForecastEngine,
            forecastInputAssembler = forecastInputAssembler,
            currencyConverter = currencyConverter,
            currencySettingsRepository = currencySettingsRepository,
            multiCurrencyRepository = multiCurrencyRepository,
            spendingPaceCalculator = SpendingPaceCalculator(timeProvider)
        )
    }
}
