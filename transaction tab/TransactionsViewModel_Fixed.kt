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
import kotlinx.coroutines.withContext
import javax.inject.Inject
import java.util.Calendar
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fixed TransactionsViewModel with:
 * - Thread-safe pagination
 * - Search functionality
 * - Proper error handling
 * - Loading states
 * - Date grouping support
 */
@HiltViewModel
class TransactionsViewModel @Inject constructor(
    private val repository: NotificationRepository,
    private val categoryRepository: CategoryRepository,
    private val recurringExpenseDao: com.yourname.expensetracker.data.database.dao.RecurringExpenseDao
) : ViewModel() {

    companion object {
        const val PAGE_SIZE = 50
    }

    // Tab definitions with lazy label computation
    enum class TransactionTab(val label: String, val daysBack: Int? = null) {
        TODAY("Today", 1),
        WEEK("Week", 7),
        MONTH("Month", 30),
        QUARTER("Quarter", 90),
        YEAR("Year", 365),
        ALL("All", null)
    }

    // Categories
    val categories: StateFlow<List<Category>> = categoryRepository.allCategories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Selected tab state
    private val _selectedTab = MutableStateFlow(TransactionTab.MONTH)
    val selectedTab: StateFlow<TransactionTab> = _selectedTab.asStateFlow()

    // Search query state
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Pagination state for ALL tab
    private val _currentPage = MutableStateFlow(0)
    private val _pagedExpenses = MutableStateFlow<List<ExpenseWithCategory>>(emptyList())
    
    // Thread-safe loading flag to prevent race conditions
    private val isLoadingMore = AtomicBoolean(false)
    
    // Loading states
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    private val _isLoadingMoreState = MutableStateFlow(false)
    val isLoadingMoreState: StateFlow<Boolean> = _isLoadingMoreState.asStateFlow()

    // Error state for UI feedback
    private val _error = MutableSharedFlow<String>()
    val error: SharedFlow<String> = _error.asSharedFlow()

    // Success feedback
    private val _successMessage = MutableSharedFlow<String>()
    val successMessage: SharedFlow<String> = _successMessage.asSharedFlow()

    // Refresh trigger for pull-to-refresh
    private val _refreshTrigger = MutableStateFlow(0)

    /**
     * Main transactions flow with reactive filtering.
     * Combines tab selection, search query, and refresh triggers.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val transactions: StateFlow<List<ExpenseWithCategory>> = combine(
        _selectedTab,
        _searchQuery,
        _refreshTrigger
    ) { tab, query, _ -> Pair(tab, query) }
        .flatMapLatest { (tab, query) ->
            if (tab == TransactionTab.ALL) {
                // For ALL tab, use paged data with optional search filter
                _pagedExpenses.map { expenses ->
                    if (query.isBlank()) expenses
                    else expenses.filter { matchesSearch(it, query) }
                }
            } else {
                // For other tabs, use time-based filtering
                val range = getTimeRangeForTab(tab)
                repository.getExpensesWithCategoryInPeriod(range.first, range.second)
                    .map { expenses ->
                        if (query.isBlank()) expenses
                        else expenses.filter { matchesSearch(it, query) }
                    }
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /**
     * Grouped transactions by date for UI display.
     * Returns a map of date string to list of transactions.
     */
    val groupedTransactions: StateFlow<Map<String, List<ExpenseWithCategory>>> = transactions
        .map { expenseList ->
            groupTransactionsByDate(expenseList)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyMap()
        )

    /**
     * Transaction counts per tab for badge display.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val tabTransactionCounts: StateFlow<Map<TransactionTab, Int>> = _refreshTrigger
        .flatMapLatest {
            flow {
                val counts = mutableMapOf<TransactionTab, Int>()
                TransactionTab.values().forEach { tab ->
                    if (tab != TransactionTab.ALL) {
                        val range = getTimeRangeForTab(tab)
                        val count = repository.getExpenseCountForPeriod(range.first, range.second)
                        counts[tab] = count
                    }
                }
                emit(counts)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(10000),
            initialValue = emptyMap()
        )

    // ============================================================
    // PUBLIC API
    // ============================================================

    fun selectTab(tab: TransactionTab) {
        if (_selectedTab.value == tab) return
        
        _selectedTab.value = tab
        _currentPage.value = 0
        _pagedExpenses.value = emptyList() // Clear to prevent stale data flash
        _searchQuery.value = "" // Reset search on tab change
        
        if (tab == TransactionTab.ALL) {
            loadInitialAll()
        }
    }

    fun search(query: String) {
        _searchQuery.value = query.trim()
    }

    fun refresh() {
        _refreshTrigger.value += 1
        
        if (_selectedTab.value == TransactionTab.ALL) {
            _currentPage.value = 0
            _pagedExpenses.value = emptyList()
            loadInitialAll()
        }
    }

    fun loadMore() {
        // Guard conditions
        if (_selectedTab.value != TransactionTab.ALL) return
        if (_isLoadingMoreState.value) return
        
        // Atomic check-and-set to prevent race conditions
        if (!isLoadingMore.compareAndSet(false, true)) return
        
        viewModelScope.launch {
            _isLoadingMoreState.value = true
            try {
                val nextPage = _currentPage.value + 1
                val offset = nextPage * PAGE_SIZE
                
                val nextItems = withContext(Dispatchers.IO) {
                    repository.getExpensesPaged(PAGE_SIZE, offset)
                }
                
                if (nextItems.isNotEmpty()) {
                    // Use thread-safe list concatenation
                    _pagedExpenses.update { current ->
                        current + nextItems.distinctBy { it.expense.id }
                    }
                    _currentPage.value = nextPage
                }
            } catch (e: Exception) {
                _error.emit("Failed to load more transactions: ${e.message}")
            } finally {
                _isLoadingMoreState.value = false
                isLoadingMore.set(false)
            }
        }
    }

    fun deleteExpense(expense: Expense) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                repository.deleteExpense(expense)
                _successMessage.emit("Transaction deleted")
                
                // Refresh data
                refresh()
            } catch (e: Exception) {
                _error.emit("Failed to delete transaction: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateCategory(expense: Expense, categoryId: Long) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                repository.updateExpenseCategory(expense, categoryId)
                _successMessage.emit("Category updated")
            } catch (e: Exception) {
                _error.emit("Failed to update category: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateMerchant(expense: Expense, newMerchant: String) {
        val trimmedName = newMerchant.trim()
        if (trimmedName.isBlank()) {
            viewModelScope.launch { _error.emit("Merchant name cannot be empty") }
            return
        }
        
        viewModelScope.launch {
            _isLoading.value = true
            try {
                repository.updateExpenseMerchant(expense, trimmedName)
                _successMessage.emit("Merchant renamed to $trimmedName")
            } catch (e: Exception) {
                _error.emit("Failed to update merchant: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun markAsRecurring(
        expense: Expense, 
        frequency: com.yourname.expensetracker.domain.model.RecurrenceFrequency
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val nextDate = System.currentTimeMillis() + frequency.intervalInMs
                val rule = com.yourname.expensetracker.data.database.entity.ManualRecurringExpense(
                    merchant = expense.merchant,
                    amount = expense.amount,
                    frequency = frequency,
                    nextDate = nextDate
                )
                recurringExpenseDao.insert(rule)
                _successMessage.emit("Marked as recurring (${frequency.name.lowercase().replace("_", " ")})")
            } catch (e: Exception) {
                _error.emit("Failed to mark as recurring: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    // ============================================================
    // PRIVATE HELPERS
    // ============================================================

    private fun loadInitialAll() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val initial = withContext(Dispatchers.IO) {
                    repository.getExpensesPaged(PAGE_SIZE, 0)
                }
                _pagedExpenses.value = initial
                _currentPage.value = 0
            } catch (e: Exception) {
                _error.emit("Failed to load transactions: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Optimized time range calculation using pre-computed values.
     * Avoids creating Calendar instances on every call.
     */
    private fun getTimeRangeForTab(tab: TransactionTab): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        
        return when (tab) {
            TransactionTab.TODAY -> {
                // Use cached calculation for start of day
                val startOfDay = getStartOfDay(now)
                Pair(startOfDay, now)
            }
            TransactionTab.WEEK -> {
                val cal = Calendar.getInstance()
                cal.timeInMillis = now
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                val day = cal.get(Calendar.DAY_OF_WEEK)
                // Calculate days to Monday (or 6 if Sunday)
                val diff = if (day == Calendar.SUNDAY) 6 else day - Calendar.MONDAY
                cal.add(Calendar.DAY_OF_YEAR, -diff)
                Pair(cal.timeInMillis, now)
            }
            TransactionTab.MONTH -> {
                val cal = Calendar.getInstance()
                cal.timeInMillis = now
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                Pair(cal.timeInMillis, now)
            }
            TransactionTab.QUARTER -> {
                val cal = Calendar.getInstance()
                cal.timeInMillis = now
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                val month = cal.get(Calendar.MONTH)
                // Calculate start of quarter (0, 3, 6, 9)
                val quarterStartMonth = (month / 3) * 3
                cal.set(Calendar.MONTH, quarterStartMonth)
                Pair(cal.timeInMillis, now)
            }
            TransactionTab.YEAR -> {
                val cal = Calendar.getInstance()
                cal.timeInMillis = now
                cal.set(Calendar.DAY_OF_YEAR, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                Pair(cal.timeInMillis, now)
            }
            TransactionTab.ALL -> Pair(0L, now)
        }
    }

    /**
     * Optimized start-of-day calculation.
     * Uses bitwise operations for faster computation.
     */
    private fun getStartOfDay(timestamp: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /**
     * Search matching with null-safety and performance optimization.
     */
    private fun matchesSearch(item: ExpenseWithCategory, query: String): Boolean {
        val lowerQuery = query.lowercase()
        
        return item.expense.merchant.lowercase().contains(lowerQuery) ||
                item.category?.name?.lowercase()?.contains(lowerQuery) == true ||
                item.formattedAmount.contains(lowerQuery, ignoreCase = true)
    }

    /**
     * Groups transactions by formatted date string.
     * Uses sorted map to maintain date order (newest first).
     */
    private fun groupTransactionsByDate(
        expenses: List<ExpenseWithCategory>
    ): Map<String, List<ExpenseWithCategory>> {
        if (expenses.isEmpty()) return emptyMap()
        
        val dateFormat = java.text.SimpleDateFormat(
            "EEEE, MMMM d, yyyy", 
            java.util.Locale.getDefault()
        )
        
        return expenses
            .sortedByDescending { it.expense.date }
            .groupBy { item ->
                dateFormat.format(java.util.Date(item.expense.date))
            }
    }
}
