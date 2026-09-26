package com.yourname.expensetracker.ui.screens.budget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.domain.budget.AdjustedSpendBreakdown
import com.yourname.expensetracker.domain.budget.BudgetAutopilotEngine
import com.yourname.expensetracker.domain.budget.BudgetAutopilotRecommendations
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.domain.budget.BudgetSuggestion
import com.yourname.expensetracker.domain.budget.CategoryBudgetRecommendation
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.groups.SharedExpenseBudgetOffsetEngine
import com.yourname.expensetracker.domain.util.TimeProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import timber.log.Timber
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BudgetUiState(
    val budgets: List<BudgetStatus> = emptyList(),
    val suggestions: List<BudgetSuggestion> = emptyList(),
    val autopilotRecommendations: List<CategoryBudgetRecommendation> = emptyList(),
    val autopilotLoading: Boolean = false,
    val autopilotError: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    /** S8-005: null until home currency loads — never defaults to "EUR" */
    val homeCurrency: String? = null,
    val referenceNowMillis: Long = 0L
) {
    val loadableState: com.yourname.expensetracker.ui.model.LoadableUiState<List<BudgetStatus>>
        get() = when {
            isLoading -> com.yourname.expensetracker.ui.model.LoadableUiState.Loading
            error != null -> com.yourname.expensetracker.ui.model.LoadableUiState.Error(
                com.yourname.expensetracker.domain.model.UiText.DynamicString(error)
            )
            budgets.isEmpty() -> com.yourname.expensetracker.ui.model.LoadableUiState.Empty(
                com.yourname.expensetracker.domain.model.UiText.DynamicString("No budgets configured")
            )
            else -> com.yourname.expensetracker.ui.model.LoadableUiState.Data(budgets)
        }
}

@HiltViewModel
class BudgetViewModel @Inject constructor(
    private val budgetRepository: BudgetRepository,
    private val categoryRepository: CategoryRepository,
    private val offsetEngine: SharedExpenseBudgetOffsetEngine,
    private val autopilotEngine: BudgetAutopilotEngine,
    private val timeProvider: TimeProvider,
    private val currencySettingsRepository: CurrencySettingsRepository,
    /** BUD-21: Used for transactional bulk updates. */
    private val database: AppDatabase
) : ViewModel() {

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val _manualState = MutableStateFlow<ManualState>(ManualState.Idle)
    private val _budgetsRefreshTrigger = MutableStateFlow(0)
    private val _suggestionsRefreshTrigger = MutableStateFlow(0)
    private val _autopilotRecommendations = MutableStateFlow<BudgetAutopilotRecommendations?>(null)
    private val _autopilotLoading = MutableStateFlow(false)
    private val _autopilotError = MutableStateFlow<String?>(null)

    /** S8-001: One-shot event — screen closes dialog only on BudgetSaved. */
    private val _uiEvents = kotlinx.coroutines.flow.MutableSharedFlow<BudgetUiEvent>(extraBufferCapacity = 4)
    val uiEvents: kotlinx.coroutines.flow.SharedFlow<BudgetUiEvent> = _uiEvents.asSharedFlow()

    companion object {
        /**
         * RP-08 (P6-002/003 privacy): user-facing autopilot errors are controlled
         * constants only — never raw exception messages. `BUDGET_HISTORY_UNAVAILABLE`
         * is the sanitized failure state for repository/home-currency history
         * failures; the raw cause stays in logs via the exception class name.
         */
        const val ERROR_BUDGET_HISTORY_UNAVAILABLE = "BUDGET_HISTORY_UNAVAILABLE: Budget history is temporarily unavailable."
        const val ERROR_AUTOPILOT_GENERATE_FAILED = "AUTOPILOT_GENERATE_FAILED: Could not generate budget recommendations."
        const val ERROR_AUTOPILOT_APPLY_FAILED = "AUTOPILOT_APPLY_FAILED: Could not apply the recommendation."
        const val ERROR_AUTOPILOT_APPLY_ALL_FAILED = "AUTOPILOT_APPLY_ALL_FAILED: Apply all failed and was rolled back."
        const val ERROR_AUTOPILOT_NOT_ACTIONABLE = "AUTOPILOT_NOT_ACTIONABLE: This recommendation cannot be applied."
        const val ERROR_AUTOPILOT_REGENERATE_REQUIRED = "AUTOPILOT_REGENERATE_REQUIRED: Budget currency changed; regenerate recommendations."
    }

    private sealed class ManualState {
        object Idle : ManualState()
        object Loading : ManualState()
        data class Error(val message: String?) : ManualState()
    }

    sealed interface BudgetUiEvent {
        data object BudgetSaved : BudgetUiEvent
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val adjustedBudgetStatuses: Flow<List<BudgetStatus>> =
        _budgetsRefreshTrigger
            .flatMapLatest { budgetRepository.getBudgetStatuses() }
            .mapLatest { statuses ->
                statuses.map { status ->
                    // P6-CURRENT-002: BudgetRepository now populates adjustedSpendBreakdown so the
                    // monitor and UI agree. Reuse it when present; only compute as a fallback.
                    status.adjustedSpendBreakdown?.let { return@map status }
                    status.copy(adjustedSpendBreakdown = calculateAdjustedSpend(status))
                }
            }

    /** S8-005: null initial value — never defaults to "EUR" before repository emits. */
    private val _homeCurrency = currencySettingsRepository.homeCurrency()
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<BudgetUiState> = combine(
        combine(
            adjustedBudgetStatuses,
            _suggestionsRefreshTrigger.flatMapLatest { flow { emit(budgetRepository.getSuggestions()) } }
        ) { statuses, suggestions -> statuses to suggestions },
        combine(
            _manualState,
            _autopilotRecommendations,
            _autopilotLoading,
            _autopilotError
        ) { manual, autopilot, autopilotLoading, autopilotError ->
            object { val manual = manual; val autopilot = autopilot; val autopilotLoading = autopilotLoading; val autopilotError = autopilotError }
        },
        _homeCurrency
    ) { (statuses, suggestions), ctx, hc ->
        BudgetUiState(
            budgets = statuses,
            suggestions = suggestions,
            autopilotRecommendations = ctx.autopilot?.categoryRecommendations ?: emptyList(),
            autopilotLoading = ctx.autopilotLoading,
            autopilotError = ctx.autopilotError,
            isLoading = ctx.manual is ManualState.Loading,
            error = (ctx.manual as? ManualState.Error)?.message,
            homeCurrency = hc,
            referenceNowMillis = timeProvider.now()
        )
    }
    .catch { e ->
        emit(BudgetUiState(error = e.message, isLoading = false))
    }
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = BudgetUiState(isLoading = true)
    )

    /**
     * Calculate adjusted spend breakdown for a budget status.
     * Uses the offset engine to compute net shared liability.
     */
    private suspend fun calculateAdjustedSpend(status: BudgetStatus): AdjustedSpendBreakdown? {
        return try {
            val breakdown = offsetEngine.calculateEffectiveBudgetSpend(
                periodStart = status.periodStart,
                periodEnd = status.periodEnd,
                categoryId = status.budget.categoryId
            )
            
            AdjustedSpendBreakdown(
                personalSpend = breakdown.totalPersonalSpend,
                sharedSpend = breakdown.totalSharedSpend,
                reimbursedAmount = breakdown.totalReimbursed,
                netSharedLiability = breakdown.netSharedLiability,
                effectiveSpend = breakdown.effectiveBudgetSpend,
                pendingReimbursements = breakdown.getPendingReimbursement()
            )
        } catch (e: Exception) {
            // Return null on error; UI will fall back to raw spend
            null
        }
    }

    val categories = categoryRepository.allCategories.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    fun addBudget(budget: Budget) {
        // S8-007: Idempotency guard
        if (_manualState.value is ManualState.Loading) return
        if (!validateThresholds(budget)) return
        viewModelScope.launch {
            _manualState.value = ManualState.Loading
            val result = budgetRepository.addBudget(budget)
            when (result) {
                is com.yourname.expensetracker.domain.model.Result.Success -> {
                    _manualState.value = ManualState.Idle
                    // S8-001: Emit success event — screen closes dialog on this
                    _uiEvents.tryEmit(BudgetUiEvent.BudgetSaved)
                }
                is com.yourname.expensetracker.domain.model.Result.Error -> {
                    _manualState.value = ManualState.Error(result.message)
                }
                else -> { _manualState.value = ManualState.Idle }
            }
        }
    }

    fun updateBudget(budget: Budget) {
        if (_manualState.value is ManualState.Loading) return
        if (!validateThresholds(budget)) return
        viewModelScope.launch {
            _manualState.value = ManualState.Loading
            val result = budgetRepository.updateBudget(budget)
            when (result) {
                is com.yourname.expensetracker.domain.model.Result.Success -> {
                    _manualState.value = ManualState.Idle
                    _uiEvents.tryEmit(BudgetUiEvent.BudgetSaved)
                }
                is com.yourname.expensetracker.domain.model.Result.Error -> {
                    _manualState.value = ManualState.Error(result.message)
                }
                else -> { _manualState.value = ManualState.Idle }
            }
        }
    }

    private fun validateThresholds(budget: Budget): Boolean {
        if (budget.notifyAtWarning <= 0f || budget.notifyAtWarning >= 1f) {
            _manualState.value = ManualState.Error("Warning threshold must be between 0 and 1")
            return false
        }
        if (budget.notifyAtCritical <= budget.notifyAtWarning || budget.notifyAtCritical > 1.0f) {
            _manualState.value = ManualState.Error("Critical threshold must be between warning and 100%")
            return false
        }
        return true
    }

    fun deleteBudget(budget: Budget) {
        if (_manualState.value is ManualState.Loading) return
        viewModelScope.launch {
            _manualState.value = ManualState.Loading
            val result = budgetRepository.deleteBudget(budget)
             when (result) {
                is com.yourname.expensetracker.domain.model.Result.Success -> {
                    _manualState.value = ManualState.Idle
                }
                is com.yourname.expensetracker.domain.model.Result.Error -> {
                    _manualState.value = ManualState.Error(result.message)
                }
                else -> { _manualState.value = ManualState.Idle }
            }
        }
    }

    fun toggleBudget(id: Long, isActive: Boolean) {
        if (_manualState.value is ManualState.Loading) return
        viewModelScope.launch {
            _manualState.value = ManualState.Loading
             val result = budgetRepository.toggleBudget(id, isActive)
             when (result) {
                is com.yourname.expensetracker.domain.model.Result.Success -> {
                    _manualState.value = ManualState.Idle
                }
                is com.yourname.expensetracker.domain.model.Result.Error -> {
                    _manualState.value = ManualState.Error(result.message)
                }
                else -> { _manualState.value = ManualState.Idle }
            }
        }
    }

    fun refreshSuggestions() {
        _suggestionsRefreshTrigger.value += 1
    }

    fun refreshBudgets() {
        timeProvider.now()
        _budgetsRefreshTrigger.value += 1
    }

    fun clearError() {
        _manualState.value = ManualState.Idle
    }

    // ==================== AUTOPILOT METHODS ====================

    fun generateAutopilotRecommendations() {
        viewModelScope.launch {
            _autopilotLoading.value = true
            _autopilotError.value = null
            try {
                val recommendations = autopilotEngine.generateRecommendations()
                _autopilotRecommendations.value = recommendations
            } catch (e: Exception) {
                // RP-01: never swallow CancellationException.
                if (e is kotlinx.coroutines.CancellationException) throw e
                Timber.e(e, "Autopilot generate failed")
                // RP-08: typed/sanitized reason code — raw exception messages
                // (e.g. repository failure details) must never reach the UI.
                _autopilotError.value = when (e) {
                    is com.yourname.expensetracker.data.repository.HomeCurrencyUnavailableException ->
                        ERROR_BUDGET_HISTORY_UNAVAILABLE
                    is com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableException ->
                        ERROR_BUDGET_HISTORY_UNAVAILABLE
                    else -> ERROR_AUTOPILOT_GENERATE_FAILED
                }
                _autopilotRecommendations.value = null
            } finally {
                _autopilotLoading.value = false
            }
        }
    }

    fun applyAutopilotRecommendation(recommendation: CategoryBudgetRecommendation) {
        // RP-08 (P6-004): LOW_HISTORY / non-actionable recommendations must never
        // write a budget. Fail closed before any repository access.
        if (!recommendation.isActionable) {
            _autopilotError.value = ERROR_AUTOPILOT_NOT_ACTIONABLE
            return
        }
        viewModelScope.launch {
            _autopilotLoading.value = true
            _autopilotError.value = null
            try {
                val activeBudgets = budgetRepository.getActiveBudgets()
                val budget = activeBudgets
                    .find { it.id == recommendation.budgetId }
                    ?: activeBudgets.find {
                        recommendation.categoryId != null && it.categoryId == recommendation.categoryId
                    }

                if (budget == null ||
                    com.yourname.expensetracker.domain.currency.SupportedCurrency.fromCode(budget.currency)?.code !=
                    recommendation.sourceAmountToApply.currency.code
                ) {
                    _autopilotError.value = ERROR_AUTOPILOT_REGENERATE_REQUIRED
                    return@launch
                }

                val updatedBudget = budget.copy(amount = recommendation.sourceAmountToApply.amount)
                val result = budgetRepository.updateBudget(updatedBudget)
                when (result) {
                    is com.yourname.expensetracker.domain.model.Result.Success -> {
                        val current = _autopilotRecommendations.value
                        if (current != null) {
                            _autopilotRecommendations.value = current.copy(
                                categoryRecommendations = current.categoryRecommendations.filter {
                                    it.budgetId != recommendation.budgetId
                                }
                            )
                        }
                    }
                    is com.yourname.expensetracker.domain.model.Result.Error -> {
                        // RP-08 privacy: repository Result messages are not
                        // guaranteed controlled text — surface the sanitized
                        // constant only (same policy as the C2 catch blocks).
                        _autopilotError.value = ERROR_AUTOPILOT_APPLY_FAILED
                    }
                    else -> {}
                }
            } catch (e: Exception) {
                // RP-01: never swallow CancellationException.
                if (e is kotlinx.coroutines.CancellationException) throw e
                Timber.e(e, "Autopilot apply single failed")
                // RP-08 privacy: sanitized constant only — raw exception
                // messages must never reach the UI.
                _autopilotError.value = ERROR_AUTOPILOT_APPLY_FAILED
            } finally {
                _autopilotLoading.value = false
            }
        }
    }

    fun applyAllAutopilotRecommendations() {
        viewModelScope.launch {
            _autopilotLoading.value = true
            _autopilotError.value = null
            try {
                // RP-08 (P6-004): Apply All must never write non-actionable
                // (LOW_HISTORY) recommendations — fail closed, filter them out.
                val recommendations = (_autopilotRecommendations.value?.categoryRecommendations ?: emptyList())
                    .filter { it.isActionable }
                if (recommendations.isEmpty()) {
                    _autopilotError.value = ERROR_AUTOPILOT_NOT_ACTIONABLE
                    return@launch
                }
                val activeBudgets = budgetRepository.getActiveBudgets()
                val updates = recommendations.map { recommendation ->
                    val budget = activeBudgets.find {
                        it.id == recommendation.budgetId ||
                            (recommendation.categoryId != null && it.categoryId == recommendation.categoryId)
                    }
                    if (budget == null ||
                        com.yourname.expensetracker.domain.currency.SupportedCurrency.fromCode(budget.currency)?.code !=
                        recommendation.sourceAmountToApply.currency.code
                    ) {
                        _autopilotError.value = ERROR_AUTOPILOT_REGENERATE_REQUIRED
                        return@launch
                    }
                    budget.copy(amount = recommendation.sourceAmountToApply.amount)
                }

                database.withTransaction {
                    for (updatedBudget in updates) {
                        budgetRepository.updateBudgetOrThrow(updatedBudget)
                    }
                }

                _autopilotRecommendations.value = null
            } catch (e: Exception) {
                // RP-01: never swallow CancellationException.
                if (e is kotlinx.coroutines.CancellationException) throw e
                Timber.e(e, "Autopilot apply-all transaction failed, rolled back")
                // RP-08 privacy: sanitized constant only — raw exception
                // messages must never reach the UI.
                _autopilotError.value = ERROR_AUTOPILOT_APPLY_ALL_FAILED
            } finally {
                _autopilotLoading.value = false
            }
        }
    }

    /**
     * Dismiss all autopilot recommendations.
     */
    fun dismissAllAutopilotRecommendations() {
        _autopilotRecommendations.value = null
    }
}
