package com.yourname.expensetracker.ui.screens.recurringmanual

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.data.repository.ManualRecurringExpenseRepository
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.logic.RecurrenceCalculator
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.recurring.lifecycle.PaymentLinkConfirmResult
import com.yourname.expensetracker.domain.recurring.lifecycle.PaymentLinkDeclineResult
import com.yourname.expensetracker.domain.recurring.lifecycle.PaymentLinkFailureCodes
import com.yourname.expensetracker.domain.recurring.lifecycle.PaymentLinkSuggestion
import com.yourname.expensetracker.domain.recurring.lifecycle.PaymentLinkSuggestions
import com.yourname.expensetracker.domain.recurring.usecase.MarkPaidResult
import com.yourname.expensetracker.domain.recurring.usecase.MarkRecurringBillPaidUseCase
import com.yourname.expensetracker.domain.util.CancellationSafe
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.ui.screens.subscription.SubscriptionTotalStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for manual recurring expense management.
 */
data class ManualRecurringExpenseUiState(
    val recurringExpenses: List<ManualRecurringExpense> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    /**
     * INV-12/13: monthly total in [totalCurrency] over the active rules in that currency only.
     * Null iff [totalStatus] is UNAVAILABLE (home currency read failed); never rendered as zero.
     */
    val totalMonthly: Double? = null,
    /** INV-14: the home code that decided inclusion; the total card label uses this. */
    val totalCurrency: String? = null,
    val totalStatus: SubscriptionTotalStatus = SubscriptionTotalStatus.UNAVAILABLE,
    /** Active rules left out of [totalMonthly] because their currency differs from [totalCurrency]. */
    val excludedRuleCount: Int = 0,
    val activeCount: Int = 0,
    val upcomingCount: Int = 0,
    val referenceNowMillis: Long = 0L,
    /**
     * R21 S2: bounded, resource-backed LOAD error (never contains exception text). Only load
     * failures drive the full-screen error state; action failures use [actionMessage].
     */
    val errorText: UiText? = null,
    /**
     * R21 review: one-shot, bounded message for an action (add / toggle / delete / mark paid /
     * already recorded) failure or info. Shown as a snackbar; the list stays visible.
     */
    val actionMessage: UiText? = null,
    /** R21 S2: rule ids with a mark-paid / already-recorded call in flight (button disabled). */
    val inFlightRuleIds: Set<Long> = emptySet()
) {
    val loadableState: com.yourname.expensetracker.ui.model.LoadableUiState<List<ManualRecurringExpense>>
        get() = when {
            isLoading -> com.yourname.expensetracker.ui.model.LoadableUiState.Loading
            errorText != null -> com.yourname.expensetracker.ui.model.LoadableUiState.Error(errorText)
            error != null ->com.yourname.expensetracker.ui.model.LoadableUiState.Error(com.yourname.expensetracker.domain.model.UiText.DynamicString(error))
            recurringExpenses.isEmpty() -> com.yourname.expensetracker.ui.model.LoadableUiState.Empty(com.yourname.expensetracker.domain.model.UiText.DynamicString("No recurring expenses"))
            else -> com.yourname.expensetracker.ui.model.LoadableUiState.Data(recurringExpenses)
        }
}

/**
 * FC-PAID-UNLINKED: one rendered suggestion ("Paid already? 400,00 EUR on Nov 25, due Nov 27"). Holds
 * only what the block shows plus the ids the two actions need. [ruleId] is the
 * [ManualRecurringExpense.id] (equal to `RecurringOccurrence.sourceId` for rule occurrences). The paid
 * amount is the expense's own amount in [paidCurrency]; it is formatted by the screen with
 * `CurrencyFormatter.formatMoney`, never converted, summed or rounded here. In memory only.
 */
data class PaymentLinkSuggestionUi(
    val occurrenceId: Long,
    val ruleId: Long,
    val expenseId: Long,
    val paidAmount: Double,
    val paidCurrency: String,
    val expenseDate: Long,
    val dueDate: Long
)

/**
 * FC-PAID-UNLINKED: suggestion state of the Recurring Expenses screen. Independent of the rule list,
 * so a suggestion failure never blanks the list.
 */
sealed interface PaymentLinkSuggestionsUiState {
    /** No result yet. Renders nothing; a recompute keeps the previous value instead. */
    data object Loading : PaymentLinkSuggestionsUiState

    /** Non-empty suggestions per rule id, each list ordered by due date. */
    data class Content(val byRuleId: Map<Long, List<PaymentLinkSuggestionUi>>) : PaymentLinkSuggestionsUiState

    /** Available with nothing to suggest. Renders nothing (no "no suggestions" text). */
    data object None : PaymentLinkSuggestionsUiState

    /** The read was blocked or failed. Never [None], never a zero (INV-11). Reason code is not shown. */
    data object Unavailable : PaymentLinkSuggestionsUiState
}

/**
 * Maps a domain suggestion to its UI row, or null when it cannot be rendered truthfully (non-finite or
 * non-positive amount, blank currency): such an item is dropped, never shown as zero or blank.
 */
internal fun PaymentLinkSuggestion.toUiOrNull(): PaymentLinkSuggestionUi? =
    if (!paidAmount.isFinite() || paidAmount <= 0.0 || paidCurrency.isBlank()) {
        null
    } else {
        PaymentLinkSuggestionUi(
            occurrenceId = occurrenceId,
            ruleId = ruleId,
            expenseId = expenseId,
            paidAmount = paidAmount,
            paidCurrency = paidCurrency,
            expenseDate = expenseDate,
            dueDate = dueDate
        )
    }

/**
 * R21: whether the mark-paid / already-recorded actions apply to [expense]. IRREGULAR rules have no
 * scheduled occurrences, so the actions are neither shown by the screen nor accepted by the ViewModel.
 */
internal fun supportsMarkPaid(expense: ManualRecurringExpense): Boolean =
    expense.frequency != RecurrenceFrequency.IRREGULAR

@HiltViewModel
class ManualRecurringExpenseViewModel @Inject constructor(
    private val recurringExpenseRepository: ManualRecurringExpenseRepository,
    private val timeProvider: TimeProvider,
    private val currencySettingsRepository: CurrencySettingsRepository,
    private val markRecurringBillPaid: MarkRecurringBillPaidUseCase
) : ViewModel() {

    val homeCurrency: Flow<String> = currencySettingsRepository.homeCurrency()
    
    private val _uiState = MutableStateFlow(ManualRecurringExpenseUiState())
    val uiState: StateFlow<ManualRecurringExpenseUiState> = _uiState.asStateFlow()

    // FC-PAID-UNLINKED: declared before init (init starts the first load, which starts the first read).
    private val _paymentLinkSuggestions =
        MutableStateFlow<PaymentLinkSuggestionsUiState>(PaymentLinkSuggestionsUiState.Loading)
    val paymentLinkSuggestions: StateFlow<PaymentLinkSuggestionsUiState> =
        _paymentLinkSuggestions.asStateFlow()
    private var paymentLinkSuggestionsJob: Job? = null

    init {
        loadRecurringExpenses()
    }
    
    private fun loadRecurringExpenses() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            
            try {
                val expenses = recurringExpenseRepository.getAll()
                val activeExpenses = expenses.filter { it.isActive }
                
                // INV-12/13: home currency read once, behind its own boundary; the total only
                // includes active rules in that currency (no conversion, no literal fallback).
                val monthlyTotal = computeMonthlyTotal(activeExpenses, readHomeCurrencyCode())

                // Count upcoming (next 7 days)
                val oneWeekFromNow = timeProvider.now() + (7L * TimePeriodUtils.DAY_IN_MILLIS)
                val upcomingCount = activeExpenses.count { it.nextDate <= oneWeekFromNow }
                
                _uiState.value = ManualRecurringExpenseUiState(
                    recurringExpenses = expenses.sortedBy { it.nextDate },
                    isLoading = false,
                    error = null,
                    totalMonthly = monthlyTotal.monthly,
                    totalCurrency = monthlyTotal.currency,
                    totalStatus = monthlyTotal.status,
                    excludedRuleCount = monthlyTotal.excludedRuleCount,
                    activeCount = activeExpenses.size,
                    upcomingCount = upcomingCount,
                    referenceNowMillis = timeProvider.now(),
                    inFlightRuleIds = _uiState.value.inFlightRuleIds,
                    actionMessage = _uiState.value.actionMessage,
                    errorText = null
                )
                // FC-PAID-UNLINKED: every successful list reload also recomputes the suggestions
                // (refresh action, toggle, delete, add, mark paid, link, decline).
                refreshPaymentLinkSuggestions()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorText = UiText.from(R.string.recurring_error_generic)
                )
            }
        }
    }

    /**
     * FC-PAID-UNLINKED: recomputes the suggestions through the use case only (read-only). A newer call
     * cancels the older one; the previous value stays visible meanwhile. An unreadable result is
     * [PaymentLinkSuggestionsUiState.Unavailable], never [PaymentLinkSuggestionsUiState.None] (INV-11).
     */
    private fun refreshPaymentLinkSuggestions() {
        paymentLinkSuggestionsJob?.cancel()
        paymentLinkSuggestionsJob = viewModelScope.launch {
            val next: PaymentLinkSuggestionsUiState = try {
                when (val result = markRecurringBillPaid.findSuggestions()) {
                    is PaymentLinkSuggestions.Available -> contentOrNone(result.items.mapNotNull { it.toUiOrNull() })
                    is PaymentLinkSuggestions.Unavailable -> PaymentLinkSuggestionsUiState.Unavailable
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PaymentLinkSuggestionsUiState.Unavailable
            }
            _paymentLinkSuggestions.value = next
        }
    }

    private fun contentOrNone(items: List<PaymentLinkSuggestionUi>): PaymentLinkSuggestionsUiState =
        if (items.isEmpty()) {
            PaymentLinkSuggestionsUiState.None
        } else {
            PaymentLinkSuggestionsUiState.Content(
                items.sortedWith(compareBy({ it.dueDate }, { it.occurrenceId }))
                    .groupBy { it.ruleId }
            )
        }

    /** Drops one suggestion from the current content right after its action settled (no stale flicker). */
    private fun removeSuggestion(suggestion: PaymentLinkSuggestionUi) {
        val current = _paymentLinkSuggestions.value as? PaymentLinkSuggestionsUiState.Content ?: return
        _paymentLinkSuggestions.value = contentOrNone(
            current.byRuleId.values.flatten().filterNot { it.occurrenceId == suggestion.occurrenceId }
        )
    }
    
    private data class MonthlyTotal(
        val monthly: Double?,
        val currency: String?,
        val status: SubscriptionTotalStatus,
        val excludedRuleCount: Int
    )

    /**
     * INV-12/13: sums [RecurrenceCalculator.toMonthlyAmount] over the active rules whose stored
     * currency equals [homeCode] (exact string equality). Active rules in any other currency are
     * left out and counted. A null [homeCode] means the home read was unavailable: no total
     * (never 0.0) and no count. Inactive rules are neither summed nor counted. No conversion.
     */
    private fun computeMonthlyTotal(activeExpenses: List<ManualRecurringExpense>, homeCode: String?): MonthlyTotal {
        if (homeCode == null) {
            return MonthlyTotal(
                monthly = null,
                currency = null,
                status = SubscriptionTotalStatus.UNAVAILABLE,
                excludedRuleCount = 0
            )
        }
        val included = activeExpenses.filter { it.currency == homeCode }
        val excludedRuleCount = activeExpenses.size - included.size
        val monthly = included.sumOf { rule ->
            RecurrenceCalculator.toMonthlyAmount(rule.amount, rule.frequency)
        }
        return MonthlyTotal(
            monthly = monthly,
            currency = homeCode,
            status = if (excludedRuleCount > 0) {
                SubscriptionTotalStatus.PARTIAL
            } else {
                SubscriptionTotalStatus.AVAILABLE
            },
            excludedRuleCount = excludedRuleCount
        )
    }

    /**
     * INV-13/INV-20: the home currency code, or null when the read threw (other than
     * cancellation) or resolved as [HomeCurrencyResolution.Failed]. Cancellation always
     * propagates; it is never turned into an unavailable total or an error text. The first-run
     * default counts as a real home currency (D-HOME).
     */
    private suspend fun readHomeCurrencyCode(): String? {
        val resolution = CancellationSafe.runCatchingCancellable {
            currencySettingsRepository.resolveHomeCurrency()
        }.getOrNull()
        // The repository implementation catches Exception and reports Failed, so a cancelled
        // coroutine must not carry on with an "unavailable" result.
        currentCoroutineContext().ensureActive()
        return resolution?.currencyOrNull?.code
    }
    
    /**
     * Add a manual recurring expense.
     */
    fun addRecurringExpense(
        merchant: String,
        amount: Double,
        frequency: RecurrenceFrequency,
        nextDate: Long,
        note: String?
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(actionMessage = null)
            try {
                // NEW-P2-016 fail-closed: never save a rule without a resolved home currency.
                val currency = when (val resolution = currencySettingsRepository.resolveHomeCurrency()) {
                    is HomeCurrencyResolution.Resolved -> resolution.currency.code
                    is HomeCurrencyResolution.FirstRunDefault -> resolution.currency.code
                    is HomeCurrencyResolution.Failed -> {
                        _uiState.value = _uiState.value.copy(
                            actionMessage = UiText.from(R.string.recurring_error_currency_unavailable)
                        )
                        return@launch
                    }
                }
                val expense = ManualRecurringExpense(
                    merchant = merchant,
                    amount = amount,
                    currency = currency,
                    frequency = frequency,
                    nextDate = nextDate,
                    note = note,
                    isSubscription = false,
                    isActive = true
                )
                recurringExpenseRepository.insert(expense)
                loadRecurringExpenses()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    actionMessage = UiText.from(R.string.recurring_error_generic)
                )
            }
        }
    }
    
    /**
     * Toggle active status.
     */
    fun toggleStatus(id: Long, currentStatus: Boolean) {
        viewModelScope.launch {
            try {
                recurringExpenseRepository.setActiveStatus(id, !currentStatus)
                loadRecurringExpenses()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    actionMessage = UiText.from(R.string.recurring_error_generic)
                )
            }
        }
    }
    
    /**
     * Delete a recurring expense.
     */
    fun deleteExpense(id: Long) {
        viewModelScope.launch {
            try {
                recurringExpenseRepository.deleteById(id)
                loadRecurringExpenses()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    actionMessage = UiText.from(R.string.recurring_error_generic)
                )
            }
        }
    }
    
    /**
     * R21 S2: mark the bill paid. Creates the expense through the lifecycle use case,
     * links it to the occurrence and advances the rule. A second tap while the action is in flight is
     * ignored here, and the use case itself creates at most one expense per occurrence.
     * IRREGULAR rules have no scheduled occurrences and are ignored.
     */
    fun markAsPaid(expense: ManualRecurringExpense) {
        if (!supportsMarkPaid(expense)) return
        runBillAction(expense) { useCase, rule -> useCase.invoke(rule.id, rule.nextDate) }
    }

    /**
     * R21 S2: "Already recorded" - the payment exists elsewhere; skip the occurrence
     * (no expense is created) and advance the rule. Ignored for IRREGULAR rules.
     */
    fun markAlreadyRecorded(expense: ManualRecurringExpense) {
        if (!supportsMarkPaid(expense)) return
        runBillAction(expense) { useCase, rule -> useCase.skip(rule.id, rule.nextDate) }
    }

    /**
     * FC-PAID-UNLINKED INV-12: the user confirmed that [suggestion]'s expense paid its bill ("Link").
     * Single tap, no confirmation dialog; the snackbar states the effect. Goes through the use case
     * only. The rule is in flight (all its buttons disabled) until the call settles.
     */
    fun linkSuggestedPayment(suggestion: PaymentLinkSuggestionUi) {
        val rule = ruleFor(suggestion) ?: return dropSuggestionOfMissingRule(suggestion)
        runSuggestionAction(suggestion) {
            when (val result = markRecurringBillPaid.linkExistingPayment(
                suggestion.ruleId,
                suggestion.occurrenceId,
                suggestion.expenseId
            )) {
                // AlreadyLinkedToThis is a success (retry after a lost response).
                PaymentLinkConfirmResult.Linked,
                PaymentLinkConfirmResult.AlreadyLinkedToThis -> SuggestionOutcome(
                    UiText.from(R.string.recurring_link_done_format, rule.merchant),
                    settled = true,
                    reloadRules = true
                )
                // Stale UI; the user need not know which cause. Recompute.
                PaymentLinkConfirmResult.NoLongerValid,
                PaymentLinkConfirmResult.ExpenseLinkedElsewhere -> SuggestionOutcome(
                    UiText.from(R.string.recurring_link_no_longer_valid),
                    settled = true,
                    reloadRules = true
                )
                is PaymentLinkConfirmResult.Failed -> SuggestionOutcome(
                    failureText(result.code),
                    settled = false,
                    reloadRules = false
                )
            }
        }
    }

    /**
     * FC-PAID-UNLINKED INV-14: "Not this bill". Permanent in v1 (no undo). The bill stays counted;
     * "Mark as Paid" and "Already recorded" remain available. Goes through the use case only.
     */
    fun declineSuggestedPayment(suggestion: PaymentLinkSuggestionUi) {
        if (ruleFor(suggestion) == null) return dropSuggestionOfMissingRule(suggestion)
        runSuggestionAction(suggestion) {
            when (val result = markRecurringBillPaid.declinePayment(
                suggestion.ruleId,
                suggestion.occurrenceId,
                suggestion.expenseId
            )) {
                PaymentLinkDeclineResult.Declined,
                PaymentLinkDeclineResult.AlreadyDeclined -> SuggestionOutcome(
                    UiText.from(R.string.recurring_link_declined),
                    settled = true,
                    reloadRules = false
                )
                PaymentLinkDeclineResult.NoLongerValid -> SuggestionOutcome(
                    UiText.from(R.string.recurring_link_no_longer_valid),
                    settled = true,
                    reloadRules = true
                )
                is PaymentLinkDeclineResult.Failed -> SuggestionOutcome(
                    failureText(result.code),
                    settled = false,
                    reloadRules = false
                )
            }
        }
    }

    private class SuggestionOutcome(
        val message: UiText,
        /** True when the suggestion is resolved (success or stale): drop it and recompute. */
        val settled: Boolean,
        /** True when the rule list must be reloaded too (the due date may have moved). */
        val reloadRules: Boolean
    )

    private fun ruleFor(suggestion: PaymentLinkSuggestionUi): ManualRecurringExpense? =
        _uiState.value.recurringExpenses.firstOrNull { it.id == suggestion.ruleId }

    /** The rule cannot be read any more: drop the item and recompute instead of acting on it. */
    private fun dropSuggestionOfMissingRule(suggestion: PaymentLinkSuggestionUi) {
        removeSuggestion(suggestion)
        refreshPaymentLinkSuggestions()
    }

    /** WRITE_BLOCKED uses the existing maintenance message; every other code is the generic one. */
    private fun failureText(code: String): UiText =
        if (code == PaymentLinkFailureCodes.WRITE_BLOCKED) {
            UiText.from(R.string.ui_write_blocked_maintenance)
        } else {
            UiText.from(R.string.recurring_error_generic)
        }

    private fun runSuggestionAction(
        suggestion: PaymentLinkSuggestionUi,
        action: suspend () -> SuggestionOutcome
    ) {
        // Claim the in-flight slot synchronously (main thread) so a double tap is ignored.
        if (suggestion.ruleId in _uiState.value.inFlightRuleIds) return
        _uiState.value = _uiState.value.copy(
            actionMessage = null,
            inFlightRuleIds = _uiState.value.inFlightRuleIds + suggestion.ruleId
        )
        viewModelScope.launch {
            try {
                val outcome = action()
                _uiState.value = _uiState.value.copy(actionMessage = outcome.message)
                if (outcome.settled) removeSuggestion(suggestion)
                if (outcome.reloadRules) {
                    loadRecurringExpenses()
                } else if (outcome.settled) {
                    refreshPaymentLinkSuggestions()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    actionMessage = UiText.from(R.string.recurring_error_generic)
                )
            } finally {
                _uiState.value = _uiState.value.copy(
                    inFlightRuleIds = _uiState.value.inFlightRuleIds - suggestion.ruleId
                )
            }
        }
    }

    private fun runBillAction(
        expense: ManualRecurringExpense,
        action: suspend (MarkRecurringBillPaidUseCase, ManualRecurringExpense) -> MarkPaidResult
    ) {
        // Claim the in-flight slot synchronously (main thread) so a double tap is ignored.
        if (expense.id in _uiState.value.inFlightRuleIds) return
        _uiState.value = _uiState.value.copy(
            actionMessage = null,
            inFlightRuleIds = _uiState.value.inFlightRuleIds + expense.id
        )
        viewModelScope.launch {
            try {
                val result = action(markRecurringBillPaid, expense)
                when (result) {
                    is MarkPaidResult.Failed -> _uiState.value = _uiState.value.copy(
                        actionMessage = UiText.from(R.string.recurring_error_generic)
                    )
                    // Only the rule date moved; make that visible instead of a silent no-op.
                    MarkPaidResult.AdvancedOnly -> _uiState.value = _uiState.value.copy(
                        actionMessage = UiText.from(R.string.recurring_advanced_only_info)
                    )
                    // Nothing was paid and nothing moved: say so truthfully.
                    MarkPaidResult.NoChange -> _uiState.value = _uiState.value.copy(
                        actionMessage = UiText.from(R.string.recurring_mark_paid_no_change_info)
                    )
                    else -> Unit
                }
                loadRecurringExpenses()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    actionMessage = UiText.from(R.string.recurring_error_generic)
                )
            } finally {
                _uiState.value = _uiState.value.copy(
                    inFlightRuleIds = _uiState.value.inFlightRuleIds - expense.id
                )
            }
        }
    }

    fun refresh() {
        _uiState.value = _uiState.value.copy(errorText = null)
        loadRecurringExpenses()
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null, errorText = null, actionMessage = null)
    }

    /** Called by the screen after the action message snackbar was shown. */
    fun consumeActionMessage() {
        _uiState.value = _uiState.value.copy(actionMessage = null)
    }
}
