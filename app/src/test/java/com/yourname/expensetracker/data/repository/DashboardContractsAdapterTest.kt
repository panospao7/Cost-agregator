package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.analytics.SpendingPace
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.forecasting.FinancialStressForecastEngine
import com.yourname.expensetracker.domain.forecasting.MonteCarloSpendingSimulator
import com.yourname.expensetracker.domain.forecasting.StressForecastResult
import com.yourname.expensetracker.domain.forecasting.StressHorizon
import com.yourname.expensetracker.domain.forecasting.StressRiskLevel
import com.yourname.expensetracker.domain.health.FinancialHealthResult
import com.yourname.expensetracker.domain.health.FinancialHealthScoreV2
import com.yourname.expensetracker.domain.health.HealthScoreOutcome
import com.yourname.expensetracker.domain.health.HealthScoreUnavailableReason
import com.yourname.expensetracker.domain.health.HealthTrend
import com.yourname.expensetracker.domain.logic.SynthesisEngine
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.model.dashboard.FinancialWeather
import com.yourname.expensetracker.domain.model.dashboard.WeatherState
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.usecase.dashboard.ComputeDashboardWidgetsUseCase
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardWidget
import com.yourname.expensetracker.domain.usecase.savings.LifestyleSavingsPromptUseCase
import com.yourname.expensetracker.domain.usecase.savings.MonthlySavingsSweepUseCase
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.model.RecurringPattern
import com.yourname.expensetracker.domain.util.TimeBoundaryTicker
import com.yourname.expensetracker.domain.forecasting.ForecastInputAssembler
import com.yourname.expensetracker.domain.forecasting.NormalizedForecastInput
import com.yourname.expensetracker.domain.model.dashboard.DashboardCategory
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DashboardContractsAdapterTest {

    private val expenseRepository = mockk<ExpenseRepository>(relaxed = true)
    private val categoryRepository = mockk<CategoryRepository>(relaxed = true)
    private val budgetRepository = mockk<BudgetRepository>(relaxed = true)
    private val reviewQueueRepository = mockk<ReviewQueueRepository>(relaxed = true)
    private val financialWeatherRepository = mockk<FinancialWeatherRepository>(relaxed = true)
    private val savingsGoalRepository = mockk<com.yourname.expensetracker.domain.savings.SavingsGoalRepository>(relaxed = true)
    private val analyticsRepository = mockk<AnalyticsRepository>(relaxed = true)
    private val recurringExpenseRepository = mockk<RecurringExpenseRepository>(relaxed = true)
    private val plannedExpenseRepository = mockk<PlannedExpenseRepository>(relaxed = true)
    private val timeBoundaryTicker = mockk<TimeBoundaryTicker>(relaxed = true)

    private lateinit var adapter: DashboardContractsAdapter

    @Before
    fun setup() {
        adapter = DashboardContractsAdapter(
            expenseRepository = expenseRepository,
            categoryRepository = categoryRepository,
            budgetRepository = budgetRepository,
            reviewQueueRepository = reviewQueueRepository,
            financialWeatherRepository = financialWeatherRepository,
            savingsGoalRepository = savingsGoalRepository,
            analyticsRepository = analyticsRepository,
            recurringExpenseRepository = recurringExpenseRepository,
            plannedExpenseRepository = plannedExpenseRepository,
            timeBoundaryTicker = timeBoundaryTicker
        )
    }

    @Test
    fun `observeRecurringPatterns uses confirmed recurring feed for dashboard forecast consumers`() = runTest {
        val confirmedPattern = recurringPattern("Confirmed Rent", 900.0)
        val mergedSuggestion = recurringPattern("Suggested Gym", 45.0)

        every { financialWeatherRepository.getConfirmedRecurringPatterns() } returns flowOf(listOf(confirmedPattern))
        every { financialWeatherRepository.getAllRecurringPatterns() } returns flowOf(listOf(mergedSuggestion))

        val result = adapter.observeRecurringPatterns().first()

        assertEquals(listOf("Confirmed Rent"), result.map { it.merchantName })
        verify(exactly = 1) { financialWeatherRepository.getConfirmedRecurringPatterns() }
        verify(exactly = 0) { financialWeatherRepository.getAllRecurringPatterns() }
    }

    @Test
    fun `observeBudgetStatuses propagates isPartial and conversionWarning to snapshot`() = runTest {
        val budget = com.yourname.expensetracker.data.database.entity.Budget(
            id = 1L,
            categoryId = null,
            amount = 100.0,
            currency = "USD",
            period = com.yourname.expensetracker.data.database.entity.BudgetPeriod.MONTHLY,
            startDate = 1_700_000_000_000L,
            isActive = true
        )
        val status = com.yourname.expensetracker.domain.budget.BudgetStatus(
            budget = budget,
            category = null,
            spentAmount = 50.0,
            remainingAmount = 0.0,
            percentUsed = 0f,
            healthStatus = com.yourname.expensetracker.domain.budget.BudgetHealthStatus.UNKNOWN,
            periodStart = 1_700_000_000_000L,
            periodEnd = 1_702_000_000_000L,
            effectiveLimit = 100.0,
            isPartial = true,
            conversionWarning = "Budget limit could not be converted from USD to EUR"
        )
        every { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(status))

        val result = adapter.observeBudgetStatuses().first()

        assertEquals(1, result.size)
        assertEquals(true, result[0].isPartial)
        assertEquals("Budget limit could not be converted from USD to EUR", result[0].conversionWarning)
        assertEquals(com.yourname.expensetracker.domain.budget.BudgetHealthStatus.UNKNOWN, result[0].healthStatus)
    }

    @Test
    fun `observeDashboardExpenses carries shared-expense identity across the boundary`() = runTest {
        // P5-004 (RP-05): the isSharedExpense flag must survive the adapter
        // mapping — downstream deposit exclusion is otherwise tautological.
        val now = 1_712_000_000_000L
        every { timeBoundaryTicker.dayBoundaryTicks() } returns flowOf(now)
        val sharedDeposit = com.yourname.expensetracker.data.database.entity.Expense(
            id = 1L, amount = 100.0, currency = "EUR",
            merchant = "Shared rent", transactionType = com.yourname.expensetracker.data.database.entity.TransactionType.DEPOSIT,
            date = now, categoryId = null, isNotMine = false,
            isSharedExpense = true, isManualEntry = false
        )
        val ownDeposit = sharedDeposit.copy(id = 2L, merchant = "Salary", isSharedExpense = false)
        every { expenseRepository.getExpensesWithCategoryInPeriod(any(), any()) } returns flowOf(
            listOf(
                com.yourname.expensetracker.data.database.model.ExpenseWithCategory(expense = sharedDeposit, category = null),
                com.yourname.expensetracker.data.database.model.ExpenseWithCategory(expense = ownDeposit, category = null)
            )
        )

        val result = adapter.observeDashboardExpenses().first()

        assertEquals(2, result.size)
        val byId = result.associateBy { it.id }
        assertEquals(true, byId[1L]?.isSharedExpense)
        assertEquals(false, byId[2L]?.isSharedExpense)
    }

    /**
     * P5-004 (RP-05) full round-trip through the REAL production mappers:
     * entity `isSharedExpense=true` → DashboardContractsAdapter.toDomainDashboard()
     * → DashboardExpense.isSharedExpense=true → ComputeDashboardWidgetsUseCase
     * .toExpenseEntity() (via compute()) → reconstructed entity keeps
     * isSharedExpense=true.
     *
     * The observable proof of the reconstructed entity is the deposit-exclusion
     * consequence (NEW-P5-003): a shared DEPOSIT must be excluded from dashboard
     * income while a shared PURCHASE remains ordinary spend. (The normalized
     * pipeline intentionally hardcodes isSharedExpense=false on NormalizedExpense,
     * so the flag's survival is only observable through the deposit filter.)
     */
    @Test
    fun `shared expense identity survives adapter to dashboard to entity round trip`() = runTest {
        // Fixed wall-clock timestamps in the JVM's default zone: `now` mid-month,
        // fixture expenses 9 days earlier — strictly inside [monthStart, now) in
        // every timezone, matching the current-period scoping of
        // produceDashboardNormalizedInput.
        val now = java.time.LocalDateTime.of(2024, 4, 15, 12, 0)
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val midMonthDate = java.time.LocalDateTime.of(2024, 4, 6, 12, 0)
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        every { timeBoundaryTicker.dayBoundaryTicks() } returns flowOf(now)
        val sharedDeposit = com.yourname.expensetracker.data.database.entity.Expense(
            id = 1L, amount = 100.0, currency = "EUR",
            merchant = "Shared rent", transactionType = com.yourname.expensetracker.data.database.entity.TransactionType.DEPOSIT,
            date = midMonthDate, categoryId = null, isNotMine = false,
            isSharedExpense = true, isManualEntry = false
        )
        val sharedPurchase = sharedDeposit.copy(
            id = 3L,
            merchant = "Shared dinner",
            transactionType = com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE,
            myShareAmount = 25.0
        )
        every { expenseRepository.getExpensesWithCategoryInPeriod(any(), any()) } returns flowOf(
            listOf(
                com.yourname.expensetracker.data.database.model.ExpenseWithCategory(expense = sharedDeposit, category = null),
                com.yourname.expensetracker.data.database.model.ExpenseWithCategory(expense = sharedPurchase, category = null)
            )
        )

        // Leg 1 — REAL production mapper: entity → DashboardExpense.
        val dashboardExpenses = adapter.observeDashboardExpenses().first()
        val dashboardById = dashboardExpenses.associateBy { it.id }
        val mappedDeposit = dashboardById[1L]
        val mappedPurchase = dashboardById[3L]
        assertEquals(true, mappedDeposit?.isSharedExpense)
        assertEquals(true, mappedPurchase?.isSharedExpense)

        // Leg 2 — REAL production use case: DashboardExpense → toExpenseEntity()
        // → produceDashboardNormalizedInput deposit filter. The shared DEPOSIT
        // must be excluded from income; the shared PURCHASE must remain spend.
        val insightsEngine = mockk<com.yourname.expensetracker.domain.analytics.InsightsEngine>(relaxed = true)
        coEvery { insightsEngine.getSpendingPaceSuspend(any()) } returns SpendingPace(
            currentMonthSpent = 0.0,
            daysElapsed = 1,
            daysInMonth = 31,
            projectedTotal = 0.0,
            previousMonthTotal = null,
            averageMonthlyTotal = null,
            pacePercentage = 100f,
            paceStatus = PaceStatus.NO_BASELINE,
            displayCurrency = "EUR"
        )
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
        val currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true)
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        val multiCurrencyRepository = mockk<MultiCurrencyRepository>(relaxed = true)
        coEvery { multiCurrencyRepository.getHomeCurrencyPurchaseTotal(any(), any()) } returns
            MoneyAggregate.empty(CurrencyCode("EUR"))
        val computeUseCase = ComputeDashboardWidgetsUseCase(
            writeBarrier = mockk(relaxed = true),
            insightsEngine = insightsEngine,
            synthesisEngine = SynthesisEngine(timeProvider = object : TimeProvider {
                override fun now(): Long = now
            }, currencyConverter = mockk(relaxed = true)),
            monteCarloSimulator = mockk<MonteCarloSpendingSimulator>(relaxed = true),
            timeProvider = object : TimeProvider {
                override fun now(): Long = now
            },
            healthCalculator = mockk(relaxed = true),
            healthScoreV2 = healthScoreV2,
            lifestyleSavingsPromptUseCase = mockk<LifestyleSavingsPromptUseCase>(relaxed = true).apply {
                coEvery { evaluateAndPrompt() } returns null
            },
            monthlySavingsSweepUseCase = mockk<MonthlySavingsSweepUseCase>(relaxed = true).apply {
                coEvery { computeSweepRecommendation() } returns null
            },
            computeMoneyRadarUseCase = mockk(relaxed = true),
            stressForecastEngine = mockk<FinancialStressForecastEngine>(relaxed = true).apply {
                coEvery { computeStressForecast() } returns StressForecastResult(
                    horizons = listOf(
                        StressHorizon(30, 0.0, 0.0, 0.0, StressRiskLevel.LOW, 0.0, 0.0, 0.0)
                    ),
                    overallRiskLevel = StressRiskLevel.LOW,
                    earliestCrunchDate = null,
                    recommendations = emptyList()
                )
            },
            forecastInputAssembler = mockk(relaxed = true),
            currencyConverter = mockk<CurrencyConverter>(relaxed = true),
            currencySettingsRepository = currencySettingsRepository,
            multiCurrencyRepository = multiCurrencyRepository,
            // RP-06 6a: real calculator + the test's fixed TimeProvider so pace math is real.
            spendingPaceCalculator = com.yourname.expensetracker.domain.analytics.SpendingPaceCalculator(
                object : TimeProvider {
                    override fun now(): Long = now
                }
            )
        )

        val data = com.yourname.expensetracker.domain.usecase.dashboard.DashboardData(
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
        val processedData = com.yourname.expensetracker.domain.usecase.dashboard.ProcessedDashboardData(
            data = data,
            summary = com.yourname.expensetracker.domain.model.dashboard.SpendingSummary(
                totalSpent = 0.0,
                previousTotalSpent = null,
                changePercent = null,
                dailyHistory = emptyList(),
                previousDailyHistory = emptyList(),
                transactionCount = 0
            ),
            categoryBreakdown = emptyList()
        )

        val compiled = computeUseCase.compute(processedData)

        // Deposit-exclusion consequence: the shared DEPOSIT is excluded from
        // income, so the canonical income aggregate is 0.0 despite the 100.0
        // shared deposit — only possible if the reconstructed entity kept
        // isSharedExpense=true. depositAggregate is the single source feeding
        // FinancialRunway.monthlyIncome (ComputeDashboardWidgetsUseCase).
        val normalizedInput = compiled.normalizedInput
            as com.yourname.expensetracker.domain.usecase.dashboard.DashboardNormalizedInputResult.Available
        assertEquals(0.0, normalizedInput.input.depositAggregate!!.displayAmount, 0.0001)
        // The shared PURCHASE remains ordinary spend (not excluded by the flag).
        assertEquals(25.0, compiled.totalSpent, 0.0001)
        val periodSummary = compiled.allWidgets.filterIsInstance<DashboardWidget.PeriodSummary>().single()
        assertEquals(25.0, periodSummary.monthSpent, 0.0001)
        // Same-input control: an OWN deposit is counted as income (proves the
        // exclusion above comes from isSharedExpense, not from a blanket rule).
        val ownDepositOnly = listOf(
            com.yourname.expensetracker.data.database.model.ExpenseWithCategory(
                expense = sharedDeposit.copy(id = 9L, isSharedExpense = false),
                category = null
            )
        )
        every { expenseRepository.getExpensesWithCategoryInPeriod(any(), any()) } returns flowOf(ownDepositOnly)
        val ownIncome = adapter.observeDashboardExpenses().first()
        assertEquals(false, ownIncome.single().isSharedExpense)
        val compiledOwn = computeUseCase.compute(
            processedData.copy(
                data = data.copy(expenses = ownIncome)
            )
        )
        val normalizedInputOwn = compiledOwn.normalizedInput
            as com.yourname.expensetracker.domain.usecase.dashboard.DashboardNormalizedInputResult.Available
        assertEquals(100.0, normalizedInputOwn.input.depositAggregate!!.displayAmount, 0.0001)
    }

    @Test
    fun `observeDashboardExpenses fetches the six-month trend window`() = runTest {
        val now = 1_712_000_000_000L
        every { timeBoundaryTicker.dayBoundaryTicks() } returns flowOf(now)
        val startSlot = slot<Long>()
        val endSlot = slot<Long>()
        every {
            expenseRepository.getExpensesWithCategoryInPeriod(capture(startSlot), capture(endSlot))
        } returns flowOf(emptyList())

        adapter.observeDashboardExpenses().first()

        // P5-005: the trend emits six calendar keys M-5..M-0, so the source
        // query must span [M-5 start, M-0 end) with calendar-safe bounds.
        assertEquals(TimePeriodUtils.getMonthRange(now, -5).first, startSlot.captured)
        assertEquals(TimePeriodUtils.getMonthRange(now, 0).second, endSlot.captured)
    }

    /**
     * P5-003 (RP-05 batch 2): the synthesis baseline (averageMonthlyTotal) must
     * come from COMPLETED-month history — day-3 MTD (60) must never be used as
     * the typical-month input when M-1 (200) and M-2 (100) hold history.
     */
    @Test
    fun `synthesis baseline uses completed-month history instead of current MTD`() = runTest {
        val zone = java.time.ZoneId.systemDefault()
        fun epoch(date: java.time.LocalDateTime) = date.atZone(zone).toInstant().toEpochMilli()
        val now = epoch(java.time.LocalDateTime.of(2024, 6, 3, 12, 0))
        every { timeBoundaryTicker.dayBoundaryTicks() } returns flowOf(now)
        fun entity(id: Long, amount: Double, date: java.time.LocalDateTime) =
            com.yourname.expensetracker.data.database.entity.Expense(
                id = id, amount = amount, currency = "EUR", merchant = "M$id",
                transactionType = com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE,
                date = epoch(date), categoryId = 1L, isNotMine = false,
                isSharedExpense = false, isManualEntry = false
            )
        every { expenseRepository.getExpensesWithCategoryInPeriod(any(), any()) } returns flowOf(
            listOf(
                entity(1, 60.0, java.time.LocalDateTime.of(2024, 6, 2, 9, 0)),   // current MTD
                entity(2, 200.0, java.time.LocalDateTime.of(2024, 5, 15, 9, 0)), // M-1
                entity(3, 100.0, java.time.LocalDateTime.of(2024, 4, 15, 9, 0))  // M-2
            ).map { com.yourname.expensetracker.data.database.model.ExpenseWithCategory(expense = it, category = null) }
        )
        val dashboardExpenses = adapter.observeDashboardExpenses().first()

        val inputSlot = slot<NormalizedForecastInput>()
        val assembler = mockk<ForecastInputAssembler>(relaxed = true)
        coEvery { assembler.assembleNormalized(capture(inputSlot)) } returns mockk(relaxed = true)

        windowTestComputeUseCase(now, assembler).compute(windowProcessedData(dashboardExpenses))

        val pace = inputSlot.captured.spendingPace
        assertEquals("baseline = completed-month mean, never MTD", 150.0, pace.averageMonthlyTotal!!, 0.0001)
        assertEquals(60.0, pace.currentMonthSpent, 0.0001)
    }

    /**
     * P5-012 (RP-05 batch 2): null-category spend must surface as the reserved
     * pseudo-category and percentages must stay sum-complete over ALL buckets.
     */
    @Test
    fun `uncategorized spend maps to the reserved pseudo-category with complete percentages`() = runTest {
        val zone = java.time.ZoneId.systemDefault()
        fun epoch(date: java.time.LocalDateTime) = date.atZone(zone).toInstant().toEpochMilli()
        val now = epoch(java.time.LocalDateTime.of(2024, 6, 15, 12, 0))
        every { timeBoundaryTicker.dayBoundaryTicks() } returns flowOf(now)
        fun entity(id: Long, amount: Double, date: java.time.LocalDateTime, categoryId: Long?) =
            com.yourname.expensetracker.data.database.entity.Expense(
                id = id, amount = amount, currency = "EUR", merchant = "M$id",
                transactionType = com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE,
                date = epoch(date), categoryId = categoryId, isNotMine = false,
                isSharedExpense = false, isManualEntry = false
            )
        every { expenseRepository.getExpensesWithCategoryInPeriod(any(), any()) } returns flowOf(
            listOf(
                entity(1, 80.0, java.time.LocalDateTime.of(2024, 6, 10, 9, 0), null),
                entity(2, 20.0, java.time.LocalDateTime.of(2024, 6, 11, 9, 0), 5L)
            ).map { com.yourname.expensetracker.data.database.model.ExpenseWithCategory(expense = it, category = null) }
        )
        val dashboardExpenses = adapter.observeDashboardExpenses().first()

        val compiled = windowTestComputeUseCase(now, mockk(relaxed = true)).compute(
            windowProcessedData(
                dashboardExpenses,
                categories = listOf(DashboardCategory(id = 5L, name = "Food", icon = "\uD83C\uDF55", color = "#00FF00"))
            )
        )

        val topCategories = compiled.allWidgets.filterIsInstance<DashboardWidget.TopCategories>().single()
        assertEquals(2, topCategories.categories.size)
        // Reserved pseudo-category convention (same as TotalsAggregationEngine).
        val pseudo = topCategories.categories.single { it.isUncategorized }
        assertEquals(0L, pseudo.category.id)
        assertEquals("Uncategorized", pseudo.category.name)
        assertEquals("?", pseudo.category.icon)
        assertEquals("#808080", pseudo.category.color)
        assertEquals(80.0, pseudo.total, 0.0001)
        // Denominator includes every bucket: percentages sum to 100%.
        assertTrue(
            "percentages must be sum-complete, got ${topCategories.categories.sumOf { it.percentage.toDouble() }}",
            kotlin.math.abs(topCategories.categories.sumOf { it.percentage.toDouble() } - 100.0) < 0.5
        )
        // P5-012: sorted AFTER the pseudo-category maps in — it competes fairly.
        assertEquals(pseudo, topCategories.categories.first())
    }

    /**
     * P5-008 (RP-06 6c) V1-fallback suppression pin — the lane's actual
     * production behavior change. When the typed health-score calculation is
     * Unavailable, the widget assembly must emit NO health widget at all:
     * neither the authoritative V2 widget nor the legacy V1
     * [DashboardWidget.FinancialHealthScoreWidget] fallback (the pre-P5-008
     * behavior fabricated/stale-rolled a V1 widget on null; that path is
     * deliberately suppressed).
     */
    @Test
    fun `computeHealthScoreV2 unavailable emits no health widget and no V1 fallback`() = runTest {
        val zone = java.time.ZoneId.systemDefault()
        fun epoch(date: java.time.LocalDateTime) = date.atZone(zone).toInstant().toEpochMilli()
        val now = epoch(java.time.LocalDateTime.of(2024, 6, 15, 12, 0))
        every { timeBoundaryTicker.dayBoundaryTicks() } returns flowOf(now)
        // One real purchase so the compute path matches the proven window-test
        // route (non-empty fixture) rather than an untested empty-input path.
        every { expenseRepository.getExpensesWithCategoryInPeriod(any(), any()) } returns flowOf(
            listOf(
                com.yourname.expensetracker.data.database.model.ExpenseWithCategory(
                    expense = com.yourname.expensetracker.data.database.entity.Expense(
                        id = 1L, amount = 80.0, currency = "EUR", merchant = "M1",
                        transactionType = com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE,
                        date = epoch(java.time.LocalDateTime.of(2024, 6, 10, 9, 0)),
                        categoryId = 1L, isNotMine = false,
                        isSharedExpense = false, isManualEntry = false
                    ),
                    category = null
                )
            )
        )
        val dashboardExpenses = adapter.observeDashboardExpenses().first()

        val compiled = windowTestComputeUseCase(
            now = now,
            forecastInputAssembler = mockk(relaxed = true),
            healthScoreOutcome = HealthScoreOutcome.Unavailable(
                HealthScoreUnavailableReason.NORMALIZATION_FAILED
            )
        ).compute(windowProcessedData(dashboardExpenses))

        // NEITHER health widget may appear when the score is unavailable.
        assertTrue(
            "V2 health widget must not be emitted on Unavailable",
            compiled.allWidgets.filterIsInstance<DashboardWidget.FinancialHealthScoreV2Widget>().isEmpty()
        )
        assertTrue(
            "Legacy V1 health widget fallback must not be emitted on Unavailable",
            compiled.allWidgets.filterIsInstance<DashboardWidget.FinancialHealthScoreWidget>().isEmpty()
        )
        // The suppression must not swallow the rest of the dashboard: the
        // always-on widgets are still present (sanity, keeps the pin honest).
        assertTrue(
            "Unrelated widgets must still be emitted",
            compiled.allWidgets.filterIsInstance<DashboardWidget.TotalsDashboard>().isNotEmpty()
        )
    }

    /**
     * Full-compute harness shared by the RP-05 batch-2 window/baseline/category
     * tests — mirrors the round-trip test's construction with a swappable
     * forecast input assembler.
     */
    private fun windowTestComputeUseCase(
        now: Long,
        forecastInputAssembler: ForecastInputAssembler,
        healthScoreOutcome: HealthScoreOutcome = HealthScoreOutcome.Available(
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
    ): ComputeDashboardWidgetsUseCase {
        val insightsEngine = mockk<com.yourname.expensetracker.domain.analytics.InsightsEngine>(relaxed = true)
        coEvery { insightsEngine.getSpendingPaceSuspend(any()) } returns SpendingPace(
            currentMonthSpent = 0.0,
            daysElapsed = 1,
            daysInMonth = 31,
            projectedTotal = 0.0,
            previousMonthTotal = null,
            averageMonthlyTotal = null,
            pacePercentage = 100f,
            paceStatus = PaceStatus.NO_BASELINE,
            displayCurrency = "EUR"
        )
        val healthScoreV2 = mockk<FinancialHealthScoreV2>(relaxed = true)
        coEvery { healthScoreV2.calculateHealthScore(any(), any()) } returns healthScoreOutcome
        val currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true)
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        val multiCurrencyRepository = mockk<MultiCurrencyRepository>(relaxed = true)
        coEvery { multiCurrencyRepository.getHomeCurrencyPurchaseTotal(any(), any()) } returns
            MoneyAggregate.empty(CurrencyCode("EUR"))
        return ComputeDashboardWidgetsUseCase(
            writeBarrier = mockk(relaxed = true),
            insightsEngine = insightsEngine,
            synthesisEngine = SynthesisEngine(timeProvider = object : TimeProvider {
                override fun now(): Long = now
            }, currencyConverter = mockk(relaxed = true)),
            monteCarloSimulator = mockk<MonteCarloSpendingSimulator>(relaxed = true),
            timeProvider = object : TimeProvider {
                override fun now(): Long = now
            },
            healthCalculator = mockk(relaxed = true),
            healthScoreV2 = healthScoreV2,
            lifestyleSavingsPromptUseCase = mockk<LifestyleSavingsPromptUseCase>(relaxed = true).apply {
                coEvery { evaluateAndPrompt() } returns null
            },
            monthlySavingsSweepUseCase = mockk<MonthlySavingsSweepUseCase>(relaxed = true).apply {
                coEvery { computeSweepRecommendation() } returns null
            },
            computeMoneyRadarUseCase = mockk(relaxed = true),
            stressForecastEngine = mockk<FinancialStressForecastEngine>(relaxed = true),
            forecastInputAssembler = forecastInputAssembler,
            currencyConverter = mockk<CurrencyConverter>(relaxed = true),
            currencySettingsRepository = currencySettingsRepository,
            multiCurrencyRepository = multiCurrencyRepository,
            // RP-06 6a: real calculator + the test's fixed TimeProvider so pace math is real.
            spendingPaceCalculator = com.yourname.expensetracker.domain.analytics.SpendingPaceCalculator(
                object : TimeProvider {
                    override fun now(): Long = now
                }
            )
        )
    }

    private fun windowProcessedData(
        expenses: List<com.yourname.expensetracker.domain.model.dashboard.DashboardExpense>,
        categories: List<DashboardCategory> = emptyList()
    ): com.yourname.expensetracker.domain.usecase.dashboard.ProcessedDashboardData {
        val data = com.yourname.expensetracker.domain.usecase.dashboard.DashboardData(
            expenses = expenses,
            categories = categories,
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
        return com.yourname.expensetracker.domain.usecase.dashboard.ProcessedDashboardData(
            data = data,
            summary = com.yourname.expensetracker.domain.model.dashboard.SpendingSummary(
                totalSpent = 0.0,
                previousTotalSpent = null,
                changePercent = null,
                dailyHistory = emptyList(),
                previousDailyHistory = emptyList(),
                transactionCount = 0
            ),
            categoryBreakdown = emptyList()
        )
    }

    private fun recurringPattern(merchant: String, amount: Double): RecurringPattern {
        return RecurringPattern(
            merchantName = merchant,
            averageAmount = amount,
            currency = "EUR",
            frequency = RecurrenceFrequency.MONTHLY,
            periodVarianceDays = 0,
            amountVariancePercent = 0.0,
            nextExpectedDate = 1_710_000_000_000L,
            confidence = 1.0f,
            previousDates = emptyList()
        )
    }
}
