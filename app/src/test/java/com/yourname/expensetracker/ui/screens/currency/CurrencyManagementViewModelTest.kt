package com.yourname.expensetracker.ui.screens.currency

import androidx.sqlite.db.SupportSQLiteProgram
import androidx.sqlite.db.SupportSQLiteQuery
import app.cash.turbine.test
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.PendingReviewDao
import com.yourname.expensetracker.data.database.dao.UserCorrectionDao
import com.yourname.expensetracker.data.database.entity.ExchangeRate
import com.yourname.expensetracker.data.repository.CurrencyDataRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.MerchantCategoryRepository
import com.yourname.expensetracker.domain.analytics.TransferDirectionAnalytics
import com.yourname.expensetracker.domain.currency.ConversionResult
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencyRateRefreshOutcome
import com.yourname.expensetracker.domain.currency.CurrencyRatesRepository
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.currency.ReadHomeCurrencyState
import com.yourname.expensetracker.domain.currency.ReadHomeFailure
import com.yourname.expensetracker.domain.currency.SupportedCurrency
import com.yourname.expensetracker.domain.core.money.ConversionFailureType
import com.yourname.expensetracker.domain.core.money.ConversionOutcome
import com.yourname.expensetracker.domain.core.money.ConversionPath
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.RateBasis
import com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber

@OptIn(ExperimentalCoroutinesApi::class)
class CurrencyManagementViewModelTest : ViewModelTestUtils() {

    private val currencyDataRepository = mockk<CurrencyDataRepository>(relaxed = true)
    private val currencyConverter = mockk<CurrencyConverter>(relaxed = true)
    private val currencyRatesRepository = mockk<CurrencyRatesRepository>(relaxed = true)
    private val settingsRepository = mockk<CurrencySettingsRepository>(relaxed = true)
    private val privacySettingsRepository = mockk<PrivacySettingsRepository>(relaxed = true)

    // Default PrivacySettings() has exchangeRateDownloadEnabled = false (fresh install).
    private val privacySettings = MutableStateFlow(PrivacySettings())

    private val eur = CurrencyCode.EUR
    private val usd = CurrencyCode.parse("USD")!!

    /**
     * A REAL repository over a mocked DAO. `countExpensesWithoutHomeValue` takes the value class
     * [CurrencyCode], which mockk cannot record a stub for (and `any()` on it fabricates a value),
     * so the count is stubbed one level down on the DAO, matched by the bound home code.
     */
    private lateinit var expenseDao: ExpenseDao
    private lateinit var expenseRepository: ExpenseRepository

    /** Source behind `observeReadHomeCurrency()`; setup emits the resolved home currency (EUR). */
    private val homeSource = MutableSharedFlow<ReadHomeCurrencyState>(replay = 1, extraBufferCapacity = 16)

    private val logs = CopyOnWriteArrayList<String>()
    private val logTree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            logs += message
        }
    }

    private lateinit var hybridClassifier: HybridExpenseClassifier
    private lateinit var viewModel: CurrencyManagementViewModel

    @Before
    override fun setup() {
        super.setup()
        Timber.plant(logTree)

        expenseDao = mockk(relaxed = true)
        expenseRepository = ExpenseRepository(
            mockk(relaxed = true),
            mockk<AppDatabase>(relaxed = true),
            expenseDao,
            mockk<UserCorrectionDao>(relaxed = true),
            mockk<PendingReviewDao>(relaxed = true),
            mockk<MerchantCategoryRepository>(relaxed = true),
            mockk<MerchantNormalizer>(relaxed = true),
            mockk<TransferDirectionAnalytics>(relaxed = true),
            mockk<TransactionLifecycleCoordinator>(relaxed = true),
            mockk(relaxed = true)
        )
        homeSource.tryEmit(available(eur))
        every { settingsRepository.observeReadHomeCurrency() } returns homeSource
        every { settingsRepository.homeCurrency() } returns flowOf("EUR")
        coEvery { settingsRepository.resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        every { settingsRepository.lastRateUpdate() } returns flowOf(1_700_000_000_000L)
        coEvery { settingsRepository.areRatesStale(any()) } returns false
        every { currencyDataRepository.getRatesToCurrency("EUR") } returns flowOf(emptyList())
        every { privacySettingsRepository.observeSettings() } returns privacySettings
        coEvery { privacySettingsRepository.getSettings() } coAnswers { privacySettings.value }
        hybridClassifier = mockk(relaxed = true)

        viewModel = createViewModel()
    }

    @After
    override fun tearDown() {
        Timber.uproot(logTree)
        super.tearDown()
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

        viewModel.request("XXX")
        viewModel.request("HRK")
        advanceUntilIdle()

        coVerify(exactly = 0) { settingsRepository.setHomeCurrency(any()) }
        coVerify(exactly = 0) { hybridClassifier.invalidateCategorySnapshot() }
        coVerify(exactly = 0) { expenseDao.countExpensesWithoutHomeValueDynamic(any()) }
        assertEquals("UNSUPPORTED_HOME_CURRENCY", viewModel.uiState.value.error)
        assertEquals(
            HomeSwitchUi.Error(HomeSwitchError.UNSUPPORTED_HOME_CURRENCY),
            viewModel.uiState.value.homeSwitch
        )
    }

    @Test
    fun `valid lowercase home currency writes canonical code and invalidates only after confirm`() =
        runTest(testDispatcher) {
            advanceUntilIdle()

            viewModel.request("usd")
            advanceUntilIdle()

            // Requesting is not writing: nothing is persisted or invalidated before confirm.
            coVerify(exactly = 0) { settingsRepository.setHomeCurrency(any()) }
            coVerify(exactly = 0) { hybridClassifier.invalidateCategorySnapshot() }
            assertEquals(usd, (viewModel.uiState.value.homeSwitch as HomeSwitchUi.PendingConfirm).target)

            viewModel.confirm()
            advanceUntilIdle()

            coVerify(exactly = 1) { settingsRepository.setHomeCurrency("USD") }
            coVerify(exactly = 1) { hybridClassifier.invalidateCategorySnapshot() }
            assertEquals(HomeSwitchUi.Idle, viewModel.uiState.value.homeSwitch)
        }

    // ------------------------------------------------------------------
    // MC 1a-4 home-switch warning (AE-14)
    // ------------------------------------------------------------------

    @Test
    fun `request for a different supported code is pending with the predicted count and does not write`() =
        runTest(testDispatcher) {
            coEvery { expenseDao.countExpensesWithoutHomeValueDynamic(match { it.bindsHome("USD") }) } returns 3
            advanceUntilIdle()

            viewModel.request("USD")
            advanceUntilIdle()

            assertEquals(HomeSwitchUi.PendingConfirm(usd, 3), viewModel.uiState.value.homeSwitch)
            // The count uses the CANDIDATE home (USD), once, never the current one.
            coVerify(exactly = 1) { expenseDao.countExpensesWithoutHomeValueDynamic(match { it.bindsHome("USD") }) }
            coVerify(exactly = 0) { expenseDao.countExpensesWithoutHomeValueDynamic(match { it.bindsHome("EUR") }) }
            coVerify(exactly = 0) { settingsRepository.setHomeCurrency(any()) }
            coVerify(exactly = 0) { hybridClassifier.invalidateCategorySnapshot() }
        }

    @Test
    fun `zero predicted count is kept as zero`() = runTest(testDispatcher) {
        coEvery { expenseDao.countExpensesWithoutHomeValueDynamic(match { it.bindsHome("USD") }) } returns 0
        advanceUntilIdle()

        viewModel.request("USD")
        advanceUntilIdle()

        assertEquals(HomeSwitchUi.PendingConfirm(usd, 0), viewModel.uiState.value.homeSwitch)
    }

    @Test
    fun `request for the current home code shows no dialog and does not count or write`() = runTest(testDispatcher) {
        advanceUntilIdle()

        viewModel.request("EUR")
        viewModel.request("eur")
        advanceUntilIdle()

        assertEquals(HomeSwitchUi.Idle, viewModel.uiState.value.homeSwitch)
        assertNull(viewModel.uiState.value.error)
        coVerify(exactly = 0) { expenseDao.countExpensesWithoutHomeValueDynamic(any()) }
        coVerify(exactly = 0) { settingsRepository.setHomeCurrency(any()) }
        coVerify(exactly = 0) { hybridClassifier.invalidateCategorySnapshot() }
    }

    @Test
    fun `cancel returns to idle without writing and confirm afterwards does nothing`() = runTest(testDispatcher) {
        advanceUntilIdle()
        viewModel.request("USD")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.homeSwitch is HomeSwitchUi.PendingConfirm)

        viewModel.cancel()
        viewModel.confirm()
        advanceUntilIdle()

        assertEquals(HomeSwitchUi.Idle, viewModel.uiState.value.homeSwitch)
        coVerify(exactly = 0) { settingsRepository.setHomeCurrency(any()) }
        coVerify(exactly = 0) { hybridClassifier.invalidateCategorySnapshot() }
    }

    @Test
    fun `confirm without a pending request does nothing`() = runTest(testDispatcher) {
        advanceUntilIdle()

        viewModel.confirm()
        advanceUntilIdle()

        assertEquals(HomeSwitchUi.Idle, viewModel.uiState.value.homeSwitch)
        coVerify(exactly = 0) { settingsRepository.setHomeCurrency(any()) }
    }

    @Test
    fun `second confirm and new requests while writing are ignored and exactly one write happens`() =
        runTest(testDispatcher) {
            val gate = CompletableDeferred<Unit>()
            coEvery { settingsRepository.setHomeCurrency("USD") } coAnswers { gate.await() }
            advanceUntilIdle()
            viewModel.request("USD")
            advanceUntilIdle()

            viewModel.confirm()
            advanceUntilIdle()
            assertEquals(HomeSwitchUi.Writing, viewModel.uiState.value.homeSwitch)

            viewModel.confirm()
            viewModel.request("GBP")
            viewModel.cancel()
            advanceUntilIdle()
            assertEquals(HomeSwitchUi.Writing, viewModel.uiState.value.homeSwitch)
            coVerify(exactly = 1) { settingsRepository.setHomeCurrency(any()) }
            coVerify(exactly = 0) { hybridClassifier.invalidateCategorySnapshot() }

            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(HomeSwitchUi.Idle, viewModel.uiState.value.homeSwitch)
            coVerify(exactly = 1) { settingsRepository.setHomeCurrency(any()) }
            coVerify(exactly = 1) { hybridClassifier.invalidateCategorySnapshot() }
        }

    @Test
    fun `home currency Loading shows the warning, writes nothing until confirm, and confirm writes once`() =
        runTest(testDispatcher) {
            coEvery { expenseDao.countExpensesWithoutHomeValueDynamic(match { it.bindsHome("USD") }) } returns 4
            every { settingsRepository.observeReadHomeCurrency() } returns MutableSharedFlow<ReadHomeCurrencyState>()
            viewModel = createViewModel()
            runCurrent() // not advanceUntilIdle: that would run the 5 s read timeout

            assertEquals(ReadHomeCurrencyState.Loading, viewModel.uiState.value.homeState)
            assertNull(viewModel.uiState.value.homeCurrency)
            // The static catalog does not depend on the home state.
            assertEquals(
                SupportedCurrency.activeCatalog.map { it.code },
                viewModel.uiState.value.supportedCurrencies.map { it.code }
            )

            // An unsupported code still errors, with no dialog and no write.
            viewModel.request("XXX")
            runCurrent()
            assertEquals(
                HomeSwitchUi.Error(HomeSwitchError.UNSUPPORTED_HOME_CURRENCY),
                viewModel.uiState.value.homeSwitch
            )
            assertEquals("UNSUPPORTED_HOME_CURRENCY", viewModel.uiState.value.error)
            viewModel.clearError()

            viewModel.request("USD")
            runCurrent()

            assertEquals(HomeSwitchUi.PendingConfirm(usd, 4), viewModel.uiState.value.homeSwitch)
            coVerify(exactly = 0) { settingsRepository.setHomeCurrency(any()) }
            coVerify(exactly = 0) { hybridClassifier.invalidateCategorySnapshot() }

            viewModel.confirm()
            runCurrent()

            coVerify(exactly = 1) { settingsRepository.setHomeCurrency("USD") }
            coVerify(exactly = 1) { hybridClassifier.invalidateCategorySnapshot() }
            assertEquals(HomeSwitchUi.Idle, viewModel.uiState.value.homeSwitch)
        }

    @Test
    fun `home currency Unavailable shows the warning, writes nothing until confirm, for every failure`() =
        runTest(testDispatcher) {
            for ((index, failure) in ReadHomeFailure.values().withIndex()) {
                every { settingsRepository.observeReadHomeCurrency() } returns
                    flowOf(ReadHomeCurrencyState.Unavailable(failure))
                viewModel = createViewModel()
                advanceUntilIdle()

                assertEquals(ReadHomeCurrencyState.Unavailable(failure), viewModel.uiState.value.homeState)
                assertNull(viewModel.uiState.value.homeCurrency)
                assertEquals(
                    SupportedCurrency.activeCatalog.map { it.code },
                    viewModel.uiState.value.supportedCurrencies.map { it.code }
                )

                // No home is known, so even the EUR default is not a "same code" no-op.
                viewModel.request("EUR")
                advanceUntilIdle()
                assertEquals(
                    eur,
                    (viewModel.uiState.value.homeSwitch as HomeSwitchUi.PendingConfirm).target
                )

                viewModel.request("USD")
                advanceUntilIdle()
                assertEquals(
                    usd,
                    (viewModel.uiState.value.homeSwitch as HomeSwitchUi.PendingConfirm).target
                )
                assertNull(viewModel.uiState.value.error)
                // Writes so far: one per previously completed iteration; none for this request.
                coVerify(exactly = index) { settingsRepository.setHomeCurrency(any()) }

                viewModel.confirm()
                advanceUntilIdle()

                coVerify(exactly = index + 1) { settingsRepository.setHomeCurrency(any()) }
                assertEquals(HomeSwitchUi.Idle, viewModel.uiState.value.homeSwitch)

                // An unsupported code still errors with no dialog and no further write.
                viewModel.request("XXX")
                advanceUntilIdle()
                assertEquals(
                    HomeSwitchUi.Error(HomeSwitchError.UNSUPPORTED_HOME_CURRENCY),
                    viewModel.uiState.value.homeSwitch
                )
                coVerify(exactly = index + 1) { settingsRepository.setHomeCurrency(any()) }
            }
            coVerify(exactly = ReadHomeFailure.values().size) { hybridClassifier.invalidateCategorySnapshot() }
        }

    @Test
    fun `dialog opens immediately without a count and a slow count fills the line in later`() =
        runTest(testDispatcher) {
            val gate = CompletableDeferred<Unit>()
            coEvery { expenseDao.countExpensesWithoutHomeValueDynamic(match { it.bindsHome("USD") }) } coAnswers {
                gate.await()
                5
            }
            advanceUntilIdle()

            viewModel.request("USD")
            runCurrent()

            // The count is still outstanding, yet the dialog is already open (static text only).
            assertEquals(HomeSwitchUi.PendingConfirm(usd, null), viewModel.uiState.value.homeSwitch)

            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(HomeSwitchUi.PendingConfirm(usd, 5), viewModel.uiState.value.homeSwitch)
            coVerify(exactly = 0) { settingsRepository.setHomeCurrency(any()) }
        }

    @Test
    fun `a late count never reopens a cancelled dialog or overwrites a newer target`() =
        runTest(testDispatcher) {
            val usdGate = CompletableDeferred<Unit>()
            coEvery { expenseDao.countExpensesWithoutHomeValueDynamic(match { it.bindsHome("USD") }) } coAnswers {
                usdGate.await()
                5
            }
            coEvery { expenseDao.countExpensesWithoutHomeValueDynamic(match { it.bindsHome("GBP") }) } returns 2
            advanceUntilIdle()

            viewModel.request("USD")
            runCurrent()
            viewModel.request("GBP")
            advanceUntilIdle()
            usdGate.complete(Unit)
            advanceUntilIdle()

            val gbp = CurrencyCode.parse("GBP")!!
            assertEquals(HomeSwitchUi.PendingConfirm(gbp, 2), viewModel.uiState.value.homeSwitch)

            viewModel.cancel()
            advanceUntilIdle()
            assertEquals(HomeSwitchUi.Idle, viewModel.uiState.value.homeSwitch)
        }

    @Test
    fun `home becoming Unavailable keeps the pending dialog and confirm still writes once`() =
        runTest(testDispatcher) {
            advanceUntilIdle()
            viewModel.request("USD")
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.homeSwitch is HomeSwitchUi.PendingConfirm)

            homeSource.tryEmit(ReadHomeCurrencyState.Unavailable(ReadHomeFailure.READ_ERROR))
            advanceUntilIdle()

            assertEquals(
                ReadHomeCurrencyState.Unavailable(ReadHomeFailure.READ_ERROR),
                viewModel.uiState.value.homeState
            )
            assertEquals(usd, (viewModel.uiState.value.homeSwitch as HomeSwitchUi.PendingConfirm).target)
            coVerify(exactly = 0) { settingsRepository.setHomeCurrency(any()) }

            viewModel.confirm()
            advanceUntilIdle()

            coVerify(exactly = 1) { settingsRepository.setHomeCurrency("USD") }
            assertEquals(HomeSwitchUi.Idle, viewModel.uiState.value.homeSwitch)
            // Not Available, so no reload is under way: the loading flag must not be left raised.
            assertFalse(viewModel.uiState.value.isLoading)
        }

    @Test
    fun `no typed emission within 5 s becomes Unavailable TIMEOUT and a late value recovers`() =
        runTest(testDispatcher) {
            val source = MutableSharedFlow<ReadHomeCurrencyState>(replay = 1)
            every { settingsRepository.observeReadHomeCurrency() } returns source
            viewModel = createViewModel()

            advanceTimeBy(4_999)
            runCurrent()
            assertEquals(ReadHomeCurrencyState.Loading, viewModel.uiState.value.homeState)

            advanceTimeBy(2)
            runCurrent()
            assertEquals(
                ReadHomeCurrencyState.Unavailable(ReadHomeFailure.TIMEOUT),
                viewModel.uiState.value.homeState
            )
            // Unavailable(TIMEOUT) still shows the warning (INV-12); nothing is written.
            viewModel.request("USD")
            runCurrent()
            assertEquals(usd, (viewModel.uiState.value.homeSwitch as HomeSwitchUi.PendingConfirm).target)
            coVerify(exactly = 0) { settingsRepository.setHomeCurrency(any()) }

            source.tryEmit(available(eur))
            runCurrent()
            assertEquals(available(eur), viewModel.uiState.value.homeState)
            assertEquals("EUR", viewModel.uiState.value.homeCurrency)
            // The recovered home (EUR) differs from the pending target, so the dialog stays.
            assertEquals(usd, (viewModel.uiState.value.homeSwitch as HomeSwitchUi.PendingConfirm).target)
        }

    @Test
    fun `a failing home read maps to Unavailable READ_ERROR without exposing the error text`() =
        runTest(testDispatcher) {
            every { settingsRepository.observeReadHomeCurrency() } returns
                flow<ReadHomeCurrencyState> { throw IllegalStateException("secret /data/user/0/prefs") }
            viewModel = createViewModel()
            advanceUntilIdle()

            assertEquals(
                ReadHomeCurrencyState.Unavailable(ReadHomeFailure.READ_ERROR),
                viewModel.uiState.value.homeState
            )
            assertTrue(logs.none { it.contains("secret") || it.contains("/data") })
        }

    @Test
    fun `refresh while the home currency is not available is unavailable and never uses a placeholder`() =
        runTest(testDispatcher) {
            every { settingsRepository.observeReadHomeCurrency() } returns MutableSharedFlow<ReadHomeCurrencyState>()
            viewModel = createViewModel()
            runCurrent()

            viewModel.refreshRates()
            runCurrent()

            assertEquals("CURRENCY_RATE_REFRESH_UNAVAILABLE", viewModel.uiState.value.error)
            assertFalse(viewModel.uiState.value.isLoading)
            coVerify(exactly = 0) { currencyRatesRepository.refresh(any()) }
        }

    @Test
    fun `home flipping to the pending target drops the stale dialog`() = runTest(testDispatcher) {
        every { currencyDataRepository.getRatesToCurrency("USD") } returns flowOf(emptyList())
        advanceUntilIdle()
        viewModel.request("USD")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.homeSwitch is HomeSwitchUi.PendingConfirm)

        homeSource.tryEmit(available(usd))
        advanceUntilIdle()

        assertEquals(HomeSwitchUi.Idle, viewModel.uiState.value.homeSwitch)
        coVerify(exactly = 0) { settingsRepository.setHomeCurrency(any()) }
    }

    @Test
    fun `write failure is a controlled error with no invalidation and no leaked text, and can be retried`() =
        runTest(testDispatcher) {
            var attempts = 0
            coEvery { settingsRepository.setHomeCurrency("USD") } coAnswers {
                if (attempts++ == 0) throw IllegalStateException("datastore failure at /data/user/0/secret")
            }
            advanceUntilIdle()
            viewModel.request("USD")
            advanceUntilIdle()

            viewModel.confirm()
            advanceUntilIdle()

            assertEquals(HomeSwitchUi.Error(HomeSwitchError.WRITE_FAILED), viewModel.uiState.value.homeSwitch)
            assertEquals("HOME_SWITCH_WRITE_FAILED", viewModel.uiState.value.error)
            assertFalse(viewModel.uiState.value.isLoading)
            coVerify(exactly = 0) { hybridClassifier.invalidateCategorySnapshot() }
            assertTrue(logs.none { it.contains("secret") || it.contains("/data") || it.contains("datastore") })

            viewModel.clearError()
            assertEquals(HomeSwitchUi.Idle, viewModel.uiState.value.homeSwitch)
            assertNull(viewModel.uiState.value.error)

            viewModel.request("USD")
            advanceUntilIdle()
            viewModel.confirm()
            advanceUntilIdle()

            coVerify(exactly = 2) { settingsRepository.setHomeCurrency("USD") }
            coVerify(exactly = 1) { hybridClassifier.invalidateCategorySnapshot() }
            assertEquals(HomeSwitchUi.Idle, viewModel.uiState.value.homeSwitch)
        }

    @Test
    fun `CancellationException from the write is rethrown, not reported as a write failure`() =
        runTest(testDispatcher) {
            coEvery { settingsRepository.setHomeCurrency("USD") } coAnswers {
                throw CancellationException("cancelled")
            }
            advanceUntilIdle()
            viewModel.request("USD")
            advanceUntilIdle()

            viewModel.confirm()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state.homeSwitch !is HomeSwitchUi.Error)
            assertEquals(HomeSwitchUi.Idle, state.homeSwitch)
            assertNull(state.error)
            coVerify(exactly = 0) { hybridClassifier.invalidateCategorySnapshot() }
        }

    @Test
    fun `count failure omits the count, keeps the dialog and logs only a reason code`() = runTest(testDispatcher) {
        coEvery { expenseDao.countExpensesWithoutHomeValueDynamic(match { it.bindsHome("USD") }) } throws
            IllegalStateException("SELECT COUNT(*) FROM expenses secret-table")
        advanceUntilIdle()

        viewModel.request("USD")
        advanceUntilIdle()

        assertEquals(HomeSwitchUi.PendingConfirm(usd, null), viewModel.uiState.value.homeSwitch)
        assertTrue(logs.any { it.contains("HOME_SWITCH_COUNT_FAILED") })
        assertTrue(logs.none { it.contains("SELECT") || it.contains("secret") })
        coVerify(exactly = 0) { settingsRepository.setHomeCurrency(any()) }
    }

    @Test
    fun `CancellationException from the count is rethrown and never logged as a count failure`() = runTest(testDispatcher) {
        coEvery { expenseDao.countExpensesWithoutHomeValueDynamic(match { it.bindsHome("USD") }) } throws
            CancellationException("cancelled")
        advanceUntilIdle()

        viewModel.request("USD")
        advanceUntilIdle()

        // The dialog opened immediately with no count; the cancelled count is rethrown (the job
        // just ends), not swallowed as a failure, so no count-failure reason code is logged.
        assertEquals(HomeSwitchUi.PendingConfirm(usd, null), viewModel.uiState.value.homeSwitch)
        assertTrue(logs.none { it.contains("HOME_SWITCH_COUNT_FAILED") })
        assertNull(viewModel.uiState.value.error)
        coVerify(exactly = 0) { settingsRepository.setHomeCurrency(any()) }
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
        coEvery { currencyRatesRepository.refresh("EUR") } returns CurrencyRateRefreshOutcome.Refreshed(2)

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

    @Test
    fun `privacy disabled refresh sets download disabled error and is not offline`() = runTest(testDispatcher) {
        coEvery { currencyRatesRepository.refresh("EUR") } returns CurrencyRateRefreshOutcome.DisabledByPrivacy
        advanceUntilIdle()
        // Fresh install: switch off and no rates. That is "download disabled", never "offline".
        assertTrue(viewModel.uiState.value.rateDownloadDisabled)
        assertFalse(viewModel.uiState.value.isOffline)

        viewModel.refreshRates()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("CURRENCY_RATE_DOWNLOAD_DISABLED", state.error)
        assertTrue(state.rateDownloadDisabled)
        assertFalse(state.isOffline)
        assertFalse(state.isLoading)
    }

    @Test
    fun `refresh outcome never writes the download disabled flag - settings flow is the only writer`() = runTest(testDispatcher) {
        privacySettings.value = PrivacySettings(exchangeRateDownloadEnabled = true)
        coEvery { currencyRatesRepository.refresh("EUR") } returns CurrencyRateRefreshOutcome.DisabledByPrivacy
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.rateDownloadDisabled)

        viewModel.refreshRates()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("CURRENCY_RATE_DOWNLOAD_DISABLED", state.error)
        // Flag still mirrors the settings flow (switch on); only the flow may flip it.
        assertFalse(state.rateDownloadDisabled)
        assertFalse(state.isOffline)

        privacySettings.value = PrivacySettings(exchangeRateDownloadEnabled = false)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.rateDownloadDisabled)
    }

    @Test
    fun `clearing error keeps download disabled flag while switch is off`() = runTest(testDispatcher) {
        coEvery { currencyRatesRepository.refresh("EUR") } returns CurrencyRateRefreshOutcome.DisabledByPrivacy
        advanceUntilIdle()
        viewModel.refreshRates()
        advanceUntilIdle()

        viewModel.clearError()

        assertEquals(null, viewModel.uiState.value.error)
        assertTrue(viewModel.uiState.value.rateDownloadDisabled)
        assertFalse(viewModel.uiState.value.isOffline)
    }

    @Test
    fun `switch on with no rates is genuinely offline`() = runTest(testDispatcher) {
        privacySettings.value = PrivacySettings(exchangeRateDownloadEnabled = true)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.rateDownloadDisabled)
        assertTrue(state.isOffline)
    }

    @Test
    fun `toggling the privacy switch updates download disabled and offline flags`() = runTest(testDispatcher) {
        privacySettings.value = PrivacySettings(exchangeRateDownloadEnabled = true)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.rateDownloadDisabled)
        assertTrue(viewModel.uiState.value.isOffline)

        privacySettings.value = PrivacySettings(exchangeRateDownloadEnabled = false)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.rateDownloadDisabled)
        assertFalse(viewModel.uiState.value.isOffline)

        privacySettings.value = PrivacySettings(exchangeRateDownloadEnabled = true)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.rateDownloadDisabled)
        assertTrue(viewModel.uiState.value.isOffline)
    }

    @Test
    fun `zero stored pairs maps to generic refresh unavailable error`() = runTest(testDispatcher) {
        coEvery { currencyRatesRepository.refresh("EUR") } returns CurrencyRateRefreshOutcome.Refreshed(0)
        privacySettings.value = PrivacySettings(exchangeRateDownloadEnabled = true)
        advanceUntilIdle()

        viewModel.refreshRates()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("CURRENCY_RATE_REFRESH_UNAVAILABLE", state.error)
        assertFalse(state.rateDownloadDisabled)
    }

    @Test
    fun `privacy check failure maps to generic refresh unavailable error`() = runTest(testDispatcher) {
        coEvery { currencyRatesRepository.refresh("EUR") } returns
            CurrencyRateRefreshOutcome.PrivacyCheckFailed("PRIVACY_BLOCKED")
        advanceUntilIdle()

        viewModel.refreshRates()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("CURRENCY_RATE_REFRESH_UNAVAILABLE", state.error)
        assertFalse(state.isLoading)
        // Switch is off in this setup: the privacy-check failure must not be presented as offline.
        assertTrue(state.rateDownloadDisabled)
        assertFalse(state.isOffline)
    }

    @Test
    fun `privacy check failure does not mark offline when switch is on and rates exist`() = runTest(testDispatcher) {
        val rates = listOf(
            ExchangeRate(fromCurrency = "EUR", toCurrency = "USD", rate = 1.1, lastUpdated = 1_700_000_100_000L)
        )
        every { currencyDataRepository.getRatesToCurrency("EUR") } returns flowOf(rates)
        privacySettings.value = PrivacySettings(exchangeRateDownloadEnabled = true)
        coEvery { currencyRatesRepository.refresh("EUR") } returns
            CurrencyRateRefreshOutcome.PrivacyCheckFailed("PRIVACY_BLOCKED")
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.refreshRates()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("CURRENCY_RATE_REFRESH_UNAVAILABLE", state.error)
        assertFalse(state.rateDownloadDisabled)
        assertFalse(state.isOffline)
    }

    private fun createViewModel(): CurrencyManagementViewModel {
        return CurrencyManagementViewModel(
            currencyDataRepository = currencyDataRepository,
            currencyConverter = currencyConverter,
            currencyRatesRepository = currencyRatesRepository,
            settingsRepository = settingsRepository,
            hybridClassifier = hybridClassifier,
            privacySettingsRepository = privacySettingsRepository,
            expenseRepository = expenseRepository
        )
    }

    private fun available(code: CurrencyCode, firstRunDefault: Boolean = false) =
        ReadHomeCurrencyState.Available(code, firstRunDefault)

    private class RecordingProgram : SupportSQLiteProgram {
        val bound = sortedMapOf<Int, Any?>()
        override fun bindNull(index: Int) { bound[index] = null }
        override fun bindLong(index: Int, value: Long) { bound[index] = value }
        override fun bindDouble(index: Int, value: Double) { bound[index] = value }
        override fun bindString(index: Int, value: String) { bound[index] = value }
        override fun bindBlob(index: Int, value: ByteArray) { bound[index] = value }
        override fun clearBindings() { bound.clear() }
        override fun close() = Unit
    }

    /**
     * True when the count query binds [homeCode] as its home currency. The empty filter binds no
     * other string, so the home code is the only string argument.
     */
    private fun SupportSQLiteQuery.bindsHome(homeCode: String): Boolean {
        val program = RecordingProgram()
        bindTo(program)
        return program.bound.values.any { it == homeCode }
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
