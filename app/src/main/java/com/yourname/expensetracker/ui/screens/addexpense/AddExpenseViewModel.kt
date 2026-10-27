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
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.model.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import kotlinx.coroutines.isActive
import com.yourname.expensetracker.domain.util.AmountUtils
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository

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
 val mutation: com.yourname.expensetracker.ui.model.MutationState = com.yourname.expensetracker.ui.model.MutationState.idle(),
 val merchantError: UiText? = null,
 val amountError: UiText? = null,
 val transferDirection: TransferDirection? = null,
 val transferAccountName: String = "",
 val isNotMine: Boolean = false,
 val ownerName: String = "",
 val isSharedExpense: Boolean = false,
 val sharedWithName: String = "",
 val mySharePercentage: String = "",
 val myShareAmount: String = "",
 /**
  * S5-001/S5-002: Typed currency state — never defaults to "EUR" sentinel.
  * null = still loading; non-null = loaded (including real "EUR" users).
  */
 val homeCurrency: String? = null
)

sealed class SaveResult {
    object Success : SaveResult()
    object Duplicate : SaveResult()
    data class Error(val message: UiText) : SaveResult()
}

@HiltViewModel
class AddExpenseViewModel @Inject constructor(
    private val manualExpenseRepository: ManualExpenseRepository,
    private val expenseRepository: com.yourname.expensetracker.data.repository.ExpenseRepository,
 private val categoryRepository: CategoryRepository,
 private val timeProvider: TimeProvider,
 private val currencySettingsRepository: CurrencySettingsRepository
) : ViewModel() {

    private companion object {
        // INV-13: bounded diagnostic codes. No throwable, query, merchant or message text is ever attached.
        const val LOG_MERCHANT_SUGGESTION_SEARCH_FAILED = "MERCHANT_SUGGESTION_SEARCH_FAILED"
        const val LOG_ADD_EXPENSE_SAVE_FAILED = "ADD_EXPENSE_SAVE_FAILED"
    }

    private val _state = MutableStateFlow(AddExpenseState(date = timeProvider.now()))
    val state: StateFlow<AddExpenseState> = _state.asStateFlow()

    val categories: StateFlow<List<Category>> = categoryRepository.allCategories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

 private var searchJob: Job? = null
 private var initialValuesApplied: Boolean = false

 private var homeCurrencyJob: Job? = null

 init {
 homeCurrencyJob = viewModelScope.launch {
 currencySettingsRepository.homeCurrency().collect { hc ->
 _state.update { it.copy(homeCurrency = hc) }
 }
 }
 }

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
                
                try {
                    val suggestions = expenseRepository.searchMerchants(sanitized)
                    if (!isActive) return@launch
                    _state.update {
                        it.copy(
                            suggestions = suggestions,
                            showSuggestions = suggestions.isNotEmpty()
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // S5-008: Silently clear suggestions on failure (non-critical)
                    // INV-13: bounded code only; no throwable, query or merchant text.
                    Timber.w(LOG_MERCHANT_SUGGESTION_SEARCH_FAILED)
                    if (isActive) {
                        _state.update { it.copy(suggestions = emptyList(), showSuggestions = false) }
                    }
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
                amount = if (it.amount.isBlank()) com.yourname.expensetracker.ui.util.AmountInputSanitizer.sanitize(String.format(java.util.Locale.US, "%.2f", suggestion.avgAmount)) else it.amount,
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
        val sanitized = com.yourname.expensetracker.ui.util.AmountInputSanitizer.sanitize(value)
        _state.update {
            it.copy(
                amount = sanitized,
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
        // S5-011: Clear transfer metadata when leaving TRANSFER type
        _state.update {
            if (type == TransactionType.TRANSFER) {
                it.copy(transactionType = type)
            } else {
                it.copy(transactionType = type, transferDirection = null, transferAccountName = "")
            }
        }
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
        val sanitized = com.yourname.expensetracker.ui.util.AmountInputSanitizer.sanitize(value)
        _state.update { it.copy(myShareAmount = sanitized) }
    }

    fun save() {
        val currentState = _state.value

        // S5-007: Guard against double-tap
        if (currentState.isSaving) return

        // S5-001: Block save until currency is loaded — null means still loading
        // Real EUR users (homeCurrency == "EUR") are allowed through once loaded
        val currency = currentState.homeCurrency
        if (currency == null) {
            _state.update { it.withSaveError(UiText.StringResource(R.string.add_expense_error_currency_loading)) }
            return
        }

        // Validate
        val merchantTrimmed = currentState.merchant.trim()
        if (merchantTrimmed.isBlank()) {
            _state.update { it.copy(merchantError = UiText.StringResource(R.string.add_expense_error_merchant_required)) }
            return
        }

        // R16: same strict locale-aware parser as AmountInputSanitizer.isValid / OwnershipValidator.
        // Ambiguous or invalid input keeps the existing invalid-amount error.
        val amount = com.yourname.expensetracker.ui.util.AmountInputSanitizer.parseOrNull(currentState.amount)
        if (amount == null || amount <= 0) {
            _state.update { it.copy(amountError = UiText.StringResource(R.string.add_expense_error_amount_invalid)) }
            return
        }

        if (amount > 1_000_000) { // Reasonable upper limit
            _state.update { it.copy(amountError = UiText.StringResource(R.string.add_expense_error_amount_too_large)) }
            return
        }

        // Reject future dates — allow up to end of today to accommodate timezone edge cases
        val endOfToday = run {
            TimePeriodUtils.getEndOfDay(timeProvider.now()) - 1
        }
        if (currentState.date > endOfToday) {
            _state.update { it.withSaveError(UiText.StringResource(R.string.add_expense_error_date_future)) }
            return
        }

        val transferAccountNameTrimmed = currentState.transferAccountName.trim()
        if (currentState.transactionType == TransactionType.TRANSFER) {
            if (currentState.transferDirection == null) {
                _state.update {
                    it.withSaveError(UiText.DynamicString("Transfer direction is required for transfer transactions"))
                }
                return
            }
            if (transferAccountNameTrimmed.isBlank()) {
                _state.update {
                    it.withSaveError(UiText.DynamicString("Transfer account name is required for transfer transactions"))
                }
                return
            }
        }

        val sharePercentageText = currentState.mySharePercentage.trim()
        val shareAmountText = currentState.myShareAmount.trim()
        val hasSharePercentage = sharePercentageText.isNotEmpty()
        val hasShareAmount = shareAmountText.isNotEmpty()

        // S5-011: Use shared OwnershipValidator
        val ownershipResult = com.yourname.expensetracker.ui.util.OwnershipValidator.validate(
            isNotMine = currentState.isNotMine,
            isSharedExpense = currentState.isSharedExpense,
            sharedWithName = currentState.sharedWithName,
            sharePercentageText = sharePercentageText,
            shareAmountText = shareAmountText
        )
        if (ownershipResult is com.yourname.expensetracker.ui.util.OwnershipValidator.ValidationResult.Invalid) {
            // OwnershipValidator messages are fixed user-safe English constants (localisation deferred).
            _state.update { it.withSaveError(UiText.DynamicString(ownershipResult.message)) }
            return
        }

        val sharePercentage: Int?
        val shareAmount: Double?
        if (currentState.isSharedExpense) {
            if (hasSharePercentage) {
                val parsedSharePercentage = sharePercentageText.toIntOrNull()!!
                sharePercentage = parsedSharePercentage
                shareAmount = null
            } else {
                // OwnershipValidator already accepted this text via the same parser.
                val parsedShareAmount = com.yourname.expensetracker.ui.util.AmountInputSanitizer.parseOrNull(shareAmountText)!!
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

        _state.update { it.copy(isSaving = true, saveResult = null, mutation = com.yourname.expensetracker.ui.model.MutationState.running("save")) }

        viewModelScope.launch {
            try {
                // 1. Save the actual transaction
                val result = manualExpenseRepository.addManualExpense(
                    merchant = merchantTrimmed,
                    amount = normalizedAmount,
                    // S5-004: Use currency captured at save-tap, not live _state.value
                    currency = currency,
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
                            it.copy(isSaving = false, saveResult = SaveResult.Success, mutation = com.yourname.expensetracker.ui.model.MutationState.success("save"))
                        }
                    }
                    is Result.Duplicate -> {
                        _state.update {
                            it.copy(isSaving = false, saveResult = SaveResult.Duplicate, mutation = com.yourname.expensetracker.ui.model.MutationState.error("save", UiText.StringResource(R.string.error_duplicate_transaction)))
                        }
                    }
                    is Result.Error -> {
                        // S5-003: Update mutation so Save button re-enables.
                        // INV-15: repository message/exception are never displayed.
                        _state.update { it.withSaveError(mapSaveError(result)) }
                    }
                    Result.Loading -> {
                        // S5-003: Result.Loading is not a valid terminal state for one-shot save
                        _state.update { it.withSaveError(saveFailedText()) }
                    }
                }

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // S5-003: Update mutation so Save button re-enables after exception.
                // INV-13/INV-15: bounded code only; no throwable or message text.
                Timber.e(LOG_ADD_EXPENSE_SAVE_FAILED)
                _state.update { it.withSaveError(saveFailedText()) }
            } finally {
                // INV-15: isSaving never stays true, including on cancellation.
                _state.update {
                    if (it.isSaving) {
                        it.copy(isSaving = false, mutation = com.yourname.expensetracker.ui.model.MutationState.idle())
                    } else {
                        it
                    }
                }
            }
        }
    }

    /**
     * Maps a repository error to a bounded, localised message. The repository message and
     * exception are never displayed (INV-15). Add bounded repository error codes as `when`
     * branches; the default is the generic save-failed resource. Non-suspend by design.
     */
    private fun mapSaveError(result: Result.Error): UiText = when (result.message) {
        else -> saveFailedText()
    }

    private fun saveFailedText(): UiText = UiText.StringResource(R.string.add_expense_error_save_failed)

    private fun AddExpenseState.withSaveError(message: UiText): AddExpenseState = copy(
        isSaving = false,
        saveResult = SaveResult.Error(message),
        mutation = com.yourname.expensetracker.ui.model.MutationState.error("save", message)
    )


    fun reset() {
        searchJob?.cancel()
        searchJob = null
        initialValuesApplied = false
        // S5-002: Preserve loaded currency — do not reset to null sentinel
        val loadedCurrency = _state.value.homeCurrency
        _state.value = AddExpenseState(date = timeProvider.now(), homeCurrency = loadedCurrency)
    }

    fun setInitialValuesIfBlank(amount: String? = null, merchant: String? = null) {
        if (initialValuesApplied) return

        _state.update { current ->
            val amountIsBlank = current.amount.isBlank()
            val merchantIsBlank = current.merchant.isBlank()

            if (!amountIsBlank || !merchantIsBlank) {
                // S5-021R: Mark consumed even when skipped due to dirty form
                // Prevents prefill applying later if user clears fields
                initialValuesApplied = true
                return@update current
            }

            initialValuesApplied = true
            current.copy(
                // S5-020: Sanitize prefilled amount — same path as manual input
                // R16: clipboard text is machine-style (ClipboardAmountParser accepts "1,234" as 1234),
                // so convert to a number first and format locale-independently, as selectSuggestion does.
                amount = amount?.let { raw ->
                    AmountUtils.parseAmount(raw)?.let { parsed ->
                        com.yourname.expensetracker.ui.util.AmountInputSanitizer.sanitize(
                            String.format(java.util.Locale.US, "%.2f", parsed)
                        )
                    }
                } ?: current.amount,
                merchant = merchant?.take(100)?.trim() ?: current.merchant
            )
        }
    }

    fun clearSaveResult() {
        _state.update { it.copy(saveResult = null) }
    }
}
