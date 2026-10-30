package com.yourname.expensetracker.ui.screens.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.TransferDirection
import com.yourname.expensetracker.data.repository.AmountSemantics
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseCountFilter
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.yourname.expensetracker.domain.core.money.ComparableHomeValue
import com.yourname.expensetracker.domain.core.money.ComparableValue
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.ReadHomeCurrencyState
import com.yourname.expensetracker.domain.currency.ReadHomeFailure
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.util.DateFormatterUtils
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.provenance.SourceLinkQueryService
import com.yourname.expensetracker.domain.transaction.lifecycle.GroupLinkedExpenseException
import com.yourname.expensetracker.domain.transaction.lifecycle.InstallmentLockedExpenseException
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.data.database.entity.EntitySourceLink
import javax.inject.Inject
import timber.log.Timber

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
  private val sourceLinkQueryService: SourceLinkQueryService,
  private val transactionLifecycleCoordinator: TransactionLifecycleCoordinator
) : ViewModel() {

    companion object {
        const val PAGE_SIZE = 50

        /** INV-9 / spec 5.1: no typed home-currency emission within this window -> TIMEOUT. */
        const val HOME_READ_TIMEOUT_MS = 5_000L

        /** Date order with the id tiebreak (D-4); also the INV-9 order while amount ops are paused. */
        private val DATE_DESC_COMPARATOR: Comparator<Expense> =
            compareByDescending<Expense> { it.date }.thenByDescending { it.id }
        private val DATE_ASC_COMPARATOR: Comparator<Expense> =
            compareBy<Expense> { it.date }.thenBy { it.id }
    }

    /** Result of the in-memory / SQL count of rows without a home value, tagged with what it was for. */
    private sealed interface CountStatus {
        data class Loaded(val count: Int) : CountStatus
        data object Failed : CountStatus
    }

    private data class UnrankedCount(
        val tab: TransactionTab,
        val home: CurrencyCode,
        val status: CountStatus
    )

    /**
     * INV-10 query key of an ALL-tab page or count request: `(H, sort, filters, search)`.
     * [semantics] carries H (Available code) or the amount-ops-disabled marker; a result whose
     * key differs from the current key is discarded.
     */
    private data class AllQueryKey(
        val semantics: AmountSemantics,
        val sortOrder: SortOrder,
        val filter: TransactionFilter?,
        val search: String
    )

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
    /** Count of rows without a home value (ALL: SQL count; other tabs: in-memory over the period list). */
    private val _unrankedCount = MutableStateFlow<UnrankedCount?>(null)
    private var allCountJob: Job? = null
    /** H component of the key the current ALL-tab data was requested with (INV-10 reset on change). */
    private var lastAllRequestSemantics: AmountSemantics? = null

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
                if (e is CancellationException) throw e
                _provenanceLinks.value = emptyList()
                Timber.w("Transactions: TRANSACTION_LOAD_FAILED class=%s", e::class.java.simpleName)
                _provenanceSummary.value = "Unable to load provenance (TRANSACTION_LOAD_FAILED)"
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

    // ============================================================
    // READ-TIME HOME CURRENCY (MC 1a-3b; INV-9, spec 5.1)
    // ============================================================

    /** Bumped by [retryHomeCurrency]; re-subscribes the typed home-currency read. */
    private val homeRetryTrigger = MutableStateFlow(0)

    /**
     * Typed read-time home currency. Starts in [ReadHomeCurrencyState.Loading] (never a guessed
     * code); no emission within [HOME_READ_TIMEOUT_MS] -> `Unavailable(TIMEOUT)`; a repeated
     * `Available` with the same code is dropped so paging is not reset (INV-10, review note N3).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val homeCurrencyState: StateFlow<ReadHomeCurrencyState> = homeRetryTrigger
        .flatMapLatest { readHomeCurrencyStates() }
        .distinctUntilChanged { old, new ->
            old is ReadHomeCurrencyState.Available &&
                new is ReadHomeCurrencyState.Available &&
                old.code == new.code
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ReadHomeCurrencyState.Loading)

    /** Bounded UI summary of [homeCurrencyState] (INV-9, INV-22). */
    val amountOrderingState: StateFlow<AmountOrderingState> = homeCurrencyState
        .map { state ->
            when (state) {
                is ReadHomeCurrencyState.Available -> AmountOrderingState.AVAILABLE
                is ReadHomeCurrencyState.Loading -> AmountOrderingState.HOME_CURRENCY_LOADING
                is ReadHomeCurrencyState.Unavailable -> AmountOrderingState.HOME_CURRENCY_UNAVAILABLE
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AmountOrderingState.HOME_CURRENCY_LOADING)

    /**
     * Notice state for amount sort / min-max / unranked count (ux-flows section 4).
     * Integers and bounded codes only; no transaction content.
     */
    val amountUi: StateFlow<TransactionsAmountUi> = combine(
        homeCurrencyState,
        _sortOrder,
        _filter,
        _selectedTab,
        _unrankedCount
    ) { home, sort, filter, tab, unranked ->
        when (home) {
            is ReadHomeCurrencyState.Loading -> TransactionsAmountUi.HomeLoading
            is ReadHomeCurrencyState.Unavailable -> TransactionsAmountUi.HomeUnavailable(home.reason)
            is ReadHomeCurrencyState.Available -> {
                if (!isAmountEngaged(sort, filter)) {
                    TransactionsAmountUi.Inactive
                } else {
                    val hidesRows = filter?.minAmount != null || filter?.maxAmount != null
                    val matching = unranked?.takeIf { it.tab == tab && it.home == home.code }
                    when (val status = matching?.status) {
                        null -> TransactionsAmountUi.CountPending
                        is CountStatus.Loaded -> TransactionsAmountUi.Ready(status.count, home.code, hidesRows)
                        is CountStatus.Failed -> TransactionsAmountUi.CountUnavailable(home.code, hidesRows)
                    }
                }
            }
        }
    }.stateIn<TransactionsAmountUi>(viewModelScope, SharingStarted.Eagerly, TransactionsAmountUi.HomeLoading)

    init {
        // INV-10: a change of the amount semantics (Available code A -> B, or Available <-> not
        // Available) resets ALL-tab paging. Same-code Available emissions never reach here.
        viewModelScope.launch {
            homeCurrencyState
                .map { it.toAmountSemantics() }
                .distinctUntilChanged()
                .collect { semantics ->
                    if (_selectedTab.value == TransactionTab.ALL && semantics != lastAllRequestSemantics) {
                        resetAllPagingState()
                        loadInitialAll()
                    }
                }
        }
    }

    /** Re-subscribes the home-currency read after [ReadHomeCurrencyState.Unavailable] ("Try Again"). */
    fun retryHomeCurrency() {
        if (homeCurrencyState.value is ReadHomeCurrencyState.Unavailable) {
            homeRetryTrigger.update { it + 1 }
        }
    }

    /**
     * Loading first, then the repository's typed states. No typed emission within
     * [HOME_READ_TIMEOUT_MS] -> `Unavailable(TIMEOUT)`; a repository failure -> `READ_ERROR`.
     * Only bounded codes are produced; no exception text is kept.
     */
    private fun readHomeCurrencyStates(): Flow<ReadHomeCurrencyState> = channelFlow {
        send(ReadHomeCurrencyState.Loading)
        val timeout = launch {
            delay(HOME_READ_TIMEOUT_MS)
            send(ReadHomeCurrencyState.Unavailable(ReadHomeFailure.TIMEOUT))
        }
        currencySettingsRepository.observeReadHomeCurrency()
            .catch { e ->
                if (e is CancellationException) throw e
                emit(ReadHomeCurrencyState.Unavailable(ReadHomeFailure.READ_ERROR))
            }
            .collect { state ->
                if (state !is ReadHomeCurrencyState.Loading) timeout.cancel()
                send(state)
            }
    }

    /** H is only ever the typed Available code; Loading/Unavailable disable amount operations (H-1). */
    private fun ReadHomeCurrencyState.toAmountSemantics(): AmountSemantics = when (this) {
        is ReadHomeCurrencyState.Available -> AmountSemantics.HomeComparable(code)
        is ReadHomeCurrencyState.Loading,
        is ReadHomeCurrencyState.Unavailable -> AmountSemantics.AmountOpsDisabled
    }

    private fun isAmountEngaged(sort: SortOrder, filter: TransactionFilter?): Boolean =
        sort == SortOrder.AMOUNT_DESC || sort == SortOrder.AMOUNT_ASC ||
            filter?.minAmount != null || filter?.maxAmount != null

    private fun currentQueryKey(): AllQueryKey = AllQueryKey(
        semantics = homeCurrencyState.value.toAmountSemantics(),
        sortOrder = _sortOrder.value,
        filter = _filter.value,
        search = _searchQuery.value
    )

    /**
     * Main transactions flow with reactive filtering.
     * Combines tab selection, search query, filter, ownership filter, and refresh triggers;
     * the read-time home currency is applied to in-memory amount bounds (INV-7, INV-9).
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

            combine(baseExpenses, homeCurrencyState) { expenses, home ->
                var filtered = expenses
                if (params.query.isNotBlank()) {
                    filtered = filtered.filter { matchesSearch(it, params.query) }
                }
                filtered = filterByOwnership(filtered, toUiOwnershipFilter(params.filter?.ownership))
                if (params.tab != TransactionTab.ALL) {
                    // ALL: min/max are applied in SQL (HomeComparable) or not at all (AmountOpsDisabled).
                    filtered = applyAmountConstraints(params.tab, filtered, params.filter, home)
                }
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
            Timber.w("Transactions: TRANSACTION_LOAD_FAILED class=%s", e::class.java.simpleName)
            _error.emit(com.yourname.expensetracker.domain.model.UiText.DynamicString("Failed to load transactions (TRANSACTION_LOAD_FAILED)"))
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
    val groupedTransactions: StateFlow<Map<String, List<ExpenseWithCategory>>> = combine(
        transactions,
        _sortOrder,
        homeCurrencyState,
        _selectedTab
    ) { expenseList, order, home, tab ->
        val homeCode = (home as? ReadHomeCurrencyState.Available)?.code
        val sortedList = if (tab == TransactionTab.ALL) {
            // Already sorted and filtered via backend pagination
            expenseList
        } else {
            // Needs sorting in-memory, by the same rule owner as the SQL ORDER BY (INV-5, INV-7)
            sortInMemory(expenseList, order, homeCode)
        }

        // INV-9: with amount operations paused the effective order is date, so no amount group.
        if (homeCode != null && (order == SortOrder.AMOUNT_DESC || order == SortOrder.AMOUNT_ASC)) {
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
        // Spec 5.1: pull-to-refresh re-subscribes a failed home-currency read.
        retryHomeCurrency()

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
                // INV-10: the page is requested and accepted under one (H, sort, filters, search) key.
                val key = currentQueryKey()
                val nextPage = _currentPage.value + 1
                val offset = nextPage * PAGE_SIZE
                val nextItems = loadPagedExpensesPage(key, limit = PAGE_SIZE, offset = offset)
                if (key != currentQueryKey() || _selectedTab.value != TransactionTab.ALL) return@launch
                if (nextItems.isNotEmpty()) {
                    _pagedExpenses.update { current ->
                        current + nextItems.distinctBy { it.expense.id }
                    }
                    _currentPage.value = nextPage
                }
                _hasReachedEnd.value = nextItems.size < PAGE_SIZE
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.w("Transactions: TRANSACTION_LOAD_FAILED class=%s", e::class.java.simpleName)
                _error.emit(com.yourname.expensetracker.domain.model.UiText.DynamicString("Failed to load more transactions (TRANSACTION_LOAD_FAILED)"))
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
            } catch (e: GroupLinkedExpenseException) {
                // R18: bounded code only; localized guidance, no raw exception text.
                Timber.w("Transactions: TRANSACTION_DELETE_BLOCKED code=%s", e.code)
                _error.emit(com.yourname.expensetracker.domain.model.UiText.from(com.yourname.expensetracker.R.string.expense_delete_blocked_group_linked))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.w("Transactions: TRANSACTION_DELETE_FAILED class=%s", e::class.java.simpleName)
                _error.emit(com.yourname.expensetracker.domain.model.UiText.DynamicString("Failed to delete transaction (TRANSACTION_DELETE_FAILED)"))
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
                if (e is CancellationException) throw e
                Timber.w("Transactions: TRANSACTION_UPDATE_FAILED class=%s", e::class.java.simpleName)
                _error.emit(com.yourname.expensetracker.domain.model.UiText.DynamicString("Failed to update category (TRANSACTION_UPDATE_FAILED)"))
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
            if (e is CancellationException) throw e
            Timber.w("Transactions: TRANSACTION_UPDATE_FAILED class=%s", e::class.java.simpleName)
            _error.emit(com.yourname.expensetracker.domain.model.UiText.DynamicString("Failed to update merchant (TRANSACTION_UPDATE_FAILED)"))
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
            } catch (e: GroupLinkedExpenseException) {
                // R18: bounded code only; localized guidance, no raw exception text.
                Timber.w("Transactions: TRANSACTION_UPDATE_BLOCKED code=%s", e.code)
                _error.emit(com.yourname.expensetracker.domain.model.UiText.from(com.yourname.expensetracker.R.string.expense_edit_blocked_group_linked))
            } catch (e: InstallmentLockedExpenseException) {
                // AE-20 / INV-22: fixed text; logs a constant, never the unvalidated exception code.
                Timber.w("Transactions: TRANSACTION_UPDATE_BLOCKED code=EXPENSE_LOCKED_BY_INSTALLMENT_PLAN")
                _error.emit(com.yourname.expensetracker.domain.model.UiText.from(com.yourname.expensetracker.R.string.transactions_installment_purchase_locked))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.w("Transactions: TRANSACTION_UPDATE_FAILED class=%s", e::class.java.simpleName)
                _error.emit(com.yourname.expensetracker.domain.model.UiText.DynamicString("Failed to update type (TRANSACTION_UPDATE_FAILED)"))
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
                // P2-007: fresh caller correlation id — travels into both the
                // UPDATED lifecycle event and the planned post-commit batch.
                val correlationId =
                    com.yourname.expensetracker.domain.diagnostics.CorrelationIds.newId()
                expenseRepository.updateTransferDetails(
                    expense,
                    transferDirection,
                    transferAccountName.takeIf { it.isNotBlank() },
                    correlationId
                )
                _successMessage.emit(com.yourname.expensetracker.domain.model.UiText.DynamicString("Transfer details updated"))
                refreshPagedExpensesAfterMutation()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.w("Transactions: TRANSACTION_UPDATE_FAILED class=%s", e::class.java.simpleName)
                _error.emit(com.yourname.expensetracker.domain.model.UiText.DynamicString("Failed to update (TRANSACTION_UPDATE_FAILED)"))
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
                    myShareAmount = com.yourname.expensetracker.ui.util.AmountInputSanitizer.parseOrNull(myShareAmount.trim())
                )
                val message = when {
                    isNotMine -> "Marked as not mine"
                    isSharedExpense -> "Marked as shared expense"
                    else -> "Ownership updated"
                }
                _successMessage.emit(com.yourname.expensetracker.domain.model.UiText.DynamicString(message))
                _ownershipSuccess.tryEmit(expense.id) // S5-014R
                refreshPagedExpensesAfterMutation()
            } catch (e: GroupLinkedExpenseException) {
                // R18: bounded code only; localized guidance, no raw exception text.
                Timber.w("Transactions: TRANSACTION_UPDATE_BLOCKED code=%s", e.code)
                _error.emit(com.yourname.expensetracker.domain.model.UiText.from(com.yourname.expensetracker.R.string.expense_edit_blocked_group_linked))
            } catch (e: InstallmentLockedExpenseException) {
                // AE-20 / INV-22: fixed text; logs a constant, never the unvalidated exception code.
                Timber.w("Transactions: TRANSACTION_UPDATE_BLOCKED code=EXPENSE_LOCKED_BY_INSTALLMENT_PLAN")
                _error.emit(com.yourname.expensetracker.domain.model.UiText.from(com.yourname.expensetracker.R.string.transactions_installment_purchase_locked))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.w("Transactions: TRANSACTION_UPDATE_FAILED class=%s", e::class.java.simpleName)
                _error.emit(com.yourname.expensetracker.domain.model.UiText.DynamicString("Failed to update (TRANSACTION_UPDATE_FAILED)"))
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
                if (e is CancellationException) throw e
                Timber.w("Transactions: TRANSACTION_UPDATE_FAILED class=%s", e::class.java.simpleName)
                _error.emit(com.yourname.expensetracker.domain.model.UiText.DynamicString("Failed to save location (TRANSACTION_UPDATE_FAILED)"))
            }
        }
    }

    fun clearLocation(expense: Expense) {
        viewModelScope.launch {
            try {
                transactionLifecycleCoordinator.clearLocation(
                    expenseId = expense.id,
                    source = com.yourname.expensetracker.domain.config.AppConfig.Location.SOURCE_USER_MANUAL,
                    reason = "User cleared location"
                )
                _successMessage.emit(com.yourname.expensetracker.domain.model.UiText.DynamicString("Location cleared"))
                refresh()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.w("Transactions: TRANSACTION_UPDATE_FAILED class=%s", e::class.java.simpleName)
                _error.emit(com.yourname.expensetracker.domain.model.UiText.DynamicString("Failed to clear location (TRANSACTION_UPDATE_FAILED)"))
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
                if (e is CancellationException) throw e
                Timber.w("Transactions: TRANSACTION_UPDATE_FAILED class=%s", e::class.java.simpleName)
                _error.emit(com.yourname.expensetracker.domain.model.UiText.DynamicString("Failed to mark as recurring (TRANSACTION_UPDATE_FAILED)"))
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
        // INV-10: page and count are requested under this key; a result under another key is dropped.
        val key = currentQueryKey()
        lastAllRequestSemantics = key.semantics
        _unrankedCount.value = null
        launchAllCount(key)
        loadInitialAllJob = viewModelScope.launch {
            _isLoading.value = true
            try {
                val initial = loadPagedExpensesPage(key, limit = PAGE_SIZE, offset = 0)
                if (requestId != loadInitialAllRequestId || key != currentQueryKey()) return@launch
                _pagedExpenses.value = initial
                _currentPage.value = 0
                _hasReachedEnd.value = initial.size < PAGE_SIZE
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.w("Transactions: TRANSACTION_LOAD_FAILED class=%s", e::class.java.simpleName)
                _error.emit(com.yourname.expensetracker.domain.model.UiText.DynamicString("Failed to load transactions (TRANSACTION_LOAD_FAILED)"))
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
        allCountJob?.cancel()
        _isLoadingMoreState.value = false
        _currentPage.value = 0
        _pagedExpenses.value = emptyList()
        _hasReachedEnd.value = false
        _unrankedCount.value = null
    }

    /**
     * One ALL-tab page under [key]. H comes only from the key's amount semantics: the typed
     * Available code (HomeComparable) or the amount-ops-disabled mode (Loading/Unavailable, H-1).
     * The VM never requests the assistant-only raw amount mode. While amount operations are
     * disabled no min/max is passed at all.
     */
    private suspend fun loadPagedExpensesPage(key: AllQueryKey, limit: Int, offset: Int): List<ExpenseWithCategory> {
        return withContext(Dispatchers.IO) {
            val activeFilter = key.filter
            val amountOpsActive = key.semantics is AmountSemantics.HomeComparable
            expenseRepository.getExpensesPagedDynamic(
                limit = limit,
                offset = offset,
                searchQuery = key.search.takeIf { it.isNotBlank() },
                startDate = activeFilter?.dateRange?.first,
                endDate = activeFilter?.dateRange?.second,
                transactionType = activeFilter?.transactionType,
                categoryId = activeFilter?.categoryId,
                merchantName = activeFilter?.merchantName,
                ownershipFilter = activeFilter?.ownership ?: RepositoryOwnershipFilter.ALL,
                minAmount = activeFilter?.minAmount?.takeIf { amountOpsActive },
                maxAmount = activeFilter?.maxAmount?.takeIf { amountOpsActive },
                sortOrder = key.sortOrder,
                amountSemantics = key.semantics
            )
        }
    }

    /**
     * ALL-tab count of rows without a home value in H (INV-8, INV-9). Runs only for Available H
     * with an amount sort or bound; otherwise clears the count. A result is applied only if the
     * request key is still current (INV-10). A failure becomes `CountStatus.Failed` with a
     * bounded log; cancellation is rethrown.
     */
    private fun launchAllCount(key: AllQueryKey) {
        allCountJob?.cancel()
        val home = (key.semantics as? AmountSemantics.HomeComparable)?.home
        if (home == null || !isAmountEngaged(key.sortOrder, key.filter)) {
            _unrankedCount.value = null
            return
        }
        val countFilter = ExpenseCountFilter(
            searchQuery = key.search.takeIf { it.isNotBlank() },
            startDate = key.filter?.dateRange?.first,
            endDate = key.filter?.dateRange?.second,
            transactionType = key.filter?.transactionType,
            categoryId = key.filter?.categoryId,
            merchantName = key.filter?.merchantName,
            ownershipFilter = key.filter?.ownership ?: RepositoryOwnershipFilter.ALL
        )
        allCountJob = viewModelScope.launch {
            val status: CountStatus = try {
                CountStatus.Loaded(
                    withContext(Dispatchers.IO) {
                        expenseRepository.countExpensesWithoutHomeValue(countFilter, home)
                    }
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.w("Transactions: TRANSACTION_COUNT_FAILED class=%s", e::class.java.simpleName)
                CountStatus.Failed
            }
            if (key != currentQueryKey() || _selectedTab.value != TransactionTab.ALL) return@launch
            _unrankedCount.value = UnrankedCount(TransactionTab.ALL, home, status)
        }
    }

    private suspend fun refreshPagedExpensesAfterMutation() {
        if (_selectedTab.value != TransactionTab.ALL) return

        val loadedCount = _pagedExpenses.value.size
        if (loadedCount == 0) {
            loadInitialAll()
            return
        }

        val key = currentQueryKey()
        val refreshedPage = loadPagedExpensesPage(key, limit = loadedCount + 1, offset = 0)
        // INV-10: a refresh built under another key (H, sort, filters, search) is stale.
        if (key != currentQueryKey()) return
        launchAllCount(key) // the mutation may have changed how many rows lack a home value
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

    /**
     * In-memory min/max for time-range tabs (INV-7): membership by the rule owner
     * [ComparableHomeValue] against the typed Available H; a row without a comparable value is
     * excluded whenever a bound is set. While H is Loading/Unavailable no bound is applied
     * (INV-9). Also publishes the count of rows without a home value over the period list
     * (after the non-amount filters), which is exact because this list is not paged.
     */
    private fun applyAmountConstraints(
        tab: TransactionTab,
        expenses: List<ExpenseWithCategory>,
        filter: TransactionFilter?,
        home: ReadHomeCurrencyState
    ): List<ExpenseWithCategory> {
        val homeCode = (home as? ReadHomeCurrencyState.Available)?.code ?: return expenses

        _unrankedCount.value = UnrankedCount(
            tab = tab,
            home = homeCode,
            status = CountStatus.Loaded(
                expenses.count { ComparableHomeValue.comparableValue(it.expense, homeCode) is ComparableValue.None }
            )
        )

        val minAmount = filter?.minAmount
        val maxAmount = filter?.maxAmount
        if (minAmount == null && maxAmount == null) return expenses
        return expenses.filter { item ->
            ComparableHomeValue.withinBounds(item.expense, homeCode, minAmount, maxAmount)
        }
    }

    /**
     * In-memory ordering for time-range tabs, mirroring the SQL ORDER BY of the ALL tab.
     * [home] null (Loading/Unavailable) forces `date DESC, id DESC` for every sort (INV-9).
     * Rows without a comparable value sort last in both amount directions (INV-5).
     */
    private fun sortInMemory(
        expenses: List<ExpenseWithCategory>,
        order: SortOrder,
        home: CurrencyCode?
    ): List<ExpenseWithCategory> {
        val comparator: Comparator<Expense> = when {
            home == null || order == SortOrder.DATE_DESC -> DATE_DESC_COMPARATOR
            order == SortOrder.DATE_ASC -> DATE_ASC_COMPARATOR
            order == SortOrder.AMOUNT_DESC ->
                ComparableHomeValue.comparator(home, ComparableHomeValue.Direction.DESCENDING)
            else -> ComparableHomeValue.comparator(home, ComparableHomeValue.Direction.ASCENDING)
        }
        return expenses.sortedWith { a, b -> comparator.compare(a.expense, b.expense) }
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
