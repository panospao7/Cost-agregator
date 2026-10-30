package com.yourname.expensetracker.ui.screens.receiptscan

import android.net.Uri
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.domain.currency.CurrencyRateHistoryOutcome
import com.yourname.expensetracker.domain.currency.CurrencyUnsetReason
import com.yourname.expensetracker.domain.currency.RateHistoryFailureReason
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.privacy.PrivacySettingsLoadState
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLifecycleCoordinator
import com.yourname.expensetracker.domain.transaction.RateRefusalReason
import com.yourname.expensetracker.ui.components.currency.CurrencyBlockOrigin
import com.yourname.expensetracker.ui.components.currency.CurrencyBlockVariant
import com.yourname.expensetracker.ui.components.currency.CurrencyHistoryStatus
import com.yourname.expensetracker.ui.components.currency.CurrencyRateFixAction
import com.yourname.expensetracker.ui.components.currency.CurrencyRatesSetting
import com.yourname.expensetracker.ui.components.currency.CurrencyRegionUi
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.Locale
import java.util.TimeZone

/**
 * MEC increment 1b S3 (receipt scan): INV-1b-3 (user-started download only), INV-1b-6 (session data),
 * INV-1b-18 (rate fix action parity with add-expense), INV-1b-20 (assumed home cannot follow a home change),
 * INV-1b-21 (cancel) and the STOP list (a download never touches the receipt lifecycle).
 *
 * Real view model over the C1 fakes. Clock is 2023-11-14 UTC; the zone is UTC. A download scenario is a USD
 * receipt dated 2023-11-09 with a stored USD->EUR rate dated 2023-11-14 (newer than the entry, none usable),
 * so the domain remedy is DOWNLOAD_HISTORY. Synthetic data only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReceiptScanViewModelRateHistoryTest : ViewModelTestUtils() {
    private lateinit var deps: ReceiptScanMecDeps
    private lateinit var model: ReceiptScanViewModel
    private val originalZone: TimeZone = TimeZone.getDefault()
    private val originalLocale: Locale = Locale.getDefault()

    private val st get() = model.state.value
    private val region get() = st.currencyRegion

    @Before
    override fun setup() {
        super.setup()
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        deps = ReceiptScanMecDeps(testDispatcher, MEC_DATE)
        coEvery { deps.links.checkCanLinkReceipt(any()) } returns true
        model = deps.createViewModel()
        settle()
    }

    @After
    override fun tearDown() {
        try {
            TimeZone.setDefault(originalZone)
            Locale.setDefault(originalLocale)
        } finally {
            super.tearDown()
        }
    }

    // ---- helpers ----

    private fun settle() = testDispatcher.scheduler.advanceUntilIdle()
    private fun runNow() = testDispatcher.scheduler.runCurrent()

    private fun setRatesSetting(state: PrivacySettingsLoadState) {
        deps.privacyFlow.value = state
        settle()
    }

    private fun enableDownload() =
        setRatesSetting(PrivacySettingsLoadState.Loaded(PrivacySettings(exchangeRateDownloadEnabled = true)))

    private fun scan(receipt: ScannedReceipt) {
        val uri = Uri.parse("content://synthetic/history-${receipt.id}.jpg")
        coEvery { deps.coordinator.processReceiptInput(uri, any()) } returns kotlin.Result.success(
            ReceiptLifecycleCoordinator.ReceiptProcessOutcome(receipt, true, null)
        )
        coEvery { deps.links.checkCanLinkReceipt(receipt.id) } returns true
        model.processGalleryImage(uri)
        settle()
    }

    private fun daysBefore(days: Int): Long = MEC_DATE - days * MEC_DAY_MS

    /** USD receipt dated 2023-11-09; the only stored rate is dated 2023-11-14 (newer than the entry). */
    private fun scanDownloadScenario(id: Long = 17L) {
        if (deps.store.rows.isEmpty()) deps.store.putRate("USD", 0.9, mecDay(14))
        scan(mecReceipt(id = id, currency = "USD", date = daysBefore(5)))
    }

    private fun storedOutcome(earliest: LocalDate = mecDay(1)) =
        CurrencyRateHistoryOutcome.Stored(days = 10, rows = 10, earliestDate = earliest, latestDate = mecDay(14))

    private fun assertDownloadable() {
        assertEquals(ScanStep.REVIEW, st.step)
        assertTrue(region is CurrencyRegionUi.Blocked)
        assertEquals(CurrencyRatesSetting.ON, st.ratesSetting)
        assertEquals(CurrencyRateFixAction.DOWNLOAD_HISTORY, st.rateFixAction)
    }

    private fun assertNoReceiptLifecycleTraffic() {
        assertTrue(deps.requests.isEmpty())
        coVerify(exactly = 0) { deps.coordinator.createExpenseAndLinkReceipt(any()) }
    }

    // ---- INV-1b-18: rateFixAction parity with add-expense ----

    @Test
    fun `INV-1b-18 setting On with no stored pair offers REFRESH_LATEST`() {
        enableDownload()
        scan(mecReceipt(currency = "USD", date = daysBefore(5)))

        assertEquals(CurrencyRegionUi.Blocked(CurrencyBlockVariant.NONE, null, CurrencyBlockOrigin.PREVIEW), region)
        assertEquals(CurrencyRateFixAction.REFRESH_LATEST, st.rateFixAction)
    }

    @Test
    fun `INV-1b-18 setting On with a newer stored rate inside the window offers DOWNLOAD_HISTORY for NONE and STALE`() {
        enableDownload()
        scanDownloadScenario()
        assertEquals(CurrencyRegionUi.Blocked(CurrencyBlockVariant.NONE, null, CurrencyBlockOrigin.PREVIEW), region)
        assertDownloadable()

        // STALE variant: an older rate (age 20 days) exists besides the newer one.
        deps.store.rows.clear()
        deps.store.putRate("USD", 0.8, LocalDate.of(2023, 10, 20))
        deps.store.putRate("USD", 0.9, mecDay(14))
        scan(mecReceipt(id = 18L, currency = "USD", date = daysBefore(5)))
        assertEquals(CurrencyBlockVariant.STALE, (region as CurrencyRegionUi.Blocked).variant)
        assertEquals(CurrencyRateFixAction.DOWNLOAD_HISTORY, st.rateFixAction)
    }

    @Test
    fun `INV-1b-18 an entry older than the history window offers TOO_OLD and never a download`() {
        enableDownload()
        deps.store.putRate("USD", 0.9, mecDay(14))
        scan(mecReceipt(currency = "USD", date = daysBefore(100)))

        assertTrue(region is CurrencyRegionUi.Blocked)
        assertEquals(CurrencyRateFixAction.TOO_OLD, st.rateFixAction)
        model.onDownloadRateHistory()
        settle()
        assertTrue(deps.ratesRepo.historyCalls.isEmpty())
    }

    @Test
    fun `INV-1b-18 setting Off or Unknown always offers OPEN_SETTING even when a download would help`() {
        scanDownloadScenario()
        assertEquals(CurrencyRatesSetting.OFF, st.ratesSetting)
        assertEquals(CurrencyRateFixAction.OPEN_SETTING, st.rateFixAction)

        setRatesSetting(PrivacySettingsLoadState.CorruptedFailClosed(PrivacySettings.FAIL_CLOSED_DEFAULTS, "TEST_CODE"))
        assertEquals(CurrencyRatesSetting.UNKNOWN, st.ratesSetting)
        assertEquals(CurrencyRateFixAction.OPEN_SETTING, st.rateFixAction)

        model.onDownloadRateHistory()
        settle()
        assertTrue(deps.ratesRepo.historyCalls.isEmpty())
    }

    @Test
    fun `INV-1b-18 a home preview an eligible rate and an unset currency offer no fix action`() {
        enableDownload()
        scan(mecReceipt(currency = "EUR"))
        assertEquals(CurrencyRegionUi.Home, region)
        assertEquals(CurrencyRateFixAction.NONE, st.rateFixAction)

        deps.store.putRate("USD", 0.9, mecDay(14))
        scan(mecReceipt(id = 18L, currency = "USD"))
        assertTrue(region is CurrencyRegionUi.Eligible)
        assertEquals(CurrencyRateFixAction.NONE, st.rateFixAction)

        scan(mecReceipt(id = 19L, currency = "BGN"))
        assertEquals(CurrencyRegionUi.Unset(CurrencyUnsetReason.PROPOSAL_NOT_SELECTABLE), region)
        assertEquals(CurrencyRateFixAction.NONE, st.rateFixAction)
    }

    // ---- INV-1b-3: only a user tap in REVIEW with a foreign selection and the setting On ----

    @Test
    fun `INV-1b-3 onDownloadRateHistory in REVIEW with the setting On calls the repository once for the home currency`() {
        enableDownload()
        scanDownloadScenario()
        assertDownloadable()
        deps.ratesRepo.historyOutcome = CurrencyRateHistoryOutcome.Failed(RateHistoryFailureReason.OFFLINE)

        model.onDownloadRateHistory()
        settle()

        assertEquals(listOf("EUR"), deps.ratesRepo.historyCalls)
        assertEquals(CurrencyHistoryStatus.FAILED_OFFLINE, st.historyStatus)
    }

    @Test
    fun `INV-1b-3 outside REVIEW or with a home selection the download is ignored`() {
        enableDownload()
        scanDownloadScenario()
        assertDownloadable()

        // Failing boundary 1: not in REVIEW (reset leaves the review).
        model.reset()
        settle()
        assertEquals(ScanStep.CAPTURE, st.step)
        model.onDownloadRateHistory()
        settle()
        assertTrue(deps.ratesRepo.historyCalls.isEmpty())

        // Failing boundary 2: a home selection in REVIEW has no rate region at all.
        scan(mecReceipt(id = 18L, currency = "USD", date = daysBefore(5)))
        assertDownloadable()
        model.updateCurrency("EUR")
        settle()
        assertEquals(CurrencyRegionUi.Home, region)
        model.onDownloadRateHistory()
        settle()
        assertTrue(deps.ratesRepo.historyCalls.isEmpty())
    }

    @Test
    fun `INV-1b-3 a second tap while RUNNING is ignored and a refresh in progress blocks the download`() {
        enableDownload()
        scanDownloadScenario()
        val release = CompletableDeferred<Unit>()
        deps.ratesRepo.onHistory = { release.await() }
        deps.ratesRepo.historyOutcome = CurrencyRateHistoryOutcome.Failed(RateHistoryFailureReason.TIMEOUT)

        model.onDownloadRateHistory()
        model.onDownloadRateHistory()
        runNow()
        assertEquals(CurrencyHistoryStatus.RUNNING, st.historyStatus)
        assertEquals(1, deps.ratesRepo.historyCalls.size)

        release.complete(Unit)
        settle()
        assertEquals(CurrencyHistoryStatus.FAILED, st.historyStatus)
        assertEquals(1, deps.ratesRepo.historyCalls.size)
    }

    // ---- Stored: the region re-evaluates, one announcement ----

    @Test
    fun `INV-1b-3 Stored with a usable rate makes the region Eligible and bumps the announce key once`() {
        enableDownload()
        scanDownloadScenario()
        assertDownloadable()
        val keyBefore = st.previewAnnounceKey
        // The download stores a rate for 2023-11-08, usable for the 2023-11-09 entry.
        deps.ratesRepo.onHistory = { deps.store.putRate("USD", 0.85, mecDay(8)) }
        deps.ratesRepo.historyOutcome = storedOutcome()

        model.onDownloadRateHistory()
        settle()

        val eligible = region as CurrencyRegionUi.Eligible
        assertEquals(mecDay(8), eligible.rateDate)
        assertEquals(CurrencyHistoryStatus.IDLE, st.historyStatus)
        assertEquals(CurrencyRateFixAction.NONE, st.rateFixAction)
        assertEquals(keyBefore + 1, st.previewAnnounceKey)
        assertEquals(1, deps.ratesRepo.historyCalls.size)

        // An unrelated amount edit afterwards does not announce again.
        model.updateAmount("11")
        settle()
        assertEquals(keyBefore + 1, st.previewAnnounceKey)
    }

    @Test
    fun `INV-1b-3 Stored that still leaves the entry blocked ends DONE_NO_RATE or DONE_TOO_OLD and offers no second download`() {
        enableDownload()
        scanDownloadScenario()
        val keyBefore = st.previewAnnounceKey
        deps.ratesRepo.historyOutcome = storedOutcome(earliest = mecDay(1))

        model.onDownloadRateHistory()
        settle()

        assertTrue(region is CurrencyRegionUi.Blocked)
        assertEquals(CurrencyHistoryStatus.DONE_NO_RATE, st.historyStatus)
        assertEquals(CurrencyRateFixAction.NONE, st.rateFixAction)
        assertEquals("a blocked result is not announced as a preview", keyBefore, st.previewAnnounceKey)
        model.onDownloadRateHistory()
        settle()
        assertEquals(1, deps.ratesRepo.historyCalls.size)

        // Entry earlier than the earliest stored ECB date: DONE_TOO_OLD (a fresh review).
        deps.ratesRepo.historyOutcome = storedOutcome(earliest = mecDay(10))
        scan(mecReceipt(id = 18L, currency = "USD", date = daysBefore(5)))
        assertDownloadable()
        model.onDownloadRateHistory()
        settle()
        assertEquals(CurrencyHistoryStatus.DONE_TOO_OLD, st.historyStatus)
        assertEquals(CurrencyRateFixAction.NONE, st.rateFixAction)
    }

    // ---- INV-1b-21: cancel ----

    @Test
    fun `INV-1b-21 Cancel while RUNNING ends CANCELLED and a late completion cannot overwrite it`() {
        enableDownload()
        scanDownloadScenario()
        val release = CompletableDeferred<Unit>()
        deps.ratesRepo.onHistory = { release.await() }
        deps.ratesRepo.historyOutcome = storedOutcome()

        model.onDownloadRateHistory()
        runNow()
        assertEquals(CurrencyHistoryStatus.RUNNING, st.historyStatus)
        model.onCancelRateHistory()
        assertEquals(CurrencyHistoryStatus.CANCELLED, st.historyStatus)

        release.complete(Unit)
        settle()
        assertEquals(CurrencyHistoryStatus.CANCELLED, st.historyStatus)
        assertTrue(region is CurrencyRegionUi.Blocked)
        // The same action is offered again; a new tap is a new, user-started download.
        assertEquals(CurrencyRateFixAction.DOWNLOAD_HISTORY, st.rateFixAction)
    }

    @Test
    fun `INV-1b-21 Cancel when nothing is running is a no-op`() {
        enableDownload()
        scanDownloadScenario()
        model.onCancelRateHistory()
        assertEquals(CurrencyHistoryStatus.IDLE, st.historyStatus)
    }

    @Test
    fun `INV-1b-3 a download tap while saving is ignored and leaves the history status unchanged`() {
        enableDownload()
        scanDownloadScenario()
        assertDownloadable()
        val saveStarted = CompletableDeferred<Unit>()
        val saveGate = CompletableDeferred<Unit>()
        coEvery { deps.coordinator.createExpenseAndLinkReceipt(any()) } coAnswers {
            saveStarted.complete(Unit)
            saveGate.await()
            kotlin.Result.success(900L)
        }
        deps.ratesRepo.historyOutcome = storedOutcome()

        model.saveExpense()
        runNow()
        assertTrue(saveStarted.isCompleted)
        assertTrue(st.isSaving)
        // The download would otherwise be offered, so the ignored tap is not vacuous.
        assertEquals(CurrencyRateFixAction.DOWNLOAD_HISTORY, st.rateFixAction)

        model.onDownloadRateHistory()
        runNow()

        assertTrue(deps.ratesRepo.historyCalls.isEmpty())
        assertEquals(CurrencyHistoryStatus.IDLE, st.historyStatus)

        saveGate.complete(Unit)
        settle()
        assertTrue(deps.ratesRepo.historyCalls.isEmpty())
    }

    @Test
    fun `INV-1b-21 a repository that ignores cancellation leaves CANCELLED without announcing or marking downloaded`() {
        enableDownload()
        scanDownloadScenario()
        val keyBefore = st.previewAnnounceKey
        // The fake ignores the user's Cancel: it is triggered inside the call, then returns Stored normally.
        deps.ratesRepo.onHistory = {
            deps.store.putRate("USD", 0.85, mecDay(8))
            model.onCancelRateHistory()
        }
        deps.ratesRepo.historyOutcome = storedOutcome()

        model.onDownloadRateHistory()
        settle()

        assertEquals(1, deps.ratesRepo.historyCalls.size)
        assertEquals(CurrencyHistoryStatus.CANCELLED, st.historyStatus)
        assertEquals("a cancelled download is not announced", keyBefore, st.previewAnnounceKey)
        // Not marked downloaded: the download is still offered (a marked session would offer none).
        assertEquals(CurrencyRateFixAction.DOWNLOAD_HISTORY, st.rateFixAction)
        assertTrue(region is CurrencyRegionUi.Blocked)
    }

    // ---- failure mapping ----

    @Test
    fun `INV-1b-3 each bounded failure maps to its status and keeps the download offered`() {
        enableDownload()
        scanDownloadScenario()
        val expected = mapOf(
            RateHistoryFailureReason.OFFLINE to CurrencyHistoryStatus.FAILED_OFFLINE,
            RateHistoryFailureReason.TIMEOUT to CurrencyHistoryStatus.FAILED,
            RateHistoryFailureReason.HTTP_ERROR to CurrencyHistoryStatus.FAILED,
            RateHistoryFailureReason.PAYLOAD_TOO_LARGE to CurrencyHistoryStatus.FAILED,
            RateHistoryFailureReason.PAYLOAD_EMPTY to CurrencyHistoryStatus.FAILED,
            RateHistoryFailureReason.PAYLOAD_MALFORMED to CurrencyHistoryStatus.FAILED,
            RateHistoryFailureReason.PAYLOAD_OUT_OF_BOUNDS to CurrencyHistoryStatus.FAILED,
            RateHistoryFailureReason.STORE_FAILED to CurrencyHistoryStatus.FAILED
        )
        for ((reason, status) in expected) {
            deps.ratesRepo.historyOutcome = CurrencyRateHistoryOutcome.Failed(reason)
            model.onDownloadRateHistory()
            settle()
            assertEquals(reason.name, status, st.historyStatus)
            assertEquals(reason.name, CurrencyRateFixAction.DOWNLOAD_HISTORY, st.rateFixAction)
        }
        assertEquals(expected.size, deps.ratesRepo.historyCalls.size)

        deps.ratesRepo.historyOutcome = CurrencyRateHistoryOutcome.AlreadyInProgress
        model.onDownloadRateHistory()
        settle()
        assertEquals(CurrencyHistoryStatus.FAILED, st.historyStatus)
    }

    @Test
    fun `INV-1b-3 a thrown repository exception ends FAILED without losing the review`() {
        enableDownload()
        scanDownloadScenario()
        deps.ratesRepo.onHistory = { throw IllegalStateException("synthetic failure") }

        model.onDownloadRateHistory()
        settle()

        assertEquals(CurrencyHistoryStatus.FAILED, st.historyStatus)
        assertEquals(ScanStep.REVIEW, st.step)
        assertTrue(region is CurrencyRegionUi.Blocked)
    }

    @Test
    fun `INV-1b-3 privacy outcomes fail closed to the setting and show no failure line`() {
        enableDownload()
        scanDownloadScenario()
        deps.ratesRepo.historyOutcome = CurrencyRateHistoryOutcome.DisabledByPrivacy
        model.onDownloadRateHistory()
        settle()
        assertEquals(CurrencyRatesSetting.OFF, st.ratesSetting)
        assertEquals(CurrencyHistoryStatus.IDLE, st.historyStatus)
        assertEquals(CurrencyRateFixAction.OPEN_SETTING, st.rateFixAction)

        // Toggle through Off so the stored setting emits On again after the local Off.
        setRatesSetting(PrivacySettingsLoadState.Loaded(PrivacySettings()))
        enableDownload()
        assertEquals(CurrencyRatesSetting.ON, st.ratesSetting)
        deps.ratesRepo.historyOutcome = CurrencyRateHistoryOutcome.PrivacyCheckFailed("TEST_CODE")
        model.onDownloadRateHistory()
        settle()
        assertEquals(CurrencyRatesSetting.UNKNOWN, st.ratesSetting)
        assertEquals(CurrencyHistoryStatus.IDLE, st.historyStatus)
        assertEquals(CurrencyRateFixAction.OPEN_SETTING, st.rateFixAction)
    }

    @Test
    fun `INV-1b-6 FAILED_HOME_UNAVAILABLE suppresses the action for the rest of the review`() {
        enableDownload()
        scanDownloadScenario()
        for (reason in listOf(
            RateHistoryFailureReason.HOME_QUOTE_UNAVAILABLE,
            RateHistoryFailureReason.INVALID_HOME_CURRENCY
        )) {
            deps.ratesRepo.historyCalls.clear()
            scanDownloadScenario()
            deps.ratesRepo.historyOutcome = CurrencyRateHistoryOutcome.Failed(reason)
            model.onDownloadRateHistory()
            settle()

            assertEquals(reason.name, CurrencyHistoryStatus.FAILED_HOME_UNAVAILABLE, st.historyStatus)
            assertTrue(st.historyHomeUnavailable)
            assertEquals(reason.name, CurrencyRateFixAction.NONE, st.rateFixAction)

            // Still suppressed after an edit re-evaluates, and a further tap does nothing.
            model.updateAmount("12")
            settle()
            assertEquals(CurrencyHistoryStatus.FAILED_HOME_UNAVAILABLE, st.historyStatus)
            assertEquals(CurrencyRateFixAction.NONE, st.rateFixAction)
            model.onDownloadRateHistory()
            settle()
            assertEquals(reason.name, 1, deps.ratesRepo.historyCalls.size)
        }
    }

    // ---- INV-1b-3: never automatic ----

    @Test
    fun `INV-1b-3 scan completion retry preview and save never call downloadRecentHistory`() {
        enableDownload()
        scanDownloadScenario()
        assertDownloadable()

        model.updateAmount("11")
        model.updateDate(daysBefore(6))
        settle()
        deps.saveOutcome = { kotlin.Result.failure(ReceiptLifecycleCoordinator.RateNotEligibleException(RateRefusalReason.NO_RATE, null)) }
        model.saveExpense()
        settle()
        assertEquals(SaveReceiptResult.RateNotEligible, st.saveResult)

        model.retry()
        settle()
        scanDownloadScenario(id = 18L)
        model.saveExpense()
        settle()
        setRatesSetting(PrivacySettingsLoadState.Loaded(PrivacySettings()))
        enableDownload()

        assertTrue(deps.ratesRepo.historyCalls.isEmpty())
        assertTrue(deps.ratesRepo.calls.isEmpty())
        assertEquals(CurrencyHistoryStatus.IDLE, st.historyStatus)
    }

    // ---- INV-1b-6: the history session is cleared with the review ----

    @Test
    fun `INV-1b-6 a new scan starts a new history session`() {
        enableDownload()
        scanDownloadScenario()
        deps.ratesRepo.historyOutcome = storedOutcome()
        model.onDownloadRateHistory()
        settle()
        assertEquals(CurrencyHistoryStatus.DONE_NO_RATE, st.historyStatus)
        assertEquals(CurrencyRateFixAction.NONE, st.rateFixAction)

        scanDownloadScenario(id = 18L)

        assertEquals(CurrencyHistoryStatus.IDLE, st.historyStatus)
        assertFalse(st.historyHomeUnavailable)
        assertEquals(CurrencyRateFixAction.DOWNLOAD_HISTORY, st.rateFixAction)
    }

    @Test
    fun `INV-1b-6 reset and retry stop a running download and drop its status`() {
        enableDownload()
        val actions: List<Pair<String, () -> Unit>> = listOf(
            "reset" to { model.reset() },
            "retry" to { model.retry() }
        )
        for ((name, leave) in actions) {
            scanDownloadScenario()
            val release = CompletableDeferred<Unit>()
            deps.ratesRepo.onHistory = { release.await() }
            // A leaked completion would show as FAILED_OFFLINE.
            deps.ratesRepo.historyOutcome = CurrencyRateHistoryOutcome.Failed(RateHistoryFailureReason.OFFLINE)
            model.onDownloadRateHistory()
            runNow()
            assertEquals(name, CurrencyHistoryStatus.RUNNING, st.historyStatus)

            leave()
            release.complete(Unit)
            settle()

            assertEquals(name, CurrencyHistoryStatus.IDLE, st.historyStatus)
            assertNotEquals(name, ScanStep.REVIEW, st.step)
            assertEquals(name, CurrencyRatesSetting.ON, st.ratesSetting)
        }
    }

    // ---- INV-1b-20 (receipt): an assumed home cannot follow a changed home ----

    @Test
    fun `INV-1b-20 a home change in REVIEW makes an assumed home selection Unset and keeps the fields`() {
        scan(mecReceipt(currency = "", total = 10.0, merchant = "Merchant"))
        assertEquals("EUR", st.editCurrency)
        assertTrue(st.currencyUnverified)
        assertEquals(CurrencyRegionUi.Home, region)
        // A blank OCR currency is not prefilled with an amount (pre-existing); set one explicitly.
        model.updateAmount("10.00")
        settle()
        val merchantBefore = st.editMerchant
        val amountBefore = st.editAmount
        assertEquals("10.00", amountBefore)

        deps.homeFlow.value = "GBP"
        settle()

        assertNull(st.editCurrency)
        assertEquals(CurrencyRegionUi.Unset(CurrencyUnsetReason.NONE_PROPOSED), region)
        assertFalse(st.currencyUnverified)
        assertTrue(st.isCurrencySaveBlocked)
        assertEquals("GBP", st.homeCurrency)
        assertEquals(merchantBefore, st.editMerchant)
        assertEquals(amountBefore, st.editAmount)
        assertEquals("10.00", st.editAmount)
        assertEquals(CurrencyRateFixAction.NONE, st.rateFixAction)

        model.saveExpense()
        settle()
        assertTrue(deps.requests.isEmpty())

        // The user can still choose a currency explicitly.
        model.updateCurrency("USD")
        settle()
        assertEquals("USD", st.editCurrency)
        assertFalse(region is CurrencyRegionUi.Unset)
    }

    @Test
    fun `INV-1b-20 an explicit currency is kept when the home changes`() {
        scan(mecReceipt(id = 17L, currency = "EUR"))
        assertEquals("EUR", st.editCurrency)
        assertFalse(st.currencyUnverified)

        deps.homeFlow.value = "GBP"
        settle()
        assertEquals("EUR", st.editCurrency)
        assertFalse(region is CurrencyRegionUi.Unset)

        scan(mecReceipt(id = 18L, currency = "USD"))
        deps.homeFlow.value = "CHF"
        settle()
        assertEquals("USD", st.editCurrency)
        assertFalse(region is CurrencyRegionUi.Unset)
    }

    // ---- STOP list: a download never touches the receipt lifecycle ----

    @Test
    fun `STOP a download never calls the receipt lifecycle or changes the receipt form`() {
        enableDownload()
        scanDownloadScenario()
        clearMocks(deps.coordinator, deps.links, answers = false)
        val receiptId = st.receiptId
        val merchant = st.editMerchant
        val amount = st.editAmount
        val date = st.editDate

        deps.ratesRepo.onHistory = { deps.store.putRate("USD", 0.85, mecDay(8)) }
        deps.ratesRepo.historyOutcome = storedOutcome()
        model.onDownloadRateHistory()
        settle()
        assertTrue(region is CurrencyRegionUi.Eligible)

        confirmVerified(deps.coordinator, deps.links)
        assertNoReceiptLifecycleTraffic()
        assertEquals(17L, receiptId)
        assertEquals("Merchant", merchant)
        assertEquals(ScanStep.REVIEW, st.step)
        assertEquals(receiptId, st.receiptId)
        assertEquals(merchant, st.editMerchant)
        assertEquals(amount, st.editAmount)
        assertEquals(date, st.editDate)
        assertEquals("USD", st.editCurrency)
        assertNull(st.saveResult)
    }

    @Test
    fun `STOP a failed or cancelled download never calls the receipt lifecycle either`() {
        enableDownload()
        scanDownloadScenario()
        clearMocks(deps.coordinator, deps.links, answers = false)
        val release = CompletableDeferred<Unit>()
        deps.ratesRepo.onHistory = { release.await() }
        deps.ratesRepo.historyOutcome = CurrencyRateHistoryOutcome.Failed(RateHistoryFailureReason.OFFLINE)

        model.onDownloadRateHistory()
        runNow()
        model.onCancelRateHistory()
        release.complete(Unit)
        settle()
        assertEquals(CurrencyHistoryStatus.CANCELLED, st.historyStatus)

        deps.ratesRepo.onHistory = {}
        model.onDownloadRateHistory()
        settle()
        assertEquals(CurrencyHistoryStatus.FAILED_OFFLINE, st.historyStatus)

        confirmVerified(deps.coordinator, deps.links)
        assertNoReceiptLifecycleTraffic()
        assertEquals(ScanStep.REVIEW, st.step)
        assertEquals(17L, st.receiptId)
        assertEquals("Merchant", st.editMerchant)
    }
}
