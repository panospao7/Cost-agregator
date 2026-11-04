package com.yourname.expensetracker.domain.usecase.dashboard

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
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
import com.yourname.expensetracker.domain.model.dashboard.BudgetStatusSnapshot
import com.yourname.expensetracker.domain.model.dashboard.DashboardCategory
import com.yourname.expensetracker.domain.model.dashboard.DashboardExpense
import com.yourname.expensetracker.domain.model.dashboard.DashboardTransactionType
import com.yourname.expensetracker.domain.model.dashboard.DomainBlockStatus
import com.yourname.expensetracker.domain.model.dashboard.DomainDayBudgetStatus
import com.yourname.expensetracker.domain.model.dashboard.FinancialWeather
import com.yourname.expensetracker.domain.model.dashboard.SpendingSummary
import com.yourname.expensetracker.domain.model.dashboard.WeatherState
import com.yourname.expensetracker.domain.usecase.savings.LifestyleSavingsPromptUseCase
import com.yourname.expensetracker.domain.usecase.savings.MonthlySavingsSweepUseCase
import com.yourname.expensetracker.domain.usecase.savings.SavingsSweepComputationResult
import com.yourname.expensetracker.domain.util.TimeProvider
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
 * Slice S2 of fix-1.0.4-block-party-plan: P-05-1 (the Block Party limit is the budget,
 * never month spend) and P-05-6 (only own PURCHASE rows feed the day actuals and
 * topTransactions).
 *
 * Uses a REAL [SynthesisEngine] (only its currency converter is a mock), so target and
 * actual numbers are the engine's own. "Now" is fixed to 2026-06-15 12:00 in the JVM
 * default zone, and every fixture date is built in that same zone at midday, so the
 * result does not depend on today's date or the device timezone. June has 30 days.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ComputeDashboardWidgetsUseCaseBlockPartyTest {

    private var fixedNowMs: Long = 0L
    private val timeProvider = object : TimeProvider {
        override fun now(): Long = fixedNowMs
    }
    private lateinit var computeUseCase: ComputeDashboardWidgetsUseCase

    // ---------------------------------------------------------------- P-05-1

    /**
     * Fails if the limit passed to the engine is `monthAggregate.displayAmount` (500 here,
     * giving a base of 16.667) instead of the overall budget.
     */
    @Test
    fun `P-05-1 limit is the overall budget not month spend`() = runTest {
        fixedNowMs = at(15, 12)
        val expenses = listOf(purchase(1L, 300.0, at(2, 12)), purchase(2L, 200.0, at(4, 12)))
        val budget = budget(null, 2000.0, spent = 500.0)

        val days = blockDays(computeUseCase.compute(data(expenses, listOf(budget))))

        assertEquals(30, days.size)
        days.forEach { day ->
            assertEquals("base day ${day.dayOfMonth}", BASE_2000_OVER_30, day.baseTarget, TOL)
            assertEquals("target day ${day.dayOfMonth}", BASE_2000_OVER_30, day.targetBudget, TOL)
        }
    }

    /** Fails if targets move with month spend (limit sourced from monthAggregate). */
    @Test
    fun `P-05-1 targets do not change when spend changes`() = runTest {
        fixedNowMs = at(15, 12)
        val budget = budget(null, 2000.0, spent = 500.0)
        val low = blockDays(computeUseCase.compute(data(listOf(purchase(1L, 100.0, at(2, 12))), listOf(budget))))
        val high = blockDays(
            computeUseCase.compute(
                data(
                    listOf(purchase(1L, 100.0, at(2, 12)), purchase(2L, 1400.0, at(5, 12)), purchase(3L, 300.0, at(6, 12))),
                    listOf(budget)
                )
            )
        )
        val none = blockDays(computeUseCase.compute(data(emptyList(), listOf(budget))))

        assertEquals(30, low.size)
        assertEquals(low.map { it.targetBudget }.size, high.size)
        low.indices.forEach { i ->
            assertEquals("low vs high day ${i + 1}", low[i].targetBudget, high[i].targetBudget, TOL)
            assertEquals("low vs none day ${i + 1}", low[i].targetBudget, none[i].targetBudget, TOL)
            assertEquals(BASE_2000_OVER_30, high[i].targetBudget, TOL)
        }
    }

    /** No budget at all: no Block Party list and no widget. */
    @Test
    fun `P-05-1 no budget gives empty list and no widget`() = runTest {
        fixedNowMs = at(15, 12)
        val result = computeUseCase.compute(data(listOf(purchase(1L, 20.0, at(3, 12))), emptyList()))

        assertTrue(blockDays(result).isEmpty())
        assertTrue(result.allWidgets.none { it is DashboardWidget.BudgetBlockParty })
    }

    /** Overall budget incomplete in the home currency (several ways): unavailable. */
    @Test
    fun `P-05-1 incomplete overall budget gives empty list`() = runTest {
        fixedNowMs = at(15, 12)
        val complete = budget(null, 2000.0)
        val variants = listOf(
            complete.copy(isPartial = true),
            complete.copy(currency = "USD"),
            complete.copy(currency = null),
            complete.copy(currencyAssumption = "LEGACY_DEFAULT"),
            complete.copy(percentKnown = false)
        )
        variants.forEach { variant ->
            val result = computeUseCase.compute(data(listOf(purchase(1L, 20.0, at(3, 12))), listOf(variant)))
            assertTrue("variant $variant", blockDays(result).isEmpty())
            assertTrue(result.allWidgets.none { it is DashboardWidget.BudgetBlockParty })
        }
        // Control: the same data with a complete budget does produce the card.
        assertEquals(30, blockDays(computeUseCase.compute(data(listOf(purchase(1L, 20.0, at(3, 12))), listOf(complete)))).size)
    }

    /**
     * No overall budget, category budget in another currency: summing would mix
     * currencies, so the card is unavailable. Fails if the S2 completeness guard is removed.
     */
    @Test
    fun `P-05-1 no overall budget with foreign currency category budget gives empty list`() = runTest {
        fixedNowMs = at(15, 12)
        val result = computeUseCase.compute(
            data(emptyList(), listOf(budget(7L, 800.0, currency = "USD")))
        )
        assertTrue(blockDays(result).isEmpty())
        assertTrue(result.allWidgets.none { it is DashboardWidget.BudgetBlockParty })
    }

    /** No overall budget, category budget incomplete: unavailable. */
    @Test
    fun `P-05-1 no overall budget with incomplete category budget gives empty list`() = runTest {
        fixedNowMs = at(15, 12)
        val incompleteVariants = listOf(
            budget(7L, 800.0, isPartial = true),
            budget(7L, 800.0, assumption = "LEGACY_DEFAULT"),
            budget(7L, 800.0, currency = null)
        )
        incompleteVariants.forEach { incomplete ->
            val result = computeUseCase.compute(data(emptyList(), listOf(incomplete)))
            assertTrue("variant $incomplete", blockDays(result).isEmpty())
        }
    }

    /** One complete and one foreign category budget: the mixed sum must not be shown. */
    @Test
    fun `P-05-1 no overall budget with one complete and one foreign category budget gives empty list`() = runTest {
        fixedNowMs = at(15, 12)
        val result = computeUseCase.compute(
            data(emptyList(), listOf(budget(7L, 300.0), budget(8L, 500.0, currency = "USD")))
        )
        assertTrue(blockDays(result).isEmpty())
    }

    /**
     * BL-02: category budgets are partial limits (all purchases are compared with the limit),
     * so a category-only setup, even with every category budget complete in the home currency,
     * hides the card. Fails on 92346ad0, which used the category sum (800 / 30) as the limit.
     */
    @Test
    fun `BL-02 category-only complete budgets give empty list and no widget`() = runTest {
        fixedNowMs = at(15, 12)
        val expenses = listOf(purchase(1L, 900.0, at(2, 12)))
        val result = computeUseCase.compute(data(expenses, listOf(budget(7L, 300.0), budget(8L, 500.0))))

        assertTrue(blockDays(result).isEmpty())
        assertTrue(result.allWidgets.none { it is DashboardWidget.BudgetBlockParty })
    }

    /** BL-02: an overall budget of 0 does not fall back to complete category budgets. */
    @Test
    fun `BL-02 zero overall budget with complete category budgets gives empty list`() = runTest {
        fixedNowMs = at(15, 12)
        val result = computeUseCase.compute(
            data(emptyList(), listOf(budget(null, 0.0), budget(7L, 300.0), budget(8L, 500.0)))
        )
        assertTrue(blockDays(result).isEmpty())
        assertTrue(result.allWidgets.none { it is DashboardWidget.BudgetBlockParty })
    }

    /**
     * Control: with an overall budget the category budgets neither change nor hide the card;
     * the limit is the overall amount only (2000, not 2000 + 800).
     */
    @Test
    fun `BL-02 overall budget alongside complete category budgets uses the overall amount only`() = runTest {
        fixedNowMs = at(15, 12)
        val days = blockDays(
            computeUseCase.compute(
                data(emptyList(), listOf(budget(null, 2000.0), budget(7L, 300.0), budget(8L, 500.0)))
            )
        )
        assertEquals(30, days.size)
        days.forEach { assertEquals(BASE_2000_OVER_30, it.targetBudget, TOL) }
    }

    /** A positive complete overall budget wins; a foreign category budget must not block it. */
    @Test
    fun `P-05-1 positive overall budget ignores foreign currency category budget`() = runTest {
        fixedNowMs = at(15, 12)
        val days = blockDays(
            computeUseCase.compute(
                data(emptyList(), listOf(budget(null, 2000.0), budget(7L, 800.0, currency = "USD")))
            )
        )
        assertEquals(30, days.size)
        days.forEach { assertEquals(BASE_2000_OVER_30, it.targetBudget, TOL) }
    }

    /** Zero limit: the card is unavailable rather than 30 days with a zero target. */
    @Test
    fun `P-05-1 zero limit gives empty list`() = runTest {
        fixedNowMs = at(15, 12)
        val overallZero = computeUseCase.compute(
            data(listOf(purchase(1L, 20.0, at(3, 12))), listOf(budget(null, 0.0)))
        )
        assertTrue(blockDays(overallZero).isEmpty())
        assertTrue(overallZero.allWidgets.none { it is DashboardWidget.BudgetBlockParty })

        val categoriesZero = computeUseCase.compute(
            data(listOf(purchase(1L, 20.0, at(3, 12))), listOf(budget(7L, 0.0)))
        )
        assertTrue(blockDays(categoriesZero).isEmpty())
    }

    // ---------------------------------------------------------------- P-05-6

    /**
     * Fails if the engine receives `ctx.expenseEntities` (all types): the 1000 deposit would
     * make the day actual 1020 and OVER_BUDGET, and appear in topTransactions.
     */
    @Test
    fun `P-05-6 deposit does not count toward actual or topTransactions`() = runTest {
        fixedNowMs = at(15, 12)
        val expenses = listOf(
            purchase(1L, 20.0, at(3, 12)),
            purchase(2L, 1000.0, at(3, 9)).copy(transactionType = TransactionType.DEPOSIT)
        )

        val day3 = blockDays(computeUseCase.compute(data(expenses, listOf(budget(null, 2000.0)))))
            .single { it.dayOfMonth == 3 }

        assertEquals(20.0, day3.actualSpent, TOL)
        assertEquals(DomainBlockStatus.UNDER_BUDGET, day3.status)
        assertEquals(listOf(1L), day3.topTransactions.map { it.id })
    }

    /** Same contract for a transfer. */
    @Test
    fun `P-05-6 transfer does not count toward actual or topTransactions`() = runTest {
        fixedNowMs = at(15, 12)
        val expenses = listOf(
            purchase(1L, 20.0, at(3, 12)),
            purchase(2L, 1000.0, at(3, 9)).copy(transactionType = TransactionType.TRANSFER)
        )

        val day3 = blockDays(computeUseCase.compute(data(expenses, listOf(budget(null, 2000.0)))))
            .single { it.dayOfMonth == 3 }

        assertEquals(20.0, day3.actualSpent, TOL)
        assertEquals(DomainBlockStatus.UNDER_BUDGET, day3.status)
        assertEquals(listOf(1L), day3.topTransactions.map { it.id })
    }

    /** Control: the purchase alone yields the same day, so the type filter is the only difference. */
    @Test
    fun `P-05-6 control purchase only day`() = runTest {
        fixedNowMs = at(15, 12)
        val day3 = blockDays(
            computeUseCase.compute(data(listOf(purchase(1L, 20.0, at(3, 12))), listOf(budget(null, 2000.0))))
        ).single { it.dayOfMonth == 3 }

        assertEquals(20.0, day3.actualSpent, TOL)
        assertEquals(DomainBlockStatus.UNDER_BUDGET, day3.status)
        assertEquals(listOf(1L), day3.topTransactions.map { it.id })
    }

    /** A not-mine row is absent from topTransactions and adds nothing to actual. */
    @Test
    fun `P-05-6 not mine row is absent from topTransactions`() = runTest {
        fixedNowMs = at(15, 12)
        val expenses = listOf(
            purchase(1L, 20.0, at(3, 12)),
            purchase(2L, 500.0, at(3, 9)).copy(isNotMine = true)
        )

        val day3 = blockDays(computeUseCase.compute(data(expenses, listOf(budget(null, 2000.0)))))
            .single { it.dayOfMonth == 3 }

        assertTrue(day3.topTransactions.none { it.id == 2L })
        assertEquals(listOf(1L), day3.topTransactions.map { it.id })
        assertEquals(20.0, day3.actualSpent, TOL)
    }

    /**
     * A neutralised row (spendingExclusionReason set, so effectiveAmount is 0) adds nothing to
     * actual and is absent from topTransactions. The fixture helper forwards the exclusion reason,
     * so the use case sees the same flagged row production does.
     */
    @Test
    fun `P-05-6 neutralised row contributes zero to actual and is absent from topTransactions`() = runTest {
        fixedNowMs = at(15, 12)
        val expenses = listOf(
            purchase(1L, 20.0, at(3, 12)),
            purchase(2L, 300.0, at(3, 9)).copy(spendingExclusionReason = "TEST_NEUTRALISED")
        )

        val day3 = blockDays(computeUseCase.compute(data(expenses, listOf(budget(null, 2000.0)))))
            .single { it.dayOfMonth == 3 }

        assertEquals(20.0, day3.actualSpent, TOL)
        assertEquals(DomainBlockStatus.UNDER_BUDGET, day3.status)
        assertTrue(day3.topTransactions.any { it.id == 1L })
        assertTrue(day3.topTransactions.none { it.id == 2L })
    }

    // ---------------------------------------------------------------- Recent Activity vs totals

    /**
     * A flagged own purchase stays in Recent Activity (shown with a label) but is out of the month
     * total, the trend chart values and the top categories. A deposit and a not-mine purchase are
     * not in Recent Activity at all.
     */
    @Test
    fun `Recent Activity lists a flagged purchase while totals chart and top categories ignore it`() = runTest {
        fixedNowMs = at(15, 12)
        val expenses = listOf(
            purchase(1L, 20.0, at(3, 12)),
            purchase(2L, 300.0, at(3, 9)).copy(spendingExclusionReason = "TEST_NEUTRALISED"),
            purchase(3L, 150.0, at(4, 12)).copy(transactionType = TransactionType.DEPOSIT),
            purchase(4L, 500.0, at(5, 12)).copy(isNotMine = true)
        )

        val result = computeUseCase.compute(
            data(expenses, emptyList(), categories = listOf(DashboardCategory(7L, "Food", "F", "#112233")))
        )

        val recentIds = result.allWidgets.filterIsInstance<DashboardWidget.RecentTransactions>()
            .single().expenses.map { it.id }
        assertTrue("flagged purchase is listed", 2L in recentIds)
        assertTrue("normal purchase is listed", 1L in recentIds)
        assertTrue("deposit is not listed", 3L !in recentIds)
        assertTrue("not-mine purchase is not listed", 4L !in recentIds)

        val period = result.allWidgets.filterIsInstance<DashboardWidget.PeriodSummary>().single()
        assertEquals("month total", 20.0, period.monthSpent, TOL)

        val currentMonth = result.allWidgets.filterIsInstance<DashboardWidget.SpendingTrend>()
            .single().series.single { it.isCurrentMonth }
        assertEquals("chart end value", 20.0f, currentMonth.data.last(), 0.0001f)

        val top = result.allWidgets.filterIsInstance<DashboardWidget.TopCategories>().single().categories
        assertEquals(1, top.size)
        assertEquals("top category amount", 20.0, top.single().moneyTotal.displayAmount, TOL)
    }

    /** Control: with no flagged row the same Recent Activity list and totals hold, so the flag is the only difference. */
    @Test
    fun `Recent Activity control without a flagged purchase`() = runTest {
        fixedNowMs = at(15, 12)
        val expenses = listOf(purchase(1L, 20.0, at(3, 12)), purchase(2L, 300.0, at(3, 9)))

        val result = computeUseCase.compute(
            data(expenses, emptyList(), categories = listOf(DashboardCategory(7L, "Food", "F", "#112233")))
        )

        assertEquals(
            setOf(1L, 2L),
            result.allWidgets.filterIsInstance<DashboardWidget.RecentTransactions>().single().expenses.map { it.id }.toSet()
        )
        assertEquals(
            320.0,
            result.allWidgets.filterIsInstance<DashboardWidget.PeriodSummary>().single().monthSpent,
            TOL
        )
    }

    // ---------------------------------------------------------------- harness

    private fun blockDays(result: CompiledDashboardData): List<DomainDayBudgetStatus> =
        result.allWidgets.filterIsInstance<DashboardWidget.BudgetBlockParty>().singleOrNull()?.days ?: emptyList()

    private fun budget(
        categoryId: Long?,
        amount: Double,
        currency: String? = "EUR",
        assumption: String = "USER_CONFIRMED",
        isPartial: Boolean = false,
        spent: Double = 0.0
    ) = BudgetStatusSnapshot(
        budgetCategoryId = categoryId,
        budgetAmount = amount,
        categoryName = null,
        spentAmount = spent,
        remainingAmount = amount - spent,
        percentUsed = 0.0,
        healthStatus = BudgetHealthStatus.ON_TRACK,
        // Real 30-day June period (half-open [Jun 1, Jul 1)): month-scale, so Block Party reads the limit.
        periodStart = at(1, 0),
        periodEnd = LocalDateTime.of(2026, 7, 1, 0, 0, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        isPartial = isPartial,
        currency = currency,
        currencyAssumption = assumption,
        // Runway (which gates Block Party) needs the budget's effective spend when the limit is positive.
        effectiveSpend = spent,
        effectiveHealthStatus = BudgetHealthStatus.ON_TRACK
    )

    private fun purchase(id: Long, amount: Double, date: Long) = Expense(
        id = id, amount = amount, currency = "EUR", merchant = "M$id",
        transactionType = TransactionType.PURCHASE, date = date,
        categoryId = 7L, isNotMine = false, isSharedExpense = false,
        isManualEntry = false
    )

    private fun data(
        expenses: List<Expense>,
        budgets: List<BudgetStatusSnapshot>,
        categories: List<DashboardCategory> = emptyList()
    ): ProcessedDashboardData {
        val dashboardData = DashboardData(
            expenses = expenses.map { it.toDashboardExpense() },
            categories = categories,
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
        return ProcessedDashboardData(data = dashboardData, summary = summary, categoryBreakdown = emptyList())
    }

    private fun Expense.toDashboardExpense(): DashboardExpense = DashboardExpense(
        id = id,
        amount = amount,
        effectiveAmount = effectiveAmount,
        currency = currency,
        merchant = merchant,
        transactionType = when (transactionType) {
            TransactionType.PURCHASE -> DashboardTransactionType.PURCHASE
            TransactionType.WITHDRAWAL -> DashboardTransactionType.WITHDRAWAL
            TransactionType.TRANSFER -> DashboardTransactionType.TRANSFER
            TransactionType.DEPOSIT -> DashboardTransactionType.DEPOSIT
            TransactionType.UNKNOWN -> DashboardTransactionType.UNKNOWN
        },
        date = date,
        categoryId = categoryId,
        isNotMine = isNotMine,
        isManualEntry = isManualEntry,
        spendingExclusionReason = spendingExclusionReason
    )

    /** Epoch millis of June [day] 2026 at [hour]:00 in the JVM default zone (same zone the engine uses). */
    private fun at(day: Int, hour: Int): Long =
        LocalDateTime.of(2026, 6, day, hour, 0, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    @Before
    fun setup() {
        val currencyConverter = mockk<CurrencyConverter>(relaxed = true)
        val insightsEngine = mockk<com.yourname.expensetracker.domain.analytics.InsightsEngine>(relaxed = true)
        val monteCarloSimulator = mockk<MonteCarloSpendingSimulator>(relaxed = true)
        coEvery { monteCarloSimulator.simulate(any(), any(), any(), any(), any()) } returns null
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
        coEvery { monthlySavingsSweepUseCase.computeSweepRecommendationResult() } returns
            SavingsSweepComputationResult.NoRecommendation()
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
            horizons = listOf(StressHorizon(30, 0.0, 0.0, 0.0, StressRiskLevel.LOW, 0.0, 0.0, 0.0)),
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
            // Real engine: block-party targets and actuals are the engine's own numbers.
            synthesisEngine = SynthesisEngine(timeProvider, currencyConverter = currencyConverter),
            monteCarloSimulator = monteCarloSimulator,
            timeProvider = timeProvider,
            healthCalculator = mockk(relaxed = true),
            healthScoreV2 = healthScoreV2,
            lifestyleSavingsPromptUseCase = lifestyleSavingsPromptUseCase,
            monthlySavingsSweepUseCase = monthlySavingsSweepUseCase,
            computeMoneyRadarUseCase = computeMoneyRadarUseCase,
            stressForecastEngine = stressForecastEngine,
            forecastInputAssembler = forecastInputAssembler,
            currencyConverter = currencyConverter,
            currencySettingsRepository = currencySettingsRepository,
            multiCurrencyRepository = multiCurrencyRepository,
            spendingPaceCalculator = com.yourname.expensetracker.domain.analytics.SpendingPaceCalculator(timeProvider)
        )
    }

    companion object {
        private const val TOL = 1e-6
        /** Limit 2000 spread over the 30 days of June, no bills, planned items or goal reserves. */
        private const val BASE_2000_OVER_30 = 2000.0 / 30.0
    }
}
