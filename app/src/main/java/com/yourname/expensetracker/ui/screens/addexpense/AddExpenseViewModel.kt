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
import kotlinx.coroutines.ensureActive
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
import com.yourname.expensetracker.domain.quickadd.CategoryRef
import com.yourname.expensetracker.domain.quickadd.CategoryMatch
import com.yourname.expensetracker.domain.quickadd.FieldParse
import com.yourname.expensetracker.domain.quickadd.FlagReason
import com.yourname.expensetracker.domain.quickadd.LearnedCategoryLookupResult
import com.yourname.expensetracker.domain.quickadd.LineHint
import com.yourname.expensetracker.domain.quickadd.QuickAddInput
import com.yourname.expensetracker.domain.quickadd.QuickAddLearnedCategoryLookup
import com.yourname.expensetracker.domain.quickadd.QuickAddParser

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
 val homeCurrency: String? = null,
 /**
  * nl-quick-add fill bookkeeping (spec 2.9, G2-11, G2-13). Enums only: no String, so the typed
  * line (INV-12) and any raw text can never be held here. All cleared by [AddExpenseViewModel.reset].
  */
 val fieldOrigins: Map<QuickAddField, FieldOrigin> = QuickAddFillContract.DEFAULT_ORIGINS,
 val fieldFlags: Map<QuickAddField, FlagReason> = emptyMap(),
 val lineHints: Set<LineHint> = emptySet(),
 /** G2-13: describes the last Fill and survives later edits; only [AddExpenseViewModel.reset] clears it. */
 val lastFillOutcome: OutcomeCode? = null,
 /** G2-11: why the current category was set by Fill; null once the user edits or removes the category. */
 val categoryFillSource: CategoryFillSource? = null,
 /**
  * Item 71a / G2-13: what the last Fill reported (outcome, hints, filled and flagged field sets, kept
  * edits). Computed once in the single Fill apply, so it survives later edits and rotation. Enums, sets
  * and a Boolean only; cleared by [AddExpenseViewModel.reset].
  */
 val fillSummary: QuickAddSummarySnapshot? = null
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
 private val currencySettingsRepository: CurrencySettingsRepository,
 private val quickAddParser: QuickAddParser,
 private val quickAddLearnedCategoryLookup: QuickAddLearnedCategoryLookup
) : ViewModel() {

    private companion object {
        // INV-13: bounded diagnostic codes. No throwable, query, merchant or message text is ever attached.
        const val LOG_MERCHANT_SUGGESTION_SEARCH_FAILED = "MERCHANT_SUGGESTION_SEARCH_FAILED"
        const val LOG_ADD_EXPENSE_SAVE_FAILED = "ADD_EXPENSE_SAVE_FAILED"
        const val LOG_QUICK_ADD_CATEGORY_LOOKUP_FAILED = "QUICK_ADD_CATEGORY_LOOKUP_FAILED"

        // Spec 7.2: exactly one outcome constant per applied Fill.
        const val LOG_FILL_FULL = "FILL_FULL"
        const val LOG_FILL_PARTIAL = "FILL_PARTIAL"
        const val LOG_FILL_NONE = "FILL_NONE"
        const val LOG_FOREIGN_CURRENCY_BLOCKED = "FOREIGN_CURRENCY_BLOCKED"
    }

    private val _state = MutableStateFlow(AddExpenseState(date = timeProvider.now()))
    val state: StateFlow<AddExpenseState> = _state.asStateFlow()

    val categories: StateFlow<List<Category>> = categoryRepository.allCategories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

 private var searchJob: Job? = null

 // INV-18: latest-wins. Holds the Job only; the typed line is never stored (INV-12).
 private var fillJob: Job? = null
 private var initialValuesApplied: Boolean = false

 private var homeCurrencyJob: Job? = null

 // MEC AE-34: the entry zone captured when the sheet opened. A plain field: never in state, never
 // persisted or logged (QuickAddPrivacy G-1). Cleared by reset().
 private var sessionZone: java.time.ZoneId? = null

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
            ).withUserEdit(QuickAddField.MERCHANT)
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
            // INV-9: a suggestion tap is a USER edit; it writes the amount only if the amount is
            // blank AND carries no flag (a flagged blank amount stays flagged and blank, AE-26).
            val writeAmount = it.amount.isBlank() && QuickAddField.AMOUNT !in it.fieldFlags
            var next = it.copy(
                merchant = suggestion.merchant,
                selectedCategoryId = suggestion.categoryId ?: it.selectedCategoryId,
                amount = if (writeAmount) com.yourname.expensetracker.ui.util.AmountInputSanitizer.sanitize(String.format(java.util.Locale.US, "%.2f", suggestion.avgAmount)) else it.amount,
                suggestions = emptyList(),
                showSuggestions = false,
                merchantError = null
            ).withUserEdit(QuickAddField.MERCHANT)
            if (suggestion.categoryId != null) {
                next = next.copy(categoryFillSource = null).withUserEdit(QuickAddField.CATEGORY)
            }
            if (writeAmount) {
                next = next.withUserEdit(QuickAddField.AMOUNT)
            }
            next
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
            ).withUserEdit(QuickAddField.AMOUNT)
        }
    }

    fun selectCategory(categoryId: Long) {
        _state.update {
            it.copy(selectedCategoryId = categoryId, categoryFillSource = null)
                .withUserEdit(QuickAddField.CATEGORY)
        }
    }

    /**
     * G2-11: removes the category (the chip's Remove). Counts as a USER edit, so a later Fill never
     * refills it. A null category at Save is still resolved by the existing classifier.
     */
    fun clearCategory() {
        _state.update {
            it.copy(selectedCategoryId = null, categoryFillSource = null)
                .withUserEdit(QuickAddField.CATEGORY)
        }
    }

    fun selectPaymentMethod(method: PaymentMethod) {
        _state.update { it.copy(paymentMethod = method).withUserEdit(QuickAddField.PAYMENT) }
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
        _state.update { it.copy(date = dateMs).withUserEdit(QuickAddField.DATE) }
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

        // MEC INV-12/13/14: per-currency precision and cap, owned by CurrencyAmountPolicy. The amount
        // is never rounded or truncated; a failing amount keeps its text and nothing is saved.
        val amountCheckError = checkAmountForCurrency(amount, currency)
        if (amountCheckError != null) {
            _state.update { it.copy(amountError = amountCheckError) }
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

        // MEC AE-34: the zone captured when the sheet opened; falls back to the device zone now.
        val entryZone = sessionZone ?: java.time.ZoneId.systemDefault()

        _state.update { it.copy(isSaving = true, saveResult = null, mutation = com.yourname.expensetracker.ui.model.MutationState.running("save")) }

        viewModelScope.launch {
            try {
                // 1. Save the actual transaction
                val result = manualExpenseRepository.addManualExpense(
                    merchant = merchantTrimmed,
                    amount = amount,
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
                    recurrenceFrequency = currentState.recurrenceFrequency.takeIf { currentState.isRecurring },
                    entryZone = entryZone
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
                        _state.update { it.withSaveError(mapSaveError(result, currency)) }
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
     * MEC INV-12/13/14: validates the parsed [amount] for [currency] with [CurrencyAmountPolicy]
     * (Double overload). Returns the inline error, or null when the amount may be saved. Never
     * rounds. An unparsable currency code skips the precision check and uses the default cap.
     */
    private fun checkAmountForCurrency(amount: Double, currency: String): UiText? {
        val code = com.yourname.expensetracker.domain.core.money.CurrencyCode.parse(currency)
        if (code == null) {
            return if (amount > com.yourname.expensetracker.domain.currency.CurrencyAmountPolicy.DEFAULT_CAP.toDouble()) {
                amountTooLargeText(com.yourname.expensetracker.domain.currency.CurrencyAmountPolicy.DEFAULT_CAP)
            } else {
                null
            }
        }
        return when (val check = com.yourname.expensetracker.domain.currency.CurrencyAmountPolicy.check(amount, code)) {
            com.yourname.expensetracker.domain.currency.AmountCheck.Ok -> null
            // Not reachable after the positive check; fail closed with the generic invalid-amount error.
            com.yourname.expensetracker.domain.currency.AmountCheck.NotPositive ->
                UiText.StringResource(R.string.add_expense_error_amount_invalid)
            // OD-6: digits unknown. Never assume 2 digits or "no limit".
            com.yourname.expensetracker.domain.currency.AmountCheck.DigitsUnavailable ->
                UiText.StringResource(R.string.add_expense_error_amount_invalid)
            is com.yourname.expensetracker.domain.currency.AmountCheck.PrecisionExceeded ->
                precisionText(code.code, check.allowedDigits)
            is com.yourname.expensetracker.domain.currency.AmountCheck.AboveCap ->
                amountTooLargeText(check.cap)
        }
    }

    private fun precisionText(currencyCode: String, allowedDigits: Int): UiText =
        if (allowedDigits == 0) {
            UiText.StringResource(R.string.mec_amount_no_decimals, listOf(currencyCode))
        } else {
            UiText.StringResource(R.string.mec_amount_max_decimals, listOf(currencyCode, allowedDigits))
        }

    private fun amountTooLargeText(cap: java.math.BigDecimal): UiText =
        UiText.StringResource(
            R.string.mec_amount_too_large,
            listOf(java.text.NumberFormat.getIntegerInstance().format(cap.toLong()))
        )

    /**
     * Maps a repository error to a bounded, localised message. The repository message and
     * exception are never displayed (INV-15). Only bounded repository error codes are matched, by
     * equality; the default is the generic save-failed resource. Non-suspend by design.
     * [currency] is the home currency captured at save-tap.
     */
    private fun mapSaveError(result: Result.Error, currency: String): UiText = when (result.message) {
        // S4 / INV-16: recurring rule refused for a currency other than home.
        ManualExpenseRepository.RECURRING_FOREIGN_CURRENCY_ERROR_CODE ->
            UiText.StringResource(R.string.mec_repeat_home_only, listOf(currency))
        // AE-14: the home currency could not be resolved at save; nothing was saved.
        com.yourname.expensetracker.domain.transaction.RateRefusalCodes.NOT_ELIGIBLE_HOME_UNRESOLVED ->
            UiText.StringResource(R.string.mec_block_home_unresolved)
        // AE-19: the coordinator refused the amount's precision; digits null falls back to save-failed.
        com.yourname.expensetracker.domain.transaction.RateRefusalCodes.AMOUNT_PRECISION_EXCEEDED -> {
            val digits = com.yourname.expensetracker.domain.core.money.CurrencyCode.parse(currency)
                ?.let { com.yourname.expensetracker.domain.currency.CurrencyAmountPolicy.fractionDigits(it) }
            if (digits == null) saveFailedText() else precisionText(currency, digits)
        }
        else -> saveFailedText()
    }

    private fun saveFailedText(): UiText = UiText.StringResource(R.string.add_expense_error_save_failed)

    private fun AddExpenseState.withSaveError(message: UiText): AddExpenseState = copy(
        isSaving = false,
        saveResult = SaveResult.Error(message),
        mutation = com.yourname.expensetracker.ui.model.MutationState.error("save", message)
    )


    /**
     * Quick-add Fill (C3, spec 5.2). The typed [line] exists only as this argument and inside the
     * launched job (INV-12); it is never stored in a field or in [AddExpenseState].
     *
     * Gate (INV-17): blank line, unknown home currency or a save in flight is a no-op. Otherwise the
     * one-shot initial-values channel is consumed (INV-8), the previous Fill job and the suggestion
     * search are cancelled and suggestions hidden (INV-9, INV-18), and a single job parses, runs the
     * read-only learned-category lookup only when the contract asks for it, and applies the result in
     * one atomic [MutableStateFlow.update] that re-evaluates fillability at apply time.
     * Not a suspend function by design (S0 D-3).
     */
    fun fill(line: String) {
        val snapshot = _state.value
        val homeCurrency = snapshot.homeCurrency
        if (line.isBlank() || homeCurrency == null || snapshot.isSaving) return

        initialValuesApplied = true
        fillJob?.cancel()
        searchJob?.cancel()
        searchJob = null
        _state.update { it.copy(suggestions = emptyList(), showSuggestions = false) }

        val nowMs = timeProvider.now()
        val categoryRefs = categories.value.map { CategoryRef(id = it.id, name = it.name) }
        // Owner-approvals item 63: parse synchronously so the launched job captures only the parsed
        // result, the category refs and nowMs - never the QuickAddInput or the typed line.
        // The parser is pure and total (golden-tested); only the C2 read can fail.
        val parsed = quickAddParser.parse(
            QuickAddInput(
                line = line,
                nowEpochMs = nowMs,
                zone = java.time.ZoneId.systemDefault(),
                homeCurrency = homeCurrency,
                categories = categoryRefs
            )
        )

        fillJob = viewModelScope.launch {
            var learned: CategoryMatch? = null
            val merchantValue = (parsed.merchant as? FieldParse.Value)?.value
            if (merchantValue != null &&
                QuickAddFillContract.wantsLearnedLookup(parsed, _state.value.toFillFields())
            ) {
                try {
                    when (val lookup = quickAddLearnedCategoryLookup.lookup(merchantValue, categoryRefs)) {
                        is LearnedCategoryLookupResult.Found -> learned = lookup.match
                        LearnedCategoryLookupResult.Absent -> Unit
                        LearnedCategoryLookupResult.Failed -> {
                            // INV-13: bounded code only; no throwable, merchant or key. Category stays Absent.
                            Timber.w(LOG_QUICK_ADD_CATEGORY_LOOKUP_FAILED)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // INV-13: bounded code only; no throwable, merchant or key. Category stays Absent.
                    Timber.w(LOG_QUICK_ADD_CATEGORY_LOOKUP_FAILED)
                }
            }

            // INV-18: a cancelled (superseded) job must never apply a stale result.
            ensureActive()
            var applied: QuickAddFillApplication? = null
            _state.update { current ->
                if (current.isSaving) {
                    applied = null
                    current
                } else {
                    val application = QuickAddFillContract.apply(current.toFillFields(), parsed, learned, nowMs)
                    applied = application
                    current.withFillApplication(application)
                }
            }
            applied?.let { logFillOutcome(it.outcome) }
        }
    }

    private fun logFillOutcome(outcome: OutcomeCode) {
        when (outcome) {
            OutcomeCode.FILL_FULL -> Timber.i(LOG_FILL_FULL)
            OutcomeCode.FILL_PARTIAL -> Timber.i(LOG_FILL_PARTIAL)
            OutcomeCode.FILL_NONE -> Timber.i(LOG_FILL_NONE)
            OutcomeCode.FOREIGN_CURRENCY_BLOCKED -> Timber.i(LOG_FOREIGN_CURRENCY_BLOCKED)
        }
    }

    /** A user gesture on [field]: origin USER and its flag cleared (spec 5.1). */
    private fun AddExpenseState.withUserEdit(field: QuickAddField): AddExpenseState = copy(
        fieldOrigins = fieldOrigins + (field to FieldOrigin.USER),
        fieldFlags = fieldFlags - field
    )

    private fun AddExpenseState.toFillFields(): QuickAddFormFields = QuickAddFormFields(
        amount = amount,
        merchant = merchant,
        dateMs = date,
        categoryId = selectedCategoryId,
        payment = paymentMethod,
        origins = fieldOrigins,
        flags = fieldFlags,
        categorySource = categoryFillSource
    )

    private fun AddExpenseState.withFillApplication(application: QuickAddFillApplication): AddExpenseState {
        val form = application.form
        val written = application.writtenFields
        return copy(
            amount = form.amount,
            merchant = form.merchant,
            date = form.dateMs,
            selectedCategoryId = form.categoryId,
            paymentMethod = form.payment,
            fieldOrigins = form.origins,
            fieldFlags = form.flags,
            categoryFillSource = form.categorySource,
            lineHints = application.lineHints,
            lastFillOutcome = application.outcome,
            fillSummary = QuickAddUiMapper.snapshotOf(
                outcome = application.outcome,
                hints = application.lineHints,
                origins = form.origins,
                flags = form.flags
            ),
            // Step 5: a stale save result and the errors of the fields Fill rewrote are cleared.
            saveResult = null,
            amountError = if (QuickAddField.AMOUNT in written) null else amountError,
            merchantError = if (QuickAddField.MERCHANT in written) null else merchantError
        )
    }

    /**
     * MEC AE-34: called by the sheet when it opens. Captures the device zone once per sheet session
     * (null-guarded) so a zone change while the sheet is open cannot move the expense's local date.
     * A configuration change keeps the captured zone; [onSheetLeft] and [reset] clear it.
     */
    fun onSheetOpened() {
        if (sessionZone == null) {
            sessionZone = java.time.ZoneId.systemDefault()
        }
    }

    /**
     * MEC AE-34: called when the sheet leaves composition for any reason other than a configuration
     * change (e.g. host navigation without [reset]), so the next session re-captures a fresh zone.
     */
    fun onSheetLeft() {
        sessionZone = null
    }

    fun reset() {
        sessionZone = null
        searchJob?.cancel()
        searchJob = null
        fillJob?.cancel()
        fillJob = null
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
            // S5-020: Sanitize prefilled amount — same path as manual input
            // R16: the clipboard parser now hands over canonical text ("12.50"; ambiguous values such as
            // "1,234" are rejected there), but a direct caller may still pass machine-style text, so
            // convert to a number first and format locale-independently, as selectSuggestion does.
            val prefilledAmount = amount?.let { raw ->
                AmountUtils.parseAmount(raw)?.let { parsed ->
                    com.yourname.expensetracker.ui.util.AmountInputSanitizer.sanitize(
                        String.format(java.util.Locale.US, "%.2f", parsed)
                    )
                }
            }
            val prefilledMerchant = merchant?.take(100)?.trim()
            var origins = current.fieldOrigins
            // Spec 5.1: PREFILL is recorded only for a prefill that actually wrote a non-blank value.
            if (!prefilledAmount.isNullOrBlank()) origins = origins + (QuickAddField.AMOUNT to FieldOrigin.PREFILL)
            if (!prefilledMerchant.isNullOrBlank()) origins = origins + (QuickAddField.MERCHANT to FieldOrigin.PREFILL)
            current.copy(
                amount = prefilledAmount ?: current.amount,
                merchant = prefilledMerchant ?: current.merchant,
                fieldOrigins = origins
            )
        }
    }

    fun clearSaveResult() {
        _state.update { it.copy(saveResult = null) }
    }
}
