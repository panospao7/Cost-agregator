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
import com.yourname.expensetracker.domain.model.PeriodStatus
import com.yourname.expensetracker.domain.model.PeriodTotal
import com.yourname.expensetracker.domain.model.PeriodType
import com.yourname.expensetracker.domain.usecase.dashboard.ComputeDashboardWidgetsUseCase
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardAnalyticsRepository
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardDataProvider
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.widget.model.WidgetStyleConfig
import com.yourname.expensetracker.domain.widget.service.WidgetStyleRepository
import com.yourname.expensetracker.service.NavigationTargetResolver
import com.yourname.expensetracker.service.RecommendationDismissalHandler
import com.yourname.expensetracker.service.RecommendationStateManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * fix-1.0.4: the Home year view follows live expense changes (a collected flow, not `.first()`), a newer
 * year load or a drill-down must stop the previous year flow from writing, and a stale year flow must not
 * overwrite the state. Harness: same as [HomeViewModelCurrencyReloadTest] (Main is an unconfined test
 * dispatcher on a scheduler nobody advances, so emissions are delivered synchronously).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTotalsLiveTest {

    // timeProvider.now() == 0 -> the init load targets 1970.
    private val initialYearFlow = MutableStateFlow(listOf(month(1970, 11, 0.0)))
    private val year2025Flow = MutableStateFlow(listOf(month(2025, 11, 0.0)))
    private var viewModel: HomeViewModel? = null

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())

        val dashboardRepository = mockk<DashboardRepository>(relaxed = true)
        val categoryRepository = mockk<CategoryRepository>(relaxed = true)
        val aiSettingsRepository = mockk<AiSettingsRepository>(relaxed = true)
        val aiArtifactRepository = mockk<AiArtifactRepository>(relaxed = true)
        val aiEnvironmentMonitor = mockk<AiEnvironmentMonitor>(relaxed = true)
        val aiEngagementRepository = mockk<AiEngagementRepository>(relaxed = true)
        val widgetStyleRepository = mockk<WidgetStyleRepository>(relaxed = true)
        val timeProvider = mockk<TimeProvider>(relaxed = true)
        val totalsAggregationEngine = mockk<TotalsAggregationEngine>(relaxed = true)

        every { aiSettingsRepository.settings() } returns flowOf(AiSettings())
        every { aiEngagementRepository.engagementState() } returns flowOf(AiEngagementState())
        every { timeProvider.now() } returns 0L
        coEvery { aiEnvironmentMonitor.getOnDeviceModelStatus(AiCapability.DASHBOARD_BRIEFING) } returns
            OnDeviceModelStatus.AVAILABLE
        every { widgetStyleRepository.config() } returns flowOf(WidgetStyleConfig())
        every { totalsAggregationEngine.getMonthlyTotals(1970) } returns initialYearFlow
        every { totalsAggregationEngine.getMonthlyTotals(2025) } returns year2025Flow
        every { totalsAggregationEngine.getWeeklyTotals(any(), any()) } returns
            flowOf(listOf(week("2025-W45", 7.0)))
        coEvery { totalsAggregationEngine.getAverageForPeriodType(any(), any()) } returns null
        every { totalsAggregationEngine.getPeriodStatus(any(), any()) } returns PeriodStatus.NO_DATA

        val configFlow = MutableStateFlow(
            listOf(DashboardWidgetConfig("safe_to_spend", 0, true))
        )
        every { dashboardRepository.configFlow } returns configFlow
        every { dashboardRepository.getDashboardConfig() } answers { configFlow.value }
        every { aiArtifactRepository.observeLatest(any(), any()) } returns flowOf(null)
        every { categoryRepository.allCategories } returns flowOf(emptyList())
        coEvery { categoryRepository.ensureDefaultCategories() } returns Unit

        viewModel = HomeViewModel(
            mockk<Application>(relaxed = true),
            mockk<DashboardDataProvider>(relaxed = true),
            dashboardRepository,
            categoryRepository,
            mockk<PlannedExpenseRepository>(relaxed = true),
            mockk<DashboardAnalyticsRepository>(relaxed = true),
            mockk<ExpenseRepository>(relaxed = true),
            mockk<ComputeDashboardWidgetsUseCase>(relaxed = true),
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
            currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true).also {
                every { it.homeCurrency() } returns flowOf("EUR")
            },
            homeInstallmentWeatherSource = mockk(relaxed = true)
        )
    }

    @After
    fun tearDown() {
        viewModel?.viewModelScope?.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `year totals follow a live emission from all zeros to November 180`() {
        val vm = checkNotNull(viewModel)
        assertEquals(listOf(0.0), vm.totalsDrillDownState.value.periodTotals.map { it.totalAmount })

        initialYearFlow.value = listOf(month(1970, 11, 180.0))

        assertEquals(listOf(180.0), vm.totalsDrillDownState.value.periodTotals.map { it.totalAmount })
    }

    @Test
    fun `loading another year stops the old year flow from overwriting state`() {
        val vm = checkNotNull(viewModel)

        vm.loadTotalsForYear(2025)
        assertEquals(listOf("2025-11"), vm.totalsDrillDownState.value.periodTotals.map { it.periodKey })

        initialYearFlow.value = listOf(month(1970, 11, 999.0))

        assertEquals(listOf("2025-11"), vm.totalsDrillDownState.value.periodTotals.map { it.periodKey })
        assertEquals(listOf(0.0), vm.totalsDrillDownState.value.periodTotals.map { it.totalAmount })

        // Control: the current year flow still updates the state.
        year2025Flow.value = listOf(month(2025, 11, 50.0))
        assertEquals(listOf(50.0), vm.totalsDrillDownState.value.periodTotals.map { it.totalAmount })
    }

    @Test
    fun `drilling down stops updates from the year flow`() {
        val vm = checkNotNull(viewModel)
        vm.loadTotalsForYear(2025)

        vm.drillDownToPeriod(month(2025, 11, 0.0))
        assertEquals(PeriodType.WEEK, vm.totalsDrillDownState.value.currentLevel)

        year2025Flow.value = listOf(month(2025, 11, 777.0))

        assertEquals(PeriodType.WEEK, vm.totalsDrillDownState.value.currentLevel)
        assertEquals(listOf("2025-W45"), vm.totalsDrillDownState.value.periodTotals.map { it.periodKey })
    }

    private companion object {
        fun month(year: Int, month: Int, amount: Double) = PeriodTotal(
            periodLabel = "M$month",
            periodKey = "%d-%02d".format(year, month),
            totalAmount = amount,
            transactionCount = if (amount > 0.0) 1 else 0,
            periodType = PeriodType.MONTH,
            startDateMs = 0L,
            endDateMs = 1L,
            status = PeriodStatus.NO_DATA
        )

        fun week(key: String, amount: Double) = PeriodTotal(
            periodLabel = key,
            periodKey = key,
            totalAmount = amount,
            transactionCount = 1,
            periodType = PeriodType.WEEK,
            startDateMs = 0L,
            endDateMs = 1L,
            status = PeriodStatus.NO_DATA
        )
    }
}
