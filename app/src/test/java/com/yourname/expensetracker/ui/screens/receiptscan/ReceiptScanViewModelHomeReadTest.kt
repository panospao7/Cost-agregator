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
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
}
