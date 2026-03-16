package com.yourname.expensetracker.ui.screens.receiptscan

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ReceiptRepository
import com.yourname.expensetracker.domain.ai.model.AiLoadState
import com.yourname.expensetracker.domain.ai.model.ReceiptAssistGenerationResult
import com.yourname.expensetracker.domain.ai.model.ReceiptAssistSuggestion
import com.yourname.expensetracker.domain.ai.model.toDiagnosticsOrNull
import com.yourname.expensetracker.domain.ai.model.toDisplayText
import com.yourname.expensetracker.domain.ai.usecase.SuggestReceiptExtractionUseCase
import com.yourname.expensetracker.domain.ai.service.AiArtifactRepository
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import com.yourname.expensetracker.domain.model.Result
import com.yourname.expensetracker.ui.screens.debug.DebugData
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.util.AmountUtils
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
    
    // Debug data
    val debugData: DebugData? = null
)

sealed class SaveReceiptResult {
    data object Success : SaveReceiptResult()
    data object Duplicate : SaveReceiptResult()
    data class Error(val message: String) : SaveReceiptResult()
}

@HiltViewModel
class ReceiptScanViewModel @Inject constructor(
    private val receiptRepository: ReceiptRepository,
    private val categoryRepository: CategoryRepository,
    private val savedStateHandle: androidx.lifecycle.SavedStateHandle,
    private val timeProvider: TimeProvider,
    private val suggestReceiptExtractionUseCase: SuggestReceiptExtractionUseCase,
    private val aiArtifactRepository: AiArtifactRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ReceiptScanState(
        tempCameraUri = savedStateHandle.get<Uri>("temp_uri"),
        editDate = timeProvider.now()
    ))
    val state: StateFlow<ReceiptScanState> = _state.asStateFlow()

    val categories: StateFlow<List<Category>> = categoryRepository.allCategories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
        _state.update {
            it.copy(
                step = ScanStep.PROCESSING,
                imageUri = uri,
                errorMessage = null,
                receiptAssistState = AiLoadState.Idle,
                receiptAssistMessage = null,
                receiptAssistDiagnostics = null
            )
        }

        viewModelScope.launch {
            val startTime = timeProvider.now()
            val parsingLogs = mutableListOf<String>()
            
            try {
                // Manual scans do NOT auto-create review items (User confirms in this UI)
                val (receipt, parsed) = receiptRepository.processReceipt(uri, autoCreateReview = false)
                
                val processingTime = timeProvider.now() - startTime
                
                // Create debug data
                val debugData = DebugData(
                    rawText = receipt.rawOcrText,
                    parsedTransactions = listOfNotNull(
                        parsed.total?.let { total ->
                            ParsedTransaction(
                                amount = total,
                                currency = "EUR",
                                merchant = parsed.merchantName ?: "Unknown",
                                type = com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE,
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
                        imageUri = Uri.fromFile(java.io.File(receipt.imagePath)),
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
                        debugData = debugData
                    )
                }
            } catch (e: Exception) {
                parsingLogs.add("OCR Error: ${e.message}")
                
                try {
                    val (receipt, parsed) = receiptRepository.saveManualReceiptRecord(uri)
                    
                    val debugData = DebugData(
                        rawText = "",
                        parsedTransactions = emptyList(),
                        parsingLogs = parsingLogs,
                        processingTimeMs = timeProvider.now() - startTime,
                        parserUsed = "Manual (OCR Failed)"
                    )
                    
                    _state.update {
                        it.copy(
                            step = ScanStep.REVIEW,
                            imageUri = uri,
                            parsedReceipt = parsed,
                            receiptId = receipt.id,
                            errorMessage = "OCR Failed: ${e.message}. You can enter details manually.",
                            receiptAssistState = AiLoadState.Idle,
                            receiptAssistMessage = null,
                            receiptAssistDiagnostics = null,
                            debugData = debugData
                        )
                    }
                } catch (fallbackError: Exception) {
                    parsingLogs.add("Fallback Error: ${fallbackError.message}")
                    
                    val debugData = DebugData(
                        rawText = "",
                        parsedTransactions = emptyList(),
                        parsingLogs = parsingLogs,
                        processingTimeMs = System.currentTimeMillis() - startTime,
                        parserUsed = "Failed"
                    )
                    
                    _state.update {
                        it.copy(
                            step = ScanStep.ERROR,
                            errorMessage = "Total failure: ${fallbackError.message}",
                            debugData = debugData
                        )
                    }
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
        val parsed = currentState.parsedReceipt
        return parsed?.merchantName.isNullOrBlank() ||
            parsed?.total == null ||
            parsed?.date == null ||
            currentState.ocrConfidence < com.yourname.expensetracker.domain.config.AppConfig.Ai.MIN_RECEIPT_CONFIDENCE_FOR_AI_FALLBACK
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
                    val diagnostics = aiArtifactRepository.getLatest(
                        targetKey = "scanned_receipt:$receiptId",
                        capability = com.yourname.expensetracker.domain.ai.model.AiCapability.RECEIPT_EXTRACTION
                    )?.toDiagnosticsOrNull()?.toDisplayText()

                    _state.update {
                        it.copy(
                            receiptAssistState = AiLoadState.Ready(result.suggestion),
                            receiptAssistDiagnostics = diagnostics,
                            receiptAssistMessage = if (result.fromCache) {
                                "Showing cached AI receipt suggestions."
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
                    _state.update {
                        it.copy(
                            receiptAssistState = AiLoadState.Error(result.reason),
                            receiptAssistDiagnostics = null,
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

    fun applyReceiptAssistMerchant() {
        applySuggestedValue(_state.value.receiptAssistState) { state, suggestion ->
            suggestion.merchant?.value?.takeIf { it.isNotBlank() }?.let { merchant ->
                state.copy(editMerchant = merchant, receiptAssistMessage = "Applied AI merchant suggestion.")
            } ?: state
        }
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
    }

    fun applyReceiptAssistDate() {
        applySuggestedValue(_state.value.receiptAssistState) { state, suggestion ->
            suggestion.date?.value?.let { date ->
                state.copy(editDate = date, receiptAssistMessage = "Applied AI date suggestion.")
            } ?: state
        }
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
    }

    fun clearReceiptAssistMessage() {
        _state.update { it.copy(receiptAssistMessage = null) }
    }

    private fun applySuggestedValue(
        state: AiLoadState<ReceiptAssistSuggestion>,
        updater: (ReceiptScanState, ReceiptAssistSuggestion) -> ReceiptScanState
    ) {
        val readyState = state as? AiLoadState.Ready ?: return
        _state.update { current -> updater(current, readyState.value) }
    }

    fun saveExpense() {
        val currentState = _state.value

        // Validate
        val merchant = currentState.editMerchant.trim()
        if (merchant.isBlank()) {
            _state.update {
                it.copy(errorMessage = "Merchant name is required")
            }
            return
        }

        val amount = AmountUtils.parseAmount(currentState.editAmount)
        if (amount == null || amount <= 0) {
            _state.update {
                it.copy(errorMessage = "Enter a valid amount")
            }
            return
        }

        val receiptId = currentState.receiptId ?: return

        _state.update { it.copy(isSaving = true, errorMessage = null) }

        viewModelScope.launch {
            try {
                val result = receiptRepository.createExpenseFromReceipt(
                    receiptId = receiptId,
                    merchant = merchant,
                    amount = amount,
                    currency = "EUR",
                    categoryId = currentState.selectedCategoryId,
                    date = currentState.editDate,
                    paymentMethod = currentState.paymentMethod,
                    notes = currentState.notes.takeIf { it.isNotBlank() }
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

    fun retry() {
        _state.update {
            ReceiptScanState(editDate = timeProvider.now())
        }
    }

    fun reset() {
        _state.update { ReceiptScanState(editDate = timeProvider.now()) }
    }
}
