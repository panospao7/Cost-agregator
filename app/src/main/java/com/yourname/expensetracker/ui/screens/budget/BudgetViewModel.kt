package com.yourname.expensetracker.ui.screens.budget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.domain.budget.BudgetSuggestion
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BudgetUiState(
    val budgets: List<BudgetStatus> = emptyList(),
    val suggestions: List<BudgetSuggestion> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class BudgetViewModel @Inject constructor(
    private val budgetRepository: BudgetRepository,
    private val categoryRepository: CategoryRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(BudgetUiState(isLoading = true))
    val uiState: StateFlow<BudgetUiState> = _uiState.asStateFlow()

    val categories = categoryRepository.allCategories.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            combine(
                budgetRepository.getBudgetStatuses(),
                flow { emit(budgetRepository.getSuggestions()) }
            ) { statuses, suggestions ->
                BudgetUiState(
                    budgets = statuses,
                    suggestions = suggestions,
                    isLoading = false
                )
            }.catch { e ->
                _uiState.emit(BudgetUiState(error = e.message, isLoading = false))
            }.collect {
                _uiState.emit(it)
            }
        }
    }

    fun addBudget(budget: Budget) {
        if (!validateThresholds(budget)) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val result = budgetRepository.addBudget(budget)
            when (result) {
                is com.yourname.expensetracker.domain.model.Result.Success -> {
                    _uiState.update { it.copy(isLoading = false) }
                }
                is com.yourname.expensetracker.domain.model.Result.Error -> {
                    _uiState.update { it.copy(isLoading = false, error = result.message) }
                }
                else -> {}
            }
        }
    }

    fun updateBudget(budget: Budget) {
        if (!validateThresholds(budget)) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val result = budgetRepository.updateBudget(budget)
            when (result) {
                is com.yourname.expensetracker.domain.model.Result.Success -> {
                    _uiState.update { it.copy(isLoading = false) }
                }
                is com.yourname.expensetracker.domain.model.Result.Error -> {
                    _uiState.update { it.copy(isLoading = false, error = result.message) }
                }
                else -> {}
            }
        }
    }

    private fun validateThresholds(budget: Budget): Boolean {
        if (budget.notifyAtWarning <= 0f || budget.notifyAtWarning >= 1f) {
            _uiState.update { it.copy(error = "Warning threshold must be between 0 and 1") }
            return false
        }
        if (budget.notifyAtCritical <= budget.notifyAtWarning || budget.notifyAtCritical >= 1.05f) {
            _uiState.update { it.copy(error = "Critical threshold must be between warning and 100%") }
            return false
        }
        return true
    }

    fun deleteBudget(budget: Budget) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val result = budgetRepository.deleteBudget(budget)
             when (result) {
                is com.yourname.expensetracker.domain.model.Result.Success -> {
                    _uiState.update { it.copy(isLoading = false) }
                }
                is com.yourname.expensetracker.domain.model.Result.Error -> {
                    _uiState.update { it.copy(isLoading = false, error = result.message) }
                }
                else -> {}
            }
        }
    }

    fun toggleBudget(id: Long, isActive: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
             val result = budgetRepository.toggleBudget(id, isActive)
             when (result) {
                is com.yourname.expensetracker.domain.model.Result.Success -> {
                    _uiState.update { it.copy(isLoading = false) }
                }
                is com.yourname.expensetracker.domain.model.Result.Error -> {
                    _uiState.update { it.copy(isLoading = false, error = result.message) }
                }
                else -> {}
            }
        }
    }

    fun refreshSuggestions() {
        viewModelScope.launch {
            val suggestions = budgetRepository.getSuggestions()
            _uiState.update { it.copy(suggestions = suggestions) }
        }
    }
}
