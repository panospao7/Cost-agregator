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
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    private val engine = mockk<TotalsAggregationEngine>(relaxed = true)
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
        val totalsAggregationEngine = engine

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
        every { totalsAggregationEngine.getYearlyTotals() } returns flowOf(emptyList())
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

    @Test
    fun `empty emission keeps the last good rows sets the error and a good emission clears it`() {
        val vm = checkNotNull(viewModel)
        initialYearFlow.value = listOf(month(1970, 11, 180.0))
        assertEquals(listOf(180.0), vm.totalsDrillDownState.value.periodTotals.map { it.totalAmount })

        initialYearFlow.value = emptyList()

        assertEquals(listOf(180.0), vm.totalsDrillDownState.value.periodTotals.map { it.totalAmount })
        assertNotNull(vm.totalsDrillDownState.value.error)
        assertFalse(vm.totalsDrillDownState.value.isLoading)

        initialYearFlow.value = listOf(month(1970, 11, 200.0))

        assertEquals(listOf(200.0), vm.totalsDrillDownState.value.periodTotals.map { it.totalAmount })
        assertNull(vm.totalsDrillDownState.value.error)
        assertFalse(vm.totalsDrillDownState.value.isLoading)
    }

    @Test
    fun `retry after a failed read reloads the viewed year and not the current year`() {
        val vm = checkNotNull(viewModel)
        val year2023Flow = MutableStateFlow(listOf(month(2023, 11, 5.0)))
        every { engine.getMonthlyTotals(2023) } returns year2023Flow
        vm.loadTotalsForYear(2023)

        year2023Flow.value = emptyList()
        assertNotNull(vm.totalsDrillDownState.value.error)

        year2023Flow.value = listOf(month(2023, 11, 9.0))
        vm.reloadCurrentTotalsLevel()

        assertEquals(listOf("2023-11"), vm.totalsDrillDownState.value.periodTotals.map { it.periodKey })
        assertEquals(listOf(9.0), vm.totalsDrillDownState.value.periodTotals.map { it.totalAmount })
        assertNull(vm.totalsDrillDownState.value.error)
        verify(exactly = 1) { engine.getMonthlyTotals(1970) } // init load only; Retry did not use the current year
    }

    @Test
    fun `a successful drill down clears an existing error`() {
        val vm = checkNotNull(viewModel)
        initialYearFlow.value = emptyList()
        assertNotNull(vm.totalsDrillDownState.value.error)

        vm.drillDownToPeriod(month(2025, 11, 0.0))

        assertEquals(PeriodType.WEEK, vm.totalsDrillDownState.value.currentLevel)
        assertNull(vm.totalsDrillDownState.value.error)
    }

    @Test
    fun `drilling up from week to the month list resumes live year updates`() {
        val vm = checkNotNull(viewModel)
        vm.loadTotalsForYear(2025)
        vm.drillDownToPeriod(year(2025))
        vm.drillDownToPeriod(month(2025, 11, 0.0))
        assertEquals(PeriodType.WEEK, vm.totalsDrillDownState.value.currentLevel)

        vm.drillUp()
        assertEquals(PeriodType.MONTH, vm.totalsDrillDownState.value.currentLevel)

        year2025Flow.value = listOf(month(2025, 11, 321.0))

        assertEquals(PeriodType.MONTH, vm.totalsDrillDownState.value.currentLevel)
        assertEquals(listOf(321.0), vm.totalsDrillDownState.value.periodTotals.map { it.totalAmount })
    }

    @Test
    fun `drilling from year to month resumes live updates for that year`() {
        val vm = checkNotNull(viewModel)

        vm.drillDownToPeriod(year(2025))
        assertEquals(PeriodType.MONTH, vm.totalsDrillDownState.value.currentLevel)

        year2025Flow.value = listOf(month(2025, 11, 64.0))

        assertEquals(PeriodType.MONTH, vm.totalsDrillDownState.value.currentLevel)
        assertEquals(listOf(64.0), vm.totalsDrillDownState.value.periodTotals.map { it.totalAmount })
    }

    @Test
    fun `a dashboard reload on a past year month list keeps that year`() {
        val vm = checkNotNull(viewModel)
        val past = MutableStateFlow(listOf(month(2025, 11, 180.0)))
        every { engine.getMonthlyTotals(2025) } returns past
        vm.drillDownToPeriod(year(2025))
        assertEquals(PeriodType.MONTH, vm.totalsDrillDownState.value.currentLevel)

        vm.reloadDashboard()

        verify(atLeast = 2) { engine.getMonthlyTotals(2025) }
        verify(exactly = 1) { engine.getMonthlyTotals(1970) } // init load only
        assertEquals(180.0, vm.totalsDrillDownState.value.periodTotals.single().total, 0.001)
    }

    @Test
    fun `an in-flight drill that finishes late does not overwrite a newer year load`() {
        val vm = checkNotNull(viewModel)
        val weeklyGate = MutableSharedFlow<List<PeriodTotal>>()
        every { engine.getWeeklyTotals(2025, 11) } returns weeklyGate
        vm.loadTotalsForYear(2025)

        vm.drillDownToPeriod(month(2025, 11, 0.0))
        assertEquals(1, weeklyGate.subscriptionCount.value) // drill is suspended, not finished

        vm.loadTotalsForYear(2025)
        weeklyGate.tryEmit(listOf(week("2025-W45-late", 99.0))) // late drill result

        assertEquals(PeriodType.MONTH, vm.totalsDrillDownState.value.currentLevel)
        assertEquals(listOf("2025-11"), vm.totalsDrillDownState.value.periodTotals.map { it.periodKey })
        assertEquals(0, weeklyGate.subscriptionCount.value)
    }

    @Test
    fun `drilling up to a non-month level does not resume year flow updates`() {
        val vm = checkNotNull(viewModel)
        every { engine.getDailyTotalsForRange(any(), any()) } returns flowOf(listOf(day("2025-11-03", 3.0)))
        vm.drillDownToPeriod(year(2025))
        vm.drillDownToPeriod(month(2025, 11, 0.0))
        vm.drillDownToPeriod(week("2025-W45", 7.0))
        assertEquals(PeriodType.DAY, vm.totalsDrillDownState.value.currentLevel)

        vm.drillUp()
        assertEquals(PeriodType.WEEK, vm.totalsDrillDownState.value.currentLevel)
        assertEquals(listOf("2025-W45"), vm.totalsDrillDownState.value.periodTotals.map { it.periodKey })

        year2025Flow.value = listOf(month(2025, 11, 888.0))

        assertEquals(PeriodType.WEEK, vm.totalsDrillDownState.value.currentLevel)
        assertEquals(listOf("2025-W45"), vm.totalsDrillDownState.value.periodTotals.map { it.periodKey })
    }

    private companion object {
        fun year(year: Int) = PeriodTotal(
            periodLabel = "$year",
            periodKey = "$year",
            totalAmount = 0.0,
            transactionCount = 0,
            periodType = PeriodType.YEAR,
            startDateMs = 0L,
            endDateMs = 1L,
            status = PeriodStatus.NO_DATA
        )

        fun day(key: String, amount: Double) = PeriodTotal(
            periodLabel = key,
            periodKey = key,
            totalAmount = amount,
            transactionCount = 1,
            periodType = PeriodType.DAY,
            startDateMs = 0L,
            endDateMs = 1L,
            status = PeriodStatus.NO_DATA
        )

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
