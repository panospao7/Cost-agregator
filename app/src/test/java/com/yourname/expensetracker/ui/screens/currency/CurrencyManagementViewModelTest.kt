package com.yourname.expensetracker.ui.screens.currency

import app.cash.turbine.test
import com.yourname.expensetracker.data.database.entity.ExchangeRate
import com.yourname.expensetracker.data.repository.CurrencyDataRepository
import com.yourname.expensetracker.domain.currency.ConversionResult
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencyRatesRepository
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CurrencyManagementViewModelTest : ViewModelTestUtils() {

    private val currencyDataRepository = mockk<CurrencyDataRepository>(relaxed = true)
    private val currencyConverter = mockk<CurrencyConverter>(relaxed = true)
    private val currencyRatesRepository = mockk<CurrencyRatesRepository>(relaxed = true)
    private val settingsRepository = mockk<CurrencySettingsRepository>(relaxed = true)

    private lateinit var viewModel: CurrencyManagementViewModel

    @Before
    override fun setup() {
        super.setup()

        every { settingsRepository.homeCurrency() } returns flowOf("EUR")
        every { settingsRepository.lastRateUpdate() } returns flowOf(1_700_000_000_000L)
        coEvery { settingsRepository.areRatesStale(any()) } returns false
        every { currencyDataRepository.getAllRatesForBase("EUR") } returns flowOf(emptyList())

        viewModel = createViewModel()
    }

    @Test
    fun `initial state shows available currencies`() = runTest(testDispatcher) {
        val rates = listOf(
            ExchangeRate(fromCurrency = "EUR", toCurrency = "USD", rate = 1.1, lastUpdated = 1_700_000_100_000L),
            ExchangeRate(fromCurrency = "EUR", toCurrency = "GBP", rate = 0.86, lastUpdated = 1_700_000_200_000L)
        )
        every { currencyDataRepository.getAllRatesForBase("EUR") } returns flowOf(rates)

        viewModel = createViewModel()

        viewModel.uiState.test {
            val initial = awaitItem()
            assertFalse(initial.isLoading)

            advanceUntilIdle()

            val loading = awaitItem()
            assertTrue(loading.isLoading)

            val loaded = awaitItem()
            assertFalse(loaded.isLoading)
            assertTrue(loaded.supportedCurrencies.isNotEmpty())
            assertEquals(2, loaded.exchangeRates.size)
            assertEquals("EUR", loaded.homeCurrency)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `currency selection updates conversion display`() = runTest(testDispatcher) {
        val result = ConversionResult(
            originalAmount = 100.0,
            originalCurrency = "EUR",
            convertedAmount = 110.0,
            targetCurrency = "USD",
            rateUsed = 1.1,
            timestamp = 1_700_000_300_000L
        )
        coEvery {
            currencyConverter.convert(
                amount = 100.0,
                fromCurrency = "EUR",
                toCurrency = "USD"
            )
        } returns result

        advanceUntilIdle()

        viewModel.uiState.test {
            awaitItem() // current settled state

            viewModel.convert(100.0, "EUR", "USD")
            advanceUntilIdle()

            val converted = awaitItem()
            assertEquals(result, converted.conversionResult)
            assertEquals(null, converted.error)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `rate refresh triggers loading then updates rates`() = runTest(testDispatcher) {
        val beforeRates = listOf(
            ExchangeRate(fromCurrency = "EUR", toCurrency = "USD", rate = 1.0, lastUpdated = 1_700_000_000_000L)
        )
        val afterRates = listOf(
            ExchangeRate(fromCurrency = "EUR", toCurrency = "USD", rate = 1.2, lastUpdated = 1_700_000_400_000L),
            ExchangeRate(fromCurrency = "EUR", toCurrency = "GBP", rate = 0.88, lastUpdated = 1_700_000_500_000L)
        )

        every {
            currencyDataRepository.getAllRatesForBase("EUR")
        } returnsMany listOf(flowOf(beforeRates), flowOf(afterRates))
        coEvery { currencyRatesRepository.refresh("EUR") } returns 2

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.uiState.test {
            val beforeRefresh = awaitItem()
            assertEquals(1, beforeRefresh.exchangeRates.size)

            viewModel.refreshRates()
            advanceUntilIdle()

            val loading = awaitItem()
            assertTrue(loading.isLoading)

            val updated = awaitItem()
            assertFalse(updated.isLoading)
            assertEquals(2, updated.exchangeRates.size)
            assertEquals(1.2, updated.exchangeRates.first { it.toCurrency == "USD" }.rate, 0.0)

            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 1) { currencyRatesRepository.refresh("EUR") }
    }

    @Test
    fun `error in rate fetch sets error state`() = runTest(testDispatcher) {
        coEvery { currencyRatesRepository.refresh("EUR") } throws IllegalStateException("network down")

        advanceUntilIdle()

        viewModel.uiState.test {
            awaitItem() // settled init state

            viewModel.refreshRates()
            advanceUntilIdle()

            val loading = awaitItem()
            assertTrue(loading.isLoading)

            val error = awaitItem()
            assertFalse(error.isLoading)
            assertTrue(error.error?.contains("Failed to refresh rates: network down") == true)

            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun createViewModel(): CurrencyManagementViewModel {
        return CurrencyManagementViewModel(
            currencyDataRepository = currencyDataRepository,
            currencyConverter = currencyConverter,
            currencyRatesRepository = currencyRatesRepository,
            settingsRepository = settingsRepository
        )
    }
}
