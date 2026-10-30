package com.yourname.expensetracker.ui.screens.receiptscan

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.ReceiptItemCategorizationRepository
import com.yourname.expensetracker.data.repository.ReceiptRepository
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLinkService
import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.transaction.CreateExpenseResult
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.dto.ReceiptItemCategorizationSnapshot
import com.yourname.expensetracker.domain.ai.model.AiArtifactStatus
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiLoadState
import com.yourname.expensetracker.domain.ai.model.CategoryAssistGenerationResult
import com.yourname.expensetracker.domain.ai.model.CategoryAssistSuggestion
import com.yourname.expensetracker.domain.ai.model.ReceiptAssistGenerationResult
import com.yourname.expensetracker.domain.ai.model.ReceiptAssistSuggestion
import com.yourname.expensetracker.domain.ai.model.toDiagnosticsOrNull
import com.yourname.expensetracker.domain.ai.model.toDisplayText
import com.yourname.expensetracker.domain.ai.service.AiArtifactRepository
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.usecase.CategorizeReceiptItemsUseCase
import com.yourname.expensetracker.domain.ai.usecase.SuggestCategoryFallbackUseCase
import com.yourname.expensetracker.domain.ai.usecase.SuggestReceiptExtractionUseCase
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.AmountCheck
import com.yourname.expensetracker.domain.currency.CurrencyAmountPolicy
import com.yourname.expensetracker.domain.currency.CurrencyEntryMode
import com.yourname.expensetracker.domain.currency.CurrencyRateHistoryOutcome
import com.yourname.expensetracker.domain.currency.CurrencyRateRefreshOutcome
import com.yourname.expensetracker.domain.currency.NoRemedyReason
import com.yourname.expensetracker.domain.currency.RateHistoryFailureReason
import com.yourname.expensetracker.domain.currency.RateRemedy
import com.yourname.expensetracker.domain.currency.RateRemedyDecision
import com.yourname.expensetracker.domain.currency.RateRemedyPolicy
import com.yourname.expensetracker.domain.currency.CurrencyRatesRepository
import com.yourname.expensetracker.domain.currency.CurrencySelection
import com.yourname.expensetracker.domain.currency.CurrencySelectionPolicy
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.CurrencyUnsetReason
import com.yourname.expensetracker.domain.currency.RateEligibility
import com.yourname.expensetracker.domain.currency.UserPresentRatePreview
import com.yourname.expensetracker.domain.currency.UserPresentRatePreviewResult
import com.yourname.expensetracker.domain.privacy.PrivacySettingsLoadState
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.transaction.RateRefusalReason
import com.yourname.expensetracker.domain.transaction.UserPresentRateRequirement
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import com.yourname.expensetracker.ui.components.currency.CurrencyAmountInvalidKind
import com.yourname.expensetracker.ui.components.currency.CurrencyBlockOrigin
import com.yourname.expensetracker.ui.components.currency.CurrencyBlockVariant
import com.yourname.expensetracker.ui.components.currency.CurrencyHistoryStatus
import com.yourname.expensetracker.ui.components.currency.CurrencyOpenSettingRequest
import com.yourname.expensetracker.ui.components.currency.CurrencyRateFixAction
import com.yourname.expensetracker.ui.components.currency.CurrencyRatesSetting
import com.yourname.expensetracker.ui.components.currency.CurrencyRefreshStatus
import com.yourname.expensetracker.ui.components.currency.CurrencyRegionUi
import com.yourname.expensetracker.domain.debug.AiRuntimeDiagnostics
import com.yourname.expensetracker.domain.model.Result
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import com.yourname.expensetracker.domain.parser.ParsedTransactionType
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import com.yourname.expensetracker.domain.receipt.ReceiptProcessingStatus
import com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLifecycleCoordinator
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptStructuredDataPolicy
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.ui.screens.debug.DebugData
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

enum class ScanStep {
    CAPTURE,
    PROCESSING,
    REVIEW,
    DUPLICATE,  // S7-66F-004: receipt already scanned/linked — show duplicate card, hide direct Save
    DONE,
    ERROR
}

data class ReceiptScanState(
    val step: ScanStep = ScanStep.CAPTURE,
    val imageUri: Uri? = null,
    val tempCameraUri: Uri? = null,
    val parsedReceipt: ReceiptParser.ParsedReceipt? = null,
    val receiptId: Long? = null,
    val rawOcrText: String = "",
    val showRawText: Boolean = false,

    // Editable fields
    val editMerchant: String = "",
    val editAmount: String = "",
    /** S7-009: null until home currency loads; never defaults to "EUR" */
    val editCurrency: String? = null,
    val editDate: Long = 0L,
    val selectedCategoryId: Long? = null,
    val paymentMethod: PaymentMethod = PaymentMethod.CARD,
    val notes: String = "",

    // Meta
    val ocrConfidence: Float = 0f,
    val errorMessage: String? = null,
    val isSaving: Boolean = false,
    val saveResult: SaveReceiptResult? = null,
    val receiptAssistState: AiLoadState<ReceiptAssistSuggestion> = AiLoadState.Idle,
    val receiptAssistMessage: String? = null,
    val receiptAssistDiagnostics: String? = null,
    val categoryAssistState: AiLoadState<CategoryAssistSuggestion> = AiLoadState.Idle,
    val categoryAssistMessage: String? = null,
    val categoryAssistDiagnostics: String? = null,
    val receiptQuickSaveEnabled: Boolean = false,
    val quickSavePreview: ReceiptQuickSavePreview? = null,

    // S7-012: track whether user manually edited the amount
    val isAmountEditedByUser: Boolean = false,

    // S7-015: AI capabilities applied to draft — marked in artifact repo only after successful save
    val pendingAppliedAiCapabilities: Set<AiCapability> = emptySet(),

    // Item categorization
    val itemCategorizations: List<ReceiptItemCategorizationSnapshot> = emptyList(),
    val isAnalyzingItems: Boolean = false,
    val showItemBreakdown: Boolean = false,
    val itemAnalysisError: String? = null,
    /** S7-027: false when AI is disabled/blocked so UI can show reason instead of Analyze button */
    val itemAnalysisAvailable: Boolean = true,
    /** S7-024: non-null when rationale dialog should be shown */
    val selectedItemRationale: ReceiptItemCategorizationSnapshot? = null,
    /** S7-025: IDs of items currently being updated (spinner/disabled state) */
    val itemCorrectionUpdatingIds: Set<Long> = emptySet(),
    val itemCorrectionError: String? = null,

    // Debug data
    val debugData: DebugData? = null,
    /** Surviving OCR data is usable, but the original document needs manual verification. */
    val hasPartialOcr: Boolean = false,
    /** A saved parse failure has no trustworthy retained page-coverage evidence. */
    val hasUnverifiedOcrCoverage: Boolean = false,
    /** RG12-01 / V12-NEW-B: the scan resolved to an already-saved receipt (outcome.inserted == false). */
    val reopenedExistingReceipt: Boolean = false,
    /**
     * R24c: the OCR result carried no currency and the home currency was assumed.
     * Quick-save is disabled until the user explicitly picks/confirms a currency;
     * manual save after review stays allowed.
     */
    val currencyUnverified: Boolean = false,

    // MEC C1: the currency/rate region under the amount. Enums, codes and LocalDate only. Session-only:
    // never persisted (no SavedStateHandle) and never logged. editCurrency == null with
    // currencyRegion == Unset means "no currency chosen yet" (Save disabled).
    /** MEC INV-10: options from [CurrencySelectionPolicy] (STANDARD): home first, the others by code. */
    val currencyOptions: List<String> = emptyList(),
    /** MEC: the resolved home currency code; null while home is unresolved. Never inferred from option order. */
    val homeCurrency: String? = null,
    /** MEC: why the amount is invalid (supporting text); non-null only while the region is AmountInvalid. */
    val amountError: UiText? = null,
    /** MEC INV-15/INV-6: the preview or blocked-card state under the amount. */
    val currencyRegion: CurrencyRegionUi = CurrencyRegionUi.Loading,
    /** MEC R23: the exchange-rate download setting; UNKNOWN is treated as OFF. The VM never enables it. */
    val ratesSetting: CurrencyRatesSetting = CurrencyRatesSetting.UNKNOWN,
    /** MEC INV-20: progress and bounded outcome of a user-started refresh. */
    val rateRefresh: CurrencyRefreshStatus = CurrencyRefreshStatus.IDLE,
    /** The device-local calendar date of "today" (injected clock, review zone) for rate-date wording. */
    val ratesToday: LocalDate? = null,
    /**
     * MEC F-B2-A11Y-1: monotonically increasing counter, bumped only when the currency, the date or the
     * home currency changes (never on an amount keystroke); the preview is announced only for its change.
     */
    val previewAnnounceKey: Int = 0,
    /** MEC D-11/D-C-3: the "Open exchange-rate setting" request; the screen shows the confirm and hands over to the host. */
    val openSettingRequest: CurrencyOpenSettingRequest = CurrencyOpenSettingRequest.NONE,
    /** MEC 1b INV-1b-3: progress and bounded outcome of a user-started rate-history download. */
    val historyStatus: CurrencyHistoryStatus = CurrencyHistoryStatus.IDLE,
    /** MEC 1b (ux 1b.2, C13): home rates not downloadable; the history action stays hidden for this review. */
    val historyHomeUnavailable: Boolean = false,
    /** MEC 1b INV-1b-5: the domain remedy chosen by [RateRemedyPolicy] at the last evaluation (enums only). */
    val rateRemedy: RateRemedyDecision = RateRemedyDecision(RateRemedy.REFRESH_LATEST)
) {
    /**
     * MEC 1b (ux 1b.5): the one fix action of the blocked card. Setting Off/Unknown is always
     * OPEN_SETTING (R23, fail closed); otherwise the domain remedy is copied through.
     */
    val rateFixAction: CurrencyRateFixAction
        get() {
            val region = currencyRegion
            if (region !is CurrencyRegionUi.Blocked) return CurrencyRateFixAction.NONE
            if (region.variant != CurrencyBlockVariant.STALE && region.variant != CurrencyBlockVariant.NONE) {
                return CurrencyRateFixAction.NONE
            }
            if (ratesSetting != CurrencyRatesSetting.ON) return CurrencyRateFixAction.OPEN_SETTING
            if (rateRefresh == CurrencyRefreshStatus.FAILED_HOME_UNAVAILABLE) return CurrencyRateFixAction.NONE
            if (historyHomeUnavailable || historyStatus == CurrencyHistoryStatus.FAILED_HOME_UNAVAILABLE) {
                return CurrencyRateFixAction.NONE
            }
            return when (rateRemedy.remedy) {
                RateRemedy.REFRESH_LATEST -> CurrencyRateFixAction.REFRESH_LATEST
                RateRemedy.DOWNLOAD_HISTORY -> CurrencyRateFixAction.DOWNLOAD_HISTORY
                RateRemedy.NONE ->
                    if (rateRemedy.noRemedyReason == NoRemedyReason.OUTSIDE_HISTORY_WINDOW) {
                        CurrencyRateFixAction.TOO_OLD
                    } else {
                        CurrencyRateFixAction.NONE
                    }
            }
        }

    /**
     * MEC ux section 4: Save is disabled for a blocked rate (except a retryable home-unresolved refusal),
     * an invalid amount, or an unset currency. While [CurrencyRegionUi.Evaluating] Save stays enabled
     * (the coordinator is authoritative, INV-6).
     */
    val isCurrencySaveBlocked: Boolean
        get() = currencyRegion is CurrencyRegionUi.Unset ||
            currencyRegion is CurrencyRegionUi.AmountInvalid ||
            (currencyRegion is CurrencyRegionUi.Blocked &&
                currencyRegion.variant != CurrencyBlockVariant.HOME_UNRESOLVED)
}

data class ReceiptQuickSavePreview(
    val merchant: String,
    val amount: Double,
    val amountText: String,
    val date: Long,
    val categoryId: Long?,
    val categoryName: String?,
    val autoAppliedFields: List<String>,
    val usedCapabilities: Set<AiCapability>,
    val fieldSummaries: List<ReceiptQuickSaveFieldSummary>,
    val diagnostics: List<String>
)

data class ReceiptQuickSaveFieldSummary(
    val label: String,
    val value: String,
    val source: String
)

private data class ReceiptSaveRequest(
    val receiptId: Long,
    val merchant: String,
    val amount: Double,
    val currency: String,  // S7-012: captured at validation time — not re-read from live state
    val date: Long,
    val categoryId: Long?,
    val paymentMethod: PaymentMethod,
    val notes: String?,
    /** S7-007: Capabilities to mark applied only after successful save */
    val appliedAiCapabilities: Set<AiCapability> = emptySet(),
    /** MEC G2-14/B1: the review zone captured at request build (never re-read at save time). */
    val entryZone: ZoneId
)

sealed class SaveReceiptResult {
    /** S7-022: Include expenseId so UI can offer "View transaction" */
    data class Success(val expenseId: Long) : SaveReceiptResult()
    data object DuplicateTransaction : SaveReceiptResult()
    data class DuplicateReceipt(val existingReceiptId: Long, val linkedExpenseId: Long?) : SaveReceiptResult()
    data class PartialLinkFailure(val expenseId: Long, val message: String) : SaveReceiptResult()
    data class Error(val message: String) : SaveReceiptResult()

    /**
     * MEC INV-6: the coordinator refused a user-present create for rate reasons. Nothing was saved and the
     * form is kept; the reason is in [ReceiptScanState.currencyRegion] (a SAVE-origin card). Payload-free.
     */
    data object RateNotEligible : SaveReceiptResult()
}

@HiltViewModel
/**
 * Note: [com.yourname.expensetracker.domain.receipt.ReceiptOcrService] is app-scoped
 * (`@Singleton`) and its lifecycle is managed by DI/app process, not by this ViewModel.
 */
class ReceiptScanViewModel @Inject constructor(
    private val receiptRepository: ReceiptRepository,
    private val categoryRepository: CategoryRepository,
    private val currencySettingsRepository: CurrencySettingsRepository,
    private val aiSettingsRepository: AiSettingsRepository,
    private val savedStateHandle: androidx.lifecycle.SavedStateHandle,
    private val timeProvider: TimeProvider,
    private val suggestReceiptExtractionUseCase: SuggestReceiptExtractionUseCase,
    private val suggestCategoryFallbackUseCase: SuggestCategoryFallbackUseCase,
    private val categorizeReceiptItemsUseCase: CategorizeReceiptItemsUseCase,
    private val receiptItemCategorizationRepository: ReceiptItemCategorizationRepository,
    private val aiArtifactRepository: AiArtifactRepository,
    private val aiRuntimeDiagnostics: AiRuntimeDiagnostics,
    private val receiptLifecycleCoordinator: ReceiptLifecycleCoordinator,
    private val receiptParser: ReceiptParser,
    private val transactionLifecycleCoordinator: TransactionLifecycleCoordinator,
    private val receiptLinkService: ReceiptLinkService,
    private val merchantNormalizer: MerchantNormalizer,
    private val hybridClassifier: HybridExpenseClassifier,
    private val userPresentRatePreview: UserPresentRatePreview,
    private val currencyRatesRepository: CurrencyRatesRepository,
    private val privacySettingsRepository: PrivacySettingsRepository
) : ViewModel() {

    private companion object {
        // MEC: bounded diagnostic codes only (INV-19): never an amount, currency, rate, zone, date or throwable.
        const val LOG_RATE_PREVIEW_FAILED = "RATE_PREVIEW_FAILED"
        const val LOG_RATE_REFRESH_FAILED = "RATE_REFRESH_FAILED"
        const val LOG_RATES_SETTING_READ_FAILED = "RATES_SETTING_READ_FAILED"
        const val LOG_RATE_HISTORY_FAILED = "RATE_HISTORY_FAILED"

        // MEC ux section 3: "Checking exchange rate" shows only if an evaluation exceeds this delay.
        const val EVALUATING_DELAY_MS = 250L

        // Bounded message tokens of the existing refresh failures (matched by equality, never displayed).
        const val REFRESH_HOME_QUOTE_UNAVAILABLE = "HOME_CURRENCY_QUOTE_UNAVAILABLE"
        const val REFRESH_INVALID_HOME_CURRENCY = "INVALID_HOME_CURRENCY"
    }

    /** G-TIME-01: the screen's single TimeProvider-backed "now" source. */
    fun referenceNowMillis(): Long = timeProvider.now()

    private val _state = MutableStateFlow(ReceiptScanState(
        tempCameraUri = savedStateHandle.get<Uri>("temp_uri"),
        editDate = timeProvider.now()
    ))
    val state: StateFlow<ReceiptScanState> = _state.asStateFlow()

    val categories: StateFlow<List<Category>> = categoryRepository.allCategories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** S7-003: Cancel prior scan job when a new image is selected. */
    private var scanJob: Job? = null
    private var scanRequestSeq = 0L

    private var itemAnalysisJob: Job? = null

    /** S7-016: In-flight guard for AI assist requests. */
    private val inFlightAssist = mutableSetOf<String>()

    /** Last home currency loaded by the init collector; re-applied by reset()/retry(). */
    private var homeCurrencyCode: String? = null

    // MEC G2-14/RF-6: the entry zone captured when the step becomes REVIEW (and by onReviewOpened()).
    // A plain field: never in state, never persisted or logged. Cleared at scan start, reset, retry and
    // cancelActiveWork. Save falls back to the device zone when it is null.
    private var reviewZone: ZoneId? = null

    // MEC review F2: an OCR currency proposal held while the home currency is unresolved. Only a parsed
    // CurrencyCode (or null = none proposed) is ever held, never the raw token; never in state or logs.
    // Cleared at scan start, reset, retry and cancelActiveWork; consumed by applyPendingProposal().
    private var pendingProposal: CurrencyCode? = null
    private var proposalAwaitingHome: Boolean = false

    // MEC INV-3: latest-wins evaluation, and the single user-started refresh.
    private var evaluationJob: Job? = null
    private var refreshJob: Job? = null

    // MEC 1b INV-1b-3/6: the single user-started history download and its session-only data. Plain fields,
    // never in state beyond the status enum, never persisted or logged; cleared with the review.
    private var historyJob: Job? = null
    private var historyDownloadedThisSession: Boolean = false
    private var historyEarliestDate: LocalDate? = null
    private var historyAnnouncePending: Boolean = false

    init {
        // S7-009: Load home currency on init so editCurrency is never a hardcoded "EUR"
        viewModelScope.launch {
            currencySettingsRepository.homeCurrency().collect { homeCurrency ->
                homeCurrencyCode = homeCurrency
                // Review F2: a proposal held while home was unresolved is resolved below, not defaulted to home.
                val holdForProposal = proposalAwaitingHome &&
                    homeCurrency?.let { CurrencyCode.parse(it) } != null
                if (proposalAwaitingHome && !holdForProposal) {
                    proposalAwaitingHome = false
                    pendingProposal = null
                }
                _state.update { current ->
                    // MEC 1b INV-1b-20 (receipt): an in-review selection that was only the assumed home and
                    // cannot follow a changed home becomes Unset (fields kept, Save blocked); never
                    // re-denominated. An explicit (proposed or picked) code is kept as is.
                    val priorHome = current.homeCurrency
                    val assumedHomeLost = current.step == ScanStep.REVIEW && priorHome != null &&
                        homeCurrency != null && priorHome != homeCurrency &&
                        current.editCurrency == priorHome && current.currencyUnverified
                    current.copy(
                        // An Unset region keeps editCurrency null on purpose (no silent swap to home).
                        editCurrency = if (assumedHomeLost) {
                            null
                        } else {
                            current.editCurrency
                                ?: homeCurrency.takeUnless {
                                    holdForProposal || current.currencyRegion is CurrencyRegionUi.Unset
                                }
                        },
                        currencyUnverified = current.currencyUnverified && !assumedHomeLost,
                        currencyRegion = if (assumedHomeLost) {
                            CurrencyRegionUi.Unset(CurrencyUnsetReason.NONE_PROPOSED)
                        } else {
                            current.currencyRegion
                        },
                        currencyOptions = optionsFor(homeCurrency),
                        homeCurrency = homeCurrency,
                        previewAnnounceKey = current.previewAnnounceKey + 1
                    )
                }
                applyPendingProposal()
                reevaluateRegion(invalidate = true)
            }
        }
        viewModelScope.launch {
            // R23: the setting is read, never written. Loaded/FirstRunDefault map by flag; a corrupted
            // (fail-closed) state or a read error is Unknown, which the UI treats as Off.
            privacySettingsRepository.observeLoadState()
                .map { loadState ->
                    when (loadState) {
                        is PrivacySettingsLoadState.Loaded ->
                            if (loadState.settings.exchangeRateDownloadEnabled) CurrencyRatesSetting.ON else CurrencyRatesSetting.OFF
                        is PrivacySettingsLoadState.FirstRunDefault ->
                            if (loadState.settings.exchangeRateDownloadEnabled) CurrencyRatesSetting.ON else CurrencyRatesSetting.OFF
                        is PrivacySettingsLoadState.CorruptedFailClosed -> CurrencyRatesSetting.UNKNOWN
                    }
                }
                .distinctUntilChanged()
                .catch { e ->
                    if (e is CancellationException) throw e
                    Timber.w(LOG_RATES_SETTING_READ_FAILED)
                    emit(CurrencyRatesSetting.UNKNOWN)
                }
                .collect { setting -> _state.update { it.copy(ratesSetting = setting) } }
        }
        viewModelScope.launch {
            aiSettingsRepository.settings().collect { settings ->
                _state.update {
                    val enabled = settings.aiEnabled && settings.receiptQuickSaveEnabled
                    it.copy(
                        receiptQuickSaveEnabled = enabled,
                        quickSavePreview = if (enabled) it.quickSavePreview else null
                    )
                }
            }
        }
    }

    /**
     * Create a URI for camera to write photo to
     */
    fun createTempPhotoUri(): Uri {
        val uri = receiptRepository.createTempPhotoUri()
        savedStateHandle["temp_uri"] = uri
        _state.update { it.copy(tempCameraUri = uri) }
        return uri
    }

    /**
     * Called after camera successfully captures a photo
     */
    fun processPhoto() {
        val uri = _state.value.tempCameraUri ?: return
        processImageUri(uri)
    }

    /**
     * Called when user selects image from gallery
     */
    fun processGalleryImage(uri: Uri) {
        processImageUri(uri)
    }

    private fun processImageUri(uri: Uri) {
        // S7-003: Cancel prior scan and increment request ID to detect stale results
        val requestId = ++scanRequestSeq
        scanJob?.cancel()
        itemAnalysisJob?.cancel()
        // MEC constraint 1/7: a new scan returns to home and drops the previous review zone, so no
        // currency selection or zone carries over across receipts.
        reviewZone = null
        pendingProposal = null
        proposalAwaitingHome = false
        // MEC 1b: a new receipt starts a new review; the history session data and status do not carry over.
        clearHistorySession()

        _state.update {
            it.copy(
                historyStatus = CurrencyHistoryStatus.IDLE,
                historyHomeUnavailable = false,
                step = ScanStep.PROCESSING,
                imageUri = uri,
                errorMessage = null,
                hasPartialOcr = false,
                hasUnverifiedOcrCoverage = false,
                // Review F2: no carry-over across scans; null while home is unresolved (like the amount reset).
                editCurrency = homeCurrencyCode,
                currencyUnverified = false,
                saveResult = it.saveResult.takeUnless { r -> r is SaveReceiptResult.RateNotEligible },
                reopenedExistingReceipt = false,
                pendingAppliedAiCapabilities = emptySet(),
                receiptAssistState = AiLoadState.Idle,
                receiptAssistMessage = null,
                receiptAssistDiagnostics = null,
                categoryAssistState = AiLoadState.Idle,
                categoryAssistMessage = null,
                categoryAssistDiagnostics = null,
                quickSavePreview = null
            ).clearItemAnalysisState()
        }
        // The region follows the reset currency (home) and cancels any in-flight evaluation.
        reevaluateRegion(invalidate = true)

        scanJob = viewModelScope.launch {
            val startTime = timeProvider.now()
            val parsingLogs = mutableListOf<String>()
            var failureCode = "UNKNOWN_ERROR"

            try {
                // S7-015: Disable auto-match for interactive scan — user must confirm save first
                val receiptResult = receiptLifecycleCoordinator.processReceiptInput(
                    uri,
                    com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLifecycleCoordinator.ReceiptProcessingOptions(
                        createReview = false,
                        autoMatchExistingExpense = false
                    )
                )
                if (receiptResult.isFailure) throw receiptResult.exceptionOrNull()!!
                // RP-12 12a (P3-001): coordinator returns a typed outcome; duplicates
                // arrive as a success outcome with inserted=false and the existing receipt.
                val outcome = receiptResult.getOrThrow()
                val receipt = outcome.savedReceipt

                // S7-003: Discard result if a newer scan has started
                if (requestId != scanRequestSeq) return@launch

                // S7-F583-002/S7-66F-003: If the receipt is already linked or is a known duplicate,
                // show DUPLICATE state instead of normal review — block direct save.
                val linkedExpenseId = receipt.expenseId
                failureCode = "SOURCE_LINK_FAILED"
                val isAlreadyLinked = linkedExpenseId != null ||
                    !receiptLinkService.checkCanLinkReceipt(receipt.id)
                failureCode = "UNKNOWN_ERROR"
                if (isAlreadyLinked) {
                    _state.update {
                        it.copy(
                            step = ScanStep.DUPLICATE,
                            receiptId = receipt.id,
                            hasPartialOcr = outcome.isPartial,
                            hasUnverifiedOcrCoverage = outcome.ocrCoverageUnavailable,
                            saveResult = SaveReceiptResult.DuplicateReceipt(
                                existingReceiptId = receipt.id,
                                linkedExpenseId = linkedExpenseId
                            ),
                            errorMessage = if (linkedExpenseId != null)
                                "This receipt is already linked to an existing transaction."
                            else
                                "This receipt was already scanned. Use Receipt Matching to link it.",
                            isSaving = false
                        )
                    }
                    return@launch
                }

                val isOcrFailure = receipt.processingStatus == ReceiptProcessingStatus.OCR_FAILED.name

                if (isOcrFailure) {
                    val now = timeProvider.now()
                    val debugData = DebugData(
                        rawText = "",
                        parsedTransactions = emptyList(),
                        parsingLogs = parsingLogs.also { it.add("Processing Error: OCR_FAILED") },
                        processingTimeMs = timeProvider.now() - startTime,
                        parserUsed = "Manual (OCR Failed)"
                    )
                    // S7-009: Use loaded home currency, not hardcoded "EUR"
                    val homeCurrency = homeCurrencyCode ?: _state.value.editCurrency
                    // MEC RF-6/G2-14: the review zone is captured when the step becomes REVIEW.
                    reviewZone = ZoneId.systemDefault()
                    _state.update {
                        it.copy(
                            step = ScanStep.REVIEW,
                            imageUri = receipt.imagePath?.let { p -> Uri.fromFile(java.io.File(p)) } ?: uri,
                            tempCameraUri = null,
                            parsedReceipt = ReceiptParser.ParsedReceipt(
                                merchantName = null, total = null, subtotal = null, tax = null,
                                date = now, currency = homeCurrency.orEmpty(), lineItems = emptyList(), confidence = 0f
                            ),
                            receiptId = receipt.id,
                            reopenedExistingReceipt = !outcome.inserted,
                            rawOcrText = "",
                            showRawText = false,
                            editMerchant = "",
                            editAmount = "",
                            editCurrency = homeCurrency,
                            // R24c: the home currency is only assumed here (no OCR result)
                            currencyUnverified = true,
                            editDate = now,
                            selectedCategoryId = null,
                            paymentMethod = PaymentMethod.CARD,
                            notes = "",
                            ocrConfidence = 0f,
                            errorMessage = "OCR could not be processed (OCR_FAILED). You can enter details manually.",
                            isSaving = false,
                            saveResult = null,
                            receiptAssistState = AiLoadState.Idle,
                            receiptAssistMessage = null,
                            receiptAssistDiagnostics = null,
                            categoryAssistState = AiLoadState.Idle,
                            categoryAssistMessage = null,
                            categoryAssistDiagnostics = null,
                            quickSavePreview = null,
                            debugData = debugData
                        ).clearItemAnalysisState()
                    }
                    reevaluateRegion(invalidate = true)
                } else {
                    // RP-12 12b (P3-007): redacted persisted items carry prices
                    // only — never parse them as full line items; item-dependent
                    // flows degrade gracefully (structured data unavailable).
                    val structuredUnavailable =
                        ReceiptStructuredDataPolicy.isRedactedItemsJson(receipt.parsedItems)
                    val lineItems = if (structuredUnavailable) {
                        emptyList()
                    } else {
                        receipt.parsedItems?.let {
                            try { receiptParser.lineItemsFromJson(it) }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { emptyList() }
                        } ?: emptyList()
                    }

                    val computedTaxInclusive = ReceiptParser.isTaxInclusive(
                        receipt.parsedTotal, receipt.parsedTaxAmount, lineItems, receipt.currency
                    )
                    val parsed = ReceiptParser.ParsedReceipt(
                        merchantName = receipt.parsedMerchant,
                        total = ReceiptParser.sourceMoneyOrNull(receipt.parsedTotal, receipt.currency),
                        subtotal = null,
                        tax = ReceiptParser.sourceMoneyOrNull(receipt.parsedTaxAmount, receipt.currency),
                        date = receipt.parsedDate,
                        currency = receipt.currency,
                        lineItems = lineItems,
                        confidence = receipt.confidence,
                        taxInclusive = receipt.taxInclusive || computedTaxInclusive
                    )

                    // MEC INV-11: the OCR currency is a proposal; an unsupported one is never swapped for home.
                    val proposal = resolveReviewProposal(parsed.currency)
                    val debugData = DebugData(
                        rawText = receipt.rawOcrText,
                        parsedTransactions = listOfNotNull(
                            // MEC privacy: with an unselectable raw OCR currency token no debug transaction is built,
                            // so the token is not kept in debug data (ParsedTransaction requires a valid ISO code).
                            parsed.total?.amount?.takeIf { proposal.unsetRegion == null && !proposal.awaitingHome }?.let { total ->
                                ParsedTransaction(
                                    amount = total,
                                    currency = parsed.currency,
                                    merchant = parsed.merchantName ?: "Unknown",
                                    type = ParsedTransactionType.PURCHASE,
                                    confidence = parsed.confidence,
                                    date = parsed.date,
                                    validationNowEpochMs = timeProvider.now()
                                )
                            }
                        ),
                        parsingLogs = if (parsed.confidence < 0.7f) {
                            listOf("Low confidence parsing (${(parsed.confidence * 100).toInt()}%)")
                        } else emptyList(),
                        processingTimeMs = timeProvider.now() - startTime,
                        parserUsed = "ReceiptParser"
                    )

                    // MEC RF-6/G2-14: the review zone is captured when the step becomes REVIEW.
                    reviewZone = ZoneId.systemDefault()
                    _state.update {
                        it.copy(
                            step = ScanStep.REVIEW,
                            imageUri = receipt.imagePath?.let { p -> Uri.fromFile(java.io.File(p)) } ?: uri,
                            parsedReceipt = parsed,
                            receiptId = receipt.id,
                            reopenedExistingReceipt = !outcome.inserted,
                            hasPartialOcr = outcome.isPartial,
                            hasUnverifiedOcrCoverage = outcome.ocrCoverageUnavailable,
                            rawOcrText = receipt.rawOcrText,
                            editMerchant = parsed.merchantName ?: "",
                            // S7-011: Locale.US for consistent decimal formatting
                            editAmount = parsed.total?.amount?.let { total ->
                                String.format(java.util.Locale.US, "%.2f", total)
                            } ?: "",
                            // S7-009 / MEC INV-11: a selectable OCR currency is used; a blank one means home is
                            // only assumed (R24c); an unsupported one leaves the currency unset.
                            editCurrency = proposal.editCurrency
                                ?: if (proposal.unsetRegion == null && !proposal.awaitingHome) it.editCurrency else null,
                            currencyUnverified = proposal.currencyUnverified,
                            currencyRegion = proposal.unsetRegion
                                ?: if (proposal.awaitingHome) CurrencyRegionUi.Loading else it.currencyRegion,
                            amountError = if (proposal.unsetRegion != null) null else it.amountError,
                            editDate = parsed.date ?: timeProvider.now(),
                            ocrConfidence = parsed.confidence,
                            selectedCategoryId = null,
                            receiptAssistState = AiLoadState.Idle,
                            receiptAssistMessage = null,
                            receiptAssistDiagnostics = null,
                            categoryAssistState = AiLoadState.Idle,
                            categoryAssistMessage = null,
                            categoryAssistDiagnostics = null,
                            quickSavePreview = null,
                            debugData = debugData
                        ).clearItemAnalysisState()
                    }
                    reevaluateRegion(invalidate = true)

                    if (lineItems.isNotEmpty()) {
                        itemAnalysisJob?.cancel()
                        itemAnalysisJob = viewModelScope.launch {
                            runCatching {
                                val settings = aiSettingsRepository.settings().first()
                                if (settings.aiEnabled && settings.receiptItemCategorizationEnabled) {
                                    analyzeReceiptItemsInternal(receipt.id)
                                } else {
                                    // S7-027: Mark analysis unavailable so UI shows reason
                                    _state.update { s -> s.copy(itemAnalysisAvailable = false) }
                                }
                            }.onFailure { e ->
                                if (e is CancellationException) throw e
                                _state.update { s -> s.copy(itemAnalysisError = "Item analysis unavailable") }
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // S7-003: Discard error if a newer scan has started
                if (requestId != scanRequestSeq) return@launch
                if (failureCode == "UNKNOWN_ERROR" && e is org.json.JSONException) failureCode = "PARSER_FAILED"
                Timber.w("ReceiptScan: %s stage=scan class=%s", failureCode, e::class.java.simpleName)
                parsingLogs.clear()
                parsingLogs.add("Processing Error: $failureCode")
                val now = timeProvider.now()
                _state.update {
                    it.copy(
                        step = ScanStep.ERROR,
                        imageUri = uri,
                        tempCameraUri = null,
                        parsedReceipt = null,
                        receiptId = null,
                        rawOcrText = "",
                        showRawText = false,
                        editMerchant = "",
                        editAmount = "",
                        editDate = now,
                        selectedCategoryId = null,
                        paymentMethod = PaymentMethod.CARD,
                        notes = "",
                        ocrConfidence = 0f,
                        errorMessage = "Receipt processing failed ($failureCode)",
                        isSaving = false,
                        saveResult = null,
                        receiptAssistState = AiLoadState.Idle,
                        receiptAssistMessage = null,
                        receiptAssistDiagnostics = null,
                        categoryAssistState = AiLoadState.Idle,
                        categoryAssistMessage = null,
                        categoryAssistDiagnostics = null,
                        quickSavePreview = null,
                        debugData = DebugData(
                            rawText = "",
                            parsedTransactions = emptyList(),
                            parsingLogs = parsingLogs,
                            processingTimeMs = timeProvider.now() - startTime,
                            parserUsed = "Failed"
                        )
                    ).clearItemAnalysisState()
                }
            }
        }
    }

    fun updateMerchant(value: String) {
        _state.update { it.copy(editMerchant = value) }
    }

    fun updateAmount(value: String) {
        // S7-013: Use shared sanitizer — consistent with Add Expense and other money fields
        val sanitized = com.yourname.expensetracker.ui.util.AmountInputSanitizer.sanitize(value)
        _state.update {
            it.copy(
                editAmount = sanitized,
                isAmountEditedByUser = true,
                saveResult = it.saveResult.takeUnless { r -> r is SaveReceiptResult.RateNotEligible }
            )
        }
        // MEC INV-3: the figure changed; drop to Pending at once so a converted amount for the previous
        // figure (or a SAVE-origin card) is never shown for the new one. MEC R-4: a Blocked card that
        // carries no amount is kept, so the card and Save do not toggle per keystroke.
        reevaluateRegion(invalidate = !_state.value.currencyRegion.isAmountIndependentBlock())
    }

    fun updateDate(dateMs: Long) {
        _state.update {
            it.copy(
                editDate = dateMs,
                previewAnnounceKey = it.previewAnnounceKey + 1,
                saveResult = it.saveResult.takeUnless { r -> r is SaveReceiptResult.RateNotEligible }
            )
        }
        // MEC INV-3: a new date can change eligibility.
        reevaluateRegion(invalidate = true)
    }

    /**
     * RCP-10/N2: User can change the currency of the scanned receipt
     * before saving. Default is the OCR-detected currency or home currency.
     * MEC INV-10/11: ignored unless the pick parses to a currency code and, when the home currency is
     * known, is selectable for it.
     */
    fun updateCurrency(currency: String) {
        val chosen = CurrencyCode.parse(currency) ?: return
        val homeCode = homeCurrencyCode?.let { CurrencyCode.parse(it) }
        if (homeCode != null &&
            !CurrencySelectionPolicy.isSelectable(chosen, homeCode, CurrencyEntryMode.STANDARD)
        ) {
            return
        }
        // R24c: any explicit pick (including re-selecting the assumed home currency) confirms it
        _state.update {
            it.copy(
                editCurrency = currency,
                currencyUnverified = false,
                previewAnnounceKey = it.previewAnnounceKey + 1,
                saveResult = it.saveResult.takeUnless { r -> r is SaveReceiptResult.RateNotEligible }
            )
        }
        reevaluateRegion(invalidate = true)
    }

    /**
     * MEC AE-34/G2-14: called by the screen when the review is shown. Captures the device zone once per
     * review (null-guarded) so a zone change while reviewing cannot move the expense's local date. A
     * configuration change keeps the captured zone; a new scan, reset and retry clear it.
     */
    fun onReviewOpened() {
        if (_state.value.step != ScanStep.REVIEW) return
        if (reviewZone == null) {
            reviewZone = ZoneId.systemDefault()
            // MEC INV-3: the captured zone may differ from the one used so far.
            reevaluateRegion(invalidate = true)
        }
    }

    /**
     * MEC D-11/D-C-3: the user tapped "Open exchange-rate setting". If an amount was entered the host must
     * confirm first ([CurrencyOpenSettingRequest.CONFIRM_NEEDED]); otherwise navigate now. The VM never
     * enables the setting.
     */
    fun requestOpenExchangeRateSetting() {
        _state.update {
            it.copy(
                openSettingRequest = if (it.editAmount.isNotBlank()) {
                    CurrencyOpenSettingRequest.CONFIRM_NEEDED
                } else {
                    CurrencyOpenSettingRequest.NAVIGATE
                }
            )
        }
    }

    /**
     * The user confirmed leaving the review to open the setting. The confirmation copy promises the entry
     * is discarded, so reset first, then raise NAVIGATE exactly like the blank-amount path.
     */
    fun confirmOpenExchangeRateSetting() {
        if (_state.value.openSettingRequest != CurrencyOpenSettingRequest.CONFIRM_NEEDED) return
        reset()
        _state.update { it.copy(openSettingRequest = CurrencyOpenSettingRequest.NAVIGATE) }
    }

    /** The user declined the confirmation or the host finished navigating: clears the request. */
    fun consumeOpenSettingRequest() {
        _state.update { it.copy(openSettingRequest = CurrencyOpenSettingRequest.NONE) }
    }

    /** MEC R-4: true for a Blocked region whose variant carries no amount-dependent data. */
    private fun CurrencyRegionUi.isAmountIndependentBlock(): Boolean =
        this is CurrencyRegionUi.Blocked && when (variant) {
            CurrencyBlockVariant.NONE,
            CurrencyBlockVariant.STALE,
            CurrencyBlockVariant.UNSUPPORTED,
            CurrencyBlockVariant.HOME_UNRESOLVED -> true
        }

    /** MEC INV-10: STANDARD options (home first, the others by code); just [home] if it does not parse. */
    private fun optionsFor(home: String?): List<String> {
        if (home == null) return emptyList()
        val homeCode = CurrencyCode.parse(home) ?: return listOf(home)
        return CurrencySelectionPolicy.selectable(homeCode, CurrencyEntryMode.STANDARD).map { it.code }
    }

    /** The outcome of resolving the scanned receipt's currency token into the editable state. */
    private class ProposalOutcome(
        /** The currency to edit; null together with a null [unsetRegion] keeps the current one. */
        val editCurrency: String?,
        val currencyUnverified: Boolean,
        /** Non-null when the proposal is not selectable: no currency, Save blocked, no OCR token kept. */
        val unsetRegion: CurrencyRegionUi.Unset?,
        /** True while the home currency is unresolved: no currency yet, region Loading, proposal held privately. */
        val awaitingHome: Boolean = false
    )

    /**
     * MEC INV-11: resolves the OCR currency [token] through [CurrencySelectionPolicy.resolveProposal].
     * Selected -> that currency; NONE_PROPOSED (blank) -> home, flagged unverified (R24c);
     * PROPOSAL_NOT_SELECTABLE -> unset, and the token is not kept. While the home currency is unresolved
     * (review F2) the proposal stays pending: only a parsed [CurrencyCode] (or none) is held in a private
     * field, the raw token never, and [applyPendingProposal] resolves it when home loads.
     */
    private fun resolveReviewProposal(token: String): ProposalOutcome {
        val home = homeCurrencyCode
        val homeCode = home?.let { CurrencyCode.parse(it) }
        val proposed = if (token.isBlank()) null else CurrencyCode.parse(token)
        if (token.isNotBlank() && proposed == null) {
            pendingProposal = null
            proposalAwaitingHome = false
            return ProposalOutcome(null, false, CurrencyRegionUi.Unset(CurrencyUnsetReason.PROPOSAL_NOT_SELECTABLE))
        }
        if (home == null || homeCode == null) {
            pendingProposal = proposed
            proposalAwaitingHome = true
            return ProposalOutcome(null, false, null, awaitingHome = true)
        }
        pendingProposal = null
        proposalAwaitingHome = false
        return resolveProposalWithHome(proposed, home, homeCode)
    }

    private fun resolveProposalWithHome(proposed: CurrencyCode?, home: String, homeCode: CurrencyCode): ProposalOutcome {
        val resolution = CurrencySelectionPolicy.resolveProposal(proposed, homeCode, CurrencyEntryMode.STANDARD)
        return if (resolution is CurrencySelection.Selected) {
            ProposalOutcome(resolution.code.code, false, null)
        } else if (resolution is CurrencySelection.Unset && resolution.reason == CurrencyUnsetReason.NONE_PROPOSED) {
            ProposalOutcome(home, true, null)
        } else {
            ProposalOutcome(null, false, CurrencyRegionUi.Unset(CurrencyUnsetReason.PROPOSAL_NOT_SELECTABLE))
        }
    }

    /**
     * Review F2: called by the home collector once [homeCurrencyCode] is set. Resolves a proposal held
     * while home was unresolved (Selected / Unset / NONE_PROPOSED -> home). An explicit user pick made in the
     * meantime wins. No-op when nothing is pending.
     */
    private fun applyPendingProposal() {
        if (!proposalAwaitingHome) return
        val home = homeCurrencyCode ?: return
        val homeCode = CurrencyCode.parse(home) ?: return
        val proposed = pendingProposal
        proposalAwaitingHome = false
        pendingProposal = null
        val outcome = resolveProposalWithHome(proposed, home, homeCode)
        _state.update {
            if (it.step != ScanStep.REVIEW || it.editCurrency != null) {
                it
            } else {
                it.copy(
                    editCurrency = outcome.editCurrency,
                    currencyUnverified = outcome.currencyUnverified,
                    currencyRegion = outcome.unsetRegion ?: it.currencyRegion,
                    amountError = if (outcome.unsetRegion != null) null else it.amountError
                )
            }
        }
    }

    fun selectCategory(categoryId: Long) {
        _state.update { it.copy(selectedCategoryId = categoryId) }
    }

    fun selectPaymentMethod(method: PaymentMethod) {
        _state.update { it.copy(paymentMethod = method) }
    }

    fun updateNotes(value: String) {
        _state.update { it.copy(notes = value) }
    }

    fun toggleRawText() {
        _state.update { it.copy(showRawText = !it.showRawText) }
    }

    fun shouldOfferReceiptAssist(): Boolean {
        val currentState = _state.value
        if (currentState.step != ScanStep.REVIEW || currentState.receiptId == null) return false
        if (currentState.rawOcrText.isBlank()) return false
        return true
    }

    fun shouldOfferCategoryAssist(): Boolean {
        val currentState = _state.value
        if (currentState.step != ScanStep.REVIEW || currentState.receiptId == null) return false
        if (currentState.selectedCategoryId == null) return true
        return currentState.ocrConfidence < com.yourname.expensetracker.domain.config.AppConfig.Ai.MIN_CATEGORY_CONFIDENCE_FOR_AI_FALLBACK
    }

    fun canOfferReceiptQuickSave(): Boolean = buildQuickSavePreview(_state.value) != null

    fun quickSaveUnavailableReason(): String? {
        val currentState = _state.value
        if (!currentState.receiptQuickSaveEnabled || currentState.step != ScanStep.REVIEW || currentState.receiptId == null) {
            return null
        }
        if (buildQuickSavePreview(currentState) != null) return null

        val receiptSuggestion = (currentState.receiptAssistState as? AiLoadState.Ready)?.value
        val hasMerchant = currentState.editMerchant.isNotBlank() || !receiptSuggestion?.merchant?.value.isNullOrBlank()
        val hasAmount = com.yourname.expensetracker.ui.util.AmountInputSanitizer.parseOrNull(currentState.editAmount)?.let { it > 0 } == true ||
            (receiptSuggestion?.total?.value?.let { it > 0 } == true)

        return when {
            currentState.currencyUnverified -> {
                "Quick save is off until you confirm the receipt currency."
            }
            currentState.receiptAssistState is AiLoadState.Idle && currentState.categoryAssistState is AiLoadState.Idle -> {
                "Request AI receipt or category assist first."
            }
            !hasMerchant -> {
                "Quick save still needs a merchant from the draft or AI receipt assist."
            }
            !hasAmount -> {
                "Quick save still needs a valid amount from the draft or AI receipt assist."
            }
            else -> {
                "Quick save appears when AI can safely fill at least one missing field."
            }
        }
    }

    fun requestReceiptAssist(force: Boolean = false) {
        val receiptId = _state.value.receiptId ?: return
        // S7-004: Scope key by receiptId so Receipt A result cannot overwrite Receipt B
        val key = "receipt_assist:$receiptId"
        if (!inFlightAssist.add(key)) return

        viewModelScope.launch {
            _state.update {
                it.copy(
                    receiptAssistState = AiLoadState.Loading,
                    receiptAssistMessage = null,
                    receiptAssistDiagnostics = null,
                    errorMessage = null
                )
            }
            try {
                when (val result = suggestReceiptExtractionUseCase(receiptId, force = force)) {
                    is ReceiptAssistGenerationResult.Success -> {
                        val diagnostics = latestArtifactDiagnostics(
                            targetKey = "scanned_receipt:$receiptId",
                            capability = AiCapability.RECEIPT_EXTRACTION
                        )
                        // S7-004: Guard — only update if still on the same receipt
                        _state.update { current ->
                            if (current.receiptId != receiptId) current
                            else current.copy(
                                receiptAssistState = AiLoadState.Ready(result.suggestion),
                                receiptAssistDiagnostics = diagnostics,
                                receiptAssistMessage = when {
                                    result.fromCache -> "Showing cached AI receipt suggestions."
                                    result.usedImageInput -> "Image-aware AI cross-checked the receipt photo and OCR text."
                                    else -> "AI suggested a few receipt fields to review."
                                }
                            )
                        }
                    }
                    is ReceiptAssistGenerationResult.Disabled -> {
                        _state.update { current ->
                            if (current.receiptId != receiptId) current
                            else current.copy(receiptAssistState = AiLoadState.Disabled, receiptAssistDiagnostics = null, receiptAssistMessage = result.reason)
                        }
                    }
                    is ReceiptAssistGenerationResult.NotNeeded -> {
                        _state.update { current ->
                            if (current.receiptId != receiptId) current
                            else current.copy(receiptAssistState = AiLoadState.Idle, receiptAssistDiagnostics = null, receiptAssistMessage = result.reason)
                        }
                    }
                    is ReceiptAssistGenerationResult.Error -> {
                        val diagnostics = latestArtifactDiagnostics(
                            targetKey = "scanned_receipt:$receiptId",
                            capability = AiCapability.RECEIPT_EXTRACTION,
                            expectedStatus = AiArtifactStatus.FAILED
                        )
                        _state.update { current ->
                            if (current.receiptId != receiptId) current
                            else current.copy(receiptAssistState = AiLoadState.Error(result.reason), receiptAssistDiagnostics = diagnostics, receiptAssistMessage = result.reason)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e("ReceiptScan: receipt_assist UNKNOWN_ERROR class=%s", e::class.java.simpleName)
                _state.update { current ->
                    if (current.receiptId != receiptId) current
                    else current.copy(receiptAssistState = AiLoadState.Error("Receipt assist failed (UNKNOWN_ERROR)"))
                }
            } finally {
                inFlightAssist.remove(key)
            }
        }
    }

    fun dismissReceiptAssist() {
        val receiptId = _state.value.receiptId ?: return
        val targetKey = "scanned_receipt:$receiptId"

        viewModelScope.launch {
            aiArtifactRepository.getLatest(
                targetKey = targetKey,
                capability = com.yourname.expensetracker.domain.ai.model.AiCapability.RECEIPT_EXTRACTION
            )?.let { artifact ->
                aiArtifactRepository.markDismissed(artifact.id)
            }
            _state.update {
                it.copy(
                    receiptAssistState = AiLoadState.Idle,
                    receiptAssistDiagnostics = null,
                    receiptAssistMessage = "AI receipt suggestions dismissed."
                )
            }
        }
    }

    fun requestCategoryAssist(force: Boolean = false) {
        val currentState = _state.value
        val receiptId = currentState.receiptId ?: return
        // S7-F583-003: Scope key by receiptId — same as receipt assist
        val key = "category_assist:$receiptId"
        if (!inFlightAssist.add(key)) return

        viewModelScope.launch {
            val receipt = receiptRepository.getReceiptById(receiptId)
            if (receipt == null) {
                inFlightAssist.remove(key)
                _state.update { current ->
                    if (current.receiptId != receiptId) current
                    else current.copy(categoryAssistState = AiLoadState.Error("Receipt not found"), categoryAssistDiagnostics = null, categoryAssistMessage = "Receipt not found")
                }
                return@launch
            }

            _state.update { current ->
                if (current.receiptId != receiptId) current
                else current.copy(categoryAssistState = AiLoadState.Loading, categoryAssistMessage = null, categoryAssistDiagnostics = null, errorMessage = null)
            }
            try {
                when (val result = suggestCategoryFallbackUseCase(
                    receipt = receipt,
                    draftMerchant = currentState.editMerchant,
                    draftAmount = com.yourname.expensetracker.ui.util.AmountInputSanitizer.parseOrNull(currentState.editAmount),
                    draftDate = currentState.editDate.takeIf { it > 0L },
                    currentCategoryId = currentState.selectedCategoryId,
                    force = force
                )) {
                    is CategoryAssistGenerationResult.Success -> {
                        val diagnostics = latestArtifactDiagnostics("scanned_receipt:$receiptId", AiCapability.CATEGORIZATION_FALLBACK)
                        _state.update { current ->
                            if (current.receiptId != receiptId) current
                            else current.copy(
                                categoryAssistState = AiLoadState.Ready(result.suggestion),
                                categoryAssistDiagnostics = diagnostics,
                                categoryAssistMessage = if (result.fromCache) "Showing cached AI category suggestion." else "AI suggested a category to review."
                            )
                        }
                    }
                    is CategoryAssistGenerationResult.Disabled -> {
                        _state.update { current ->
                            if (current.receiptId != receiptId) current
                            else current.copy(categoryAssistState = AiLoadState.Disabled, categoryAssistDiagnostics = null, categoryAssistMessage = result.reason)
                        }
                    }
                    is CategoryAssistGenerationResult.NotNeeded -> {
                        _state.update { current ->
                            if (current.receiptId != receiptId) current
                            else current.copy(categoryAssistState = AiLoadState.Idle, categoryAssistDiagnostics = null, categoryAssistMessage = result.reason)
                        }
                    }
                    is CategoryAssistGenerationResult.Error -> {
                        val diagnostics = latestArtifactDiagnostics("scanned_receipt:$receiptId", AiCapability.CATEGORIZATION_FALLBACK, AiArtifactStatus.FAILED)
                        _state.update { current ->
                            if (current.receiptId != receiptId) current
                            else current.copy(categoryAssistState = AiLoadState.Error(result.reason), categoryAssistDiagnostics = diagnostics, categoryAssistMessage = result.reason)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e("ReceiptScan: category_assist UNKNOWN_ERROR class=%s", e::class.java.simpleName)
                // S7-66F-010: Guard catch path by receiptId
                _state.update { current ->
                    if (current.receiptId != receiptId) current
                    else current.copy(categoryAssistState = AiLoadState.Error("Category assist failed (UNKNOWN_ERROR)"))
                }
            } finally {
                inFlightAssist.remove(key)
            }
        }
    }

    fun dismissCategoryAssist() {
        val receiptId = _state.value.receiptId ?: return
        val targetKey = "scanned_receipt:$receiptId"

        viewModelScope.launch {
            aiArtifactRepository.getLatest(
                targetKey = targetKey,
                capability = AiCapability.CATEGORIZATION_FALLBACK
            )?.let { artifact ->
                aiArtifactRepository.markDismissed(artifact.id)
            }
            _state.update {
                it.copy(
                    categoryAssistState = AiLoadState.Idle,
                    categoryAssistDiagnostics = null,
                    categoryAssistMessage = "AI category suggestion dismissed."
                )
            }
        }
    }

    fun applyCategoryAssist() {
        val readyState = _state.value.categoryAssistState as? AiLoadState.Ready ?: return
        // S7-016: Validate category still exists before applying
        val categoryId = readyState.value.categoryId
        if (categoryId <= 0 || categories.value.none { it.id == categoryId }) {
            _state.update { it.copy(categoryAssistMessage = "Suggested category is no longer available.") }
            return
        }
        _state.update {
            it.copy(
                selectedCategoryId = categoryId,
                categoryAssistMessage = "Applied AI category suggestion to the draft.",
                // S7-015: Accumulate — mark applied only after successful save
                pendingAppliedAiCapabilities = it.pendingAppliedAiCapabilities + AiCapability.CATEGORIZATION_FALLBACK
            )
        }
    }

    fun clearCategoryAssistMessage() {
        _state.update { it.copy(categoryAssistMessage = null) }
    }

    fun applyReceiptAssistMerchant() {
        applySuggestedValue(_state.value.receiptAssistState) { state, suggestion ->
            suggestion.merchant?.value?.takeIf { it.isNotBlank() }?.let { merchant ->
                state.copy(
                    editMerchant = merchant,
                    receiptAssistMessage = "Applied AI merchant suggestion.",
                    pendingAppliedAiCapabilities = state.pendingAppliedAiCapabilities + AiCapability.RECEIPT_EXTRACTION
                )
            } ?: state
        }
    }

    fun applyReceiptAssistTotal() {
        val before = _state.value
        applySuggestedValue(_state.value.receiptAssistState) { state, suggestion ->
            suggestion.total?.value?.let { total ->
                state.copy(
                    editAmount = String.format(java.util.Locale.US, "%.2f", total),
                    receiptAssistMessage = "Applied AI total suggestion.",
                    pendingAppliedAiCapabilities = state.pendingAppliedAiCapabilities + AiCapability.RECEIPT_EXTRACTION
                )
            } ?: state
        }
        reevaluateRegionAfterWrite(before)
    }

    fun applyReceiptAssistDate() {
        val before = _state.value
        applySuggestedValue(_state.value.receiptAssistState) { state, suggestion ->
            suggestion.date?.value?.let { date ->
                state.copy(
                    editDate = date,
                    receiptAssistMessage = "Applied AI date suggestion.",
                    pendingAppliedAiCapabilities = state.pendingAppliedAiCapabilities + AiCapability.RECEIPT_EXTRACTION
                )
            } ?: state
        }
        reevaluateRegionAfterWrite(before)
    }

    /**
     * MEC INV-3: re-evaluates the region after an AI-assist or quick-save write changed the amount or
     * date, with the manual-edit semantics: a date change invalidates; an amount change invalidates
     * unless the region is an amount-independent Blocked (R-4). No-op when neither changed.
     */
    private fun reevaluateRegionAfterWrite(before: ReceiptScanState) {
        val after = _state.value
        val dateChanged = after.editDate != before.editDate
        val amountChanged = after.editAmount != before.editAmount
        if (!dateChanged && !amountChanged) return
        if (dateChanged) _state.update { it.copy(previewAnnounceKey = it.previewAnnounceKey + 1) }
        reevaluateRegion(invalidate = dateChanged || !after.currencyRegion.isAmountIndependentBlock())
    }

    fun applyAllReceiptAssist() {
        val readyState = _state.value.receiptAssistState as? AiLoadState.Ready ?: return
        val suggestion = readyState.value
        val before = _state.value
        _state.update { state ->
            state.copy(
                editMerchant = suggestion.merchant?.value?.takeIf { it.isNotBlank() } ?: state.editMerchant,
                editAmount = suggestion.total?.value?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: state.editAmount,
                editDate = suggestion.date?.value ?: state.editDate,
                receiptAssistMessage = "Applied all AI receipt suggestions to the draft.",
                // S7-015: Accumulate — mark applied only after successful save
                pendingAppliedAiCapabilities = state.pendingAppliedAiCapabilities + AiCapability.RECEIPT_EXTRACTION
            )
        }
        reevaluateRegionAfterWrite(before)
    }

    fun clearReceiptAssistMessage() {
        _state.update { it.copy(receiptAssistMessage = null) }
    }

    fun requestReceiptQuickSaveConfirmation() {
        if (!_state.value.receiptQuickSaveEnabled) {
            _state.update {
                it.copy(errorMessage = "Receipt quick save is turned off.")
            }
            return
        }
        val preview = buildQuickSavePreview(_state.value)
        if (preview == null) {
            _state.update {
                it.copy(errorMessage = "AI quick save needs a missing field that an AI suggestion can fill safely.")
            }
            return
        }

        _state.update {
            it.copy(
                quickSavePreview = preview,
                errorMessage = null,
                saveResult = null
            )
        }
        aiRuntimeDiagnostics.recordInteraction(
            type = "phase4_preview",
            message = "receipt quick save preview opened for ${preview.autoAppliedFields.joinToString(", ")}"
        )
    }

    fun dismissReceiptQuickSaveConfirmation() {
        _state.update { it.copy(quickSavePreview = null) }
        aiRuntimeDiagnostics.recordInteraction(
            type = "phase4_dismiss",
            message = "receipt quick save preview dismissed"
        )
    }

    fun confirmReceiptQuickSave() {
        val currentState = _state.value
        val preview = currentState.quickSavePreview ?: return
        if (!currentState.receiptQuickSaveEnabled) {
            _state.update { it.copy(quickSavePreview = null, errorMessage = "Receipt quick save is turned off.") }
            return
        }
        // S7-004: Idempotency guard
        if (currentState.isSaving) return
        // R24c: defense in depth — an unverified currency can never be quick-saved
        if (currentState.currencyUnverified) {
            _state.update {
                it.copy(quickSavePreview = null, errorMessage = "Check the receipt currency before saving.")
            }
            return
        }
        // MEC defence in depth: an unset currency or an invalid amount can never be quick-saved.
        // The coordinator remains authoritative for rate eligibility (INV-6).
        if (isCurrencyUnsavable(currentState)) {
            _state.update { it.copy(quickSavePreview = null) }
            return
        }

        val request = ReceiptSaveRequest(
            receiptId = currentState.receiptId ?: return,
            merchant = preview.merchant,
            amount = preview.amount,
            currency = run {
                // S7-F583-006: Re-check currency at confirm time — preview can be stale
                val cur = currentState.editCurrency?.takeIf { it.isNotBlank() }
                if (cur == null) {
                    _state.update { it.copy(errorMessage = "Currency is not loaded yet. Please wait and retry.") }
                    return
                }
                cur
            },
            date = preview.date,
            categoryId = preview.categoryId,
            paymentMethod = currentState.paymentMethod,
            notes = currentState.notes.takeIf { it.isNotBlank() },
            appliedAiCapabilities = preview.usedCapabilities,
            // MEC G2-14/B1: captured at request build; falls back to the device zone now.
            entryZone = reviewZone ?: ZoneId.systemDefault()
        )

        // S7-008: Do NOT clear quickSavePreview here — keep it open until save result is known
        _state.update {
            it.copy(
                editMerchant = preview.merchant,
                editAmount = preview.amountText,
                editDate = preview.date,
                selectedCategoryId = preview.categoryId ?: it.selectedCategoryId,
                receiptAssistMessage = if (preview.autoAppliedFields.isNotEmpty()) {
                    "AI quick save filled ${preview.autoAppliedFields.joinToString(", ")} before saving."
                } else it.receiptAssistMessage
            )
        }
        // MEC INV-3: the quick-save write may change the amount or date; re-evaluate and re-apply the
        // defence-in-depth guard against the written values (AmountInvalid is decided synchronously).
        reevaluateRegionAfterWrite(currentState)
        if (isCurrencyUnsavable(_state.value)) {
            _state.update { it.copy(quickSavePreview = null) }
            return
        }

        aiRuntimeDiagnostics.recordInteraction(
            type = "phase4_accept",
            message = "receipt quick save confirmed with ${preview.autoAppliedFields.joinToString(", ")}"
        )
        saveExpenseInternal(request)
    }

    private fun applySuggestedValue(
        state: AiLoadState<ReceiptAssistSuggestion>,
        updater: (ReceiptScanState, ReceiptAssistSuggestion) -> ReceiptScanState
    ) {
        val readyState = state as? AiLoadState.Ready ?: return
        _state.update { current -> updater(current, readyState.value) }
    }

    private fun markLatestArtifactApplied(capability: AiCapability) {
        val receiptId = _state.value.receiptId ?: return
        markLatestArtifactApplied(receiptId, capability)
    }

    private fun markLatestArtifactApplied(receiptId: Long, capability: AiCapability) {
        viewModelScope.launch {
            aiArtifactRepository.getLatest("scanned_receipt:$receiptId", capability)
                ?.let { artifact ->
                    aiArtifactRepository.markApplied(artifact.id)
                }
        }
    }

    fun saveExpense() {
        // S7-004: Idempotency guard
        if (_state.value.isSaving) return
        val currentState = _state.value
        // MEC defence in depth: Save is disabled in the UI for these; the coordinator enforces rates.
        if (isCurrencyUnsavable(currentState)) return
        val request = buildManualSaveRequest(currentState) ?: return
        saveExpenseInternal(request)
    }

    // Intentionally narrower than ReceiptScanState.isCurrencySaveBlocked: the coordinator is authoritative for Blocked; the UI binds Save to isCurrencySaveBlocked in C2.
    /** True when no currency is chosen or the amount fails the currency's rules (never rate eligibility). */
    private fun isCurrencyUnsavable(s: ReceiptScanState): Boolean =
        s.currencyRegion is CurrencyRegionUi.Unset || s.currencyRegion is CurrencyRegionUi.AmountInvalid

    fun retry() {
        cancelActiveWork()
        _state.update { freshStateKeeping(it) }
    }

    fun reset() {
        cancelActiveWork()
        _state.update { freshStateKeeping(it) }
    }

    /**
     * A blank state on the home currency. The currency choice is session-only (MEC constraint 1), so it
     * is never carried over. The rates setting is a live observation of stored settings and is kept.
     */
    private fun freshStateKeeping(previous: ReceiptScanState): ReceiptScanState = ReceiptScanState(
        editDate = timeProvider.now(),
        editCurrency = homeCurrencyCode,
        receiptQuickSaveEnabled = previous.receiptQuickSaveEnabled,
        currencyOptions = optionsFor(homeCurrencyCode),
        homeCurrency = homeCurrencyCode,
        currencyRegion = if (homeCurrencyCode == null) CurrencyRegionUi.Loading else CurrencyRegionUi.Home,
        ratesSetting = previous.ratesSetting,
        ratesToday = todayFor(ZoneId.systemDefault()),
        previewAnnounceKey = previous.previewAnnounceKey
    )

    /** S7-F583-006: Centralized cleanup — cancels all in-flight work and clears assist state. */
    private fun cancelActiveWork() {
        scanRequestSeq++
        scanJob?.cancel()
        scanJob = null
        itemAnalysisJob?.cancel()
        itemAnalysisJob = null
        inFlightAssist.clear()
        // MEC constraint 2/7: no late preview or refresh may land after a reset, retry or dismissal.
        evaluationJob?.cancel()
        evaluationJob = null
        refreshJob?.cancel()
        refreshJob = null
        clearHistorySession()
        reviewZone = null
        pendingProposal = null
        proposalAwaitingHome = false
    }

    override fun onCleared() {
        cancelActiveWork()
        super.onCleared()
    }

    // ---------------------------------------------------------------------------------------------
    // MEC C1: rate preview, refresh (lean region orchestration; rules stay in the policy/preview)
    // ---------------------------------------------------------------------------------------------

    /** The bounded invalid-amount kind for the region, or null when the amount passes (INV-12/13/14). */
    private fun amountInvalidKind(amount: Double, currency: CurrencyCode): CurrencyAmountInvalidKind? {
        val check = CurrencyAmountPolicy.check(amount, currency)
        return when (check) {
            AmountCheck.Ok -> null
            AmountCheck.NotPositive -> CurrencyAmountInvalidKind.NOT_POSITIVE
            // OD-6: digits unknown. Never assume 2 digits; treat as an invalid amount.
            AmountCheck.DigitsUnavailable -> CurrencyAmountInvalidKind.NOT_POSITIVE
            is AmountCheck.PrecisionExceeded -> CurrencyAmountInvalidKind.PRECISION_EXCEEDED
            is AmountCheck.AboveCap -> CurrencyAmountInvalidKind.ABOVE_CAP
        }
    }

    /**
     * The supporting text for an invalid amount, from the same [CurrencyAmountPolicy.check] result as
     * [amountInvalidKind] (same keys and cap formatting as the add-expense sheet). A null or unparseable
     * amount, and unavailable digits (OD-6), use the generic invalid-amount string.
     */
    private fun amountErrorText(amount: Double?, currency: CurrencyCode): UiText {
        val generic = UiText.StringResource(R.string.add_expense_error_amount_invalid)
        if (amount == null) return generic
        return when (val check = CurrencyAmountPolicy.check(amount, currency)) {
            is AmountCheck.PrecisionExceeded ->
                if (check.allowedDigits == 0) {
                    UiText.StringResource(R.string.mec_amount_no_decimals, listOf(currency.code))
                } else {
                    UiText.StringResource(R.string.mec_amount_max_decimals, listOf(currency.code, check.allowedDigits))
                }
            is AmountCheck.AboveCap ->
                UiText.StringResource(
                    R.string.mec_amount_too_large,
                    listOf(java.text.NumberFormat.getIntegerInstance().format(check.cap.toLong()))
                )
            // Exhaustive over the sealed AmountCheck (no else): a new variant must be mapped here.
            AmountCheck.Ok, AmountCheck.NotPositive, AmountCheck.DigitsUnavailable -> generic
        }
    }

    /**
     * MEC INV-3/INV-15: recomputes [ReceiptScanState.currencyRegion]. Latest wins: the previous
     * evaluation is cancelled. Home and Loading need no evaluation; an Unset region stays Unset until a
     * currency is picked. For a foreign currency a valid amount (or none) is evaluated through the single
     * read-only [UserPresentRatePreview]; "Checking" ([CurrencyRegionUi.Evaluating]) appears only after
     * [EVALUATING_DELAY_MS]. No fetch, and no logging of amounts, currencies, zones, rates or dates.
     *
     * @param invalidate true when the currency, date, home or zone changed: the displayed result is
     * dropped to [CurrencyRegionUi.Pending] immediately so a stale amount is never shown.
     * @param refreshCompleted true when called after a user-started refresh finished.
     */
    private fun reevaluateRegion(
        invalidate: Boolean,
        showEvaluating: Boolean = true,
        refreshCompleted: Boolean = false
    ) {
        evaluationJob?.cancel()
        evaluationJob = null

        val s = _state.value
        val home = homeCurrencyCode
        val selected = s.editCurrency
        val zone = reviewZone ?: ZoneId.systemDefault()
        val today = todayFor(zone)
        if (home == null || selected == null) {
            _state.update {
                it.copy(
                    currencyRegion = if (it.currencyRegion is CurrencyRegionUi.Unset) it.currencyRegion else CurrencyRegionUi.Loading,
                    amountError = null,
                    ratesToday = today
                )
            }
            return
        }
        if (selected == home) {
            val homeOnlyCode = CurrencyCode.parse(home)
            val homeAmountText = s.editAmount
            val homeParsed = if (homeAmountText.isBlank()) null
            else com.yourname.expensetracker.ui.util.AmountInputSanitizer.parseOrNull(homeAmountText)
            val homeInvalidKind = when {
                homeOnlyCode == null || homeAmountText.isBlank() -> null
                homeParsed == null -> CurrencyAmountInvalidKind.NOT_POSITIVE
                else -> amountInvalidKind(homeParsed, homeOnlyCode)
            }
            if (homeInvalidKind != null && homeOnlyCode != null) {
                val homeAmountError = amountErrorText(homeParsed, homeOnlyCode)
                _state.update {
                    it.copy(
                        currencyRegion = CurrencyRegionUi.AmountInvalid(homeInvalidKind),
                        amountError = homeAmountError,
                        ratesToday = today,
                        rateRefresh = settledRefresh(it, false)
                    )
                }
                return
            }
            _state.update {
                it.copy(currencyRegion = CurrencyRegionUi.Home, amountError = null, ratesToday = today, rateRefresh = settledRefresh(it, false))
            }
            return
        }
        val homeCode = CurrencyCode.parse(home)
        val selectedCode = CurrencyCode.parse(selected)
        if (homeCode == null || selectedCode == null) {
            val variant = if (homeCode == null) CurrencyBlockVariant.HOME_UNRESOLVED else CurrencyBlockVariant.UNSUPPORTED
            _state.update {
                it.copy(
                    currencyRegion = CurrencyRegionUi.Blocked(variant, null, CurrencyBlockOrigin.PREVIEW),
                    amountError = null,
                    ratesToday = today,
                    rateRefresh = settledRefresh(it, refreshCompleted)
                )
            }
            return
        }

        val amountText = s.editAmount
        val parsedAmount = if (amountText.isBlank()) null
        else com.yourname.expensetracker.ui.util.AmountInputSanitizer.parseOrNull(amountText)
        val invalidKind = when {
            amountText.isBlank() -> null
            parsedAmount == null -> CurrencyAmountInvalidKind.NOT_POSITIVE
            else -> amountInvalidKind(parsedAmount, selectedCode)
        }
        val amountError = if (invalidKind != null) amountErrorText(parsedAmount, selectedCode) else null
        if (invalidKind != null) {
            _state.update {
                it.copy(
                    currencyRegion = CurrencyRegionUi.AmountInvalid(invalidKind),
                    amountError = amountError,
                    ratesToday = today,
                    rateRefresh = settledRefresh(it, refreshCompleted)
                )
            }
            return
        }

        if (invalidate) {
            _state.update { it.copy(currencyRegion = CurrencyRegionUi.Pending, amountError = null, ratesToday = today) }
        }

        val atMillis = s.editDate
        evaluationJob = viewModelScope.launch {
            val marker = if (showEvaluating) {
                launch {
                    delay(EVALUATING_DELAY_MS)
                    _state.update { it.copy(currencyRegion = CurrencyRegionUi.Evaluating, amountError = null) }
                }
            } else {
                null
            }
            // INV-1b-5: the remedy is copied from RateRemedyPolicy; the VM supplies only today and the flag.
            var decision = RateRemedyDecision(RateRemedy.REFRESH_LATEST)
            val region: CurrencyRegionUi = try {
                val result = userPresentRatePreview.preview(
                    parsedAmount = parsedAmount ?: 1.0,
                    currency = selectedCode,
                    home = homeCode,
                    expenseAtMillis = atMillis,
                    entryZone = zone
                )
                decision = if (result is UserPresentRatePreviewResult.Blocked) {
                    RateRemedyPolicy.remedy(
                        eligibility = result.eligibility,
                        localDate = java.time.Instant.ofEpochMilli(atMillis).atZone(zone).toLocalDate(),
                        latestStoredPairDate = result.latestStoredPairDate,
                        today = today,
                        historyDownloadedThisSession = historyDownloadedThisSession
                    )
                } else {
                    RateRemedyDecision(RateRemedy.NONE)
                }
                regionFor(result, hasAmount = parsedAmount != null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // INV-19: bounded code only. An unreadable rate store fails closed: no preview, blocked.
                Timber.w(LOG_RATE_PREVIEW_FAILED)
                CurrencyRegionUi.Blocked(CurrencyBlockVariant.NONE, null, CurrencyBlockOrigin.PREVIEW)
            }
            marker?.cancel()
            ensureActive()
            // F-B2-A11Y-1: after a history success the preview line is announced once (latest inputs win).
            val announce = historyAnnouncePending && region is CurrencyRegionUi.Eligible
            historyAnnouncePending = false
            val doneStatus = doneStatusFor(region, decision, atMillis, zone)
            _state.update {
                it.copy(
                    currencyRegion = region,
                    amountError = null,
                    ratesToday = today,
                    rateRefresh = settledRefresh(it, refreshCompleted && region is CurrencyRegionUi.Blocked),
                    rateRemedy = decision,
                    historyStatus = when {
                        it.historyStatus == CurrencyHistoryStatus.RUNNING -> CurrencyHistoryStatus.RUNNING
                        it.historyHomeUnavailable -> CurrencyHistoryStatus.FAILED_HOME_UNAVAILABLE
                        else -> doneStatus
                    },
                    previewAnnounceKey = if (announce) it.previewAnnounceKey + 1 else it.previewAnnounceKey
                )
            }
        }
    }

    /**
     * The refresh status after an evaluation: RUNNING is kept; a finished refresh that is still blocked
     * becomes DONE_STILL_BLOCKED; any other settled evaluation clears an earlier outcome to IDLE.
     */
    private fun settledRefresh(current: ReceiptScanState, doneStillBlocked: Boolean): CurrencyRefreshStatus = when {
        current.rateRefresh == CurrencyRefreshStatus.RUNNING -> CurrencyRefreshStatus.RUNNING
        doneStillBlocked -> CurrencyRefreshStatus.DONE_STILL_BLOCKED
        else -> CurrencyRefreshStatus.IDLE
    }

    private fun todayFor(zone: ZoneId): LocalDate =
        java.time.Instant.ofEpochMilli(timeProvider.now()).atZone(zone).toLocalDate()

    /** MEC AE-38: the preview text is the shared formatter applied to the exact snapshot amount. */
    private fun regionFor(result: UserPresentRatePreviewResult, hasAmount: Boolean): CurrencyRegionUi {
        val origin = CurrencyBlockOrigin.PREVIEW
        return when (result) {
            UserPresentRatePreviewResult.NotApplicableHome -> CurrencyRegionUi.Home
            is UserPresentRatePreviewResult.ConvertedPreview -> CurrencyRegionUi.Eligible(
                rateDate = result.rateDate,
                sourceLabel = result.sourceLabel,
                formattedAmountInHome = if (hasAmount) CurrencyFormatter.formatMoney(result.amountInHome, result.home.code) else null,
                amountUnavailable = false
            )
            // INV-15: eligible rate but no computable amount. Never shown as zero; the date still is.
            is UserPresentRatePreviewResult.AmountUnavailable -> CurrencyRegionUi.Eligible(
                rateDate = result.rateDate,
                sourceLabel = result.sourceLabel,
                formattedAmountInHome = null,
                amountUnavailable = hasAmount
            )
            is UserPresentRatePreviewResult.Blocked -> {
                val eligibility = result.eligibility
                when (eligibility) {
                    is RateEligibility.Stale ->
                        CurrencyRegionUi.Blocked(CurrencyBlockVariant.STALE, eligibility.latestRateDate, origin)
                    RateEligibility.None ->
                        CurrencyRegionUi.Blocked(CurrencyBlockVariant.NONE, null, origin)
                    is RateEligibility.Unsupported ->
                        CurrencyRegionUi.Blocked(CurrencyBlockVariant.UNSUPPORTED, null, origin)
                    // Not produced by the use case for a blocked result; fail closed.
                    RateEligibility.Home, is RateEligibility.Eligible ->
                        CurrencyRegionUi.Blocked(CurrencyBlockVariant.NONE, null, origin)
                }
            }
        }
    }

    /**
     * MEC INV-20: user-started "Refresh rates". Runs only when the setting is On and home is loaded;
     * the only network path is [CurrencyRatesRepository.refresh], which is privacy-gated. The VM never
     * enables the setting. On success the region is re-evaluated; failures map to bounded statuses.
     */
    fun refreshRates() {
        val snapshot = _state.value
        val home = homeCurrencyCode ?: return
        if (snapshot.ratesSetting != CurrencyRatesSetting.ON) return
        val selected = snapshot.editCurrency ?: return
        if (selected == home) return
        if (snapshot.rateRefresh == CurrencyRefreshStatus.RUNNING) return

        _state.update { it.copy(rateRefresh = CurrencyRefreshStatus.RUNNING) }
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            var status = CurrencyRefreshStatus.IDLE
            var setting: CurrencyRatesSetting? = null
            var refreshed = false
            try {
                val outcome = currencyRatesRepository.refresh(home)
                if (outcome is CurrencyRateRefreshOutcome.Refreshed) {
                    refreshed = true
                } else if (outcome is CurrencyRateRefreshOutcome.DisabledByPrivacy) {
                    // The setting was turned off meanwhile: show the Off variant, no failure line.
                    setting = CurrencyRatesSetting.OFF
                } else {
                    // PrivacyCheckFailed: fail closed to Unknown (treated as Off).
                    setting = CurrencyRatesSetting.UNKNOWN
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                Timber.w(LOG_RATE_REFRESH_FAILED)
                status = CurrencyRefreshStatus.FAILED_OFFLINE
            } catch (e: Exception) {
                // INV-19: bounded code only; the exception message is matched by equality, never shown.
                Timber.w(LOG_RATE_REFRESH_FAILED)
                status = if (e.message == REFRESH_HOME_QUOTE_UNAVAILABLE || e.message == REFRESH_INVALID_HOME_CURRENCY) {
                    CurrencyRefreshStatus.FAILED_HOME_UNAVAILABLE
                } else {
                    CurrencyRefreshStatus.FAILED
                }
            }
            _state.update {
                it.copy(
                    rateRefresh = status,
                    ratesSetting = setting ?: it.ratesSetting
                )
            }
            if (refreshed) {
                reevaluateRegion(invalidate = false, showEvaluating = false, refreshCompleted = true)
            }
        }
    }

    /**
     * MEC 1b INV-1b-3: user-started "Download rate history". The only caller of
     * [CurrencyRatesRepository.downloadRecentHistory] here; never reached from preview, save, scan or any
     * automatic path. Single job: ignored while RUNNING (or a refresh runs). The VM never enables the
     * setting; the repository is privacy-gated. Outcomes map per ux D-1b-5. No receipt, event or file
     * changes: only the rate table is written (by the repository).
     */
    fun onDownloadRateHistory() {
        val snapshot = _state.value
        val home = homeCurrencyCode ?: return
        if (snapshot.step != ScanStep.REVIEW) return
        if (snapshot.ratesSetting != CurrencyRatesSetting.ON) return
        val selected = snapshot.editCurrency ?: return
        if (selected == home) return
        if (snapshot.rateFixAction != CurrencyRateFixAction.DOWNLOAD_HISTORY) return
        if (snapshot.historyStatus == CurrencyHistoryStatus.RUNNING || historyJob?.isActive == true) return
        if (snapshot.rateRefresh == CurrencyRefreshStatus.RUNNING) return
        // Spec 1b.10: a history download is illegal while saving.
        if (snapshot.isSaving) return

        _state.update { it.copy(historyStatus = CurrencyHistoryStatus.RUNNING) }
        historyJob = viewModelScope.launch {
            var status = CurrencyHistoryStatus.IDLE
            var setting: CurrencyRatesSetting? = null
            var stored = false
            try {
                val outcome = currencyRatesRepository.downloadRecentHistory(home)
                // A repository that ignored Cancel must not overwrite CANCELLED (rethrown below).
                ensureActive()
                when (outcome) {
                    is CurrencyRateHistoryOutcome.Stored -> {
                        stored = true
                        historyDownloadedThisSession = true
                        historyEarliestDate = outcome.earliestDate
                    }
                    // The setting was turned off meanwhile: show the Off variant, no failure line.
                    CurrencyRateHistoryOutcome.DisabledByPrivacy -> setting = CurrencyRatesSetting.OFF
                    is CurrencyRateHistoryOutcome.PrivacyCheckFailed -> setting = CurrencyRatesSetting.UNKNOWN
                    CurrencyRateHistoryOutcome.AlreadyInProgress -> status = CurrencyHistoryStatus.FAILED
                    is CurrencyRateHistoryOutcome.Failed -> status = historyFailureStatus(outcome.reason)
                    CurrencyRateHistoryOutcome.Cancelled -> status = CurrencyHistoryStatus.CANCELLED
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // INV-19: bounded code only; the exception is never attached.
                Timber.w(LOG_RATE_HISTORY_FAILED)
                status = CurrencyHistoryStatus.FAILED
            }
            if (stored) historyAnnouncePending = true
            _state.update {
                it.copy(
                    historyStatus = status,
                    historyHomeUnavailable = it.historyHomeUnavailable ||
                        status == CurrencyHistoryStatus.FAILED_HOME_UNAVAILABLE,
                    ratesSetting = setting ?: it.ratesSetting
                )
            }
            if (stored) {
                // Latest inputs win; the region becomes Eligible, or Blocked with a DONE_* status.
                reevaluateRegion(invalidate = false, showEvaluating = false)
            }
        }
    }

    /** INV-1b-21: user Cancel, offered only while RUNNING. The job's CancellationException is rethrown. */
    fun onCancelRateHistory() {
        if (_state.value.historyStatus != CurrencyHistoryStatus.RUNNING) return
        historyJob?.cancel()
        historyJob = null
        _state.update { it.copy(historyStatus = CurrencyHistoryStatus.CANCELLED) }
    }

    /** D-1b-5: bounded failure reason to UX status. */
    private fun historyFailureStatus(reason: RateHistoryFailureReason): CurrencyHistoryStatus = when (reason) {
        RateHistoryFailureReason.OFFLINE -> CurrencyHistoryStatus.FAILED_OFFLINE
        RateHistoryFailureReason.INVALID_HOME_CURRENCY,
        RateHistoryFailureReason.HOME_QUOTE_UNAVAILABLE -> CurrencyHistoryStatus.FAILED_HOME_UNAVAILABLE
        RateHistoryFailureReason.TIMEOUT,
        RateHistoryFailureReason.HTTP_ERROR,
        RateHistoryFailureReason.PAYLOAD_TOO_LARGE,
        RateHistoryFailureReason.PAYLOAD_EMPTY,
        RateHistoryFailureReason.PAYLOAD_MALFORMED,
        RateHistoryFailureReason.PAYLOAD_OUT_OF_BOUNDS,
        RateHistoryFailureReason.STORE_FAILED -> CurrencyHistoryStatus.FAILED
    }

    /**
     * D-1b-5: still blocked after a history download in this review. An entry date before the earliest
     * stored ECB date is DONE_TOO_OLD; any other case is DONE_NO_RATE. Anything else settles to IDLE.
     */
    private fun doneStatusFor(
        region: CurrencyRegionUi,
        decision: RateRemedyDecision,
        atMillis: Long,
        zone: ZoneId
    ): CurrencyHistoryStatus {
        if (region !is CurrencyRegionUi.Blocked || decision.noRemedyReason != NoRemedyReason.HISTORY_ALREADY_DOWNLOADED) {
            return CurrencyHistoryStatus.IDLE
        }
        val earliest = historyEarliestDate
        val entryDate = java.time.Instant.ofEpochMilli(atMillis).atZone(zone).toLocalDate()
        return if (earliest != null && entryDate.isBefore(earliest)) {
            CurrencyHistoryStatus.DONE_TOO_OLD
        } else {
            CurrencyHistoryStatus.DONE_NO_RATE
        }
    }

    /** Leaving the review (reset, retry, dismissal) stops a running download and drops the session data. */
    private fun clearHistorySession() {
        historyJob?.cancel()
        historyJob = null
        historyDownloadedThisSession = false
        historyEarliestDate = null
        historyAnnouncePending = false
    }

    /** MEC INV-6: maps a typed coordinator refusal reason to the card variant. */
    private fun blockVariantFor(reason: RateRefusalReason): CurrencyBlockVariant = when (reason) {
        RateRefusalReason.NO_RATE -> CurrencyBlockVariant.NONE
        RateRefusalReason.STALE_RATE -> CurrencyBlockVariant.STALE
        RateRefusalReason.UNSUPPORTED_CURRENCY -> CurrencyBlockVariant.UNSUPPORTED
        RateRefusalReason.HOME_CURRENCY_UNRESOLVED -> CurrencyBlockVariant.HOME_UNRESOLVED
    }

    private fun buildManualSaveRequest(currentState: ReceiptScanState): ReceiptSaveRequest? {
        // S7-021: Must be in REVIEW state
        if (currentState.step != ScanStep.REVIEW) return null

        val merchant = currentState.editMerchant.trim()
        if (merchant.isBlank()) {
            _state.update { it.copy(errorMessage = "Merchant name is required") }
            return null
        }

        // R16: strict locale-aware parser shared with the sanitizer (ambiguous -> existing error).
        val amount = com.yourname.expensetracker.ui.util.AmountInputSanitizer.parseOrNull(currentState.editAmount)
        if (amount == null || amount <= 0) {
            _state.update { it.copy(errorMessage = "Enter a valid amount") }
            return null
        }

        // S7-021: Date must be set
        if (currentState.editDate <= 0L) {
            _state.update { it.copy(errorMessage = "Select a valid date") }
            return null
        }

        // S7-009/S7-021: Currency must be loaded
        val currency = currentState.editCurrency
        if (currency.isNullOrBlank()) {
            _state.update { it.copy(errorMessage = "Currency is not loaded yet. Please wait.") }
            return null
        }

        val receiptId = currentState.receiptId ?: return null
        return ReceiptSaveRequest(
            receiptId = receiptId,
            merchant = merchant,
            amount = amount,
            currency = currency,  // S7-012: captured at validation time
            date = currentState.editDate,
            categoryId = currentState.selectedCategoryId,
            paymentMethod = currentState.paymentMethod,
            notes = currentState.notes.takeIf { it.isNotBlank() },
            // S7-015: Include pending manual-apply capabilities
            appliedAiCapabilities = currentState.pendingAppliedAiCapabilities,
            // MEC G2-14/B1: captured at request build; falls back to the device zone now.
            entryZone = reviewZone ?: ZoneId.systemDefault()
        )
    }

    /**
     * RCP-11: Confidence threshold for quick-save.
     * Only offer quick-save when OCR confidence is above this minimum.
     * Low-confidence scans should go through manual review instead.
     */
    private val QUICK_SAVE_MIN_CONFIDENCE = 0.5f

    private fun buildQuickSavePreview(currentState: ReceiptScanState): ReceiptQuickSavePreview? {
        if (!currentState.receiptQuickSaveEnabled || currentState.step != ScanStep.REVIEW) return null
        if (currentState.receiptId == null || currentState.isSaving) return null
        // S7-014: Quick save unavailable until currency is loaded
        if (currentState.editCurrency.isNullOrBlank()) return null
        // R24c: assumed (unread) currency must be confirmed by the user before quick save
        if (currentState.currencyUnverified) return null

        // RCP-11: Skip quick-save when OCR confidence is too low — the
        // extracted data is unreliable and requires user review.
        if (currentState.ocrConfidence < QUICK_SAVE_MIN_CONFIDENCE) return null

        var merchant = currentState.editMerchant.trim()
        var amount = com.yourname.expensetracker.ui.util.AmountInputSanitizer.parseOrNull(currentState.editAmount)
        var date = currentState.editDate
        var categoryId = currentState.selectedCategoryId
        var categoryName: String? = categoryId?.let { selectedId ->
            categories.value.firstOrNull { it.id == selectedId }?.name
        }
        var merchantSource = "Current draft"
        var amountSource = "Current draft"
        var dateSource = "Current draft"
        var categorySource = if (categoryId != null) "Current draft" else null

        val autoAppliedFields = mutableListOf<String>()
        val usedCapabilities = linkedSetOf<AiCapability>()
        val receiptSuggestion = (currentState.receiptAssistState as? AiLoadState.Ready)?.value
        val categorySuggestion = (currentState.categoryAssistState as? AiLoadState.Ready)?.value

        if (merchant.isBlank()) {
            receiptSuggestion?.merchant?.value?.takeIf { it.isNotBlank() }?.let {
                merchant = it
                merchantSource = "AI receipt assist"
                autoAppliedFields += "merchant"
                usedCapabilities += AiCapability.RECEIPT_EXTRACTION
            }
        }

        if (amount == null || amount <= 0) {
            receiptSuggestion?.total?.value?.takeIf { it > 0 }?.let {
                amount = it
                amountSource = "AI receipt assist"
                autoAppliedFields += "amount"
                usedCapabilities += AiCapability.RECEIPT_EXTRACTION
            }
        }

        if (date <= 0L) {
            receiptSuggestion?.date?.value?.let {
                date = it
                dateSource = "AI receipt assist"
                autoAppliedFields += "date"
                usedCapabilities += AiCapability.RECEIPT_EXTRACTION
            }
        }

        if (categoryId == null) {
            categorySuggestion?.let {
                categoryId = it.categoryId
                categoryName = it.categoryName
                categorySource = "AI category assist"
                autoAppliedFields += "category"
                usedCapabilities += AiCapability.CATEGORIZATION_FALLBACK
            }
        }

        if (autoAppliedFields.isEmpty()) return null
        val finalAmount = amount?.takeIf { it > 0 } ?: return null
        if (merchant.isBlank()) return null

        return ReceiptQuickSavePreview(
            merchant = merchant,
            amount = finalAmount,
            // S7-011: Locale.US for consistent decimal formatting
            amountText = String.format(java.util.Locale.US, "%.2f", finalAmount),
            date = date,
            categoryId = categoryId,
            categoryName = categoryName,
            autoAppliedFields = autoAppliedFields,
            usedCapabilities = usedCapabilities,
            fieldSummaries = buildList {
                add(ReceiptQuickSaveFieldSummary("Merchant", merchant, merchantSource))
                add(ReceiptQuickSaveFieldSummary("Amount", String.format(java.util.Locale.US, "%.2f", finalAmount), amountSource))
                if (date > 0L) {
                    add(ReceiptQuickSaveFieldSummary("Date", date.toString(), dateSource))
                }
                categoryId?.let {
                    add(ReceiptQuickSaveFieldSummary(
                        label = "Category",
                        value = categoryName ?: "Selected category",
                        source = categorySource ?: "Current draft"
                    ))
                }
            },
            diagnostics = buildList {
                if (AiCapability.RECEIPT_EXTRACTION in usedCapabilities) {
                    currentState.receiptAssistDiagnostics?.let(::add)
                }
                if (AiCapability.CATEGORIZATION_FALLBACK in usedCapabilities) {
                    currentState.categoryAssistDiagnostics?.let(::add)
                }
            }
        )
    }

    private fun saveExpenseInternal(request: ReceiptSaveRequest) {
        // S7-004: Idempotency guard
        if (_state.value.isSaving) return
        _state.update { it.copy(isSaving = true, errorMessage = null) }

        viewModelScope.launch {
            var failureCode = "SOURCE_LINK_FAILED"
            try {
                // S7-66F-002: Real linkability check — no sentinel expense ID
                val canLink = receiptLinkService.checkCanLinkReceipt(request.receiptId)
                failureCode = "UNKNOWN_ERROR"
                if (!canLink) {
                    _state.update {
                        it.copy(
                            isSaving = false,
                            errorMessage = "This receipt is already linked to a transaction. Use Receipt Matching to view it."
                        )
                    }
                    return@launch
                }

                // S7-012: Use currency captured at request-build time — not re-read from live state
                val resolvedCurrency = request.currency.takeIf { it.isNotBlank() }
                    ?: run {
                        _state.update { it.copy(isSaving = false, errorMessage = "Currency not loaded. Please wait and retry.") }
                        return@launch
                    }

                // S7-012: Tax-inclusive override only when user has NOT edited the amount
                val taxInclusive = _state.value.parsedReceipt?.taxInclusive == true
                val parsedTotal = _state.value.parsedReceipt?.total
                    ?.takeIf { it.currency.code == resolvedCurrency }?.amount
                val effectiveAmount = if (taxInclusive && parsedTotal != null && !_state.value.isAmountEditedByUser) {
                    Timber.d("ReceiptScan: tax_inclusive_total_applied receiptId=%d", request.receiptId)
                    parsedTotal
                } else {
                    request.amount
                }

                // S7-013: Preserve user-visible merchant; use normalized only for classifier/deduplication
                val displayMerchant = request.merchant.trim()
                val normalizedMerchant = merchantNormalizer.normalize(
                    rawName = displayMerchant,
                    autoCreate = true
                ).canonical.normalizedName

                val finalCategoryId = request.categoryId ?: hybridClassifier.classify(
                    merchantName = normalizedMerchant,
                    amount = effectiveAmount
                ).categoryId.takeIf { it > 0 }

                val notes = request.notes ?: "Scanned from receipt"

                val createRequest = CreateExpenseRequest(
                    // S7-013: Display merchant shown in ledger; normalized used for classifier below
                    merchant = displayMerchant,
                    amount = effectiveAmount,
                    currency = resolvedCurrency,
                    date = request.date,
                    transactionType = TransactionType.PURCHASE,
                    source = ExpenseSource.RECEIPT_SCAN,
                    categoryId = finalCategoryId,
                    notes = notes,
                    paymentMethod = request.paymentMethod,
                    isManualEntry = true,
                    scannedReceiptId = request.receiptId,
                    // MEC INV-6/G2-14: the user-present flag; the coordinator evaluates the rate and
                    // the amount precision with the zone captured at request build.
                    userPresentRate = UserPresentRateRequirement(request.entryZone)
                )

                // S7-F583-001: Atomic create+link — if link fails, expense is rolled back
                val atomicResult = receiptLifecycleCoordinator.createExpenseAndLinkReceipt(createRequest)

                atomicResult.fold(
                    onSuccess = { expenseId ->
                        if (finalCategoryId != null) {
                            runCatching {
                                hybridClassifier.learnFromCorrection(
                                    merchantName = normalizedMerchant,
                                    correctCategoryId = finalCategoryId,
                                    amount = effectiveAmount
                                )
                            }.onFailure { e ->
                                if (e is CancellationException) throw e
                                Timber.w("ReceiptScan: classifier_learning UNKNOWN_ERROR class=%s", e::class.java.simpleName)
                            }
                        }
                        // S7-007/S7-66F-011: Mark AI artifacts applied using request receipt ID — not live state
                        request.appliedAiCapabilities.forEach { capability ->
                            markLatestArtifactApplied(request.receiptId, capability)
                        }
                        _state.update {
                            it.copy(
                                isSaving = false,
                                step = ScanStep.DONE,
                                saveResult = SaveReceiptResult.Success(expenseId),
                                quickSavePreview = null,
                                pendingAppliedAiCapabilities = emptySet()
                            )
                        }
                    },
                    onFailure = { e ->
                        if (e is CancellationException) throw e
                        if (e is ReceiptLifecycleCoordinator.RateNotEligibleException) {
                            // MEC INV-6/AE-24: nothing was saved. Keep the form and the review, close the
                            // quick-save dialog, cancel any in-flight preview so a late result cannot
                            // overwrite the SAVE card, and show the SAVE-origin card. Not logged as an error.
                            evaluationJob?.cancel()
                            evaluationJob = null
                            _state.update {
                                it.copy(
                                    isSaving = false,
                                    quickSavePreview = null,
                                    saveResult = SaveReceiptResult.RateNotEligible,
                                    amountError = null,
                                    currencyRegion = CurrencyRegionUi.Blocked(
                                        variant = blockVariantFor(e.reason),
                                        latestRateDate = if (e.reason == RateRefusalReason.STALE_RATE) e.latestRateDate else null,
                                        origin = CurrencyBlockOrigin.SAVE
                                    )
                                )
                            }
                            return@fold
                        }
                        val isDuplicate = e is ReceiptLifecycleCoordinator.DuplicateTransactionException
                        if (!isDuplicate) {
                            Timber.e("ReceiptScan: atomic_save UNKNOWN_ERROR class=%s", e::class.java.simpleName)
                        }
                        _state.update {
                            it.copy(
                                isSaving = false,
                                rawOcrText = "",
                                showRawText = false,
                                debugData = it.debugData?.copy(
                                    rawText = "",
                                    parsedTransactions = emptyList(),
                                    parsingLogs = if (isDuplicate) emptyList() else listOf("Save Error: UNKNOWN_ERROR")
                                ),
                                saveResult = if (isDuplicate) SaveReceiptResult.DuplicateTransaction
                                    else SaveReceiptResult.Error("Receipt save failed (UNKNOWN_ERROR)")
                            )
                        }
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("ReceiptScan: %s stage=save class=%s", failureCode, e::class.java.simpleName)
                _state.update {
                    it.copy(
                        isSaving = false,
                        rawOcrText = "",
                        showRawText = false,
                        debugData = it.debugData?.copy(rawText = "", parsedTransactions = emptyList(), parsingLogs = mutableListOf("Save Error: $failureCode")),
                        saveResult = SaveReceiptResult.Error("Receipt save failed ($failureCode)")
                    )
                }
            }
        }
    }

    private suspend fun latestArtifactDiagnostics(
        targetKey: String,
        capability: AiCapability,
        expectedStatus: AiArtifactStatus? = null
    ): String? {
        val artifact = aiArtifactRepository.getLatest(targetKey, capability) ?: return null
        if (expectedStatus != null && artifact.status != expectedStatus) return null
        return artifact.toDiagnosticsOrNull()?.toDisplayText()
    }

    // -------------------------------------------------------------------------
    // Item Categorization
    // -------------------------------------------------------------------------

    fun analyzeReceiptItems() {
        val receiptId = _state.value.receiptId ?: return
        itemAnalysisJob?.cancel()
        itemAnalysisJob = viewModelScope.launch {
            analyzeReceiptItemsInternal(receiptId)
        }
    }

    private suspend fun analyzeReceiptItemsInternal(receiptId: Long) {
        if (!_state.value.matchesReceiptForAnalysis(receiptId)) return

        _state.update { current ->
            if (!current.matchesReceiptForAnalysis(receiptId)) current
            else current.copy(isAnalyzingItems = true, itemAnalysisError = null)
        }

        try {
            val result = categorizeReceiptItemsUseCase(receiptId)
            if (!_state.value.matchesReceiptForAnalysis(receiptId)) return

            when (result) {
                is com.yourname.expensetracker.domain.ai.model.CategorizationResult.Success -> {
                    val items = receiptItemCategorizationRepository.getByReceiptIdAsSnapshots(receiptId)
                    if (!_state.value.matchesReceiptForAnalysis(receiptId)) return

                    _state.update { current ->
                        if (!current.matchesReceiptForAnalysis(receiptId)) current
                        else current.copy(
                            itemCategorizations = items,
                            showItemBreakdown = true,
                            isAnalyzingItems = false,
                            itemAnalysisError = null
                        )
                    }
                }

                is com.yourname.expensetracker.domain.ai.model.CategorizationResult.AlreadyAnalyzed -> {
                    _state.update { current ->
                        if (!current.matchesReceiptForAnalysis(receiptId)) current
                        else current.copy(
                            itemCategorizations = result.items,
                            showItemBreakdown = true,
                            isAnalyzingItems = false,
                            itemAnalysisError = null
                        )
                    }
                }

                else -> {
                    _state.update { current ->
                        if (!current.matchesReceiptForAnalysis(receiptId)) current
                        else current.copy(
                            isAnalyzingItems = false,
                            itemAnalysisError = "Item analysis returned no categorizations."
                        )
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { current ->
                if (!current.matchesReceiptForAnalysis(receiptId)) current
                else current.copy(
                    isAnalyzingItems = false,
                    itemAnalysisError = "Item analysis failed (UNKNOWN_ERROR)"
                )
            }
        }
    }

    fun clearItemAnalysisError() {
        _state.update { it.copy(itemAnalysisError = null) }
    }

    fun updateItemCategory(item: ReceiptItemCategorizationSnapshot, category: Category?) {
        // S7-025: Mark item as updating
        _state.update { it.copy(itemCorrectionUpdatingIds = it.itemCorrectionUpdatingIds + item.id, itemCorrectionError = null) }
        viewModelScope.launch {
            // S7-018: Capture receiptId to guard against stale writes after scan change
            val capturedReceiptId = _state.value.receiptId
            try {
                val now = timeProvider.now()
                receiptItemCategorizationRepository.updateUserCorrection(
                    itemId = item.id,
                    categoryId = category?.id,
                    categoryName = category?.name,
                    timestamp = now
                )
                val receiptId = capturedReceiptId ?: return@launch
                // S7-018: Only reload if still on the same receipt
                if (_state.value.receiptId != receiptId) return@launch
                val updatedItems = receiptItemCategorizationRepository.getByReceiptIdAsSnapshots(receiptId)
                _state.update { it.copy(itemCategorizations = updatedItems) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(itemCorrectionError = "Failed to save category (UNKNOWN_ERROR)") }
            } finally {
                // S7-017: Always clear updating state
                _state.update { it.copy(itemCorrectionUpdatingIds = it.itemCorrectionUpdatingIds - item.id) }
            }
        }
    }

    fun toggleItemBreakdown() {
        _state.update { it.copy(showItemBreakdown = !it.showItemBreakdown) }
    }

    // S7-024: Show rationale dialog
    fun showItemRationale(item: ReceiptItemCategorizationSnapshot) {
        _state.update { it.copy(selectedItemRationale = item) }
    }

    fun dismissItemRationale() {
        _state.update { it.copy(selectedItemRationale = null) }
    }

    fun clearItemCorrectionError() {
        _state.update { it.copy(itemCorrectionError = null) }
    }

    private fun ReceiptScanState.clearItemAnalysisState(): ReceiptScanState {
        return copy(
            itemCategorizations = emptyList(),
            isAnalyzingItems = false,
            showItemBreakdown = false,
            itemAnalysisError = null,
            itemAnalysisAvailable = true,
            selectedItemRationale = null,
            itemCorrectionUpdatingIds = emptySet(),
            itemCorrectionError = null
        )
    }

    private fun ReceiptScanState.matchesReceiptForAnalysis(receiptId: Long): Boolean {
        return step == ScanStep.REVIEW && this.receiptId == receiptId
    }

}
