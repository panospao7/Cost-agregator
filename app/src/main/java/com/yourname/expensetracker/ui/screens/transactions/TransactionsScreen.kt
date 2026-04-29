package com.yourname.expensetracker.ui.screens.transactions

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.BuildConfig
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.TransferDirection
import com.yourname.expensetracker.data.database.model.ExpenseWithCategory
import com.yourname.expensetracker.data.database.model.formattedTime
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import com.yourname.expensetracker.ui.screens.transactions.TransactionsViewModel.OwnershipFilter
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.ui.screens.transactions.TransactionsViewModel.TransactionTab
import com.yourname.expensetracker.ui.theme.Dimens
import com.yourname.expensetracker.ui.theme.SemanticColors
import com.yourname.expensetracker.ui.components.TransferDirectionBadge
import com.yourname.expensetracker.ui.components.LocationSearchPicker
import com.yourname.expensetracker.ui.components.common.ListSkeleton
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

private fun OwnershipFilter.toRepositoryOwnershipFilter(): com.yourname.expensetracker.data.repository.OwnershipFilter {
    return when (this) {
        OwnershipFilter.ALL -> com.yourname.expensetracker.data.repository.OwnershipFilter.ALL
        OwnershipFilter.MINE -> com.yourname.expensetracker.data.repository.OwnershipFilter.MINE
        OwnershipFilter.NOT_MINE -> com.yourname.expensetracker.data.repository.OwnershipFilter.NOT_MINE
        OwnershipFilter.SHARED -> com.yourname.expensetracker.data.repository.OwnershipFilter.SHARED
        OwnershipFilter.TRANSFER -> com.yourname.expensetracker.data.repository.OwnershipFilter.TRANSFER
    }
}

// ============================================================
// MAIN SCREEN
// ============================================================

@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class, ExperimentalFoundationApi::class)
@Composable
fun TransactionsScreen(
    viewModel: TransactionsViewModel = hiltViewModel(),
    initialFilter: TransactionFilter? = null,
    highlightedExpenseId: Long? = null,
    onNavigateToAnalytics: () -> Unit = {},
    onAddExpense: () -> Unit = {},
    onOpenVisualSplit: (Expense) -> Unit = {}
) {
    val transactions by viewModel.transactions.collectAsState()
    val groupedTransactions by viewModel.groupedTransactions.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val activeFilter by viewModel.filter.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val isLoadingMore by viewModel.isLoadingMoreState.collectAsState()
    val hasReachedEnd by viewModel.hasReachedEnd.collectAsState()
    val tabCounts by viewModel.tabTransactionCounts.collectAsState()
 val homeCurrency by viewModel.homeCurrency.collectAsState()
    val ownershipFilter by viewModel.ownershipFilter.collectAsState()
    val sortOrder by viewModel.sortOrder.collectAsState()
    val debugActionsEnabled = BuildConfig.DEBUG
    
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    
    // Initial filter application
    LaunchedEffect(initialFilter) {
        if (initialFilter != null) {
            viewModel.applyFilter(initialFilter)
        }
    }

    // Dialog states
    var expenseToDelete by remember { mutableStateOf<Expense?>(null) }
    var expenseToCategorize by remember { mutableStateOf<Expense?>(null) }
    var expenseToRecurring by remember { mutableStateOf<Expense?>(null) }
    var expenseToRename by remember { mutableStateOf<Expense?>(null) }
    var expenseToChangeType by remember { mutableStateOf<Expense?>(null) }
    var expenseToEditOwnership by remember { mutableStateOf<Expense?>(null) }
    var expenseToDebug by remember { mutableStateOf<Expense?>(null) }
    var expenseToEditLocation by remember { mutableStateOf<Expense?>(null) }
    var showSearch by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }
    
    // Pull-to-refresh state
    val pullToRefreshState = rememberPullToRefreshState()
    
    // Error handling
    val snackbarHostState = remember { SnackbarHostState() }
    
    // Collect error messages
    LaunchedEffect(Unit) {
        viewModel.error.collect { message ->
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short
            )
        }
    }
    
    // Collect success messages
    LaunchedEffect(Unit) {
        viewModel.successMessage.collect { message ->
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short,
                actionLabel = "OK"
            )
        }
    }
    
    // Detect when to load more for pagination
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            selectedTab == TransactionTab.ALL && 
            lastVisibleItem >= totalItems - 5 && 
            totalItems > 0 &&
            !isLoadingMore &&
            !hasReachedEnd
        }
    }
    
    // Trigger load more when needed
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            viewModel.loadMore()
        }
    }
    
    Scaffold(
        containerColor = SemanticColors.BaseNavy,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                // Header with search toggle
                TopAppBar(
                    title = { 
                        AnimatedContent(
                            targetState = showSearch,
                            transitionSpec = {
                                fadeIn() togetherWith fadeOut()
                            },
                            label = "SearchToggle"
                        ) { searching ->
                            if (searching) {
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { viewModel.search(it) },
                                    modifier = Modifier.fillMaxWidth(),
                                    placeholder = { Text(stringResource(R.string.transactions_search_placeholder)) },
                                    leadingIcon = { 
                                        Icon(Icons.Rounded.Search, contentDescription = null) 
                                    },
                                    trailingIcon = {
                                        IconButton(onClick = { 
                                            showSearch = false
                                            viewModel.search("")
                                        }) {
                                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.transactions_close_search_cd))
                                        }
                                    },
                                    singleLine = true,
                                    shape = RoundedCornerShape(28.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = SemanticColors.PrimaryIndigo,
                                        unfocusedBorderColor = SemanticColors.GlassBorder
                                    )
                                )
                            } else {
                                Text(stringResource(R.string.transactions_title))
                            }
                        }
                    },
                    actions = {
                        val navigateAnalyticsCd = stringResource(R.string.transactions_navigate_analytics_cd)
                        IconButton(
                            onClick = onNavigateToAnalytics,
                            modifier = Modifier.semantics { contentDescription = navigateAnalyticsCd }
                        ) {
                            Icon(Icons.Rounded.BarChart, contentDescription = null)
                        }
                        Box {
                            val openSortMenuCd = stringResource(R.string.transactions_open_sort_menu_cd, sortOrder.displayName)
                            IconButton(
                                onClick = { showSortMenu = true },
                                modifier = Modifier.semantics { contentDescription = openSortMenuCd }
                            ) {
                                Icon(Icons.Rounded.Sort, contentDescription = null)
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false }
                            ) {
                                val sortSelected = stringResource(R.string.transactions_sort_selected)
                                val sortNotSelected = stringResource(R.string.transactions_sort_not_selected)
                                com.yourname.expensetracker.data.repository.SortOrder.values().forEach { order ->
                                    val sortByCd = stringResource(R.string.transactions_sort_by_cd, order.displayName, if (sortOrder == order) sortSelected else sortNotSelected)
                                    DropdownMenuItem(
                                        text = { 
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(order.displayName)
                                                if (sortOrder == order) {
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Icon(
                                                        Icons.Rounded.Check, 
                                                        contentDescription = null,
                                                        modifier = Modifier.size(16.dp),
                                                        tint = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            viewModel.setSortOrder(order)
                                            showSortMenu = false
                                        },
                                        modifier = Modifier.semantics { 
                                            contentDescription = sortByCd
                                        }
                                    )
                                }
                            }
                        }
                        val openFiltersCd = stringResource(R.string.transactions_open_filters_cd)
                        IconButton(
                            onClick = { showFilterSheet = true },
                            modifier = Modifier.semantics { contentDescription = openFiltersCd }
                        ) {
                            Icon(Icons.Rounded.FilterList, contentDescription = null)
                        }
                        if (!showSearch) {
                            val openSearchCd = stringResource(R.string.transactions_open_search_cd)
                            IconButton(
                                onClick = { showSearch = true },
                                modifier = Modifier.semantics { contentDescription = openSearchCd }
                            ) {
                                Icon(Icons.Rounded.Search, contentDescription = null)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = SemanticColors.BaseNavy,
                        titleContentColor = SemanticColors.TextPrimary
                    )
                )
                
                // Tab row with counts - scrollable for proper tab widths
                val tabSelectedStr = stringResource(R.string.transactions_tab_selected)
                val tabNotSelectedStr = stringResource(R.string.transactions_tab_not_selected)
                ScrollableTabRow(
                    selectedTabIndex = selectedTab.ordinal,
                    edgePadding = 0.dp,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab.ordinal])
                                .padding(horizontal = 16.dp)
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)),
                            height = 4.dp,
                            color = SemanticColors.PrimaryIndigo
                        )
                    },
                    divider = {}
                ) {
                    TransactionTab.values().forEach { tab ->
                        val count = tabCounts[tab] ?: 0
                        val tabCd = stringResource(R.string.transactions_tab_cd_format, tab.label, if (selectedTab == tab) tabSelectedStr else tabNotSelectedStr, count)
                        Tab(
                            selected = selectedTab == tab,
                            onClick = { 
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                viewModel.selectTab(tab)
                                scope.launch { listState.animateScrollToItem(0) }
                            },
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = tab.label,
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                    if (count > 0 && tab != TransactionTab.ALL) {
                                        Badge(
                                            containerColor = SemanticColors.PrimaryIndigo.copy(alpha = 0.2f),
                                            contentColor = SemanticColors.PrimaryIndigo
                                        ) {
                                            Text(
                                                text = if (count > 99) "99+" else count.toString(),
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.semantics { 
                                contentDescription = tabCd
                            }
                        )
                    }
                }
                
                // Active Filter Banner
                AnimatedVisibility(visible = activeFilter != null) {
                    Surface(
                        color = SemanticColors.PrimaryIndigo.copy(alpha = 0.1f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val filteredByPrefix = stringResource(R.string.transactions_filtered_by_prefix)
                        val ownershipMine = stringResource(R.string.transactions_ownership_mine)
                        val ownershipNotMine = stringResource(R.string.transactions_ownership_not_mine)
                        val ownershipShared = stringResource(R.string.transactions_ownership_shared)
                        val ownershipTransfers = stringResource(R.string.transactions_ownership_transfers)
                        val ownershipAll = stringResource(R.string.transactions_ownership_all)
                        val clearFiltersCd = stringResource(R.string.transactions_clear_all_filters_cd)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                        val filterText = buildString {
                            append(filteredByPrefix)
                            val parts = mutableListOf<String>()
                            activeFilter?.merchantName?.let { parts.add(stringResource(R.string.transactions_filter_merchant_format, it)) }
                            activeFilter?.categoryId?.let { id -> 
                                categories.find { it.id == id }?.name?.let { parts.add(stringResource(R.string.transactions_filter_category_format, it)) }
                            }
                            activeFilter?.transactionType?.let { parts.add(stringResource(R.string.transactions_filter_type_format, it.name)) }
                            activeFilter?.dateRange?.let { parts.add(stringResource(R.string.transactions_filter_date_range)) }
                            activeFilter?.ownership?.let {
                                val label = when (it) {
                                    com.yourname.expensetracker.data.repository.OwnershipFilter.MINE -> ownershipMine
                                    com.yourname.expensetracker.data.repository.OwnershipFilter.NOT_MINE -> ownershipNotMine
                                    com.yourname.expensetracker.data.repository.OwnershipFilter.SHARED -> ownershipShared
                                    com.yourname.expensetracker.data.repository.OwnershipFilter.TRANSFER -> ownershipTransfers
                                    com.yourname.expensetracker.data.repository.OwnershipFilter.ALL -> ownershipAll
                                }
                                parts.add(stringResource(R.string.transactions_filter_ownership_format, label))
                            }
                            activeFilter?.minAmount?.let { parts.add(stringResource(R.string.transactions_filter_min_format, it)) }
                            activeFilter?.maxAmount?.let { parts.add(stringResource(R.string.transactions_filter_max_format, it)) }
                            append(parts.joinToString(", "))
                        }
                            
                            Text(
                                text = filterText,
                                style = MaterialTheme.typography.labelMedium,
                                color = SemanticColors.PrimaryIndigo,
                                modifier = Modifier.weight(1f)
                            )
                            
                            IconButton(
                                onClick = { viewModel.clearFilter() },
                                modifier = Modifier
                                    .size(48.dp)
                                    .semantics { contentDescription = clearFiltersCd }
                            ) {
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription = null,
                                    tint = SemanticColors.PrimaryIndigo,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        PullToRefreshBox(
            state = pullToRefreshState,
            isRefreshing = isRefreshing,
            onRefresh = { 
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                viewModel.refresh()
            },
            modifier = Modifier.padding(padding)
        ) {
            when {
                isLoading && transactions.isEmpty() -> {
                    // Initial loading state with skeleton
                    ListSkeleton(
                        itemCount = 8,
                        modifier = Modifier.padding(16.dp)
                    )
                }
                transactions.isEmpty() -> {
                    // Empty state with illustration
                    EmptyTransactionsState(
                        hasSearch = searchQuery.isNotBlank(),
                        onAddClick = onAddExpense
                    )
                }
                else -> {
                    // Transaction list with date grouping
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(
                            start = 20.dp,
                            end = 20.dp,
                            top = 8.dp,
                            bottom = 80.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Group transactions by date
                        groupedTransactions.forEach { (dateString, items) ->
                            // Date header
                            stickyHeader {
            DateHeader(
                    date = dateString,
                    totalAmount = items.sumOf { it.expense.signedEffectiveAmount() },
                    itemCount = items.size,
                    homeCurrency = homeCurrency
                )
                            }
                            
                            // Transactions for this date
                            items(
                                items = items,
                                key = { item -> item.expense.id },
                                contentType = { "transaction" }
                            ) { item ->
                                TransactionItem(
                                    transaction = item,
                                    isHighlighted = highlightedExpenseId == item.expense.id,
                                    onDelete = { expenseToDelete = item.expense },
                                    onEditCategory = { expenseToCategorize = item.expense },
                                    onMarkRecurring = { expenseToRecurring = item.expense },
                                    onRename = { expenseToRename = item.expense },
                                    onChangeType = { expenseToChangeType = item.expense },
                                    onEditOwnership = { expenseToEditOwnership = item.expense },
                                    onDebug = {
                                        if (debugActionsEnabled) {
                                            expenseToDebug = item.expense
                                        }
                                    },
                                    onEditLocation = { expenseToEditLocation = item.expense }
                                )
                            }
                        }
                        
                        // Loading more indicator
                        if (isLoadingMore) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        color = SemanticColors.PrimaryIndigo,
                                        strokeWidth = 2.dp
                                    )
                                }
                            }
                        } else if (selectedTab == TransactionTab.ALL && hasReachedEnd && transactions.isNotEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 20.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "You've reached the end",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = SemanticColors.TextMuted
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ============================================================
        // DIALOGS
        // ============================================================

        // Delete confirmation dialog
        if (expenseToDelete != null) {
            DeleteConfirmationDialog(
                expense = expenseToDelete!!,
                onDismiss = { expenseToDelete = null },
                onConfirm = {
                    viewModel.deleteExpense(expenseToDelete!!)
                    expenseToDelete = null
                }
            )
        }

        // Recurrence picker dialog
        if (expenseToRecurring != null) {
            RecurrencePickerDialog(
                onDismiss = { expenseToRecurring = null },
                onFrequencySelected = { frequency ->
                    expenseToRecurring?.let { viewModel.markAsRecurring(it, frequency) }
                    expenseToRecurring = null
                }
            )
        }

        // Category picker dialog
        if (expenseToCategorize != null) {
            CategoryPickerDialog(
                categories = categories,
                currentCategoryId = expenseToCategorize?.categoryId,
                currentMerchant = expenseToCategorize?.merchant,
                onDismiss = { expenseToCategorize = null },
                onCategorySelected = { categoryId, applyToAll ->
                    expenseToCategorize?.let { viewModel.updateCategory(it, categoryId, applyToAll) }
                    expenseToCategorize = null
                }
            )
        }

        // Filter bottom sheet
        if (showFilterSheet) {
            TransactionFilterSheet(
                categories = categories,
                currentFilter = activeFilter,
                currentOwnershipFilter = ownershipFilter,
                referenceNowMs = viewModel.referenceNow(),
                onDismiss = { showFilterSheet = false },
                onApply = { filter, ownership ->
                    if (filter != null || ownership != OwnershipFilter.ALL) {
                        viewModel.applyFilter(
                            (filter ?: TransactionFilter()).copy(ownership = ownership.toRepositoryOwnershipFilter())
                        )
                    } else {
                        viewModel.clearFilter()
                    }
                    showFilterSheet = false
                },
                onClear = {
                    viewModel.clearFilter()
                    showFilterSheet = false
                }
            )
        }

        // Rename merchant dialog
        if (expenseToRename != null) {
            RenameMerchantDialog(
                currentName = expenseToRename?.merchant ?: "",
                onDismiss = { expenseToRename = null },
                onConfirm = { newName, applyToAll ->
                    expenseToRename?.let { viewModel.updateMerchant(it, newName, applyToAll) }
                    expenseToRename = null
                }
            )
        }

        // Change type dialog
        if (expenseToChangeType != null) {
            ChangeTypeDialog(
                currentType = expenseToChangeType?.transactionType ?: TransactionType.PURCHASE,
                currentTransferDirection = expenseToChangeType?.transferDirection,
                currentTransferAccountName = expenseToChangeType?.transferAccountName,
                onDismiss = { expenseToChangeType = null },
                onConfirm = { newType, transferDirection, transferAccountName ->
                    expenseToChangeType?.let {
                        viewModel.updateExpenseType(
                            expense = it,
                            newType = newType,
                            transferDirection = transferDirection,
                            transferAccountName = transferAccountName
                        )
                    }
                    expenseToChangeType = null
                }
            )
        }

        // Edit ownership/not-mine/shared dialog
        if (expenseToEditOwnership != null) {
            EditOwnershipDialog(
                expense = expenseToEditOwnership!!,
                onDismiss = { expenseToEditOwnership = null },
                onOpenVisualSplit = { expense ->
                    expenseToEditOwnership = null
                    onOpenVisualSplit(expense)
                },
        onSave = { isNotMine, ownerName, isShared, sharedWith, sharePercent, shareAmount ->
            expenseToEditOwnership?.let { expense ->
                val finalIsShared = isShared && !isNotMine
                viewModel.updateOwnership(
                    expense = expense,
                    isNotMine = isNotMine,
                    ownerName = ownerName,
                    isSharedExpense = finalIsShared,
                    sharedWithName = sharedWith,
                    mySharePercentage = sharePercent,
                    myShareAmount = shareAmount
                )
            }
            expenseToEditOwnership = null
        }
            )
        }
        
        // Debug Screen Overlay
        if (debugActionsEnabled && expenseToDebug != null) {
            Dialog(
                onDismissRequest = { expenseToDebug = null },
                properties = androidx.compose.ui.window.DialogProperties(
                    usePlatformDefaultWidth = false,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = false
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    com.yourname.expensetracker.ui.screens.debug.CategorizationDebugScreen(
                        initialMerchant = expenseToDebug!!.merchant,
                        initialAmount = expenseToDebug!!.amount,
                        initialTimestamp = expenseToDebug!!.date,
                        onNavigateBack = { expenseToDebug = null }
                    )
                }
            }
        }

        // Edit location bottom sheet
        if (expenseToEditLocation != null) {
            EditLocationDialog(
                expense = expenseToEditLocation!!,
                onDismiss = { expenseToEditLocation = null },
                onSave = { lat, lon, address, osmId ->
                    expenseToEditLocation?.let { viewModel.updateLocation(it, lat, lon, address, osmId) }
                    expenseToEditLocation = null
                },
                onClear = {
                    expenseToEditLocation?.let { viewModel.clearLocation(it) }
                    expenseToEditLocation = null
                },
                geocodingService = viewModel.geocodingService
            )
        }
    }
}

// ============================================================
// EMPTY STATE COMPONENT
// ============================================================

@Composable
private fun EmptyTransactionsState(
    hasSearch: Boolean,
    onAddClick: () -> Unit
) {
    val emptySearchCd = stringResource(R.string.transactions_empty_search_cd)
    val emptyNoTransactionsCd = stringResource(R.string.transactions_empty_no_transactions_cd)
    val addFirstExpenseCd = stringResource(R.string.transactions_add_first_expense_cd)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .semantics { 
                contentDescription = if (hasSearch) emptySearchCd else emptyNoTransactionsCd
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Animated illustration
            val infiniteTransition = rememberInfiniteTransition(label = "float")
            val offsetY by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = -10f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1500, easing = EaseInOutQuad),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "floatY"
            )
            
            Icon(
                imageVector = if (hasSearch) Icons.Rounded.SearchOff else Icons.Rounded.ReceiptLong,
                contentDescription = null,
                modifier = Modifier
                    .size(100.dp)
                    .offset(y = offsetY.dp),
                tint = SemanticColors.TextMuted
            )
            
            Text(
                text = if (hasSearch) stringResource(R.string.transactions_no_search_results_title) else stringResource(R.string.no_transactions_title),
                style = MaterialTheme.typography.titleMedium,
                color = SemanticColors.TextPrimary
            )
            
            Text(
                text = if (hasSearch) {
                    stringResource(R.string.transactions_no_search_results_subtitle)
                } else {
                    stringResource(R.string.no_transactions_subtitle)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = SemanticColors.TextSecondary,
                textAlign = TextAlign.Center
            )
            
            if (!hasSearch) {
                FilledTonalButton(
                    onClick = onAddClick,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = SemanticColors.PrimaryIndigo.copy(alpha = 0.2f),
                        contentColor = SemanticColors.PrimaryIndigo
                    ),
                    modifier = Modifier.semantics { contentDescription = addFirstExpenseCd }
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.transactions_add_expense_button))
                }
            }
        }
    }
}

// ============================================================
// DATE HEADER COMPONENT
// ============================================================

@Composable
private fun DateHeader(
    date: String,
    totalAmount: Double,
    itemCount: Int,
    homeCurrency: String = "EUR"
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .background(MaterialTheme.colorScheme.surface) // Solid background underneath for sticky visibility
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(SemanticColors.GlassSurface),
            color = Color.Transparent, // Let the background modifier handle the glass fill
            border = BorderStroke(1.dp, SemanticColors.GlassBorder)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = date,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = SemanticColors.TextPrimary
                    )
                    Text(
                        text = stringResource(R.string.transactions_date_header_count_format, itemCount, if (itemCount == 1) stringResource(R.string.transactions_date_header_count_suffix_single) else stringResource(R.string.transactions_date_header_count_suffix_plural)),
                        style = MaterialTheme.typography.labelSmall,
                        color = SemanticColors.TextMuted,
                        fontWeight = FontWeight.Medium
                    )
                }
                
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = when {
                        totalAmount < 0 -> SemanticColors.DangerRed.copy(alpha = 0.1f)
                        totalAmount > 0 -> SemanticColors.SuccessGreen.copy(alpha = 0.1f)
                        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    }
                ) {
                    Text(
                        text = CurrencyFormatter.formatWithSign(totalAmount, homeCurrency),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            totalAmount < 0 -> SemanticColors.DangerRed
                            totalAmount > 0 -> SemanticColors.SuccessGreen
                            else -> SemanticColors.TextSecondary
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

// ============================================================
// TRANSACTION ITEM COMPONENT
// ============================================================

@Composable
private fun TransactionItem(
    transaction: ExpenseWithCategory,
    isHighlighted: Boolean,
    onDelete: () -> Unit,
    onEditCategory: () -> Unit,
    onMarkRecurring: () -> Unit,
    onRename: () -> Unit,
    onChangeType: () -> Unit,
    onEditOwnership: () -> Unit,
    onDebug: () -> Unit,
    onEditLocation: () -> Unit
) {
    val expense = transaction.expense
    val category = transaction.category
    var showActionMenu by remember { mutableStateOf(false) }
    
    // Pre-load string resources
    val uncategorizedLabel = stringResource(R.string.uncategorized_label)
    val categoryCdFormat = stringResource(R.string.transactions_category_cd_format, category?.name ?: uncategorizedLabel)
    val categoryUncategorizedCd = stringResource(R.string.transactions_category_uncategorized_cd)
    val merchantRenameCd = stringResource(R.string.transactions_merchant_rename_cd_format, expense.merchant)
    val manualEntryCd = stringResource(R.string.transactions_manual_entry_cd)
    val doubleTapToEditCd = stringResource(R.string.transactions_double_tap_to_edit_cd)
    val paymentMethodCash = stringResource(R.string.transactions_payment_method_cash)
    val paymentMethodBankTransfer = stringResource(R.string.transactions_payment_method_bank_transfer)
    val paymentMethodCard = stringResource(R.string.transactions_payment_method_card)
    val changeTypeCd = stringResource(R.string.transactions_change_type_cd_format, expense.transactionType.name)
    val typePurchase = stringResource(R.string.transactions_transaction_type_purchase)
    val typeDeposit = stringResource(R.string.transactions_transaction_type_deposit)
    val typeWithdrawal = stringResource(R.string.transactions_transaction_type_withdrawal)
    val typeTransfer = stringResource(R.string.transactions_transaction_type_transfer)
    val typeUnknown = stringResource(R.string.transactions_transaction_type_unknown)
    val ownershipNotMine = stringResource(R.string.transactions_ownership_not_mine_icon)
    val ownershipShared = stringResource(R.string.transactions_ownership_shared_icon)
    val ownershipTransfer = stringResource(R.string.transactions_ownership_transfer_icon)
    val ownershipSettings = stringResource(R.string.transactions_ownership_settings_icon)
    val editOwnershipCd = stringResource(R.string.transactions_edit_ownership_cd)
    val markRecurringCd = stringResource(R.string.transactions_mark_recurring_cd)
    val editLocationCd = stringResource(R.string.transactions_edit_location_cd)
    val addLocationCd = stringResource(R.string.transactions_add_location_cd)
    val deleteTransactionCd = stringResource(R.string.transactions_delete_transaction_cd)
    val moreOptionsCd = stringResource(R.string.a11y_more_options)
    val debugMenuLabel = stringResource(R.string.home_debug_menu)
    
    // Safe color parsing with fallback
    val categoryColor = remember(transaction.categoryColor) {
        try {
            Color(transaction.categoryColor.toInt())
        } catch (e: Exception) {
            SemanticColors.PrimaryIndigo
        }
    }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isHighlighted) {
                SemanticColors.PrimaryIndigo.copy(alpha = 0.16f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Category Icon (Clickable)
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                categoryColor,
                                categoryColor.copy(alpha = 0.7f)
                            )
                        ),
                        shape = CircleShape
                    )
                    .clickable { onEditCategory() }
                    .padding(4.dp)
                    .semantics { contentDescription = categoryCdFormat },
                contentAlignment = Alignment.Center
            ) {
                if (category?.icon != null) {
                    val categoryIconCd = stringResource(R.string.transactions_category_icon_cd_format, category.name)
                    Text(
                        text = category.icon,
                        fontSize = 26.sp,
                        modifier = Modifier.semantics { contentDescription = categoryIconCd }
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.HelpOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            // Transaction Info
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // Merchant row with edit indicator
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = expense.merchant,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onRename() }
                            .semantics { contentDescription = merchantRenameCd }
                    )
                    
                    // Manual entry indicator
                    if (expense.isManualEntry) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = SemanticColors.PrimaryIndigo.copy(alpha = 0.15f)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Edit,
                                contentDescription = manualEntryCd,
                                modifier = Modifier
                                    .size(16.dp)
                                    .padding(horizontal = 4.dp, vertical = 1.dp),
                                tint = SemanticColors.PrimaryIndigo
                            )
                        }
                    }
                    
                    // Edit icon hint
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = null,
                        modifier = Modifier
                            .size(14.dp)
                            .semantics { contentDescription = doubleTapToEditCd },
                        tint = SemanticColors.TextMuted.copy(alpha = 0.5f)
                    )
                }
                
                // Category and payment method row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Payment method icon - remember to avoid recalculation
                    val (methodIcon, methodDesc) = remember(expense.paymentMethod, paymentMethodCash, paymentMethodBankTransfer, paymentMethodCard) {
                        when (expense.paymentMethod) {
                            PaymentMethod.CASH -> Icons.Rounded.Payments to paymentMethodCash
                            PaymentMethod.BANK_TRANSFER -> Icons.Rounded.AccountBalance to paymentMethodBankTransfer
                            PaymentMethod.CARD -> Icons.Rounded.CreditCard to paymentMethodCard
                            else -> null to null
                        }
                    }
                    
                    if (methodIcon != null) {
                        Icon(
                            imageVector = methodIcon,
                            contentDescription = methodDesc,
                            modifier = Modifier.size(14.dp),
                            tint = SemanticColors.TextSecondary
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                    }
                    
                    Text(
                        text = category?.name ?: uncategorizedLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = SemanticColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onEditCategory() }
                            .semantics { contentDescription = if (category != null) categoryCdFormat else categoryUncategorizedCd }
                    )
                }
                
                // Transfer Direction Badge (for transfers and deposits)
                if (expense.transactionType == TransactionType.TRANSFER || 
                    expense.transactionType == TransactionType.DEPOSIT) {
                    Spacer(modifier = Modifier.height(4.dp))
                    TransferDirectionBadge(
                        direction = expense.transferDirection,
                        accountName = expense.transferAccountName,
                        compact = true
                    )
                }
                
                // Time
                Text(
                    text = transaction.formattedTime,
                    style = MaterialTheme.typography.labelSmall,
                    color = SemanticColors.TextMuted
                )

                // Resolved address subtitle
                if (expense.resolvedAddress != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.LocationOn,
                            contentDescription = null,
                            modifier = Modifier.size(10.dp),
                            tint = SemanticColors.TextMuted.copy(alpha = 0.7f)
                        )
                        Text(
                            text = expense.resolvedAddress,
                            style = MaterialTheme.typography.labelSmall,
                            color = SemanticColors.TextMuted.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Amount
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = transaction.formattedAmount,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 18.sp,
                        fontFeatureSettings = "tnum"
                    ),
                    color = SemanticColors.TextPrimary
                )
                

                
                // Action buttons row (primary + overflow to preserve text space)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Change type action
                    IconButton(
                        onClick = onChangeType,
                        modifier = Modifier
                            .size(Dimens.TouchTargetMin)
                            .semantics { contentDescription = changeTypeCd }
                    ) {
                        val typeIcon = when (expense.transactionType) {
                            TransactionType.PURCHASE -> Icons.Rounded.ShoppingCart
                            TransactionType.DEPOSIT -> Icons.Rounded.ArrowCircleDown
                            TransactionType.WITHDRAWAL -> Icons.Rounded.ArrowCircleUp
                            TransactionType.TRANSFER -> Icons.Rounded.SyncAlt
                            else -> Icons.Rounded.HelpOutline
                        }
                        Icon(
                            imageVector = typeIcon,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = SemanticColors.PrimaryIndigo.copy(alpha = 0.6f)
                        )
                    }

                    // Overflow menu for secondary actions
                    Box {
                        IconButton(
                            onClick = { showActionMenu = true },
                            modifier = Modifier
                                .size(Dimens.TouchTargetMin)
                                .semantics { contentDescription = moreOptionsCd }
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MoreVert,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = SemanticColors.TextSecondary
                            )
                        }

                        DropdownMenu(
                            expanded = showActionMenu,
                            onDismissRequest = { showActionMenu = false }
                        ) {
                            val (ownershipIcon, _) = remember(expense.isNotMine, expense.isSharedExpense, expense.transactionType, ownershipNotMine, ownershipShared, ownershipTransfer, ownershipSettings) {
                                when {
                                    expense.isNotMine -> Icons.Rounded.Person to ownershipNotMine
                                    expense.isSharedExpense -> Icons.Rounded.People to ownershipShared
                                    expense.transactionType == TransactionType.TRANSFER -> Icons.Rounded.SyncAlt to ownershipTransfer
                                    else -> Icons.Rounded.Settings to ownershipSettings
                                }
                            }

                            DropdownMenuItem(
                                text = { Text(editOwnershipCd) },
                                onClick = {
                                    showActionMenu = false
                                    onEditOwnership()
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = ownershipIcon,
                                        contentDescription = null
                                    )
                                }
                            )

                            DropdownMenuItem(
                                text = { Text(markRecurringCd) },
                                onClick = {
                                    showActionMenu = false
                                    onMarkRecurring()
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Repeat,
                                        contentDescription = null
                                    )
                                }
                            )

                            DropdownMenuItem(
                                text = { Text(if (expense.latitude != null) editLocationCd else addLocationCd) },
                                onClick = {
                                    showActionMenu = false
                                    onEditLocation()
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (expense.latitude != null) Icons.Filled.LocationOn else Icons.Rounded.AddLocationAlt,
                                        contentDescription = null
                                    )
                                }
                            )

                            if (BuildConfig.DEBUG) {
                                DropdownMenuItem(
                                    text = { Text(debugMenuLabel) },
                                    onClick = {
                                        showActionMenu = false
                                        onDebug()
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Rounded.BugReport,
                                            contentDescription = null
                                        )
                                    }
                                )
                            }

                            DropdownMenuItem(
                                text = { Text(deleteTransactionCd) },
                                onClick = {
                                    showActionMenu = false
                                    onDelete()
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Delete,
                                        contentDescription = null,
                                        tint = SemanticColors.DangerRed.copy(alpha = 0.8f)
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun Expense.signedEffectiveAmount(): Double {
    return when (transactionType.toDomainType()) {
        DomainTransactionType.PURCHASE,
        DomainTransactionType.WITHDRAWAL -> -effectiveAmount
        DomainTransactionType.DEPOSIT -> effectiveAmount
        DomainTransactionType.TRANSFER,
        DomainTransactionType.UNKNOWN -> 0.0
    }
}

private fun TransactionType.toDomainType(): DomainTransactionType {
    return when (this) {
        TransactionType.PURCHASE -> DomainTransactionType.PURCHASE
        TransactionType.WITHDRAWAL -> DomainTransactionType.WITHDRAWAL
        TransactionType.TRANSFER -> DomainTransactionType.TRANSFER
        TransactionType.DEPOSIT -> DomainTransactionType.DEPOSIT
        TransactionType.UNKNOWN -> DomainTransactionType.UNKNOWN
    }
}

// ============================================================
// DIALOG COMPONENTS
// ============================================================

@Composable
private fun DeleteConfirmationDialog(
    expense: Expense,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { 
            Icon(
                Icons.Rounded.Warning, 
                contentDescription = null,
                tint = SemanticColors.DangerRed,
                modifier = Modifier.size(32.dp)
            ) 
        },
        title = { Text(stringResource(R.string.delete_transaction_title)) },
        text = { 
            Text(
                stringResource(
                    R.string.delete_transaction_confirmation, 
                    expense.merchant
                )
            ) 
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = SemanticColors.DangerRed
                )
            ) {
                Text(stringResource(R.string.delete_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel_button))
            }
        }
    )
}

@Composable
fun RecurrencePickerDialog(
    onDismiss: () -> Unit,
    onFrequencySelected: (RecurrenceFrequency) -> Unit
) {
    val frequencies = RecurrenceFrequency.values()
    
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Repeat, contentDescription = null) },
        title = { Text(stringResource(R.string.select_frequency_title)) },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(frequencies) { frequency ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onFrequencySelected(frequency) },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                    val icon = when (frequency) {
                        RecurrenceFrequency.WEEKLY -> Icons.Rounded.DateRange
                        RecurrenceFrequency.BIWEEKLY -> Icons.Rounded.DateRange
                        RecurrenceFrequency.MONTHLY -> Icons.Rounded.CalendarMonth
                        RecurrenceFrequency.QUARTERLY -> Icons.Rounded.CalendarViewMonth
                        RecurrenceFrequency.SEMI_ANNUALLY -> Icons.Rounded.Event
                        RecurrenceFrequency.ANNUALLY -> Icons.Rounded.Event
                        RecurrenceFrequency.IRREGULAR -> Icons.Rounded.HelpOutline
                    }
                            
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = SemanticColors.PrimaryIndigo,
                                modifier = Modifier.size(24.dp)
                            )
                            
                            Spacer(modifier = Modifier.width(12.dp))
                            
                            Text(
                                text = frequency.name.replace("_", " ").lowercase()
                                    .replaceFirstChar { 
                                        if (it.isLowerCase()) it.titlecase(Locale.getDefault()) 
                                        else it.toString() 
                                    },
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel_button))
            }
        }
    )
}

@Composable
fun CategoryPickerDialog(
    categories: List<Category>,
    currentCategoryId: Long?,
    currentMerchant: String?,
    onDismiss: () -> Unit,
    onCategorySelected: (Long, Boolean) -> Unit
) {
    var searchText by remember { mutableStateOf("") }
    var applyToAll by remember { mutableStateOf(false) }
    
    val filteredCategories = remember(categories, searchText) {
        if (searchText.isBlank()) {
            categories
        } else {
            categories.filter { 
                it.name.contains(searchText, ignoreCase = true) 
            }
        }
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Category, contentDescription = null) },
        title = { Text(stringResource(R.string.select_category_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Search field
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.category_search_placeholder)) },
                    leadingIcon = { 
                        Icon(Icons.Rounded.Search, contentDescription = null) 
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
                
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filteredCategories) { category ->
                        val isSelected = category.id == currentCategoryId
                        
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onCategorySelected(category.id, applyToAll) },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) {
                                SemanticColors.PrimaryIndigo.copy(alpha = 0.15f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = category.icon, 
                                    fontSize = 24.sp,
                                    modifier = Modifier.padding(end = 12.dp)
                                )
                                
                                Text(
                                    text = category.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f)
                                )
                                
                                if (isSelected) {
                                    Icon(
                                        Icons.Rounded.Check,
                                        contentDescription = stringResource(R.string.transactions_selected_cd),
                                        tint = SemanticColors.PrimaryIndigo,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                if (currentMerchant != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { applyToAll = !applyToAll }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = applyToAll,
                            onCheckedChange = { applyToAll = it }
                        )
                        Text(
                            text = stringResource(R.string.transactions_apply_to_all_past, currentMerchant),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel_button))
            }
        }
    )
}

@Composable
fun RenameMerchantDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String, Boolean) -> Unit
) {
    var name by remember(currentName) { mutableStateOf(currentName) }
    var applyToAll by remember { mutableStateOf(false) }
    var isError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
        title = { Text(stringResource(R.string.transactions_rename_merchant_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    stringResource(R.string.transactions_rename_merchant_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = SemanticColors.TextSecondary
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        isError = it.isBlank()
                    },
                    label = { Text(stringResource(R.string.transactions_brand_name_label)) },
                    singleLine = true,
                    isError = isError,
                    supportingText = if (isError) {
                        { Text(stringResource(R.string.transactions_brand_name_empty_error)) }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = {
                        Icon(Icons.Rounded.Store, contentDescription = null)
                    },
                    shape = RoundedCornerShape(12.dp)
                )

                // Apply to All Checkbox
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { applyToAll = !applyToAll }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = applyToAll,
                        onCheckedChange = { applyToAll = it },
                        colors = CheckboxDefaults.colors(checkedColor = SemanticColors.PrimaryIndigo)
                    )
                    Text(
                        stringResource(R.string.transactions_apply_to_all_entries),
                        style = MaterialTheme.typography.bodySmall,
                        color = SemanticColors.TextPrimary
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        onConfirm(name, applyToAll)
                    } else {
                        isError = true
                    }
                },
                enabled = name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SemanticColors.PrimaryIndigo
                )
            ) {
                Text(stringResource(R.string.save_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel_button))
            }
        }
    )
}

@Composable
fun ChangeTypeDialog(
    currentType: TransactionType,
    currentTransferDirection: TransferDirection?,
    currentTransferAccountName: String?,
    onDismiss: () -> Unit,
    onConfirm: (TransactionType, TransferDirection?, String) -> Unit
) {
    var selectedType by remember { mutableStateOf(currentType) }
    var transferDirection by remember { mutableStateOf(currentTransferDirection) }
    var transferAccountName by remember { mutableStateOf(currentTransferAccountName.orEmpty()) }
    val transferAccountNameTrimmed = transferAccountName.trim()
    val hasTransferMetadataChange = currentType == TransactionType.TRANSFER && selectedType == TransactionType.TRANSFER && (
        transferDirection != currentTransferDirection ||
            transferAccountNameTrimmed != currentTransferAccountName.orEmpty().trim()
        )
    val transferMetadataValid = selectedType != TransactionType.TRANSFER ||
        (transferDirection != null && transferAccountNameTrimmed.isNotBlank())

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.SwapHoriz, contentDescription = null) },
        title = { Text(stringResource(R.string.transactions_change_type_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.transactions_change_type_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = SemanticColors.TextSecondary
                )

                Spacer(modifier = Modifier.height(8.dp))

                TransactionType.values().filter { it != TransactionType.UNKNOWN }.forEach { type ->
                    val isSelected = selectedType == type
                    val typeColor = when (type) {
                        TransactionType.PURCHASE -> SemanticColors.DangerRed
                        TransactionType.DEPOSIT -> SemanticColors.SuccessGreen
                        TransactionType.WITHDRAWAL -> SemanticColors.WarningOrange
                        TransactionType.TRANSFER -> SemanticColors.PrimaryIndigo
                        else -> SemanticColors.TextSecondary
                    }
                    val typeIcon = when (type) {
                        TransactionType.PURCHASE -> "💸"
                        TransactionType.DEPOSIT -> "💰"
                        TransactionType.WITHDRAWAL -> "🏧"
                        TransactionType.TRANSFER -> "🔄"
                        else -> "❓"
                    }
                    val typeDescription = when (type) {
                        TransactionType.PURCHASE -> stringResource(R.string.transactions_type_purchase_description)
                        TransactionType.DEPOSIT -> stringResource(R.string.transactions_type_deposit_description)
                        TransactionType.WITHDRAWAL -> stringResource(R.string.transactions_type_withdrawal_description)
                        TransactionType.TRANSFER -> stringResource(R.string.transactions_type_transfer_description)
                        else -> ""
                    }

                    Surface(
                        onClick = { selectedType = type },
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) typeColor.copy(alpha = 0.15f) else Color.Transparent,
                        border = BorderStroke(1.dp, if (isSelected) typeColor else SemanticColors.GlassBorder)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(typeIcon, fontSize = 24.sp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    type.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) typeColor else SemanticColors.TextPrimary
                                )
                                Text(
                                    typeDescription,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SemanticColors.TextSecondary
                                )
                            }
                            if (isSelected) {
                                Icon(
                                    Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    tint = typeColor
                                )
                            }
                        }
                    }
                }

                if (selectedType == TransactionType.TRANSFER) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.add_expense_transfer_direction),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = transferDirection == TransferDirection.INCOMING,
                            onClick = { transferDirection = TransferDirection.INCOMING },
                            label = { Text(stringResource(R.string.add_expense_incoming)) },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = transferDirection == TransferDirection.OUTGOING,
                            onClick = { transferDirection = TransferDirection.OUTGOING },
                            label = { Text(stringResource(R.string.add_expense_outgoing)) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedTextField(
                        value = transferAccountName,
                        onValueChange = { transferAccountName = it.take(100) },
                        label = { Text(stringResource(R.string.add_expense_account_name_label)) },
                        placeholder = { Text(stringResource(R.string.add_expense_account_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = transferAccountNameTrimmed.isBlank()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selectedType, transferDirection, transferAccountNameTrimmed) },
                enabled = (selectedType != currentType || hasTransferMetadataChange) && transferMetadataValid,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SemanticColors.PrimaryIndigo
                )
            ) {
                Text(stringResource(R.string.transactions_update_type_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel_button))
            }
        }
    )
}

@Composable
fun EditOwnershipDialog(
    expense: Expense,
    onDismiss: () -> Unit,
    onOpenVisualSplit: (Expense) -> Unit,
    onSave: (isNotMine: Boolean, ownerName: String, isShared: Boolean, sharedWithName: String, sharePercentage: String, shareAmount: String) -> Unit
) {
    var isNotMine by remember { mutableStateOf(expense.isNotMine) }
    var ownerName by remember { mutableStateOf(expense.ownerName ?: "") }
    var isSharedExpense by remember { mutableStateOf(expense.isSharedExpense) }
    var sharedWithName by remember { mutableStateOf(expense.sharedWithName ?: "") }
    var mySharePercentage by remember { mutableStateOf(expense.mySharePercentage?.toString() ?: "") }
    var myShareAmount by remember { mutableStateOf(expense.myShareAmount?.toString() ?: "") }
    val canOpenVisualSplit = expense.id > 0L
    val parsedSharePercentage = mySharePercentage.toIntOrNull()
    val isSharePercentageInvalid = isSharedExpense && mySharePercentage.isNotBlank() &&
        (parsedSharePercentage == null || parsedSharePercentage !in 0..100)

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.PersonAdd, contentDescription = null) },
        title = { Text(stringResource(R.string.transactions_edit_expense_details_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    stringResource(R.string.transactions_ownership_section),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(R.string.transactions_not_mine_label))
                    Switch(
                        checked = isNotMine,
                        onCheckedChange = {
                            isNotMine = it
                            if (it) {
                                isSharedExpense = false
                            }
                        }
                    )
                }
                if (isNotMine) {
                    OutlinedTextField(
                        value = ownerName,
                        onValueChange = { ownerName = it },
                        label = { Text(stringResource(R.string.transactions_owner_name_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.transactions_owner_name_placeholder)) }
                    )
                }

                HorizontalDivider()

                Text(
                    stringResource(R.string.transactions_shared_expense_section),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(R.string.transactions_split_with_someone))
                    Switch(
                        checked = isSharedExpense,
                        onCheckedChange = {
                            isSharedExpense = it
                            if (it) {
                                isNotMine = false
                            }
                        }
                    )
                }
                if (isSharedExpense) {
                    TextButton(
                        onClick = { onOpenVisualSplit(expense) },
                        enabled = canOpenVisualSplit,
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(Icons.Rounded.PieChart, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.feature_visual_split))
                    }
                    OutlinedTextField(
                        value = sharedWithName,
                        onValueChange = { sharedWithName = it },
                        label = { Text(stringResource(R.string.transactions_shared_with_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = mySharePercentage,
                            onValueChange = { input ->
                                val filtered = input.filter { c -> c.isDigit() }.take(3)
                                mySharePercentage = when {
                                    filtered.isBlank() -> ""
                                    filtered.toIntOrNull() == null -> ""
                                    filtered.toInt() > 100 -> "100"
                                    else -> filtered
                                }
                            },
                            label = { Text(stringResource(R.string.transactions_my_percentage_label)) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            isError = isSharePercentageInvalid
                        )
                        OutlinedTextField(
                            value = myShareAmount,
                            onValueChange = { myShareAmount = it.filter { c -> c.isDigit() || c == '.' } },
                            label = { Text(stringResource(R.string.transactions_or_amount_label)) },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }
                }

                if (expense.transactionType == TransactionType.TRANSFER) {
                    HorizontalDivider()
                    Text(
                        stringResource(R.string.transactions_transfer_direction_section),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        stringResource(R.string.transactions_transfer_direction_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = SemanticColors.TextSecondary
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(isNotMine, ownerName, isSharedExpense, sharedWithName, mySharePercentage, myShareAmount)
                },
                modifier = Modifier,
                enabled = !isSharePercentageInvalid
            ) {
                Text(stringResource(R.string.transactions_save_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel_button))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditLocationDialog(
    expense: Expense,
    onDismiss: () -> Unit,
    onSave: (lat: Double, lon: Double, address: String?, osmId: String?) -> Unit,
    onClear: () -> Unit,
    geocodingService: com.yourname.expensetracker.domain.location.GeocodingService
) {
    var pendingLat by remember { mutableStateOf<Double?>(null) }
    var pendingLon by remember { mutableStateOf<Double?>(null) }
    var pendingAddress by remember { mutableStateOf<String?>(null) }
    var pendingOsmId by remember { mutableStateOf<String?>(null) }
    var hasSelection by remember { mutableStateOf(false) }
    var showClearConfirmation by remember { mutableStateOf(false) }

    if (showClearConfirmation) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text(stringResource(R.string.transactions_clear_location_title)) },
            text = { Text(stringResource(R.string.transactions_clear_location_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirmation = false
                        onClear()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = SemanticColors.DangerRed)
                ) { Text(stringResource(R.string.transactions_clear_button)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) { Text(stringResource(R.string.cancel_button)) }
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // B9: Open fully expanded so the embedded map has enough room and the
        // sheet's partial-expand swipe gesture doesn't compete with map panning.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.transactions_edit_location_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (expense.latitude != null) {
                    TextButton(
                        onClick = { showClearConfirmation = true },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = SemanticColors.DangerRed
                        )
                    ) {
                        Text(stringResource(R.string.transactions_clear_button))
                    }
                }
            }

            Text(
                text = expense.merchant,
                style = MaterialTheme.typography.bodySmall,
                color = SemanticColors.TextSecondary
            )

            LocationSearchPicker(
                currentLat = if (hasSelection) pendingLat else expense.latitude,
                currentLon = if (hasSelection) pendingLon else expense.longitude,
                currentAddress = if (hasSelection) pendingAddress else expense.resolvedAddress,
                onResult = { lat, lon, address, osmId ->
                    if (lat == null) {
                        // Reset picker selection (don't clear from DB — use Clear button for that)
                        pendingLat = null
                        pendingLon = null
                        pendingAddress = null
                        pendingOsmId = null
                        hasSelection = false
                    } else {
                        pendingLat = lat
                        pendingLon = lon
                        pendingAddress = address
                        pendingOsmId = osmId
                        hasSelection = true
                    }
                },
                geocodingService = geocodingService,
                // Bias toward the expense's existing location if available
                biasLat = expense.latitude,
                biasLon = expense.longitude
            )

            if (hasSelection && pendingLat != null && pendingLon != null) {
                Button(
                    onClick = { onSave(pendingLat!!, pendingLon!!, pendingAddress, pendingOsmId) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = SemanticColors.PrimaryIndigo)
                ) {
                    Text(stringResource(R.string.transactions_save_location_button))
                }
            }
        }
    }
}
