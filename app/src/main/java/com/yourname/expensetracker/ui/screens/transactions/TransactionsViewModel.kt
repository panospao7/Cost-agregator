package com.yourname.expensetracker.ui.screens.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.TransferDirection
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.NotificationRepository
import com.yourname.expensetracker.data.repository.OwnershipFilter as RepositoryOwnershipFilter
import com.yourname.expensetracker.data.repository.SortOrder
import dagger.hilt.android.lifecycle.HiltViewModel
import com.yourname.expensetracker.data.database.model.ExpenseWithCategory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.util.DateFormatterUtils
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import javax.inject.Inject

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

    // Filter state for drill-down
    private val _filter = MutableStateFlow<TransactionFilter?>(null)
    val filter: StateFlow<TransactionFilter?> = _filter.asStateFlow()

    val ownershipFilter: StateFlow<OwnershipFilter> = _filter
        .map { toUiOwnershipFilter(it?.ownership) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), OwnershipFilter.ALL)

    // Sort order state
    private val _sortOrder = MutableStateFlow(SortOrder.DATE_DESC)
    val sortOrder: StateFlow<SortOrder> = _sortOrder.asStateFlow()

    // Pagination state for ALL tab
    private val _currentPage = MutableStateFlow(0)
    private val _pagedExpenses = MutableStateFlow<List<ExpenseWithCategory>>(emptyList())
    private val _hasReachedEnd = MutableStateFlow(false)
    val hasReachedEnd: StateFlow<Boolean> = _hasReachedEnd.asStateFlow()
    private var loadInitialAllJob: Job? = null
    private var loadMoreJob: Job? = null
    private var loadInitialAllRequestId: Long = 0L
    
    // Loading states - using StateFlow for thread-safe observable loading state
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    private val _isLoadingMoreState = MutableStateFlow(false)
    val isLoadingMoreState: StateFlow<Boolean> = _isLoadingMoreState.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

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
     * Combines tab selection, search query, filter, ownership filter, and refresh triggers.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val transactions: StateFlow<List<ExpenseWithCategory>> = combine(
        _selectedTab,
        _searchQuery,
        _filter,
        _refreshTrigger
    ) { tab, query, filter, _ -> 
        FilterParams(tab, query, filter) 
    }
        .flatMapLatest { params ->
            val baseExpenses = if (params.filter != null) {
                if (params.tab == TransactionTab.ALL) {
                    _pagedExpenses
                } else {
                    val range = params.filter.dateRange ?: getTimeRangeForTab(params.tab)
                    expenseRepository.getExpensesWithCategoryFiltered(
                        startMs = range.first,
                        endMs = range.second,
                        type = params.filter.transactionType,
                        categoryId = params.filter.categoryId,
                        merchantKey = params.filter.merchantName?.let { MerchantKeyGenerator.generate(it) }
                    )
                }
            } else if (params.tab == TransactionTab.ALL) {
                _pagedExpenses
            } else {
                val range = getTimeRangeForTab(params.tab)
                expenseRepository.getExpensesWithCategoryInPeriod(range.first, range.second)
            }

            baseExpenses.map { expenses ->
                var filtered = if (params.tab == TransactionTab.ALL) {
                    expenses
                } else {
                    applyAmountConstraints(expenses, params.filter)
                }
                if (params.query.isNotBlank()) {
                    filtered = filtered.filter { matchesSearch(it, params.query) }
                }
                filtered = filterByOwnership(filtered, toUiOwnershipFilter(params.filter?.ownership))
                filtered
            }
        }
        .onEach {
            if (_isRefreshing.value && _selectedTab.value != TransactionTab.ALL) {
                _isRefreshing.value = false
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
        val filter: TransactionFilter?
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
                SortOrder.AMOUNT_DESC -> expenseList.sortedByDescending { it.expense.effectiveAmount }
                SortOrder.AMOUNT_ASC -> expenseList.sortedBy { it.expense.effectiveAmount }
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
        if (_selectedTab.value == TransactionTab.ALL) {
            resetAllPagingState()
            loadInitialAll()
        }
    }

    /**
     * Returns the current reference timestamp from the injected [TimeProvider].
     *
     * UI composables that need "now" (e.g., [TransactionFilterSheet] year chips)
     * should call this instead of [System.currentTimeMillis] so time remains
     * controllable in tests.
     */
    fun referenceNow(): Long = timeProvider.now()

    fun clearFilter() {
        _filter.value = null
        if (_selectedTab.value == TransactionTab.ALL) {
            resetAllPagingState()
            loadInitialAll()
        }
    }

    fun selectTab(tab: TransactionTab) {
        if (_selectedTab.value == tab) return
        
        _selectedTab.value = tab
        resetAllPagingState()
        _searchQuery.value = "" // Reset search on tab change
        _filter.value = null // Clear filter when manually changing tabs
        
        if (tab == TransactionTab.ALL) {
            loadInitialAll()
        }
    }

    fun search(query: String) {
        _searchQuery.value = query.trim()
        if (_selectedTab.value == TransactionTab.ALL) {
            resetAllPagingState()
            loadInitialAll()
        }
    }

    fun setSortOrder(order: SortOrder) {
        _sortOrder.value = order
        if (_selectedTab.value == TransactionTab.ALL) {
            resetAllPagingState()
            loadInitialAll()
        }
    }

    fun setOwnershipFilter(filter: OwnershipFilter) {
        val currentFilter = _filter.value
        _filter.value = when {
            filter == OwnershipFilter.ALL && currentFilter == null -> null
            filter == OwnershipFilter.ALL -> currentFilter?.copy(ownership = null)
            currentFilter != null -> currentFilter.copy(ownership = toRepositoryOwnershipFilter(filter))
            else -> TransactionFilter(ownership = toRepositoryOwnershipFilter(filter))
        }
        if (_selectedTab.value == TransactionTab.ALL) {
            resetAllPagingState()
            loadInitialAll()
        }
    }

    fun refresh() {
        _isRefreshing.value = true
        _refreshTrigger.value += 1
        
        if (_selectedTab.value == TransactionTab.ALL) {
            resetAllPagingState()
            loadInitialAll()
        }
    }

    fun loadMore() {
        // Guard conditions - prevent loading if not on ALL tab or already loading
        if (_selectedTab.value != TransactionTab.ALL) return
        if (_isLoadingMoreState.value) return
        if (_isLoading.value) return
        if (_hasReachedEnd.value) return

        loadMoreJob?.cancel()
        loadMoreJob = viewModelScope.launch {
            // Double-check inside coroutine to prevent race conditions
            if (_isLoadingMoreState.value) return@launch
            if (_hasReachedEnd.value) return@launch
            
            _isLoadingMoreState.value = true
            try {
                val nextPage = _currentPage.value + 1
                val offset = nextPage * PAGE_SIZE
                
                val nextItems = loadPagedExpensesPage(limit = PAGE_SIZE, offset = offset)
                
                if (nextItems.isNotEmpty()) {
                    // Use thread-safe list concatenation
                    _pagedExpenses.update { current ->
                        current + nextItems.distinctBy { it.expense.id }
                    }
                    _currentPage.value = nextPage
                }

                _hasReachedEnd.value = nextItems.size < PAGE_SIZE
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _error.emit("Failed to load more transactions: ${e.message}")
            } finally {
                _isLoadingMoreState.value = false
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
                refreshPagedExpensesAfterMutation()
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
            refreshPagedExpensesAfterMutation()
        } catch (e: Exception) {
            _error.emit("Failed to update merchant: ${e.message}")
        } finally {
            _isLoading.value = false
        }
    }
}
    fun updateExpenseType(
        expense: Expense,
        newType: TransactionType,
        transferDirection: TransferDirection? = expense.transferDirection,
        transferAccountName: String = expense.transferAccountName.orEmpty()
    ) {
        val normalizedTransferAccountName = transferAccountName.trim()
        if (newType == TransactionType.TRANSFER) {
            if (transferDirection == null || normalizedTransferAccountName.isBlank()) {
                viewModelScope.launch {
                    _error.emit("Transfer direction and account name are required for transfer transactions")
                }
                return
            }
        }

        viewModelScope.launch {
            _isLoading.value = true
            try {
                expenseRepository.updateExpenseType(expense, newType)
                if (newType == TransactionType.TRANSFER) {
                    expenseRepository.updateTransferDetails(
                        expense = expense,
                        transferDirection = transferDirection,
                        transferAccountName = normalizedTransferAccountName
                    )
                }
                _successMessage.emit("Type changed to ${newType.name}")
                refreshPagedExpensesAfterMutation()
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
                refreshPagedExpensesAfterMutation()
            } catch (e: Exception) {
                _error.emit("Failed to update: ${e.message}")
            }
        }
    }

    fun updateNotMineDetails(expense: Expense, isNotMine: Boolean, ownerName: String) {
        viewModelScope.launch {
            try {
                if (isNotMine && expense.isSharedExpense) {
                    expenseRepository.updateSharedExpenseDetails(
                        expense = expense,
                        isSharedExpense = false,
                        sharedWithName = null,
                        mySharePercentage = null,
                        myShareAmount = null
                    )
                }
                expenseRepository.updateNotMineDetails(expense, isNotMine, ownerName.takeIf { it.isNotBlank() })
                _successMessage.emit(if (isNotMine) "Marked as not mine" else "Marked as mine")
                refreshPagedExpensesAfterMutation()
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
        val percentageText = mySharePercentage.trim()
        val parsedSharePercentage = percentageText.takeIf { it.isNotEmpty() }?.toIntOrNull()
        if (percentageText.isNotEmpty() && (parsedSharePercentage == null || parsedSharePercentage !in 0..100)) {
            viewModelScope.launch {
                _error.emit("Share percentage must be between 0 and 100")
            }
            return
        }

        viewModelScope.launch {
            try {
                if (isSharedExpense && expense.isNotMine) {
                    expenseRepository.updateNotMineDetails(
                        expense = expense,
                        isNotMine = false,
                        ownerName = null
                    )
                }
                expenseRepository.updateSharedExpenseDetails(
                    expense = expense,
                    isSharedExpense = isSharedExpense,
                    sharedWithName = sharedWithName.takeIf { it.isNotBlank() },
                    mySharePercentage = parsedSharePercentage,
                    myShareAmount = myShareAmount.toDoubleOrNull()
                )
                _successMessage.emit(if (isSharedExpense) "Marked as shared expense" else "Unmarked shared expense")
                refreshPagedExpensesAfterMutation()
            } catch (e: Exception) {
                _error.emit("Failed to update: ${e.message}")
            }
        }
    }

    /**
     * Atomically update all ownership fields (not-mine + shared) in a single
     * repository call.  This is the **only** safe way to persist changes from
     * the `EditOwnershipDialog`, because calling `updateNotMineDetails` and
     * `updateSharedExpenseDetails` sequentially would cause the second call
     * to overwrite the first (both operate on the original Expense object
     * whose flags are stale after the first write).
     */
    fun updateOwnership(
        expense: Expense,
        isNotMine: Boolean,
        ownerName: String,
        isSharedExpense: Boolean,
        sharedWithName: String,
        mySharePercentage: String,
        myShareAmount: String
    ) {
        val percentageText = mySharePercentage.trim()
        val parsedSharePercentage = percentageText.takeIf { it.isNotEmpty() }?.toIntOrNull()
        if (percentageText.isNotEmpty() && (parsedSharePercentage == null || parsedSharePercentage !in 0..100)) {
            viewModelScope.launch {
                _error.emit("Share percentage must be between 0 and 100")
            }
            return
        }

        viewModelScope.launch {
            try {
                expenseRepository.updateOwnership(
                    expense = expense,
                    isNotMine = isNotMine,
                    ownerName = ownerName.takeIf { it.isNotBlank() },
                    isSharedExpense = isSharedExpense,
                    sharedWithName = sharedWithName.takeIf { it.isNotBlank() },
                    mySharePercentage = parsedSharePercentage,
                    myShareAmount = myShareAmount.toDoubleOrNull()
                )
                val message = when {
                    isNotMine -> "Marked as not mine"
                    isSharedExpense -> "Marked as shared expense"
                    else -> "Ownership updated"
                }
                _successMessage.emit(message)
                refreshPagedExpensesAfterMutation()
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
                    amount = expense.effectiveAmount,
                    frequency = frequency,
                    lastDate = timeProvider.now(),
                    currency = expense.currency
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
        loadMoreJob?.cancel()
        _isLoadingMoreState.value = false
        loadInitialAllJob?.cancel()
        val requestId = ++loadInitialAllRequestId
        loadInitialAllJob = viewModelScope.launch {
            _isLoading.value = true
            try {
                val initial = loadPagedExpensesPage(limit = PAGE_SIZE, offset = 0)
                if (requestId != loadInitialAllRequestId) return@launch
                _pagedExpenses.value = initial
                _currentPage.value = 0
                _hasReachedEnd.value = initial.size < PAGE_SIZE
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _error.emit("Failed to load transactions: ${e.message}")
            } finally {
                if (requestId == loadInitialAllRequestId) {
                    _isLoading.value = false
                    _isRefreshing.value = false
                }
            }
        }
    }

    private fun resetAllPagingState() {
        loadMoreJob?.cancel()
        _isLoadingMoreState.value = false
        _currentPage.value = 0
        _pagedExpenses.value = emptyList()
        _hasReachedEnd.value = false
    }

    private suspend fun loadPagedExpensesPage(limit: Int, offset: Int): List<ExpenseWithCategory> {
        return withContext(Dispatchers.IO) {
            val activeFilter = _filter.value
            expenseRepository.getExpensesPagedDynamic(
                limit = limit,
                offset = offset,
                searchQuery = _searchQuery.value.takeIf { it.isNotBlank() },
                startDate = activeFilter?.dateRange?.first,
                endDate = activeFilter?.dateRange?.second,
                transactionType = activeFilter?.transactionType,
                categoryId = activeFilter?.categoryId,
                merchantName = activeFilter?.merchantName,
                ownershipFilter = activeFilter?.ownership ?: RepositoryOwnershipFilter.ALL,
                minAmount = activeFilter?.minAmount,
                maxAmount = activeFilter?.maxAmount,
                sortOrder = _sortOrder.value
            )
        }
    }

    private suspend fun refreshPagedExpensesAfterMutation() {
        if (_selectedTab.value != TransactionTab.ALL) return

        val loadedCount = _pagedExpenses.value.size
        if (loadedCount == 0) {
            loadInitialAll()
            return
        }

        val refreshedPage = loadPagedExpensesPage(limit = loadedCount + 1, offset = 0)
        val visibleItems = refreshedPage.take(loadedCount)

        _pagedExpenses.value = visibleItems
        _currentPage.value = if (visibleItems.isEmpty()) 0 else (visibleItems.size - 1) / PAGE_SIZE
        _hasReachedEnd.value = refreshedPage.size <= loadedCount
    }

    private fun toRepositoryOwnershipFilter(filter: OwnershipFilter): RepositoryOwnershipFilter {
        return when (filter) {
            OwnershipFilter.ALL -> RepositoryOwnershipFilter.ALL
            OwnershipFilter.MINE -> RepositoryOwnershipFilter.MINE
            OwnershipFilter.NOT_MINE -> RepositoryOwnershipFilter.NOT_MINE
            OwnershipFilter.SHARED -> RepositoryOwnershipFilter.SHARED
            OwnershipFilter.TRANSFER -> RepositoryOwnershipFilter.TRANSFER
        }
    }

    private fun toUiOwnershipFilter(filter: RepositoryOwnershipFilter?): OwnershipFilter {
        return when (filter ?: RepositoryOwnershipFilter.ALL) {
            RepositoryOwnershipFilter.ALL -> OwnershipFilter.ALL
            RepositoryOwnershipFilter.MINE -> OwnershipFilter.MINE
            RepositoryOwnershipFilter.NOT_MINE -> OwnershipFilter.NOT_MINE
            RepositoryOwnershipFilter.SHARED -> OwnershipFilter.SHARED
            RepositoryOwnershipFilter.TRANSFER -> OwnershipFilter.TRANSFER
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
                Pair(startOfDay, com.yourname.expensetracker.domain.util.TimePeriodUtils.getEndOfDay(now))
            }
            TransactionTab.WEEK -> com.yourname.expensetracker.domain.util.TimePeriodUtils.getWeekRange(now, 0).let { (start, end) -> start to end }
            TransactionTab.MONTH -> com.yourname.expensetracker.domain.util.TimePeriodUtils.getLastNDaysRange(now, 30)
            TransactionTab.QUARTER -> com.yourname.expensetracker.domain.util.TimePeriodUtils.getLastNDaysRange(now, 90)
            TransactionTab.YEAR -> com.yourname.expensetracker.domain.util.TimePeriodUtils.getLastNDaysRange(now, 365)
            TransactionTab.ALL -> Pair(0L, now)
        }
    }

    private fun applyAmountConstraints(
        expenses: List<ExpenseWithCategory>,
        filter: TransactionFilter?
    ): List<ExpenseWithCategory> {
        if (filter == null) return expenses
        val minAmount = filter.minAmount
        val maxAmount = filter.maxAmount
        if (minAmount == null && maxAmount == null) return expenses

        return expenses.filter { item ->
            val amount = item.expense.effectiveAmount
            (minAmount == null || amount >= minAmount) &&
                (maxAmount == null || amount <= maxAmount)
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
                DateFormatterUtils.formatTimestampJavaTime(item.expense.date, "EEEE, MMMM d, yyyy")
            }
    }
}
