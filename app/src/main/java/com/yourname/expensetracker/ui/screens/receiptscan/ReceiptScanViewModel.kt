package com.yourname.expensetracker.ui.screens.receiptscan

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.debug.AiRuntimeDiagnostics
import com.yourname.expensetracker.domain.model.Result
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import com.yourname.expensetracker.domain.parser.ParsedTransactionType
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import com.yourname.expensetracker.domain.receipt.ReceiptProcessingStatus
import com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLifecycleCoordinator
import com.yourname.expensetracker.domain.util.AmountUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.ui.screens.debug.DebugData
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
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
    val debugData: DebugData? = null
)

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
    val appliedAiCapabilities: Set<AiCapability> = emptySet()
)

sealed class SaveReceiptResult {
    /** S7-022: Include expenseId so UI can offer "View transaction" */
    data class Success(val expenseId: Long) : SaveReceiptResult()
    data object DuplicateTransaction : SaveReceiptResult()
    data class DuplicateReceipt(val existingReceiptId: Long, val linkedExpenseId: Long?) : SaveReceiptResult()
    data class PartialLinkFailure(val expenseId: Long, val message: String) : SaveReceiptResult()
    data class Error(val message: String) : SaveReceiptResult()
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
    private val hybridClassifier: HybridExpenseClassifier
) : ViewModel() {

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

    init {
        // S7-009: Load home currency on init so editCurrency is never a hardcoded "EUR"
        viewModelScope.launch {
            currencySettingsRepository.homeCurrency().collect { homeCurrency ->
                _state.update { current ->
                    current.copy(
                        editCurrency = current.editCurrency ?: homeCurrency
                    )
                }
            }
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

        _state.update {
            it.copy(
                step = ScanStep.PROCESSING,
                imageUri = uri,
                errorMessage = null,
                receiptAssistState = AiLoadState.Idle,
                receiptAssistMessage = null,
                receiptAssistDiagnostics = null,
                categoryAssistState = AiLoadState.Idle,
                categoryAssistMessage = null,
                categoryAssistDiagnostics = null,
                quickSavePreview = null
            ).clearItemAnalysisState()
        }

        scanJob = viewModelScope.launch {
            val startTime = timeProvider.now()
            val parsingLogs = mutableListOf<String>()

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
                val receipt = receiptResult.getOrThrow()

                // S7-003: Discard result if a newer scan has started
                if (requestId != scanRequestSeq) return@launch

                // S7-F583-002/S7-66F-003: If the receipt is already linked or is a known duplicate,
                // show DUPLICATE state instead of normal review — block direct save.
                val linkedExpenseId = receipt.expenseId
                val isAlreadyLinked = linkedExpenseId != null ||
                    !receiptLinkService.checkCanLinkReceipt(receipt.id)
                if (isAlreadyLinked) {
                    _state.update {
                        it.copy(
                            step = ScanStep.DUPLICATE,
                            receiptId = receipt.id,
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
                        rawText = receipt.rawOcrText,
                        parsedTransactions = emptyList(),
                        parsingLogs = parsingLogs.also { it.add("OCR processing failed, manual entry available") },
                        processingTimeMs = timeProvider.now() - startTime,
                        parserUsed = "Manual (OCR Failed)"
                    )
                    // S7-009: Use loaded home currency, not hardcoded "EUR"
                    val homeCurrency = _state.value.editCurrency
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
                            rawOcrText = receipt.rawOcrText,
                            showRawText = false,
                            editMerchant = "",
                            editAmount = "",
                            editCurrency = homeCurrency,
                            editDate = now,
                            selectedCategoryId = null,
                            paymentMethod = PaymentMethod.CARD,
                            notes = "",
                            ocrConfidence = 0f,
                            errorMessage = "OCR could not be processed. You can enter details manually.",
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
                } else {
                    val lineItems = receipt.parsedItems?.let {
                        try { receiptParser.lineItemsFromJson(it) } catch (_: Exception) { emptyList() }
                    } ?: emptyList()

                    val computedTaxInclusive = ReceiptParser.isTaxInclusive(
                        receipt.parsedTotal, receipt.parsedTaxAmount, lineItems
                    )
                    val parsed = ReceiptParser.ParsedReceipt(
                        merchantName = receipt.parsedMerchant,
                        total = receipt.parsedTotal,
                        subtotal = null,
                        tax = receipt.parsedTaxAmount,
                        date = receipt.parsedDate,
                        currency = receipt.currency,
                        lineItems = lineItems,
                        confidence = receipt.confidence,
                        taxInclusive = receipt.taxInclusive || computedTaxInclusive
                    )

                    val debugData = DebugData(
                        rawText = receipt.rawOcrText,
                        parsedTransactions = listOfNotNull(
                            parsed.total?.let { total ->
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

                    _state.update {
                        it.copy(
                            step = ScanStep.REVIEW,
                            imageUri = receipt.imagePath?.let { p -> Uri.fromFile(java.io.File(p)) } ?: uri,
                            parsedReceipt = parsed,
                            receiptId = receipt.id,
                            rawOcrText = receipt.rawOcrText,
                            editMerchant = parsed.merchantName ?: "",
                            // S7-011: Locale.US for consistent decimal formatting
                            editAmount = parsed.total?.let { total ->
                                String.format(java.util.Locale.US, "%.2f", total)
                            } ?: "",
                            // S7-009: Use OCR currency if present, else keep loaded home currency
                            editCurrency = parsed.currency?.takeIf { c -> c.isNotBlank() } ?: it.editCurrency,
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
                parsingLogs.add("Processing Error: ${e.message}")
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
                        errorMessage = "Total failure: ${e.message}",
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
        _state.update { it.copy(editAmount = sanitized, isAmountEditedByUser = true) }
    }

    fun updateDate(dateMs: Long) {
        _state.update { it.copy(editDate = dateMs) }
    }

    /**
     * RCP-10/N2: User can change the currency of the scanned receipt
     * before saving. Default is the OCR-detected currency or home currency.
     */
    fun updateCurrency(currency: String) {
        _state.update { it.copy(editCurrency = currency) }
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
        val hasAmount = AmountUtils.parseAmount(currentState.editAmount)?.let { it > 0 } == true ||
            (receiptSuggestion?.total?.value?.let { it > 0 } == true)

        return when {
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
                Timber.e(e, "Receipt assist failed for receipt $receiptId")
                _state.update { current ->
                    if (current.receiptId != receiptId) current
                    else current.copy(receiptAssistState = AiLoadState.Error(e.message ?: "AI assist failed"))
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
                    draftAmount = AmountUtils.parseAmount(currentState.editAmount),
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
                Timber.e(e, "Category assist failed for receipt $receiptId")
                // S7-66F-010: Guard catch path by receiptId
                _state.update { current ->
                    if (current.receiptId != receiptId) current
                    else current.copy(categoryAssistState = AiLoadState.Error(e.message ?: "AI assist failed"))
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
        applySuggestedValue(_state.value.receiptAssistState) { state, suggestion ->
            suggestion.total?.value?.let { total ->
                state.copy(
                    editAmount = String.format(java.util.Locale.US, "%.2f", total),
                    receiptAssistMessage = "Applied AI total suggestion.",
                    pendingAppliedAiCapabilities = state.pendingAppliedAiCapabilities + AiCapability.RECEIPT_EXTRACTION
                )
            } ?: state
        }
    }

    fun applyReceiptAssistDate() {
        applySuggestedValue(_state.value.receiptAssistState) { state, suggestion ->
            suggestion.date?.value?.let { date ->
                state.copy(
                    editDate = date,
                    receiptAssistMessage = "Applied AI date suggestion.",
                    pendingAppliedAiCapabilities = state.pendingAppliedAiCapabilities + AiCapability.RECEIPT_EXTRACTION
                )
            } ?: state
        }
    }

    fun applyAllReceiptAssist() {
        val readyState = _state.value.receiptAssistState as? AiLoadState.Ready ?: return
        val suggestion = readyState.value
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
            appliedAiCapabilities = preview.usedCapabilities
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
        val request = buildManualSaveRequest(currentState) ?: return
        saveExpenseInternal(request)
    }

    fun retry() {
        cancelActiveWork()
        _state.update {
            ReceiptScanState(
                editDate = timeProvider.now(),
                receiptQuickSaveEnabled = it.receiptQuickSaveEnabled
            )
        }
    }

    fun reset() {
        cancelActiveWork()
        _state.update {
            ReceiptScanState(
                editDate = timeProvider.now(),
                receiptQuickSaveEnabled = it.receiptQuickSaveEnabled
            )
        }
    }

    /** S7-F583-006: Centralized cleanup — cancels all in-flight work and clears assist state. */
    private fun cancelActiveWork() {
        scanRequestSeq++
        scanJob?.cancel()
        scanJob = null
        itemAnalysisJob?.cancel()
        itemAnalysisJob = null
        inFlightAssist.clear()
    }

    override fun onCleared() {
        cancelActiveWork()
        super.onCleared()
    }

    private fun buildManualSaveRequest(currentState: ReceiptScanState): ReceiptSaveRequest? {
        // S7-021: Must be in REVIEW state
        if (currentState.step != ScanStep.REVIEW) return null

        val merchant = currentState.editMerchant.trim()
        if (merchant.isBlank()) {
            _state.update { it.copy(errorMessage = "Merchant name is required") }
            return null
        }

        val amount = AmountUtils.parseAmount(currentState.editAmount)
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
            appliedAiCapabilities = currentState.pendingAppliedAiCapabilities
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

        // RCP-11: Skip quick-save when OCR confidence is too low — the
        // extracted data is unreliable and requires user review.
        if (currentState.ocrConfidence < QUICK_SAVE_MIN_CONFIDENCE) return null

        var merchant = currentState.editMerchant.trim()
        var amount = AmountUtils.parseAmount(currentState.editAmount)
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
            try {
                // S7-66F-002: Real linkability check — no sentinel expense ID
                val canLink = receiptLinkService.checkCanLinkReceipt(request.receiptId)
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
                val effectiveAmount = if (taxInclusive && parsedTotal != null && !_state.value.isAmountEditedByUser) {
                    Timber.d("RCP-14: Using parsed total %.2f for tax-inclusive receipt %d (user did not edit)", parsedTotal, request.receiptId)
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
                    scannedReceiptId = request.receiptId
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
                            }.onFailure { e -> Timber.w(e, "Classifier learning failed after receipt save") }
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
                        Timber.e(e, "Atomic receipt save failed for receipt ${request.receiptId}")
                        val isDuplicate = e.message?.contains("Duplicate", ignoreCase = true) == true
                        _state.update {
                            it.copy(
                                isSaving = false,
                                saveResult = if (isDuplicate) SaveReceiptResult.DuplicateTransaction
                                             else SaveReceiptResult.Error(e.message ?: "Save failed")
                            )
                        }
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(isSaving = false, saveResult = SaveReceiptResult.Error(e.message ?: "Unknown error"))
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
                    itemAnalysisError = e.message ?: "Item analysis failed."
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
                _state.update { it.copy(itemCorrectionError = "Failed to save category: ${e.message}") }
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
