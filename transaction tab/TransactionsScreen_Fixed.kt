package com.yourname.expensetracker.ui.screens.transactions

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.pulltorefresh.PullToRefreshBox
import androidx.compose.material.pulltorefresh.rememberPullToRefreshState
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
import com.yourname.expensetracker.data.database.model.ExpenseWithCategory
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.ui.screens.transactions.TransactionsViewModel.TransactionTab
import com.yourname.expensetracker.ui.theme.SemanticColors
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

// ============================================================
// MAIN SCREEN
// ============================================================

@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
@Composable
fun TransactionsScreen(
    viewModel: TransactionsViewModel = hiltViewModel()
) {
    val transactions by viewModel.transactions.collectAsState()
    val groupedTransactions by viewModel.groupedTransactions.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isLoadingMore by viewModel.isLoadingMoreState.collectAsState()
    val tabCounts by viewModel.tabTransactionCounts.collectAsState()
    
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    
    // Dialog states
    var expenseToDelete by remember { mutableStateOf<Expense?>(null) }
    var expenseToCategorize by remember { mutableStateOf<Expense?>(null) }
    var expenseToRecurring by remember { mutableStateOf<Expense?>(null) }
    var expenseToRename by remember { mutableStateOf<Expense?>(null) }
    var showSearch by remember { mutableStateOf(false) }
    
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
                        if (!showSearch) {
                            IconButton(onClick = { showSearch = true }) {
                                Icon(Icons.Rounded.Search, contentDescription = "Search")
                            }
                        }
                    }
                )
                
                // Tab row with counts
                ScrollableTabRow(
                    selectedTabIndex = selectedTab.ordinal,
                    edgePadding = 8.dp,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab.ordinal]),
                            color = SemanticColors.PrimaryIndigo
                        )
                    },
                    divider = {
                        HorizontalDivider(
                            color = SemanticColors.GlassBorder,
                            thickness = 1.dp
                        )
                    }
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
                            }
                        )
                    }
                }
            }
        }
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
                    // Initial loading state
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = SemanticColors.PrimaryIndigo
                        )
                    }
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
                                    totalAmount = items.sumOf { it.expense.amount },
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
                                    onRename = { expenseToRename = item.expense }
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
                onDismiss = { expenseToCategorize = null },
                onCategorySelected = { categoryId ->
                    expenseToCategorize?.let { viewModel.updateCategory(it, categoryId) }
                    expenseToCategorize = null
                }
            )
        }

        // Rename merchant dialog
        if (expenseToRename != null) {
            RenameMerchantDialog(
                currentName = expenseToRename?.merchant ?: "",
                onDismiss = { expenseToRename = null },
                onConfirm = { newName ->
                    expenseToRename?.let { viewModel.updateMerchant(it, newName) }
                    expenseToRename = null
                }
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
        modifier = Modifier.fillMaxSize(),
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
                    )
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
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
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
                    color = SemanticColors.TextMuted
                )
            }
            
            Text(
                text = "€${String.format(Locale.getDefault(), "%.2f", totalAmount)}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = SemanticColors.PrimaryIndigo
            )
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
    onRename: () -> Unit
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

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
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
                    .padding(4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = category?.icon ?: "❓",
                    fontSize = 26.sp
                )
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
                    )
                    
                    // Manual entry indicator
                    if (expense.isManualEntry) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = SemanticColors.PrimaryIndigo.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = "✏️",
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                    
                    // Edit icon hint
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = "Tap to rename",
                        modifier = Modifier.size(14.dp),
                        tint = SemanticColors.TextMuted.copy(alpha = 0.5f)
                    )
                }
                
                // Category and payment method row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Payment method icon
                    val (methodIcon, methodDesc) = when (expense.paymentMethod) {
                        PaymentMethod.CASH -> "💵" to "Cash"
                        PaymentMethod.BANK_TRANSFER -> "🏦" to "Bank"
                        PaymentMethod.CARD -> "💳" to "Card"
                        else -> "" to ""
                    }
                    
                    if (methodIcon.isNotEmpty()) {
                        Text(methodIcon, fontSize = 12.sp)
                        Spacer(modifier = Modifier.width(2.dp))
                    }
                    
                    Text(
                        text = category?.name ?: stringResource(R.string.uncategorized_label),
                        style = MaterialTheme.typography.bodySmall,
                        color = SemanticColors.TextSecondary,
                        modifier = Modifier.clickable { onEditCategory() }
                    )
                }
                
                // Time
                Text(
                    text = transaction.formattedDate,
                    style = MaterialTheme.typography.labelSmall,
                    color = SemanticColors.TextMuted
                )
            }

            // Amount
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = transaction.formattedAmount,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 18.sp,
                    color = SemanticColors.TextPrimary,
                    fontFeatureSettings = "tnum"
                )
                
                // Action buttons row
                Row(
                    horizontalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    // Recurring action
                    IconButton(
                        onClick = onMarkRecurring,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Repeat,
                            contentDescription = stringResource(R.string.mark_recurring_content_description),
                            tint = SemanticColors.PrimaryIndigo.copy(alpha = 0.6f),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Delete action
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = stringResource(R.string.delete_button),
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
                                RecurrenceFrequency.DAILY -> Icons.Rounded.Today
                                RecurrenceFrequency.WEEKLY -> Icons.Rounded.DateRange
                                RecurrenceFrequency.BI_WEEKLY -> Icons.Rounded.DateRange
                                RecurrenceFrequency.MONTHLY -> Icons.Rounded.CalendarMonth
                                RecurrenceFrequency.QUARTERLY -> Icons.Rounded.CalendarViewMonth
                                RecurrenceFrequency.YEARLY -> Icons.Rounded.Event
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
    onDismiss: () -> Unit,
    onCategorySelected: (Long) -> Unit
) {
    var searchText by remember { mutableStateOf("") }
    
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
                        .heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filteredCategories) { category ->
                        val isSelected = category.id == currentCategoryId
                        
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onCategorySelected(category.id) },
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
    onConfirm: (String) -> Unit
) {
    var name by remember(currentName) { mutableStateOf(currentName) }
    var isError by remember { mutableStateOf(false) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
        title = { Text("Rename Merchant") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Assign a brand name helps the app learn. Future transactions from this source will be auto-corrected.",
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
            }
        },
        confirmButton = {
            Button(
                onClick = { 
                    if (name.isNotBlank() && name != currentName) {
                        onConfirm(name.trim())
                    } else {
                        isError = true
                    }
                },
                enabled = name.isNotBlank() && name != currentName,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SemanticColors.PrimaryIndigo
                )
            ) {
                Text("Update")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
