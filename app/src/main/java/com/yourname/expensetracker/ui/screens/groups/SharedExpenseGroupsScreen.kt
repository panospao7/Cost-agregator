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
import com.yourname.expensetracker.domain.groups.GroupSettlementBalancePolicy
import com.yourname.expensetracker.domain.logic.SplitCalculator
import com.yourname.expensetracker.domain.currency.SupportedCurrency
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import com.yourname.expensetracker.ui.theme.SemanticColors
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.yourname.expensetracker.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.pow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedExpenseGroupsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SharedExpenseGroupsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

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
                        onDeleteGroup = { viewModel.deleteGroup(it) }
                    )
                }
                else -> {
                    // Groups List View
                    GroupsListContent(
                        groups = uiState.groups,
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
                },
                homeCurrency = uiState.homeCurrency
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
                members = uiState.selectedGroup!!.activeMembers,
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
                    onClick = { onGroupClick(group) }
                )
            }
        }
    }
}

@Composable
private fun GroupCard(
    group: GroupWithDetails,
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
                    // Each card is labelled with its own group currency (OPEN-1), never the home
                    // currency. UNAVAILABLE reads "unavailable", never zero.
                    Text(
                        text = displayedTotalText(group.displayed),
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
            LeftOutNotice(group.displayed)
        }
    }
}

/** The group total in the group currency, or "unavailable" when it could not be computed. */
@Composable
private fun displayedTotalText(displayed: GroupSettlementBalancePolicy.DisplayedBalances): String {
    val includedTotal = displayed.includedTotal
    return if (includedTotal == null) {
        stringResource(R.string.groups_total_unavailable)
    } else {
        CurrencyFormatter.formatMoney(includedTotal, displayed.currency)
    }
}

/**
 * INV-8: one combined notice, shown only when PARTIAL. Count only; no description, amount,
 * member name or currency wording.
 */
@Composable
private fun LeftOutNotice(displayed: GroupSettlementBalancePolicy.DisplayedBalances) {
    val leftOut = leftOutItemCount(displayed)
    if (displayed.availability != GroupSettlementBalancePolicy.Availability.PARTIAL || leftOut <= 0) return
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = pluralStringResource(R.plurals.money_total_partial_notice, leftOut, leftOut),
        style = MaterialTheme.typography.bodySmall,
        color = SemanticColors.TextSecondary
    )
}

/** INV-8: N = excluded expenses + excluded considered settlements. */
internal fun leftOutItemCount(displayed: GroupSettlementBalancePolicy.DisplayedBalances): Int =
    displayed.excludedExpenseCount + displayed.excludedSettlementCount

/**
 * INV-7: the suggested transfers, or null when the plan must not be composed (PARTIAL or
 * UNAVAILABLE). The solver itself is unchanged.
 */
internal fun suggestedSettlementTransfers(
    displayed: GroupSettlementBalancePolicy.DisplayedBalances
): List<Triple<Long, Long, Double>>? =
    if (displayed.settlementPlanAllowed) {
        SplitCalculator.simplifyBalances(displayed.memberBalances)
    } else {
        null
    }

/** INV-11: the home currency pre-selects only when it is a supported currency; otherwise nothing. */
internal fun initialGroupCurrencySelection(homeCurrency: String?): String? =
    homeCurrency?.let { SupportedCurrency.fromCode(it)?.code }

/** INV-11: Create needs a name and an explicitly selected supported currency. */
internal fun canCreateGroup(name: String, selectedCurrency: String?): Boolean =
    name.isNotBlank() && selectedCurrency != null &&
        SupportedCurrency.fromCode(selectedCurrency)?.code == selectedCurrency

@Composable
private fun GroupDetailContent(
    group: GroupWithDetails,
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
                        text = displayedTotalText(group.displayed),
                        style = MaterialTheme.typography.headlineMedium,
                        color = SemanticColors.PrimaryIndigo,
                        fontWeight = FontWeight.Bold
                    )
                    LeftOutNotice(group.displayed)
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
        
        if (group.displayed.availability == GroupSettlementBalancePolicy.Availability.UNAVAILABLE) {
            // No balances exist to show; an absent balance must never read as "settled up".
            item {
                Text(
                    text = stringResource(R.string.groups_balances_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = SemanticColors.TextSecondary
                )
            }
        } else {
            items(group.members, key = { it.id }) { member ->
                MemberBalanceCard(
                    member = member,
                    balance = group.displayed.memberBalances[member.id] ?: 0.0,
                    groupCurrency = group.currency
                )
            }
        }

        // INV-7: composed only for an AVAILABLE group.
        if (group.displayed.settlementPlanAllowed) {
            item {
                SettlementPlanSection(
                    members = group.members,
                    displayed = group.displayed
                )
            }
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
                    groupCurrency = group.currency
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
    displayed: GroupSettlementBalancePolicy.DisplayedBalances
) {
    val memberNames = remember(members) { members.associate { it.id to it.name } }

    // The key is the whole result, so a PARTIAL/AVAILABLE change can never reuse a stale plan.
    val settlementTransfers = remember(displayed) { suggestedSettlementTransfers(displayed) }
        ?: return
    val groupCurrency = displayed.currency

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.groups_settlement_plan_title),
            style = MaterialTheme.typography.titleMedium,
            color = SemanticColors.TextPrimary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp)
        )
        Text(
            text = stringResource(R.string.groups_suggested_transfers_hint),
            style = MaterialTheme.typography.bodySmall,
            color = SemanticColors.TextSecondary
        )

        if (settlementTransfers.isEmpty()) {
            Text(
                text = stringResource(R.string.label_settled_up),
                style = MaterialTheme.typography.bodyMedium,
                color = SemanticColors.TextSecondary
            )
        } else {
            settlementTransfers.forEach { (fromId, toId, amount) ->
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
                            text = "$fromName → $toName: ${CurrencyFormatter.formatMoney(amount, groupCurrency)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SemanticColors.TextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

internal fun roundedBalanceForDisplay(
    balance: Double,
    fractionDigits: Int = 2
): Double {
    if (!balance.isFinite()) return balance
    return BigDecimal.valueOf(balance)
        .setScale(fractionDigits.coerceAtLeast(0), RoundingMode.HALF_UP)
        .toDouble()
}

internal fun isSettledBalance(
    balance: Double,
    fractionDigits: Int = 2
): Boolean {
    if (!balance.isFinite()) return false
    val normalizedDigits = fractionDigits.coerceAtLeast(0)
    val threshold = 0.5 * 10.0.pow(-normalizedDigits)
    return abs(balance) < threshold
}

@Composable
private fun MemberBalanceCard(
    member: GroupMember,
    balance: Double,
    groupCurrency: String
) {
    val displayBalance = roundedBalanceForDisplay(balance)
    val isPositive = displayBalance > 0
    val isZero = isSettledBalance(balance)
    
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
                        isPositive -> stringResource(R.string.label_gets_back_format, CurrencyFormatter.formatMoney(displayBalance, groupCurrency))
                        isZero -> stringResource(R.string.label_settled_up)
                        else -> stringResource(R.string.label_owes_format, CurrencyFormatter.formatMoney(-displayBalance, groupCurrency))
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
    groupCurrency: String
) {
    val dateFormat = DateTimeFormatter.ofPattern("MMM dd", Locale.getDefault())
    
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
                    text = CurrencyFormatter.formatMoney(expense.expense.totalAmount, groupCurrency),
                    style = MaterialTheme.typography.bodyLarge,
                    color = SemanticColors.PrimaryIndigo,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Text(
                text = stringResource(R.string.label_paid_by_format, expense.paidByName, dateFormat.format(Instant.ofEpochMilli(expense.expense.date).atZone(ZoneId.systemDefault()))),
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
    onCreate: (String, String?, String) -> Unit,
    /** Null when the home currency is unknown: nothing is pre-selected (INV-11). */
    homeCurrency: String?
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf(initialGroupCurrencySelection(homeCurrency)) }
    val currencies = SupportedCurrency.values().map { it.code }
    
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
                        value = currency.orEmpty(),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.label_currency)) },
                        placeholder = { Text(stringResource(R.string.groups_currency_not_selected)) },
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
                onClick = {
                    currency?.let { selected ->
                        onCreate(name, description.takeIf { it.isNotBlank() }, selected)
                    }
                },
                enabled = canCreateGroup(name, currency)
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
    val activeMemberIds = remember(members) { members.map { it.id }.toSet() }
    var paidById by remember { mutableStateOf(members.firstOrNull()?.id ?: 0L) }
    LaunchedEffect(activeMemberIds) {
        if (paidById !in activeMemberIds) paidById = 0L
    }
    var splitType by remember { mutableStateOf(SplitType.EQUAL) }
    var memberSplitInputs by remember(activeMemberIds) {
        mutableStateOf(members.associate { it.id to "" })
    }

    val totalAmount = com.yourname.expensetracker.ui.util.AmountInputSanitizer.parseOrNull(amount)
    val amountErrorRes = when {
        amount.isBlank() -> null
        totalAmount == null -> R.string.error_invalid_amount
        totalAmount < 0.0 -> R.string.groups_split_error_non_negative_amount
        else -> null
    }

    val parsedMemberSplits = memberSplitInputs.mapValues {
        com.yourname.expensetracker.ui.util.AmountInputSanitizer.parseOrNull(it.value.trim())
    }
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
                                                val amt = com.yourname.expensetracker.ui.util.AmountInputSanitizer.parseOrNull(amount)
                                                    ?.div(members.size.coerceAtLeast(1)) ?: 0.0
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
                    com.yourname.expensetracker.ui.util.AmountInputSanitizer.parseOrNull(amount)?.let { amt ->
                        val customSplits = when (splitType) {
                            SplitType.EQUAL -> null
                            else -> parsedMemberSplits.mapValues { it.value ?: 0.0 }
                        }
                        onAdd(description, amt, paidById, splitType, customSplits)
                    }
                },
                enabled = description.isNotBlank() && totalAmount != null && totalAmount >= 0.0 && isSplitValid
                    && members.isNotEmpty() && members.any { it.id == paidById }
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
