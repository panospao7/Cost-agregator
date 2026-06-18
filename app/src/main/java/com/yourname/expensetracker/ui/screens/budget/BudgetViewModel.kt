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
                Timber.e(e, "Autopilot generate failed")
                _autopilotError.value = "Failed to generate recommendations: ${e.message}"
                _autopilotRecommendations.value = null
            } finally {
                _autopilotLoading.value = false
            }
        }
    }

    fun applyAutopilotRecommendation(recommendation: CategoryBudgetRecommendation) {
        viewModelScope.launch {
            _autopilotLoading.value = true
            _autopilotError.value = null
            try {
                val budget = budgetRepository.getActiveBudgets()
                    .find { it.id == recommendation.budgetId }
                    ?: budgetRepository.getActiveBudgets().find {
                        recommendation.categoryId != null && it.categoryId == recommendation.categoryId
                    }

                if (budget != null) {
                    val updatedBudget = budget.copy(amount = recommendation.recommendedBudget)
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
                            _autopilotError.value = "Failed to apply recommendation: ${result.message}"
                        }
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Autopilot apply single failed")
                _autopilotError.value = "Failed to apply recommendation: ${e.message}"
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
                val recommendations = _autopilotRecommendations.value?.categoryRecommendations ?: emptyList()
                val activeBudgets = budgetRepository.getActiveBudgets()

                database.withTransaction {
                    for (rec in recommendations) {
                        val budget = activeBudgets.find {
                            it.id == rec.budgetId ||
                                (rec.categoryId != null && it.categoryId == rec.categoryId)
                        }
                        if (budget != null) {
                            budgetRepository.updateBudgetOrThrow(budget.copy(amount = rec.recommendedBudget))
                        }
                    }
                }

                _autopilotRecommendations.value = BudgetAutopilotRecommendations(
                    categoryRecommendations = emptyList(),
                    totalCurrentBudget = 0.0,
                    totalRecommendedBudget = 0.0,
                    overallDelta = 0.0,
                    confidence = 0.0,
                    generatedAt = timeProvider.now()
                )
            } catch (e: Exception) {
                Timber.e(e, "Autopilot apply-all transaction failed, rolled back")
                _autopilotError.value = "Apply all failed and was rolled back: ${e.message}"
            } finally {
                _autopilotLoading.value = false
            }
        }
    }

    /**
     * Dismiss all autopilot recommendations.
     */
    fun dismissAllAutopilotRecommendations() {
        _autopilotRecommendations.value = BudgetAutopilotRecommendations(
            categoryRecommendations = emptyList(),
            totalCurrentBudget = 0.0,
            totalRecommendedBudget = 0.0,
            overallDelta = 0.0,
            confidence = 0.0,
            generatedAt = timeProvider.now()
        )
    }
}
