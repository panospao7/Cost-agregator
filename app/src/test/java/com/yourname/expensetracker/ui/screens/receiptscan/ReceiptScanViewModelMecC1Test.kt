package com.yourname.expensetracker.ui.screens.receiptscan

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ReceiptItemCategorizationRepository
import com.yourname.expensetracker.data.repository.ReceiptRepository
import com.yourname.expensetracker.domain.ai.model.AiLoadState
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.ReceiptAssistSuggestion
import com.yourname.expensetracker.domain.ai.model.SuggestedValue
import com.yourname.expensetracker.domain.ai.service.AiArtifactRepository
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.usecase.CategorizeReceiptItemsUseCase
import com.yourname.expensetracker.domain.ai.usecase.SuggestCategoryFallbackUseCase
import com.yourname.expensetracker.domain.ai.usecase.SuggestReceiptExtractionUseCase
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencyRateHistoryOutcome
import com.yourname.expensetracker.domain.currency.CurrencyRateRefreshOutcome
import com.yourname.expensetracker.domain.currency.CurrencyRatesRepository
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.DomainExchangeRate
import com.yourname.expensetracker.domain.currency.ExchangeRateStore
import com.yourname.expensetracker.domain.currency.RateEligibilityEvaluator
import com.yourname.expensetracker.domain.currency.RateSourceLabel
import com.yourname.expensetracker.domain.currency.CurrencyUnsetReason
import com.yourname.expensetracker.domain.currency.UserPresentRatePreview
import com.yourname.expensetracker.domain.debug.AiRuntimeDiagnostics
import com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.privacy.PrivacySettingsLoadState
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLifecycleCoordinator
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLinkService
import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.transaction.RateRefusalReason
import com.yourname.expensetracker.domain.transaction.UserPresentRateRequirement
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.ui.components.currency.CurrencyAmountInvalidKind
import com.yourname.expensetracker.ui.components.currency.CurrencyBlockOrigin
import com.yourname.expensetracker.ui.components.currency.CurrencyBlockVariant
import com.yourname.expensetracker.ui.components.currency.CurrencyOpenSettingRequest
import com.yourname.expensetracker.ui.components.currency.CurrencyRatesSetting
import com.yourname.expensetracker.ui.components.currency.CurrencyRefreshStatus
import com.yourname.expensetracker.ui.components.currency.CurrencyRegionUi
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import timber.log.Timber
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.TimeZone

// ---------------------------------------------------------------------------------------------
// MEC C1 test fakes: a real UserPresentRatePreview over an in-memory rate store (no value-class mock
// matchers), a counting rates repository, and the shared VM dependency set. Synthetic data only.
// ---------------------------------------------------------------------------------------------

internal class ReceiptScanRateStoreFake : ExchangeRateStore {
    val rows = mutableListOf<DomainExchangeRate>()
    val asOfCalls = mutableListOf<Triple<String, String, Long>>()

    /** When set, every lookup suspends until it completes (a slow preview). */
    var gate: CompletableDeferred<Unit>? = null
    var latencyMs: (String, String, Long) -> Long = { _, _, _ -> 0L }
    var failure: Throwable? = null

    override suspend fun getRate(fromCurrency: String, toCurrency: String): DomainExchangeRate? = null
    // MEC 1b: the newest stored direct pair (any date), the RateRemedyPolicy input; null when none is stored.
    override suspend fun getLatestRateForPair(fromCurrency: String, toCurrency: String): DomainExchangeRate? =
        rows
            .filter { it.fromCurrency == fromCurrency && it.toCurrency == toCurrency && it.validDate != null }
            .maxByOrNull { it.validDate ?: Long.MIN_VALUE }

    override suspend fun getRateAsOf(fromCurrency: String, toCurrency: String, atMillis: Long): DomainExchangeRate? {
        asOfCalls += Triple(fromCurrency, toCurrency, atMillis)
        gate?.await()
        val wait = latencyMs(fromCurrency, toCurrency, atMillis)
        if (wait > 0L) delay(wait)
        failure?.let { throw it }
        return rows
            .filter { it.fromCurrency == fromCurrency && it.toCurrency == toCurrency }
            .filter { row -> row.validDate?.let { it <= atMillis } ?: false }
            .maxByOrNull { it.validDate ?: Long.MIN_VALUE }
    }

    override suspend fun insertOrUpdate(rate: DomainExchangeRate) { rows += rate }
    override suspend fun insertOrUpdateAll(rates: List<DomainExchangeRate>) { rows += rates }
    override fun getRatesToCurrency(targetCurrency: String) = flowOf(emptyList<DomainExchangeRate>())
    override suspend fun getLatestRate(): DomainExchangeRate? = null
    override suspend fun deleteOldRates(olderThan: Long) = Unit
}

internal class ReceiptScanRatesRepositoryFake : CurrencyRatesRepository {
    val calls = mutableListOf<String>()
    var outcome: suspend (String) -> CurrencyRateRefreshOutcome = { CurrencyRateRefreshOutcome.Refreshed(0) }

    override suspend fun refresh(homeCurrency: String): CurrencyRateRefreshOutcome {
        calls += homeCurrency
        return outcome(homeCurrency)
    }

    val historyCalls = mutableListOf<String>()
    var historyOutcome: CurrencyRateHistoryOutcome = CurrencyRateHistoryOutcome.DisabledByPrivacy

    /** MEC 1b seam: runs inside the download before the outcome is returned (a gate, or storing a rate). */
    var onHistory: suspend () -> Unit = {}

    override suspend fun downloadRecentHistory(homeCurrency: String): CurrencyRateHistoryOutcome {
        historyCalls += homeCurrency
        onHistory()
        return historyOutcome
    }
}

internal class ReceiptScanMecDeps(dispatcher: CoroutineDispatcher, nowMs: Long) {
    val coordinator = mockk<ReceiptLifecycleCoordinator>()
    val links = mockk<ReceiptLinkService>()
    val store = ReceiptScanRateStoreFake()
    val ratesRepo = ReceiptScanRatesRepositoryFake()
    val privacyFlow = MutableStateFlow<PrivacySettingsLoadState>(PrivacySettingsLoadState.Loaded(PrivacySettings()))
    val homeFlow = MutableStateFlow("EUR")

    /** Review F2 seam: when set, [createViewModel] uses this flow as the home currency source (e.g. one that emits nothing at first). */
    var homeSource: Flow<String>? = null
    val aiFlow = MutableStateFlow(AiSettings(aiEnabled = true, receiptQuickSaveEnabled = true))
    val clock = mockk<TimeProvider>(relaxed = true).also { every { it.now() } returns nowMs }
    val diagnostics = spyk(AiRuntimeDiagnostics(clock))

    /** Every request that reached the coordinator, in order. */
    val requests = mutableListOf<CreateExpenseRequest>()
    var saveOutcome: (CreateExpenseRequest) -> kotlin.Result<Long> = { kotlin.Result.success(900L) }

    private val privacyRepo = mockk<PrivacySettingsRepository>().also { every { it.observeLoadState() } returns privacyFlow }
    private val converter = CurrencyConverter(store, clock, dispatcher)

    init {
        coEvery { coordinator.createExpenseAndLinkReceipt(any()) } coAnswers {
            val request = firstArg<CreateExpenseRequest>()
            requests += request
            saveOutcome(request)
        }
    }

    fun createViewModel(): ReceiptScanViewModel {
        val categories = mockk<CategoryRepository>(relaxed = true)
        every { categories.allCategories } returns flowOf(emptyList())
        val currency = mockk<CurrencySettingsRepository>(relaxed = true)
        every { currency.homeCurrency() } returns (homeSource ?: homeFlow)
        val aiSettings = mockk<AiSettingsRepository>(relaxed = true)
        every { aiSettings.settings() } returns aiFlow
        return ReceiptScanViewModel(
            mockk<ReceiptRepository>(relaxed = true), categories, currency, aiSettings,
            SavedStateHandle(), clock, mockk<SuggestReceiptExtractionUseCase>(relaxed = true),
            mockk<SuggestCategoryFallbackUseCase>(relaxed = true),
            mockk<CategorizeReceiptItemsUseCase>(relaxed = true),
            mockk<ReceiptItemCategorizationRepository>(relaxed = true),
            mockk<AiArtifactRepository>(relaxed = true), diagnostics,
            receiptLifecycleCoordinator = coordinator,
            receiptParser = mockk<ReceiptParser>(relaxed = true),
            transactionLifecycleCoordinator = mockk<TransactionLifecycleCoordinator>(relaxed = true),
            receiptLinkService = links,
            merchantNormalizer = mockk<MerchantNormalizer>(relaxed = true),
            hybridClassifier = mockk<HybridExpenseClassifier>(relaxed = true),
            userPresentRatePreview = UserPresentRatePreview(RateEligibilityEvaluator(converter), store),
            currencyRatesRepository = ratesRepo,
            privacySettingsRepository = privacyRepo
        )
    }
}

internal const val MEC_DATE = 1_700_000_000_000L // 2023-11-14T22:13:20Z
internal const val MEC_DAY_MS = 86_400_000L
internal fun mecDay(day: Int): LocalDate = LocalDate.of(2023, 11, day)
internal fun mecUtcMidnight(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

internal fun ReceiptScanRateStoreFake.putRate(from: String, rate: Double, date: LocalDate, source: String = "ecb") {
    rows += DomainExchangeRate(
        fromCurrency = from, toCurrency = "EUR", rate = rate, lastUpdated = 0L, source = source,
        validDate = mecUtcMidnight(date)
    )
}

internal fun mecReceipt(
    id: Long = 17L,
    status: String = "PARSED",
    currency: String = "USD",
    total: Double? = 10.0,
    merchant: String? = "Merchant",
    date: Long = MEC_DATE
) = ScannedReceipt(
    id = id, imagePath = null, rawOcrText = "synthetic receipt text", parsedTotal = total,
    parsedMerchant = merchant, parsedDate = date, parsedItems = null, parsedTaxAmount = null,
    currency = currency, confidence = 0.8f, processingStatus = status
)

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReceiptScanViewModelMecC1Test : ViewModelTestUtils() {
    private lateinit var deps: ReceiptScanMecDeps
    private lateinit var model: ReceiptScanViewModel
    private val originalZone: TimeZone = TimeZone.getDefault()
    private val originalLocale: Locale = Locale.getDefault()

    private data class Line(val priority: Int, val message: String, val throwable: Throwable?)
    private val lines = mutableListOf<Line>()
    private val tree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            lines += Line(priority, message, t)
        }
    }

    private val athens = ZoneId.of("Europe/Athens")
    private val st get() = model.state.value
    private val region get() = st.currencyRegion

    @Before
    override fun setup() {
        super.setup()
        Locale.setDefault(Locale.US)
        useZone("UTC")
        Timber.plant(tree)
        deps = ReceiptScanMecDeps(testDispatcher, MEC_DATE)
        coEvery { deps.links.checkCanLinkReceipt(any()) } returns true
        model = deps.createViewModel()
        settle()
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

    private fun useZone(id: String) = TimeZone.setDefault(TimeZone.getTimeZone(id))
    private fun settle() = testDispatcher.scheduler.advanceUntilIdle()
    private fun runNow() = testDispatcher.scheduler.runCurrent()
    private fun advance(ms: Long) {
        testDispatcher.scheduler.advanceTimeBy(ms)
        testDispatcher.scheduler.runCurrent()
    }

    private fun stubScan(uri: Uri, receipt: ScannedReceipt) {
        coEvery { deps.coordinator.processReceiptInput(uri, any()) } returns kotlin.Result.success(
            ReceiptLifecycleCoordinator.ReceiptProcessOutcome(receipt, true, null)
        )
        coEvery { deps.links.checkCanLinkReceipt(receipt.id) } returns true
    }

    private fun scan(receipt: ScannedReceipt, name: String = "receipt") {
        val uri = Uri.parse("content://synthetic/$name-${receipt.id}.jpg")
        stubScan(uri, receipt)
        model.processGalleryImage(uri)
        settle()
    }

    private fun setState(state: ReceiptScanState) {
        val field = ReceiptScanViewModel::class.java.getDeclaredField("_state")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(model) as MutableStateFlow<ReceiptScanState>).value = state
    }

    private fun refuse(reason: RateRefusalReason, latest: LocalDate? = null) {
        deps.saveOutcome = { kotlin.Result.failure(ReceiptLifecycleCoordinator.RateNotEligibleException(reason, latest)) }
    }

    private fun reviewState(currency: String? = "USD", amount: String = "10") = ReceiptScanState(
        step = ScanStep.REVIEW, receiptId = 17L, editMerchant = "Merchant", editAmount = amount,
        editCurrency = currency, editDate = MEC_DATE
    )

    private fun quickSaveState(currency: String = "USD") = reviewState(currency, amount = "").copy(
        editMerchant = "", receiptQuickSaveEnabled = true, ocrConfidence = 0.8f,
        receiptAssistState = AiLoadState.Ready(
            ReceiptAssistSuggestion(merchant = SuggestedValue("Lidl"), total = SuggestedValue(12.34))
        )
    )

    // ---- AE-23 / AE-24 / AE-22 / AE-25 ----

    @Test
    fun `C1 AE-23 a USD receipt previews the rate and saves with the flag`() {
        deps.store.putRate("USD", 0.9, mecDay(14))
        scan(mecReceipt(currency = "USD", total = 10.0))

        assertEquals(ScanStep.REVIEW, st.step)
        assertEquals("USD", st.editCurrency)
        val eligible = region as CurrencyRegionUi.Eligible
        assertEquals(mecDay(14), eligible.rateDate)
        assertEquals(RateSourceLabel.ECB, eligible.sourceLabel)
        assertFalse(eligible.amountUnavailable)
        assertTrue(eligible.formattedAmountInHome!!.contains("9.00"))
        assertFalse(st.isCurrencySaveBlocked)

        model.saveExpense()
        settle()

        assertEquals(SaveReceiptResult.Success(900L), st.saveResult)
        assertEquals(ScanStep.DONE, st.step)
        val request = deps.requests.single()
        assertEquals("USD", request.currency)
        assertEquals(10.0, request.amount, 0.0)
        assertNotNull(request.userPresentRate)
    }

    @Test
    fun `C1 AE-24 NO_RATE keeps the review and the form then EUR saves`() {
        scan(mecReceipt(currency = "USD", total = 10.0, merchant = "Merchant"))
        assertEquals(CurrencyRegionUi.Blocked(CurrencyBlockVariant.NONE, null, CurrencyBlockOrigin.PREVIEW), region)
        deps.saveOutcome = { request ->
            if (request.currency == "USD") {
                kotlin.Result.failure(ReceiptLifecycleCoordinator.RateNotEligibleException(RateRefusalReason.NO_RATE, null))
            } else {
                kotlin.Result.success(901L)
            }
        }

        model.saveExpense()
        settle()

        assertEquals(SaveReceiptResult.RateNotEligible, st.saveResult)
        assertEquals(ScanStep.REVIEW, st.step)
        assertFalse(st.isSaving)
        assertNull(st.errorMessage)
        assertNull(st.quickSavePreview)
        assertEquals("Merchant", st.editMerchant)
        assertEquals("10.00", st.editAmount)
        assertEquals("USD", st.editCurrency)
        assertEquals(MEC_DATE, st.editDate)
        assertEquals(CurrencyRegionUi.Blocked(CurrencyBlockVariant.NONE, null, CurrencyBlockOrigin.SAVE), region)
        assertTrue(st.isCurrencySaveBlocked)

        model.updateCurrency("EUR")
        settle()
        assertNull(st.saveResult)
        assertEquals(CurrencyRegionUi.Home, region)

        model.saveExpense()
        settle()
        assertEquals(SaveReceiptResult.Success(901L), st.saveResult)
        assertEquals("EUR", deps.requests.last().currency)
        assertEquals(2, deps.requests.size)
        assertTrue(lines.none { it.priority >= 5 && it.message.contains("atomic_save") })
    }

    private data class RefusalCase(
        val reason: RateRefusalReason,
        val coordinatorDate: LocalDate?,
        val variant: CurrencyBlockVariant,
        val shownDate: LocalDate?,
        val saveBlocked: Boolean
    )

    @Test
    fun `C1 AE-24 each refusal reason maps to its card and only STALE shows a date`() {
        scan(mecReceipt(currency = "USD"))
        val cases = listOf(
            RefusalCase(RateRefusalReason.NO_RATE, null, CurrencyBlockVariant.NONE, null, true),
            RefusalCase(RateRefusalReason.NO_RATE, mecDay(3), CurrencyBlockVariant.NONE, null, true),
            RefusalCase(RateRefusalReason.STALE_RATE, mecDay(7), CurrencyBlockVariant.STALE, mecDay(7), true),
            RefusalCase(RateRefusalReason.UNSUPPORTED_CURRENCY, mecDay(3), CurrencyBlockVariant.UNSUPPORTED, null, true),
            RefusalCase(RateRefusalReason.HOME_CURRENCY_UNRESOLVED, null, CurrencyBlockVariant.HOME_UNRESOLVED, null, false)
        )
        for (case in cases) {
            refuse(case.reason, case.coordinatorDate)
            model.saveExpense()
            settle()
            assertEquals(
                case.reason.name,
                CurrencyRegionUi.Blocked(case.variant, case.shownDate, CurrencyBlockOrigin.SAVE), region
            )
            assertEquals(case.reason.name, case.saveBlocked, st.isCurrencySaveBlocked)
            assertEquals(SaveReceiptResult.RateNotEligible, st.saveResult)
            assertFalse(st.isSaving)
        }
        assertEquals(cases.size, deps.requests.size)
    }

    @Test
    fun `C1 AE-22 a rate cleared between preview and save shows the SAVE card`() {
        deps.store.putRate("USD", 0.9, mecDay(14))
        scan(mecReceipt(currency = "USD"))
        assertTrue(region is CurrencyRegionUi.Eligible)

        deps.store.rows.clear()
        refuse(RateRefusalReason.NO_RATE)
        model.saveExpense()
        settle()

        assertEquals(CurrencyRegionUi.Blocked(CurrencyBlockVariant.NONE, null, CurrencyBlockOrigin.SAVE), region)
        assertEquals(SaveReceiptResult.RateNotEligible, st.saveResult)
        assertEquals(ScanStep.REVIEW, st.step)
    }

    @Test
    fun `C1 AE-22 a refusal beats a late preview completion`() {
        deps.store.putRate("USD", 0.9, mecDay(14))
        deps.store.gate = CompletableDeferred()
        scan0(mecReceipt(currency = "USD"))
        assertEquals(CurrencyRegionUi.Pending, region)
        advance(300)
        assertEquals(CurrencyRegionUi.Evaluating, region)
        assertFalse(st.isCurrencySaveBlocked)

        refuse(RateRefusalReason.NO_RATE)
        model.saveExpense()
        runNow()
        assertEquals(CurrencyRegionUi.Blocked(CurrencyBlockVariant.NONE, null, CurrencyBlockOrigin.SAVE), region)

        deps.store.gate!!.complete(Unit)
        settle()
        advance(5_000)

        // The late preview would have been Eligible (a rate is stored); the refusal still stands.
        assertEquals(CurrencyRegionUi.Blocked(CurrencyBlockVariant.NONE, null, CurrencyBlockOrigin.SAVE), region)
        assertEquals(SaveReceiptResult.RateNotEligible, st.saveResult)
    }

    /** Starts a scan and runs only the currently due tasks (the preview stays in flight). */
    private fun scan0(receipt: ScannedReceipt) {
        val uri = Uri.parse("content://synthetic/slow-${receipt.id}.jpg")
        stubScan(uri, receipt)
        model.processGalleryImage(uri)
        runNow()
    }

    @Test
    fun `C1 AE-25 a BGN proposal is Unset, keeps no OCR token and blocks Save`() {
        deps.store.putRate("USD", 0.9, mecDay(14))
        scan(mecReceipt(currency = "BGN"))

        assertEquals(ScanStep.REVIEW, st.step)
        assertEquals(CurrencyRegionUi.Unset(CurrencyUnsetReason.PROPOSAL_NOT_SELECTABLE), region)
        assertNull(st.editCurrency)
        assertFalse(st.currencyUnverified)
        assertTrue(st.isCurrencySaveBlocked)
        assertEquals("EUR", st.currencyOptions.first())
        assertFalse("BGN" in st.currencyOptions)

        model.saveExpense()
        settle()
        assertTrue(deps.requests.isEmpty())
        assertNull(st.saveResult)

        // A non-selectable pick is ignored; a selectable one leaves Unset.
        model.updateCurrency("BGN")
        settle()
        assertNull(st.editCurrency)
        assertTrue(region is CurrencyRegionUi.Unset)
        model.updateCurrency("USD")
        settle()
        assertEquals("USD", st.editCurrency)
        assertTrue(region is CurrencyRegionUi.Eligible)
    }

    @Test
    fun `C1 AE-25 a forced flagged BGN request is refused as UNSUPPORTED`() {
        setState(reviewState(currency = "BGN"))
        refuse(RateRefusalReason.UNSUPPORTED_CURRENCY)

        model.saveExpense()
        settle()

        val request = deps.requests.single()
        assertEquals("BGN", request.currency)
        assertNotNull(request.userPresentRate)
        assertEquals(CurrencyRegionUi.Blocked(CurrencyBlockVariant.UNSUPPORTED, null, CurrencyBlockOrigin.SAVE), region)
        assertEquals(SaveReceiptResult.RateNotEligible, st.saveResult)
    }

    @Test
    fun `C1 INV-11 a blank OCR currency assumes home flagged and shows no rate card`() {
        scan(mecReceipt(currency = ""))
        assertEquals("EUR", st.editCurrency)
        assertTrue(st.currencyUnverified)
        assertEquals(CurrencyRegionUi.Home, region)
    }

    @Test
    fun `C1 INV-10 updateCurrency ignores a non-selectable pick`() {
        scan(mecReceipt(currency = "EUR"))
        for (pick in listOf("BGN", "RUB")) {
            model.updateCurrency(pick)
            settle()
            assertEquals(pick, "EUR", st.editCurrency)
            assertEquals(pick, CurrencyRegionUi.Home, region)
        }
        assertTrue(deps.store.asOfCalls.isEmpty())
    }

    @Test
    fun `C1 INV-10 updateCurrency ignores an unparseable pick`() {
        scan(mecReceipt(currency = "EUR"))
        for (pick in listOf("", "ZZZ", "US")) {
            model.updateCurrency(pick)
            settle()
            assertEquals("pick='$pick'", "EUR", st.editCurrency)
        }
    }

    // ---- AE-30 ----

    @Test
    fun `C1 AE-30 no log line carries an amount merchant currency date rate or zone`() {
        useZone("Europe/Athens")
        deps.store.putRate("USD", 0.9137, mecDay(14))
        val sensitive = "USD 1234.56 Zorblax 0.9137 Europe/Athens"
        scan(mecReceipt(currency = "USD", total = 1234.56, merchant = "Zorblax Cafe"))

        // A refusal, an amount edit, a failing preview and a failing refresh.
        refuse(RateRefusalReason.STALE_RATE, mecDay(7))
        model.saveExpense()
        settle()
        model.updateAmount("2345.67")
        deps.store.failure = IOException(sensitive)
        model.updateCurrency("GBP")
        settle()
        deps.store.failure = null
        deps.privacyFlow.value = PrivacySettingsLoadState.Loaded(PrivacySettings(exchangeRateDownloadEnabled = true))
        settle()
        deps.ratesRepo.outcome = { throw IOException(sensitive) }
        model.refreshRates()
        settle()

        assertTrue(lines.any { it.message == "RATE_PREVIEW_FAILED" })
        assertTrue(lines.any { it.message == "RATE_REFRESH_FAILED" })
        val forbidden = listOf(
            "Zorblax", "1234", "2345", "USD", "GBP", "EUR", "0.9137", "Athens", "Europe/", "2023",
            "1700000000000", "11-0"
        )
        for (line in lines) {
            assertNull("throwable attached to '${line.message}'", line.throwable)
            for (token in forbidden) {
                assertFalse("'$token' leaked into a log line", line.message.contains(token))
            }
        }
        // The refusal itself is not an error line.
        assertTrue(lines.none { it.priority >= 6 })
    }

    // ---- AE-33 ----

    @Test
    fun `C1 AE-33 dismissing while saving propagates cancellation and records no refusal or error`() {
        scan(mecReceipt(currency = "EUR"))
        val started = CompletableDeferred<Unit>()
        var cancelledSeen = false
        coEvery { deps.coordinator.createExpenseAndLinkReceipt(any()) } coAnswers {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelledSeen = true
            }
        }

        model.saveExpense()
        runNow()
        assertTrue(started.isCompleted)
        assertTrue(st.isSaving)

        model.viewModelScope.cancel()
        settle()

        assertTrue(cancelledSeen)
        assertNull(st.saveResult)
        assertNull(st.errorMessage)
        assertFalse(region is CurrencyRegionUi.Blocked)
        assertTrue(lines.none { it.priority >= 5 })
    }

    // ---- AE-37 ----

    @Test
    fun `C1 AE-37 tomorrow with the rate of today is eligible and plus 30 days is stale`() {
        deps.store.putRate("USD", 0.9, mecDay(14))
        scan(mecReceipt(currency = "USD", date = MEC_DATE))
        assertEquals(mecDay(14), (region as CurrencyRegionUi.Eligible).rateDate)

        model.updateDate(MEC_DATE + MEC_DAY_MS) // 15 Nov: age 1
        settle()
        assertEquals(mecDay(14), (region as CurrencyRegionUi.Eligible).rateDate)

        model.updateDate(MEC_DATE + 7 * MEC_DAY_MS) // 21 Nov: age 7, the inclusive limit
        settle()
        assertTrue(region is CurrencyRegionUi.Eligible)

        model.updateDate(MEC_DATE + 8 * MEC_DAY_MS) // 22 Nov: age 8
        settle()
        assertEquals(CurrencyRegionUi.Blocked(CurrencyBlockVariant.STALE, mecDay(14), CurrencyBlockOrigin.PREVIEW), region)

        model.updateDate(MEC_DATE + 30 * MEC_DAY_MS)
        settle()
        assertEquals(CurrencyRegionUi.Blocked(CurrencyBlockVariant.STALE, mecDay(14), CurrencyBlockOrigin.PREVIEW), region)
    }

    // ---- G2-14 / RF-6 / zone ----

    @Test
    fun `C1 G2-14 a manual save carries the review zone captured at review`() {
        useZone("Europe/Athens")
        deps.store.putRate("USD", 0.9, mecDay(14))
        scan(mecReceipt(currency = "USD"))
        useZone("America/Los_Angeles")

        model.saveExpense()
        settle()

        assertEquals(UserPresentRateRequirement(athens), deps.requests.single().userPresentRate)
    }

    @Test
    fun `C1 G2-14 a quick save carries the review zone captured at review`() {
        setState(quickSaveState("USD"))
        useZone("Europe/Athens")
        model.onReviewOpened()
        settle()
        useZone("America/Los_Angeles")
        coEvery { deps.links.checkCanLinkReceipt(17L) } returns true

        model.requestReceiptQuickSaveConfirmation()
        assertNotNull(st.quickSavePreview)
        model.confirmReceiptQuickSave()
        settle()

        val request = deps.requests.single()
        assertEquals(12.34, request.amount, 0.0)
        assertEquals(UserPresentRateRequirement(athens), request.userPresentRate)
    }

    @Test
    fun `C1 zone is stable against a device zone change after review`() {
        useZone("Europe/Athens")
        deps.store.putRate("USD", 0.9, mecDay(15))
        scan(mecReceipt(currency = "USD"))
        // Athens local date of the instant is 15 Nov, so the 15 Nov rate qualifies.
        assertEquals(mecDay(15), (region as CurrencyRegionUi.Eligible).rateDate)
        assertEquals(mecUtcMidnight(mecDay(15)), deps.store.asOfCalls.last().third)

        useZone("America/Los_Angeles") // local 14 Nov: that rate would not qualify
        model.updateAmount("11")
        settle()

        assertEquals(mecUtcMidnight(mecDay(15)), deps.store.asOfCalls.last().third)
        assertEquals(mecDay(15), (region as CurrencyRegionUi.Eligible).rateDate)
        model.saveExpense()
        settle()
        assertEquals(athens, deps.requests.single().userPresentRate!!.entryZone)
    }

    @Test
    fun `C1 zone follows the device when no review was opened and onReviewOpened is null-guarded`() {
        setState(reviewState("USD"))
        useZone("America/Los_Angeles")
        model.saveExpense() // no zone captured: falls back to the device zone at request build
        settle()
        assertEquals(ZoneId.of("America/Los_Angeles"), deps.requests.last().userPresentRate!!.entryZone)

        setState(reviewState("USD"))
        useZone("Europe/Athens")
        model.onReviewOpened()
        useZone("America/Los_Angeles")
        model.onReviewOpened() // already captured: no change
        model.saveExpense()
        settle()
        assertEquals(athens, deps.requests.last().userPresentRate!!.entryZone)
    }

    @Test
    fun `C1 onReviewOpened outside REVIEW captures nothing`() {
        setState(reviewState("USD").copy(step = ScanStep.CAPTURE))
        useZone("Europe/Athens")
        model.onReviewOpened()
        useZone("America/Los_Angeles")
        setState(reviewState("USD"))
        model.saveExpense()
        settle()
        assertEquals(ZoneId.of("America/Los_Angeles"), deps.requests.single().userPresentRate!!.entryZone)
    }

    @Test
    fun `C1 a new scan and reset clear the captured zone`() {
        useZone("Europe/Athens")
        scan(mecReceipt(currency = "USD"))
        useZone("America/Los_Angeles")
        scan(mecReceipt(id = 18L, currency = "USD"))
        model.saveExpense()
        settle()
        assertEquals(ZoneId.of("America/Los_Angeles"), deps.requests.last().userPresentRate!!.entryZone)

        useZone("Europe/Athens")
        model.reset()
        setState(reviewState("USD"))
        useZone("America/Los_Angeles")
        model.onReviewOpened() // captured after reset: the zone at that moment
        useZone("Europe/Athens")
        model.saveExpense()
        settle()
        assertEquals(ZoneId.of("America/Los_Angeles"), deps.requests.last().userPresentRate!!.entryZone)
    }

    @Test
    fun `C1 an OCR failed review captures the zone and assumes home flagged`() {
        useZone("Europe/Athens")
        scan(mecReceipt(status = "OCR_FAILED", currency = "EUR"))
        assertEquals("EUR", st.editCurrency)
        assertTrue(st.currencyUnverified)
        useZone("America/Los_Angeles")
        model.updateMerchant("Merchant")
        model.updateAmount("10")
        model.saveExpense()
        settle()
        assertEquals(athens, deps.requests.single().userPresentRate!!.entryZone)
    }

    @Test
    fun `C1 RF-6 normal case a receipt date at start of day in the device zone evaluates as that local date`() {
        useZone("Europe/Athens")
        val startOfDayAthens = LocalDate.of(2023, 11, 15).atStartOfDay(athens).toInstant().toEpochMilli()
        deps.store.putRate("USD", 0.9, mecDay(15))
        scan(mecReceipt(currency = "USD", date = startOfDayAthens))

        assertEquals(mecDay(15), (region as CurrencyRegionUi.Eligible).rateDate)
        assertEquals(mecUtcMidnight(mecDay(15)), deps.store.asOfCalls.last().third)
        assertEquals(startOfDayAthens, st.editDate)
    }

    // ---- amount edits / region invariants ----

    @Test
    fun `C1 an amount edit keeps an amount-independent Blocked and sends amount-dependent states to Pending`() {
        deps.store.putRate("USD", 0.9, mecDay(14))
        scan(mecReceipt(currency = "USD"))
        assertTrue(region is CurrencyRegionUi.Eligible)

        deps.store.gate = CompletableDeferred()
        model.updateAmount("11")
        runNow()
        assertEquals(CurrencyRegionUi.Pending, region)
        deps.store.gate!!.complete(Unit)
        settle()
        deps.store.gate = null

        refuse(RateRefusalReason.NO_RATE)
        model.saveExpense()
        settle()
        val blocked = CurrencyRegionUi.Blocked(CurrencyBlockVariant.NONE, null, CurrencyBlockOrigin.SAVE)
        assertEquals(blocked, region)

        deps.store.gate = CompletableDeferred()
        model.updateAmount("12")
        runNow()
        assertEquals(blocked, region)
        assertNull(st.saveResult)
        deps.store.gate!!.complete(Unit)
        settle()
    }

    @Test
    fun `C1 amount rules are inline and bounded`() {
        scan(mecReceipt(currency = "USD"))
        model.updateAmount("0")
        assertEquals(CurrencyRegionUi.AmountInvalid(CurrencyAmountInvalidKind.NOT_POSITIVE), region)
        model.updateAmount("99999999")
        assertEquals(CurrencyRegionUi.AmountInvalid(CurrencyAmountInvalidKind.ABOVE_CAP), region)
        model.updateCurrency("JPY")
        model.updateAmount("10.5")
        assertEquals(CurrencyRegionUi.AmountInvalid(CurrencyAmountInvalidKind.PRECISION_EXCEEDED), region)
        assertTrue(st.isCurrencySaveBlocked)
        model.updateAmount("10")
        settle()
        assertFalse(region is CurrencyRegionUi.AmountInvalid)
    }

    @Test
    fun `C1 INV-3 an AI applied date or total re-evaluates the region`() {
        deps.store.putRate("USD", 0.9, mecDay(14))
        scan(mecReceipt(currency = "USD"))
        assertTrue(region is CurrencyRegionUi.Eligible)

        setState(st.copy(receiptAssistState = AiLoadState.Ready(
            ReceiptAssistSuggestion(date = SuggestedValue(MEC_DATE + 30 * MEC_DAY_MS))
        )))
        model.applyReceiptAssistDate()
        settle()
        assertEquals(CurrencyRegionUi.Blocked(CurrencyBlockVariant.STALE, mecDay(14), CurrencyBlockOrigin.PREVIEW), region)

        model.updateDate(MEC_DATE)
        settle()
        setState(st.copy(receiptAssistState = AiLoadState.Ready(
            ReceiptAssistSuggestion(total = SuggestedValue(99_999_999.0))
        )))
        model.applyReceiptAssistTotal()
        settle()
        assertEquals(CurrencyRegionUi.AmountInvalid(CurrencyAmountInvalidKind.ABOVE_CAP), region)
    }

    @Test
    fun `C1 the checking state appears only after 250 ms and Save stays enabled`() {
        deps.store.putRate("USD", 0.9, mecDay(14))
        deps.store.gate = CompletableDeferred()
        scan0(mecReceipt(currency = "USD"))
        advance(249)
        assertEquals(CurrencyRegionUi.Pending, region)
        advance(1)
        assertEquals(CurrencyRegionUi.Evaluating, region)
        assertFalse(st.isCurrencySaveBlocked)
        deps.store.gate!!.complete(Unit)
        settle()
        assertTrue(region is CurrencyRegionUi.Eligible)
    }

    @Test
    fun `C1 a home currency change re-evaluates the region`() {
        deps.store.putRate("USD", 0.9, mecDay(14))
        scan(mecReceipt(currency = "USD"))
        assertTrue(region is CurrencyRegionUi.Eligible)

        deps.homeFlow.value = "GBP"
        settle()

        assertEquals("GBP", st.currencyOptions.first())
        assertTrue(deps.store.asOfCalls.any { it.second == "GBP" })
        assertEquals(CurrencyRegionUi.Blocked(CurrencyBlockVariant.NONE, null, CurrencyBlockOrigin.PREVIEW), region)
    }

    // ---- new scan / reset / retry go to home ----

    @Test
    fun `C1 a new scan goes to home and overwrites amount and currency together`() {
        deps.store.putRate("USD", 0.9, mecDay(14))
        scan(mecReceipt(currency = "USD", total = 10.0))
        model.updateCurrency("GBP")
        settle()
        assertEquals("GBP", st.editCurrency)

        val gate = CompletableDeferred<ReceiptLifecycleCoordinator.ReceiptProcessOutcome>()
        val uri = Uri.parse("content://synthetic/second.jpg")
        coEvery { deps.coordinator.processReceiptInput(uri, any()) } coAnswers { kotlin.Result.success(gate.await()) }
        coEvery { deps.links.checkCanLinkReceipt(18L) } returns true
        model.processGalleryImage(uri)
        runNow()
        assertEquals(ScanStep.PROCESSING, st.step)
        assertEquals("EUR", st.editCurrency)
        assertEquals(CurrencyRegionUi.Home, region)

        gate.complete(ReceiptLifecycleCoordinator.ReceiptProcessOutcome(mecReceipt(id = 18L, currency = "", total = null), true, null))
        settle()
        assertEquals(18L, st.receiptId)
        assertEquals("EUR", st.editCurrency)
        assertTrue(st.currencyUnverified)
        assertEquals("", st.editAmount)
        assertEquals(CurrencyRegionUi.Home, region)
    }

    @Test
    fun `C1 a new scan after an Unset proposal returns to home`() {
        scan(mecReceipt(currency = "BGN"))
        assertTrue(region is CurrencyRegionUi.Unset)
        scan(mecReceipt(id = 18L, currency = "EUR"))
        assertEquals("EUR", st.editCurrency)
        assertEquals(CurrencyRegionUi.Home, region)
        assertFalse(st.isCurrencySaveBlocked)
    }

    @Test
    fun `C1 reset and retry return to home with options and cancel the evaluation`() {
        deps.store.putRate("USD", 0.9, mecDay(14))
        deps.store.gate = CompletableDeferred()
        scan0(mecReceipt(currency = "USD"))
        model.reset()
        assertEquals("EUR", st.editCurrency)
        assertEquals(CurrencyRegionUi.Home, region)
        assertEquals("EUR", st.currencyOptions.first())
        deps.store.gate!!.complete(Unit)
        settle()
        advance(5_000)
        assertEquals(CurrencyRegionUi.Home, region)

        deps.store.gate = null
        scan(mecReceipt(currency = "USD"))
        model.retry()
        assertEquals("EUR", st.editCurrency)
        assertEquals(CurrencyRegionUi.Home, region)
        assertNull(st.saveResult)
    }

    // ---- saveResult clearing ----

    @Test
    fun `C1 a RateNotEligible result clears on amount date currency edits and a new scan but other results stay`() {
        scan(mecReceipt(currency = "USD"))
        refuse(RateRefusalReason.NO_RATE)
        val edits: List<() -> Unit> = listOf(
            { model.updateAmount("11") },
            { model.updateDate(MEC_DATE + MEC_DAY_MS) },
            { model.updateCurrency("GBP") },
            { scan(mecReceipt(id = 19L, currency = "USD")) }
        )
        for ((index, edit) in edits.withIndex()) {
            model.saveExpense()
            settle()
            assertEquals("edit $index", SaveReceiptResult.RateNotEligible, st.saveResult)
            edit()
            runNow()
            assertNull("edit $index", st.saveResult)
        }

        // Control: a different result is not cleared by an amount edit.
        setState(st.copy(saveResult = SaveReceiptResult.Error("Receipt save failed (UNKNOWN_ERROR)")))
        model.updateAmount("12")
        assertEquals(SaveReceiptResult.Error("Receipt save failed (UNKNOWN_ERROR)"), st.saveResult)
    }

    // ---- defence-in-depth guard ----

    @Test
    fun `C1 the guard blocks Save for AmountInvalid and leaves Blocked to the coordinator`() {
        scan(mecReceipt(currency = "USD"))
        model.updateCurrency("JPY")
        model.updateAmount("10.5")
        assertTrue(region is CurrencyRegionUi.AmountInvalid)
        model.saveExpense()
        settle()
        assertTrue(deps.requests.isEmpty())

        model.updateAmount("10")
        settle()
        assertTrue(region is CurrencyRegionUi.Blocked) // no JPY rate stored
        refuse(RateRefusalReason.NO_RATE)
        model.saveExpense()
        settle()
        assertEquals(1, deps.requests.size)
        assertEquals(SaveReceiptResult.RateNotEligible, st.saveResult)
    }

    @Test
    fun `C1 the guard blocks quick save for Unset and AmountInvalid and leaves Blocked to the coordinator`() {
        coEvery { deps.links.checkCanLinkReceipt(17L) } returns true
        for (guarded in listOf<CurrencyRegionUi>(
            CurrencyRegionUi.Unset(CurrencyUnsetReason.PROPOSAL_NOT_SELECTABLE),
            CurrencyRegionUi.AmountInvalid(CurrencyAmountInvalidKind.NOT_POSITIVE)
        )) {
            setState(quickSaveState("USD"))
            model.requestReceiptQuickSaveConfirmation()
            assertNotNull(st.quickSavePreview)
            setState(st.copy(currencyRegion = guarded))
            model.confirmReceiptQuickSave()
            settle()
            assertNull(st.quickSavePreview)
            assertTrue(deps.requests.isEmpty())
        }

        setState(quickSaveState("USD"))
        model.requestReceiptQuickSaveConfirmation()
        setState(st.copy(currencyRegion = CurrencyRegionUi.Blocked(CurrencyBlockVariant.NONE, null, CurrencyBlockOrigin.PREVIEW)))
        refuse(RateRefusalReason.NO_RATE)
        model.confirmReceiptQuickSave()
        settle()
        assertEquals(1, deps.requests.size)
    }

    // ---- UX-Q-5 ----

    @Test
    fun `C1 UX-Q-5 a quick save refusal closes the dialog and keeps the card in review`() {
        setState(quickSaveState("USD"))
        coEvery { deps.links.checkCanLinkReceipt(17L) } returns true
        model.requestReceiptQuickSaveConfirmation()
        assertNotNull(st.quickSavePreview)
        refuse(RateRefusalReason.STALE_RATE, mecDay(7))

        model.confirmReceiptQuickSave()
        settle()

        assertNull(st.quickSavePreview)
        assertEquals(ScanStep.REVIEW, st.step)
        assertFalse(st.isSaving)
        assertEquals(SaveReceiptResult.RateNotEligible, st.saveResult)
        assertEquals(CurrencyRegionUi.Blocked(CurrencyBlockVariant.STALE, mecDay(7), CurrencyBlockOrigin.SAVE), region)
        assertEquals("Lidl", st.editMerchant)
        assertEquals("12.34", st.editAmount)
        assertTrue(lines.none { it.priority >= 5 })
    }

    // ---- rates setting and refresh ----

    private fun scanBlockedUsd() {
        scan(mecReceipt(currency = "USD"))
        assertEquals(CurrencyRegionUi.Blocked(CurrencyBlockVariant.NONE, null, CurrencyBlockOrigin.PREVIEW), region)
    }

    private fun setRatesSetting(state: PrivacySettingsLoadState) {
        deps.privacyFlow.value = state
        settle()
    }

    @Test
    fun `C1 refresh never runs while the setting is Off or Unknown`() {
        scanBlockedUsd()
        assertEquals(CurrencyRatesSetting.OFF, st.ratesSetting)
        model.refreshRates()
        settle()
        assertTrue(deps.ratesRepo.calls.isEmpty())

        setRatesSetting(PrivacySettingsLoadState.CorruptedFailClosed(PrivacySettings.FAIL_CLOSED_DEFAULTS, "TEST_CODE"))
        assertEquals(CurrencyRatesSetting.UNKNOWN, st.ratesSetting)
        model.refreshRates()
        settle()
        assertTrue(deps.ratesRepo.calls.isEmpty())

        setRatesSetting(PrivacySettingsLoadState.FirstRunDefault(PrivacySettings()))
        assertEquals(CurrencyRatesSetting.OFF, st.ratesSetting)
        model.refreshRates()
        settle()
        assertTrue(deps.ratesRepo.calls.isEmpty())
        assertEquals(CurrencyRefreshStatus.IDLE, st.rateRefresh)
    }

    @Test
    fun `C1 with the setting On nothing refreshes on its own and a user refresh re-evaluates`() {
        setRatesSetting(PrivacySettingsLoadState.Loaded(PrivacySettings(exchangeRateDownloadEnabled = true)))
        assertEquals(CurrencyRatesSetting.ON, st.ratesSetting)
        scanBlockedUsd()
        refuse(RateRefusalReason.NO_RATE)
        model.saveExpense()
        settle()
        assertTrue(deps.ratesRepo.calls.isEmpty())

        deps.ratesRepo.outcome = {
            deps.store.putRate("USD", 0.9, mecDay(14))
            CurrencyRateRefreshOutcome.Refreshed(1)
        }
        model.refreshRates()
        settle()

        assertEquals(listOf("EUR"), deps.ratesRepo.calls)
        assertTrue(region is CurrencyRegionUi.Eligible)
        assertEquals(CurrencyRefreshStatus.IDLE, st.rateRefresh)
    }

    @Test
    fun `C1 a refresh that stores nothing ends as still blocked and a second tap while running is ignored`() {
        setRatesSetting(PrivacySettingsLoadState.Loaded(PrivacySettings(exchangeRateDownloadEnabled = true)))
        scanBlockedUsd()
        val release = CompletableDeferred<Unit>()
        deps.ratesRepo.outcome = {
            release.await()
            CurrencyRateRefreshOutcome.Refreshed(0)
        }
        model.refreshRates()
        model.refreshRates()
        runNow()
        assertEquals(CurrencyRefreshStatus.RUNNING, st.rateRefresh)
        assertEquals(1, deps.ratesRepo.calls.size)

        release.complete(Unit)
        settle()
        assertEquals(CurrencyRefreshStatus.DONE_STILL_BLOCKED, st.rateRefresh)
        assertTrue(region is CurrencyRegionUi.Blocked)
    }

    @Test
    fun `C1 refresh outcomes map to bounded statuses and settings`() {
        setRatesSetting(PrivacySettingsLoadState.Loaded(PrivacySettings(exchangeRateDownloadEnabled = true)))
        scanBlockedUsd()

        deps.ratesRepo.outcome = { throw IOException("offline") }
        model.refreshRates()
        settle()
        assertEquals(CurrencyRefreshStatus.FAILED_OFFLINE, st.rateRefresh)

        deps.ratesRepo.outcome = { CurrencyRateRefreshOutcome.DisabledByPrivacy }
        model.refreshRates()
        settle()
        assertEquals(CurrencyRatesSetting.OFF, st.ratesSetting)

        // Toggle through Off so the stored setting emits On again after the local Off.
        setRatesSetting(PrivacySettingsLoadState.Loaded(PrivacySettings()))
        setRatesSetting(PrivacySettingsLoadState.Loaded(PrivacySettings(exchangeRateDownloadEnabled = true)))
        assertEquals(CurrencyRatesSetting.ON, st.ratesSetting)
        deps.ratesRepo.outcome = { CurrencyRateRefreshOutcome.PrivacyCheckFailed("TEST_CODE") }
        model.refreshRates()
        settle()
        assertEquals(CurrencyRatesSetting.UNKNOWN, st.ratesSetting)
        assertNotEquals(CurrencyRefreshStatus.RUNNING, st.rateRefresh)
    }

    @Test
    fun `C1 refresh does nothing when the selected currency is home`() {
        setRatesSetting(PrivacySettingsLoadState.Loaded(PrivacySettings(exchangeRateDownloadEnabled = true)))
        scan(mecReceipt(currency = "EUR"))
        model.refreshRates()
        settle()
        assertTrue(deps.ratesRepo.calls.isEmpty())
    }

    @Test
    fun `C1 saving a home currency receipt never touches the rate store`() {
        scan(mecReceipt(currency = "EUR"))
        model.saveExpense()
        settle()
        assertTrue(deps.store.asOfCalls.isEmpty())
        coVerify(exactly = 1) { deps.coordinator.createExpenseAndLinkReceipt(any()) }
    }

    // ---- MEC C2 seams: amountError, homeCurrency, open-setting request ----

    private fun amountText(resId: Int, vararg args: Any) =
        com.yourname.expensetracker.domain.model.UiText.StringResource(resId, args.toList())

    @Test
    fun `C2 amountError for a currency without decimals names the code`() {
        scan(mecReceipt(currency = "USD"))
        assertNull(st.amountError)

        model.updateCurrency("JPY")
        model.updateAmount("1500.5")

        assertEquals(CurrencyRegionUi.AmountInvalid(CurrencyAmountInvalidKind.PRECISION_EXCEEDED), region)
        assertEquals(amountText(com.yourname.expensetracker.R.string.mec_amount_no_decimals, "JPY"), st.amountError)
    }

    @Test
    fun `C2 amountError for a too large amount carries the formatted cap`() {
        scan(mecReceipt(currency = "USD"))

        model.updateAmount("99999999")

        assertEquals(CurrencyRegionUi.AmountInvalid(CurrencyAmountInvalidKind.ABOVE_CAP), region)
        assertEquals(amountText(com.yourname.expensetracker.R.string.mec_amount_too_large, "1,000,000"), st.amountError)
    }

    @Test
    fun `C2 amountError for a not positive amount is the generic invalid text`() {
        scan(mecReceipt(currency = "USD"))

        model.updateAmount("0")

        assertEquals(CurrencyRegionUi.AmountInvalid(CurrencyAmountInvalidKind.NOT_POSITIVE), region)
        assertEquals(amountText(com.yourname.expensetracker.R.string.add_expense_error_amount_invalid), st.amountError)
    }

    @Test
    fun `C2 amountError when the digits are unavailable is the generic text and never a precision claim`() {
        // XXX has no defined minor-unit digits (OD-6): fail closed, no guess of 2 decimals.
        setState(reviewState(currency = "XXX", amount = ""))
        model.updateAmount("5")

        assertEquals(CurrencyRegionUi.AmountInvalid(CurrencyAmountInvalidKind.NOT_POSITIVE), region)
        assertEquals(amountText(com.yourname.expensetracker.R.string.add_expense_error_amount_invalid), st.amountError)
        assertTrue(st.isCurrencySaveBlocked)
    }

    @Test
    fun `C2 amountError is cleared whenever the region leaves AmountInvalid`() {
        scan(mecReceipt(currency = "USD"))
        model.updateCurrency("JPY")
        model.updateAmount("10.5")
        assertNotNull(st.amountError)

        // A valid amount drops to Pending at once: no stale message.
        model.updateAmount("10")
        assertFalse(region is CurrencyRegionUi.AmountInvalid)
        assertNull(st.amountError)
        settle()
        assertNull(st.amountError)

        // Back to home.
        model.updateAmount("10.5")
        assertNotNull(st.amountError)
        model.updateCurrency("EUR")
        assertEquals(CurrencyRegionUi.Home, region)
        assertNull(st.amountError)

        // Reset and a new scan.
        model.updateCurrency("JPY")
        model.updateAmount("10.5")
        assertNotNull(st.amountError)
        model.reset()
        assertNull(st.amountError)
        scan(mecReceipt(id = 18L, currency = "BGN"))
        assertTrue(region is CurrencyRegionUi.Unset)
        assertNull(st.amountError)
    }

    @Test
    fun `C2 homeCurrency follows the resolved home and survives scan reset and an Unset proposal`() {
        assertEquals("EUR", st.homeCurrency)

        scan(mecReceipt(currency = "BGN"))
        assertTrue(region is CurrencyRegionUi.Unset)
        assertEquals("EUR", st.homeCurrency)

        deps.homeFlow.value = "GBP"
        settle()
        assertEquals("GBP", st.homeCurrency)
        assertEquals("GBP", st.currencyOptions.first())

        model.reset()
        assertEquals("GBP", st.homeCurrency)
        scan(mecReceipt(id = 19L, currency = "USD"))
        assertEquals("GBP", st.homeCurrency)
    }

    private fun scanHomeJpy() {
        deps.homeFlow.value = "JPY"
        settle()
        assertEquals("JPY", st.homeCurrency)
        scan(mecReceipt(currency = "JPY", total = 1500.0))
    }

    @Test
    fun `C2 home JPY with a fractional amount is AmountInvalid and blocks save`() {
        scanHomeJpy()

        model.updateAmount("1500.5")
        settle()

        assertEquals(CurrencyRegionUi.AmountInvalid(CurrencyAmountInvalidKind.PRECISION_EXCEEDED), region)
        assertEquals(amountText(com.yourname.expensetracker.R.string.mec_amount_no_decimals, "JPY"), st.amountError)
        assertTrue(st.isCurrencySaveBlocked)

        model.saveExpense()
        settle()
        assertTrue(deps.requests.isEmpty())
        coVerify(exactly = 0) { deps.coordinator.createExpenseAndLinkReceipt(any()) }
    }

    @Test
    fun `C2 home JPY with a whole amount is Home with no error and save unblocked`() {
        scanHomeJpy()
        model.updateAmount("1500.5")
        settle()
        assertTrue(region is CurrencyRegionUi.AmountInvalid)

        model.updateAmount("1500")
        settle()

        assertEquals(CurrencyRegionUi.Home, region)
        assertNull(st.amountError)
        assertFalse(st.isCurrencySaveBlocked)
    }

    @Test
    fun `C2 home EUR with a two decimal amount stays Home with no error`() {
        scan(mecReceipt(currency = "EUR"))

        model.updateAmount("12.50")
        settle()

        assertEquals(CurrencyRegionUi.Home, region)
        assertNull(st.amountError)
        assertFalse(st.isCurrencySaveBlocked)
    }

    @Test
    fun `C2 confirmOpenExchangeRateSetting resets the draft then raises NAVIGATE and consume clears it`() {
        scan(mecReceipt(currency = "USD", total = 10.0, merchant = "Merchant"))
        model.requestOpenExchangeRateSetting()
        assertEquals(CurrencyOpenSettingRequest.CONFIRM_NEEDED, st.openSettingRequest)
        assertEquals("the draft is intact until the user confirms", "Merchant", st.editMerchant)

        model.confirmOpenExchangeRateSetting()
        settle()

        assertEquals(CurrencyOpenSettingRequest.NAVIGATE, st.openSettingRequest)
        assertEquals(ScanStep.CAPTURE, st.step)
        assertNull(st.receiptId)
        assertEquals("", st.editMerchant)
        assertEquals("", st.editAmount)
        assertEquals("EUR", st.editCurrency)
        assertEquals(CurrencyRegionUi.Home, region)
        assertNull(st.amountError)

        model.consumeOpenSettingRequest()
        assertEquals(CurrencyOpenSettingRequest.NONE, st.openSettingRequest)
    }

    @Test
    fun `C2 confirmOpenExchangeRateSetting is a no-op unless a confirmation is pending`() {
        scan(mecReceipt(currency = "USD", total = 10.0, merchant = "Merchant"))

        // NONE: nothing to confirm.
        model.confirmOpenExchangeRateSetting()
        settle()
        assertEquals(CurrencyOpenSettingRequest.NONE, st.openSettingRequest)
        assertEquals(ScanStep.REVIEW, st.step)
        assertEquals("Merchant", st.editMerchant)
        assertEquals("10.00", st.editAmount)

        // NAVIGATE (a blank amount needs no confirm): a stray confirm must not reset the draft.
        model.updateAmount("")
        model.requestOpenExchangeRateSetting()
        assertEquals(CurrencyOpenSettingRequest.NAVIGATE, st.openSettingRequest)
        model.confirmOpenExchangeRateSetting()
        settle()
        assertEquals(CurrencyOpenSettingRequest.NAVIGATE, st.openSettingRequest)
        assertEquals(ScanStep.REVIEW, st.step)
        assertEquals("Merchant", st.editMerchant)
        assertEquals(17L, st.receiptId)
    }

    // ---- Review F2: a proposal stays pending while home is unresolved; no carry-over across scans ----

    /** Builds a view model whose home currency source emits nothing until the returned flow emits. */
    private fun modelWithUnresolvedHome(): MutableSharedFlow<String> {
        val source = MutableSharedFlow<String>(extraBufferCapacity = 8)
        deps.homeSource = source
        model = deps.createViewModel()
        settle()
        assertNull("home is unresolved before the first emission", st.homeCurrency)
        return source
    }

    private fun assertPendingProposal() {
        assertEquals(ScanStep.REVIEW, st.step)
        assertNull(st.editCurrency)
        assertEquals(CurrencyRegionUi.Loading, region)
        assertTrue(st.debugData!!.parsedTransactions.isEmpty())
    }

    @Test
    fun `F2 a USD proposal stays pending while home is unresolved then resolves to Selected USD`() {
        deps.store.putRate("USD", 0.9, mecDay(14))
        val home = modelWithUnresolvedHome()

        scan(mecReceipt(currency = "USD"))
        assertPendingProposal()
        assertFalse(st.currencyUnverified)

        home.tryEmit("EUR")
        settle()

        assertEquals("USD", st.editCurrency)
        assertFalse(st.currencyUnverified)
        assertEquals("EUR", st.homeCurrency)
        assertTrue(region is CurrencyRegionUi.Eligible)
        assertFalse(st.isCurrencySaveBlocked)
    }

    @Test
    fun `F2 a BGN proposal stays pending while home is unresolved then resolves to Unset with no token in state`() {
        val home = modelWithUnresolvedHome()

        scan(mecReceipt(currency = "BGN"))
        // An unselectable token is never kept as the edit currency, not even while home is unresolved.
        assertNull(st.editCurrency)
        assertTrue(st.debugData!!.parsedTransactions.isEmpty())

        home.tryEmit("EUR")
        settle()

        assertEquals(CurrencyRegionUi.Unset(CurrencyUnsetReason.PROPOSAL_NOT_SELECTABLE), region)
        assertNull(st.editCurrency)
        assertFalse(st.currencyUnverified)
        assertTrue(st.isCurrencySaveBlocked)
        assertFalse("BGN" in st.currencyOptions)
        assertFalse(region.toString().contains("BGN"))
        assertFalse(st.currencyOptions.toString().contains("BGN"))
        assertTrue(st.debugData!!.parsedTransactions.isEmpty())
        assertTrue(lines.none { it.message.contains("BGN") })

        model.saveExpense()
        settle()
        assertTrue(deps.requests.isEmpty())
    }

    @Test
    fun `F2 a blank proposal stays pending while home is unresolved then resolves to home flagged unverified`() {
        val home = modelWithUnresolvedHome()

        scan(mecReceipt(currency = ""))
        assertPendingProposal()

        home.tryEmit("EUR")
        settle()

        assertEquals("EUR", st.editCurrency)
        assertTrue(st.currencyUnverified)
        assertEquals(CurrencyRegionUi.Home, region)
        assertFalse(st.isCurrencySaveBlocked)
    }

    @Test
    fun `F2 a second scan does not carry over the previous selection`() {
        deps.store.putRate("USD", 0.9, mecDay(14))
        scan(mecReceipt(currency = "USD"))
        model.updateCurrency("GBP")
        settle()
        assertEquals("GBP", st.editCurrency)

        // Unselectable proposal: nothing from the previous scan survives.
        scan(mecReceipt(id = 18L, currency = "BGN"))
        assertNull(st.editCurrency)
        assertTrue(region is CurrencyRegionUi.Unset)

        // Blank proposal: back to home, flagged, never the earlier GBP.
        model.updateCurrency("GBP")
        settle()
        scan(mecReceipt(id = 19L, currency = ""))
        assertEquals("EUR", st.editCurrency)
        assertTrue(st.currencyUnverified)
        assertEquals(CurrencyRegionUi.Home, region)
    }

    @Test
    fun `F2 a second scan before home loads drops the first pending proposal`() {
        deps.store.putRate("USD", 0.9, mecDay(14))
        val home = modelWithUnresolvedHome()

        scan(mecReceipt(currency = "USD"))
        assertPendingProposal()
        scan(mecReceipt(id = 18L, currency = "BGN"))
        assertNull(st.editCurrency)

        home.tryEmit("EUR")
        settle()

        // The second scan's unselectable proposal wins; the first scan's USD is not resurrected.
        assertNull(st.editCurrency)
        assertEquals(CurrencyRegionUi.Unset(CurrencyUnsetReason.PROPOSAL_NOT_SELECTABLE), region)
        assertEquals(18L, st.receiptId)
    }

    @Test
    fun `F2 a user pick made before home loads wins over the pending proposal`() {
        deps.store.putRate("USD", 0.9, mecDay(14))
        deps.store.putRate("GBP", 1.1, mecDay(14))
        val home = modelWithUnresolvedHome()

        scan(mecReceipt(currency = "USD"))
        assertPendingProposal()
        model.updateCurrency("GBP")
        runNow()
        assertEquals("GBP", st.editCurrency)

        home.tryEmit("EUR")
        settle()

        assertEquals("GBP", st.editCurrency)
        assertFalse(st.currencyUnverified)
        assertTrue(region is CurrencyRegionUi.Eligible)
    }

    @Test
    fun `F2 a user pick made before home loads wins over an unselectable pending proposal`() {
        deps.store.putRate("USD", 0.9, mecDay(14))
        val home = modelWithUnresolvedHome()

        scan(mecReceipt(currency = "BGN"))
        model.updateCurrency("USD")
        runNow()
        assertEquals("USD", st.editCurrency)

        home.tryEmit("EUR")
        settle()

        assertEquals("USD", st.editCurrency)
        assertFalse(region is CurrencyRegionUi.Unset)
        assertTrue(region is CurrencyRegionUi.Eligible)
    }

    @Test
    fun `C2 declining the open-setting confirmation keeps the draft`() {
        scan(mecReceipt(currency = "USD", total = 10.0, merchant = "Merchant"))
        model.requestOpenExchangeRateSetting()
        assertEquals(CurrencyOpenSettingRequest.CONFIRM_NEEDED, st.openSettingRequest)

        model.consumeOpenSettingRequest()

        assertEquals(CurrencyOpenSettingRequest.NONE, st.openSettingRequest)
        assertEquals(ScanStep.REVIEW, st.step)
        assertEquals("Merchant", st.editMerchant)
        assertEquals("10.00", st.editAmount)
        assertEquals("USD", st.editCurrency)
        assertTrue(deps.requests.isEmpty())
    }

    // ---- F-B2-A11Y-1: previewAnnounceKey bumps only on currency/date changes ----

    @Test
    fun `A11Y-1 updateCurrency then updateDate each increment the preview announce key`() {
        deps.store.putRate("USD", 0.9, mecDay(14))
        deps.store.putRate("GBP", 1.1, mecDay(14))
        scan(mecReceipt(currency = "USD"))
        val baseline = st.previewAnnounceKey

        model.updateCurrency("GBP")
        settle()
        assertEquals(baseline + 1, st.previewAnnounceKey)

        model.updateDate(MEC_DATE + MEC_DAY_MS)
        settle()
        assertEquals(baseline + 2, st.previewAnnounceKey)
    }

    @Test
    fun `A11Y-1 updateAmount does not change the preview announce key`() {
        deps.store.putRate("USD", 0.9, mecDay(14))
        scan(mecReceipt(currency = "USD"))
        val baseline = st.previewAnnounceKey

        model.updateAmount("11")
        settle()
        model.updateAmount("12.5")
        settle()

        assertEquals(baseline, st.previewAnnounceKey)
    }

    @Test
    fun `A11Y-1 INV-10 a non-selectable currency pick does not change the preview announce key`() {
        scan(mecReceipt(currency = "EUR"))
        val baseline = st.previewAnnounceKey

        for (pick in listOf("BGN", "RUB", "ZZZ", "")) {
            model.updateCurrency(pick)
            settle()
            assertEquals("pick='$pick'", baseline, st.previewAnnounceKey)
        }
    }

    @Test
    fun `A11Y-1 an AI applied date increments the preview announce key`() {
        deps.store.putRate("USD", 0.9, mecDay(14))
        scan(mecReceipt(currency = "USD"))
        val baseline = st.previewAnnounceKey

        setState(st.copy(receiptAssistState = AiLoadState.Ready(
            ReceiptAssistSuggestion(date = SuggestedValue(MEC_DATE + MEC_DAY_MS))
        )))
        model.applyReceiptAssistDate()
        settle()

        assertEquals(MEC_DATE + MEC_DAY_MS, st.editDate)
        assertEquals(baseline + 1, st.previewAnnounceKey)
    }

    @Test
    fun `A11Y-1 the preview announce key survives reset and a new scan (monotonic)`() {
        deps.store.putRate("USD", 0.9, mecDay(14))
        deps.store.putRate("GBP", 1.1, mecDay(14))
        scan(mecReceipt(currency = "USD"))
        model.updateCurrency("GBP")
        settle()
        val k = st.previewAnnounceKey
        assertTrue("key must have incremented above 0", k > 0)

        // New scan after a currency change must not restart the key from 0.
        scan(mecReceipt(id = 18L, currency = "USD"))
        assertTrue("after new scan: ${st.previewAnnounceKey} >= $k", st.previewAnnounceKey >= k)

        // Reset must not restart the key from 0 either.
        val beforeReset = st.previewAnnounceKey
        model.reset()
        settle()
        assertTrue("after reset: ${st.previewAnnounceKey} >= $k", st.previewAnnounceKey >= k)
        assertTrue(st.previewAnnounceKey >= beforeReset)
    }
}
