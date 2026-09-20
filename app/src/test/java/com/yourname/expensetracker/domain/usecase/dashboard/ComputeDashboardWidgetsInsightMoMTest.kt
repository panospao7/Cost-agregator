package com.yourname.expensetracker.domain.usecase.dashboard

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.forecasting.FinancialStressForecastEngine
import com.yourname.expensetracker.domain.forecasting.ForecastInputAssembler
import com.yourname.expensetracker.domain.forecasting.MonteCarloSpendingSimulator
import com.yourname.expensetracker.domain.forecasting.NormalizedForecastInput
import com.yourname.expensetracker.domain.forecasting.StressForecastResult
import com.yourname.expensetracker.domain.forecasting.StressHorizon
import com.yourname.expensetracker.domain.forecasting.StressRiskLevel
import com.yourname.expensetracker.domain.health.FinancialHealthResult
import com.yourname.expensetracker.domain.health.FinancialHealthScoreV2
import com.yourname.expensetracker.domain.health.HealthScoreOutcome
import com.yourname.expensetracker.domain.health.HealthTrend
import com.yourname.expensetracker.domain.logic.SynthesisEngine
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.model.dashboard.DashboardExpense
import com.yourname.expensetracker.domain.model.dashboard.FinancialWeather
import com.yourname.expensetracker.domain.model.dashboard.SpendingSummary
import com.yourname.expensetracker.domain.model.dashboard.WeatherState
import com.yourname.expensetracker.domain.text.DashboardTextKeys
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.usecase.savings.LifestyleSavingsPromptUseCase
import com.yourname.expensetracker.domain.usecase.savings.MonthlySavingsSweepUseCase
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * P5-013 (RP-07): the month-over-month insight must compare like with like —
 * the canonical [com.yourname.expensetracker.domain.analytics.SpendingPace].projectedTotal
 * for the CURRENT month against the COMPLETED previous-month total.
 *
 * Plan contract (RP-07-runway-trend-polish §P5-013):
 * - Comparing MTD with a full previous month produced a false "spent less"
 *   message early in every month — that regression is pinned by the
 *   day-three fixture below, which the old MTD comparison read as "spent less".
 * - A missing baseline or an unavailable projection skips the MoM branch and
 *   retains the today-spent fallback; neither value is ever coerced to zero.
 * - The >20% spike threshold and text keys are unchanged.
 *
 * All fixtures drive the real path: real SpendingPaceCalculator over the
 * normalized expenses (same harness as ComputeDashboardWidgetsUseCasePaceWiringTest),
 * assertions on the emitted [DashboardWidget.NaturalLanguageInsight] message key.
 */
class ComputeDashboardWidgetsInsightMoMTest {

    private var fixedNowMs: Long = 0L
    private val timeProvider = object : TimeProvider {
        override fun now(): Long = fixedNowMs
    }
    private lateinit var computeUseCase: ComputeDashboardWidgetsUseCase

    @Before
    fun setup() {
        val insightsEngine = mockk<com.yourname.expensetracker.domain.analytics.InsightsEngine>(relaxed = true)
        val monteCarloSimulator = mockk<MonteCarloSpendingSimulator>(relaxed = true)
        coEvery { monteCarloSimulator.simulate(any(), any(), any()) } returns null
        val healthScoreV2 = mockk<FinancialHealthScoreV2>(relaxed = true)
        coEvery { healthScoreV2.calculateHealthScore(any(), any()) } returns HealthScoreOutcome.Available(
            FinancialHealthResult(
                overallScore = 50,
                savingsRateScore = 50,
                runwayScore = 50,
                budgetAdherenceScore = 50,
                billReliabilityScore = 50,
                factorContributions = emptyList(),
                trend = HealthTrend.STABLE,
                recommendation = null
            )
        )
        val lifestyleSavingsPromptUseCase = mockk<LifestyleSavingsPromptUseCase>(relaxed = true)
        coEvery { lifestyleSavingsPromptUseCase.evaluateAndPrompt() } returns null
        val monthlySavingsSweepUseCase = mockk<MonthlySavingsSweepUseCase>(relaxed = true)
        coEvery { monthlySavingsSweepUseCase.computeSweepRecommendation() } returns null
        val computeMoneyRadarUseCase = mockk<ComputeMoneyRadarUseCase>(relaxed = true)
        coEvery { computeMoneyRadarUseCase.compute() } returns MoneyRadarData(
            urgencyScore = 0,
            urgencyLevel = UrgencyLevel.GREEN,
            dueBills = emptyList(),
            anomalyAlerts = emptyList(),
            budgetRisk = null,
            topReasons = emptyList(),
            primaryCta = null
        )
        val stressForecastEngine = mockk<FinancialStressForecastEngine>(relaxed = true)
        coEvery { stressForecastEngine.computeStressForecast() } returns StressForecastResult(
            horizons = listOf(
                StressHorizon(30, 0.0, 0.0, 0.0, StressRiskLevel.LOW, 0.0, 0.0, 0.0)
            ),
            overallRiskLevel = StressRiskLevel.LOW,
            earliestCrunchDate = null,
            recommendations = emptyList()
        )
        val currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true)
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        val multiCurrencyRepository = mockk<MultiCurrencyRepository>(relaxed = true)
        coEvery { multiCurrencyRepository.getHomeCurrencyPurchaseTotal(any(), any()) } returns
            MoneyAggregate.empty(CurrencyCode("EUR"))

        // Echo stub (see ComputeDashboardWidgetsUseCasePaceWiringTest): the assembler
        // must faithfully carry the use case's enriched SpendingPace through.
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

        computeUseCase = ComputeDashboardWidgetsUseCase(
            writeBarrier = mockk(relaxed = true),
            insightsEngine = insightsEngine,
            synthesisEngine = SynthesisEngine(timeProvider, currencyConverter = mockk(relaxed = true)),
            monteCarloSimulator = monteCarloSimulator,
            timeProvider = timeProvider,
            healthCalculator = mockk(relaxed = true),
            healthScoreV2 = healthScoreV2,
            lifestyleSavingsPromptUseCase = lifestyleSavingsPromptUseCase,
            monthlySavingsSweepUseCase = monthlySavingsSweepUseCase,
            computeMoneyRadarUseCase = computeMoneyRadarUseCase,
            stressForecastEngine = stressForecastEngine,
            forecastInputAssembler = forecastInputAssembler,
            currencyConverter = mockk<CurrencyConverter>(relaxed = true),
            currencySettingsRepository = currencySettingsRepository,
            multiCurrencyRepository = multiCurrencyRepository,
            spendingPaceCalculator = com.yourname.expensetracker.domain.analytics.SpendingPaceCalculator(timeProvider)
        )
    }

    @Test
    fun `day-three MTD below last month but projection above it emits the spike message`() = runTest {
        // "Now" = June 3 2026 → daysElapsed = 3, daysInMonth = 30. May (completed) = 300.
        // June MTD = 60. Old MTD comparison: 60 - 300 < 0 → "spent less" (the bug).
        // Canonical projection (SpendingPaceProjection, blended because daysElapsed < 5):
        //   linear      = 60 * 30 / 3            = 600
        //   baselineMth = (300 / 31) * 30        = 290.3226
        //   w = 3/5 → projection = 290.3226*0.4 + 600*0.6 = 476.129
        // diff = 476.129 - 300 = 176.129 > 300 * 0.2 → spent-HIGHER.
        fixedNowMs = toEpochMs(2026, 6, 3, 12, 0)
        val juneStart = TimePeriodUtils.getStartOfMonth(fixedNowMs)

        val result = computeUseCase.compute(
            createProcessedData(
                listOf(
                    purchase(1L, 300.0, toEpochMs(2026, 5, 10, 12, 0)),
                    purchase(2L, 30.0, juneStart + DAY_MS),
                    purchase(3L, 30.0, juneStart + 2 * DAY_MS)
                )
            )
        )

        val insight = singleInsight(result)
        assertMessageKey(insight, DashboardTextKeys.WIDGET_INSIGHT_SPENT_HIGHER_FORMAT)
        assertEquals(
            "the reported spike amount must be the projected diff, not MTD",
            176.129,
            (insight.text as UiText.MessageKey).args.first() as Double,
            0.01
        )
    }

    @Test
    fun `genuine under-pace projection emits spent-less`() = runTest {
        // "Now" = June 15 → daysElapsed = 15 (≥ 5, pure linear projection).
        // May = 300; June MTD = 120 → projection = 120 * 30 / 15 = 240.
        // diff = 240 - 300 = -60 < 0 → spent-LESS, reporting 60.
        fixedNowMs = toEpochMs(2026, 6, 15, 12, 0)
        val juneStart = TimePeriodUtils.getStartOfMonth(fixedNowMs)

        val result = computeUseCase.compute(
            createProcessedData(
                listOf(
                    purchase(1L, 300.0, toEpochMs(2026, 5, 10, 12, 0)),
                    purchase(2L, 60.0, juneStart + DAY_MS),
                    purchase(3L, 60.0, juneStart + 10 * DAY_MS)
                )
            )
        )

        val insight = singleInsight(result)
        assertMessageKey(insight, DashboardTextKeys.WIDGET_INSIGHT_SPENT_LESS_FORMAT)
        assertEquals(
            "the reported saving must come from the projection",
            60.0,
            (insight.text as UiText.MessageKey).args.first() as Double,
            0.01
        )
    }

    @Test
    fun `projection spike above twenty percent of previous month emits spent-higher`() = runTest {
        // "Now" = June 15 → pure linear projection. May = 300; June MTD = 200 →
        // projection = 200 * 30 / 15 = 400. diff = 100 > 300 * 0.2 → spent-HIGHER.
        fixedNowMs = toEpochMs(2026, 6, 15, 12, 0)
        val juneStart = TimePeriodUtils.getStartOfMonth(fixedNowMs)

        val result = computeUseCase.compute(
            createProcessedData(
                listOf(
                    purchase(1L, 300.0, toEpochMs(2026, 5, 10, 12, 0)),
                    purchase(2L, 100.0, juneStart + DAY_MS),
                    purchase(3L, 100.0, juneStart + 10 * DAY_MS)
                )
            )
        )

        val insight = singleInsight(result)
        assertMessageKey(insight, DashboardTextKeys.WIDGET_INSIGHT_SPENT_HIGHER_FORMAT)
        assertEquals(
            100.0,
            (insight.text as UiText.MessageKey).args.first() as Double,
            0.01
        )
    }

    @Test
    fun `no previous baseline skips MoM branch and keeps the today-spent fallback`() = runTest {
        // No completed previous month → calculator returns previousMonthTotal = null
        // (NO_BASELINE). The MoM branch must be skipped — never coerced to zero —
        // and the today-spent fallback must still fire for today's purchase.
        fixedNowMs = toEpochMs(2026, 6, 15, 12, 0)
        val juneStart = TimePeriodUtils.getStartOfMonth(fixedNowMs)

        val result = computeUseCase.compute(
            createProcessedData(
                listOf(
                    purchase(1L, 60.0, juneStart + DAY_MS),
                    purchase(2L, 60.0, juneStart + 10 * DAY_MS),
                    // Today at 08:00 ("now" is 12:00) — the normalized today
                    // aggregate is a half-open [todayStart, now) window, so a
                    // purchase stamped at exactly `now` would not count.
                    purchase(3L, 25.0, toEpochMs(2026, 6, 15, 8, 0))
                )
            )
        )

        val insight = singleInsight(result)
        assertMessageKey(insight, DashboardTextKeys.WIDGET_INSIGHT_TODAY_SPENT_FORMAT)
        assertEquals(
            "the today fallback keeps reporting today's spend",
            25.0,
            (insight.text as UiText.MessageKey).args.first() as Double,
            0.01
        )
    }

    @Test
    fun `no insight is emitted when projection lands within the spike band`() = runTest {
        // Projection within ±20% of the previous month → neither message; the
        // insight widget is suppressed even though today had no spend.
        // May = 300; June MTD = 150 at June 15 → projection = 300 = previous → null.
        fixedNowMs = toEpochMs(2026, 6, 15, 12, 0)
        val juneStart = TimePeriodUtils.getStartOfMonth(fixedNowMs)

        val result = computeUseCase.compute(
            createProcessedData(
                listOf(
                    purchase(1L, 300.0, toEpochMs(2026, 5, 10, 12, 0)),
                    purchase(2L, 75.0, juneStart + DAY_MS),
                    purchase(3L, 75.0, juneStart + 10 * DAY_MS)
                )
            )
        )

        assertTrue(
            "an on-pace projection must not fabricate an insight",
            result.allWidgets.none { it is DashboardWidget.NaturalLanguageInsight }
        )
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun singleInsight(result: CompiledDashboardData): DashboardWidget.NaturalLanguageInsight {
        val insights = result.allWidgets.filterIsInstance<DashboardWidget.NaturalLanguageInsight>()
        assertEquals("exactly one insight widget is expected", 1, insights.size)
        return insights.first()
    }

    private fun assertMessageKey(insight: DashboardWidget.NaturalLanguageInsight, key: String) {
        assertTrue(
            "insight text must be a MessageKey but was ${insight.text::class.simpleName}",
            insight.text is UiText.MessageKey
        )
        assertEquals(key, (insight.text as UiText.MessageKey).key)
    }

    private fun purchase(id: Long, amount: Double, date: Long) = Expense(
        id = id, amount = amount, currency = "EUR", merchant = "M$id",
        transactionType = TransactionType.PURCHASE, date = date,
        categoryId = 7L, isNotMine = false, isSharedExpense = false,
        isManualEntry = false
    )

    private fun createProcessedData(expenses: List<Expense>): ProcessedDashboardData {
        val dashboardExpenses = expenses.map { it.toDashboardExpense() }
        val data = DashboardData(
            expenses = dashboardExpenses,
            categories = emptyList(),
            budgetStatuses = emptyList(),
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
            plannedExpenses = emptyList(),
            goals = emptyList()
        )
        val summary = SpendingSummary(
            totalSpent = 0.0,
            previousTotalSpent = null,
            changePercent = null,
            dailyHistory = emptyList(),
            previousDailyHistory = emptyList(),
            transactionCount = expenses.size
        )
        return ProcessedDashboardData(data = data, summary = summary, categoryBreakdown = emptyList())
    }

    private fun Expense.toDashboardExpense(): DashboardExpense = DashboardExpense(
        id = id,
        amount = amount,
        effectiveAmount = effectiveAmount,
        currency = currency,
        merchant = merchant,
        transactionType = when (transactionType) {
            TransactionType.PURCHASE -> com.yourname.expensetracker.domain.model.dashboard.DashboardTransactionType.PURCHASE
            TransactionType.WITHDRAWAL -> com.yourname.expensetracker.domain.model.dashboard.DashboardTransactionType.WITHDRAWAL
            TransactionType.TRANSFER -> com.yourname.expensetracker.domain.model.dashboard.DashboardTransactionType.TRANSFER
            TransactionType.DEPOSIT -> com.yourname.expensetracker.domain.model.dashboard.DashboardTransactionType.DEPOSIT
            TransactionType.UNKNOWN -> com.yourname.expensetracker.domain.model.dashboard.DashboardTransactionType.UNKNOWN
        },
        date = date,
        categoryId = categoryId,
        isNotMine = isNotMine,
        isManualEntry = isManualEntry
    )

    private fun toEpochMs(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long {
        return LocalDateTime.of(year, month, day, hour, minute, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }

    companion object {
        private const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}
