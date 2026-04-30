package com.yourname.expensetracker.ui.screens.analytics

import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.domain.analytics.AdvancedAnalyticsDashboard
import com.yourname.expensetracker.domain.analytics.AnalyticsDashboardData
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AdvancedAnalyticsViewModelTest {

    private val analyticsDashboard = mockk<AdvancedAnalyticsDashboard>()
    private val homeCurrencyFlow = MutableStateFlow("EUR")
    private val rateUpdateFlow = MutableStateFlow(0L)
    private val currencySettingsRepository = mockk<CurrencySettingsRepository>()
    private val timeProvider = FakeTimeProvider(1_730_000_000_000L)

    @Test
    fun `uiState reloads when home currency changes`() = runTest {
        every { currencySettingsRepository.homeCurrency() } returns homeCurrencyFlow
        every { currencySettingsRepository.lastRateUpdate() } returns rateUpdateFlow
        coEvery { analyticsDashboard.generateDashboardData(any(), any()) } returns dashboardData("EUR") andThen dashboardData("USD")

        val viewModel = AdvancedAnalyticsViewModel(
            analyticsDashboard = analyticsDashboard,
            currencySettingsRepository = currencySettingsRepository,
            timeProvider = timeProvider
        )

        advanceUntilIdle()
        homeCurrencyFlow.value = "USD"
        advanceUntilIdle()

        val state = viewModel.uiState.value as AnalyticsUiState.Success
        assertThat(state.homeCurrency).isEqualTo("USD")
        coVerify(exactly = 2) { analyticsDashboard.generateDashboardData(any(), any()) }
    }

    @Test
    fun `uiState exposes latest rate timestamp from settings`() = runTest {
        every { currencySettingsRepository.homeCurrency() } returns homeCurrencyFlow
        every { currencySettingsRepository.lastRateUpdate() } returns rateUpdateFlow
        coEvery { analyticsDashboard.generateDashboardData(any(), any()) } returns dashboardData("EUR")

        val viewModel = AdvancedAnalyticsViewModel(
            analyticsDashboard = analyticsDashboard,
            currencySettingsRepository = currencySettingsRepository,
            timeProvider = timeProvider
        )

        rateUpdateFlow.value = 9876L
        advanceUntilIdle()

        val state = viewModel.uiState.value as AnalyticsUiState.Success
        assertThat(state.latestRateTimestamp).isEqualTo(9876L)
    }

    private fun dashboardData(currency: String) = AnalyticsDashboardData(
        totalSpent = 10.0,
        totalIncome = 20.0,
        netCashflow = 10.0,
        displayCurrency = currency,
        topCategories = emptyList(),
        topMerchants = emptyList(),
        monthlyTrend = emptyList(),
        weeklyPattern = emptyList(),
        insights = emptyList(),
        conversionWarnings = emptyList()
    )
}
