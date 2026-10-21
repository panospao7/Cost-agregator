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
import com.yourname.expensetracker.domain.recurring.usecase.MarkPaidResult
import com.yourname.expensetracker.domain.recurring.usecase.MarkRecurringBillPaidUseCase
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
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
    val totalMonthly: Double = 0.0,
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
    
    init {
        loadRecurringExpenses()
    }
    
    private fun loadRecurringExpenses() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            
            try {
                val expenses = recurringExpenseRepository.getAll()
                val activeExpenses = expenses.filter { it.isActive }
                
                // Calculate monthly total
                val totalMonthly = activeExpenses.sumOf { expense ->
                    calculateMonthlyAmount(expense.amount, expense.frequency)
                }
                
                // Count upcoming (next 7 days)
                val oneWeekFromNow = timeProvider.now() + (7L * TimePeriodUtils.DAY_IN_MILLIS)
                val upcomingCount = activeExpenses.count { it.nextDate <= oneWeekFromNow }
                
                _uiState.value = ManualRecurringExpenseUiState(
                    recurringExpenses = expenses.sortedBy { it.nextDate },
                    isLoading = false,
                    error = null,
                    totalMonthly = totalMonthly,
                    activeCount = activeExpenses.size,
                    upcomingCount = upcomingCount,
                    referenceNowMillis = timeProvider.now(),
                    inFlightRuleIds = _uiState.value.inFlightRuleIds,
                    actionMessage = _uiState.value.actionMessage,
                    errorText = null
                )
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
    
    private fun calculateMonthlyAmount(amount: Double, frequency: RecurrenceFrequency): Double {
        return RecurrenceCalculator.toMonthlyAmount(amount, frequency)
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
