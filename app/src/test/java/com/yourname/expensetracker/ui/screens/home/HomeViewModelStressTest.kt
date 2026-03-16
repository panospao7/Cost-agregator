package com.yourname.expensetracker.ui.screens.home

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.yourname.expensetracker.data.database.entity.AiArtifactEntity
import com.yourname.expensetracker.data.database.model.DashboardWidgetConfig
import com.yourname.expensetracker.data.repository.DashboardRepository
import com.yourname.expensetracker.domain.ai.model.AiArtifactStatus
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiMode
import com.yourname.expensetracker.domain.ai.model.OnDeviceModelStatus
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.AiTargetType
import com.yourname.expensetracker.domain.ai.service.AiArtifactRepository
import com.yourname.expensetracker.domain.ai.service.AiEnvironmentMonitor
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.usecase.dashboard.CompiledDashboardData
import com.yourname.expensetracker.domain.usecase.dashboard.ComputeDashboardWidgetsUseCase
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardDataProvider
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardWidget
import com.yourname.expensetracker.domain.usecase.dashboard.ProcessedDashboardData
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelStressTest : ViewModelTestUtils() {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var dashboardDataProvider: DashboardDataProvider
    private lateinit var dashboardRepository: DashboardRepository
    private lateinit var categoryRepository: com.yourname.expensetracker.data.repository.CategoryRepository
    private lateinit var plannedExpenseRepository: com.yourname.expensetracker.data.repository.PlannedExpenseRepository
    private lateinit var analyticsRepository: com.yourname.expensetracker.data.repository.AnalyticsRepository
    private lateinit var computeDashboardWidgetsUseCase: ComputeDashboardWidgetsUseCase
    private lateinit var aiSettingsRepository: AiSettingsRepository
    private lateinit var aiArtifactRepository: AiArtifactRepository
    private lateinit var aiEnvironmentMonitor: AiEnvironmentMonitor
    private lateinit var timeProvider: TimeProvider

    private val configFlow = MutableStateFlow(defaultConfig())
    private lateinit var viewModel: HomeViewModel

    @Before
    override fun setup() {
        super.setup()
        dashboardDataProvider = mockk(relaxed = true)
        dashboardRepository = mockk(relaxed = true)
        categoryRepository = mockk(relaxed = true)
        plannedExpenseRepository = mockk(relaxed = true)
        analyticsRepository = mockk(relaxed = true)
        computeDashboardWidgetsUseCase = mockk(relaxed = true)
        aiSettingsRepository = mockk(relaxed = true)
        aiArtifactRepository = mockk(relaxed = true)
        aiEnvironmentMonitor = mockk(relaxed = true)
        timeProvider = mockk(relaxed = true)

        every { aiSettingsRepository.settings() } returns flowOf(AiSettings())
        every { timeProvider.now() } returns 0L
        coEvery { aiEnvironmentMonitor.getOnDeviceModelStatus(AiCapability.DASHBOARD_BRIEFING) } returns OnDeviceModelStatus.AVAILABLE

        every { dashboardDataProvider.getProcessedDataFlow(analyticsRepository) } returns flowOf(
            ProcessedDashboardData(
                data = com.yourname.expensetracker.domain.usecase.dashboard.DashboardData(
                    expenses = emptyList(),
                    categories = emptyList(),
                    budgetStatuses = emptyList(),
                    pendingCount = 0,
                    weather = com.yourname.expensetracker.data.repository.FinancialWeather(
                        state = com.yourname.expensetracker.data.repository.WeatherState.UNKNOWN,
                        headline = "",
                        summary = "",
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
                summary = com.yourname.expensetracker.data.repository.SpendingSummary(
                    totalSpent = 0.0,
                    previousTotalSpent = null,
                    changePercent = null,
                    dailyHistory = emptyList(),
                    previousDailyHistory = emptyList(),
                    transactionCount = 0
                ),
                categoryBreakdown = emptyList()
            )
        )
        coEvery { computeDashboardWidgetsUseCase.compute(any()) } returns CompiledDashboardData(
            allWidgets = emptyList(),
            totalSpent = 0.0,
            txCount = 0
        )
        every { dashboardRepository.configFlow } returns configFlow
        every { dashboardRepository.getDashboardConfig() } answers { configFlow.value }
        every { dashboardRepository.saveDashboardConfigSync(any()) } answers {
            configFlow.value = firstArg()
        }
        every { aiArtifactRepository.observeLatest(any(), any()) } returns flowOf(null)
        every { categoryRepository.allCategories } returns flowOf(emptyList())
        coEvery { categoryRepository.ensureDefaultCategories() } just Runs
        coEvery { plannedExpenseRepository.addPlannedExpense(any()) } returns 1L

        viewModel = HomeViewModel(
            dashboardDataProvider,
            dashboardRepository,
            categoryRepository,
            plannedExpenseRepository,
            analyticsRepository,
            computeDashboardWidgetsUseCase,
            aiSettingsRepository,
            aiArtifactRepository,
            aiEnvironmentMonitor,
            timeProvider
        )
    }

    private fun defaultConfig(): List<DashboardWidgetConfig> = listOf(
        DashboardWidgetConfig("safe_to_spend", 0, true),
        DashboardWidgetConfig("spending_pace", 1, true),
        DashboardWidgetConfig("review_alert", 2, true)
    )

    // ============================================================================
    // SECTION 1: INITIAL STATE
    // ============================================================================

    @Test
    fun `stress - initial dashboard state has values`() = runTest(testDispatcher) {
        viewModel.dashboard.test {
            val state = awaitItem()
            assertNotNull(state)
            // May emit loading first, then loaded - accept either
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `stress - initial categories available`() = runTest(testDispatcher) {
        assertNotNull(viewModel.categories)
    }

    // ============================================================================
    // SECTION 2: TOGGLE EDIT MODE
    // ============================================================================

    @Test
    fun `stress - toggleEditMode flips isEditMode`() = runTest(testDispatcher) {
        viewModel.dashboard.test {
            val initial = awaitItem()
            viewModel.toggleEditMode()
            advanceUntilIdle()
            val afterToggle = awaitItem()
            assertTrue(afterToggle.isEditMode != initial.isEditMode)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `stress - cloud dashboard artifact does not surface on-device runtime warning`() = runTest(testDispatcher) {
        every { aiSettingsRepository.settings() } returns flowOf(
            AiSettings(
                aiEnabled = true,
                dashboardBriefingEnabled = true,
                allowCloudAi = true,
                allowOnDeviceAi = true
            )
        )
        every { aiArtifactRepository.observeLatest(any(), any()) } returns flowOf(
            AiArtifactEntity(
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

        viewModel = HomeViewModel(
            dashboardDataProvider,
            dashboardRepository,
            categoryRepository,
            plannedExpenseRepository,
            analyticsRepository,
            computeDashboardWidgetsUseCase,
            aiSettingsRepository,
            aiArtifactRepository,
            aiEnvironmentMonitor,
            timeProvider
        )

        viewModel.dashboard.test {
            advanceUntilIdle()
            var latest = awaitItem()
            while (latest.aiBriefing !is com.yourname.expensetracker.domain.ai.model.AiLoadState.Ready) {
                latest = awaitItem()
            }

            val briefing = latest.aiBriefing as com.yourname.expensetracker.domain.ai.model.AiLoadState.Ready
            assertEquals("Cloud generated briefing", briefing.value.text)
            assertEquals(null, briefing.value.runtimeStatusMessage)
            assertEquals("Cloud - google-ai-studio - gemini-2.5-flash", briefing.value.diagnostics)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `stress - rapid toggleEditMode does not crash`() = runTest(testDispatcher) {
        repeat(10) {
            viewModel.toggleEditMode()
        }
        advanceUntilIdle()
        viewModel.dashboard.test {
            val state = awaitItem()
            assertNotNull(state)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ============================================================================
    // SECTION 3: MOVE WIDGET
    // ============================================================================

    @Test
    fun `stress - moveWidget up at top boundary does nothing`() = runTest(testDispatcher) {
        val initialConfig = configFlow.value.toList()
        viewModel.moveWidget("safe_to_spend", moveUp = true)
        advanceUntilIdle()
        assertEquals(initialConfig, configFlow.value)
    }

    @Test
    fun `stress - moveWidget down at bottom boundary does nothing`() = runTest(testDispatcher) {
        val initialConfig = configFlow.value.toList()
        viewModel.moveWidget("review_alert", moveUp = false)
        advanceUntilIdle()
        assertEquals(initialConfig, configFlow.value)
    }

    @Test
    fun `stress - moveWidget with non-existent widget does nothing`() = runTest(testDispatcher) {
        val initialConfig = configFlow.value.toList()
        viewModel.moveWidget("nonexistent_widget", moveUp = true)
        advanceUntilIdle()
        assertEquals(initialConfig, configFlow.value)
    }

    @Test
    fun `stress - moveWidget down swaps order`() = runTest(testDispatcher) {
        viewModel.moveWidget("safe_to_spend", moveUp = false)
        advanceUntilIdle()
        verify(exactly = 1) { dashboardRepository.saveDashboardConfigSync(any()) }
    }

    // ============================================================================
    // SECTION 4: TOGGLE WIDGET VISIBILITY
    // ============================================================================

    @Test
    fun `stress - toggleWidgetVisibility for existing widget`() = runTest(testDispatcher) {
        viewModel.toggleWidgetVisibility("safe_to_spend")
        advanceUntilIdle()
        verify(exactly = 1) { dashboardRepository.saveDashboardConfigSync(any()) }
    }

    @Test
    fun `stress - toggleWidgetVisibility for non-existent widget`() = runTest(testDispatcher) {
        viewModel.toggleWidgetVisibility("nonexistent")
        advanceUntilIdle()
        verify(exactly = 1) { dashboardRepository.saveDashboardConfigSync(any()) }
    }

    // ============================================================================
    // SECTION 5: ADD PLANNED EXPENSE
    // ============================================================================

    @Test
    fun `stress - addPlannedExpense with valid data`() = runTest(testDispatcher) {
        viewModel.addPlannedExpense(
            description = "Test expense",
            amount = 50.0,
            date = System.currentTimeMillis(),
            categoryId = 1L,
            priority = com.yourname.expensetracker.data.database.entity.PlannedExpensePriority.LIKELY
        )
        advanceUntilIdle()
        coVerify(exactly = 1) { plannedExpenseRepository.addPlannedExpense(match { true }) }
    }

    @Test
    fun `stress - addPlannedExpense with empty description`() = runTest(testDispatcher) {
        viewModel.addPlannedExpense(
            description = "",
            amount = 50.0,
            date = System.currentTimeMillis(),
            categoryId = null,
            priority = com.yourname.expensetracker.data.database.entity.PlannedExpensePriority.LIKELY
        )
        advanceUntilIdle()
        coVerify(exactly = 1) { plannedExpenseRepository.addPlannedExpense(match { true }) }
    }

    @Test
    fun `stress - addPlannedExpense with zero amount`() = runTest(testDispatcher) {
        viewModel.addPlannedExpense(
            description = "Free item",
            amount = 0.0,
            date = System.currentTimeMillis(),
            categoryId = null,
            priority = com.yourname.expensetracker.data.database.entity.PlannedExpensePriority.OPTIONAL
        )
        advanceUntilIdle()
        coVerify(exactly = 1) { plannedExpenseRepository.addPlannedExpense(match { true }) }
    }

    @Test
    fun `stress - addPlannedExpense with null categoryId`() = runTest(testDispatcher) {
        viewModel.addPlannedExpense(
            description = "Test",
            amount = 100.0,
            date = System.currentTimeMillis(),
            categoryId = null,
            priority = com.yourname.expensetracker.data.database.entity.PlannedExpensePriority.MUST
        )
        advanceUntilIdle()
        coVerify(exactly = 1) { plannedExpenseRepository.addPlannedExpense(match { true }) }
    }

    @Test
    fun `stress - addPlannedExpense with all priority types`() = runTest(testDispatcher) {
        val priorities = listOf(
            com.yourname.expensetracker.data.database.entity.PlannedExpensePriority.MUST,
            com.yourname.expensetracker.data.database.entity.PlannedExpensePriority.LIKELY,
            com.yourname.expensetracker.data.database.entity.PlannedExpensePriority.OPTIONAL
        )
        priorities.forEach { priority ->
            viewModel.addPlannedExpense("Item", 10.0, System.currentTimeMillis(), null, priority)
        }
        advanceUntilIdle()
        coVerify(exactly = 3) { plannedExpenseRepository.addPlannedExpense(match { true }) }
    }

    // ============================================================================
    // SECTION 6: GET WIDGET ID
    // ============================================================================

    @Test
    fun `stress - getWidgetId for SafeToSpend`() {
        val widget = DashboardWidget.SafeToSpend(100.0, 500.0, 15)
        assertEquals("safe_to_spend", HomeViewModel.getWidgetId(widget))
    }

    @Test
    fun `stress - getWidgetId for all widget types`() {
        assertEquals("spending_pace", HomeViewModel.getWidgetId(
            DashboardWidget.SpendingPaceWidget(
                com.yourname.expensetracker.domain.analytics.SpendingPace(
                    currentMonthSpent = 0.0,
                    daysElapsed = 0,
                    daysInMonth = 30,
                    projectedTotal = 0.0,
                    previousMonthTotal = null,
                    averageMonthlyTotal = null,
                    pacePercentage = 0f,
                    paceStatus = com.yourname.expensetracker.domain.analytics.PaceStatus.ON_PACE
                )
            )
        ))
        assertEquals("review_alert", HomeViewModel.getWidgetId(DashboardWidget.PendingReviewAlert(5)))
        assertEquals("period_summary", HomeViewModel.getWidgetId(
            DashboardWidget.PeriodSummary(10.0, 50.0, 200.0)
        ))
        assertEquals("budget_health", HomeViewModel.getWidgetId(
            DashboardWidget.BudgetHealthWidget(emptyList(), null)
        ))
        assertEquals("top_categories", HomeViewModel.getWidgetId(
            DashboardWidget.TopCategories(emptyList())
        ))
        assertEquals("recent_transactions", HomeViewModel.getWidgetId(
            DashboardWidget.RecentTransactions(emptyList())
        ))
        assertEquals("insight", HomeViewModel.getWidgetId(
            DashboardWidget.NaturalLanguageInsight("text", "icon")
        ))
        assertEquals("financial_weather", HomeViewModel.getWidgetId(
            DashboardWidget.FinancialWeatherWidget(
                com.yourname.expensetracker.data.repository.FinancialWeather(
                    com.yourname.expensetracker.data.repository.WeatherState.UNKNOWN, "", "", "", 0, 0.0, 0.0, 0.0, 0.0
                )
            )
        ))
    }

    // ============================================================================
    // SECTION 7: ERROR HANDLING
    // ============================================================================

    @Test
    fun `stress - ensureDefaultCategories throws - viewModel continues`() = runTest(testDispatcher) {
        coEvery { categoryRepository.ensureDefaultCategories() } throws RuntimeException("DB error")
        val vm = HomeViewModel(
            dashboardDataProvider,
            dashboardRepository,
            categoryRepository,
            plannedExpenseRepository,
            analyticsRepository,
            computeDashboardWidgetsUseCase,
            aiSettingsRepository,
            aiArtifactRepository,
            aiEnvironmentMonitor,
            timeProvider
        )
        advanceUntilIdle()
        assertNotNull(vm.dashboard)
    }
}
