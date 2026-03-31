package com.yourname.expensetracker.ui.screens.recurringmanual

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.dao.ManualRecurringExpenseDao
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.domain.logic.RecurrenceCalculator
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale
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
    val upcomingCount: Int = 0
)

@HiltViewModel
class ManualRecurringExpenseViewModel @Inject constructor(
    private val dao: ManualRecurringExpenseDao
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(ManualRecurringExpenseUiState())
    val uiState: StateFlow<ManualRecurringExpenseUiState> = _uiState.asStateFlow()
    
    init {
        loadRecurringExpenses()
    }
    
    private fun loadRecurringExpenses() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            
            try {
                val expenses = dao.getAll()
                val activeExpenses = expenses.filter { it.isActive }
                
                // Calculate monthly total
                val totalMonthly = activeExpenses.sumOf { expense ->
                    calculateMonthlyAmount(expense.amount, expense.frequency)
                }
                
                // Count upcoming (next 7 days)
                val oneWeekFromNow = System.currentTimeMillis() + (7 * 24 * 60 * 60 * 1000)
                val upcomingCount = activeExpenses.count { it.nextDate <= oneWeekFromNow }
                
                _uiState.value = ManualRecurringExpenseUiState(
                    recurringExpenses = expenses.sortedBy { it.nextDate },
                    isLoading = false,
                    error = null,
                    totalMonthly = totalMonthly,
                    activeCount = activeExpenses.size,
                    upcomingCount = upcomingCount
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to load recurring expenses: ${e.message}"
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
            try {
                val expense = ManualRecurringExpense(
                    merchant = merchant,
                    amount = amount,
                    frequency = frequency,
                    nextDate = nextDate,
                    note = note,
                    isSubscription = false,
                    isActive = true
                )
                dao.insert(expense)
                loadRecurringExpenses()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to add expense: ${e.message}"
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
                dao.setActiveStatus(id, !currentStatus)
                loadRecurringExpenses()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to update status: ${e.message}"
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
                dao.deleteById(id)
                loadRecurringExpenses()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to delete: ${e.message}"
                )
            }
        }
    }
    
    /**
     * Mark expense as paid and update next date.
     */
    fun markAsPaid(expense: ManualRecurringExpense) {
        viewModelScope.launch {
            try {
                val nextDate = calculateNextDate(expense.nextDate, expense.frequency)
                dao.updateNextDate(expense.id, nextDate)
                loadRecurringExpenses()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to update: ${e.message}"
                )
            }
        }
    }
    
    private fun calculateNextDate(currentDate: Long, frequency: RecurrenceFrequency): Long {
        return RecurrenceCalculator.calculateNextDate(currentDate, frequency)
    }
    
    fun refresh() {
        loadRecurringExpenses()
    }
    
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}