package com.yourname.expensetracker.ui.screens.currency

import app.cash.turbine.test
import com.yourname.expensetracker.data.database.entity.ExchangeRate
import com.yourname.expensetracker.data.repository.CurrencyDataRepository
import com.yourname.expensetracker.domain.currency.ConversionResult
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencyRatesRepository
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.currency.SupportedCurrency
import com.yourname.expensetracker.domain.core.money.ConversionFailureType
import com.yourname.expensetracker.domain.core.money.ConversionOutcome
import com.yourname.expensetracker.domain.core.money.ConversionPath
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.RateBasis
import com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier
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

    private lateinit var hybridClassifier: HybridExpenseClassifier
    private lateinit var viewModel: CurrencyManagementViewModel

    @Before
    override fun setup() {
        super.setup()

        every { settingsRepository.homeCurrency() } returns flowOf("EUR")
        coEvery { settingsRepository.resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        every { settingsRepository.lastRateUpdate() } returns flowOf(1_700_000_000_000L)
        coEvery { settingsRepository.areRatesStale(any()) } returns false
        every { currencyDataRepository.getRatesToCurrency("EUR") } returns flowOf(emptyList())
        hybridClassifier = mockk(relaxed = true)

        viewModel = createViewModel()
    }

    @Test
    fun `initial state shows available currencies`() = runTest(testDispatcher) {
        val rates = listOf(
            ExchangeRate(fromCurrency = "EUR", toCurrency = "USD", rate = 1.1, lastUpdated = 1_700_000_100_000L),
            ExchangeRate(fromCurrency = "EUR", toCurrency = "GBP", rate = 0.86, lastUpdated = 1_700_000_200_000L)
        )
        every { currencyDataRepository.getRatesToCurrency("EUR") } returns flowOf(rates)

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
            currencyConverter.convertOutcome(
                100.0,
                "EUR",
                "USD",
                RateBasis.LATEST_AVAILABLE,
                null,
                any()
            )
        } returns convertedOutcome()

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
    fun `selector exposes exactly the active currency catalog`() = runTest(testDispatcher) {
        advanceUntilIdle()

        assertEquals(
            SupportedCurrency.activeCatalog.map { it.code },
            viewModel.uiState.value.supportedCurrencies.map { it.code }
        )
        assertTrue(viewModel.uiState.value.supportedCurrencies.any { it.code == "CNY" })
        assertTrue(viewModel.uiState.value.supportedCurrencies.any { it.code == "INR" })
        assertFalse(viewModel.uiState.value.supportedCurrencies.any { it.code == "HRK" })
    }

    @Test
    fun `invalid and inactive home currencies do not write or invalidate`() = runTest(testDispatcher) {
        advanceUntilIdle()

        viewModel.setHomeCurrency("XXX")
        viewModel.setHomeCurrency("HRK")
        advanceUntilIdle()

        coVerify(exactly = 0) { settingsRepository.setHomeCurrency(any()) }
        coVerify(exactly = 0) { hybridClassifier.invalidateCategorySnapshot() }
        assertEquals("UNSUPPORTED_HOME_CURRENCY", viewModel.uiState.value.error)
    }

    @Test
    fun `valid lowercase home currency writes canonical code and invalidates`() = runTest(testDispatcher) {
        advanceUntilIdle()

        viewModel.setHomeCurrency("usd")
        advanceUntilIdle()

        coVerify(exactly = 1) { settingsRepository.setHomeCurrency("USD") }
        coVerify(exactly = 1) { hybridClassifier.invalidateCategorySnapshot() }
    }

    @Test
    fun `failed conversion clears stale result with controlled error`() = runTest(testDispatcher) {
        coEvery {
            currencyConverter.convertOutcome(any(), any(), any(), any(), any(), any())
        } returnsMany listOf(
            convertedOutcome(),
            ConversionOutcome.Failed(
                originalAmount = 100.0,
                originalCurrency = "EUR",
                targetCurrency = "USD",
                rateBasis = RateBasis.LATEST_AVAILABLE,
                failureType = ConversionFailureType.MISSING_RATE,
                message = ConversionFailureType.MISSING_RATE.name
            )
        )
        advanceUntilIdle()

        viewModel.convert(100.0, "EUR", "USD")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.conversionResult != null)

        viewModel.convert(100.0, "EUR", "USD")
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.conversionResult)
        assertEquals("CURRENCY_CONVERSION_UNAVAILABLE", viewModel.uiState.value.error)
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

        // Extra entry for the setup viewModel's pending init coroutine
        every {
            currencyDataRepository.getRatesToCurrency("EUR")
        } returnsMany listOf(flowOf(emptyList()), flowOf(beforeRates), flowOf(afterRates))
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
            assertEquals("CURRENCY_RATE_REFRESH_UNAVAILABLE", error.error)

            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun createViewModel(): CurrencyManagementViewModel {
        return CurrencyManagementViewModel(
            currencyDataRepository = currencyDataRepository,
            currencyConverter = currencyConverter,
            currencyRatesRepository = currencyRatesRepository,
            settingsRepository = settingsRepository,
            hybridClassifier = hybridClassifier
        )
    }

    private fun convertedOutcome() = ConversionOutcome.Converted(
        originalAmount = 100.0,
        originalCurrency = CurrencyCode("EUR"),
        convertedAmount = 110.0,
        targetCurrency = CurrencyCode("USD"),
        rateUsed = 1.1,
        rateBasis = RateBasis.LATEST_AVAILABLE,
        rateValidDate = 1_700_000_300_000L,
        rateLastUpdated = 1_700_000_300_000L,
        rateSource = "ecb",
        conversionPath = ConversionPath.DIRECT
    )
}
