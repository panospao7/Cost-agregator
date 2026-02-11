package com.yourname.expensetracker.ui.screens.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.NotificationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import com.yourname.expensetracker.data.database.model.ExpenseWithCategory
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TransactionsViewModel @Inject constructor(
    private val repository: NotificationRepository,
    private val categoryRepository: CategoryRepository,
    private val recurringExpenseDao: com.yourname.expensetracker.data.database.dao.RecurringExpenseDao
) : ViewModel() {
    
    val categories: StateFlow<List<Category>> = categoryRepository.allCategories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val transactions: StateFlow<List<ExpenseWithCategory>> = repository
        .getExpensesWithCategory(limit = 200)
        .flowOn(Dispatchers.Default)
        .stateIn(
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

    fun markAsRecurring(expense: Expense, frequency: com.yourname.expensetracker.domain.model.RecurrenceFrequency) {
        viewModelScope.launch {
            val rule = com.yourname.expensetracker.data.database.entity.ManualRecurringExpense(
                merchant = expense.merchant,
                amount = expense.amount,
                frequency = frequency,
                nextDate = System.currentTimeMillis() + frequency.intervalInMs
            )
            recurringExpenseDao.insert(rule)
        }
    }
}
