package com.yourname.expensetracker.ui.screens.addexpense

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.dao.MerchantSuggestion
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.TransferDirection
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ManualExpenseRepository
import com.yourname.expensetracker.domain.model.Result
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
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
import kotlinx.coroutines.isActive
import com.yourname.expensetracker.domain.util.AmountUtils
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider

data class AddExpenseState(
    val merchant: String = "",
    val amount: String = "",
    val selectedCategoryId: Long? = null,
    val paymentMethod: PaymentMethod = PaymentMethod.CASH,
    val transactionType: TransactionType = TransactionType.PURCHASE,
    val date: Long = 0L,
    val notes: String = "",
    val showNotes: Boolean = false,
    val showTransactionType: Boolean = false,
    val isRecurring: Boolean = false,
    val recurrenceFrequency: RecurrenceFrequency = RecurrenceFrequency.MONTHLY,
    val suggestions: List<MerchantSuggestion> = emptyList(),
    val showSuggestions: Boolean = false,
    val isSaving: Boolean = false,
    val saveResult: SaveResult? = null,
    val merchantError: String? = null,
    val amountError: String? = null,
    val transferDirection: TransferDirection? = null,
    val transferAccountName: String = "",
    val isNotMine: Boolean = false,
    val ownerName: String = "",
    val isSharedExpense: Boolean = false,
    val sharedWithName: String = "",
    val mySharePercentage: String = "",
    val myShareAmount: String = ""
)

sealed class SaveResult {
    object Success : SaveResult()
    object Duplicate : SaveResult()
    data class Error(val message: String) : SaveResult()
}

@HiltViewModel
class AddExpenseViewModel @Inject constructor(
    private val manualExpenseRepository: ManualExpenseRepository,
    private val expenseRepository: com.yourname.expensetracker.data.repository.ExpenseRepository,
    private val categoryRepository: CategoryRepository,
    private val timeProvider: TimeProvider
) : ViewModel() {

    private val _state = MutableStateFlow(AddExpenseState(date = timeProvider.now()))
    val state: StateFlow<AddExpenseState> = _state.asStateFlow()

    val categories: StateFlow<List<Category>> = categoryRepository.allCategories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var searchJob: Job? = null
    private var initialValuesApplied: Boolean = false

    fun updateMerchant(value: String) {
        val sanitized = value.take(100) // Max 100 chars
        _state.update {
            it.copy(
                merchant = sanitized,
                merchantError = null,
                saveResult = null
            )
        }

        // Debounced search
        searchJob?.cancel()
        if (sanitized.length >= 2) {
            searchJob = viewModelScope.launch {
                delay(300)
                if (!isActive) return@launch
                
                val suggestions = expenseRepository.searchMerchants(sanitized)
                
                if (!isActive) return@launch
                
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
        _state.update { it.copy(notes = value.take(500)) } // Max 500 chars
    }

    fun toggleNotes() {
        _state.update { it.copy(showNotes = !it.showNotes) }
    }

    fun toggleTransactionType() {
        _state.update { it.copy(showTransactionType = !it.showTransactionType) }
    }
    
    fun toggleRecurring() {
        _state.update { it.copy(isRecurring = !it.isRecurring) }
    }
    
    fun setRecurrenceFrequency(frequency: RecurrenceFrequency) {
        _state.update { it.copy(recurrenceFrequency = frequency) }
    }

    fun setTransferDirection(direction: TransferDirection?) {
        _state.update { it.copy(transferDirection = direction) }
    }

    fun updateTransferAccountName(name: String) {
        _state.update { it.copy(transferAccountName = name.take(100)) }
    }

    fun setIsNotMine(value: Boolean) {
        _state.update {
            if (value) {
                it.copy(
                    isNotMine = true,
                    isSharedExpense = false,
                    sharedWithName = "",
                    mySharePercentage = "",
                    myShareAmount = ""
                )
            } else {
                it.copy(isNotMine = false)
            }
        }
    }

    fun updateOwnerName(name: String) {
        _state.update { it.copy(ownerName = name.take(100)) }
    }

    fun setIsSharedExpense(value: Boolean) {
        _state.update {
            if (value) {
                it.copy(
                    isSharedExpense = true,
                    isNotMine = false,
                    ownerName = ""
                )
            } else {
                it.copy(
                    isSharedExpense = false,
                    sharedWithName = "",
                    mySharePercentage = "",
                    myShareAmount = ""
                )
            }
        }
    }

    fun updateSharedWithName(name: String) {
        _state.update { it.copy(sharedWithName = name.take(100)) }
    }

    fun updateMySharePercentage(value: String) {
        _state.update { it.copy(mySharePercentage = value.filter { it.isDigit() }.take(3)) }
    }

    fun updateMyShareAmount(value: String) {
        val filtered = value.filter { it.isDigit() || it == '.' || it == ',' }
        _state.update { it.copy(myShareAmount = filtered.take(10)) }
    }

    fun save() {
        val currentState = _state.value

        // Validate
        val merchantTrimmed = currentState.merchant.trim()
        if (merchantTrimmed.isBlank()) {
            _state.update { it.copy(merchantError = "Merchant name is required") }
            return
        }

        val amount = AmountUtils.parseAmount(currentState.amount)
        if (amount == null || amount <= 0) {
            _state.update { it.copy(amountError = "Enter a valid amount") }
            return
        }

        if (amount > 1_000_000) { // Reasonable upper limit
            _state.update { it.copy(amountError = "Amount is too large") }
            return
        }

        // Reject future dates — allow up to end of today to accommodate timezone edge cases
        val endOfToday = run {
            TimePeriodUtils.getEndOfDay(timeProvider.now()) - 1
        }
        if (currentState.date > endOfToday) {
            _state.update { it.copy(saveResult = SaveResult.Error("Date cannot be in the future")) }
            return
        }

        val transferAccountNameTrimmed = currentState.transferAccountName.trim()
        if (currentState.transactionType == TransactionType.TRANSFER) {
            if (currentState.transferDirection == null) {
                _state.update {
                    it.copy(saveResult = SaveResult.Error("Transfer direction is required for transfer transactions"))
                }
                return
            }
            if (transferAccountNameTrimmed.isBlank()) {
                _state.update {
                    it.copy(saveResult = SaveResult.Error("Transfer account name is required for transfer transactions"))
                }
                return
            }
        }

        val sharePercentageText = currentState.mySharePercentage.trim()
        val shareAmountText = currentState.myShareAmount.trim()
        val hasSharePercentage = sharePercentageText.isNotEmpty()
        val hasShareAmount = shareAmountText.isNotEmpty()

        if (currentState.isNotMine && currentState.isSharedExpense) {
            _state.update {
                it.copy(saveResult = SaveResult.Error("Expense cannot be both not-mine and shared"))
            }
            return
        }

        val sharePercentage: Int?
        val shareAmount: Double?
        if (currentState.isSharedExpense) {
            if (currentState.sharedWithName.trim().isBlank()) {
                _state.update {
                    it.copy(saveResult = SaveResult.Error("Shared with name is required for shared expenses"))
                }
                return
            }

            if (hasSharePercentage == hasShareAmount) {
                _state.update {
                    it.copy(saveResult = SaveResult.Error("Set either share % or share amount for shared expenses"))
                }
                return
            }

            if (hasSharePercentage) {
                val parsedSharePercentage = sharePercentageText.toIntOrNull()
                if (parsedSharePercentage == null || parsedSharePercentage !in 0..100) {
                    _state.update {
                        it.copy(saveResult = SaveResult.Error("Share percentage must be between 0 and 100"))
                    }
                    return
                }
                sharePercentage = parsedSharePercentage
                shareAmount = null
            } else {
                val parsedShareAmount = AmountUtils.parseAmount(shareAmountText)
                if (parsedShareAmount == null || parsedShareAmount <= 0) {
                    _state.update {
                        it.copy(saveResult = SaveResult.Error("Share amount must be greater than 0"))
                    }
                    return
                }
                sharePercentage = null
                shareAmount = parsedShareAmount
            }
        } else {
            sharePercentage = null
            shareAmount = null
        }

        // Normalize to 2 decimal places
        val normalizedAmount = java.math.BigDecimal(amount)
            .setScale(2, java.math.RoundingMode.HALF_UP)
            .toDouble()

        _state.update { it.copy(isSaving = true, saveResult = null) }

        viewModelScope.launch {
            try {
                // 1. Save the actual transaction
                val result = manualExpenseRepository.addManualExpense(
                    merchant = merchantTrimmed,
                    amount = normalizedAmount,
                    currency = "EUR",
                    categoryId = currentState.selectedCategoryId,
                    transactionType = currentState.transactionType,
                    paymentMethod = currentState.paymentMethod,
                    date = currentState.date,
                    notes = currentState.notes.takeIf { it.isNotBlank() },
                    transferDirection = currentState.transferDirection.takeIf {
                        currentState.transactionType == TransactionType.TRANSFER
                    },
                    transferAccountName = transferAccountNameTrimmed.takeIf {
                        currentState.transactionType == TransactionType.TRANSFER && it.isNotBlank()
                    },
                    isNotMine = currentState.isNotMine,
                    ownerName = currentState.ownerName.takeIf { it.isNotBlank() },
                    isSharedExpense = currentState.isSharedExpense,
                    sharedWithName = currentState.sharedWithName.trim().takeIf {
                        currentState.isSharedExpense && it.isNotBlank()
                    },
                    mySharePercentage = sharePercentage,
                    myShareAmount = shareAmount,
                    recurrenceFrequency = currentState.recurrenceFrequency.takeIf { currentState.isRecurring }
                )

                when (result) {
                    is Result.Success -> {
                        _state.update {
                            it.copy(isSaving = false, saveResult = SaveResult.Success)
                        }
                    }
                    is Result.Duplicate -> {
                        _state.update {
                            it.copy(isSaving = false, saveResult = SaveResult.Duplicate)
                        }
                    }
                    is Result.Error -> {
                        _state.update {
                            it.copy(
                                isSaving = false,
                                saveResult = SaveResult.Error(result.message ?: "Failed to save expense")
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
                        saveResult = SaveResult.Error(e.message ?: "Unknown error")
                    )
                }
            }
        }
    }


    fun reset() {
        searchJob?.cancel()
        searchJob = null
        initialValuesApplied = false
        _state.value = AddExpenseState(date = timeProvider.now())
    }

    fun setInitialValuesIfBlank(amount: String? = null, merchant: String? = null) {
        if (initialValuesApplied) return

        _state.update { current ->
            val amountIsBlank = current.amount.isBlank()
            val merchantIsBlank = current.merchant.isBlank()

            if (!amountIsBlank || !merchantIsBlank) {
                return@update current
            }

            initialValuesApplied = true
            current.copy(
                amount = amount ?: current.amount,
                merchant = merchant ?: current.merchant
            )
        }
    }

    fun clearSaveResult() {
        _state.update { it.copy(saveResult = null) }
    }
}
