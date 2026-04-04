@file:OptIn(ExperimentalMaterial3Api::class)

package com.yourname.expensetracker.ui.screens.groups

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
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.data.database.entity.GroupMember
import com.yourname.expensetracker.data.database.entity.SplitType
import com.yourname.expensetracker.domain.logic.SplitCalculator
import com.yourname.expensetracker.ui.theme.SemanticColors
import androidx.compose.ui.res.stringResource
import com.yourname.expensetracker.R
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedExpenseGroupsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SharedExpenseGroupsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val currencyFormat = NumberFormat.getCurrencyInstance(Locale.getDefault())
    
    Scaffold(
        containerColor = SemanticColors.BaseNavy,
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        if (uiState.selectedGroup != null) {
                            uiState.selectedGroup!!.group.name
                        } else {
                            stringResource(R.string.groups_title)
                        },
                        color = SemanticColors.TextPrimary,
                        fontWeight = FontWeight.Bold
                    ) 
                },
                navigationIcon = {
                    IconButton(
                        onClick = { 
                            if (uiState.selectedGroup != null) {
                                viewModel.selectGroup(null)
                            } else {
                                onNavigateBack()
                            }
                        }
                    ) {
                        Icon(
                            if (uiState.selectedGroup != null) Icons.Default.ArrowBack else Icons.Default.Close,
                            contentDescription = stringResource(R.string.cd_back),
                            tint = SemanticColors.TextPrimary
                        )
                    }
                },
                actions = {
                    if (uiState.selectedGroup != null) {
                        IconButton(onClick = { viewModel.toggleAddExpense(true) }) {
                            Icon(
                                Icons.Rounded.Add,
                                contentDescription = stringResource(R.string.a11y_add_expense_to_group),
                                tint = SemanticColors.TextPrimary
                            )
                        }
                        IconButton(onClick = { viewModel.toggleAddMember(true) }) {
                            Icon(
                                Icons.Rounded.PersonAdd,
                                contentDescription = stringResource(R.string.a11y_add_member),
                                tint = SemanticColors.TextPrimary
                            )
                        }
                    }
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(
                            Icons.Rounded.Refresh,
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
            if (uiState.selectedGroup == null) {
                FloatingActionButton(
                    onClick = { viewModel.toggleCreateGroup(true) },
                    containerColor = SemanticColors.PrimaryIndigo
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.a11y_create_group))
                }
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
                uiState.selectedGroup != null -> {
                    // Group Detail View
                    GroupDetailContent(
                        group = uiState.selectedGroup!!,
                        currencyFormat = currencyFormat,
                        onDeleteGroup = { viewModel.deleteGroup(it) }
                    )
                }
                else -> {
                    // Groups List View
                    GroupsListContent(
                        groups = uiState.groups,
                        currencyFormat = currencyFormat,
                        onGroupClick = { viewModel.selectGroup(it) }
                    )
                }
            }
        }
        
        // Dialogs
        if (uiState.creatingGroup) {
            CreateGroupDialog(
                onDismiss = { viewModel.toggleCreateGroup(false) },
                onCreate = { name, desc, currency ->
                    viewModel.createGroup(name, desc, currency)
                }
            )
        }
        
        if (uiState.addingMember && uiState.selectedGroup != null) {
            AddMemberDialog(
                onDismiss = { viewModel.toggleAddMember(false) },
                onAdd = { name, email ->
                    viewModel.addMember(uiState.selectedGroup!!.group.id, name, email)
                }
            )
        }
        
        if (uiState.addingExpense && uiState.selectedGroup != null) {
            AddExpenseDialog(
                members = uiState.selectedGroup!!.members,
                onDismiss = { viewModel.toggleAddExpense(false) },
                onAdd = { description, amount, paidById, splitType, customSplits ->
                    viewModel.addExpense(
                        uiState.selectedGroup!!.group.id,
                        description,
                        amount,
                        paidById,
                        splitType,
                        customSplits
                    )
                }
            )
        }
    }
}

@Composable
private fun GroupsListContent(
    groups: List<GroupWithDetails>,
    currencyFormat: NumberFormat,
    onGroupClick: (GroupWithDetails) -> Unit
) {
    if (groups.isEmpty()) {
        EmptyGroupsState()
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(groups, key = { it.group.id }) { group ->
                GroupCard(
                    group = group,
                    currencyFormat = currencyFormat,
                    onClick = { onGroupClick(group) }
                )
            }
        }
    }
}

@Composable
private fun GroupCard(
    group: GroupWithDetails,
    currencyFormat: NumberFormat,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = SemanticColors.SurfaceLight.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = group.group.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = SemanticColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    
                    group.group.description?.let { desc ->
                        Text(
                            text = desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = SemanticColors.TextSecondary
                        )
                    }
                }
                
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = SemanticColors.TextSecondary
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.label_members_count_format, group.members.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = SemanticColors.TextSecondary
                    )
                    Text(
                        text = stringResource(R.string.label_expenses_count_format, group.expenses.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = SemanticColors.TextSecondary
                    )
                }
                
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = currencyFormat.format(group.totalSpent),
                        style = MaterialTheme.typography.titleMedium,
                        color = SemanticColors.PrimaryIndigo,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.label_total_spent),
                        style = MaterialTheme.typography.bodySmall,
                        color = SemanticColors.TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupDetailContent(
    group: GroupWithDetails,
    currencyFormat: NumberFormat,
    onDeleteGroup: (Long) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Total Card
        item {
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
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.label_total_group_spending),
                        style = MaterialTheme.typography.bodyMedium,
                        color = SemanticColors.TextSecondary
                    )
                    Text(
                        text = currencyFormat.format(group.totalSpent),
                        style = MaterialTheme.typography.headlineMedium,
                        color = SemanticColors.PrimaryIndigo,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        
        // Members Section
        item {
            Text(
                text = stringResource(R.string.header_members_balances),
                style = MaterialTheme.typography.titleMedium,
                color = SemanticColors.TextPrimary,
                fontWeight = FontWeight.Bold
            )
        }
        
        items(group.members, key = { it.id }) { member ->
            MemberBalanceCard(
                member = member,
                balance = group.memberBalances[member.id] ?: 0.0,
                currencyFormat = currencyFormat
            )
        }

        item {
            SettlementPlanSection(
                members = group.members,
                memberBalances = group.memberBalances,
                currencyFormat = currencyFormat
            )
        }
        
        // Expenses Section
        if (group.expenses.isNotEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.header_expenses),
                    style = MaterialTheme.typography.titleMedium,
                    color = SemanticColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
            
            items(group.expenses, key = { it.expense.id }) { expense ->
                ExpenseCard(
                    expense = expense,
                    currencyFormat = currencyFormat
                )
            }
        }
        
        // Delete Group Button
        item {
            Spacer(modifier = Modifier.height(24.dp))
            OutlinedButton(
                onClick = { onDeleteGroup(group.group.id) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(Icons.Rounded.Delete, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.groups_delete_title))
            }
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

@Composable
private fun SettlementPlanSection(
    members: List<GroupMember>,
    memberBalances: Map<Long, Double>,
    currencyFormat: NumberFormat
) {
    val memberNames = remember(members) { members.associate { it.id to it.name } }
    var settledTransferKeys by remember(memberBalances) { mutableStateOf(setOf<String>()) }

    val settlementTransfers = remember(memberBalances) {
        SplitCalculator.simplifyBalances(memberBalances)
    }

    val pendingTransfers = settlementTransfers.filterNot { (fromId, toId, amount) ->
        buildSettlementKey(fromId, toId, amount) in settledTransferKeys
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.groups_settlement_plan_title),
            style = MaterialTheme.typography.titleMedium,
            color = SemanticColors.TextPrimary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp)
        )

        if (pendingTransfers.isEmpty()) {
            Text(
                text = stringResource(R.string.label_settled_up),
                style = MaterialTheme.typography.bodyMedium,
                color = SemanticColors.TextSecondary
            )
        } else {
            pendingTransfers.forEach { (fromId, toId, amount) ->
                val fromName = memberNames[fromId] ?: stringResource(R.string.groups_member_fallback_format, fromId)
                val toName = memberNames[toId] ?: stringResource(R.string.groups_member_fallback_format, toId)

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = SemanticColors.SurfaceLight.copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "$fromName → $toName: ${currencyFormat.format(amount)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SemanticColors.TextPrimary,
                            modifier = Modifier.weight(1f)
                        )

                        TextButton(
                            onClick = {
                                settledTransferKeys = settledTransferKeys + buildSettlementKey(fromId, toId, amount)
                            }
                        ) {
                            Text(stringResource(R.string.groups_settle_action))
                        }
                    }
                }
            }
        }
    }
}

private fun buildSettlementKey(fromId: Long, toId: Long, amount: Double): String {
    return "$fromId-$toId-${"%.2f".format(Locale.US, amount)}"
}

@Composable
private fun MemberBalanceCard(
    member: GroupMember,
    balance: Double,
    currencyFormat: NumberFormat
) {
    val isPositive = balance > 0
    val isZero = balance == 0.0
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isPositive -> Color(0xFF4CAF50).copy(alpha = 0.15f)
                isZero -> SemanticColors.SurfaceLight.copy(alpha = 0.5f)
                else -> Color(0xFFF44336).copy(alpha = 0.15f)
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (member.isCurrentUser) {
                    Icon(
                        imageVector = Icons.Rounded.Person,
                        contentDescription = stringResource(R.string.a11y_you),
                        tint = SemanticColors.PrimaryIndigo,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                
                Text(
                    text = if (member.isCurrentUser) stringResource(R.string.label_you_format, member.name) else member.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = SemanticColors.TextPrimary,
                    fontWeight = FontWeight.Medium
                )
            }
            
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = when {
                        isPositive -> stringResource(R.string.label_gets_back_format, currencyFormat.format(balance))
                        isZero -> stringResource(R.string.label_settled_up)
                        else -> stringResource(R.string.label_owes_format, currencyFormat.format(-balance))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        isPositive -> Color(0xFF4CAF50)
                        isZero -> SemanticColors.TextSecondary
                        else -> Color(0xFFF44336)
                    },
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun ExpenseCard(
    expense: GroupExpenseWithDetails,
    currencyFormat: NumberFormat
) {
    val dateFormat = SimpleDateFormat("MMM dd", Locale.getDefault())
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = SemanticColors.SurfaceLight.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = expense.expense.description,
                    style = MaterialTheme.typography.bodyLarge,
                    color = SemanticColors.TextPrimary,
                    fontWeight = FontWeight.Medium
                )
                
                Text(
                    text = currencyFormat.format(expense.expense.totalAmount),
                    style = MaterialTheme.typography.bodyLarge,
                    color = SemanticColors.PrimaryIndigo,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Text(
                text = stringResource(R.string.label_paid_by_format, expense.paidByName, dateFormat.format(Date(expense.expense.date))),
                style = MaterialTheme.typography.bodySmall,
                color = SemanticColors.TextSecondary
            )
            
            Text(
                text = stringResource(R.string.label_split_format, expense.expense.splitType.name.lowercase().replaceFirstChar { it.uppercase() }),
                style = MaterialTheme.typography.bodySmall,
                color = SemanticColors.TextSecondary
            )
        }
    }
}

@Composable
private fun EmptyGroupsState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.People,
            contentDescription = null,
            tint = SemanticColors.TextSecondary,
            modifier = Modifier.size(80.dp)
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = stringResource(R.string.empty_groups_title),
            style = MaterialTheme.typography.headlineSmall,
            color = SemanticColors.TextPrimary,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = stringResource(R.string.empty_groups_message),
            style = MaterialTheme.typography.bodyMedium,
            color = SemanticColors.TextSecondary,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun CreateGroupDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String?, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf("EUR") }
    val currencies = listOf("EUR", "USD", "GBP")
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.groups_create_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.groups_name_label)) },
                    placeholder = { Text(stringResource(R.string.groups_name_placeholder)) },
                    modifier = Modifier.fillMaxWidth()
                )
                
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.groups_description_label)) },
                    placeholder = { Text(stringResource(R.string.groups_description_placeholder)) },
                    modifier = Modifier.fillMaxWidth()
                )
                
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it }
                ) {
                    OutlinedTextField(
                        value = currency,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.label_currency)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        currencies.forEach { cur ->
                            DropdownMenuItem(
                                text = { Text(cur) },
                                onClick = { currency = cur; expanded = false }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(name, description.takeIf { it.isNotBlank() }, currency) },
                enabled = name.isNotBlank()
            ) {
                Text(stringResource(R.string.groups_create_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
private fun AddMemberDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.groups_add_member_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.groups_member_name_label)) },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text(stringResource(R.string.groups_member_email_label)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(name, email.takeIf { it.isNotBlank() }) },
                enabled = name.isNotBlank()
            ) {
                Text(stringResource(R.string.groups_add_member_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
private fun AddExpenseDialog(
    members: List<GroupMember>,
    onDismiss: () -> Unit,
    onAdd: (String, Double, Long, SplitType, Map<Long, Double>?) -> Unit
) {
    var description by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var paidById by remember { mutableStateOf(members.firstOrNull()?.id ?: 0L) }
    var splitType by remember { mutableStateOf(SplitType.EQUAL) }
    var memberSplitInputs by remember {
        mutableStateOf(members.associate { it.id to "" })
    }

    val totalAmount = amount.toDoubleOrNull()
    val amountErrorRes = when {
        amount.isBlank() -> null
        totalAmount == null -> R.string.error_invalid_amount
        totalAmount < 0.0 -> R.string.groups_split_error_non_negative_amount
        else -> null
    }

    val parsedMemberSplits = memberSplitInputs.mapValues { it.value.trim().toDoubleOrNull() }
    val splitTotal = parsedMemberSplits.values.sumOf { it ?: 0.0 }
    val isNonEqualSplit = splitType != SplitType.EQUAL
    val memberSplitErrors: Map<Long, Int?> = if (isNonEqualSplit) {
        members.associate { member ->
            val rawValue = memberSplitInputs[member.id].orEmpty().trim()
            val parsedValue = parsedMemberSplits[member.id]
            val errorRes = when {
                rawValue.isBlank() -> R.string.groups_split_error_required
                parsedValue == null -> R.string.groups_split_error_invalid_number
                parsedValue < 0.0 -> R.string.groups_split_error_non_negative
                splitType == SplitType.CUSTOM_PERCENT && parsedValue > 100.0 -> R.string.groups_split_error_percent_range
                else -> null
            }
            member.id to errorRes
        }
    } else {
        emptyMap()
    }

    val hasMemberFieldErrors = memberSplitErrors.values.any { it != null }
    val hasNegativeComponents = parsedMemberSplits.values.any { it != null && it < 0.0 }
    val splitSummaryErrorRes = when (splitType) {
        SplitType.EQUAL -> null
        SplitType.CUSTOM_PERCENT -> when {
            hasMemberFieldErrors -> null
            hasNegativeComponents || splitTotal < 0.0 -> R.string.groups_split_error_total_non_negative
            abs(splitTotal - 100.0) > 0.1 -> R.string.groups_split_error_percent_total
            else -> null
        }
        SplitType.CUSTOM_AMOUNT, SplitType.UNEQUAL -> when {
            totalAmount == null -> R.string.error_invalid_amount
            totalAmount < 0.0 -> R.string.groups_split_error_non_negative_amount
            hasMemberFieldErrors -> null
            hasNegativeComponents || splitTotal < 0.0 -> R.string.groups_split_error_total_non_negative
            abs(splitTotal - totalAmount) > 0.01 -> R.string.groups_split_error_amount_total
            else -> null
        }
    }

    val isSplitValid = when (splitType) {
        SplitType.EQUAL -> true
        else -> !hasMemberFieldErrors && splitSummaryErrorRes == null
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.groups_add_expense_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.groups_expense_description_label)) },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text(stringResource(R.string.groups_expense_amount_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = amountErrorRes != null,
                    supportingText = {
                        amountErrorRes?.let { Text(stringResource(it)) }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                
                // Paid By
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it }
                ) {
                    val selectedMember = members.find { it.id == paidById }
                    OutlinedTextField(
                        value = selectedMember?.name ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.groups_paid_by_label)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        members.forEach { member ->
                            DropdownMenuItem(
                                text = {
                                    val memberDisplayName = if (member.isCurrentUser) {
                                        stringResource(R.string.label_you_format, member.name)
                                    } else {
                                        member.name
                                    }
                                    Text(memberDisplayName)
                                },
                                onClick = { paidById = member.id; expanded = false }
                            )
                        }
                    }
                }
                
                // Split Type
                var splitExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = splitExpanded,
                    onExpandedChange = { splitExpanded = it }
                ) {
                    OutlinedTextField(
                        value = splitType.name.lowercase().replaceFirstChar { it.uppercase() },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.groups_split_type_label)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(splitExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = splitExpanded,
                        onDismissRequest = { splitExpanded = false }
                    ) {
                        SplitType.values().forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                onClick = {
                                    splitType = type
                                    splitExpanded = false
                                    memberSplitInputs = members.associate { member ->
                                        val seeded = when (type) {
                                            SplitType.EQUAL -> ""
                                            SplitType.CUSTOM_PERCENT -> String.format(
                                                Locale.US,
                                                "%.2f",
                                                100.0 / members.size.coerceAtLeast(1)
                                            )
                                            SplitType.CUSTOM_AMOUNT, SplitType.UNEQUAL -> {
                                                val amt = amount.toDoubleOrNull()?.div(members.size.coerceAtLeast(1)) ?: 0.0
                                                String.format(Locale.US, "%.2f", amt)
                                            }
                                        }
                                        member.id to seeded
                                    }
                                }
                            )
                        }
                    }
                }

                if (isNonEqualSplit) {
                    HorizontalDivider()
                    Text(
                        text = if (splitType == SplitType.CUSTOM_PERCENT) {
                            stringResource(R.string.groups_split_hint_enter_percent)
                        } else {
                            stringResource(R.string.groups_split_hint_enter_amount)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = SemanticColors.TextSecondary
                    )

                    members.forEach { member ->
                        val memberDisplayName = if (member.isCurrentUser) {
                            stringResource(R.string.label_you_format, member.name)
                        } else {
                            member.name
                        }
                        val fieldErrorRes = memberSplitErrors[member.id]

                        OutlinedTextField(
                            value = memberSplitInputs[member.id].orEmpty(),
                            onValueChange = { value ->
                                memberSplitInputs = memberSplitInputs.toMutableMap().apply {
                                    this[member.id] = value
                                }
                            },
                            label = {
                                Text(
                                    if (splitType == SplitType.CUSTOM_PERCENT) {
                                        stringResource(R.string.groups_split_member_percent_label, memberDisplayName)
                                    } else {
                                        memberDisplayName
                                    }
                                )
                            },
                            suffix = {
                                if (splitType == SplitType.CUSTOM_PERCENT) {
                                    Text("%")
                                }
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            isError = fieldErrorRes != null,
                            supportingText = {
                                fieldErrorRes?.let { Text(stringResource(it)) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }

                    val summaryText = when (splitType) {
                        SplitType.CUSTOM_PERCENT -> stringResource(
                            R.string.groups_split_summary_percent,
                            String.format(Locale.US, "%.2f", splitTotal)
                        )
                        SplitType.CUSTOM_AMOUNT, SplitType.UNEQUAL -> {
                            val totalText = String.format(Locale.US, "%.2f", splitTotal)
                            val amountText = totalAmount?.let { String.format(Locale.US, "%.2f", it) } ?: "-"
                            stringResource(R.string.groups_split_summary_amount, totalText, amountText)
                        }
                        SplitType.EQUAL -> ""
                    }

                    Text(
                        text = splitSummaryErrorRes?.let { stringResource(it) } ?: summaryText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (splitSummaryErrorRes == null && !hasMemberFieldErrors) {
                            Color(0xFF4CAF50)
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    amount.toDoubleOrNull()?.let { amt ->
                        val customSplits = when (splitType) {
                            SplitType.EQUAL -> null
                            else -> parsedMemberSplits.mapValues { it.value ?: 0.0 }
                        }
                        onAdd(description, amt, paidById, splitType, customSplits)
                    }
                },
                enabled = description.isNotBlank() && totalAmount != null && totalAmount >= 0.0 && isSplitValid
            ) {
                Text(stringResource(R.string.groups_add_expense_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
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
        Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = SemanticColors.PrimaryIndigo)) {
            Text(stringResource(R.string.action_retry))
        }
    }
}
