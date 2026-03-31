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
import com.yourname.expensetracker.data.database.entity.ExpenseGroup
import com.yourname.expensetracker.data.database.entity.GroupMember
import com.yourname.expensetracker.data.database.entity.SplitType
import com.yourname.expensetracker.ui.theme.SemanticColors
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
                            "Shared Expense Groups"
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
                            contentDescription = "Back",
                            tint = SemanticColors.TextPrimary
                        )
                    }
                },
                actions = {
                    if (uiState.selectedGroup != null) {
                        IconButton(onClick = { viewModel.toggleAddExpense(true) }) {
                            Icon(
                                Icons.Rounded.Add,
                                contentDescription = "Add Expense",
                                tint = SemanticColors.TextPrimary
                            )
                        }
                        IconButton(onClick = { viewModel.toggleAddMember(true) }) {
                            Icon(
                                Icons.Rounded.PersonAdd,
                                contentDescription = "Add Member",
                                tint = SemanticColors.TextPrimary
                            )
                        }
                    }
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = "Refresh",
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
                    Icon(Icons.Default.Add, contentDescription = "Create Group")
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
                onAdd = { description, amount, paidById, splitType ->
                    viewModel.addExpense(
                        uiState.selectedGroup!!.group.id,
                        description,
                        amount,
                        paidById,
                        splitType
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
                        text = "${group.members.size} members",
                        style = MaterialTheme.typography.bodySmall,
                        color = SemanticColors.TextSecondary
                    )
                    Text(
                        text = "${group.expenses.size} expenses",
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
                        text = "total spent",
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
                        text = "Total Group Spending",
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
                text = "Members & Balances",
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
        
        // Expenses Section
        if (group.expenses.isNotEmpty()) {
            item {
                Text(
                    text = "Expenses",
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
                Text("Delete Group")
            }
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
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
                        contentDescription = "You",
                        tint = SemanticColors.PrimaryIndigo,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                
                Text(
                    text = member.name + if (member.isCurrentUser) " (You)" else "",
                    style = MaterialTheme.typography.bodyLarge,
                    color = SemanticColors.TextPrimary,
                    fontWeight = FontWeight.Medium
                )
            }
            
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = when {
                        isPositive -> "gets back ${currencyFormat.format(balance)}"
                        isZero -> "settled up"
                        else -> "owes ${currencyFormat.format(-balance)}"
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
                text = "Paid by ${expense.paidByName} on ${dateFormat.format(Date(expense.expense.date))}",
                style = MaterialTheme.typography.bodySmall,
                color = SemanticColors.TextSecondary
            )
            
            Text(
                text = "Split: ${expense.expense.splitType.name.lowercase().replaceFirstChar { it.uppercase() }}",
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
            text = "No Shared Expense Groups",
            style = MaterialTheme.typography.headlineSmall,
            color = SemanticColors.TextPrimary,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "Create a group to split expenses with friends, family, or roommates",
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
        title = { Text("Create New Group") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Group Name") },
                    placeholder = { Text("e.g., Weekend Trip") },
                    modifier = Modifier.fillMaxWidth()
                )
                
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (Optional)") },
                    placeholder = { Text("e.g., Paris trip with friends") },
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
                        label = { Text("Currency") },
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
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
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
        title = { Text("Add Member") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email (Optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(name, email.takeIf { it.isNotBlank() }) },
                enabled = name.isNotBlank()
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun AddExpenseDialog(
    members: List<GroupMember>,
    onDismiss: () -> Unit,
    onAdd: (String, Double, Long, SplitType) -> Unit
) {
    var description by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var paidById by remember { mutableStateOf(members.firstOrNull()?.id ?: 0L) }
    var splitType by remember { mutableStateOf(SplitType.EQUAL) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Group Expense") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Amount") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
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
                        label = { Text("Paid By") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        members.forEach { member ->
                            DropdownMenuItem(
                                text = { Text(member.name + if (member.isCurrentUser) " (You)" else "") },
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
                        label = { Text("Split Type") },
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
                                onClick = { splitType = type; splitExpanded = false }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    amount.toDoubleOrNull()?.let { amt ->
                        onAdd(description, amt, paidById, splitType)
                    }
                },
                enabled = description.isNotBlank() && amount.toDoubleOrNull() != null
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
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
            Text("Try Again")
        }
    }
}