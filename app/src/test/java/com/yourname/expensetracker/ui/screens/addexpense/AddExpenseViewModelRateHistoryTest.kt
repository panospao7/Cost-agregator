package com.yourname.expensetracker.ui.screens.addexpense

import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.ManualExpenseRepository
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencyRateHistoryOutcome
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.DomainExchangeRate
import com.yourname.expensetracker.domain.currency.RateEligibilityEvaluator
import com.yourname.expensetracker.domain.currency.RateHistoryFailureReason
import com.yourname.expensetracker.domain.currency.UserPresentRatePreview
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPlanLifecycleCoordinator
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPostCommitDispatcher
import com.yourname.expensetracker.domain.model.Result
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.privacy.PrivacySettingsLoadState
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.quickadd.LearnedCategoryLookupResult
import com.yourname.expensetracker.domain.quickadd.QuickAddLearnedCategoryLookup
import com.yourname.expensetracker.domain.quickadd.QuickAddParser
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.ui.components.currency.CurrencyHistoryStatus
import com.yourname.expensetracker.ui.components.currency.CurrencyRateFixAction
import com.yourname.expensetracker.ui.components.currency.CurrencyRefreshStatus
import com.yourname.expensetracker.ui.components.currency.CurrencyRatesSetting
import com.yourname.expensetracker.ui.components.currency.CurrencyRegionUi
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CompletableDeferred
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

/**
 * manual-entry-currency 1b S2: the add-expense view model side of the user-started "Download rate
 * history" action. Oracle: domain-spec "## 1b" (AE-1b-1, 3, 4, 5/5d, 6b, 15, 24, 25) and ux-flows "## 1b".
 *
 * The preview use case, evaluator and converter are the real classes over an in-memory rate store; the
 * rates repository is the counting fake with a scripted history outcome. Synthetic rates only.
 * Default context: home EUR, Europe/Athens, now Thu 2025-10-16 14:30, USD entry dated Fri 2025-10-10
 * 12:00 with only a newer (2025-10-14) stored USD->EUR row, so the date is inside the 90-day window
 * and a history download can help.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddExpenseViewModelRateHistoryTest : ViewModelTestUtils() {

    private val manualExpenseRepository = mockk<ManualExpenseRepository>(relaxed = true)
    private val expenseRepository = mockk<ExpenseRepository>(relaxed = true)
    private val categoryRepository = mockk<CategoryRepository>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private val quickAddLearnedCategoryLookup = mockk<QuickAddLearnedCategoryLookup>()
    private val installmentCoordinator = mockk<InstallmentPlanLifecycleCoordinator>(relaxed = true)
    private val installmentDispatcher = mockk<InstallmentPostCommitDispatcher>(relaxed = true)
    private val privacyRepo = mockk<PrivacySettingsRepository>()

    private val rateStore = AddExpenseRateStoreFake()
    private val ratesRepo = AddExpenseRatesRepositoryFake()
    private val privacyFlow = MutableStateFlow<PrivacySettingsLoadState>(
        PrivacySettingsLoadState.Loaded(PrivacySettings())
    )

    private lateinit var viewModel: AddExpenseViewModel
    private val athens = ZoneId.of("Europe/Athens")
    private val originalLocale: Locale = Locale.getDefault()
    private val originalTimeZone: TimeZone = TimeZone.getDefault()
    private val ratesOn = PrivacySettingsLoadState.Loaded(PrivacySettings(exchangeRateDownloadEnabled = true))

    @Before
    override fun setup() {
        super.setup()
        every { timeProvider.now() } returns at(2025, 10, 16, 14, 30)
        every { categoryRepository.allCategories } returns flowOf(emptyList())
        every { privacyRepo.observeLoadState() } returns privacyFlow
        coEvery { expenseRepository.searchMerchants(any()) } returns emptyList()
        coEvery { quickAddLearnedCategoryLookup.lookup(any(), any()) } returns LearnedCategoryLookupResult.Absent
        coEvery {
            manualExpenseRepository.addManualExpense(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
            )
        } returns Result.Success(1L)
    }

    @After
    override fun tearDown() {
        try {
            Locale.setDefault(originalLocale)
            TimeZone.setDefault(originalTimeZone)
        } finally {
            super.tearDown()
        }
    }

    // ---- helpers ----

    private val st: AddExpenseState get() = viewModel.state.value
    private fun settle() = testDispatcher.scheduler.advanceUntilIdle()
    private fun runNow() = testDispatcher.scheduler.runCurrent()
    private fun advance(ms: Long) {
        testDispatcher.scheduler.advanceTimeBy(ms)
        testDispatcher.scheduler.runCurrent()
    }

    private fun at(y: Int, mo: Int, d: Int, h: Int = 12, mi: Int = 0): Long =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, athens).toInstant().toEpochMilli()

    private fun oct(day: Int): LocalDate = LocalDate.of(2025, 10, day)

    private fun putRate(from: String, to: String, rate: Double, date: LocalDate) {
        rateStore.rows += DomainExchangeRate(
            fromCurrency = from,
            toCurrency = to,
            rate = rate,
            lastUpdated = 0L,
            source = "ecb",
            validDate = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
    }

    private fun stored(earliest: LocalDate) = CurrencyRateHistoryOutcome.Stored(
        days = 60,
        rows = 600,
        earliestDate = earliest,
        latestDate = oct(16)
    )

    /**
     * A fresh VM (home EUR) with USD 20.00 entered for [entryDate] (epoch ms, default Fri 10 Oct 12:00
     * Athens) in a blocked state. [newerRow] stores the 14 Oct USD->EUR row (a download can help).
     */
    private fun openBlockedUsd(
        privacy: PrivacySettingsLoadState = ratesOn,
        entryDate: Long = at(2025, 10, 10),
        newerRow: Boolean = true
    ) {
        TimeZone.setDefault(TimeZone.getTimeZone(athens))
        Locale.setDefault(Locale.US)
        rateStore.rows.clear()
        ratesRepo.historyCalls.clear()
        ratesRepo.calls.clear()
        if (newerRow) putRate("USD", "EUR", 0.8, oct(14))
        privacyFlow.value = privacy
        val repo = mockk<CurrencySettingsRepository>(relaxed = true)
        every { repo.homeCurrency() } returns flowOf("EUR")
        viewModel = AddExpenseViewModel(
            manualExpenseRepository = manualExpenseRepository,
            expenseRepository = expenseRepository,
            categoryRepository = categoryRepository,
            timeProvider = timeProvider,
            currencySettingsRepository = repo,
            quickAddParser = QuickAddParser(),
            quickAddLearnedCategoryLookup = quickAddLearnedCategoryLookup,
            userPresentRatePreview = UserPresentRatePreview(
                RateEligibilityEvaluator(CurrencyConverter(rateStore, timeProvider, testDispatcher)),
                rateStore
            ),
            currencyRatesRepository = ratesRepo,
            privacySettingsRepository = privacyRepo,
            installmentPlanCoordinator = installmentCoordinator,
            installmentPostCommitDispatcher = installmentDispatcher
        )
        settle()
        viewModel.onSheetOpened()
        settle()
        viewModel.updateDate(entryDate)
        viewModel.selectCurrency("USD")
        viewModel.updateAmount("20.00")
        settle()
    }

    private fun tapDownload() {
        viewModel.onDownloadRateHistory()
        settle()
    }

    // ---- AE-1b-1 / INV-1b-5: the remedy shown on the blocked card ----

    @Test
    fun `AE-1b-1 no stored pair row with the setting On offers REFRESH_LATEST`() {
        openBlockedUsd(newerRow = false)

        assertTrue(st.currencyRegion is CurrencyRegionUi.Blocked)
        assertEquals(CurrencyRateFixAction.REFRESH_LATEST, st.rateFixAction)
    }

    @Test
    fun `AE-1b-1 a newer stored row inside the 90 day window offers DOWNLOAD_HISTORY`() {
        openBlockedUsd()

        assertTrue(st.currencyRegion is CurrencyRegionUi.Blocked)
        assertEquals(CurrencyRateFixAction.DOWNLOAD_HISTORY, st.rateFixAction)
        assertEquals(CurrencyHistoryStatus.IDLE, st.historyStatus)
    }

    @Test
    fun `AE-1b-1 an entry date older than 90 days shows TOO_OLD and never a download`() {
        openBlockedUsd(entryDate = at(2025, 7, 1))

        assertEquals(CurrencyRateFixAction.TOO_OLD, st.rateFixAction)
        viewModel.onDownloadRateHistory()
        settle()
        assertTrue(ratesRepo.historyCalls.isEmpty())
    }

    @Test
    fun `AE-1b-1 the 90 day boundary date still offers DOWNLOAD_HISTORY and one day earlier is TOO_OLD`() {
        // today 16 Oct minus 90 days is 18 Jul: the boundary day is inside, 17 Jul is outside.
        openBlockedUsd(entryDate = at(2025, 7, 18))
        assertEquals(CurrencyRateFixAction.DOWNLOAD_HISTORY, st.rateFixAction)

        openBlockedUsd(entryDate = at(2025, 7, 17))
        assertEquals(CurrencyRateFixAction.TOO_OLD, st.rateFixAction)
    }

    @Test
    fun `AE-1b-1 setting Off or Unknown always shows OPEN_SETTING whatever the domain remedy`() {
        openBlockedUsd(privacy = PrivacySettingsLoadState.Loaded(PrivacySettings()))
        assertEquals(CurrencyRatesSetting.OFF, st.ratesSetting)
        assertEquals(CurrencyRateFixAction.OPEN_SETTING, st.rateFixAction)

        openBlockedUsd(
            privacy = PrivacySettingsLoadState.CorruptedFailClosed(
                PrivacySettings(exchangeRateDownloadEnabled = true),
                reason = "READ_FAILED"
            )
        )
        assertEquals(CurrencyRatesSetting.UNKNOWN, st.ratesSetting)
        assertEquals(CurrencyRateFixAction.OPEN_SETTING, st.rateFixAction)

        openBlockedUsd(
            privacy = PrivacySettingsLoadState.Loaded(PrivacySettings()),
            entryDate = at(2025, 7, 1)
        )
        assertEquals(CurrencyRateFixAction.OPEN_SETTING, st.rateFixAction)
    }

    @Test
    fun `AE-1b-1 a non blocked region has no fix action`() {
        openBlockedUsd()
        putRate("USD", "EUR", 0.9, oct(9))
        viewModel.updateAmount("21.00")
        settle()

        assertTrue(st.currencyRegion is CurrencyRegionUi.Eligible)
        assertEquals(CurrencyRateFixAction.NONE, st.rateFixAction)
    }

    // ---- AE-1b-3 / AE-1b-24: tap, RUNNING, Stored, re-evaluation and the single announcement ----

    @Test
    fun `AE-1b-3 tapping Download shows RUNNING then a Stored result re-evaluates the preview to Eligible`() {
        ratesRepo.historyAction = {
            delay(500)
            putRate("USD", "EUR", 0.9, oct(9))
        }
        ratesRepo.historyOutcome = stored(earliest = oct(1))
        openBlockedUsd()
        val keyBefore = st.previewAnnounceKey

        viewModel.onDownloadRateHistory()
        runNow()
        assertEquals(CurrencyHistoryStatus.RUNNING, st.historyStatus)
        assertTrue(st.currencyRegion is CurrencyRegionUi.Blocked)

        advance(500)
        settle()

        val region = st.currencyRegion as CurrencyRegionUi.Eligible
        assertEquals(oct(9), region.rateDate)
        assertEquals(CurrencyFormatter.formatMoney(18.0, "EUR"), region.formattedAmountInHome)
        assertEquals(CurrencyHistoryStatus.IDLE, st.historyStatus)
        assertEquals(listOf("EUR"), ratesRepo.historyCalls)
        assertEquals(keyBefore + 1, st.previewAnnounceKey)
    }

    @Test
    fun `AE-1b-24 the announce key bumps exactly once for a history success and not for later keystrokes`() {
        ratesRepo.historyAction = { putRate("USD", "EUR", 0.9, oct(9)) }
        ratesRepo.historyOutcome = stored(earliest = oct(1))
        openBlockedUsd()
        val keyBefore = st.previewAnnounceKey

        tapDownload()
        assertEquals(keyBefore + 1, st.previewAnnounceKey)

        viewModel.updateAmount("21.00")
        settle()
        viewModel.updateAmount("22.00")
        settle()

        assertTrue(st.currencyRegion is CurrencyRegionUi.Eligible)
        assertEquals("a keystroke is not an announcement", keyBefore + 1, st.previewAnnounceKey)
    }

    @Test
    fun `AE-1b-24 a Stored result that leaves the entry blocked does not bump the announce key`() {
        ratesRepo.historyOutcome = stored(earliest = oct(5))
        openBlockedUsd()
        val keyBefore = st.previewAnnounceKey

        tapDownload()

        assertTrue(st.currencyRegion is CurrencyRegionUi.Blocked)
        assertEquals(keyBefore, st.previewAnnounceKey)
    }

    @Test
    fun `AE-1b-3 a second tap while RUNNING is ignored and calls the repository once`() {
        ratesRepo.historyAction = { delay(500) }
        ratesRepo.historyOutcome = stored(earliest = oct(5))
        openBlockedUsd()

        viewModel.onDownloadRateHistory()
        runNow()
        assertEquals(CurrencyHistoryStatus.RUNNING, st.historyStatus)
        viewModel.onDownloadRateHistory()
        runNow()
        viewModel.onDownloadRateHistory()
        runNow()
        assertEquals(1, ratesRepo.historyCalls.size)

        advance(500)
        settle()
        assertEquals(1, ratesRepo.historyCalls.size)
    }

    // ---- AE-1b-15: Cancel ----

    @Test
    fun `AE-1b-15 Cancel while RUNNING ends CANCELLED and no rows are stored`() {
        ratesRepo.historyAction = {
            delay(500)
            putRate("USD", "EUR", 0.9, oct(9))
        }
        ratesRepo.historyOutcome = stored(earliest = oct(1))
        openBlockedUsd()
        val rowsBefore = rateStore.rows.size
        val keyBefore = st.previewAnnounceKey

        viewModel.onDownloadRateHistory()
        runNow()
        assertEquals(CurrencyHistoryStatus.RUNNING, st.historyStatus)
        viewModel.onCancelRateHistory()
        advance(1_000)
        settle()

        assertEquals(CurrencyHistoryStatus.CANCELLED, st.historyStatus)
        assertEquals(rowsBefore, rateStore.rows.size)
        assertTrue(st.currencyRegion is CurrencyRegionUi.Blocked)
        assertEquals(keyBefore, st.previewAnnounceKey)
        // The cancelled download did not count as a session download: the action is offered again.
        assertEquals(CurrencyRateFixAction.DOWNLOAD_HISTORY, st.rateFixAction)
    }

    @Test
    fun `AE-1b-15 Cancel when nothing is running is ignored`() {
        openBlockedUsd()

        viewModel.onCancelRateHistory()
        settle()

        assertEquals(CurrencyHistoryStatus.IDLE, st.historyStatus)
        assertTrue(ratesRepo.historyCalls.isEmpty())
    }

    @Test
    fun `AE-1b-15 after Cancel the user can start a new download`() {
        ratesRepo.historyAction = { delay(500) }
        ratesRepo.historyOutcome = stored(earliest = oct(5))
        openBlockedUsd()

        viewModel.onDownloadRateHistory()
        runNow()
        viewModel.onCancelRateHistory()
        settle()
        assertEquals(CurrencyHistoryStatus.CANCELLED, st.historyStatus)

        tapDownload()

        assertEquals(2, ratesRepo.historyCalls.size)
    }

    @Test
    fun `AE-1b-10 a download tap while saving is ignored and leaves the history status unchanged`() {
        ratesRepo.historyOutcome = stored(earliest = oct(5))
        openBlockedUsd()
        val saveGate = CompletableDeferred<Result<Long>>()
        coEvery {
            manualExpenseRepository.addManualExpense(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
            )
        } coAnswers { saveGate.await() }

        viewModel.updateMerchant("Shop")
        settle()
        viewModel.save()
        runNow()
        assertTrue(st.isSaving)
        // The download would otherwise be offered, so the ignored tap is not vacuous.
        assertEquals(CurrencyRateFixAction.DOWNLOAD_HISTORY, st.rateFixAction)

        viewModel.onDownloadRateHistory()
        runNow()

        assertTrue(ratesRepo.historyCalls.isEmpty())
        assertEquals(CurrencyHistoryStatus.IDLE, st.historyStatus)

        saveGate.complete(Result.Success(1L))
        settle()
        assertTrue(ratesRepo.historyCalls.isEmpty())
    }

    @Test
    fun `AE-1b-15 a repository that ignores cancellation leaves CANCELLED without announcing or marking downloaded`() {
        // The fake ignores the user's Cancel: it is triggered inside the call, then returns Stored normally.
        ratesRepo.historyAction = {
            putRate("USD", "EUR", 0.9, oct(9))
            viewModel.onCancelRateHistory()
        }
        ratesRepo.historyOutcome = stored(earliest = oct(1))
        openBlockedUsd()
        val keyBefore = st.previewAnnounceKey

        tapDownload()

        assertEquals(1, ratesRepo.historyCalls.size)
        assertEquals(CurrencyHistoryStatus.CANCELLED, st.historyStatus)
        assertEquals("a cancelled download is not announced", keyBefore, st.previewAnnounceKey)
        // Not marked downloaded: the action is still offered (a marked session would offer none).
        assertEquals(CurrencyRateFixAction.DOWNLOAD_HISTORY, st.rateFixAction)
        assertTrue(st.currencyRegion is CurrencyRegionUi.Blocked)
    }

    // ---- AE-1b-5 / D-1b-5: failure reason mapping ----

    @Test
    fun `AE-1b-5 OFFLINE maps to FAILED_OFFLINE and the action stays available for a retry`() {
        ratesRepo.historyOutcome = CurrencyRateHistoryOutcome.Failed(RateHistoryFailureReason.OFFLINE)
        openBlockedUsd()

        tapDownload()

        assertEquals(CurrencyHistoryStatus.FAILED_OFFLINE, st.historyStatus)
        assertEquals(CurrencyRateFixAction.DOWNLOAD_HISTORY, st.rateFixAction)
        assertTrue(st.currencyRegion is CurrencyRegionUi.Blocked)
    }

    @Test
    fun `AE-1b-5 each non home failure reason other than OFFLINE maps to FAILED`() {
        val others = RateHistoryFailureReason.values().filter {
            it != RateHistoryFailureReason.OFFLINE &&
                it != RateHistoryFailureReason.INVALID_HOME_CURRENCY &&
                it != RateHistoryFailureReason.HOME_QUOTE_UNAVAILABLE
        }
        assertEquals(7, others.size)
        for (reason in others) {
            ratesRepo.historyOutcome = CurrencyRateHistoryOutcome.Failed(reason)
            openBlockedUsd()

            tapDownload()

            assertEquals(reason.name, CurrencyHistoryStatus.FAILED, st.historyStatus)
            assertEquals(reason.name, CurrencyRateFixAction.DOWNLOAD_HISTORY, st.rateFixAction)
        }
    }

    @Test
    fun `AE-1b-5 AlreadyInProgress maps to FAILED`() {
        ratesRepo.historyOutcome = CurrencyRateHistoryOutcome.AlreadyInProgress
        openBlockedUsd()

        tapDownload()

        assertEquals(CurrencyHistoryStatus.FAILED, st.historyStatus)
    }

    @Test
    fun `AE-1b-5 home currency failures map to FAILED_HOME_UNAVAILABLE and hide the action for the session`() {
        for (reason in listOf(
            RateHistoryFailureReason.INVALID_HOME_CURRENCY,
            RateHistoryFailureReason.HOME_QUOTE_UNAVAILABLE
        )) {
            ratesRepo.historyOutcome = CurrencyRateHistoryOutcome.Failed(reason)
            openBlockedUsd()

            tapDownload()
            assertEquals(reason.name, CurrencyHistoryStatus.FAILED_HOME_UNAVAILABLE, st.historyStatus)
            assertEquals(reason.name, CurrencyRateFixAction.NONE, st.rateFixAction)
            assertTrue(reason.name, st.historyHomeUnavailable)

            // A later re-evaluation clears the message but not the session flag.
            viewModel.updateAmount("21.00")
            settle()
            assertTrue(st.currencyRegion is CurrencyRegionUi.Blocked)
            assertEquals(reason.name, CurrencyRateFixAction.NONE, st.rateFixAction)

            // The hidden action cannot be started either.
            viewModel.onDownloadRateHistory()
            settle()
            assertEquals(reason.name, 1, ratesRepo.historyCalls.size)
        }
    }

    @Test
    fun `AE-1b-5 a thrown exception maps to FAILED without leaving RUNNING behind`() {
        ratesRepo.historyAction = { throw IllegalStateException("synthetic failure") }
        openBlockedUsd()

        tapDownload()

        assertEquals(CurrencyHistoryStatus.FAILED, st.historyStatus)
    }

    @Test
    fun `AE-1b-5 DisabledByPrivacy turns the setting Off without a failure line`() {
        ratesRepo.historyOutcome = CurrencyRateHistoryOutcome.DisabledByPrivacy
        openBlockedUsd()

        tapDownload()

        assertEquals(CurrencyRatesSetting.OFF, st.ratesSetting)
        assertEquals(CurrencyHistoryStatus.IDLE, st.historyStatus)
        assertEquals(CurrencyRateFixAction.OPEN_SETTING, st.rateFixAction)
    }

    @Test
    fun `AE-1b-5 a privacy check failure makes the setting Unknown without a failure line`() {
        ratesRepo.historyOutcome = CurrencyRateHistoryOutcome.PrivacyCheckFailed("PRIVACY_READ_FAILED")
        openBlockedUsd()

        tapDownload()

        assertEquals(CurrencyRatesSetting.UNKNOWN, st.ratesSetting)
        assertEquals(CurrencyHistoryStatus.IDLE, st.historyStatus)
        assertEquals(CurrencyRateFixAction.OPEN_SETTING, st.rateFixAction)
    }

    // ---- AE-1b-6b: HISTORY_ALREADY_DOWNLOADED, still blocked ----

    @Test
    fun `AE-1b-6b still blocked after a download with the entry before the earliest stored date is DONE_TOO_OLD`() {
        ratesRepo.historyOutcome = stored(earliest = oct(12))
        openBlockedUsd() // entry 10 Oct

        tapDownload()

        assertTrue(st.currencyRegion is CurrencyRegionUi.Blocked)
        assertEquals(CurrencyHistoryStatus.DONE_TOO_OLD, st.historyStatus)
        assertEquals(CurrencyRateFixAction.NONE, st.rateFixAction)
    }

    @Test
    fun `AE-1b-6b still blocked after a download with the entry on the earliest stored date is DONE_NO_RATE`() {
        ratesRepo.historyOutcome = stored(earliest = oct(10))
        openBlockedUsd()

        tapDownload()

        assertTrue(st.currencyRegion is CurrencyRegionUi.Blocked)
        assertEquals(CurrencyHistoryStatus.DONE_NO_RATE, st.historyStatus)
        assertEquals(CurrencyRateFixAction.NONE, st.rateFixAction)
    }

    @Test
    fun `AE-1b-6b still blocked after a download with the entry after the earliest stored date is DONE_NO_RATE`() {
        ratesRepo.historyOutcome = stored(earliest = oct(5))
        openBlockedUsd()

        tapDownload()

        assertEquals(CurrencyHistoryStatus.DONE_NO_RATE, st.historyStatus)
        assertEquals(CurrencyRateFixAction.NONE, st.rateFixAction)
        assertEquals(1, ratesRepo.historyCalls.size)
    }

    @Test
    fun `AE-1b-6b a DONE status settles to IDLE when the entry becomes eligible`() {
        ratesRepo.historyOutcome = stored(earliest = oct(5))
        openBlockedUsd()
        tapDownload()
        assertEquals(CurrencyHistoryStatus.DONE_NO_RATE, st.historyStatus)

        putRate("USD", "EUR", 0.9, oct(9))
        viewModel.updateAmount("21.00")
        settle()

        assertTrue(st.currencyRegion is CurrencyRegionUi.Eligible)
        assertEquals(CurrencyHistoryStatus.IDLE, st.historyStatus)
    }

    // ---- AE-1b-25: leaving the sheet ----

    @Test
    fun `AE-1b-25 onSheetLeft cancels a running download without a status or a store effect`() {
        ratesRepo.historyAction = {
            delay(500)
            putRate("USD", "EUR", 0.9, oct(9))
        }
        ratesRepo.historyOutcome = stored(earliest = oct(1))
        openBlockedUsd()
        val rowsBefore = rateStore.rows.size

        viewModel.onDownloadRateHistory()
        runNow()
        assertEquals(CurrencyHistoryStatus.RUNNING, st.historyStatus)
        viewModel.onSheetLeft()
        advance(1_000)
        settle()

        assertEquals(CurrencyHistoryStatus.IDLE, st.historyStatus)
        assertEquals(rowsBefore, rateStore.rows.size)
        assertTrue(st.currencyRegion is CurrencyRegionUi.Blocked)
    }

    @Test
    fun `AE-1b-25 onSheetLeft drops the session download flag so the action is offered again`() {
        ratesRepo.historyOutcome = stored(earliest = oct(5))
        openBlockedUsd()
        tapDownload()
        assertEquals(CurrencyRateFixAction.NONE, st.rateFixAction)

        viewModel.onSheetLeft()
        viewModel.updateAmount("21.00")
        settle()

        assertEquals(CurrencyRateFixAction.DOWNLOAD_HISTORY, st.rateFixAction)
        assertEquals(CurrencyHistoryStatus.IDLE, st.historyStatus)
    }

    @Test
    fun `AE-1b-25 onSheetLeft clears the home unavailable flag`() {
        ratesRepo.historyOutcome =
            CurrencyRateHistoryOutcome.Failed(RateHistoryFailureReason.HOME_QUOTE_UNAVAILABLE)
        openBlockedUsd()
        tapDownload()
        assertTrue(st.historyHomeUnavailable)

        viewModel.onSheetLeft()
        viewModel.updateAmount("21.00")
        settle()

        assertFalse(st.historyHomeUnavailable)
        assertEquals(CurrencyRateFixAction.DOWNLOAD_HISTORY, st.rateFixAction)
    }

    @Test
    fun `AE-1b-25 reset clears the history session`() {
        ratesRepo.historyOutcome = stored(earliest = oct(5))
        openBlockedUsd()
        tapDownload()

        viewModel.reset()
        settle()

        assertEquals(CurrencyHistoryStatus.IDLE, st.historyStatus)
        assertFalse(st.historyHomeUnavailable)
    }

    // ---- regression: home-unavailable outcomes hide every fix action; announce is one-shot ----

    @Test
    fun `AE-1b-5 a daily refresh FAILED_HOME_UNAVAILABLE leaves rateFixAction NONE`() {
        for (message in listOf("HOME_CURRENCY_QUOTE_UNAVAILABLE", "INVALID_HOME_CURRENCY")) {
            ratesRepo.outcome = { throw IllegalStateException(message) }
            openBlockedUsd(newerRow = false)
            assertEquals(message, CurrencyRateFixAction.REFRESH_LATEST, st.rateFixAction)

            viewModel.refreshRates()
            settle()

            assertEquals(message, CurrencyRefreshStatus.FAILED_HOME_UNAVAILABLE, st.rateRefresh)
            assertTrue(message, st.currencyRegion is CurrencyRegionUi.Blocked)
            assertEquals(message, CurrencyRateFixAction.NONE, st.rateFixAction)
            assertTrue(message, ratesRepo.historyCalls.isEmpty())
        }
    }

    @Test
    fun `AE-1b-5 a plain refresh failure keeps REFRESH_LATEST offered as a control`() {
        ratesRepo.outcome = { throw IllegalStateException("synthetic failure") }
        openBlockedUsd(newerRow = false)

        viewModel.refreshRates()
        settle()

        assertEquals(CurrencyRefreshStatus.FAILED, st.rateRefresh)
        assertEquals(CurrencyRateFixAction.REFRESH_LATEST, st.rateFixAction)
    }

    @Test
    fun `AE-1b-5 history FAILED_HOME_UNAVAILABLE persists across a date re-evaluation until onSheetLeft`() {
        ratesRepo.historyOutcome =
            CurrencyRateHistoryOutcome.Failed(RateHistoryFailureReason.HOME_QUOTE_UNAVAILABLE)
        openBlockedUsd()
        tapDownload()
        assertEquals(CurrencyHistoryStatus.FAILED_HOME_UNAVAILABLE, st.historyStatus)
        assertEquals(CurrencyRateFixAction.NONE, st.rateFixAction)

        viewModel.updateDate(at(2025, 10, 9))
        settle()
        assertTrue(st.currencyRegion is CurrencyRegionUi.Blocked)
        assertEquals(CurrencyHistoryStatus.FAILED_HOME_UNAVAILABLE, st.historyStatus)
        assertEquals(CurrencyRateFixAction.NONE, st.rateFixAction)

        viewModel.updateAmount("21.00")
        settle()
        assertEquals(CurrencyHistoryStatus.FAILED_HOME_UNAVAILABLE, st.historyStatus)
        assertEquals(CurrencyRateFixAction.NONE, st.rateFixAction)

        viewModel.onSheetLeft()
        viewModel.updateAmount("22.00")
        settle()
        assertEquals(CurrencyHistoryStatus.IDLE, st.historyStatus)
        assertFalse(st.historyHomeUnavailable)
        assertEquals(CurrencyRateFixAction.DOWNLOAD_HISTORY, st.rateFixAction)
    }

    @Test
    fun `AE-1b-24 a Stored then DONE blocked evaluation then a date change adds only the date change bump`() {
        ratesRepo.historyOutcome = stored(earliest = oct(5))
        openBlockedUsd()
        val keyBefore = st.previewAnnounceKey

        tapDownload()
        assertEquals(CurrencyHistoryStatus.DONE_NO_RATE, st.historyStatus)
        assertEquals("a blocked result after Stored is not announced", keyBefore, st.previewAnnounceKey)

        putRate("USD", "EUR", 0.9, oct(9))
        viewModel.updateDate(at(2025, 10, 10, 15))
        settle()

        assertTrue(st.currencyRegion is CurrencyRegionUi.Eligible)
        assertEquals("only updateDate's own bump, no stale announce", keyBefore + 1, st.previewAnnounceKey)

        viewModel.updateAmount("21.00")
        settle()
        assertEquals(keyBefore + 1, st.previewAnnounceKey)
    }

    // ---- AE-1b-4: the download is never automatic ----

    @Test
    fun `AE-1b-4 init preview typing and save never call downloadRecentHistory`() {
        ratesRepo.historyOutcome = stored(earliest = oct(1))
        openBlockedUsd()
        assertEquals(CurrencyRateFixAction.DOWNLOAD_HISTORY, st.rateFixAction)

        viewModel.updateMerchant("Synthetic Shop")
        viewModel.updateAmount("25.50")
        viewModel.updateDate(at(2025, 10, 9))
        viewModel.selectCurrency("EUR")
        viewModel.selectCurrency("USD")
        viewModel.save()
        settle()
        viewModel.onSheetLeft()
        viewModel.onSheetOpened()
        settle()

        assertTrue("download must stay user-started", ratesRepo.historyCalls.isEmpty())
    }

    @Test
    fun `AE-1b-4 refreshing latest rates never starts a history download`() {
        openBlockedUsd(newerRow = false)
        assertEquals(CurrencyRateFixAction.REFRESH_LATEST, st.rateFixAction)

        viewModel.refreshRates()
        settle()

        assertEquals(listOf("EUR"), ratesRepo.calls)
        assertTrue(ratesRepo.historyCalls.isEmpty())
    }

    @Test
    fun `AE-1b-4 the download action is ignored unless DOWNLOAD_HISTORY is the offered action`() {
        // REFRESH_LATEST offered.
        openBlockedUsd(newerRow = false)
        viewModel.onDownloadRateHistory()
        settle()
        // Setting Off (OPEN_SETTING offered).
        openBlockedUsd(privacy = PrivacySettingsLoadState.Loaded(PrivacySettings()))
        viewModel.onDownloadRateHistory()
        settle()
        // TOO_OLD offered.
        openBlockedUsd(entryDate = at(2025, 7, 1))
        viewModel.onDownloadRateHistory()
        settle()
        // Home currency selected.
        openBlockedUsd()
        viewModel.selectCurrency("EUR")
        settle()
        viewModel.onDownloadRateHistory()
        settle()

        assertTrue(ratesRepo.historyCalls.isEmpty())
        assertEquals(CurrencyHistoryStatus.IDLE, st.historyStatus)
    }
}
