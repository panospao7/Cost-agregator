package com.yourname.expensetracker.ui.screens.receiptscan

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ReceiptItemCategorizationRepository
import com.yourname.expensetracker.data.repository.ReceiptRepository
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
    
    // Item categorization
    val itemCategorizations: List<ReceiptItemCategorizationSnapshot> = emptyList(),
    val isAnalyzingItems: Boolean = false,
    val showItemBreakdown: Boolean = false,
    val itemAnalysisError: String? = null,
    
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
    val date: Long,
    val categoryId: Long?,
    val paymentMethod: PaymentMethod,
    val notes: String?
)

sealed class SaveReceiptResult {
    data object Success : SaveReceiptResult()
    data object Duplicate : SaveReceiptResult()
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
    private val receiptParser: ReceiptParser
) : ViewModel() {

    private val _state = MutableStateFlow(ReceiptScanState(
        tempCameraUri = savedStateHandle.get<Uri>("temp_uri"),
        editDate = timeProvider.now()
    ))
    val state: StateFlow<ReceiptScanState> = _state.asStateFlow()

    val categories: StateFlow<List<Category>> = categoryRepository.allCategories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var itemAnalysisJob: Job? = null

    init {
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

        viewModelScope.launch {
            val startTime = timeProvider.now()
            val parsingLogs = mutableListOf<String>()
            
            try {
                val receiptResult = receiptLifecycleCoordinator.processReceiptInput(uri)
                if (receiptResult.isFailure) {
                    throw receiptResult.exceptionOrNull()!!
                }
                val receipt = receiptResult.getOrThrow()

                val isOcrFailure = receipt.processingStatus == ReceiptProcessingStatus.OCR_FAILED.name

                if (isOcrFailure) {
                    // OCR failure — show review step with empty fields for manual entry
                    val now = timeProvider.now()

                    val debugData = DebugData(
                        rawText = receipt.rawOcrText,
                        parsedTransactions = emptyList(),
                        parsingLogs = parsingLogs.also { it.add("OCR processing failed, manual entry available") },
                        processingTimeMs = timeProvider.now() - startTime,
                        parserUsed = "Manual (OCR Failed)"
                    )

                    val emptyParsed = ReceiptParser.ParsedReceipt(
                        merchantName = null, total = null, subtotal = null, tax = null,
                        date = now, currency = "EUR", lineItems = emptyList(), confidence = 0f
                    )

                    _state.update {
                        it.copy(
                            step = ScanStep.REVIEW,
                            imageUri = receipt.imagePath?.let { Uri.fromFile(java.io.File(it)) } ?: uri,
                            tempCameraUri = null,
                            parsedReceipt = emptyParsed,
                            receiptId = receipt.id,
                            rawOcrText = receipt.rawOcrText,
                            showRawText = false,
                            editMerchant = "",
                            editAmount = "",
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
                    // Normal success path
                    val lineItems = receipt.parsedItems?.let {
                        try { receiptParser.lineItemsFromJson(it) } catch (_: Exception) { emptyList() }
                    } ?: emptyList()

                    val parsed = ReceiptParser.ParsedReceipt(
                        merchantName = receipt.parsedMerchant,
                        total = receipt.parsedTotal,
                        subtotal = null,
                        tax = receipt.parsedTaxAmount,
                        date = receipt.parsedDate,
                        currency = receipt.currency,
                        lineItems = lineItems,
                        confidence = receipt.confidence
                    )

                    val processingTime = timeProvider.now() - startTime

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
                                    date = parsed.date
                                )
                            }
                        ),
                        parsingLogs = if (parsed.confidence < 0.7f) {
                            listOf("Low confidence parsing (${(parsed.confidence * 100).toInt()}%)")
                        } else emptyList(),
                        processingTimeMs = processingTime,
                        parserUsed = "ReceiptParser"
                    )

                    _state.update {
                        it.copy(
                            step = ScanStep.REVIEW,
                            imageUri = receipt.imagePath?.let { Uri.fromFile(java.io.File(it)) } ?: uri,
                            parsedReceipt = parsed,
                            receiptId = receipt.id,
                            rawOcrText = receipt.rawOcrText,
                            editMerchant = parsed.merchantName ?: "",
                            editAmount = parsed.total?.let { total ->
                                String.format("%.2f", total)
                            } ?: "",
                            editDate = parsed.date ?: timeProvider.now(),
                            ocrConfidence = parsed.confidence,
                            selectedCategoryId = null, // Will be auto-detected on save
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

                    // Auto-trigger item categorization if AI enabled and items exist
                    if (lineItems.isNotEmpty()) {
                        itemAnalysisJob?.cancel()
                        itemAnalysisJob = viewModelScope.launch {
                            val settings = aiSettingsRepository.settings().first()
                            if (settings.aiEnabled && settings.receiptItemCategorizationEnabled) {
                                analyzeReceiptItemsInternal(receipt.id)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                parsingLogs.add("Processing Error: ${e.message}")
                val now = timeProvider.now()

                val debugData = DebugData(
                    rawText = "",
                    parsedTransactions = emptyList(),
                    parsingLogs = parsingLogs,
                    processingTimeMs = timeProvider.now() - startTime,
                    parserUsed = "Failed"
                )

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
                        debugData = debugData
                    ).clearItemAnalysisState()
                }
            }
        }
    }

    fun updateMerchant(value: String) {
        _state.update { it.copy(editMerchant = value) }
    }

    fun updateAmount(value: String) {
        val filtered = value.filter { it.isDigit() || it == '.' || it == ',' }
        _state.update { it.copy(editAmount = filtered) }
    }

    fun updateDate(dateMs: Long) {
        _state.update { it.copy(editDate = dateMs) }
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

        viewModelScope.launch {
            _state.update {
                it.copy(
                    receiptAssistState = AiLoadState.Loading,
                    receiptAssistMessage = null,
                    receiptAssistDiagnostics = null,
                    errorMessage = null
                )
            }

            when (val result = suggestReceiptExtractionUseCase(receiptId, force = force)) {
                is ReceiptAssistGenerationResult.Success -> {
                    val diagnostics = latestArtifactDiagnostics(
                        targetKey = "scanned_receipt:$receiptId",
                        capability = AiCapability.RECEIPT_EXTRACTION
                    )

                    _state.update {
                        it.copy(
                            receiptAssistState = AiLoadState.Ready(result.suggestion),
                            receiptAssistDiagnostics = diagnostics,
                            receiptAssistMessage = if (result.fromCache) {
                                "Showing cached AI receipt suggestions."
                            } else if (result.usedImageInput) {
                                "Image-aware AI cross-checked the receipt photo and OCR text."
                            } else {
                                "AI suggested a few receipt fields to review."
                            }
                        )
                    }
                }
                is ReceiptAssistGenerationResult.Disabled -> {
                    _state.update {
                        it.copy(
                            receiptAssistState = AiLoadState.Disabled,
                            receiptAssistDiagnostics = null,
                            receiptAssistMessage = result.reason
                        )
                    }
                }
                is ReceiptAssistGenerationResult.NotNeeded -> {
                    _state.update {
                        it.copy(
                            receiptAssistState = AiLoadState.Idle,
                            receiptAssistDiagnostics = null,
                            receiptAssistMessage = result.reason
                        )
                    }
                }
                is ReceiptAssistGenerationResult.Error -> {
                    val diagnostics = latestArtifactDiagnostics(
                        targetKey = "scanned_receipt:$receiptId",
                        capability = AiCapability.RECEIPT_EXTRACTION,
                        expectedStatus = AiArtifactStatus.FAILED
                    )
                    _state.update {
                        it.copy(
                            receiptAssistState = AiLoadState.Error(result.reason),
                            receiptAssistDiagnostics = diagnostics,
                            receiptAssistMessage = result.reason
                        )
                    }
                }
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

        viewModelScope.launch {
            val receipt = receiptRepository.getReceiptById(receiptId)
            if (receipt == null) {
                _state.update {
                    it.copy(
                        categoryAssistState = AiLoadState.Error("Receipt not found"),
                        categoryAssistDiagnostics = null,
                        categoryAssistMessage = "Receipt not found"
                    )
                }
                return@launch
            }

            _state.update {
                it.copy(
                    categoryAssistState = AiLoadState.Loading,
                    categoryAssistMessage = null,
                    categoryAssistDiagnostics = null,
                    errorMessage = null
                )
            }

            when (
                val result = suggestCategoryFallbackUseCase(
                    receipt = receipt,
                    draftMerchant = currentState.editMerchant,
                    draftAmount = AmountUtils.parseAmount(currentState.editAmount),
                    draftDate = currentState.editDate.takeIf { it > 0L },
                    currentCategoryId = currentState.selectedCategoryId,
                    force = force
                )
            ) {
                is CategoryAssistGenerationResult.Success -> {
                    val diagnostics = latestArtifactDiagnostics(
                        targetKey = "scanned_receipt:$receiptId",
                        capability = AiCapability.CATEGORIZATION_FALLBACK
                    )
                    _state.update {
                        it.copy(
                            categoryAssistState = AiLoadState.Ready(result.suggestion),
                            categoryAssistDiagnostics = diagnostics,
                            categoryAssistMessage = if (result.fromCache) {
                                "Showing cached AI category suggestion."
                            } else {
                                "AI suggested a category to review."
                            }
                        )
                    }
                }
                is CategoryAssistGenerationResult.Disabled -> {
                    _state.update {
                        it.copy(
                            categoryAssistState = AiLoadState.Disabled,
                            categoryAssistDiagnostics = null,
                            categoryAssistMessage = result.reason
                        )
                    }
                }
                is CategoryAssistGenerationResult.NotNeeded -> {
                    _state.update {
                        it.copy(
                            categoryAssistState = AiLoadState.Idle,
                            categoryAssistDiagnostics = null,
                            categoryAssistMessage = result.reason
                        )
                    }
                }
                is CategoryAssistGenerationResult.Error -> {
                    val diagnostics = latestArtifactDiagnostics(
                        targetKey = "scanned_receipt:$receiptId",
                        capability = AiCapability.CATEGORIZATION_FALLBACK,
                        expectedStatus = AiArtifactStatus.FAILED
                    )
                    _state.update {
                        it.copy(
                            categoryAssistState = AiLoadState.Error(result.reason),
                            categoryAssistDiagnostics = diagnostics,
                            categoryAssistMessage = result.reason
                        )
                    }
                }
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
        _state.update {
            it.copy(
                selectedCategoryId = readyState.value.categoryId,
                categoryAssistMessage = "Applied AI category suggestion to the draft."
            )
        }
        markLatestArtifactApplied(AiCapability.CATEGORIZATION_FALLBACK)
    }

    fun clearCategoryAssistMessage() {
        _state.update { it.copy(categoryAssistMessage = null) }
    }

    fun applyReceiptAssistMerchant() {
        applySuggestedValue(_state.value.receiptAssistState) { state, suggestion ->
            suggestion.merchant?.value?.takeIf { it.isNotBlank() }?.let { merchant ->
                state.copy(editMerchant = merchant, receiptAssistMessage = "Applied AI merchant suggestion.")
            } ?: state
        }
        markLatestArtifactApplied(AiCapability.RECEIPT_EXTRACTION)
    }

    fun applyReceiptAssistTotal() {
        applySuggestedValue(_state.value.receiptAssistState) { state, suggestion ->
            suggestion.total?.value?.let { total ->
                state.copy(
                    editAmount = String.format("%.2f", total),
                    receiptAssistMessage = "Applied AI total suggestion."
                )
            } ?: state
        }
        markLatestArtifactApplied(AiCapability.RECEIPT_EXTRACTION)
    }

    fun applyReceiptAssistDate() {
        applySuggestedValue(_state.value.receiptAssistState) { state, suggestion ->
            suggestion.date?.value?.let { date ->
                state.copy(editDate = date, receiptAssistMessage = "Applied AI date suggestion.")
            } ?: state
        }
        markLatestArtifactApplied(AiCapability.RECEIPT_EXTRACTION)
    }

    fun applyAllReceiptAssist() {
        val readyState = _state.value.receiptAssistState as? AiLoadState.Ready ?: return
        val suggestion = readyState.value
        _state.update { state ->
            state.copy(
                editMerchant = suggestion.merchant?.value?.takeIf { it.isNotBlank() } ?: state.editMerchant,
                editAmount = suggestion.total?.value?.let { String.format("%.2f", it) } ?: state.editAmount,
                editDate = suggestion.date?.value ?: state.editDate,
                receiptAssistMessage = "Applied all AI receipt suggestions to the draft."
            )
        }
        markLatestArtifactApplied(AiCapability.RECEIPT_EXTRACTION)
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
            _state.update {
                it.copy(
                    quickSavePreview = null,
                    errorMessage = "Receipt quick save is turned off."
                )
            }
            return
        }
        val request = ReceiptSaveRequest(
            receiptId = currentState.receiptId ?: return,
            merchant = preview.merchant,
            amount = preview.amount,
            date = preview.date,
            categoryId = preview.categoryId,
            paymentMethod = currentState.paymentMethod,
            notes = currentState.notes.takeIf { it.isNotBlank() }
        )

        _state.update {
            it.copy(
                editMerchant = preview.merchant,
                editAmount = preview.amountText,
                editDate = preview.date,
                selectedCategoryId = preview.categoryId ?: it.selectedCategoryId,
                quickSavePreview = null,
                receiptAssistMessage = if (preview.autoAppliedFields.isNotEmpty()) {
                    "AI quick save filled ${preview.autoAppliedFields.joinToString(", ")} before saving."
                } else {
                    it.receiptAssistMessage
                }
            )
        }

        preview.usedCapabilities.forEach(::markLatestArtifactApplied)
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
        viewModelScope.launch {
            aiArtifactRepository.getLatest("scanned_receipt:$receiptId", capability)
                ?.let { artifact ->
                    aiArtifactRepository.markApplied(artifact.id)
                }
        }
    }

    fun saveExpense() {
        val currentState = _state.value
        val request = buildManualSaveRequest(currentState) ?: return
        saveExpenseInternal(request)
    }

    fun retry() {
        itemAnalysisJob?.cancel()
        _state.update {
            ReceiptScanState(
                editDate = timeProvider.now(),
                receiptQuickSaveEnabled = it.receiptQuickSaveEnabled
            )
        }
    }

    fun reset() {
        itemAnalysisJob?.cancel()
        _state.update {
            ReceiptScanState(
                editDate = timeProvider.now(),
                receiptQuickSaveEnabled = it.receiptQuickSaveEnabled
            )
        }
    }

    private fun buildManualSaveRequest(currentState: ReceiptScanState): ReceiptSaveRequest? {
        val merchant = currentState.editMerchant.trim()
        if (merchant.isBlank()) {
            _state.update {
                it.copy(errorMessage = "Merchant name is required")
            }
            return null
        }

        val amount = AmountUtils.parseAmount(currentState.editAmount)
        if (amount == null || amount <= 0) {
            _state.update {
                it.copy(errorMessage = "Enter a valid amount")
            }
            return null
        }

        val receiptId = currentState.receiptId ?: return null
        return ReceiptSaveRequest(
            receiptId = receiptId,
            merchant = merchant,
            amount = amount,
            date = currentState.editDate,
            categoryId = currentState.selectedCategoryId,
            paymentMethod = currentState.paymentMethod,
            notes = currentState.notes.takeIf { it.isNotBlank() }
        )
    }

    private fun buildQuickSavePreview(currentState: ReceiptScanState): ReceiptQuickSavePreview? {
        if (!currentState.receiptQuickSaveEnabled || currentState.step != ScanStep.REVIEW) return null
        if (currentState.receiptId == null || currentState.isSaving) return null

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
            amountText = String.format("%.2f", finalAmount),
            date = date,
            categoryId = categoryId,
            categoryName = categoryName,
            autoAppliedFields = autoAppliedFields,
            usedCapabilities = usedCapabilities,
            fieldSummaries = buildList {
                add(ReceiptQuickSaveFieldSummary("Merchant", merchant, merchantSource))
                add(ReceiptQuickSaveFieldSummary("Amount", String.format("%.2f", finalAmount), amountSource))
                if (date > 0L) {
                    add(ReceiptQuickSaveFieldSummary("Date", date.toString(), dateSource))
                }
                categoryId?.let {
                    add(
                        ReceiptQuickSaveFieldSummary(
                            label = "Category",
                            value = categoryName ?: "Selected category",
                            source = categorySource ?: "Current draft"
                        )
                    )
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
        _state.update { it.copy(isSaving = true, errorMessage = null) }

        viewModelScope.launch {
            try {
                val defaultCurrency = try {
                    currencySettingsRepository.homeCurrency().first()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    "EUR"
                }
                val resolvedCurrency = _state.value.parsedReceipt?.currency
                    ?.takeIf { it.isNotBlank() }
                    ?: defaultCurrency

                val result = receiptRepository.createExpenseFromReceipt(
                    receiptId = request.receiptId,
                    merchant = request.merchant,
                    amount = request.amount,
                    currency = resolvedCurrency,
                    categoryId = request.categoryId,
                    date = request.date,
                    paymentMethod = request.paymentMethod,
                    notes = request.notes
                )

                when (result) {
                    is Result.Success -> {
                        _state.update {
                            it.copy(
                                isSaving = false,
                                step = ScanStep.DONE,
                                saveResult = SaveReceiptResult.Success
                            )
                        }
                    }
                    is Result.Duplicate -> {
                        _state.update {
                            it.copy(
                                isSaving = false,
                                saveResult = SaveReceiptResult.Duplicate
                            )
                        }
                    }
                    is Result.Error -> {
                        _state.update {
                            it.copy(
                                isSaving = false,
                                saveResult = SaveReceiptResult.Error(result.message ?: "Unknown error")
                            )
                        }
                    }
                    Result.Loading -> {
                        _state.update { it.copy(isSaving = true) }
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isSaving = false,
                        saveResult = SaveReceiptResult.Error(
                            e.message ?: "Unknown error"
                        )
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
                    itemAnalysisError = e.message ?: "Item analysis failed."
                )
            }
        }
    }

    fun clearItemAnalysisError() {
        _state.update { it.copy(itemAnalysisError = null) }
    }

    fun updateItemCategory(item: ReceiptItemCategorizationSnapshot, category: Category?) {
        viewModelScope.launch {
            val now = timeProvider.now()
            
            // Update in database
            receiptItemCategorizationRepository.updateUserCorrection(
                itemId = item.id,
                categoryId = category?.id,
                categoryName = category?.name,
                timestamp = now
            )
            
            // Reload items
            val receiptId = _state.value.receiptId ?: return@launch
            val updatedItems = receiptItemCategorizationRepository.getByReceiptIdAsSnapshots(receiptId)
            
            _state.update { it.copy(itemCategorizations = updatedItems) }
        }
    }

    fun toggleItemBreakdown() {
        _state.update { it.copy(showItemBreakdown = !it.showItemBreakdown) }
    }

    fun showItemRationale(item: ReceiptItemCategorizationSnapshot) {
        // This would show a dialog with AI rationale - for now just log it
        Timber.d("Item rationale: ${item.aiRationale}")
    }

    private fun ReceiptScanState.clearItemAnalysisState(): ReceiptScanState {
        return copy(
            itemCategorizations = emptyList(),
            isAnalyzingItems = false,
            showItemBreakdown = false,
            itemAnalysisError = null
        )
    }

    private fun ReceiptScanState.matchesReceiptForAnalysis(receiptId: Long): Boolean {
        return step == ScanStep.REVIEW && this.receiptId == receiptId
    }

}
