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
        viewModelScope.launch {
            budgetRepository.addBudget(budget)
        }
    }

    fun updateBudget(budget: Budget) {
        viewModelScope.launch {
            budgetRepository.updateBudget(budget)
        }
    }

    fun deleteBudget(budget: Budget) {
        viewModelScope.launch {
            budgetRepository.deleteBudget(budget)
        }
    }

    fun toggleBudget(id: Long, isActive: Boolean) {
        viewModelScope.launch {
            budgetRepository.toggleBudget(id, isActive)
        }
    }

    fun refreshSuggestions() {
        viewModelScope.launch {
            val suggestions = budgetRepository.getSuggestions()
            _uiState.update { it.copy(suggestions = suggestions) }
        }
    }
}
