package com.yourname.expensetracker.ui.screens.receiptscan

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ReceiptItemCategorizationRepository
import com.yourname.expensetracker.data.repository.ReceiptRepository
import com.yourname.expensetracker.domain.ai.model.AiLoadState
import com.yourname.expensetracker.domain.ai.model.ReceiptAssistSuggestion
import com.yourname.expensetracker.domain.ai.model.SuggestedValue
import com.yourname.expensetracker.domain.ai.service.AiArtifactRepository
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.usecase.CategorizeReceiptItemsUseCase
import com.yourname.expensetracker.domain.ai.usecase.SuggestCategoryFallbackUseCase
import com.yourname.expensetracker.domain.ai.usecase.SuggestReceiptExtractionUseCase
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.RateEligibilityEvaluator
import com.yourname.expensetracker.domain.currency.ReadHomeCurrencyState
import com.yourname.expensetracker.domain.currency.ReadHomeFailure
import com.yourname.expensetracker.domain.currency.UserPresentRatePreview
import com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLifecycleCoordinator
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import com.yourname.expensetracker.domain.currency.CurrencyUnsetReason
import com.yourname.expensetracker.ui.components.currency.CurrencyRegionUi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
import timber.log.Timber

/**
 * N-01-1 (the saved receipt amount is the validated edit amount) and N-01-3 (the typed read-time home
 * currency: failure, deadline, first run) on the receipt scan view model. Real values; the home is
 * driven through `observeReadHomeCurrency()`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReceiptScanViewModelHomeReadTest : ViewModelTestUtils() {
    private lateinit var deps: ReceiptScanMecDeps
    private lateinit var model: ReceiptScanViewModel
    private val originalZone: TimeZone = TimeZone.getDefault()
    private val originalLocale: Locale = Locale.getDefault()

    private val warnings = mutableListOf<String>()
    private val throwables = mutableListOf<Throwable>()
    private val tree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (priority >= 5) warnings += message
            if (t != null) throwables += t
        }
    }

    private val st get() = model.state.value

    @Before
    override fun setup() {
        super.setup()
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Timber.plant(tree)
        deps = ReceiptScanMecDeps(testDispatcher, MEC_DATE)
        coEvery { deps.links.checkCanLinkReceipt(any()) } returns true
    }

    @After
    override fun tearDown() {
        try {
            Timber.uproot(tree)
            TimeZone.setDefault(originalZone)
            Locale.setDefault(originalLocale)
        } finally {
            super.tearDown()
        }
    }

    // ---- helpers ----

    private fun settle() = testDispatcher.scheduler.advanceUntilIdle()
    private fun runNow() = testDispatcher.scheduler.runCurrent()
    private fun advance(ms: Long) {
        testDispatcher.scheduler.advanceTimeBy(ms)
        testDispatcher.scheduler.runCurrent()
    }

    private fun avail(code: String, firstRun: Boolean = false) =
        ReadHomeCurrencyState.Available(CurrencyCode(code), firstRun)

    private fun newModel(read: Flow<ReadHomeCurrencyState>) {
        val categories = mockk<CategoryRepository>(relaxed = true)
        every { categories.allCategories } returns flowOf(emptyList())
        val currency = mockk<CurrencySettingsRepository>(relaxed = true)
        every { currency.observeReadHomeCurrency() } returns read
        val aiSettings = mockk<AiSettingsRepository>(relaxed = true)
        every { aiSettings.settings() } returns deps.aiFlow
        val privacyRepo = mockk<PrivacySettingsRepository>()
        every { privacyRepo.observeLoadState() } returns deps.privacyFlow
        model = ReceiptScanViewModel(
            mockk<ReceiptRepository>(relaxed = true), categories, currency, aiSettings,
            SavedStateHandle(), deps.clock, mockk<SuggestReceiptExtractionUseCase>(relaxed = true),
            mockk<SuggestCategoryFallbackUseCase>(relaxed = true),
            mockk<CategorizeReceiptItemsUseCase>(relaxed = true),
            mockk<ReceiptItemCategorizationRepository>(relaxed = true),
            mockk<AiArtifactRepository>(relaxed = true), deps.diagnostics,
            receiptLifecycleCoordinator = deps.coordinator,
            receiptParser = mockk<ReceiptParser>(relaxed = true),
            transactionLifecycleCoordinator = mockk<TransactionLifecycleCoordinator>(relaxed = true),
            receiptLinkService = deps.links,
            merchantNormalizer = mockk<MerchantNormalizer>(relaxed = true),
            hybridClassifier = mockk<HybridExpenseClassifier>(relaxed = true),
            userPresentRatePreview = UserPresentRatePreview(
                RateEligibilityEvaluator(CurrencyConverter(deps.store, deps.clock, testDispatcher)),
                deps.store
            ),
            currencyRatesRepository = deps.ratesRepo,
            privacySettingsRepository = privacyRepo
        )
    }

    private fun scan(receipt: ScannedReceipt) {
        val uri = Uri.parse("content://synthetic/receipt-${receipt.id}.jpg")
        coEvery { deps.coordinator.processReceiptInput(uri, any()) } returns kotlin.Result.success(
            ReceiptLifecycleCoordinator.ReceiptProcessOutcome(receipt, true, null)
        )
        coEvery { deps.links.checkCanLinkReceipt(receipt.id) } returns true
        model.processGalleryImage(uri)
        settle()
    }

    private fun setState(state: ReceiptScanState) {
        val field = ReceiptScanViewModel::class.java.getDeclaredField("_state")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(model) as MutableStateFlow<ReceiptScanState>).value = state
    }

    private fun taxInclusiveEurReceipt() =
        mecReceipt(currency = "EUR", total = 10.005, merchant = "Merchant").also { it.taxInclusive = true }

    // ---- A1 / N-01-1: the saved amount is the validated edit amount ----

    @Test
    fun `N-01-1 tax inclusive 10_005 total with no user edit saves the validated edit amount, not the raw total`() {
        newModel(flowOf(avail("EUR")))
        settle()
        scan(taxInclusiveEurReceipt())
        assertEquals(ScanStep.REVIEW, st.step)
        assertFalse(st.isAmountEditedByUser)
        val seeded = st.editAmount
        assertNotEquals("the raw total is not what the form holds", "10.005", seeded)

        model.saveExpense()
        settle()

        coVerify(exactly = 1) { deps.coordinator.createExpenseAndLinkReceipt(any()) }
        val amount = deps.requests.single().amount
        assertEquals(seeded.toDouble(), amount, 0.0)
        assertNotEquals(10.005, amount, 0.0)
    }

    @Test
    fun `N-01-1 an AI-assist total applied without the edited flag is saved as that value, not the parsed total`() {
        newModel(flowOf(avail("EUR")))
        settle()
        scan(taxInclusiveEurReceipt())
        setState(
            st.copy(
                receiptAssistState = AiLoadState.Ready(
                    ReceiptAssistSuggestion(merchant = SuggestedValue("Merchant"), total = SuggestedValue(12.34))
                )
            )
        )

        model.applyAllReceiptAssist()
        settle()
        assertEquals("12.34", st.editAmount)
        assertFalse(st.isAmountEditedByUser)

        model.saveExpense()
        settle()

        coVerify(exactly = 1) { deps.coordinator.createExpenseAndLinkReceipt(any()) }
        assertEquals(12.34, deps.requests.single().amount, 0.0)
    }

    // ---- A3 / N-01-3: the typed read-time home currency ----

    private fun reviewWithoutCurrency() = ReceiptScanState(
        step = ScanStep.REVIEW, receiptId = 17L, editMerchant = "Merchant", editAmount = "10",
        editCurrency = null, editDate = MEC_DATE
    )

    private fun assertSaveBlocked() {
        setState(reviewWithoutCurrency())
        model.saveExpense()
        settle()
        coVerify(exactly = 0) { deps.coordinator.createExpenseAndLinkReceipt(any()) }
        assertTrue(deps.requests.isEmpty())
    }

    @Test
    fun `N-01-3 a read that throws IOException does not crash, keeps home null and blocks save with a bounded log`() {
        newModel(flow { throw IOException("synthetic private path /data/918") })
        settle()

        assertNull(st.homeCurrency)
        assertSaveBlocked()
        assertEquals(1, warnings.size)
        assertTrue(warnings.single(), warnings.single().endsWith("reason=READ_ERROR"))
        assertFalse(warnings.single().contains("918"))
        assertFalse(warnings.single().contains("private"))
        assertTrue("no throwable is logged", throwables.isEmpty())
    }

    @Test
    fun `N-01-3 a flow that never emits leaves home null, with no change before 5s and a TIMEOUT log at 5s`() {
        newModel(flow<ReadHomeCurrencyState> {
            emit(ReadHomeCurrencyState.Loading)
            awaitCancellation()
        })
        runNow()
        val baselineKey = st.previewAnnounceKey
        assertNull(st.homeCurrency)

        advance(4_999)
        assertEquals("no change before the deadline", baselineKey, st.previewAnnounceKey)
        assertTrue(warnings.isEmpty())

        advance(1)
        assertNull(st.homeCurrency)
        assertEquals(baselineKey + 1, st.previewAnnounceKey)
        assertEquals(1, warnings.size)
        assertTrue(warnings.single(), warnings.single().endsWith("reason=TIMEOUT"))
        assertSaveBlocked()
    }

    @Test
    fun `N-01-3 Available within the deadline cancels the timeout so no late null arrives`() {
        newModel(flow {
            delay(3_000)
            emit(avail("USD"))
            awaitCancellation()
        })
        runNow()
        assertNull(st.homeCurrency)

        advance(3_000)
        assertEquals("USD", st.homeCurrency)
        val keyAfterAvailable = st.previewAnnounceKey

        advance(10_000)
        assertEquals("USD", st.homeCurrency)
        assertEquals(keyAfterAvailable, st.previewAnnounceKey)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `N-01-3 an invalid stored code leaves home null and logs only INVALID_CODE`() {
        newModel(flowOf(ReadHomeCurrencyState.Unavailable(ReadHomeFailure.INVALID_CODE)))
        runNow()

        assertNull(st.homeCurrency)
        assertEquals(1, warnings.size)
        assertTrue(warnings.single(), warnings.single().endsWith("reason=INVALID_CODE"))
        assertSaveBlocked()
    }

    @Test
    fun `N-01-3 an unset stored value is the EUR first-run home`() {
        newModel(flowOf(avail("EUR", firstRun = true)))
        settle()

        assertEquals("EUR", st.homeCurrency)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `N-01-3 a CancellationException from the read propagates and is neither logged nor turned into an error`() {
        newModel(flow<ReadHomeCurrencyState> { throw kotlinx.coroutines.CancellationException("synthetic") })
        runNow()
        advance(10_000)

        assertNull(st.homeCurrency)
        assertTrue("cancellation is not a read error: $warnings", warnings.isEmpty())
    }

    // ---- F-entry: a null home is "home unknown" (never a resolution) and a dead collection restarts ----

    private val homeSignal = MutableSharedFlow<ReadHomeCurrencyState>(replay = 1, extraBufferCapacity = 8)
    private val subscriptions = AtomicInteger(0)

    private fun emitHome(state: ReadHomeCurrencyState) {
        homeSignal.tryEmit(state)
        runNow()
    }

    private fun readFails() = emitHome(ReadHomeCurrencyState.Unavailable(ReadHomeFailure.READ_ERROR))

    /** Scans without advancing virtual time (so a pending 5s home deadline is not fired by the helper). */
    private fun scanNow(receipt: ScannedReceipt) {
        val uri = stubReceipt(receipt)
        model.processGalleryImage(uri)
        runNow()
    }

    private fun stubReceipt(receipt: ScannedReceipt): Uri {
        val uri = Uri.parse("content://synthetic/receipt-${receipt.id}.jpg")
        coEvery { deps.coordinator.processReceiptInput(uri, any()) } returns kotlin.Result.success(
            ReceiptLifecycleCoordinator.ReceiptProcessOutcome(receipt, true, null)
        )
        coEvery { deps.links.checkCanLinkReceipt(receipt.id) } returns true
        return uri
    }

    /** A Ready merchant suggestion: with a blank merchant it is the one field quick save may fill. */
    private fun withMerchantSuggestion() = setState(
        st.copy(
            receiptAssistState = AiLoadState.Ready(
                ReceiptAssistSuggestion(merchant = SuggestedValue("Lidl"), total = SuggestedValue(50.0))
            )
        )
    )

    /**
     * Home never emits before the 5s deadline. A USD 50 proposal arrives before (or after) the timeout null.
     * While home is null: no currency, no quick save, no save.
     */
    private fun proposalAcrossTimeout(scanAfterNull: Boolean, merchant: String?) {
        deps.store.putRate("USD", 0.9, mecDay(14))
        newModel(homeSignal)
        runNow()
        if (!scanAfterNull) {
            scanNow(mecReceipt(currency = "USD", total = 50.0, merchant = merchant))
            assertEquals(ScanStep.REVIEW, st.step)
            assertNull(st.editCurrency)
        }
        advance(5_000)
        assertNull(st.homeCurrency)
        if (scanAfterNull) {
            scanNow(mecReceipt(currency = "USD", total = 50.0, merchant = merchant))
            assertEquals(ScanStep.REVIEW, st.step)
        }
        assertNull("no currency is invented while home is null", st.editCurrency)
        assertNull(st.homeCurrency)
        if (merchant == null) {
            withMerchantSuggestion()
            assertFalse(model.canOfferReceiptQuickSave())
            model.requestReceiptQuickSaveConfirmation()
            assertNull(st.quickSavePreview)
        } else {
            model.saveExpense()
            settle()
            assertTrue("no save while home is null", deps.requests.isEmpty())
        }
    }

    @Test
    fun `N-01-3 USD 50 proposed before the timeout null then real EUR resolves as late home and saves USD 50`() {
        proposalAcrossTimeout(scanAfterNull = false, merchant = "Shop")

        emitHome(avail("EUR"))
        settle()

        assertEquals("EUR", st.homeCurrency)
        assertEquals("USD", st.editCurrency)
        assertFalse("an OCR-proposed currency is verified", st.currencyUnverified)
        model.saveExpense()
        settle()
        val request = deps.requests.single()
        assertEquals("USD", request.currency)
        assertEquals(50.0, request.amount, 0.0)
    }

    @Test
    fun `N-01-3 USD 50 proposed after the timeout null then real EUR resolves as late home and saves USD 50`() {
        proposalAcrossTimeout(scanAfterNull = true, merchant = "Shop")

        emitHome(avail("EUR"))
        settle()

        assertEquals("EUR", st.homeCurrency)
        assertEquals("USD", st.editCurrency)
        assertFalse(st.currencyUnverified)
        model.saveExpense()
        settle()
        val request = deps.requests.single()
        assertEquals("USD", request.currency)
        assertEquals(50.0, request.amount, 0.0)
    }

    @Test
    fun `N-01-3 quick save stays unavailable across the timeout null and previews USD 50 only once home is real`() {
        proposalAcrossTimeout(scanAfterNull = false, merchant = null)

        emitHome(avail("EUR"))
        settle()
        assertEquals("USD", st.editCurrency)
        assertFalse(st.currencyUnverified)
        assertTrue(model.canOfferReceiptQuickSave())
        model.requestReceiptQuickSaveConfirmation()
        assertEquals(50.0, st.quickSavePreview!!.amount, 0.0)
        model.confirmReceiptQuickSave()
        settle()

        val request = deps.requests.single()
        assertEquals("USD", request.currency)
        assertEquals(50.0, request.amount, 0.0)
    }

    @Test
    fun `N-01-3 quick save stays unavailable when the proposal arrives after the timeout null`() {
        proposalAcrossTimeout(scanAfterNull = true, merchant = null)

        emitHome(avail("EUR"))
        settle()
        assertEquals("USD", st.editCurrency)
        assertTrue(model.canOfferReceiptQuickSave())
        model.requestReceiptQuickSaveConfirmation()
        model.confirmReceiptQuickSave()
        settle()
        assertEquals("USD", deps.requests.single().currency)
    }

    @Test
    fun `N-01-3 R24c a blank-currency OCR token keeps its unverified flag across null then EUR`() {
        newModel(homeSignal)
        runNow()
        scanNow(mecReceipt(currency = "", total = 50.0, merchant = "Shop"))
        advance(5_000)
        assertNull(st.homeCurrency)
        assertNull(st.editCurrency)

        emitHome(avail("EUR"))
        settle()

        assertEquals("EUR", st.homeCurrency)
        assertEquals("EUR", st.editCurrency)
        assertTrue("home is only assumed for a blank token (R24c)", st.currencyUnverified)
        setState(st.copy(editAmount = "50.00", editMerchant = ""))
        withMerchantSuggestion()
        assertFalse("an unverified currency is never quick-saved", model.canOfferReceiptQuickSave())
    }

    @Test
    fun `N-01-3 R24c a blank-currency token scanned after the null stays unverified when EUR arrives`() {
        newModel(homeSignal)
        runNow()
        advance(5_000)
        scanNow(mecReceipt(currency = "", total = 50.0, merchant = "Shop"))
        assertNull(st.editCurrency)

        emitHome(avail("EUR"))
        settle()

        assertEquals("EUR", st.editCurrency)
        assertTrue(st.currencyUnverified)
    }

    private data class Outcome(
        val editCurrency: String?,
        val unverified: Boolean,
        val region: CurrencyRegionUi,
        val home: String?,
        val options: List<String>,
        val amount: String,
        val merchant: String
    )

    private fun outcomeAfter(receiptCurrency: String, homes: List<ReadHomeCurrencyState>): Outcome {
        homeSignal.resetReplayCache()
        homeSignal.tryEmit(homes.first())
        newModel(homeSignal)
        settle()
        scan(mecReceipt(currency = receiptCurrency, total = 50.0, merchant = "Shop"))
        homes.drop(1).forEach { emitHome(it); settle() }
        return Outcome(
            st.editCurrency, st.currencyUnverified, st.currencyRegion, st.homeCurrency,
            st.currencyOptions, st.editAmount, st.editMerchant
        )
    }

    private val readError = ReadHomeCurrencyState.Unavailable(ReadHomeFailure.READ_ERROR)

    @Test
    fun `N-01-3 EUR then null then EUR leaves an assumed-home review identical to no null`() {
        val baseline = outcomeAfter("", listOf(avail("EUR")))
        val viaNull = outcomeAfter("", listOf(avail("EUR"), readError, avail("EUR")))

        assertEquals("EUR", baseline.editCurrency)
        assertTrue(baseline.unverified)
        assertEquals(baseline, viaNull)
    }

    @Test
    fun `N-01-3 EUR then null then EUR leaves an explicit USD proposal identical to no null`() {
        val baseline = outcomeAfter("USD", listOf(avail("EUR")))
        val viaNull = outcomeAfter("USD", listOf(avail("EUR"), readError, avail("EUR")))

        assertEquals("USD", baseline.editCurrency)
        assertEquals(baseline, viaNull)
    }

    @Test
    fun `N-01-3 the home is null while the read is unavailable and the edit state is untouched`() {
        newModel(homeSignal)
        homeSignal.tryEmit(avail("EUR"))
        settle()
        scan(mecReceipt(currency = "", total = 50.0, merchant = "Shop"))
        model.updateMerchant("Edited")
        val before = st

        readFails()

        assertNull(st.homeCurrency)
        assertEquals(before.editCurrency, st.editCurrency)
        assertEquals(before.currencyUnverified, st.currencyUnverified)
        assertEquals(before.editAmount, st.editAmount)
        assertEquals("Edited", st.editMerchant)
        assertEquals(ScanStep.REVIEW, st.step)
    }

    @Test
    fun `N-01-3 EUR then null then USD follows the direct EUR to USD change for an assumed home including assumedHomeLost`() {
        val direct = outcomeAfter("", listOf(avail("EUR"), avail("USD")))
        val viaNull = outcomeAfter("", listOf(avail("EUR"), readError, avail("USD")))

        assertNull("the assumed home cannot follow the change", direct.editCurrency)
        assertEquals(CurrencyRegionUi.Unset(CurrencyUnsetReason.NONE_PROPOSED), direct.region)
        assertFalse(direct.unverified)
        assertEquals("USD", direct.home)
        assertEquals(direct, viaNull)
    }

    @Test
    fun `N-01-3 EUR then null then GBP keeps an explicit USD proposal exactly like the direct change`() {
        val direct = outcomeAfter("USD", listOf(avail("EUR"), avail("GBP")))
        val viaNull = outcomeAfter("USD", listOf(avail("EUR"), readError, avail("GBP")))

        assertEquals("USD", direct.editCurrency)
        assertEquals(direct, viaNull)
    }

    // ---- F-entry controls: real home behaviour is unchanged ----

    @Test
    fun `N-01-3 control a real home with a USD proposal resolves at once and saves USD 50`() {
        deps.store.putRate("USD", 0.9, mecDay(14))
        newModel(homeSignal)
        homeSignal.tryEmit(avail("EUR"))
        settle()

        scan(mecReceipt(currency = "USD", total = 50.0, merchant = "Shop"))

        assertEquals("EUR", st.homeCurrency)
        assertEquals("USD", st.editCurrency)
        assertFalse(st.currencyUnverified)
        model.saveExpense()
        settle()
        assertEquals("USD", deps.requests.single().currency)
        assertEquals(50.0, deps.requests.single().amount, 0.0)
    }

    @Test
    fun `N-01-3 control a home change with an assumed-home review still clears the currency`() {
        newModel(homeSignal)
        homeSignal.tryEmit(avail("EUR"))
        settle()
        scan(mecReceipt(currency = "", total = 50.0, merchant = "Shop"))
        assertEquals("EUR", st.editCurrency)

        emitHome(avail("GBP"))
        settle()

        assertEquals("GBP", st.homeCurrency)
        assertNull(st.editCurrency)
        assertFalse(st.currencyUnverified)
        assertEquals(CurrencyRegionUi.Unset(CurrencyUnsetReason.NONE_PROPOSED), st.currencyRegion)
    }

    @Test
    fun `N-01-3 control a home change with an explicit USD proposal keeps USD`() {
        newModel(homeSignal)
        homeSignal.tryEmit(avail("EUR"))
        settle()
        scan(mecReceipt(currency = "USD", total = 50.0, merchant = "Shop"))

        emitHome(avail("GBP"))
        settle()

        assertEquals("GBP", st.homeCurrency)
        assertEquals("USD", st.editCurrency)
    }

    // ---- F-entry restart: a dead collection restarts once, never duplicated ----

    /** First subscription fails (READ_ERROR ends the collection); later ones follow [homeSignal] (EUR). */
    private fun newModelWithEndedRead() {
        homeSignal.resetReplayCache()
        homeSignal.tryEmit(avail("EUR"))
        subscriptions.set(0)
        newModel(flow {
            if (subscriptions.incrementAndGet() == 1) throw IOException("synthetic private path /data/918")
            emitAll(homeSignal)
        })
        settle()
        assertNull(st.homeCurrency)
        assertEquals(1, subscriptions.get())
        assertEquals("the ended collection holds no subscriber", 0, homeSignal.subscriptionCount.value)
    }

    private fun assertRestartedOnce() {
        assertEquals("EUR", st.homeCurrency)
        assertEquals("one restart, one new subscription", 2, subscriptions.get())
        assertEquals("exactly one live collector", 1, homeSignal.subscriptionCount.value)
    }

    @Test
    fun `N-01-3 reset after READ_ERROR restarts the home collection once and home resolves`() {
        newModelWithEndedRead()

        model.reset()
        settle()

        assertRestartedOnce()
        assertEquals("EUR", st.editCurrency)
    }

    @Test
    fun `N-01-3 retry after READ_ERROR restarts the home collection once and home resolves`() {
        newModelWithEndedRead()

        model.retry()
        settle()

        assertRestartedOnce()
        assertEquals("EUR", st.editCurrency)
    }

    @Test
    fun `N-01-3 processImageUri after READ_ERROR restarts the home collection once and the proposal resolves`() {
        newModelWithEndedRead()

        model.processGalleryImage(stubReceipt(mecReceipt(currency = "USD", total = 50.0, merchant = "Shop")))
        settle()

        assertRestartedOnce()
        assertEquals(ScanStep.REVIEW, st.step)
        assertEquals("USD", st.editCurrency)
        assertFalse(st.currencyUnverified)
    }

    @Test
    fun `N-01-3 repeated reset retry and scan after READ_ERROR create no duplicate collector`() {
        newModelWithEndedRead()

        model.reset()
        model.retry()
        model.reset()
        model.processGalleryImage(stubReceipt(mecReceipt(currency = "USD", total = 50.0, merchant = "Shop")))
        model.reset()
        settle()
        assertRestartedOnce()

        model.reset()
        model.retry()
        settle()
        assertRestartedOnce()
    }

    @Test
    fun `N-01-3 reset retry and scan do not restart when home is known`() {
        subscriptions.set(0)
        newModel(flow {
            subscriptions.incrementAndGet()
            emit(avail("EUR"))
            awaitCancellation()
        })
        settle()
        assertEquals("EUR", st.homeCurrency)

        model.reset()
        model.retry()
        model.processGalleryImage(stubReceipt(mecReceipt(currency = "USD", total = 50.0, merchant = "Shop")))
        settle()

        assertEquals(1, subscriptions.get())
        assertEquals("EUR", st.homeCurrency)
    }

    @Test
    fun `N-01-3 reset retry and scan do not restart while the collection is still active`() {
        subscriptions.set(0)
        newModel(flow<ReadHomeCurrencyState> {
            subscriptions.incrementAndGet()
            emit(ReadHomeCurrencyState.Loading)
            awaitCancellation()
        })
        runNow()

        model.reset()
        model.retry()
        model.processGalleryImage(stubReceipt(mecReceipt(currency = "USD", total = 50.0, merchant = "Shop")))
        runNow()
        assertEquals("pending home, active job", 1, subscriptions.get())

        advance(5_000)
        assertNull(st.homeCurrency)
        model.reset()
        model.retry()
        model.processGalleryImage(stubReceipt(mecReceipt(currency = "USD", total = 50.0, merchant = "Shop")))
        runNow()

        assertEquals("a timeout null leaves the job active, so no restart", 1, subscriptions.get())
    }

    // ---- READ_ERROR during REVIEW with a USD proposal pending: opening review or tapping Save retries ----

    private val failRead = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Review of a USD 50 receipt with home unknown; the read then fails (READ_ERROR) and its job ends. */
    private fun reviewWithReadErrorAndUsdProposalPending() {
        homeSignal.resetReplayCache()
        subscriptions.set(0)
        deps.store.putRate("USD", 0.9, mecDay(14))
        newModel(flow {
            if (subscriptions.incrementAndGet() == 1) {
                emit(ReadHomeCurrencyState.Loading)
                failRead.first()
                throw IOException("synthetic private path /data/918")
            }
            emitAll(homeSignal)
        })
        runNow()
        scanNow(mecReceipt(currency = "USD", total = 50.0, merchant = "Shop"))
        assertEquals(ScanStep.REVIEW, st.step)
        assertNull(st.editCurrency)
        setState(st.copy(editMerchant = "Edited shop"))
        failRead.tryEmit(Unit)
        runNow()
        assertEquals("the failed read ended its collection", 1, subscriptions.get())
        assertEquals(0, homeSignal.subscriptionCount.value)
    }

    @Test
    fun `N-01-3 Save after READ_ERROR during REVIEW retries once keeps edits and saves USD 50 once EUR arrives`() {
        reviewWithReadErrorAndUsdProposalPending()

        model.saveExpense()
        model.saveExpense()
        runNow()

        assertEquals("the Save tap restarted the read once, no duplicate", 2, subscriptions.get())
        assertEquals(1, homeSignal.subscriptionCount.value)
        assertTrue("nothing saves while home is unknown", deps.requests.isEmpty())
        assertNull(st.editCurrency)
        assertEquals("Edited shop", st.editMerchant)

        emitHome(avail("EUR"))
        settle()

        assertEquals("EUR", st.homeCurrency)
        assertEquals("USD", st.editCurrency)
        assertEquals("Edited shop", st.editMerchant)
        model.saveExpense()
        settle()
        val request = deps.requests.single()
        assertEquals("USD", request.currency)
        assertEquals(50.0, request.amount, 0.0)
        assertEquals(2, subscriptions.get())
    }

    @Test
    fun `N-01-3 onReviewOpened after READ_ERROR during REVIEW restarts the read once and the proposal resolves`() {
        reviewWithReadErrorAndUsdProposalPending()

        model.onReviewOpened()
        model.onReviewOpened()
        runNow()
        assertEquals(2, subscriptions.get())
        assertEquals(1, homeSignal.subscriptionCount.value)
        assertEquals("Edited shop", st.editMerchant)

        emitHome(avail("EUR"))
        settle()

        assertEquals("USD", st.editCurrency)
        model.saveExpense()
        settle()
        assertEquals("USD", deps.requests.single().currency)
    }

    @Test
    fun `N-01-3 quick save reason says the currency is not loaded while home is unknown`() {
        reviewWithReadErrorAndUsdProposalPending()
        setState(st.copy(receiptQuickSaveEnabled = true))

        assertNull(st.homeCurrency)
        assertEquals("Currency is not loaded yet. Please wait.", model.quickSaveUnavailableReason())
    }
}
