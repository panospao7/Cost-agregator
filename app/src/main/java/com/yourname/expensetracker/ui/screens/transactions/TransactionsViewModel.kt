package com.yourname.expensetracker.ui.screens.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.TransferDirection
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.NotificationRepository
import com.yourname.expensetracker.data.repository.OwnershipFilter
import com.yourname.expensetracker.data.repository.SortOrder
import dagger.hilt.android.lifecycle.HiltViewModel
import com.yourname.expensetracker.data.database.model.ExpenseWithCategory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.util.DateFormatterUtils
import javax.inject.Inject
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
    private val repository: com.yourname.expensetracker.data.repository.NotificationRepository,
    private val expenseRepository: com.yourname.expensetracker.data.repository.ExpenseRepository,
    private val categoryRepository: com.yourname.expensetracker.data.repository.CategoryRepository,
    private val recurringExpenseRepository: com.yourname.expensetracker.data.repository.RecurringExpenseRepository,
    private val merchantLocationRepository: com.yourname.expensetracker.data.repository.MerchantLocationRepository,
    private val timeProvider: com.yourname.expensetracker.domain.util.TimeProvider,
    val geocodingService: com.yourname.expensetracker.domain.location.GeocodingService
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

    enum class OwnershipFilter(val label: String) {
        ALL("All"),
        MINE("Mine only"),
        NOT_MINE("Not mine"),
        SHARED("Shared"),
        TRANSFER("Transfers")
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

    // Ownership filter state
    private val _ownershipFilter = MutableStateFlow(OwnershipFilter.ALL)
    val ownershipFilter: StateFlow<OwnershipFilter> = _ownershipFilter.asStateFlow()

    // Sort order state
    private val _sortOrder = MutableStateFlow(SortOrder.DATE_DESC)
    val sortOrder: StateFlow<SortOrder> = _sortOrder.asStateFlow()

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

    // Filter state for drill-down
    private val _filter = MutableStateFlow<TransactionFilter?>(null)
    val filter: StateFlow<TransactionFilter?> = _filter.asStateFlow()

    /**
     * Main transactions flow with reactive filtering.
     * Combines tab selection, search query, filter, ownership filter, and refresh triggers.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val transactions: StateFlow<List<ExpenseWithCategory>> = combine(
        _selectedTab,
        _searchQuery,
        _filter,
        _ownershipFilter,
        _refreshTrigger
    ) { tab, query, filter, ownership, _ -> 
        FilterParams(tab, query, filter, ownership) 
    }
        .flatMapLatest { params ->
            val baseExpenses = if (params.filter != null) {
                val (start, end) = params.filter.dateRange ?: Pair(0L, timeProvider.now())
                
                expenseRepository.getExpensesWithCategoryFiltered(
                    startMs = start,
                    endMs = end,
                    type = params.filter.transactionType,
                    categoryId = params.filter.categoryId,
                    merchant = params.filter.merchantName
                )
            } else if (params.tab == TransactionTab.ALL) {
                _pagedExpenses
            } else {
                val range = getTimeRangeForTab(params.tab)
                expenseRepository.getExpensesWithCategoryInPeriod(range.first, range.second)
            }

            baseExpenses.map { expenses ->
                var filtered = expenses
                if (params.query.isNotBlank()) {
                    filtered = filtered.filter { matchesSearch(it, params.query) }
                }
                filtered = filterByOwnership(filtered, params.ownership)
                filtered
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private data class FilterParams(
        val tab: TransactionTab,
        val query: String,
        val filter: TransactionFilter?,
        val ownership: OwnershipFilter
    )

    /**
     * Grouped transactions by date for UI display.
     * Returns a map of date string to list of transactions.
     */
    val groupedTransactions: StateFlow<Map<String, List<ExpenseWithCategory>>> = combine(transactions, _sortOrder) { expenseList, order ->
        val sortedList = if (_selectedTab.value == TransactionTab.ALL) {
            // Already sorted and filtered via backend pagination
            expenseList
        } else {
            // Needs sorting in-memory
            when (order) {
                SortOrder.DATE_DESC -> expenseList.sortedByDescending { it.expense.date }
                SortOrder.DATE_ASC -> expenseList.sortedBy { it.expense.date }
                SortOrder.AMOUNT_DESC -> expenseList.sortedByDescending { it.expense.amount }
                SortOrder.AMOUNT_ASC -> expenseList.sortedBy { it.expense.amount }
            }
        }

        if (order == SortOrder.AMOUNT_DESC || order == SortOrder.AMOUNT_ASC) {
            mapOf("Sorted By Amount" to sortedList)
        } else {
            groupTransactionsByDate(sortedList)
        }
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
                        val count = expenseRepository.getCountForPeriod(range.first, range.second)
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

    // ============================================================
    // PUBLIC API
    // ============================================================

    fun applyFilter(filter: TransactionFilter) {
        _filter.value = filter
        // We might want to switch tab visual state or specific logic here if needed
    }

    fun clearFilter() {
        _filter.value = null
    }

    fun selectTab(tab: TransactionTab) {
        if (_selectedTab.value == tab) return
        
        _selectedTab.value = tab
        _currentPage.value = 0
        _pagedExpenses.value = emptyList() // Clear to prevent stale data flash
        _searchQuery.value = "" // Reset search on tab change
        _filter.value = null // Clear filter when manually changing tabs
        
        if (tab == TransactionTab.ALL) {
            loadInitialAll()
        }
    }

    fun search(query: String) {
        _searchQuery.value = query.trim()
        if (_selectedTab.value == TransactionTab.ALL) {
            _currentPage.value = 0
            _pagedExpenses.value = emptyList()
            loadInitialAll()
        }
    }

    fun setSortOrder(order: SortOrder) {
        _sortOrder.value = order
        if (_selectedTab.value == TransactionTab.ALL) {
            _currentPage.value = 0
            _pagedExpenses.value = emptyList()
            loadInitialAll()
        }
    }

    fun setOwnershipFilter(filter: OwnershipFilter) {
        _ownershipFilter.value = filter
        if (_selectedTab.value == TransactionTab.ALL) {
            _currentPage.value = 0
            _pagedExpenses.value = emptyList()
            loadInitialAll()
        }
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
                    expenseRepository.getExpensesPagedDynamic(
                        limit = PAGE_SIZE,
                        offset = offset,
                        searchQuery = _searchQuery.value.takeIf { it.isNotBlank() },
                        ownershipFilter = mapOwnershipFilter(_ownershipFilter.value),
                        sortOrder = _sortOrder.value
                    )
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
                expenseRepository.deleteExpense(expense)
                _successMessage.emit("Transaction deleted")
                refresh()
            } catch (e: Exception) {
                _error.emit("Failed to delete transaction: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateCategory(expense: Expense, categoryId: Long, applyToAll: Boolean = false) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                if (applyToAll) {
                    expenseRepository.updateExpenseCategoryBulk(expense.merchant, categoryId)
                    _successMessage.emit("Category updated for all ${expense.merchant} transactions")
                } else {
                    expenseRepository.updateExpenseCategory(expense, categoryId)
                    _successMessage.emit("Category updated")
                }
            } catch (e: Exception) {
                _error.emit("Failed to update category: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateMerchant(expense: Expense, newMerchant: String, applyToAll: Boolean = false) {
    val trimmedName = newMerchant.trim()
    if (trimmedName.isBlank()) {
        viewModelScope.launch { _error.emit("Merchant name cannot be empty") }
        return
    }
    
    viewModelScope.launch {
        _isLoading.value = true
        try {
            expenseRepository.updateExpenseMerchant(expense, trimmedName, applyToAll)
            val message = if (applyToAll) "Merchant renamed to $trimmedName globally" else "Merchant renamed to $trimmedName"
            _successMessage.emit(message)
        } catch (e: Exception) {
            _error.emit("Failed to update merchant: ${e.message}")
        } finally {
            _isLoading.value = false
        }
    }
}
    fun updateExpenseType(expense: Expense, newType: TransactionType) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                expenseRepository.updateExpenseType(expense, newType)
                _successMessage.emit("Type changed to ${newType.name}")
            } catch (e: Exception) {
                _error.emit("Failed to update type: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateTransferDetails(
        expense: Expense,
        transferDirection: TransferDirection?,
        transferAccountName: String
    ) {
        viewModelScope.launch {
            try {
                expenseRepository.updateTransferDetails(expense, transferDirection, transferAccountName.takeIf { it.isNotBlank() })
                _successMessage.emit("Transfer details updated")
            } catch (e: Exception) {
                _error.emit("Failed to update: ${e.message}")
            }
        }
    }

    fun updateNotMineDetails(expense: Expense, isNotMine: Boolean, ownerName: String) {
        viewModelScope.launch {
            try {
                expenseRepository.updateNotMineDetails(expense, isNotMine, ownerName.takeIf { it.isNotBlank() })
                _successMessage.emit(if (isNotMine) "Marked as not mine" else "Marked as mine")
            } catch (e: Exception) {
                _error.emit("Failed to update: ${e.message}")
            }
        }
    }

    fun updateSharedExpenseDetails(
        expense: Expense,
        isSharedExpense: Boolean,
        sharedWithName: String,
        mySharePercentage: String,
        myShareAmount: String
    ) {
        viewModelScope.launch {
            try {
                expenseRepository.updateSharedExpenseDetails(
                    expense = expense,
                    isSharedExpense = isSharedExpense,
                    sharedWithName = sharedWithName.takeIf { it.isNotBlank() },
                    mySharePercentage = mySharePercentage.toIntOrNull(),
                    myShareAmount = myShareAmount.toDoubleOrNull()
                )
                _successMessage.emit(if (isSharedExpense) "Marked as shared expense" else "Unmarked shared expense")
            } catch (e: Exception) {
                _error.emit("Failed to update: ${e.message}")
            }
        }
    }

    fun updateLocation(expense: Expense, lat: Double, lon: Double, address: String?, osmId: String?) {
        viewModelScope.launch {
            try {
                // B18 fix: use AppConfig constant instead of hardcoded string
                val source = com.yourname.expensetracker.domain.config.AppConfig.Location.SOURCE_USER_MANUAL
                expenseRepository.updateExpenseLocation(
                    expenseId = expense.id,
                    latitude = lat,
                    longitude = lon,
                    source = source,
                    placeId = osmId,
                    address = address
                )
                // B14 fix: also save to merchant location cache so future expenses
                // for the same merchant benefit from this correction, consistent
                // with SpendingMapViewModel.onSaveCorrection().
                val correction = com.yourname.expensetracker.data.database.entity.MerchantLocationCorrection(
                    normalizedMerchantName = merchantLocationRepository.normalizeKey(expense.merchant),
                    correctedLatitude = lat,
                    correctedLongitude = lon,
                    // B15 note: use the corrected location itself as the area center
                    // (the user chose this location for this merchant at this place)
                    areaLatitude = lat,
                    areaLongitude = lon,
                    osmId = osmId,
                    displayAddress = address
                )
                merchantLocationRepository.saveCorrection(correction)
                _successMessage.emit("Location saved")
                refresh()
            } catch (e: Exception) {
                _error.emit("Failed to save location: ${e.message}")
            }
        }
    }

    fun clearLocation(expense: Expense) {
        viewModelScope.launch {
            try {
                expenseRepository.clearExpenseLocation(expense.id)
                _successMessage.emit("Location cleared")
                refresh()
            } catch (e: Exception) {
                _error.emit("Failed to clear location: ${e.message}")
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
                recurringExpenseRepository.addRecurringExpense(
                    merchant = expense.merchant,
                    amount = expense.amount,
                    frequency = frequency,
                    lastDate = timeProvider.now(),
                    currency = "EUR"
                )
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
                    expenseRepository.getExpensesPagedDynamic(
                        limit = PAGE_SIZE,
                        offset = 0,
                        searchQuery = _searchQuery.value.takeIf { it.isNotBlank() },
                        ownershipFilter = mapOwnershipFilter(_ownershipFilter.value),
                        sortOrder = _sortOrder.value
                    )
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

    private fun mapOwnershipFilter(filter: OwnershipFilter): com.yourname.expensetracker.data.repository.OwnershipFilter {
        return when (filter) {
            OwnershipFilter.ALL -> com.yourname.expensetracker.data.repository.OwnershipFilter.ALL
            OwnershipFilter.MINE -> com.yourname.expensetracker.data.repository.OwnershipFilter.MINE
            OwnershipFilter.NOT_MINE -> com.yourname.expensetracker.data.repository.OwnershipFilter.NOT_MINE
            OwnershipFilter.SHARED -> com.yourname.expensetracker.data.repository.OwnershipFilter.SHARED
            OwnershipFilter.TRANSFER -> com.yourname.expensetracker.data.repository.OwnershipFilter.TRANSFER
        }
    }

    /**
     * Optimized time range calculation using TimePeriodUtils.
     */
    private fun getTimeRangeForTab(tab: TransactionTab): Pair<Long, Long> {
        val now = timeProvider.now()
        
        return when (tab) {
            TransactionTab.TODAY -> {
                val startOfDay = com.yourname.expensetracker.domain.util.TimePeriodUtils.getStartOfDay(now)
                Pair(startOfDay, now)
            }
            TransactionTab.WEEK -> com.yourname.expensetracker.domain.util.TimePeriodUtils.getLastNDaysRange(now, 7)
            TransactionTab.MONTH -> com.yourname.expensetracker.domain.util.TimePeriodUtils.getMonthRange(now, 0)
            TransactionTab.QUARTER -> {
                val startOfQuarter = com.yourname.expensetracker.domain.util.TimePeriodUtils.getStartOfQuarter(now)
                Pair(startOfQuarter, now)
            }
            TransactionTab.YEAR -> {
                val startOfYear = com.yourname.expensetracker.domain.util.TimePeriodUtils.getStartOfYear(now)
                Pair(startOfYear, now)
            }
            TransactionTab.ALL -> Pair(0L, now)
        }
    }

    /**
     * Search matching with null-safety and performance optimization.
     */
    private fun matchesSearch(item: ExpenseWithCategory, query: String): Boolean {
        val lowerQuery = query.lowercase()
        
        return item.expense.merchant.lowercase().contains(lowerQuery) ||
                item.category?.name?.lowercase()?.contains(lowerQuery) == true
    }

    /**
     * Filter expenses by ownership type.
     */
    private fun filterByOwnership(
        expenses: List<ExpenseWithCategory>,
        filter: OwnershipFilter
    ): List<ExpenseWithCategory> {
        return when (filter) {
            OwnershipFilter.ALL -> expenses
            OwnershipFilter.MINE -> expenses.filter { !it.expense.isNotMine }
            OwnershipFilter.NOT_MINE -> expenses.filter { it.expense.isNotMine }
            OwnershipFilter.SHARED -> expenses.filter { it.expense.isSharedExpense }
            OwnershipFilter.TRANSFER -> expenses.filter { it.expense.transactionType == TransactionType.TRANSFER }
        }
    }

    /**
     * Groups transactions by formatted date string.
     * Uses sorted map to maintain date order (newest first).
     */
    private fun groupTransactionsByDate(
        expenses: List<ExpenseWithCategory>
    ): Map<String, List<ExpenseWithCategory>> {
        if (expenses.isEmpty()) return emptyMap()
        
        return expenses
            .groupBy { item ->
                DateFormatterUtils.fullDateWithDay().format(java.util.Date(item.expense.date))
            }
    }
}
