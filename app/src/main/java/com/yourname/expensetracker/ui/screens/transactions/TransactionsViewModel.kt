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
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.provenance.SourceLinkQueryService
import com.yourname.expensetracker.data.database.entity.EntitySourceLink
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
 val geocodingService: com.yourname.expensetracker.domain.location.GeocodingService,
 private val currencySettingsRepository: CurrencySettingsRepository,
 private val sourceLinkQueryService: SourceLinkQueryService
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
    /** S5-016R: Mutex ensures only one loadMore runs at a time */
    private val loadMoreMutex = kotlinx.coroutines.sync.Mutex()
    /** S5-028: Debounce job for search */
    private var searchDebounceJob: Job? = null
    private var loadInitialAllRequestId: Long = 0L
    
    // Loading states - using StateFlow for thread-safe observable loading state
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** S5-015: Per-row mutation tracking — avoids blocking entire screen for row operations */
    private val _mutatingExpenseIds = MutableStateFlow<Set<Long>>(emptySet())
    val mutatingExpenseIds: StateFlow<Set<Long>> = _mutatingExpenseIds.asStateFlow()

    private fun beginRowMutation(expenseId: Long) {
        _mutatingExpenseIds.update { it + expenseId }
    }
    private fun endRowMutation(expenseId: Long) {
        _mutatingExpenseIds.update { it - expenseId }
    }
    
    private val _isLoadingMoreState = MutableStateFlow(false)
    val isLoadingMoreState: StateFlow<Boolean> = _isLoadingMoreState.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    // Error state for UI feedback
    private val _error = MutableSharedFlow<com.yourname.expensetracker.domain.model.UiText>()
    val error: SharedFlow<com.yourname.expensetracker.domain.model.UiText> = _error.asSharedFlow()

    // Success feedback
    private val _successMessage = MutableSharedFlow<com.yourname.expensetracker.domain.model.UiText>()
    val successMessage: SharedFlow<com.yourname.expensetracker.domain.model.UiText> = _successMessage.asSharedFlow()

    /** S5-014/S5-026: Emitted after category update succeeds — carries expenseId for dialog matching */
    private val _categoryUpdateSuccess = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val categoryUpdateSuccess: SharedFlow<Long> = _categoryUpdateSuccess.asSharedFlow()

    /** S5-014R: Emitted after rename succeeds */
    private val _renameSuccess = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val renameSuccess: SharedFlow<Long> = _renameSuccess.asSharedFlow()

    /** S5-014R: Emitted after type change succeeds */
    private val _typeChangeSuccess = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val typeChangeSuccess: SharedFlow<Long> = _typeChangeSuccess.asSharedFlow()

    /** S5-014R: Emitted after ownership update succeeds */
    private val _ownershipSuccess = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val ownershipSuccess: SharedFlow<Long> = _ownershipSuccess.asSharedFlow()

    /** S5-035: Emitted after location save succeeds */
    private val _locationSaveSuccess = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val locationSaveSuccess: SharedFlow<Long> = _locationSaveSuccess.asSharedFlow()

    /** S5-036: Emitted after recurring mark succeeds */
    private val _recurringSuccess = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val recurringSuccess: SharedFlow<Long> = _recurringSuccess.asSharedFlow()

    // PR9: Provenance query state
    private val _provenanceLinks = MutableStateFlow<List<EntitySourceLink>?>(null)
    val provenanceLinks: StateFlow<List<EntitySourceLink>?> = _provenanceLinks.asStateFlow()

    private val _provenanceSummary = MutableStateFlow<String?>(null)
    val provenanceSummary: StateFlow<String?> = _provenanceSummary.asStateFlow()

    private val _isProvenanceLoading = MutableStateFlow(false)
    val isProvenanceLoading: StateFlow<Boolean> = _isProvenanceLoading.asStateFlow()

    fun loadProvenanceForExpense(expenseId: Long) {
        viewModelScope.launch {
            _isProvenanceLoading.value = true
            try {
                _provenanceLinks.value = sourceLinkQueryService.getLinksForExpense(expenseId)
                _provenanceSummary.value = sourceLinkQueryService.getExpenseSourceSummary(expenseId)
            } catch (e: Exception) {
                _provenanceLinks.value = emptyList()
                _provenanceSummary.value = "Error loading provenance: ${e.message}"
            } finally {
                _isProvenanceLoading.value = false
            }
        }
    }

    fun clearProvenance() {
        _provenanceLinks.value = null
        _provenanceSummary.value = null
    }

 // Refresh trigger for pull-to-refresh
 private val _refreshTrigger = MutableStateFlow(0)

 /** Placeholder initial value "EUR"; immediately replaced by [CurrencySettingsRepository.homeCurrency]. */
 private val _homeCurrency = currencySettingsRepository.homeCurrency()
 .stateIn(viewModelScope, SharingStarted.Lazily, "EUR")
 val homeCurrency: StateFlow<String> = _homeCurrency

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
        // S5-018: Catch repository/flow errors — preserve stale data, show error
        .catch { e ->
            if (e is CancellationException) throw e
            _isRefreshing.value = false // S5-017: Always clear refresh spinner on error
            _error.emit(com.yourname.expensetracker.domain.model.UiText.DynamicString("Failed to load transactions: ${e.message}"))
            // Do NOT emit emptyList() — preserve whatever the StateFlow currently holds
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
                SortOrder.AMOUNT_DESC -> expenseList.sortedByDescending { it.expense.normalizedEffectiveAmount }
                SortOrder.AMOUNT_ASC -> expenseList.sortedBy { it.expense.normalizedEffectiveAmount }
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
    // ============================================================
    // PUBLIC API
    // ============================================================

    fun applyFilter(filter: TransactionFilter) {
        // S5-032/S5-033: Normalize — empty filter and ownership ALL become null
        val normalized = filter.let { f ->
            val withNullOwnership = if (f.ownership == com.yourname.expensetracker.data.repository.OwnershipFilter.ALL) f.copy(ownership = null) else f
            if (withNullOwnership.categoryId == null && withNullOwnership.merchantName == null &&
                withNullOwnership.transactionType == null && withNullOwnership.dateRange == null &&
                withNullOwnership.ownership == null && withNullOwnership.minAmount == null &&
                withNullOwnership.maxAmount == null && withNullOwnership.correlationId == 0L) null
            else withNullOwnership
        }
        _filter.value = normalized
        if (_selectedTab.value == TransactionTab.ALL) {
            resetAllPagingState()
            loadInitialAll()
        }
    }

    /** S5-005: Clear any route-provided filter — canonical no-filter state is null. */
    fun clearRouteFilter() {
        if (_filter.value != null) {
            _filter.value = null
            if (_selectedTab.value == TransactionTab.ALL) {
                resetAllPagingState()
                loadInitialAll()
            }
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
        
        searchDebounceJob?.cancel() // S5-031: cancel pending search before switching tab
        _selectedTab.value = tab
        resetAllPagingState()
        _searchQuery.value = ""
        _filter.value = null
        
        if (tab == TransactionTab.ALL) {
            loadInitialAll()
        }
    }

    fun search(query: String) {
        _searchQuery.value = query.trim()
        if (_selectedTab.value == TransactionTab.ALL) {
            // S5-028/S5-031: Debounce + re-check tab inside delayed block
            searchDebounceJob?.cancel()
            searchDebounceJob = viewModelScope.launch {
                kotlinx.coroutines.delay(250)
                if (_selectedTab.value != TransactionTab.ALL) return@launch // S5-031
                resetAllPagingState()
                loadInitialAll()
            }
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
        if (_selectedTab.value != TransactionTab.ALL) return
        if (_isLoadingMoreState.value) return
        if (_isLoading.value) return
        if (_hasReachedEnd.value) return

        // S5-016R/S5-030: Use Mutex + assign job so reset/search can cancel it
        loadMoreJob = viewModelScope.launch {
            if (!loadMoreMutex.tryLock()) return@launch
            try {
                if (_hasReachedEnd.value || _selectedTab.value != TransactionTab.ALL) return@launch
                _isLoadingMoreState.value = true
                val nextPage = _currentPage.value + 1
                val offset = nextPage * PAGE_SIZE
                val nextItems = loadPagedExpensesPage(limit = PAGE_SIZE, offset = offset)
                if (nextItems.isNotEmpty()) {
                    _pagedExpenses.update { current ->
                        current + nextItems.distinctBy { it.expense.id }
                    }
                    _currentPage.value = nextPage
                }
                _hasReachedEnd.value = nextItems.size < PAGE_SIZE
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _error.emit(com.yourname.expensetracker.domain.model.UiText.DynamicString("Failed to load more transactions: ${e.message}"))
            } finally {
                _isLoadingMoreState.value = false
                if (loadMoreMutex.isLocked) loadMoreMutex.unlock()
            }
        }
    }

    fun deleteExpense(expense: Expense) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                expenseRepository.deleteExpense(expense)
                _successMessage.emit(com.yourname.expensetracker.domain.model.UiText.DynamicString("Transaction deleted"))
                refresh()
            } catch (e: Exception) {
                _error.emit(com.yourname.expensetracker.domain.model.UiText.DynamicString("Failed to delete transaction: ${e.message}"))
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateCategory(expense: Expense, categoryId: Long, applyToAll: Boolean = false) {
        viewModelScope.launch {
            beginRowMutation(expense.id) // S5-015
            try {
                if (applyToAll) {
                    expenseRepository.updateExpenseCategoryBulk(expense.merchant, categoryId)
                    _successMessage.emit(com.yourname.expensetracker.domain.model.UiText.DynamicString("Category updated for all ${expense.merchant} transactions"))
                } else {
                    expenseRepository.updateExpenseCategory(expense, categoryId)
                    _successMessage.emit(com.yourname.expensetracker.domain.model.UiText.DynamicString("Category updated"))
                }
                // S5-014/S5-026: Signal success with expenseId for dialog matching
                _categoryUpdateSuccess.tryEmit(expense.id)
                refreshPagedExpensesAfterMutation()
            } catch (e: Exception) {
                _error.emit(com.yourname.expensetracker.domain.model.UiText.DynamicString("Failed to update category: ${e.message}"))
            } finally {
                endRowMutation(expense.id) // S5-015
            }
        }
    }

    fun updateMerchant(expense: Expense, newMerchant: String, applyToAll: Boolean = false) {
    val trimmedName = newMerchant.trim()
    if (trimmedName.isBlank()) {
        viewModelScope.launch { _error.emit(com.yourname.expensetracker.domain.model.UiText.DynamicString("Merchant name cannot be empty")) }
        return
    }
    
    viewModelScope.launch {
        beginRowMutation(expense.id) // S5-015
        try {
            expenseRepository.updateExpenseMerchant(expense, trimmedName, applyToAll)
            val message = if (applyToAll) "Merchant renamed to $trimmedName globally" else "Merchant renamed to $trimmedName"
            _successMessage.emit(com.yourname.expensetracker.domain.model.UiText.DynamicString(message))
            _renameSuccess.tryEmit(expense.id) // S5-014R
            refreshPagedExpensesAfterMutation()
        } catch (e: Exception) {
            _error.emit(com.yourname.expensetracker.domain.model.UiText.DynamicString("Failed to update merchant: ${e.message}"))
        } finally {
            endRowMutation(expense.id) // S5-015
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
                    _error.emit(com.yourname.expensetracker.domain.model.UiText.DynamicString("Transfer direction and account name are required for transfer transactions"))
                }
                return
            }
        }

        viewModelScope.launch {
            beginRowMutation(expense.id) // S5-015
            try {
                val effectiveDirection = if (newType == TransactionType.TRANSFER) transferDirection else null
                val effectiveAccount = if (newType == TransactionType.TRANSFER) normalizedTransferAccountName else ""

                expenseRepository.updateExpenseTypeAndTransfer(
                    expense = expense,
                    newType = newType,
                    transferDirection = effectiveDirection,
                    transferAccountName = effectiveAccount.takeIf { it.isNotBlank() }
                )
                _successMessage.emit(com.yourname.expensetracker.domain.model.UiText.DynamicString("Type changed to ${newType.name}"))
                _typeChangeSuccess.tryEmit(expense.id) // S5-014R
                refreshPagedExpensesAfterMutation()
            } catch (e: Exception) {
                _error.emit(com.yourname.expensetracker.domain.model.UiText.DynamicString("Failed to update type: ${e.message}"))
            } finally {
                endRowMutation(expense.id) // S5-015
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
                _successMessage.emit(com.yourname.expensetracker.domain.model.UiText.DynamicString("Transfer details updated"))
                refreshPagedExpensesAfterMutation()
            } catch (e: Exception) {
                _error.emit(com.yourname.expensetracker.domain.model.UiText.DynamicString("Failed to update: ${e.message}"))
            }
        }
    }

    @Deprecated("Use updateOwnership", ReplaceWith("updateOwnership(...)"))
    fun updateSharedExpenseDetails(
        expense: Expense,
        isSharedExpense: Boolean,
        sharedWithName: String,
        mySharePercentage: String,
        myShareAmount: String
    ) {
        updateOwnership(expense, false, "", isSharedExpense, sharedWithName, mySharePercentage, myShareAmount)
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
        // S5-012: Use shared OwnershipValidator — same rules as Add Expense
        val validationResult = com.yourname.expensetracker.ui.util.OwnershipValidator.validate(
            isNotMine = isNotMine,
            isSharedExpense = isSharedExpense,
            sharedWithName = sharedWithName,
            sharePercentageText = mySharePercentage.trim(),
            shareAmountText = myShareAmount.trim()
        )
        if (validationResult is com.yourname.expensetracker.ui.util.OwnershipValidator.ValidationResult.Invalid) {
            viewModelScope.launch { _error.emit(com.yourname.expensetracker.domain.model.UiText.DynamicString(validationResult.message)) }
            return
        }

        val percentageText = mySharePercentage.trim()
        val parsedSharePercentage = percentageText.takeIf { it.isNotEmpty() }?.toIntOrNull()

        viewModelScope.launch {
            beginRowMutation(expense.id) // S5-034
            try {
                expenseRepository.updateOwnership(
                    expense = expense,
                    isNotMine = isNotMine,
                    ownerName = ownerName.takeIf { it.isNotBlank() },
                    isSharedExpense = isSharedExpense,
                    sharedWithName = sharedWithName.takeIf { it.isNotBlank() },
                    mySharePercentage = parsedSharePercentage,
                    myShareAmount = com.yourname.expensetracker.domain.util.AmountUtils.parseAmount(myShareAmount.trim())
                )
                val message = when {
                    isNotMine -> "Marked as not mine"
                    isSharedExpense -> "Marked as shared expense"
                    else -> "Ownership updated"
                }
                _successMessage.emit(com.yourname.expensetracker.domain.model.UiText.DynamicString(message))
                _ownershipSuccess.tryEmit(expense.id) // S5-014R
                refreshPagedExpensesAfterMutation()
            } catch (e: Exception) {
                _error.emit(com.yourname.expensetracker.domain.model.UiText.DynamicString("Failed to update: ${e.message}"))
            } finally {
                endRowMutation(expense.id) // S5-034
            }
        }
    }

    /** S5-013: Deprecated — use [updateOwnership] for validated atomic ownership update. */
    @Deprecated("Use updateOwnership", ReplaceWith("updateOwnership(...)"))
    fun updateNotMineDetails(expense: Expense, isNotMine: Boolean, ownerName: String) {
        updateOwnership(expense, isNotMine, ownerName, false, "", "", "")
    }

    /** S5-013: Deprecated — use [updateOwnership] for validated atomic ownership update. */
    @Deprecated("Use updateOwnership", ReplaceWith("updateOwnership(...)"))

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
                _successMessage.emit(com.yourname.expensetracker.domain.model.UiText.DynamicString("Location saved"))
                _locationSaveSuccess.tryEmit(expense.id) // S5-035
                refresh()
            } catch (e: Exception) {
                _error.emit(com.yourname.expensetracker.domain.model.UiText.DynamicString("Failed to save location: ${e.message}"))
            }
        }
    }

    fun clearLocation(expense: Expense) {
        viewModelScope.launch {
            try {
                expenseRepository.clearExpenseLocation(expense.id)
                _successMessage.emit(com.yourname.expensetracker.domain.model.UiText.DynamicString("Location cleared"))
                refresh()
            } catch (e: Exception) {
                _error.emit(com.yourname.expensetracker.domain.model.UiText.DynamicString("Failed to clear location: ${e.message}"))
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
                _successMessage.emit(com.yourname.expensetracker.domain.model.UiText.DynamicString("Marked as recurring (${frequency.name.lowercase().replace("_", " ")})"))
                _recurringSuccess.tryEmit(expense.id) // S5-036
            } catch (e: Exception) {
                _error.emit(com.yourname.expensetracker.domain.model.UiText.DynamicString("Failed to mark as recurring: ${e.message}"))
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
                _error.emit(com.yourname.expensetracker.domain.model.UiText.DynamicString("Failed to load transactions: ${e.message}"))
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
            TransactionTab.MONTH -> com.yourname.expensetracker.domain.util.TimePeriodUtils.getMonthRange(now)
            TransactionTab.QUARTER -> com.yourname.expensetracker.domain.util.TimePeriodUtils.getQuarterRange(now)
            TransactionTab.YEAR -> com.yourname.expensetracker.domain.util.TimePeriodUtils.getYearRange(now)
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

        // S5-008R-A: Use normalizedEffectiveAmount for currency-aware comparison
        return expenses.filter { item ->
            val amount = item.expense.normalizedEffectiveAmount
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
