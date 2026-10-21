package com.yourname.expensetracker.ui.screens.home

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.model.DashboardWidgetConfig
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.DashboardRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.PlannedExpenseRepository
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiEngagementState
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.OnDeviceModelStatus
import com.yourname.expensetracker.domain.ai.service.AiArtifactRepository
import com.yourname.expensetracker.domain.ai.service.AiEngagementRepository
import com.yourname.expensetracker.domain.ai.service.AiEnvironmentMonitor
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.analytics.AdvancedAnalyticsEngine
import com.yourname.expensetracker.domain.analytics.TotalsAggregationEngine
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
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
import com.yourname.expensetracker.domain.usecase.dashboard.ProcessedDashboardData
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.widget.model.WidgetStyleConfig
import com.yourname.expensetracker.domain.widget.service.WidgetStyleRepository
import com.yourname.expensetracker.service.NavigationTargetResolver
import com.yourname.expensetracker.service.RecommendationDismissalHandler
import com.yourname.expensetracker.service.RecommendationStateManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * P5-006 (RP-06 batch 6a): a home-currency change must recompute the processed
 * dashboard exactly once, and the first (initial) emission must not count as a
 * change. The recompute rides the same collector that already observes home
 * currency for category trends, so ordering against the MultiCurrencyRepository
 * cache invalidation cannot diverge, and the trigger flow cannot loop (it never
 * observes dashboardReloadTrigger).
 *
 * Harness: same as [HomeViewModelWidgetsTest] (see its class KDoc). Main is an
 * [UnconfinedTestDispatcher] on a scheduler nobody advances, so the init midnight
 * refresh loop stays parked; `processedDataFlow` hops to Dispatchers.Default, so the
 * test waits on a real signal (a channel fed by the compute stub) inside
 * `runBlocking`. The `withTimeout` values are failure watchdogs only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelCurrencyReloadTest {

    private val currencyFlow = MutableStateFlow("EUR")

    /** compute() runs on Dispatchers.Default, so the counter and signal are thread-safe. */
    private val computeCalls = AtomicInteger(0)
    private val computeSignal = Channel<Int>(Channel.UNLIMITED)

    private var viewModel: HomeViewModel? = null

    @Before
    fun setup() {
        // Own scheduler, never advanced: parks the init midnight-refresh loop.
        Dispatchers.setMain(UnconfinedTestDispatcher())

        val dashboardDataProvider = mockk<DashboardDataProvider>(relaxed = true)
        val dashboardRepository = mockk<DashboardRepository>(relaxed = true)
        val categoryRepository = mockk<CategoryRepository>(relaxed = true)
        val plannedExpenseRepository = mockk<PlannedExpenseRepository>(relaxed = true)
        val analyticsRepository = mockk<DashboardAnalyticsRepository>(relaxed = true)
        val computeDashboardWidgetsUseCase = mockk<ComputeDashboardWidgetsUseCase>(relaxed = true)
        val aiSettingsRepository = mockk<AiSettingsRepository>(relaxed = true)
        val aiArtifactRepository = mockk<AiArtifactRepository>(relaxed = true)
        val aiEnvironmentMonitor = mockk<AiEnvironmentMonitor>(relaxed = true)
        val aiEngagementRepository = mockk<AiEngagementRepository>(relaxed = true)
        val widgetStyleRepository = mockk<WidgetStyleRepository>(relaxed = true)
        val timeProvider = mockk<TimeProvider>(relaxed = true)
        val totalsAggregationEngine = mockk<TotalsAggregationEngine>(relaxed = true)
        val currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true)

        every { aiSettingsRepository.settings() } returns flowOf(AiSettings())
        every { aiEngagementRepository.engagementState() } returns flowOf(AiEngagementState())
        every { timeProvider.now() } returns 0L
        coEvery { aiEnvironmentMonitor.getOnDeviceModelStatus(AiCapability.DASHBOARD_BRIEFING) } returns
            OnDeviceModelStatus.AVAILABLE
        every { currencySettingsRepository.homeCurrency() } returns currencyFlow
        // A relaxed Flow mock would complete without emitting and the dashboard combine
        // would never leave its initial state.
        every { widgetStyleRepository.config() } returns flowOf(WidgetStyleConfig())
        every { totalsAggregationEngine.getMonthlyTotals(any()) } returns flowOf(emptyList())
        coEvery { totalsAggregationEngine.getAverageForPeriodType(any(), any()) } returns null

        every { dashboardDataProvider.getProcessedDataFlow(analyticsRepository) } returns flowOf(
            ProcessedDashboardData(
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
        )
        coEvery { computeDashboardWidgetsUseCase.compute(any()) } answers {
            val count = computeCalls.incrementAndGet()
            computeSignal.trySend(count)
            CompiledDashboardData(
                allWidgets = emptyList(),
                totalSpent = 0.0,
                txCount = 0,
                normalizedInput = DashboardNormalizedInputResult.Unavailable(
                    reason = "test",
                    periodStart = 0L,
                    periodEnd = 0L
                )
            )
        }
        val configFlow = MutableStateFlow(defaultConfig())
        every { dashboardRepository.configFlow } returns configFlow
        every { dashboardRepository.getDashboardConfig() } answers { configFlow.value }
        every { dashboardRepository.saveDashboardConfigSync(any()) } answers {
            configFlow.value = firstArg()
        }
        every { aiArtifactRepository.observeLatest(any(), any()) } returns flowOf(null)
        every { categoryRepository.allCategories } returns flowOf(emptyList())
        coEvery { categoryRepository.ensureDefaultCategories() } returns Unit

        viewModel = HomeViewModel(
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
            mockk<RecommendationStateManager>(relaxed = true),
            mockk<NavigationTargetResolver>(relaxed = true),
            mockk<RecommendationDismissalHandler>(relaxed = true),
            totalsAggregationEngine,
            mockk<AdvancedAnalyticsEngine>(relaxed = true),
            currencySettingsRepository = currencySettingsRepository
        )
    }

    @After
    fun tearDown() {
        viewModel?.viewModelScope?.cancel()
        computeSignal.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `home currency change recomputes dashboard exactly once per change`() = runBlocking {
        val vm = checkNotNull(viewModel)
        // dashboard is WhileSubscribed: subscribe so processedDataFlow starts computing.
        val subscription = launch(Dispatchers.Unconfined) { vm.dashboard.collect { } }
        try {
            withTimeout(WATCHDOG_MILLIS) { computeSignal.receive() }
            assertEquals("initial value must not count as a change", 1, computeCalls.get())
            // The init collector has seen the initial EUR before any change is made.
            withTimeout(WATCHDOG_MILLIS) { vm.homeCurrency.first { it == "EUR" } }

            currencyFlow.value = "USD"
            withTimeout(WATCHDOG_MILLIS) { computeSignal.receive() }
            assertEquals("one change must produce exactly one reload", 2, computeCalls.get())

            currencyFlow.value = "GBP"
            withTimeout(WATCHDOG_MILLIS) { computeSignal.receive() }
            assertEquals(3, computeCalls.get())

            // Negative evidence: no further (duplicate/looping) recompute follows the last change.
            assertNull(
                "no extra reload after the last change",
                withTimeoutOrNull(QUIET_WINDOW_MILLIS) { computeSignal.receive() }
            )
            assertEquals(3, computeCalls.get())
        } finally {
            subscription.cancelAndJoin()
        }
    }

    private fun defaultConfig(): List<DashboardWidgetConfig> = listOf(
        DashboardWidgetConfig("safe_to_spend", 0, true),
        DashboardWidgetConfig("spending_pace", 1, true),
        DashboardWidgetConfig("review_alert", 2, true)
    )

    private companion object {
        /** Failure watchdog only; no expected value depends on the wall clock. */
        const val WATCHDOG_MILLIS = 10_000L

        /** Bounded real-time window used solely to look for an unexpected extra recompute. */
        const val QUIET_WINDOW_MILLIS = 300L
    }
}
