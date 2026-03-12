package com.yourname.expensetracker.ui.screens.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.database.model.PendingReviewWithReceipt
import com.yourname.expensetracker.data.repository.NotificationRepository
import com.yourname.expensetracker.data.repository.ReviewQueueRepository
import com.yourname.expensetracker.domain.model.Result
import timber.log.Timber
// ...
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import javax.inject.Inject

@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val repository: NotificationRepository,
    private val reviewQueueRepository: ReviewQueueRepository,
    private val categoryRepository: CategoryRepository,
    private val receiptRepository: com.yourname.expensetracker.data.repository.ReceiptRepository,
    private val expenseRepository: com.yourname.expensetracker.data.repository.ExpenseRepository,
    private val debugDataStorage: com.yourname.expensetracker.ui.screens.debug.DebugDataStorage,
    val geocodingService: com.yourname.expensetracker.domain.location.GeocodingService
) : ViewModel() {
    
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()

    private val _batchProgress = MutableStateFlow<Pair<Int, Int>?>(null) // current, total
    val batchProgress = _batchProgress.asStateFlow()

    private val _isBatchProcessing = MutableStateFlow(false)
    val isBatchProcessing = _isBatchProcessing.asStateFlow()
    
    private val _debugData = MutableStateFlow<com.yourname.expensetracker.ui.screens.debug.DebugData?>(null)
    val debugData = _debugData.asStateFlow()

    init {
        // Load saved debug data on startup
        viewModelScope.launch {
            _debugData.value = debugDataStorage.load()
        }
    }

    private var batchJob: Job? = null

    val pendingReviews: StateFlow<List<PendingReviewWithReceipt>> = reviewQueueRepository
        .getPendingReviews()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pendingCount: StateFlow<Int> = reviewQueueRepository
        .getPendingReviewCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val categories: StateFlow<List<Category>> = categoryRepository.allCategories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun approveReview(reviewId: Long) {
        viewModelScope.launch {
            val result = reviewQueueRepository.approveReview(reviewId)
            handleResult(result, "Failed to approve")
        }
    }

    private fun handleResult(result: Result<Long>, prefix: String) {
        when (result) {
            is Result.Success -> { /* Handled by UI observing DB change */ }
            is Result.Duplicate -> _errorMessage.value = "Duplicate transaction detected"
            is Result.Error -> _errorMessage.value = "$prefix: ${result.message}"
            Result.Loading -> { /* No-op or show loading */ }
        }
    }


    fun rejectReview(reviewId: Long) {
        viewModelScope.launch {
            reviewQueueRepository.rejectReview(reviewId)
        }
    }

    fun approveReviewWithEdits(
        reviewId: Long,
        finalAmount: Double?,
        finalMerchant: String?,
        finalCategoryId: Long?,
        finalType: TransactionType?,
        applyToAll: Boolean = false,
        approveAllPending: Boolean = false,
        finalLatitude: Double? = null,
        finalLongitude: Double? = null,
        finalAddress: String? = null
    ) {
        viewModelScope.launch {
            val result = reviewQueueRepository.approveReview(
                reviewId = reviewId,
                finalAmount = finalAmount,
                finalMerchant = finalMerchant,
                finalCategoryId = finalCategoryId,
                finalType = finalType,
                finalLatitude = finalLatitude,
                finalLongitude = finalLongitude,
                finalAddress = finalAddress
            )
            handleResult(result, "Failed to approve edits")

            if (applyToAll && (finalCategoryId != null || finalMerchant != null)) {
                try {
                    val review = reviewQueueRepository.getReviewById(reviewId)
                    val originalMerchant = review?.suggestedMerchant
                    val merchantName = finalMerchant ?: originalMerchant
                    val categoryId = finalCategoryId
                    
                    if (merchantName != null && categoryId != null) {
                        expenseRepository.updateExpenseCategoryBulk(merchantName, categoryId)
                        // Propagation to other pending reviews
                        reviewQueueRepository.updatePendingReviewCategoryBulk(merchantName, categoryId)
                    }

                    // Also handle bulk renaming if a new merchant name was provided
                    if (finalMerchant != null && originalMerchant != null && finalMerchant != originalMerchant) {
                        expenseRepository.updateExpenseMerchantBulk(originalMerchant, finalMerchant)
                        reviewQueueRepository.updatePendingReviewMerchantBulk(originalMerchant, finalMerchant)
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to apply bulk category update")
                }
            }

            if (approveAllPending) {
                try {
                    val review = reviewQueueRepository.getReviewById(reviewId)
                    val originalMerchant = review?.suggestedMerchant
                    val searchMerchant = finalMerchant ?: originalMerchant
                    if (searchMerchant != null) {
                        val identicalPending = reviewQueueRepository.getPendingReviewsByMerchant(searchMerchant)
                        for (pending in identicalPending) {
                            if (pending.id != reviewId) {
                                reviewQueueRepository.approveReview(
                                    reviewId = pending.id,
                                    finalAmount = null, // Keep original amounts for identical transactions
                                    finalMerchant = finalMerchant,
                                    finalCategoryId = finalCategoryId,
                                    finalType = finalType,
                                    finalLatitude = finalLatitude,
                                    finalLongitude = finalLongitude,
                                    finalAddress = finalAddress
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to apply bulk approval")
                }
            }
        }
    }


    fun clearError() {
        _errorMessage.value = null
    }

    fun approveAll() {
        viewModelScope.launch {
            try {
                reviewQueueRepository.approveAllReview()
            } catch (e: Exception) {
                _errorMessage.value = "Failed to approve all: ${e.message}"
            }
        }
    }

    fun rejectAll() {
        viewModelScope.launch {
            try {
                reviewQueueRepository.rejectAllReviews()
                _errorMessage.value = "All pending reviews cleared."
            } catch (e: Exception) {
                _errorMessage.value = "Failed to clear all: ${e.message}"
            }
        }
    }

    fun processBatch(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        batchJob?.cancel() // Cancel previous if any
        batchJob = viewModelScope.launch {
            try {
                _isBatchProcessing.value = true
                _batchProgress.value = Pair(0, uris.size)
                
                val result = receiptRepository.processBatch(uris) { current, total ->
                    _batchProgress.value = Pair(current, total)
                }
                
                if (result.failureCount > 0) {
                    val firstError = result.errors.firstOrNull()?.let { 
                        if (it.length > 60) it.take(57) + "..." else it 
                    }
                    _errorMessage.value = "Processed ${result.successCount} ok. ${result.failureCount} failed: $firstError"
                } else {
                    _errorMessage.value = "Successfully processed all ${result.successCount} receipts!"
                }
            } catch (e: Exception) {
                _errorMessage.value = "Batch failed: ${e.message}"
            } finally {
                _isBatchProcessing.value = false
                _batchProgress.value = null
            }
        }
    }

    fun cancelBatchProcessing() {
        batchJob?.cancel()
        _isBatchProcessing.value = false
        _batchProgress.value = null
        _errorMessage.value = "Batch processing cancelled."
    }

    fun processStatement(uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                _isBatchProcessing.value = true // Reuse batch loading state
                _batchProgress.value = Pair(0, 1)
                
                val result = receiptRepository.processStatement(uri)
                
                // Store debug data and persist to file
                result.debugData?.let { data ->
                    _debugData.value = data
                    debugDataStorage.save(data)
                }
                
                if (result.failureCount > 0) {
                    _errorMessage.value = "Failed to parse screenshot: ${result.errors.firstOrNull()}"
                } else {
                    _errorMessage.value = "Imported ${result.successCount} transactions from screenshot!"
                }
            } catch (e: Exception) {
                _errorMessage.value = "Import failed: ${e.message}"
            } finally {
                _isBatchProcessing.value = false
                _batchProgress.value = null
            }
        }
    }

    suspend fun getDebugExportData(): String {
        return receiptRepository.exportParserDebugData()
    }

    suspend fun getReceiptDebugInfo(receiptId: Long): String {
        return receiptRepository.debugReceipt(receiptId)
    }

    fun clearScannedData() {
        viewModelScope.launch {
            receiptRepository.clearAllScannedReceipts()
            _errorMessage.value = "All scanned debug data cleared."
        }
    }
    
    fun clearDebugData() {
        viewModelScope.launch {
            debugDataStorage.clear()
        }
        _debugData.value = null
        _errorMessage.value = "Debug data cleared."
    }
}
