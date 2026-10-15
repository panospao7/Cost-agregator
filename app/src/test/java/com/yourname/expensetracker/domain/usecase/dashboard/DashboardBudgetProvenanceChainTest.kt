package com.yourname.expensetracker.domain.usecase.dashboard

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.currency.ExchangeRateStoreAdapter
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.RestrictedExpenseDaoMutation
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.AnalyticsRepository
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.DashboardContractsAdapter
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.FinancialWeatherRepository
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.data.repository.PlannedExpenseRepository
import com.yourname.expensetracker.data.repository.RecurringExpenseRepository
import com.yourname.expensetracker.data.repository.ReviewQueueRepository
import com.yourname.expensetracker.domain.budget.BudgetAutopilotEngine
import com.yourname.expensetracker.domain.budget.BudgetCalculator
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.forecasting.ConfidenceLevel
import com.yourname.expensetracker.domain.forecasting.FinancialStressForecastEngine
import com.yourname.expensetracker.domain.forecasting.ForecastInputAssembler
import com.yourname.expensetracker.domain.forecasting.MonteCarloResult
import com.yourname.expensetracker.domain.forecasting.MonteCarloSpendingSimulator
import com.yourname.expensetracker.domain.forecasting.NormalizedForecastInput
import com.yourname.expensetracker.domain.forecasting.SimulationConfidence
import com.yourname.expensetracker.domain.forecasting.SimulationMetadata
import com.yourname.expensetracker.domain.forecasting.StressForecastResult
import com.yourname.expensetracker.domain.forecasting.StressHorizon
import com.yourname.expensetracker.domain.forecasting.StressRiskLevel
import com.yourname.expensetracker.domain.groups.SharedExpenseBudgetOffsetEngine
import com.yourname.expensetracker.domain.health.FinancialHealthResult
import com.yourname.expensetracker.domain.health.FinancialHealthScoreV2
import com.yourname.expensetracker.domain.health.HealthScoreOutcome
import com.yourname.expensetracker.domain.health.HealthTrend
import com.yourname.expensetracker.domain.logic.SynthesisEngine
import com.yourname.expensetracker.domain.model.GoalProtectionLevel
import com.yourname.expensetracker.domain.model.SavingsGoal
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.model.dashboard.DashboardExpense
import com.yourname.expensetracker.domain.model.dashboard.DashboardTransactionType
import com.yourname.expensetracker.domain.model.dashboard.FinancialWeather
import com.yourname.expensetracker.domain.model.dashboard.SpendingSummary
import com.yourname.expensetracker.domain.model.dashboard.WeatherState
import com.yourname.expensetracker.domain.analytics.SpendingPaceCalculator
import com.yourname.expensetracker.domain.usecase.savings.LifestyleSavingsPromptUseCase
import com.yourname.expensetracker.domain.usecase.savings.MonthlySavingsSweepUseCase
import com.yourname.expensetracker.domain.usecase.savings.SavingsSweepComputationResult
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.util.TimeBoundaryTicker
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.ui.screens.budget.BudgetViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * R0-6 / RG06A-1 fail-first chain test: budget created through the REAL UI path
 * (BudgetScreen dialog builds the entity, [BudgetViewModel.addBudget] persists it
 * through the real [BudgetRepository] on in-memory Room) must reach the dashboard
 * with confirmed currency provenance, so the runway, Safe-to-Spend total budget and
 * Monte Carlo budget are NOT gated as "unavailable".
 *
 * Chain under test (all real except peripheral ports):
 *   BudgetScreen entity -> BudgetViewModel.addBudget -> BudgetRepository.addBudget
 *   -> budgets table -> BudgetRepository.getBudgetStatuses (BudgetStatus.currencyAssumption)
 *   -> DashboardContractsAdapter.observeBudgetStatuses (BudgetStatusSnapshot)
 *   -> ComputeDashboardWidgetsUseCase.compute.
 *
 * Root cause pinned: [Budget.currencyAssumption] defaults to "LEGACY_DEFAULT" and nothing on
 * the add path stamps it; BudgetStatusSnapshot.hasCompleteAmountIn() only accepts
 * EXPLICIT / EXPLICIT_HOME_CURRENCY / USER_CONFIRMED / PARSED_FROM_SOURCE
 * (BudgetStatusSnapshot.kt:28), so ComputeDashboardWidgetsUseCase returns
 * RunwayResult.Unavailable (ComputeDashboardWidgetsUseCase.kt:654-660).
 *
 * Post-fix (R3) the ONLY edit this file needs is inside [createBudgetThroughUi].
 */
@OptIn(ExperimentalCoroutinesApi::class, RestrictedExpenseDaoMutation::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DashboardBudgetProvenanceChainTest {

    // 2026-06-15 12:00 local: day 15 of a 30-day month (June).
    private val nowMs: Long = LocalDateTime.of(2026, 6, 15, 12, 0, 0)
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val clock = FakeTimeProvider(nowMs)

    private lateinit var database: AppDatabase
    private lateinit var budgetRepository: BudgetRepository
    private lateinit var budgetViewModel: BudgetViewModel
    private lateinit var adapter: DashboardContractsAdapter
    private lateinit var monteCarloSimulator: MonteCarloSpendingSimulator
    private lateinit var computeUseCase: ComputeDashboardWidgetsUseCase
    private var assembledGoalIds: List<Long> = emptyList()
    private var assembledGoalAssumptions: List<String> = emptyList()
    private var seededExpenseId: Long = 0L

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        // Peripheral ports (controlled): write barrier open, home currency USD.
        val writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true)
        every { writeBarrier.checkWritesAllowed(any<String>()) } just runs
        val currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true)
        every { currencySettingsRepository.homeCurrency() } returns flowOf("USD")
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Resolved(CurrencyCode("USD"))

        // Real Room-backed converter and multi-currency repository (all data is USD, so no rate is needed).
        val currencyConverter = CurrencyConverter(
            ExchangeRateStoreAdapter(database.exchangeRateDao(), writeBarrier),
            clock
        )
        val multiCurrencyRepository = MultiCurrencyRepository(
            expenseDao = database.expenseDao(),
            currencyConverter = currencyConverter,
            timeProvider = clock,
            currencySettingsRepository = currencySettingsRepository,
            applicationScope = CoroutineScope(Dispatchers.Unconfined)
        )
        val offsetEngine = mockk<SharedExpenseBudgetOffsetEngine>(relaxed = true)
        // Shared-expense offsets are out of scope; a failing offset engine degrades to gross spend (null breakdown).
        coEvery { offsetEngine.calculateEffectiveBudgetSpend(any(), any(), any()) } throws
            IllegalStateException("offset engine not under test")

        // REAL budget repository on REAL Room.
        budgetRepository = BudgetRepository(
            budgetDao = database.budgetDao(),
            categoryDao = database.categoryDao(),
            expenseDao = database.expenseDao(),
            budgetCalculator = BudgetCalculator(clock),
            timeProvider = clock,
            offsetEngine = offsetEngine,
            timeBoundaryTicker = TimeBoundaryTicker(clock),
            currencyConverter = currencyConverter,
            currencySettingsRepository = currencySettingsRepository,
            multiCurrencyRepository = multiCurrencyRepository,
            writeBarrier = writeBarrier,
            database = database,
            budgetForecastDao = database.budgetForecastDao()
        )

        // REAL view model; fakes only for non-DB ports.
        val categoryRepository = mockk<CategoryRepository>(relaxed = true)
        every { categoryRepository.allCategories } returns flowOf(emptyList())
        budgetViewModel = BudgetViewModel(
            budgetRepository = budgetRepository,
            categoryRepository = categoryRepository,
            offsetEngine = offsetEngine,
            autopilotEngine = mockk<BudgetAutopilotEngine>(relaxed = true),
            timeProvider = clock,
            currencySettingsRepository = currencySettingsRepository,
            database = database
        )

        // REAL dashboard budget projection (only budgetRepository is read by observeBudgetStatuses).
        adapter = DashboardContractsAdapter(
            expenseRepository = mockk<ExpenseRepository>(relaxed = true),
            categoryRepository = categoryRepository,
            budgetRepository = budgetRepository,
            reviewQueueRepository = mockk<ReviewQueueRepository>(relaxed = true),
            financialWeatherRepository = mockk<FinancialWeatherRepository>(relaxed = true),
            savingsGoalRepository = mockk<com.yourname.expensetracker.domain.savings.SavingsGoalRepository>(relaxed = true),
            analyticsRepository = mockk<AnalyticsRepository>(relaxed = true),
            recurringExpenseRepository = mockk<RecurringExpenseRepository>(relaxed = true),
            plannedExpenseRepository = mockk<PlannedExpenseRepository>(relaxed = true),
            timeBoundaryTicker = TimeBoundaryTicker(clock)
        )

        // Use case wiring mirrors ComputeDashboardWidgetsUseCasePaceWiringTest (peripheral engines stubbed).
        monteCarloSimulator = mockk(relaxed = true)
        coEvery { monteCarloSimulator.simulate(any(), any(), any(), any(), any()) } returns monteCarloResult("USD")
        val healthScoreV2 = mockk<FinancialHealthScoreV2>(relaxed = true)
        coEvery { healthScoreV2.calculateHealthScore(any(), any()) } returns HealthScoreOutcome.Available(
            FinancialHealthResult(
                overallScore = 50, savingsRateScore = 50, runwayScore = 50,
                budgetAdherenceScore = 50, billReliabilityScore = 50,
                factorContributions = emptyList(), trend = HealthTrend.STABLE, recommendation = null
            )
        )
        val lifestyle = mockk<LifestyleSavingsPromptUseCase>(relaxed = true)
        coEvery { lifestyle.evaluateAndPrompt() } returns null
        val sweep = mockk<MonthlySavingsSweepUseCase>(relaxed = true)
        coEvery { sweep.computeSweepRecommendationResult() } returns SavingsSweepComputationResult.NoRecommendation()
        val radar = mockk<ComputeMoneyRadarUseCase>(relaxed = true)
        coEvery { radar.compute() } returns MoneyRadarData(
            urgencyScore = 0, urgencyLevel = UrgencyLevel.GREEN, dueBills = emptyList(),
            anomalyAlerts = emptyList(), budgetRisk = null, topReasons = emptyList(), primaryCta = null
        )
        val stress = mockk<FinancialStressForecastEngine>(relaxed = true)
        coEvery { stress.computeStressForecast() } returns StressForecastResult(
            horizons = listOf(StressHorizon(30, 0.0, 0.0, 0.0, StressRiskLevel.LOW, 0.0, 0.0, 0.0)),
            overallRiskLevel = StressRiskLevel.LOW, earliestCrunchDate = null, recommendations = emptyList()
        )
        val assembler = mockk<ForecastInputAssembler>(relaxed = true)
        coEvery { assembler.assembleNormalized(any()) } answers {
            val input = arg<NormalizedForecastInput>(0)
            assembledGoalIds = input.savingsGoals.map { it.id }
            assembledGoalAssumptions = input.savingsGoals.map { it.currencyAssumption }
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
            insightsEngine = mockk(relaxed = true),
            synthesisEngine = SynthesisEngine(clock, currencyConverter = currencyConverter),
            monteCarloSimulator = monteCarloSimulator,
            timeProvider = clock,
            multiCurrencyRepository = multiCurrencyRepository,
            healthCalculator = mockk(relaxed = true),
            healthScoreV2 = healthScoreV2,
            lifestyleSavingsPromptUseCase = lifestyle,
            monthlySavingsSweepUseCase = sweep,
            computeMoneyRadarUseCase = radar,
            stressForecastEngine = stress,
            forecastInputAssembler = assembler,
            currencyConverter = currencyConverter,
            currencySettingsRepository = currencySettingsRepository,
            spendingPaceCalculator = SpendingPaceCalculator(clock)
        )
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    // ---------------------------------------------------------------------------------------
    // Decisive RG06A-1 test: fails today (assumption=LEGACY_DEFAULT), passes after R3.
    // ---------------------------------------------------------------------------------------
    @Test
    fun ui_created_overall_budget_drives_runway_safe_to_spend_and_monte_carlo() = runTest {
        seedDayOnePurchase()
        createBudgetThroughUi(screenBuiltOverallBudget())
        val persistedAssumption = persistedOverallBudgetAssumption()

        val result = computeDashboard()

        // Oracle derivation (day 15 of 30, USD home, one USD 100 purchase on day 1, budget USD 1000):
        //  - monthSpent = 100 (normalized month aggregate, ComputeDashboardWidgetsUseCase.kt:544)
        //  - averageDailyBurn = monthSpent / dayOfMonth = 100 / 15 = 6.667   (:774)
        //  - totalBudgetAmount = overallBudget.budgetAmount = effectiveLimit = 1000, ROLLING + rollover=false so no
        //    carry-over; limit is already home currency so no conversion   (:586)
        //  - remainingBeforeCommitments = (1000 - 100).coerceAtLeast(0) = 900   (:780-781)
        //  - totalCommitted = 0 (no recurring/planned), totalRemaining = 900 - 0 = 900   (:790)
        //  - runwayDays = (900 / 6.667).roundToInt() = 135 >= 14 -> HEALTHY   (:820-823)
        //  - SafeToSpend.totalBudget = overallBudget.budgetAmount only when hasCompleteAmountIn("USD")   (:1302)
        //  - Monte Carlo: spentToDate = periodAggregate = 100, knownUpcoming = committed + likely = 0,
        //    budgetForMC = 1000 (>0), displayCurrency = "USD"; 5th arg defaults to 0.0   (:969-977)
        val runway = result.allWidgets.filterIsInstance<DashboardWidget.FinancialRunway>().single()
        // DECISIVE first assertion (provenance gate at :654-660 -> RunwayResult.Unavailable).
        assertFalse(
            "RG06A-1: runway unavailable for UI-created budget (assumption=$persistedAssumption)",
            runway.isUnavailable
        )
        assertEquals("runway status", DashboardWidget.RunwayStatus.HEALTHY, runway.status)
        assertEquals("runway days (900 / (100/15))", 135, runway.daysRemaining)
        assertEquals("remaining after spend", 900.0, runway.discretionaryRemaining, 0.001)
        assertEquals("daily burn 100/15", 100.0 / 15.0, runway.averageDailyDiscretionarySpend, 0.001)
        assertEquals("committed", 0.0, runway.committedExpenses, 0.0)
        assertEquals("runway total budget", 1000.0, runway.totalBudget, 0.001)

        val safeToSpend = result.allWidgets.filterIsInstance<DashboardWidget.SafeToSpend>().single()
        assertNotNull("RG06A-1: SafeToSpend.totalBudget null for UI-created budget (assumption=$persistedAssumption)", safeToSpend.totalBudget)
        assertEquals("safe-to-spend total budget", 1000.0, safeToSpend.totalBudget!!, 0.001)

        coVerify(exactly = 1) { monteCarloSimulator.simulate(100.0, 0.0, 1000.0, "USD", 0.0) }
        val monteCarlo = result.allWidgets.filterIsInstance<DashboardWidget.MonteCarloForecast>().single()
        assertEquals("monte carlo display currency", "USD", monteCarlo.result.displayCurrency)
    }

    // ---------------------------------------------------------------------------------------
    // Control A: a budget carrying confirmed provenance (DAO-level, bypasses the UI path) yields
    // the same oracle values today. Proves the expected numbers are independent of the fix.
    // ---------------------------------------------------------------------------------------
    @Test
    fun control_user_confirmed_budget_yields_available_runway_with_same_oracle() = runTest {
        seedDayOnePurchase()
        database.budgetDao().insertAndActivateOverall(
            screenBuiltOverallBudget().copy(currencyAssumption = "USER_CONFIRMED", createdAt = nowMs)
        )

        val result = computeDashboard()

        val runway = result.allWidgets.filterIsInstance<DashboardWidget.FinancialRunway>().single()
        assertFalse("CONTROL: runway unavailable for USER_CONFIRMED USD budget", runway.isUnavailable)
        assertEquals("runway status", DashboardWidget.RunwayStatus.HEALTHY, runway.status)
        assertEquals("runway days", 135, runway.daysRemaining)
        assertEquals("remaining", 900.0, runway.discretionaryRemaining, 0.001)
        assertEquals("daily burn", 100.0 / 15.0, runway.averageDailyDiscretionarySpend, 0.001)
        assertEquals("committed", 0.0, runway.committedExpenses, 0.0)
        val safeToSpend = result.allWidgets.filterIsInstance<DashboardWidget.SafeToSpend>().single()
        assertEquals("safe-to-spend total budget", 1000.0, safeToSpend.totalBudget!!, 0.001)
        coVerify(exactly = 1) { monteCarloSimulator.simulate(100.0, 0.0, 1000.0, "USD", 0.0) }
    }

    // ---------------------------------------------------------------------------------------
    // Control B (guard against "fixing" by deleting the gate): a genuinely unconfirmed legacy
    // budget (LEGACY_DEFAULT row, never stamped) must stay gated before and after R3.
    // ---------------------------------------------------------------------------------------
    @Test
    fun control_unstamped_legacy_default_budget_stays_gated() = runTest {
        seedDayOnePurchase()
        database.budgetDao().insertAndActivateOverall(
            screenBuiltOverallBudget().copy(currencyAssumption = "LEGACY_DEFAULT", createdAt = nowMs)
        )

        val result = computeDashboard()

        val runway = result.allWidgets.filterIsInstance<DashboardWidget.FinancialRunway>().single()
        assertTrue("CONTROL: LEGACY_DEFAULT budget must keep runway unavailable", runway.isUnavailable)
        val safeToSpend = result.allWidgets.filterIsInstance<DashboardWidget.SafeToSpend>().single()
        assertNull("CONTROL: LEGACY_DEFAULT budget must not expose totalBudget", safeToSpend.totalBudget)
        coVerify(exactly = 0) { monteCarloSimulator.simulate(any(), any(), any(), any(), any()) }
    }

    // ---------------------------------------------------------------------------------------
    // R9 T7: a legacy-provenance STRICT goal (excluded from goal reserves) must NOT make the
    // forecast partial for Monte Carlo gating (ComputeDashboardWidgetsUseCase :964 reads
    // forecast.isPartial/excludedCount, which goal exclusions never touch).
    // ---------------------------------------------------------------------------------------
    @Test
    fun legacy_strict_goal_keeps_monte_carlo_available() = runTest {
        seedDayOnePurchase()
        database.budgetDao().insertAndActivateOverall(
            screenBuiltOverallBudget().copy(currencyAssumption = "USER_CONFIRMED", createdAt = nowMs)
        )

        val result = computeDashboard(goals = listOf(legacyStrictGoal()))

        // Same oracle as the control above: the goal changes none of runway/budget/MC inputs.
        val runway = result.allWidgets.filterIsInstance<DashboardWidget.FinancialRunway>().single()
        assertFalse("R9: legacy STRICT goal must not make runway unavailable", runway.isUnavailable)
        assertEquals("remaining", 900.0, runway.discretionaryRemaining, 0.001)
        // The computed result does not expose goalReservesPartial (the forecast stays internal to
        // the use case; goal reserves feed only predictedDiscretionary, never runway/MC). What is
        // observable here: the legacy STRICT goal really reached the forecast engine input, so the
        // unchanged runway/MC values above are not vacuous. The exclusion count itself is pinned
        // at engine level (SynthesisEngine goal-provenance tests).
        assertEquals("R9: the legacy STRICT goal reached the forecast input", listOf(1L), assembledGoalIds)
        assertEquals("R9: goal carries unconfirmed provenance", listOf("LEGACY_DEFAULT"), assembledGoalAssumptions)
        assertEquals("committed unaffected by goal", 0.0, runway.committedExpenses, 0.0)
        val monteCarlo = result.allWidgets.filterIsInstance<DashboardWidget.MonteCarloForecast>().single()
        assertEquals("monte carlo display currency", "USD", monteCarlo.result.displayCurrency)
        coVerify(exactly = 1) { monteCarloSimulator.simulate(100.0, 0.0, 1000.0, "USD", 0.0) }
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    /** STRICT, unfinished goal whose currency provenance is the unconfirmed LEGACY_DEFAULT. */
    private fun legacyStrictGoal() = SavingsGoal(
        id = 1L,
        name = "Legacy strict goal",
        targetAmount = 1000.0,
        currentAmount = 0.0,
        targetDate = null,
        protectionLevel = GoalProtectionLevel.STRICT,
        currency = "USD",
        currencyAssumption = "LEGACY_DEFAULT",
        createdAt = 0L
    )

    /**
     * The overall budget exactly as BudgetScreen.AddEditBudgetDialog builds it for a new budget
     * (BudgetScreen.kt:949-958): categoryId = selectedCategory (null = overall), period default
     * MONTHLY (:868), periodMode default "ROLLING" (:869), rollover default false (:870),
     * startDate = referenceNowMillis, currency = resolved home currency. NO currencyAssumption is set.
     */
    private fun screenBuiltOverallBudget(): Budget = Budget(
        categoryId = null,
        amount = 1000.0,
        period = BudgetPeriod.MONTHLY,
        periodMode = "ROLLING",
        startDate = nowMs,
        rollover = false,
        currency = "USD"
    )

    /**
     * The single place that persists a budget through the real UI path.
     *
     * Persistence is awaited via the view model's one-shot BudgetSaved event (subscribed before the call).
     */
    private suspend fun createBudgetThroughUi(budget: Budget) {
        val saved = CompletableDeferred<Unit>()
        val collector = CoroutineScope(Dispatchers.Unconfined).launch {
            budgetViewModel.uiEvents.first { it is BudgetViewModel.BudgetUiEvent.BudgetSaved }
            saved.complete(Unit)
        }
        try {
            // R3: becomes addBudget(budget, "USD")
            budgetViewModel.addBudget(budget, "USD")
            withContext(Dispatchers.Default) { withTimeout(10_000L) { saved.await() } }
        } catch (_: TimeoutCancellationException) {
            fail("SETUP: addBudget did not emit BudgetSaved within 10s (persisted=${database.budgetDao().getActiveBudgets().size})")
        } finally {
            collector.cancel()
        }
    }

    private suspend fun persistedOverallBudgetAssumption(): String {
        val active = database.budgetDao().getActiveBudgets()
        assertEquals("SETUP: exactly one active budget persisted", 1, active.size)
        assertNull("SETUP: persisted budget is the overall budget", active.single().categoryId)
        return active.single().currencyAssumption
    }

    /** One USD 100 PURCHASE on day 1 (mine, not shared), inserted via the DAO insertAtomic path used by golden tests. */
    private suspend fun seedDayOnePurchase() {
        val dayOne = TimePeriodUtils.getStartOfMonth(nowMs) + 12L * 60 * 60 * 1000
        seededExpenseId = database.expenseDao().insertAtomic(
            Expense(
                amount = 100.0,
                currency = "USD",
                merchant = "Synthetic Mart",
                merchantKey = "synthetic_mart",
                categoryId = null,
                date = dayOne,
                transactionType = TransactionType.PURCHASE,
                isNotMine = false,
                createdAt = nowMs
            )
        )
        assertTrue("SETUP: expense persisted", seededExpenseId > 0L)
    }

    /** Dashboard input built from persisted rows; budget statuses come from the real repository + adapter projection. */
    private suspend fun computeDashboard(goals: List<SavingsGoal> = emptyList()): CompiledDashboardData {
        val persisted = requireNotNull(database.expenseDao().getById(seededExpenseId)) { "SETUP: expense row missing" }
        // Off the runTest scheduler: the day-boundary ticker inside getBudgetStatuses() would otherwise be advanced
        // by virtual time and keep restarting the real Room queries via flatMapLatest (livelock).
        val budgetStatuses = withContext(Dispatchers.Default) {
            withTimeout(10_000L) { adapter.observeBudgetStatuses().first() }
        }
        val data = DashboardData(
            expenses = listOf(
                DashboardExpense(
                    id = persisted.id,
                    amount = persisted.amount,
                    effectiveAmount = persisted.effectiveAmount,
                    merchant = persisted.merchant,
                    transactionType = DashboardTransactionType.PURCHASE,
                    date = persisted.date,
                    categoryId = persisted.categoryId,
                    isNotMine = persisted.isNotMine,
                    isManualEntry = persisted.isManualEntry,
                    isSharedExpense = persisted.isSharedExpense,
                    currency = persisted.currency
                )
            ),
            categories = emptyList(),
            budgetStatuses = budgetStatuses,
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
            goals = goals
        )
        val summary = SpendingSummary(
            totalSpent = 0.0,
            previousTotalSpent = null,
            changePercent = null,
            dailyHistory = emptyList(),
            previousDailyHistory = emptyList(),
            transactionCount = 1
        )
        return withContext(Dispatchers.Default) {
            computeUseCase.compute(ProcessedDashboardData(data = data, summary = summary, categoryBreakdown = emptyList()))
        }
    }

    private fun monteCarloResult(currency: String) = MonteCarloResult(
        percentile10 = 100.0, percentile25 = 125.0, percentile50 = 150.0,
        percentile75 = 175.0, percentile90 = 200.0,
        probabilityUnderBudget = null, budgetAmount = null,
        spentToDate = 100.0, knownUpcoming = 0.0,
        confidence = SimulationConfidence(0.8, ConfidenceLevel.HIGH, "TEST_COMPLETE"),
        metadata = SimulationMetadata(12, 12, 1000, 1.0, 0.5, 15, nowMs),
        displayCurrency = currency
    )
}
