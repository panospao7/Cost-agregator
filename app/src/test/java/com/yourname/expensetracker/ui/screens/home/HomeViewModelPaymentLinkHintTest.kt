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
import com.yourname.expensetracker.domain.recurring.lifecycle.PaymentLinkSuggestion
import com.yourname.expensetracker.domain.recurring.lifecycle.PaymentLinkSuggestions
import com.yourname.expensetracker.domain.recurring.lifecycle.PaymentLinkUnavailableReason
import com.yourname.expensetracker.domain.recurring.usecase.MarkRecurringBillPaidUseCase
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
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * FC-PAID-UNLINKED S3 (ux-flows sections 3.2 and 9): the Home hint state. Count only, taken from the use
 * case, recomputed on resume; Unavailable is never shown as zero or as a clean None.
 *
 * Harness: same as [HomeViewModelTotalsLiveTest] (Main is an unconfined test dispatcher on a scheduler
 * nobody advances, so viewModelScope work runs eagerly and the midnight refresh loop stays parked).
 * The use case is a strict mock: HomeViewModel may only call `findSuggestions()` on it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelPaymentLinkHintTest {

    private val useCase = mockk<MarkRecurringBillPaidUseCase>()
    private var suggestionsResult: PaymentLinkSuggestions = PaymentLinkSuggestions.Available(emptyList())
    private var viewModel: HomeViewModel? = null

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { useCase.findSuggestions() } answers { suggestionsResult }
    }

    @After
    fun tearDown() {
        viewModel?.viewModelScope?.cancel()
        Dispatchers.resetMain()
    }

    private fun suggestion(occurrenceId: Long, ruleId: Long) = PaymentLinkSuggestion(
        occurrenceId = occurrenceId,
        ruleId = ruleId,
        occurrenceKey = "rule:$ruleId:$occurrenceId",
        dueDate = 2_000_000L,
        expectedAmount = 400.0,
        expectedCurrency = "EUR",
        expenseId = 900L + occurrenceId,
        expenseDate = 1_000_000L,
        paidAmount = 400.0,
        paidCurrency = "EUR",
        dayDelta = -1,
        alternatives = emptyList()
    )

    private fun available(vararg items: PaymentLinkSuggestion) = PaymentLinkSuggestions.Available(items.toList())

    private fun createViewModel(): HomeViewModel {
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
        every { totalsAggregationEngine.getMonthlyTotals(any()) } returns flowOf(emptyList())
        every { totalsAggregationEngine.getWeeklyTotals(any(), any()) } returns flowOf(emptyList())
        every { totalsAggregationEngine.getYearlyTotals() } returns flowOf(emptyList())
        coEvery { totalsAggregationEngine.getAverageForPeriodType(any(), any()) } returns null
        every { totalsAggregationEngine.getPeriodStatus(any(), any()) } returns PeriodStatus.NO_DATA

        val configFlow = MutableStateFlow(listOf(DashboardWidgetConfig("safe_to_spend", 0, true)))
        every { dashboardRepository.configFlow } returns configFlow
        every { dashboardRepository.getDashboardConfig() } answers { configFlow.value }
        every { aiArtifactRepository.observeLatest(any(), any()) } returns flowOf(null)
        every { categoryRepository.allCategories } returns flowOf(emptyList())
        coEvery { categoryRepository.ensureDefaultCategories() } returns Unit

        val created = HomeViewModel(
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
            homeInstallmentWeatherSource = mockk(relaxed = true),
            markRecurringBillPaid = useCase
        )
        viewModel = created
        return created
    }

    @Test
    fun `hint is Loading until the first read finishes and then becomes Ready with the count`() {
        val gate = CompletableDeferred<PaymentLinkSuggestions>()
        coEvery { useCase.findSuggestions() } coAnswers { gate.await() }

        val vm = createViewModel()

        assertEquals(PaymentLinkHintUiState.Loading, vm.paymentLinkHint.value)

        gate.complete(available(suggestion(1L, 10L), suggestion(2L, 11L)))

        assertEquals(PaymentLinkHintUiState.Ready(2), vm.paymentLinkHint.value)
    }

    @Test
    fun `Ready count is the number of suggestions and not the number of rules`() {
        suggestionsResult = available(suggestion(1L, 10L), suggestion(2L, 10L), suggestion(3L, 11L))

        val vm = createViewModel()

        assertEquals(PaymentLinkHintUiState.Ready(3), vm.paymentLinkHint.value)
    }

    @Test
    fun `a single suggestion gives Ready one`() {
        suggestionsResult = available(suggestion(1L, 10L))

        assertEquals(PaymentLinkHintUiState.Ready(1), createViewModel().paymentLinkHint.value)
    }

    @Test
    fun `empty Available maps to None and never to Ready zero`() {
        suggestionsResult = available()

        assertEquals(PaymentLinkHintUiState.None, createViewModel().paymentLinkHint.value)
    }

    @Test
    fun `INV-11 both unavailable reasons map to Unavailable and never to None`() {
        for (reason in PaymentLinkUnavailableReason.values()) {
            suggestionsResult = PaymentLinkSuggestions.Unavailable(reason)

            assertEquals(
                "reason $reason",
                PaymentLinkHintUiState.Unavailable,
                createViewModel().paymentLinkHint.value
            )
        }
    }

    @Test
    fun `a use case exception maps to Unavailable and its text is not kept`() {
        coEvery { useCase.findSuggestions() } coAnswers { throw IllegalStateException("db-secret-detail") }

        val vm = createViewModel()

        assertEquals(PaymentLinkHintUiState.Unavailable, vm.paymentLinkHint.value)
        assertFalse(vm.paymentLinkHint.value.toString().contains("db-secret-detail"))
    }

    @Test
    fun `a CancellationException is rethrown and not mapped to Unavailable`() {
        coEvery { useCase.findSuggestions() } coAnswers { throw CancellationException("cancelled-by-test") }

        val vm = createViewModel()

        assertEquals(PaymentLinkHintUiState.Loading, vm.paymentLinkHint.value)
    }

    @Test
    fun `refresh on resume recomputes the hint after the user linked or declined meanwhile`() {
        suggestionsResult = available(suggestion(1L, 10L), suggestion(2L, 11L))
        val vm = createViewModel()
        assertEquals(PaymentLinkHintUiState.Ready(2), vm.paymentLinkHint.value)

        suggestionsResult = available(suggestion(2L, 11L))
        vm.refreshPaymentLinkHint()
        assertEquals(PaymentLinkHintUiState.Ready(1), vm.paymentLinkHint.value)

        suggestionsResult = available()
        vm.refreshPaymentLinkHint()
        assertEquals(PaymentLinkHintUiState.None, vm.paymentLinkHint.value)

        coVerify(exactly = 3) { useCase.findSuggestions() }
    }

    @Test
    fun `a refresh that finds the read blocked replaces Ready with Unavailable and not with a stale count`() {
        suggestionsResult = available(suggestion(1L, 10L))
        val vm = createViewModel()
        assertEquals(PaymentLinkHintUiState.Ready(1), vm.paymentLinkHint.value)

        suggestionsResult = PaymentLinkSuggestions.Unavailable(PaymentLinkUnavailableReason.READ_BLOCKED)
        vm.refreshPaymentLinkHint()

        assertEquals(PaymentLinkHintUiState.Unavailable, vm.paymentLinkHint.value)
    }

    @Test
    fun `the previous value stays visible while a recompute is pending`() {
        suggestionsResult = available(suggestion(1L, 10L))
        val vm = createViewModel()
        val gate = CompletableDeferred<PaymentLinkSuggestions>()
        coEvery { useCase.findSuggestions() } coAnswers { gate.await() }

        vm.refreshPaymentLinkHint()

        // No flicker to Loading.
        assertEquals(PaymentLinkHintUiState.Ready(1), vm.paymentLinkHint.value)

        gate.complete(available())
        assertEquals(PaymentLinkHintUiState.None, vm.paymentLinkHint.value)
    }

    @Test
    fun `a stale older read finishing late does not overwrite the newer result`() {
        val oldGate = CompletableDeferred<PaymentLinkSuggestions>()
        coEvery { useCase.findSuggestions() } coAnswers { oldGate.await() }
        val vm = createViewModel()
        assertEquals(PaymentLinkHintUiState.Loading, vm.paymentLinkHint.value)

        coEvery { useCase.findSuggestions() } coAnswers { available(suggestion(1L, 10L)) }
        vm.refreshPaymentLinkHint()
        assertEquals(PaymentLinkHintUiState.Ready(1), vm.paymentLinkHint.value)

        oldGate.complete(PaymentLinkSuggestions.Unavailable(PaymentLinkUnavailableReason.READ_FAILED))

        assertEquals(PaymentLinkHintUiState.Ready(1), vm.paymentLinkHint.value)
    }
}
