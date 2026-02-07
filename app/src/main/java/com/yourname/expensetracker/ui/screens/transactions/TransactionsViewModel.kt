package com.yourname.expensetracker.ui.screens.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.NotificationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ExpenseWithCategory(
    val expense: Expense,
    val category: Category?
)

@HiltViewModel
class TransactionsViewModel @Inject constructor(
    private val repository: NotificationRepository,
    private val categoryRepository: CategoryRepository
) : ViewModel() {
    
    val categories: StateFlow<List<Category>> = categoryRepository.allCategories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val transactions: StateFlow<List<ExpenseWithCategory>> = combine(
        repository.getAllExpenses(),
        categoryRepository.allCategories
    ) { expenses, categories ->
        val categoryMap = categories.associateBy { it.id }
        expenses.map { expense ->
            ExpenseWithCategory(
                expense = expense,
                category = expense.categoryId?.let { categoryMap[it] }
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    fun deleteExpense(expense: Expense) {
        viewModelScope.launch {
            repository.deleteExpense(expense)
        }
    }

    fun updateCategory(expense: Expense, categoryId: Long) {
        viewModelScope.launch {
            repository.updateExpenseCategory(expense, categoryId)
        }
    }
}
