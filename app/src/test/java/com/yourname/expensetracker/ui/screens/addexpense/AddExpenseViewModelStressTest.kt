package com.yourname.expensetracker.ui.screens.addexpense

import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.ManualExpenseRepository
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencyRateRefreshOutcome
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.DomainExchangeRate
import com.yourname.expensetracker.domain.currency.RateEligibilityEvaluator
import com.yourname.expensetracker.domain.currency.RateSourceLabel
import com.yourname.expensetracker.domain.currency.UserPresentRatePreview
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.privacy.PrivacySettingsLoadState
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.quickadd.QuickAddLearnedCategoryLookup
import com.yourname.expensetracker.domain.quickadd.QuickAddParser
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.ui.components.currency.CurrencyBlockOrigin
import com.yourname.expensetracker.ui.components.currency.CurrencyBlockVariant
import com.yourname.expensetracker.ui.components.currency.CurrencyRefreshStatus
import com.yourname.expensetracker.ui.components.currency.CurrencyRegionUi
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale
import java.util.Random
import java.util.TimeZone

/**
 * manual-entry-currency B2a stress: rapid currency and date switching on the add-expense VM
 * (INV-3: the latest input wins; a stale result is never shown). Real preview use case over an
 * in-memory rate store with seeded random latency on the virtual clock. Synthetic rates only.
 *
 * The expected results come from a hand-written table of the fixtures below, not from production.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddExpenseViewModelStressTest : ViewModelTestUtils() {

    private val athens: ZoneId = ZoneId.of("Europe/Athens")
    private val originalTimeZone: TimeZone = TimeZone.getDefault()
    private val originalLocale: Locale = Locale.getDefault()

    private val rateStore = AddExpenseRateStoreFake()
    private val ratesRepo = AddExpenseRatesRepositoryFake()
    private val privacyFlow = MutableStateFlow<PrivacySettingsLoadState>(
        PrivacySettingsLoadState.Loaded(PrivacySettings())
    )
    private val privacyRepo = mockk<PrivacySettingsRepository>()
    private val timeProvider = mockk<TimeProvider>(relaxed = true)

    private lateinit var viewModel: AddExpenseViewModel

    private val st: AddExpenseState get() = viewModel.state.value
    private val region: CurrencyRegionUi get() = st.currencyRegion

    @Before
    override fun setup() {
        super.setup()
        TimeZone.setDefault(TimeZone.getTimeZone(athens))
        Locale.setDefault(Locale.US)
        every { timeProvider.now() } returns at(2025, 10, 16, 14, 30)
        every { privacyRepo.observeLoadState() } returns privacyFlow
    }

    @After
    override fun tearDown() {
        try {
            TimeZone.setDefault(originalTimeZone)
            Locale.setDefault(originalLocale)
        } finally {
            super.tearDown()
        }
    }

    private fun createViewModel(): AddExpenseViewModel {
        val categoryRepository = mockk<CategoryRepository>(relaxed = true)
        every { categoryRepository.allCategories } returns flowOf(emptyList())
        val homeRepo = mockk<CurrencySettingsRepository>(relaxed = true)
        every { homeRepo.homeCurrency() } returns flowOf("EUR")
        return AddExpenseViewModel(
            manualExpenseRepository = mockk<ManualExpenseRepository>(relaxed = true),
            expenseRepository = mockk<ExpenseRepository>(relaxed = true),
            categoryRepository = categoryRepository,
            timeProvider = timeProvider,
            currencySettingsRepository = homeRepo,
            quickAddParser = QuickAddParser(),
            quickAddLearnedCategoryLookup = mockk<QuickAddLearnedCategoryLookup>(relaxed = true),
            userPresentRatePreview = UserPresentRatePreview(
                RateEligibilityEvaluator(CurrencyConverter(rateStore, timeProvider, testDispatcher)),
                rateStore
            ),
            currencyRatesRepository = ratesRepo,
            privacySettingsRepository = privacyRepo,
            installmentPlanCoordinator = mockk(relaxed = true),
            installmentPostCommitDispatcher = mockk(relaxed = true)
        )
    }

    private fun open() {
        viewModel = createViewModel()
        settle()
        viewModel.onSheetOpened()
        settle()
    }

    private fun settle() = testDispatcher.scheduler.advanceUntilIdle()

    private fun runNow() = testDispatcher.scheduler.runCurrent()

    private fun advance(ms: Long) {
        testDispatcher.scheduler.advanceTimeBy(ms)
        testDispatcher.scheduler.runCurrent()
    }

    private fun at(y: Int, mo: Int, d: Int, h: Int = 12, mi: Int = 0): Long =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, athens).toInstant().toEpochMilli()

    private fun oct(day: Int): LocalDate = LocalDate.of(2025, 10, day)

    private fun put(from: String, to: String, rate: Double, date: LocalDate) {
        rateStore.rows += DomainExchangeRate(
            fromCurrency = from,
            toCurrency = to,
            rate = rate,
            lastUpdated = 0L,
            source = "ecb",
            validDate = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
    }

    // Fixtures (home EUR, amount 20.00): USD has rates on 15 and 16 Oct, GBP only on 15 Oct, JPY none.
    private fun seedRates() {
        put("USD", "EUR", 0.5, oct(15))
        put("USD", "EUR", 0.25, oct(16))
        put("GBP", "EUR", 2.0, oct(15))
    }

    private val d1 = at(2025, 10, 16, 9, 0)
    private val d2 = at(2025, 10, 15, 12, 0)
    private val d3 = at(2025, 10, 6, 12, 0)

    private fun eligible(rateDate: LocalDate, homeAmount: Double) = CurrencyRegionUi.Eligible(
        rateDate,
        RateSourceLabel.ECB,
        CurrencyFormatter.formatMoney(homeAmount, "EUR"),
        amountUnavailable = false
    )

    private val none = CurrencyRegionUi.Blocked(CurrencyBlockVariant.NONE, null, CurrencyBlockOrigin.PREVIEW)

    /** Hand-written expectation for the 20.00 amount. */
    private fun expected(currency: String, date: Long): CurrencyRegionUi = when (currency) {
        "EUR" -> CurrencyRegionUi.Home
        "USD" -> when (date) {
            d1 -> eligible(oct(16), 5.0)
            d2 -> eligible(oct(15), 10.0)
            else -> none
        }
        "GBP" -> when (date) {
            d1, d2 -> eligible(oct(15), 40.0)
            else -> none
        }
        else -> none
    }

    private fun assertNeverStale(currency: String, date: Long, step: Int) {
        val shown = region
        if (shown is CurrencyRegionUi.Eligible || shown is CurrencyRegionUi.Blocked || shown is CurrencyRegionUi.Home) {
            assertEquals("step $step $currency", expected(currency, date), shown)
        } else {
            assertTrue(
                "step $step: unexpected $shown",
                shown == CurrencyRegionUi.Pending || shown == CurrencyRegionUi.Evaluating
            )
        }
    }

    @Test
    fun `rapid currency and date switching never shows a stale result and ends on the latest input`() {
        seedRates()
        val rng = Random(20251029L)
        rateStore.latencyMs = { _, _, _ -> rng.nextInt(700).toLong() }
        open()
        viewModel.updateAmount("20.00")
        viewModel.updateDate(d1)
        settle()

        var currency = "EUR"
        var date = d1
        val currencies = listOf("EUR", "USD", "GBP", "JPY")
        val dates = listOf(d1, d2, d3)

        for (step in 1..250) {
            when (rng.nextInt(7)) {
                0, 1, 2 -> {
                    currency = currencies[rng.nextInt(currencies.size)]
                    viewModel.selectCurrency(currency)
                    // The changed currency drops the shown result immediately.
                    val immediate = region
                    if (currency == "EUR") {
                        assertEquals("step $step", CurrencyRegionUi.Home, immediate)
                    } else {
                        assertEquals("step $step", CurrencyRegionUi.Pending, immediate)
                    }
                }
                3, 4 -> {
                    date = dates[rng.nextInt(dates.size)]
                    viewModel.updateDate(date)
                    if (currency != "EUR") {
                        assertEquals("step $step", CurrencyRegionUi.Pending, region)
                    }
                }
                5 -> viewModel.updateAmount("20.00") // same figure: drops to Pending, then re-evaluates
                else -> Unit
            }
            advance(rng.nextInt(400).toLong())
            assertNeverStale(currency, date, step)
        }

        settle()
        assertEquals(currency, st.selectedCurrency)
        assertEquals(expected(currency, date), region)
        assertFalse(st.isCurrencySaveBlocked && expected(currency, date) is CurrencyRegionUi.Eligible)
        assertTrue("no fetch during previews", ratesRepo.calls.isEmpty())
    }

    @Test
    fun `switching currency restarts the 250 ms Checking clock`() {
        seedRates()
        rateStore.latencyMs = { _, _, _ -> 1_000L }
        open()
        viewModel.updateDate(d2)
        viewModel.updateAmount("20.00")
        settle()

        viewModel.selectCurrency("USD")
        runNow()
        advance(200)
        assertEquals(CurrencyRegionUi.Pending, region)

        viewModel.selectCurrency("GBP")
        assertEquals(CurrencyRegionUi.Pending, region)
        runNow()
        advance(200)
        assertEquals("the old 250 ms marker must not survive the switch", CurrencyRegionUi.Pending, region)
        advance(50)
        assertEquals(CurrencyRegionUi.Evaluating, region)
        advance(750)
        assertEquals(eligible(oct(15), 40.0), region)
        advance(5_000)
        assertEquals(eligible(oct(15), 40.0), region)
    }

    @Test
    fun `reset during a burst of slow switches leaves the home state and no late result`() {
        seedRates()
        rateStore.latencyMs = { _, _, _ -> 2_000L }
        open()
        viewModel.updateAmount("20.00")
        viewModel.updateDate(d2)
        for (code in listOf("USD", "GBP", "USD", "JPY", "GBP")) {
            viewModel.selectCurrency(code)
            advance(100)
        }

        viewModel.reset()
        advance(10_000)
        settle()

        assertEquals("EUR", st.selectedCurrency)
        assertEquals(CurrencyRegionUi.Home, region)
        assertEquals("", st.amount)
    }

    @Test
    fun `a burst of refresh taps starts exactly one refresh and its single result is applied`() {
        privacyFlow.value = PrivacySettingsLoadState.Loaded(PrivacySettings(exchangeRateDownloadEnabled = true))
        ratesRepo.outcome = {
            delay(500)
            put("USD", "EUR", 0.5, oct(15))
            CurrencyRateRefreshOutcome.Refreshed(1)
        }
        open()
        viewModel.updateDate(d2)
        viewModel.selectCurrency("USD")
        viewModel.updateAmount("20.00")
        settle()
        assertEquals(none, region)

        repeat(10) {
            viewModel.refreshRates()
            advance(10)
        }
        assertEquals(CurrencyRefreshStatus.RUNNING, st.rateRefresh)
        settle()

        assertEquals(listOf("EUR"), ratesRepo.calls)
        assertEquals(CurrencyRefreshStatus.IDLE, st.rateRefresh)
        assertEquals(eligible(oct(15), 10.0), region)
    }

    @Test
    fun `rapid open-setting requests keep one pending request and never touch the setting`() {
        open()
        viewModel.updateAmount("5.00")

        repeat(20) {
            viewModel.requestOpenExchangeRateSetting()
            viewModel.confirmOpenExchangeRateSetting()
        }
        viewModel.consumeOpenSettingRequest()
        viewModel.consumeOpenSettingRequest()

        assertEquals(
            com.yourname.expensetracker.ui.components.currency.CurrencyOpenSettingRequest.NONE,
            st.openSettingRequest
        )
        // the first confirmed request discards the draft (C-1); later requests navigate at once
        assertEquals("", st.amount)
    }
}
