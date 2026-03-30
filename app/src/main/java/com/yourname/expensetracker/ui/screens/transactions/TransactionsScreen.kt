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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.TransferDirection
import com.yourname.expensetracker.data.database.model.ExpenseWithCategory
import com.yourname.expensetracker.data.database.model.formattedAmount
import com.yourname.expensetracker.data.database.model.formattedDate
import com.yourname.expensetracker.ui.screens.transactions.TransactionsViewModel.OwnershipFilter
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.ui.screens.transactions.TransactionsViewModel.TransactionTab
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
    onNavigateToAnalytics: () -> Unit = {}
) {
    val transactions by viewModel.transactions.collectAsState()
    val groupedTransactions by viewModel.groupedTransactions.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val activeFilter by viewModel.filter.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isLoadingMore by viewModel.isLoadingMoreState.collectAsState()
    val tabCounts by viewModel.tabTransactionCounts.collectAsState()
    val ownershipFilter by viewModel.ownershipFilter.collectAsState()
    val sortOrder by viewModel.sortOrder.collectAsState()
    
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
    var isRefreshing by remember { mutableStateOf(false) }
    
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
            !isLoadingMore
        }
    }
    
    // Trigger load more when needed
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            viewModel.loadMore()
        }
    }
    
    // Handle refresh
    LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            viewModel.refresh()
            isRefreshing = false
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
                                    placeholder = { Text("Search transactions...") },
                                    leadingIcon = { 
                                        Icon(Icons.Rounded.Search, contentDescription = null) 
                                    },
                                    trailingIcon = {
                                        IconButton(onClick = { 
                                            showSearch = false
                                            viewModel.search("")
                                        }) {
                                            Icon(Icons.Rounded.Close, contentDescription = "Close search")
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
                        IconButton(
                            onClick = onNavigateToAnalytics,
                            modifier = Modifier.semantics { contentDescription = "Navigate to advanced analytics" }
                        ) {
                            Icon(Icons.Rounded.BarChart, contentDescription = null)
                        }
                        Box {
                            IconButton(
                                onClick = { showSortMenu = true },
                                modifier = Modifier.semantics { contentDescription = "Open sort menu, currently sorted by ${sortOrder.displayName}" }
                            ) {
                                Icon(Icons.Rounded.Sort, contentDescription = null)
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false }
                            ) {
                                com.yourname.expensetracker.data.repository.SortOrder.values().forEach { order ->
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
                                            contentDescription = "Sort by ${order.displayName}, ${if (sortOrder == order) "selected" else "not selected"}"
                                        }
                                    )
                                }
                            }
                        }
                        IconButton(
                            onClick = { showFilterSheet = true },
                            modifier = Modifier.semantics { contentDescription = "Open transaction filters" }
                        ) {
                            Icon(Icons.Rounded.FilterList, contentDescription = null)
                        }
                        if (!showSearch) {
                            IconButton(
                                onClick = { showSearch = true },
                                modifier = Modifier.semantics { contentDescription = "Open search" }
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
                                contentDescription = "${tab.label} tab, ${if (selectedTab == tab) "selected" else "not selected"}, ${count} transactions"
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
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            val filterText = buildString {
                                append("Filtered by: ")
                                val parts = mutableListOf<String>()
                                activeFilter?.merchantName?.let { parts.add("Merchant: $it") }
                                activeFilter?.categoryId?.let { id -> 
                                    categories.find { it.id == id }?.name?.let { parts.add("Category: $it") }
                                }
                                activeFilter?.transactionType?.let { parts.add("Type: ${it.name}") }
                                activeFilter?.dateRange?.let { parts.add("Date range") }
                                activeFilter?.ownership?.let {
                                    val label = when (it) {
                                        com.yourname.expensetracker.data.repository.OwnershipFilter.MINE -> "Mine"
                                        com.yourname.expensetracker.data.repository.OwnershipFilter.NOT_MINE -> "Not mine"
                                        com.yourname.expensetracker.data.repository.OwnershipFilter.SHARED -> "Shared"
                                        com.yourname.expensetracker.data.repository.OwnershipFilter.TRANSFER -> "Transfers"
                                        com.yourname.expensetracker.data.repository.OwnershipFilter.ALL -> "All"
                                    }
                                    parts.add("Ownership: $label")
                                }
                                activeFilter?.minAmount?.let { parts.add("Min: %.2f".format(it)) }
                                activeFilter?.maxAmount?.let { parts.add("Max: %.2f".format(it)) }
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
                                    .size(24.dp)
                                    .semantics { contentDescription = "Clear all filters" }
                            ) {
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription = null,
                                    tint = SemanticColors.PrimaryIndigo,
                                    modifier = Modifier.size(16.dp)
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
                isRefreshing = true 
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
                        onAddClick = { /* Navigate to add expense */ }
                    )
                }
                else -> {
                    // Transaction list with date grouping
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
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
                                    totalAmount = items.sumOf { it.expense.effectiveAmount },
                                    itemCount = items.size
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
                                    onDelete = { expenseToDelete = item.expense },
                                    onEditCategory = { expenseToCategorize = item.expense },
                                    onMarkRecurring = { expenseToRecurring = item.expense },
                                    onRename = { expenseToRename = item.expense },
                                    onChangeType = { expenseToChangeType = item.expense },
                                    onEditOwnership = { expenseToEditOwnership = item.expense },
                                    onDebug = { expenseToDebug = item.expense },
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
                onDismiss = { showFilterSheet = false },
                onApply = { filter, ownership ->
                    if (filter != null) {
                        viewModel.applyFilter(
                            filter.copy(ownership = ownership.toRepositoryOwnershipFilter())
                        )
                    } else {
                        viewModel.clearFilter()
                    }
                    viewModel.setOwnershipFilter(ownership)
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
                onDismiss = { expenseToChangeType = null },
                onConfirm = { newType ->
                    expenseToChangeType?.let { viewModel.updateExpenseType(it, newType) }
                    expenseToChangeType = null
                }
            )
        }

        // Edit ownership/not-mine/shared dialog
        if (expenseToEditOwnership != null) {
            EditOwnershipDialog(
                expense = expenseToEditOwnership!!,
                onDismiss = { expenseToEditOwnership = null },
                onSave = { isNotMine, ownerName, isShared, sharedWith, sharePercent, shareAmount ->
                    expenseToEditOwnership?.let { expense ->
                        viewModel.updateNotMineDetails(expense, isNotMine, ownerName)
                        viewModel.updateSharedExpenseDetails(expense, isShared, sharedWith, sharePercent, shareAmount)
                    }
                    expenseToEditOwnership = null
                }
            )
        }
        
        // Debug Screen Overlay
        if (expenseToDebug != null) {
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
    Box(
        modifier = Modifier
            .fillMaxSize()
            .semantics { 
                contentDescription = if (hasSearch) "No search results found. Try a different search term." else "No transactions yet. Add your first expense to get started."
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
                text = if (hasSearch) "No results found" else stringResource(R.string.no_transactions_title),
                style = MaterialTheme.typography.titleMedium,
                color = SemanticColors.TextPrimary
            )
            
            Text(
                text = if (hasSearch) {
                    "Try a different search term"
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
                    modifier = Modifier.semantics { contentDescription = "Add your first expense" }
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Add Expense")
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
    itemCount: Int
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
                        text = "$itemCount transaction${if (itemCount != 1) "s" else ""}",
                        style = MaterialTheme.typography.labelSmall,
                        color = SemanticColors.TextMuted,
                        fontWeight = FontWeight.Medium
                    )
                }
                
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (totalAmount < 0) SemanticColors.DangerRed.copy(alpha = 0.1f) else SemanticColors.SuccessGreen.copy(alpha = 0.1f)
                ) {
                    Text(
                        text = "€${String.format(java.util.Locale.getDefault(), "%.2f", kotlin.math.abs(totalAmount))}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (totalAmount < 0) SemanticColors.DangerRed else SemanticColors.SuccessGreen,
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
    
    // Safe color parsing with fallback
    val categoryColor = remember(transaction.categoryColor) {
        try {
            Color(transaction.categoryColor.toInt())
        } catch (e: Exception) {
            SemanticColors.PrimaryIndigo
        }
    }
    
    // Build accessibility description for the transaction
    val accessibilityDescription = buildString {
        append("${expense.merchant}, ${transaction.formattedAmount}")
        category?.name?.let { append(", Category: $it") }
        if (expense.isManualEntry) append(", Manual entry")
        if (expense.resolvedAddress != null) append(", Location: ${expense.resolvedAddress}")
        expense.transferDirection?.let { append(", ${it.name}") }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .semantics {
                contentDescription = accessibilityDescription
            },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
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
                    .semantics { contentDescription = "${category?.name ?: "Uncategorized"} category, double tap to change category" },
                contentAlignment = Alignment.Center
            ) {
                if (category?.icon != null) {
                    Text(
                        text = category.icon,
                        fontSize = 26.sp,
                        modifier = Modifier.semantics { contentDescription = "${category.name} icon" }
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
                            .weight(1f, fill = false)
                            .clickable { onRename() }
                            .semantics { contentDescription = "${expense.merchant}, double tap to rename" }
                    )
                    
                    // Manual entry indicator
                    if (expense.isManualEntry) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = SemanticColors.PrimaryIndigo.copy(alpha = 0.15f)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Edit,
                                contentDescription = "Manual entry",
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
                            .semantics { contentDescription = "Double tap merchant name to edit" },
                        tint = SemanticColors.TextMuted.copy(alpha = 0.5f)
                    )
                }
                
                // Category and payment method row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Payment method icon - remember to avoid recalculation
                    val (methodIcon, methodDesc) = remember(expense.paymentMethod) {
                        when (expense.paymentMethod) {
                            PaymentMethod.CASH -> Icons.Rounded.Payments to "Cash"
                            PaymentMethod.BANK_TRANSFER -> Icons.Rounded.AccountBalance to "Bank transfer"
                            PaymentMethod.CARD -> Icons.Rounded.CreditCard to "Card"
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
                        text = category?.name ?: stringResource(R.string.uncategorized_label),
                        style = MaterialTheme.typography.bodySmall,
                        color = SemanticColors.TextSecondary,
                        modifier = Modifier
                            .clickable { onEditCategory() }
                            .semantics { contentDescription = "${category?.name ?: "Uncategorized"}, double tap to change category" }
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
                    text = transaction.formattedDate,
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
                

                
                // Action buttons row
                Row(
                    horizontalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    // Change type action
                    IconButton(
                        onClick = onChangeType,
                        modifier = Modifier
                            .size(40.dp)
                            .semantics { contentDescription = "Change transaction type, currently ${expense.transactionType.name}" }
                    ) {
                        val (typeIcon, typeDesc) = when (expense.transactionType) {
                            TransactionType.PURCHASE -> Icons.Rounded.ShoppingCart to "Purchase"
                            TransactionType.DEPOSIT -> Icons.Rounded.ArrowCircleDown to "Deposit"
                            TransactionType.WITHDRAWAL -> Icons.Rounded.ArrowCircleUp to "Withdrawal"
                            TransactionType.TRANSFER -> Icons.Rounded.SyncAlt to "Transfer"
                            else -> Icons.Rounded.HelpOutline to "Unknown"
                        }
                        Icon(
                            imageVector = typeIcon,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = SemanticColors.PrimaryIndigo.copy(alpha = 0.6f)
                        )
                    }

                    // Edit ownership/not-mine/shared action
                    val (ownershipIcon, _) = remember(expense.isNotMine, expense.isSharedExpense, expense.transactionType) {
                        when {
                            expense.isNotMine -> Icons.Rounded.Person to "Not mine"
                            expense.isSharedExpense -> Icons.Rounded.People to "Shared"
                            expense.transactionType == TransactionType.TRANSFER -> Icons.Rounded.SyncAlt to "Transfer"
                            else -> Icons.Rounded.Settings to "Settings"
                        }
                    }
                    IconButton(
                        onClick = onEditOwnership,
                        modifier = Modifier
                            .size(40.dp)
                            .semantics { contentDescription = "Edit ownership" }
                    ) {
                        Icon(
                            imageVector = ownershipIcon,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = SemanticColors.PrimaryIndigo.copy(alpha = 0.6f)
                        )
                    }

                    // Recurring action
                    IconButton(
                        onClick = onMarkRecurring,
                        modifier = Modifier
                            .size(40.dp)
                            .semantics { contentDescription = "Mark as recurring" }
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Repeat,
                            contentDescription = null,
                            tint = SemanticColors.PrimaryIndigo.copy(alpha = 0.6f),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Location pin action
                    IconButton(
                        onClick = onEditLocation,
                        modifier = Modifier
                            .size(40.dp)
                            .semantics { contentDescription = if (expense.latitude != null) "Edit location" else "Add location" }
                    ) {
                        Icon(
                            imageVector = if (expense.latitude != null) Icons.Filled.LocationOn else Icons.Rounded.AddLocationAlt,
                            contentDescription = null,
                            tint = if (expense.latitude != null)
                                SemanticColors.PrimaryIndigo.copy(alpha = 0.8f)
                            else
                                SemanticColors.TextMuted.copy(alpha = 0.5f),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Delete action
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .size(40.dp)
                            .semantics { contentDescription = "Delete transaction" }
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = null,
                            tint = SemanticColors.DangerRed.copy(alpha = 0.6f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
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
                    placeholder = { Text("Search categories...") },
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
                                        contentDescription = "Selected",
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
                            text = "Apply to all past transactions for $currentMerchant",
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
        title = { Text("Rename Merchant") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "Assigning a brand name helps the app learn. Future transactions from this source will be auto-corrected.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SemanticColors.TextSecondary
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        isError = it.isBlank()
                    },
                    label = { Text("Brand Name") },
                    singleLine = true,
                    isError = isError,
                    supportingText = if (isError) {
                        { Text("Name cannot be empty") }
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
                        "Apply to all past and pending entries",
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
    onDismiss: () -> Unit,
    onConfirm: (TransactionType) -> Unit
) {
    var selectedType by remember { mutableStateOf(currentType) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.SwapHoriz, contentDescription = null) },
        title = { Text("Change Transaction Type") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Incorrectly categorized? Change the type here.",
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
                        TransactionType.PURCHASE -> "Money spent on purchases"
                        TransactionType.DEPOSIT -> "Money received (salary, transfer in)"
                        TransactionType.WITHDRAWAL -> "Cash withdrawn from ATM"
                        TransactionType.TRANSFER -> "Money transferred between accounts"
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
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selectedType) },
                enabled = selectedType != currentType,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SemanticColors.PrimaryIndigo
                )
            ) {
                Text("Update Type")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun EditOwnershipDialog(
    expense: Expense,
    onDismiss: () -> Unit,
    onSave: (isNotMine: Boolean, ownerName: String, isShared: Boolean, sharedWithName: String, sharePercentage: String, shareAmount: String) -> Unit
) {
    var isNotMine by remember { mutableStateOf(expense.isNotMine) }
    var ownerName by remember { mutableStateOf(expense.ownerName ?: "") }
    var isSharedExpense by remember { mutableStateOf(expense.isSharedExpense) }
    var sharedWithName by remember { mutableStateOf(expense.sharedWithName ?: "") }
    var mySharePercentage by remember { mutableStateOf(expense.mySharePercentage?.toString() ?: "") }
    var myShareAmount by remember { mutableStateOf(expense.myShareAmount?.toString() ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.PersonAdd, contentDescription = null) },
        title = { Text("Edit Expense Details") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Ownership",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Not mine (belongs to someone else)")
                    Switch(
                        checked = isNotMine,
                        onCheckedChange = { isNotMine = it }
                    )
                }
                if (isNotMine) {
                    OutlinedTextField(
                        value = ownerName,
                        onValueChange = { ownerName = it },
                        label = { Text("Owner name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("e.g., Partner, Roommate") }
                    )
                }

                HorizontalDivider()

                Text(
                    "Shared Expense",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Split with someone")
                    Switch(
                        checked = isSharedExpense,
                        onCheckedChange = { isSharedExpense = it }
                    )
                }
                if (isSharedExpense) {
                    OutlinedTextField(
                        value = sharedWithName,
                        onValueChange = { sharedWithName = it },
                        label = { Text("Shared with") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = mySharePercentage,
                            onValueChange = { mySharePercentage = it.filter { c -> c.isDigit() } },
                            label = { Text("My %") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = myShareAmount,
                            onValueChange = { myShareAmount = it.filter { c -> c.isDigit() || c == '.' } },
                            label = { Text("Or amount") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }
                }

                if (expense.transactionType == TransactionType.TRANSFER) {
                    HorizontalDivider()
                    Text(
                        "Transfer Direction",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Transfer type can be changed in the type dialog",
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
                modifier = Modifier
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
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
            title = { Text("Clear Location?") },
            text = { Text("This will permanently remove the location from this transaction. The backfill worker may re-resolve it later.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirmation = false
                        onClear()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = SemanticColors.DangerRed)
                ) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) { Text("Cancel") }
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
                    text = "Edit Location",
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
                        Text("Clear")
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
                    Text("Save Location")
                }
            }
        }
    }
}
