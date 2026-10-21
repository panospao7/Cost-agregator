package com.yourname.expensetracker.ui.screens.home

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.PlannedExpense
import com.yourname.expensetracker.data.database.entity.PlannedExpensePriority
import com.yourname.expensetracker.data.database.model.DashboardWidgetConfig
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.DashboardRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.PlannedExpenseRepository
import com.yourname.expensetracker.domain.analytics.AdvancedAnalyticsEngine
import com.yourname.expensetracker.domain.analytics.TotalsAggregationEngine
import com.yourname.expensetracker.domain.ai.model.AiArtifactStatus
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiEngagementState
import com.yourname.expensetracker.domain.ai.model.AiLoadState
import com.yourname.expensetracker.domain.ai.model.AiMode
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.AiTargetType
import com.yourname.expensetracker.domain.ai.model.OnDeviceModelStatus
import com.yourname.expensetracker.domain.ai.service.AiArtifactRepository
import com.yourname.expensetracker.domain.ai.service.AiEngagementRepository
import com.yourname.expensetracker.domain.ai.service.AiEnvironmentMonitor
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.dto.AiArtifactRecord
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.model.dashboard.FinancialWeather
import com.yourname.expensetracker.domain.model.dashboard.SpendingSummary
import com.yourname.expensetracker.domain.model.dashboard.WeatherState
import com.yourname.expensetracker.domain.usecase.dashboard.CompiledDashboardData
import com.yourname.expensetracker.domain.usecase.dashboard.ComputeDashboardWidgetsUseCase
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardAnalyticsRepository
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardData
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardDataProvider
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardNormalizedInputResult
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardWidget
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardWidgetRegistry
import com.yourname.expensetracker.domain.usecase.dashboard.ProcessedDashboardData
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.widget.model.WidgetStyleConfig
import com.yourname.expensetracker.domain.widget.service.WidgetStyleRepository
import com.yourname.expensetracker.service.NavigationTargetResolver
import com.yourname.expensetracker.service.RecommendationDismissalHandler
import com.yourname.expensetracker.service.RecommendationStateManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * HomeViewModel dashboard-widget behaviour: edit mode, widget reorder/visibility persistence,
 * planned-expense hand-off, cloud AI briefing presentation and widget-id mapping.
 *
 * Harness notes (why this class no longer hangs):
 * - HomeViewModel.init runs a `while (true) { delay(nextMidnight - now) }` refresh loop. With a
 *   fixed TimeProvider every loop iteration is a fresh virtual-time delay, so `advanceUntilIdle()`
 *   on the Main scheduler never returns. Main is therefore an [UnconfinedTestDispatcher] whose
 *   scheduler nobody advances: viewModelScope work runs eagerly, the midnight delay stays parked,
 *   and the tests never call `advanceUntilIdle()`.
 * - `processedDataFlow` hops to Dispatchers.Default, so tests that need the rendered dashboard
 *   wait on a real StateFlow condition (`first { }`) inside `runBlocking`, not on virtual time.
 *   The `withTimeout` is only a failure watchdog; no expected value depends on the wall clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelWidgetsTest {

    private lateinit var dashboardDataProvider: DashboardDataProvider
    private lateinit var dashboardRepository: DashboardRepository
    private lateinit var categoryRepository: CategoryRepository
    private lateinit var plannedExpenseRepository: PlannedExpenseRepository
    private lateinit var analyticsRepository: DashboardAnalyticsRepository
    private lateinit var computeDashboardWidgetsUseCase: ComputeDashboardWidgetsUseCase
    private lateinit var aiSettingsRepository: AiSettingsRepository
    private lateinit var aiArtifactRepository: AiArtifactRepository
    private lateinit var aiEnvironmentMonitor: AiEnvironmentMonitor
    private lateinit var aiEngagementRepository: AiEngagementRepository
    private lateinit var widgetStyleRepository: WidgetStyleRepository
    private lateinit var timeProvider: TimeProvider
    private lateinit var totalsAggregationEngine: TotalsAggregationEngine
    private lateinit var currencySettingsRepository: CurrencySettingsRepository
    private lateinit var recommendationStateManager: RecommendationStateManager

    private val configFlow = MutableStateFlow(defaultConfig())
    private val savedConfigs = mutableListOf<List<DashboardWidgetConfig>>()
    private var renderedWidgets: List<DashboardWidget> = threeWidgets()
    private var viewModel: HomeViewModel? = null

    private val vm: HomeViewModel
        get() = checkNotNull(viewModel) { "createViewModel() must be called first" }

    @Before
    fun setup() {
        // Own scheduler, never advanced: parks the init midnight-refresh loop (see class KDoc).
        Dispatchers.setMain(UnconfinedTestDispatcher())

        dashboardDataProvider = mockk(relaxed = true)
        dashboardRepository = mockk(relaxed = true)
        categoryRepository = mockk(relaxed = true)
        plannedExpenseRepository = mockk(relaxed = true)
        analyticsRepository = mockk(relaxed = true)
        computeDashboardWidgetsUseCase = mockk(relaxed = true)
        aiSettingsRepository = mockk(relaxed = true)
        aiArtifactRepository = mockk(relaxed = true)
        aiEnvironmentMonitor = mockk(relaxed = true)
        aiEngagementRepository = mockk(relaxed = true)
        widgetStyleRepository = mockk(relaxed = true)
        timeProvider = mockk(relaxed = true)
        totalsAggregationEngine = mockk(relaxed = true)
        currencySettingsRepository = mockk(relaxed = true)
        recommendationStateManager = mockk(relaxed = true)

        every { aiSettingsRepository.settings() } returns flowOf(AiSettings())
        every { aiEngagementRepository.engagementState() } returns flowOf(AiEngagementState())
        every { timeProvider.now() } returns 0L
        coEvery { aiEnvironmentMonitor.getOnDeviceModelStatus(AiCapability.DASHBOARD_BRIEFING) } returns
            OnDeviceModelStatus.AVAILABLE
        every { currencySettingsRepository.homeCurrency() } returns flowOf("EUR")
        // dashboard = combine(..., widgetStyleRepository.config(), ...): a relaxed Flow mock would
        // complete without emitting and the dashboard would never leave its initial state.
        every { widgetStyleRepository.config() } returns flowOf(WidgetStyleConfig())
        every { totalsAggregationEngine.getMonthlyTotals(any()) } returns flowOf(emptyList())
        coEvery { totalsAggregationEngine.getAverageForPeriodType(any(), any()) } returns null

        every { dashboardDataProvider.getProcessedDataFlow(analyticsRepository) } returns
            flowOf(processedData())
        coEvery { computeDashboardWidgetsUseCase.compute(any()) } answers {
            CompiledDashboardData(
                allWidgets = renderedWidgets,
                totalSpent = 0.0,
                txCount = 0,
                normalizedInput = DashboardNormalizedInputResult.Unavailable(
                    reason = "test",
                    periodStart = 0L,
                    periodEnd = 0L
                )
            )
        }

        every { dashboardRepository.configFlow } returns configFlow
        every { dashboardRepository.getDashboardConfig() } answers { configFlow.value }
        coEvery { dashboardRepository.saveDashboardConfig(any()) } coAnswers {
            val saved = firstArg<List<DashboardWidgetConfig>>()
            savedConfigs.add(saved)
            configFlow.value = saved
        }
        every { aiArtifactRepository.observeLatest(any(), any()) } returns flowOf(null)
        every { categoryRepository.allCategories } returns flowOf(emptyList())
        coEvery { categoryRepository.ensureDefaultCategories() } returns Unit
        coEvery { plannedExpenseRepository.addPlannedExpense(any()) } returns 1L
    }

    @After
    fun tearDown() {
        viewModel?.viewModelScope?.cancel()
        Dispatchers.resetMain()
    }

    // ---- fixture helpers -------------------------------------------------------------------

    private fun createViewModel(): HomeViewModel {
        val created = HomeViewModel(
            mockk<Application>(relaxed = true),
            dashboardDataProvider,
            dashboardRepository,
            categoryRepository,
            plannedExpenseRepository,
            analyticsRepository,
            mockk<ExpenseRepository>(relaxed = true),
            computeDashboardWidgetsUseCase,
            aiSettingsRepository,
            aiArtifactRepository,
            aiEnvironmentMonitor,
            aiEngagementRepository,
            widgetStyleRepository,
            timeProvider,
            recommendationStateManager,
            mockk<NavigationTargetResolver>(relaxed = true),
            mockk<RecommendationDismissalHandler>(relaxed = true),
            totalsAggregationEngine,
            mockk<AdvancedAnalyticsEngine>(relaxed = true),
            currencySettingsRepository = currencySettingsRepository,
        )
        viewModel = created
        return created
    }

    /** Runs [block] with the dashboard subscribed (stateIn is WhileSubscribed), in real time. */
    private fun runWithDashboard(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        val subscription = launch(Dispatchers.Unconfined) { vm.dashboard.collect { } }
        try {
            block()
        } finally {
            subscription.cancelAndJoin()
        }
    }

    private suspend fun awaitDashboard(predicate: (DashboardState) -> Boolean): DashboardState =
        withTimeout(WATCHDOG_MILLIS) { vm.dashboard.first(predicate) }

    private fun renderedIds(state: DashboardState): List<String> =
        state.widgets.map { DashboardWidgetRegistry.idFor(it) }

    private fun defaultConfig(): List<DashboardWidgetConfig> = listOf(
        DashboardWidgetConfig("safe_to_spend", 0, true),
        DashboardWidgetConfig("review_alert", 1, true),
        DashboardWidgetConfig("period_summary", 2, true)
    )

    private fun threeWidgets(): List<DashboardWidget> = listOf(
        DashboardWidget.SafeToSpend(100.0, 500.0, 15),
        DashboardWidget.PendingReviewAlert(5),
        DashboardWidget.PeriodSummary(10.0, 50.0, 200.0)
    )

    private fun processedData() = ProcessedDashboardData(
        data = DashboardData(
            expenses = emptyList(),
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
        ),
        summary = SpendingSummary(
            totalSpent = 0.0,
            previousTotalSpent = null,
            changePercent = null,
            dailyHistory = emptyList(),
            previousDailyHistory = emptyList(),
            transactionCount = 0
        ),
        categoryBreakdown = emptyList()
    )

    // ---- edit mode (HomeViewModel.toggleEditMode :413, dashboard combine :335-404) ------------

    @Test
    fun `toggleEditMode flips isEditMode in the rendered dashboard and flips back`() {
        createViewModel()
        runWithDashboard {
            val ready = awaitDashboard { !it.isLoading }
            assertFalse(ready.isEditMode)

            vm.toggleEditMode()
            assertTrue(awaitDashboard { it.isEditMode }.isEditMode)

            vm.toggleEditMode()
            assertFalse(awaitDashboard { !it.isEditMode }.isEditMode)
        }
    }

    // ---- init resilience (HomeViewModel.init :190-199) ----------------------------------------

    @Test
    fun `default category seeding failure does not abort init and recommendations still refresh`() {
        coEvery { categoryRepository.ensureDefaultCategories() } throws RuntimeException("db down")

        createViewModel()

        runWithDashboard {
            val state = awaitDashboard { !it.isLoading }
            assertFalse(state.isLoading)
        }
        verify(exactly = 1) { recommendationStateManager.refreshForUser(any()) }
    }

    // ---- cloud briefing presentation (HomeViewModel :277-333, AiArtifactPresentation.kt) -------

    @Test
    fun `cloud dashboard artifact does not surface an on-device runtime warning`() {
        every { aiSettingsRepository.settings() } returns flowOf(
            AiSettings(
                aiEnabled = true,
                dashboardBriefingEnabled = true,
                allowCloudAi = true,
                allowOnDeviceAi = true,
                // Discriminating setup: without the `entity?.mode == CLOUD -> null` branch the
                // preferredMode == ON_DEVICE branch would probe the monitor and surface a warning.
                preferredMode = AiMode.ON_DEVICE
            )
        )
        coEvery { aiEnvironmentMonitor.getOnDeviceModelStatus(AiCapability.DASHBOARD_BRIEFING) } returns
            OnDeviceModelStatus.UNAVAILABLE
        every { aiArtifactRepository.observeLatest(any(), any()) } returns flowOf(
            AiArtifactRecord(
                targetType = AiTargetType.DASHBOARD,
                targetKey = "dashboard_home:1970-01-01",
                capability = AiCapability.DASHBOARD_BRIEFING,
                status = AiArtifactStatus.READY,
                mode = AiMode.CLOUD,
                provider = "google-ai-studio",
                modelName = "gemini-2.5-flash",
                promptVersion = "v1",
                summaryText = "Cloud generated briefing",
                sourceHash = "hash",
                createdAt = 0L,
                updatedAt = 0L,
                expiresAt = 1L
            )
        )
        createViewModel()

        runWithDashboard {
            val state = awaitDashboard { it.aiBriefing is AiLoadState.Ready }

            val briefing = (state.aiBriefing as AiLoadState.Ready).value
            assertEquals(UiText.DynamicString("Cloud generated briefing"), briefing.text)
            assertEquals(null, briefing.runtimeStatusMessage)
            assertEquals("Cloud - google-ai-studio - gemini-2.5-flash", briefing.diagnostics)
            // CLOUD short-circuits before any on-device probe, even though on-device AI is allowed.
            coVerify(exactly = 0) { aiEnvironmentMonitor.getOnDeviceModelStatus(any()) }
        }
    }

    // ---- moveWidget (HomeViewModel :436-461): reorders RENDERED widgets only -------------------

    @Test
    fun `moveWidget down swaps with the next rendered widget and the dashboard re-renders in that order`() {
        createViewModel()
        runWithDashboard {
            val before = awaitDashboard { !it.isLoading && it.widgets.size == 3 }
            assertEquals(listOf("safe_to_spend", "review_alert", "period_summary"), renderedIds(before))

            vm.moveWidget("safe_to_spend", moveUp = false)

            assertEquals(
                listOf(
                    listOf(
                        DashboardWidgetConfig("review_alert", 0, true),
                        DashboardWidgetConfig("safe_to_spend", 1, true),
                        DashboardWidgetConfig("period_summary", 2, true)
                    )
                ),
                savedConfigs
            )
            val after = awaitDashboard { renderedIds(it).firstOrNull() == "review_alert" }
            assertEquals(listOf("review_alert", "safe_to_spend", "period_summary"), renderedIds(after))
        }
    }

    @Test
    fun `moveWidget up swaps with the previous rendered widget`() {
        createViewModel()
        runWithDashboard {
            awaitDashboard { !it.isLoading && it.widgets.size == 3 }

            vm.moveWidget("period_summary", moveUp = true)

            assertEquals(
                listOf(
                    listOf(
                        DashboardWidgetConfig("safe_to_spend", 0, true),
                        DashboardWidgetConfig("period_summary", 1, true),
                        DashboardWidgetConfig("review_alert", 2, true)
                    )
                ),
                savedConfigs
            )
            val after = awaitDashboard { renderedIds(it).lastOrNull() == "review_alert" }
            assertEquals(listOf("safe_to_spend", "period_summary", "review_alert"), renderedIds(after))
        }
    }

    @Test
    fun `moveWidget up at the top boundary saves nothing`() {
        createViewModel()
        runWithDashboard {
            awaitDashboard { !it.isLoading && it.widgets.size == 3 }

            vm.moveWidget("safe_to_spend", moveUp = true)

            assertTrue(savedConfigs.isEmpty())
            coVerify(exactly = 0) { dashboardRepository.saveDashboardConfig(any()) }
            assertEquals(defaultConfig(), configFlow.value)
        }
    }

    @Test
    fun `moveWidget down at the bottom boundary saves nothing`() {
        createViewModel()
        runWithDashboard {
            awaitDashboard { !it.isLoading && it.widgets.size == 3 }

            vm.moveWidget("period_summary", moveUp = false)

            assertTrue(savedConfigs.isEmpty())
            coVerify(exactly = 0) { dashboardRepository.saveDashboardConfig(any()) }
            assertEquals(defaultConfig(), configFlow.value)
        }
    }

    @Test
    fun `moveWidget with an id that is not rendered saves nothing`() {
        createViewModel()
        runWithDashboard {
            awaitDashboard { !it.isLoading && it.widgets.size == 3 }

            vm.moveWidget("nonexistent_widget", moveUp = true)

            assertTrue(savedConfigs.isEmpty())
            coVerify(exactly = 0) { dashboardRepository.saveDashboardConfig(any()) }
            assertEquals(defaultConfig(), configFlow.value)
        }
    }

    @Test
    fun `moveWidget skips configured widgets that are not rendered`() {
        // spending_pace is configured and visible but the use case did not produce it (no data).
        configFlow.value = listOf(
            DashboardWidgetConfig("safe_to_spend", 0, true),
            DashboardWidgetConfig("spending_pace", 1, true),
            DashboardWidgetConfig("review_alert", 2, true)
        )
        renderedWidgets = listOf(
            DashboardWidget.SafeToSpend(100.0, 500.0, 15),
            DashboardWidget.PendingReviewAlert(5)
        )
        createViewModel()
        runWithDashboard {
            val before = awaitDashboard { !it.isLoading && it.widgets.size == 2 }
            assertEquals(listOf("safe_to_spend", "review_alert"), renderedIds(before))

            vm.moveWidget("safe_to_spend", moveUp = false)

            assertEquals(
                listOf(
                    listOf(
                        DashboardWidgetConfig("review_alert", 0, true),
                        DashboardWidgetConfig("spending_pace", 1, true),
                        DashboardWidgetConfig("safe_to_spend", 2, true)
                    )
                ),
                savedConfigs
            )
            val after = awaitDashboard { renderedIds(it).firstOrNull() == "review_alert" }
            assertEquals(listOf("review_alert", "safe_to_spend"), renderedIds(after))
        }
    }

    // ---- toggleWidgetVisibility (HomeViewModel :463-473) ---------------------------------------

    @Test
    fun `toggleWidgetVisibility flips only the targeted widget and saves once`() {
        createViewModel()

        vm.toggleWidgetVisibility("review_alert")

        assertEquals(
            listOf(
                listOf(
                    DashboardWidgetConfig("safe_to_spend", 0, true),
                    DashboardWidgetConfig("review_alert", 1, false),
                    DashboardWidgetConfig("period_summary", 2, true)
                )
            ),
            savedConfigs
        )
        coVerify(exactly = 1) { dashboardRepository.saveDashboardConfig(any()) }
    }

    @Test
    fun `toggleWidgetVisibility twice restores the original visibility`() {
        createViewModel()

        vm.toggleWidgetVisibility("review_alert")
        vm.toggleWidgetVisibility("review_alert")

        assertEquals(2, savedConfigs.size)
        assertEquals(defaultConfig(), savedConfigs.last())
        assertEquals(defaultConfig(), configFlow.value)
    }

    @Test
    fun `toggleWidgetVisibility for an unknown id re-saves the config unchanged`() {
        createViewModel()

        vm.toggleWidgetVisibility("nonexistent")

        assertEquals(listOf(defaultConfig()), savedConfigs)
        coVerify(exactly = 1) { dashboardRepository.saveDashboardConfig(any()) }
    }

    // ---- addPlannedExpense (HomeViewModel :550-568) --------------------------------------------

    @Test
    fun `addPlannedExpense hands the entered values to the repository exactly once`() {
        val captured = mutableListOf<PlannedExpense>()
        coEvery { plannedExpenseRepository.addPlannedExpense(capture(captured)) } returns 1L
        createViewModel()

        vm.addPlannedExpense(
            description = "Test expense",
            amount = 50.0,
            date = PLANNED_DATE,
            categoryId = 1L,
            priority = PlannedExpensePriority.LIKELY
        )

        coVerify(exactly = 1) { plannedExpenseRepository.addPlannedExpense(any()) }
        assertEquals(1, captured.size)
        with(captured.single()) {
            assertEquals("Test expense", description)
            assertEquals(50.0, amount, 0.0)
            assertEquals(PLANNED_DATE, date)
            assertEquals(1L, categoryId)
            assertEquals(PlannedExpensePriority.LIKELY, priority)
        }
    }

    @Test
    fun `addPlannedExpense performs no validation of its own and passes blank, zero and null through`() {
        val captured = mutableListOf<PlannedExpense>()
        coEvery { plannedExpenseRepository.addPlannedExpense(capture(captured)) } returns 1L
        createViewModel()

        vm.addPlannedExpense("", 0.0, PLANNED_DATE, null, PlannedExpensePriority.OPTIONAL)

        coVerify(exactly = 1) { plannedExpenseRepository.addPlannedExpense(any()) }
        with(captured.single()) {
            assertEquals("", description)
            assertEquals(0.0, amount, 0.0)
            assertEquals(null, categoryId)
            assertEquals(PlannedExpensePriority.OPTIONAL, priority)
        }
    }

    @Test
    fun `addPlannedExpense forwards every priority in call order`() {
        val captured = mutableListOf<PlannedExpense>()
        coEvery { plannedExpenseRepository.addPlannedExpense(capture(captured)) } returns 1L
        createViewModel()
        val priorities = listOf(
            PlannedExpensePriority.MUST,
            PlannedExpensePriority.LIKELY,
            PlannedExpensePriority.OPTIONAL
        )

        priorities.forEach { vm.addPlannedExpense("Item", 10.0, PLANNED_DATE, null, it) }

        coVerify(exactly = 3) { plannedExpenseRepository.addPlannedExpense(any()) }
        assertEquals(priorities, captured.map { it.priority })
    }

    // ---- widget id mapping (HomeViewModel.getWidgetId :892 -> DashboardWidgetRegistry.idFor) ---

    @Test
    @Suppress("DEPRECATION")
    fun `getWidgetId maps every dashboard widget type to its canonical id`() {
        val weather = FinancialWeather(
            WeatherState.UNKNOWN, UiText.DynamicString(""), UiText.DynamicString(""), "", 0, 0.0, 0.0, 0.0, 0.0
        )
        val expectedIds: List<Pair<DashboardWidget, String>> = listOf(
            DashboardWidget.SafeToSpend(100.0, 500.0, 15) to "safe_to_spend",
            mockk<DashboardWidget.SpendingPaceWidget>() to "spending_pace",
            DashboardWidget.PendingReviewAlert(5) to "review_alert",
            mockk<DashboardWidget.SpendingTrend>() to "spending_trend",
            DashboardWidget.NaturalLanguageInsight(UiText.DynamicString("text"), "icon") to "insight",
            DashboardWidget.PeriodSummary(10.0, 50.0, 200.0) to "period_summary",
            DashboardWidget.BudgetHealthWidget(emptyList(), null) to "budget_health",
            DashboardWidget.TopCategories(emptyList()) to "top_categories",
            DashboardWidget.RecentTransactions(emptyList()) to "recent_transactions",
            DashboardWidget.FinancialWeatherWidget(weather) to "financial_weather",
            mockk<DashboardWidget.BudgetBlockParty>() to "budget_block_party",
            mockk<DashboardWidget.FinancialRunway>() to "financial_runway",
            DashboardWidget.TotalsDashboard to "totals_dashboard",
            mockk<DashboardWidget.MonteCarloForecast>() to "monte_carlo_forecast",
            mockk<DashboardWidget.NoSpendStreak>() to "no_spend_streak",
            mockk<DashboardWidget.FinancialHealthScoreWidget>() to "financial_health_score",
            mockk<DashboardWidget.FinancialHealthScoreV2Widget>() to "financial_health_score_v2",
            mockk<DashboardWidget.LifestyleSavingsPrompt>() to "lifestyle_savings_prompt",
            mockk<DashboardWidget.MoneyRadar>() to "money_radar",
            mockk<DashboardWidget.FinancialStressForecast>() to "financial_stress_forecast",
            mockk<DashboardWidget.SavingsSweepPrompt>() to "savings_sweep_prompt",
            mockk<DashboardWidget.SavingsSweepUnavailable>() to "savings_sweep_prompt"
        )

        expectedIds.forEach { (widget, expectedId) ->
            assertEquals(expectedId, HomeViewModel.getWidgetId(widget))
            assertEquals(expectedId, DashboardWidgetRegistry.idFor(widget))
        }
    }

    private companion object {
        /** Failure watchdog only; never part of an expected value. */
        const val WATCHDOG_MILLIS = 10_000L
        const val PLANNED_DATE = 1_700_000_000_000L
    }
}
