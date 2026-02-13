package com.yourname.expensetracker.ui.screens.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.NotificationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import com.yourname.expensetracker.data.database.model.ExpenseWithCategory
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    enum class TransactionTab(val label: String) {
        TODAY("Today"),
        WEEK("Week"),
        MONTH("Month"),
        QUARTER("Quarter"),
        YEAR("Year"),
        ALL("All")
    }

    val categories: StateFlow<List<Category>> = categoryRepository.allCategories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedTab = MutableStateFlow(TransactionTab.MONTH)
    val selectedTab: StateFlow<TransactionTab> = _selectedTab.asStateFlow()

    private val _currentPage = MutableStateFlow(0)
    private val PAGE_SIZE = 50
    
    // For pagination, we'll manually append for TransactionTab.ALL
    private val _pagedExpenses = MutableStateFlow<List<ExpenseWithCategory>>(emptyList())

    fun selectTab(tab: TransactionTab) {
        _selectedTab.value = tab
        _currentPage.value = 0
        if (tab == TransactionTab.ALL) {
            loadInitialAll()
        }
    }

    private fun loadInitialAll() {
        viewModelScope.launch {
            val initial = repository.getExpensesPaged(PAGE_SIZE, 0)
            _pagedExpenses.value = initial
        }
    }

    fun loadMore() {
        if (_selectedTab.value != TransactionTab.ALL) return
        
        viewModelScope.launch {
            val nextPage = _currentPage.value + 1
            val nextItems = repository.getExpensesPaged(PAGE_SIZE, nextPage * PAGE_SIZE)
            if (nextItems.isNotEmpty()) {
                _pagedExpenses.value = _pagedExpenses.value + nextItems
                _currentPage.value = nextPage
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val transactions: StateFlow<List<ExpenseWithCategory>> = _selectedTab
        .flatMapLatest { tab ->
            if (tab == TransactionTab.ALL) {
                _pagedExpenses
            } else {
                val range = getRangeForTab(tab)
                repository.getExpensesWithCategoryInPeriod(range.first, range.second)
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private fun getRangeForTab(tab: TransactionTab): Pair<Long, Long> {
        val cal = java.util.Calendar.getInstance()
        val now = System.currentTimeMillis()
        cal.timeInMillis = now

        return when (tab) {
            TransactionTab.TODAY -> {
                cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
                cal.set(java.util.Calendar.MINUTE, 0)
                cal.set(java.util.Calendar.SECOND, 0)
                cal.set(java.util.Calendar.MILLISECOND, 0)
                Pair(cal.timeInMillis, now)
            }
            TransactionTab.WEEK -> {
                cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
                cal.set(java.util.Calendar.MINUTE, 0)
                cal.set(java.util.Calendar.SECOND, 0)
                cal.set(java.util.Calendar.MILLISECOND, 0)
                val day = cal.get(java.util.Calendar.DAY_OF_WEEK)
                val diff = if (day == java.util.Calendar.SUNDAY) 6 else day - java.util.Calendar.MONDAY
                cal.add(java.util.Calendar.DAY_OF_YEAR, -diff)
                Pair(cal.timeInMillis, now)
            }
            TransactionTab.MONTH -> {
                cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
                cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
                cal.set(java.util.Calendar.MINUTE, 0)
                cal.set(java.util.Calendar.SECOND, 0)
                cal.set(java.util.Calendar.MILLISECOND, 0)
                Pair(cal.timeInMillis, now)
            }
            TransactionTab.QUARTER -> {
                cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
                cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
                cal.set(java.util.Calendar.MINUTE, 0)
                cal.set(java.util.Calendar.SECOND, 0)
                cal.set(java.util.Calendar.MILLISECOND, 0)
                val month = cal.get(java.util.Calendar.MONTH)
                cal.set(java.util.Calendar.MONTH, month - (month % 3))
                Pair(cal.timeInMillis, now)
            }
            TransactionTab.YEAR -> {
                cal.set(java.util.Calendar.DAY_OF_YEAR, 1)
                cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
                cal.set(java.util.Calendar.MINUTE, 0)
                cal.set(java.util.Calendar.SECOND, 0)
                cal.set(java.util.Calendar.MILLISECOND, 0)
                Pair(cal.timeInMillis, now)
            }
            TransactionTab.ALL -> Pair(0L, now)
        }
    }

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

    fun updateMerchant(expense: Expense, newMerchant: String) {
        viewModelScope.launch {
            repository.updateExpenseMerchant(expense, newMerchant)
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
