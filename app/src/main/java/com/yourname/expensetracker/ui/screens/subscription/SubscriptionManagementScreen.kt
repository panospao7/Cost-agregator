@file:OptIn(ExperimentalMaterial3Api::class)

package com.yourname.expensetracker.ui.screens.subscription

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.ui.components.common.EmptyStateType
import com.yourname.expensetracker.ui.components.common.EnhancedEmptyState
import com.yourname.expensetracker.ui.components.emptystate.ContextualActionRegistry
import com.yourname.expensetracker.ui.components.emptystate.EmptyStateAction
import com.yourname.expensetracker.ui.components.emptystate.EmptyStateActionType
import com.yourname.expensetracker.ui.components.emptystate.EmptyStateFeatureAction
import com.yourname.expensetracker.ui.components.emptystate.EmptyStateScreenKeys
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import com.yourname.expensetracker.ui.theme.SemanticColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionManagementScreen(
    onNavigateBack: () -> Unit,
    onOpenNotificationSettings: () -> Unit = {},
    onOpenBankConnections: () -> Unit = {},
    viewModel: SubscriptionManagementViewModel = hiltViewModel(),
    actionRegistry: ContextualActionRegistry
) {
    val uiState by viewModel.uiState.collectAsState()
    val homeCurrency by viewModel.homeCurrency.collectAsState(initial = "")
    val completedActionKeys by actionRegistry.completedActions.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf<SubscriptionInfo?>(null) }
    
    // Get contextual actions for empty state
    val emptyStateActions by remember(completedActionKeys) {
        derivedStateOf {
            actionRegistry.getActions(EmptyStateScreenKeys.SUBSCRIPTION)
        }
    }
    
    Scaffold(
        containerColor = SemanticColors.BaseNavy,
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        stringResource(R.string.subscriptions_title),
                        color = SemanticColors.TextPrimary,
                        fontWeight = FontWeight.Bold
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                            tint = SemanticColors.TextPrimary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.cd_refresh),
                            tint = SemanticColors.TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SemanticColors.BaseNavy,
                    titleContentColor = SemanticColors.TextPrimary
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = SemanticColors.PrimaryIndigo
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_add))
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                uiState.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = SemanticColors.PrimaryIndigo)
                    }
                }
                uiState.error != null -> {
                    ErrorState(
                        message = uiState.error!!,
                        onRetry = { viewModel.refresh() }
                    )
                }
                uiState.subscriptions.isEmpty() -> {
                    // Enhanced empty state with contextual actions
                    EnhancedEmptyState(
                        type = EmptyStateType.GENERIC,
                        title = stringResource(R.string.subscriptions_empty_title),
                        message = stringResource(R.string.subscriptions_empty_message),
                        actions = emptyStateActions,
                        onActionClick = { action ->
                            when (val actionType = action.action) {
                                is EmptyStateActionType.NavigateToDestination -> {
                                    // Handle navigation if needed
                                }
                                is EmptyStateActionType.ExecuteAction -> actionType.action.invoke()
                                is EmptyStateActionType.OpenFeature -> {
                                    when (actionType.feature) {
                                        EmptyStateFeatureAction.NotificationSettings -> onOpenNotificationSettings()
                                        EmptyStateFeatureAction.AddSubscription -> showAddDialog = true
                                        else -> {}
                                    }
                                }
                            }
                        },
                        onDismissAction = { actionId ->
                            actionRegistry.markCompleted(EmptyStateScreenKeys.SUBSCRIPTION, actionId)
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Summary Cards
                        item {
                            SummaryCards(uiState)
                        }
                        
                        // Total Cost Card
                        item {
                            TotalCostCard(
                                monthlyTotal = uiState.totalMonthlyCost,
                                annualTotal = uiState.totalAnnualCost,
                                homeCurrency = homeCurrency
                            )
                        }
                        
                        // Detected Candidates Section
                        if (uiState.detectedCandidates.isNotEmpty()) {
                            item {
                                Text(
                                    text = stringResource(R.string.header_detected_subscriptions, uiState.detectedCount),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = SemanticColors.TextPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            
                        items(
                            uiState.detectedCandidates,
                            key = { it.id }
                        ) { candidate ->
                            SubscriptionCandidateCard(
                                candidate = candidate,
                                homeCurrency = homeCurrency,
                                onAccept = { viewModel.acceptCandidate(it) },
                                onReject = { viewModel.rejectCandidate(it.id) }
                            )
                        }
                        }
                        
                        // Active Subscriptions Header
                        if (uiState.subscriptions.any { it.subscription.isActive }) {
                            item {
                                Text(
                                    text = stringResource(R.string.header_active_subscriptions, uiState.activeCount),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = SemanticColors.TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = if (uiState.detectedCandidates.isNotEmpty()) 16.dp else 0.dp)
                                )
                            }
                        }
                        
                        // Active Subscriptions List
                        items(
                            uiState.subscriptions.filter { it.subscription.isActive },
                            key = { it.subscription.id }
                        ) { subscription ->
                            SubscriptionCard(
                                subscription = subscription,
                                homeCurrency = homeCurrency,
                                onToggleStatus = { viewModel.toggleSubscriptionStatus(it) },
                                onDelete = { showDeleteConfirm = it },
                                onRecordUsage = { viewModel.recordUsage(it) }
                            )
                        }
                        
                        // Inactive Subscriptions Header
                        if (uiState.subscriptions.any { !it.subscription.isActive }) {
                            item {
                                Text(
                                    text = stringResource(R.string.header_inactive_subscriptions, uiState.inactiveCount),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = SemanticColors.TextSecondary,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 16.dp)
                                )
                            }
                        }
                        
                        // Inactive Subscriptions List
                        items(
                            uiState.subscriptions.filter { !it.subscription.isActive },
                            key = { it.subscription.id }
                        ) { subscription ->
                            SubscriptionCard(
                                subscription = subscription,
                                homeCurrency = homeCurrency,
                                onToggleStatus = { viewModel.toggleSubscriptionStatus(it) },
                                onDelete = { showDeleteConfirm = it },
                                onRecordUsage = null
                            )
                        }
                        
                        // Bottom spacing
                        item { Spacer(modifier = Modifier.height(80.dp)) }
                    }
                }
            }
        }
        
        // Add Subscription Dialog
        if (showAddDialog) {
            AddSubscriptionDialog(
                referenceNowMillis = uiState.referenceNowMillis,
                onDismiss = { showAddDialog = false },
                onAdd = { merchant, amount, frequency, category, nextDate ->
                    viewModel.addSubscription(merchant, amount, frequency, category, nextDate)
                    showAddDialog = false
                }
            )
        }
        
        // Delete Confirmation Dialog
        showDeleteConfirm?.let { subscription ->
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = null },
                title = { Text(stringResource(R.string.subscriptions_delete_confirm_title)) },
                text = { Text(stringResource(R.string.subscriptions_delete_confirm_message, subscription.subscription.merchant)) },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.deleteSubscription(subscription.subscription.id)
                            showDeleteConfirm = null
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(stringResource(R.string.action_delete))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = null }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }
    }
}

@Composable
private fun SummaryCards(uiState: SubscriptionManagementUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SummaryCard(
            title = stringResource(R.string.label_active),
            value = uiState.activeCount.toString(),
            icon = Icons.Rounded.CheckCircle,
            color = Color(0xFF4CAF50),
            modifier = Modifier.weight(1f)
        )
        
        SummaryCard(
            title = stringResource(R.string.label_inactive),
            value = uiState.inactiveCount.toString(),
            icon = Icons.Rounded.Cancel,
            color = Color(0xFFFF9800),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SummaryCard(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.15f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(32.dp)
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                color = SemanticColors.TextPrimary,
                fontWeight = FontWeight.Bold
            )
            
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = SemanticColors.TextSecondary
            )
        }
    }
}

@Composable
private fun TotalCostCard(monthlyTotal: Double, annualTotal: Double, homeCurrency: String) {
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = SemanticColors.PrimaryIndigo.copy(alpha = 0.15f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text(
                text = stringResource(R.string.label_total_subscription_cost),
                style = MaterialTheme.typography.titleMedium,
                color = SemanticColors.TextPrimary,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                Text(
                    text = CurrencyFormatter.formatMoney(monthlyTotal, homeCurrency),
                    style = MaterialTheme.typography.headlineSmall,
                    color = SemanticColors.TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                    Text(
                        text = stringResource(R.string.label_per_month),
                        style = MaterialTheme.typography.bodySmall,
                        color = SemanticColors.TextSecondary
                    )
                }
                
                Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = CurrencyFormatter.formatMoney(annualTotal, homeCurrency),
                    style = MaterialTheme.typography.headlineSmall,
                    color = SemanticColors.PrimaryIndigo,
                    fontWeight = FontWeight.Bold
                )
                    Text(
                        text = stringResource(R.string.label_per_year),
                        style = MaterialTheme.typography.bodySmall,
                        color = SemanticColors.TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun SubscriptionCard(
    subscription: SubscriptionInfo,
    homeCurrency: String,
    onToggleStatus: (Long) -> Unit,
    onDelete: (SubscriptionInfo) -> Unit,
    onRecordUsage: ((Long) -> Unit)?
) {
    val dateFormat = DateTimeFormatter.ofPattern("MMM dd", Locale.getDefault())
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (subscription.subscription.isActive) {
                SemanticColors.SurfaceLight.copy(alpha = 0.5f)
            } else {
                SemanticColors.SurfaceLight.copy(alpha = 0.3f)
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = subscription.subscription.merchant,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (subscription.subscription.isActive) {
                            SemanticColors.TextPrimary
                        } else {
                            SemanticColors.TextSecondary
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                    
                    subscription.subscription.subscriptionCategory?.let { category ->
                        Text(
                            text = category,
                            style = MaterialTheme.typography.bodySmall,
                            color = SemanticColors.TextSecondary
                        )
                    }
                }
                
                Switch(
                    checked = subscription.subscription.isActive,
                    onCheckedChange = { onToggleStatus(subscription.subscription.id) }
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Price and frequency
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    // S12-012: Use subscription's own currency for row display
                    text = "${CurrencyFormatter.formatMoney(subscription.subscription.amount, subscription.subscription.currency)} ${subscription.subscription.frequency.name.lowercase().replaceFirstChar { it.uppercase() }}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = SemanticColors.TextPrimary
                )
                
                subscription.priceChange?.let { change ->
                    val changeText = "${if (change.isIncrease) "+" else ""}${String.format("%.1f", change.changePercentage)}%"
                    val changeColor = if (change.isIncrease) Color(0xFFF44336) else Color(0xFF4CAF50)
                    
                    Text(
                        text = changeText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = changeColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Renews ${dateFormat.format(Instant.ofEpochMilli(subscription.subscription.nextDate).atZone(ZoneId.systemDefault()))}",
                style = MaterialTheme.typography.bodySmall,
                color = SemanticColors.TextSecondary
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Usage stats
            if (subscription.subscription.isActive && onRecordUsage != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        // S12-014: Label matches actual calculation window (varies by frequency)
                        text = stringResource(R.string.label_uses_in_billing_window_format, subscription.monthlyUsage),
                        style = MaterialTheme.typography.bodySmall,
                        color = SemanticColors.TextSecondary
                    )
                    
                    Text(
                        text = stringResource(R.string.label_per_use_format, CurrencyFormatter.formatMoney(subscription.costPerUse, homeCurrency)),
                        style = MaterialTheme.typography.bodySmall,
                        color = SemanticColors.PrimaryIndigo,
                        fontWeight = FontWeight.Medium
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Usage button
                OutlinedButton(
                    onClick = { onRecordUsage(subscription.subscription.id) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = SemanticColors.PrimaryIndigo
                    )
                ) {
                    Icon(Icons.Rounded.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.subscriptions_record_usage), style = MaterialTheme.typography.labelMedium)
                }
            }
            
            // Delete button for inactive
            if (!subscription.subscription.isActive) {
                TextButton(
                    onClick = { onDelete(subscription) },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Rounded.Delete, null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.action_delete))
                }
            }
        }
    }
}

@Composable
private fun SubscriptionCandidateCard(
    candidate: com.yourname.expensetracker.data.database.entity.SubscriptionCandidate,
    homeCurrency: String,
    onAccept: (com.yourname.expensetracker.data.database.entity.SubscriptionCandidate) -> Unit,
    onReject: (com.yourname.expensetracker.data.database.entity.SubscriptionCandidate) -> Unit
) {
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = SemanticColors.PrimaryIndigo.copy(alpha = 0.15f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header with confidence badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = candidate.merchant,
                        style = MaterialTheme.typography.titleMedium,
                        color = SemanticColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    
                    Text(
                        text = stringResource(
                            R.string.subscription_candidate_details,
                            candidate.transactionCount,
                            candidate.detectedInterval.replaceFirstChar { it.uppercase() },
                            "${(candidate.confidence * 100).toInt()}%"
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = SemanticColors.TextSecondary
                    )
                }
                
                // Confidence badge
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = when {
                        candidate.confidence >= 0.8 -> Color(0xFF4CAF50)
                        candidate.confidence >= 0.6 -> Color(0xFFFF9800)
                        else -> Color(0xFFF44336)
                    }.copy(alpha = 0.2f)
                ) {
                    Text(
                        text = "${(candidate.confidence * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = when {
                            candidate.confidence >= 0.8 -> Color(0xFF4CAF50)
                            candidate.confidence >= 0.6 -> Color(0xFFFF9800)
                            else -> Color(0xFFF44336)
                        },
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Amount and annual cost
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    // S12-012: Use candidate's own currency for row display
                    text = CurrencyFormatter.formatMoney(candidate.averageAmount, candidate.currency ?: homeCurrency),
                    style = MaterialTheme.typography.bodyLarge,
                    color = SemanticColors.TextPrimary,
                    fontWeight = FontWeight.Medium
                )
                
                Text(
                    text = stringResource(R.string.label_estimated_annual, CurrencyFormatter.formatMoney(candidate.estimatedAnnualCost, homeCurrency)),
                    style = MaterialTheme.typography.bodySmall,
                    color = SemanticColors.TextSecondary
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { onReject(candidate) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.action_reject))
                }
                
                Button(
                    onClick = { onAccept(candidate) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SemanticColors.PrimaryIndigo
                    )
                ) {
                    Text(stringResource(R.string.action_add_subscription))
                }
            }
        }
    }
}

@Composable
private fun AddSubscriptionDialog(
    referenceNowMillis: Long,
    onDismiss: () -> Unit,
    onAdd: (String, Double, RecurrenceFrequency, String?, Long) -> Unit
) {
    var merchant by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var frequency by remember { mutableStateOf(RecurrenceFrequency.MONTHLY) }
    var category by remember { mutableStateOf("") }
    var nextDate by remember { mutableStateOf<Long?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }
    val dateFormat = remember { DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.getDefault()) }
    
    val categories = listOf(
        stringResource(R.string.category_streaming),
        stringResource(R.string.category_software),
        stringResource(R.string.category_fitness),
        stringResource(R.string.category_news),
        stringResource(R.string.category_cloud_storage),
        stringResource(R.string.category_other)
    )
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.subscriptions_add_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = merchant,
                    onValueChange = { merchant = it },
                    label = { Text(stringResource(R.string.subscriptions_merchant_label)) },
                    modifier = Modifier.fillMaxWidth()
                )
                
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text(stringResource(R.string.label_amount)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                
                // Frequency dropdown
                var frequencyExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = frequencyExpanded,
                    onExpandedChange = { frequencyExpanded = it }
                ) {
                    OutlinedTextField(
                        value = frequency.name.lowercase().replaceFirstChar { it.uppercase() },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.label_frequency)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = frequencyExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    
                    ExposedDropdownMenu(
                        expanded = frequencyExpanded,
                        onDismissRequest = { frequencyExpanded = false }
                    ) {
                        RecurrenceFrequency.values().forEach { freq ->
                            DropdownMenuItem(
                                text = { Text(freq.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                onClick = {
                                    frequency = freq
                                    frequencyExpanded = false
                                }
                            )
                        }
                    }
                }
                
                // Category dropdown
                var categoryExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = categoryExpanded,
                    onExpandedChange = { categoryExpanded = it }
                ) {
                    OutlinedTextField(
                        value = category,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.subscriptions_category_label)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    
                    ExposedDropdownMenu(
                        expanded = categoryExpanded,
                        onDismissRequest = { categoryExpanded = false }
                    ) {
                        categories.forEach { cat ->
                            DropdownMenuItem(
                                text = { Text(cat) },
                                onClick = {
                                    category = cat
                                    categoryExpanded = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = nextDate?.let { dateFormat.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) }.orEmpty(),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.recurring_next_date_label)) },
                    trailingIcon = {
                        IconButton(onClick = { showDatePicker = true }) {
                            Icon(Icons.Rounded.CalendarToday, null)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Select next billing date") }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    amount.toDoubleOrNull()?.let { amt ->
                        nextDate?.let { chosenDate ->
                            onAdd(merchant, amt, frequency, category.takeIf { it.isNotEmpty() }, chosenDate)
                        }
                    }
                },
                enabled = merchant.isNotBlank() && amount.toDoubleOrNull() != null && nextDate != null
            ) {
                Text(stringResource(R.string.action_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = nextDate ?: referenceNowMillis
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        nextDate = datePickerState.selectedDateMillis
                        showDatePicker = false
                    }
                ) {
                    Text(stringResource(R.string.action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.Error,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(64.dp)
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = SemanticColors.TextSecondary,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(
                containerColor = SemanticColors.PrimaryIndigo
            )
        ) {
            Text(stringResource(R.string.action_retry))
        }
    }
}
