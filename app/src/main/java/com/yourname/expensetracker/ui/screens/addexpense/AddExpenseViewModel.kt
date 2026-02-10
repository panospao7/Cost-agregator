package com.yourname.expensetracker.ui.screens.addexpense

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.dao.MerchantSuggestion
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.NotificationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AddExpenseState(
    val merchant: String = "",
    val amount: String = "",
    val selectedCategoryId: Long? = null,
    val paymentMethod: PaymentMethod = PaymentMethod.CASH,
    val transactionType: TransactionType = TransactionType.PURCHASE,
    val date: Long = System.currentTimeMillis(),
    val notes: String = "",
    val showNotes: Boolean = false,
    val showTransactionType: Boolean = false,
    val suggestions: List<MerchantSuggestion> = emptyList(),
    val showSuggestions: Boolean = false,
    val isSaving: Boolean = false,
    val saveResult: SaveResult? = null,
    val merchantError: String? = null,
    val amountError: String? = null
)

sealed class SaveResult {
    object Success : SaveResult()
    object Duplicate : SaveResult()
    data class Error(val message: String) : SaveResult()
}

@HiltViewModel
class AddExpenseViewModel @Inject constructor(
    private val repository: NotificationRepository,
    private val categoryRepository: CategoryRepository
) : ViewModel() {

    private val _state = MutableStateFlow(AddExpenseState())
    val state: StateFlow<AddExpenseState> = _state.asStateFlow()

    val categories: StateFlow<List<Category>> = categoryRepository.allCategories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var searchJob: Job? = null

    fun updateMerchant(value: String) {
        _state.update {
            it.copy(
                merchant = value,
                merchantError = null,
                saveResult = null
            )
        }

        // Debounced search
        searchJob?.cancel()
        if (value.length >= 2) {
            searchJob = viewModelScope.launch {
                delay(300)
                val suggestions = repository.searchMerchants(value)
                _state.update {
                    it.copy(
                        suggestions = suggestions,
                        showSuggestions = suggestions.isNotEmpty()
                    )
                }
            }
        } else {
            _state.update { it.copy(suggestions = emptyList(), showSuggestions = false) }
        }
    }

    fun selectSuggestion(suggestion: MerchantSuggestion) {
        _state.update {
            it.copy(
                merchant = suggestion.merchant,
                selectedCategoryId = suggestion.categoryId ?: it.selectedCategoryId,
                amount = if (it.amount.isBlank()) String.format("%.2f", suggestion.avgAmount) else it.amount,
                suggestions = emptyList(),
                showSuggestions = false,
                merchantError = null
            )
        }
    }

    fun dismissSuggestions() {
        _state.update { it.copy(showSuggestions = false) }
    }

    fun updateAmount(value: String) {
        // Only allow valid decimal input
        val filtered = value.filter { it.isDigit() || it == '.' || it == ',' }
        _state.update {
            it.copy(
                amount = filtered,
                amountError = null,
                saveResult = null
            )
        }
    }

    fun selectCategory(categoryId: Long) {
        _state.update { it.copy(selectedCategoryId = categoryId) }
    }

    fun selectPaymentMethod(method: PaymentMethod) {
        _state.update { it.copy(paymentMethod = method) }
    }

    fun selectTransactionType(type: TransactionType) {
        _state.update { it.copy(transactionType = type) }
    }

    fun updateDate(dateMs: Long) {
        _state.update { it.copy(date = dateMs) }
    }

    fun updateNotes(value: String) {
        _state.update { it.copy(notes = value) }
    }

    fun toggleNotes() {
        _state.update { it.copy(showNotes = !it.showNotes) }
    }

    fun toggleTransactionType() {
        _state.update { it.copy(showTransactionType = !it.showTransactionType) }
    }

    fun save() {
        val currentState = _state.value

        // Validate
        val merchantTrimmed = currentState.merchant.trim()
        if (merchantTrimmed.isBlank()) {
            _state.update { it.copy(merchantError = "Merchant name is required") }
            return
        }

        val amountStr = currentState.amount.replace(",", ".")
        val amount = amountStr.toDoubleOrNull()
        if (amount == null || amount <= 0) {
            _state.update { it.copy(amountError = "Enter a valid amount") }
            return
        }

        if (amount > 50000) {
            _state.update { it.copy(amountError = "Amount seems too large") }
            return
        }

        _state.update { it.copy(isSaving = true, saveResult = null) }

        viewModelScope.launch {
            try {
                val result = repository.addManualExpense(
                    merchant = merchantTrimmed,
                    amount = amount,
                    currency = "EUR",
                    categoryId = currentState.selectedCategoryId,
                    transactionType = currentState.transactionType,
                    paymentMethod = currentState.paymentMethod,
                    date = currentState.date,
                    notes = currentState.notes.takeIf { it.isNotBlank() }
                )

                if (result == -1L) {
                    _state.update {
                        it.copy(isSaving = false, saveResult = SaveResult.Duplicate)
                    }
                } else {
                    _state.update {
                        it.copy(isSaving = false, saveResult = SaveResult.Success)
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isSaving = false,
                        saveResult = SaveResult.Error(e.message ?: "Unknown error")
                    )
                }
            }
        }
    }

    fun reset() {
        _state.value = AddExpenseState()
    }

    fun setInitialValues(amount: String? = null, merchant: String? = null) {
        _state.update { 
            it.copy(
                amount = amount ?: it.amount,
                merchant = merchant ?: it.merchant
            )
        }
    }

    fun clearSaveResult() {
        _state.update { it.copy(saveResult = null) }
    }
}
