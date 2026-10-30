package com.yourname.expensetracker.ui.screens.home

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.model.DashboardWidgetConfig
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.DashboardRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.HomeInstallmentWeather
import com.yourname.expensetracker.data.repository.HomeInstallmentWeatherSource
import com.yourname.expensetracker.data.repository.PlannedExpenseRepository
import com.yourname.expensetracker.domain.analytics.AdvancedAnalyticsEngine
import com.yourname.expensetracker.domain.analytics.TotalsAggregationEngine
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiEngagementState
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.OnDeviceModelStatus
import com.yourname.expensetracker.domain.ai.service.AiArtifactRepository
import com.yourname.expensetracker.domain.ai.service.AiEngagementRepository
import com.yourname.expensetracker.domain.ai.service.AiEnvironmentMonitor
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.installment.model.InstallmentCurrency
import com.yourname.expensetracker.domain.installment.projection.InstallmentUpcomingRow
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.model.RecurringPattern
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.model.UpcomingItem
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
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * S8b-3: the Home merge point (plan A15 option A). After `ComputeDashboardWidgetsUseCase.compute()`
 * HomeViewModel REPLACES the single FinancialWeatherWidget with the installment-aware weather when the
 * seam [HomeViewModel.installmentsEnabled] is on and [HomeInstallmentWeatherSource] emits non-null. It
 * never adds a second weather widget, never touches another widget, and the installment rows are a
 * separate Home-only state, not a field of [DashboardState].
 *
 * Same harness as [HomeViewModelWidgetsTest]: Main is an [UnconfinedTestDispatcher] whose scheduler
 * nobody advances (parks the midnight-refresh loop) and waits are real-time on StateFlow conditions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelInstallmentWeatherTest {

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
    private lateinit var weatherSource: HomeInstallmentWeatherSource

    private val configFlow = MutableStateFlow(defaultConfig())
    private var renderedWidgets: List<DashboardWidget> = emptyList()
    private val createdViewModels = mutableListOf<HomeViewModel>()

    private val baselineWeather = weather("baseline", upcoming = listOf(upcomingItem()), committed = 140.0)
    private val awareWeather = weather("aware", upcoming = emptyList(), committed = 90.0)
    private val rows = listOf(
        InstallmentUpcomingRow(
            planId = 1L,
            entryId = 11L,
            displayName = "Synthetic plan",
            seq = 1,
            currency = InstallmentCurrency.USD,
            dueEpochDay = 20_000L,
            amountMinor = 10_000L
        )
    )

    @Before
    fun setup() {
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
        weatherSource = mockk(relaxed = true)

        renderedWidgets = listOf(
            DashboardWidget.SafeToSpend(100.0, 500.0, 15),
            DashboardWidget.FinancialWeatherWidget(baselineWeather),
            DashboardWidget.PeriodSummary(10.0, 50.0, 200.0)
        )

        every { aiSettingsRepository.settings() } returns flowOf(AiSettings())
        every { aiEngagementRepository.engagementState() } returns flowOf(AiEngagementState())
        every { timeProvider.now() } returns 0L
        coEvery { aiEnvironmentMonitor.getOnDeviceModelStatus(AiCapability.DASHBOARD_BRIEFING) } returns
            OnDeviceModelStatus.AVAILABLE
        every { currencySettingsRepository.homeCurrency() } returns flowOf("EUR")
        every { widgetStyleRepository.config() } returns flowOf(WidgetStyleConfig())
        every { totalsAggregationEngine.getMonthlyTotals(any()) } returns flowOf(emptyList())
        coEvery { totalsAggregationEngine.getAverageForPeriodType(any(), any()) } returns null

        every { dashboardDataProvider.getProcessedDataFlow(analyticsRepository) } returns flowOf(processedData())
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
        every { aiArtifactRepository.observeLatest(any(), any()) } returns flowOf(null)
        every { categoryRepository.allCategories } returns flowOf(emptyList())
        coEvery { categoryRepository.ensureDefaultCategories() } returns Unit
    }

    @After
    fun tearDown() {
        createdViewModels.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    // ---- fixtures ----------------------------------------------------------------------------

    private fun createViewModel(seamOn: Boolean): HomeViewModel {
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
            homeInstallmentWeatherSource = weatherSource
        )
        created.installmentsEnabled = seamOn
        createdViewModels += created
        return created
    }

    /** Collects the dashboard in real time (stateIn is WhileSubscribed) and runs [block]. */
    private fun withDashboard(vm: HomeViewModel, block: suspend CoroutineScope.() -> Unit) = runBlocking {
        val subscription = launch(Dispatchers.Unconfined) { vm.dashboard.collect { } }
        try {
            block()
        } finally {
            subscription.cancelAndJoin()
        }
    }

    /**
     * Waits for a Ready state. With the seam on, the first Ready state may be the baseline (the
     * installment flow is seeded with null so a silent source never delays Home), so tests that
     * expect the aware weather pass [until].
     */
    private suspend fun awaitReady(
        vm: HomeViewModel,
        expectedWidgets: Int,
        until: (DashboardState) -> Boolean = { true }
    ): DashboardState =
        withTimeout(WATCHDOG_MILLIS) {
            vm.dashboard.first { !it.isLoading && it.widgets.size == expectedWidgets && until(it) }
        }

    private fun stateOf(
        seamOn: Boolean,
        expectedWidgets: Int = 3,
        until: (DashboardState) -> Boolean = { true }
    ): DashboardState {
        val vm = createViewModel(seamOn)
        var state: DashboardState? = null
        withDashboard(vm) { state = awaitReady(vm, expectedWidgets, until) }
        return checkNotNull(state)
    }

    private fun weatherWidgets(state: DashboardState): List<DashboardWidget.FinancialWeatherWidget> =
        state.widgets.filterIsInstance<DashboardWidget.FinancialWeatherWidget>()

    private fun ids(state: DashboardState): List<String> = state.widgets.map { DashboardWidgetRegistry.idFor(it) }

    private fun defaultConfig(): List<DashboardWidgetConfig> = listOf(
        DashboardWidgetConfig("safe_to_spend", 0, true),
        DashboardWidgetConfig("financial_weather", 1, true),
        DashboardWidgetConfig("period_summary", 2, true)
    )

    private fun upcomingItem() = UpcomingItem.Recurring(
        RecurringPattern(
            merchantName = "Synthetic rent",
            averageAmount = 600.0,
            currency = "EUR",
            frequency = RecurrenceFrequency.MONTHLY,
            periodVarianceDays = 0,
            amountVariancePercent = 0.0,
            nextExpectedDate = 1_000L,
            confidence = 0.95f,
            previousDates = emptyList()
        )
    )

    private fun weather(label: String, upcoming: List<UpcomingItem>, committed: Double = 0.0) = FinancialWeather(
        state = WeatherState.CLOUDY,
        headline = UiText.DynamicString(label),
        summary = UiText.DynamicString(""),
        icon = "",
        riskLevel = 10,
        totalCommitted = committed,
        totalLikely = 0.0,
        predictedDiscretionary = 0.0,
        discretionaryBudget = 0.0,
        upcomingItems = upcoming
    )

    private fun processedData() = ProcessedDashboardData(
        data = DashboardData(
            expenses = emptyList(),
            categories = emptyList(),
            budgetStatuses = emptyList(),
            pendingCount = 0,
            weather = baselineWeather,
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

    // ---- the merge ---------------------------------------------------------------------------

    @Test
    fun `seam on and a non-null aware weather - the single weather widget is replaced and not added`() {
        every { weatherSource.observe() } returns flowOf(HomeInstallmentWeather(awareWeather, rows))

        val state = stateOf(seamOn = true, until = { weatherWidgets(it).singleOrNull()?.weather?.totalCommitted == 90.0 })

        // Exactly one weather widget, in the same position, and it carries the aware numbers.
        assertEquals(listOf("safe_to_spend", "financial_weather", "period_summary"), ids(state))
        val weatherWidget = weatherWidgets(state).single()
        assertEquals(90.0, weatherWidget.weather.totalCommitted, 0.0)
        assertEquals(UiText.DynamicString("aware"), weatherWidget.weather.headline)
        // upcomingItems stay the baseline's: a projection is never an UpcomingItem.
        assertEquals(baselineWeather.upcomingItems, weatherWidget.weather.upcomingItems)
        assertEquals(1, weatherWidget.weather.upcomingItems.size)
        // The aware and the baseline numbers are never summed (140 + 90).
        assertNotEquals(230.0, weatherWidget.weather.totalCommitted, 0.0)
        // Other widgets are the very same objects compute() returned.
        assertSame(renderedWidgets[0], state.widgets[0])
        assertSame(renderedWidgets[2], state.widgets[2])
    }

    @Test
    fun `the installment rows are a separate Home-only state and DashboardState has no installment or weather field`() {
        every { weatherSource.observe() } returns flowOf(HomeInstallmentWeather(awareWeather, rows))
        val vm = createViewModel(seamOn = true)

        withDashboard(vm) {
            awaitReady(vm, 3)
            val emitted = withTimeout(WATCHDOG_MILLIS) { vm.installmentRows.first { it.isNotEmpty() } }
            assertEquals(rows, emitted)
        }

        val fieldTypes = DashboardState::class.java.declaredFields.map { it.type.name }
        assertFalse(fieldTypes.any { it.contains("FinancialWeather") })
        assertFalse(fieldTypes.any { it.contains("HomeInstallmentWeather") })
        assertFalse(fieldTypes.any { it.contains("InstallmentUpcomingRow") })
    }

    @Test
    fun `no baseline weather widget to replace - nothing is added`() {
        renderedWidgets = listOf(
            DashboardWidget.SafeToSpend(100.0, 500.0, 15),
            DashboardWidget.PeriodSummary(10.0, 50.0, 200.0)
        )
        every { weatherSource.observe() } returns flowOf(HomeInstallmentWeather(awareWeather, rows))

        val state = stateOf(seamOn = true, expectedWidgets = 2)

        assertTrue(weatherWidgets(state).isEmpty())
        assertEquals(listOf("safe_to_spend", "period_summary"), ids(state))
    }

    // ---- dormant and empty: the state equals pre-S8b ------------------------------------------

    @Test
    fun `seam off - the source is never read and the baseline widget is rendered unchanged`() {
        every { weatherSource.observe() } returns flowOf(HomeInstallmentWeather(awareWeather, rows))

        val vm = createViewModel(seamOn = false)
        var state: DashboardState? = null
        withDashboard(vm) {
            state = awaitReady(vm, 3)
            assertTrue(vm.installmentRows.value.isEmpty())
        }

        val rendered = checkNotNull(state)
        assertSame(baselineWeather, weatherWidgets(rendered).single().weather)
        assertEquals(renderedWidgets, rendered.widgets)
        verify(exactly = 0) { weatherSource.observe() }
    }

    @Test
    fun `the production default of the seam is the compile-time flag, now on`() {
        // createViewModel overrides the seam, so build the VM directly to read the untouched default.
        val untouched = HomeViewModel(
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
            homeInstallmentWeatherSource = weatherSource
        )
        createdViewModels += untouched

        assertEquals(com.yourname.expensetracker.ui.navigation.InstallmentsFeature.ENABLED, untouched.installmentsEnabled)
        // S13-b (owner-approvals 91(b)): the compile-time default is now true.
        assertTrue(untouched.installmentsEnabled)
    }

    /**
     * Like [stateOf] but only returns after the aware source has really been collected AND has run
     * ([afterSourceRan] completes), so the compared state is the SETTLED one and not the seeded
     * baseline that exists before the source is read (which would make the comparison vacuous).
     */
    private fun settledStateOf(seamOn: Boolean, afterSourceRan: CompletableDeferred<Unit>?): DashboardState {
        val vm = createViewModel(seamOn)
        var state: DashboardState? = null
        withDashboard(vm) {
            awaitReady(vm, 3)
            if (afterSourceRan != null) {
                withTimeout(WATCHDOG_MILLIS) { afterSourceRan.await() }
                // Let the already-emitted source value propagate through the shared flow and the combine.
                delay(SETTLE_MILLIS)
            }
            state = awaitReady(vm, 3)
        }
        return checkNotNull(state)
    }

    @Test
    fun `seam on but the port is empty - the dashboard state equals the seam-off state`() {
        val sourceRan = CompletableDeferred<Unit>()
        every { weatherSource.observe() } returns flow<HomeInstallmentWeather?> {
            emit(null)
            sourceRan.complete(Unit)
        }

        val seamOff = settledStateOf(seamOn = false, afterSourceRan = null)
        val seamOnEmptyPort = settledStateOf(seamOn = true, afterSourceRan = sourceRan)

        verify(atLeast = 1) { weatherSource.observe() }
        assertEquals(seamOff, seamOnEmptyPort)
        assertSame(baselineWeather, weatherWidgets(seamOnEmptyPort).single().weather)
    }

    @Test
    fun `seam on and a source that never emits - the dashboard still reaches Ready with the baseline widget`() {
        val sourceStarted = CompletableDeferred<Unit>()
        every { weatherSource.observe() } returns flow<HomeInstallmentWeather?> {
            sourceStarted.complete(Unit)
            awaitCancellation()
        }

        val seamOff = settledStateOf(seamOn = false, afterSourceRan = null)
        val silent = settledStateOf(seamOn = true, afterSourceRan = sourceStarted)

        verify(atLeast = 1) { weatherSource.observe() }
        assertFalse(silent.isLoading)
        assertSame(baselineWeather, weatherWidgets(silent).single().weather)
        assertEquals(seamOff, silent)
    }

    @Test
    fun `seam on but the source fails - the baseline widget is kept and the dashboard still renders`() {
        val sourceRan = CompletableDeferred<Unit>()
        every { weatherSource.observe() } returns flow<HomeInstallmentWeather?> {
            sourceRan.complete(Unit)
            throw IllegalStateException("synthetic failure")
        }

        val seamOff = settledStateOf(seamOn = false, afterSourceRan = null)
        val failed = settledStateOf(seamOn = true, afterSourceRan = sourceRan)

        verify(atLeast = 1) { weatherSource.observe() }
        assertEquals(seamOff, failed)
        assertSame(baselineWeather, weatherWidgets(failed).single().weather)
    }

    // ---- reload: generation safety and failure isolation ----------------------------------------

    @Test
    fun `a reload never combines the old generation aware weather with the new baseline`() {
        val homeCurrency = MutableStateFlow("EUR")
        every { currencySettingsRepository.homeCurrency() } returns homeCurrency
        val newGenerationGate = CompletableDeferred<Unit>()
        val calls = AtomicInteger(0)
        val awareNew = weather("aware-new", upcoming = emptyList(), committed = 70.0)
        every { weatherSource.observe() } answers {
            if (calls.incrementAndGet() == 1) {
                flowOf(HomeInstallmentWeather(awareWeather, rows))
            } else {
                flow<HomeInstallmentWeather?> {
                    newGenerationGate.await()
                    emit(HomeInstallmentWeather(awareNew, rows))
                }
            }
        }
        val vm = createViewModel(seamOn = true)
        val recorded = CopyOnWriteArrayList<DashboardState>()

        withDashboard(vm) {
            val collector = launch(Dispatchers.Unconfined) { vm.dashboard.collect { recorded += it } }
            awaitReady(vm, 3, until = { weatherWidgets(it).singleOrNull()?.weather?.headline == UiText.DynamicString("aware") })

            // A home-currency change: new baseline, new generation; the new aware value is gated.
            val newBaseline = weather("baseline-new", upcoming = emptyList(), committed = 150.0)
            renderedWidgets = listOf(
                DashboardWidget.SafeToSpend(100.0, 500.0, 15),
                DashboardWidget.FinancialWeatherWidget(newBaseline),
                DashboardWidget.PeriodSummary(10.0, 50.0, 200.0)
            )
            val mark = recorded.size
            homeCurrency.value = "USD"
            awaitReady(vm, 3, until = { weatherWidgets(it).singleOrNull()?.weather?.headline == UiText.DynamicString("baseline-new") })
            // The gate is still closed: the old-generation aware weather must not have been combined.
            assertTrue(
                "an old-generation aware weather was combined with the new baseline",
                recorded.drop(mark).none { weatherWidgets(it).singleOrNull()?.weather?.headline == UiText.DynamicString("aware") }
            )

            // The new generation's aware weather replaces the new baseline once it arrives.
            newGenerationGate.complete(Unit)
            awaitReady(vm, 3, until = { weatherWidgets(it).singleOrNull()?.weather?.headline == UiText.DynamicString("aware-new") })
            assertTrue(
                "an old-generation aware weather was combined with the new baseline",
                recorded.drop(mark).none { weatherWidgets(it).singleOrNull()?.weather?.headline == UiText.DynamicString("aware") }
            )
            collector.cancelAndJoin()
        }
        verify(atLeast = 2) { weatherSource.observe() }
    }

    @Test
    fun `a failure of the aware source keeps the baseline and a later reload resumes the aware weather`() {
        val homeCurrency = MutableStateFlow("EUR")
        every { currencySettingsRepository.homeCurrency() } returns homeCurrency
        val failureSeen = CompletableDeferred<Unit>()
        val calls = AtomicInteger(0)
        every { weatherSource.observe() } answers {
            if (calls.incrementAndGet() == 1) {
                flow<HomeInstallmentWeather?> {
                    failureSeen.complete(Unit)
                    throw IllegalStateException("synthetic failure")
                }
            } else {
                flowOf(HomeInstallmentWeather(awareWeather, rows))
            }
        }
        val vm = createViewModel(seamOn = true)

        withDashboard(vm) {
            val first = awaitReady(vm, 3)
            withTimeout(WATCHDOG_MILLIS) { failureSeen.await() }
            delay(SETTLE_MILLIS)
            // After the failure the baseline stays (fail closed).
            assertSame(baselineWeather, weatherWidgets(vm.dashboard.value).single().weather)
            assertSame(baselineWeather, weatherWidgets(first).single().weather)

            // The reload (currency change) must restart the aware source: one failure does not stop it.
            homeCurrency.value = "USD"
            val resumed = awaitReady(vm, 3, until = { weatherWidgets(it).singleOrNull()?.weather?.headline == UiText.DynamicString("aware") })
            assertEquals(90.0, weatherWidgets(resumed).single().weather.totalCommitted, 0.0)
        }
        verify(atLeast = 2) { weatherSource.observe() }
    }

    private companion object {
        /** Failure watchdog only; never part of an expected value. */
        const val WATCHDOG_MILLIS = 10_000L

        /** Real-time grace for an already-emitted source value to propagate; never part of an expected value. */
        const val SETTLE_MILLIS = 200L
    }
}
