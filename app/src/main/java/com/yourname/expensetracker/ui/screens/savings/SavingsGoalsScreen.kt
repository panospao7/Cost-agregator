package com.yourname.expensetracker.ui.screens.savings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.domain.savings.SavingsStreak
import androidx.compose.ui.res.stringResource
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.entity.GoalProtectionLevel
import com.yourname.expensetracker.domain.usecase.savings.GoalAllocation
import com.yourname.expensetracker.domain.usecase.savings.SavingsSweepRecommendation
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavingsGoalsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SavingsGoalsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val dateFormat = remember { SimpleDateFormat("MMM yyyy", Locale.getDefault()) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showAddGoalDialog by remember { mutableStateOf(false) }
    var recommendationToConfirm by remember { mutableStateOf<SmartRecommendation?>(null) }

    Scaffold(
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_savings_goals)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_refresh))
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddGoalDialog = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(R.string.savings_action_add_goal)) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Gamification Header
            GamificationHeader(
                level = state.userLevel,
                title = state.levelTitle,
                totalSaved = state.totalSaved,
                streak = state.streak
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Monthly Savings Sweep Recommendation
            val sweepRec = state.sweepRecommendation
            if (sweepRec != null) {
                SweepRecommendationCard(
                    recommendation = sweepRec,
                    onAccept = { viewModel.acceptSweepRecommendation() },
                    onDismiss = { viewModel.dismissSweepRecommendation() }
                )
                
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Lifestyle-Linked Recommendations
            val lifestyleRec = state.lifestyleRecommendation
            if (lifestyleRec != null) {
                LifestyleRecommendationCard(
                    recommendation = lifestyleRec,
                    onAccept = { viewModel.acceptLifestyleRecommendation(null) },
                    onDismiss = { viewModel.dismissLifestyleRecommendation() }
                )
                
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Smart Recommendations
            if (state.smartRecommendations.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.savings_section_recommendations),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                LazyColumn(
                    modifier = Modifier.height(120.dp)
                ) {
                    items(state.smartRecommendations.take(3)) { rec ->
                        SmartRecommendationCard(
                            rec = rec,
                            onSave = { recommendationToConfirm = rec }
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Goals List
            Text(
                text = stringResource(R.string.savings_section_your_goals),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (state.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (state.goals.isEmpty()) {
                EmptyGoalsState()
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.goals) { goal ->
                        GoalCard(
                            goal = goal,
                            dateFormat = dateFormat,
                            onClick = { viewModel.selectGoal(goal) },
                            onQuickAdd = { viewModel.contributeToGoal(goal.id, 10.0) }
                        )
                    }
                }
            }
        }

        if (showAddGoalDialog) {
            AddGoalDialog(
                onDismiss = { showAddGoalDialog = false },
                onConfirm = { name, targetAmount, targetDate, protectionLevel ->
                    viewModel.addGoal(
                        name = name,
                        targetAmount = targetAmount,
                        targetDate = targetDate,
                        protectionLevel = protectionLevel
                    )
                    showAddGoalDialog = false
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            message = "Goal created",
                            withDismissAction = true
                        )
                    }
                }
            )
        }

        recommendationToConfirm?.let { recommendation ->
            AlertDialog(
                onDismissRequest = { recommendationToConfirm = null },
                title = { Text("Contribute to ${recommendation.goal.name}?") },
                text = {
                    Text(
                        "Add €${String.format("%.2f", recommendation.recommendedAmount)} " +
                                "to this goal now?"
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.contributeToGoal(
                                goalId = recommendation.goal.id,
                                amount = recommendation.recommendedAmount
                            )
                            recommendationToConfirm = null
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    message = "Saved €${String.format("%.2f", recommendation.recommendedAmount)}",
                                    withDismissAction = true
                                )
                            }
                        }
                    ) {
                        Text(stringResource(R.string.action_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { recommendationToConfirm = null }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }
    }
}

@Composable
private fun GamificationHeader(
    level: Int,
    title: String,
    totalSaved: Double,
    streak: SavingsStreak?
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.savings_level_format, level),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                // Streak badge
                if (streak != null && streak.currentStreakDays > 0) {
                    Badge(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text("${streak.currentStreakDays}${stringResource(R.string.savings_fire_emoji)}")
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = stringResource(R.string.savings_label_total_saved, String.format("%.2f", totalSaved)),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            
            if (streak != null) {
                Text(
                    text = stringResource(R.string.savings_contributions_this_month, streak.monthlyContributions),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SmartRecommendationCard(
    rec: SmartRecommendation,
    onSave: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = rec.goal.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = stringResource(R.string.savings_safe_to_save, String.format("%.2f", rec.recommendedAmount)),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = rec.impact,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
            }
            
            Button(
                onClick = onSave,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text(stringResource(R.string.savings_action_save))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddGoalDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, targetAmount: Double, targetDate: Long?, protectionLevel: GoalProtectionLevel) -> Unit
) {
    var goalName by remember { mutableStateOf("") }
    var targetAmountInput by remember { mutableStateOf("") }
    var targetDateMillis by remember { mutableStateOf<Long?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }
    var protectionLevel by remember { mutableStateOf(GoalProtectionLevel.WARNING) }

    val parsedAmount = targetAmountInput.toDoubleOrNull()
    val isNameValid = goalName.trim().isNotEmpty()
    val isAmountValid = parsedAmount != null && parsedAmount > 0.0
    val dateLabel = remember(targetDateMillis) {
        targetDateMillis?.let { millis ->
            SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(millis))
        } ?: "No date"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add savings goal") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = goalName,
                    onValueChange = { goalName = it },
                    label = { Text("Goal name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    isError = goalName.isNotBlank() && !isNameValid
                )

                OutlinedTextField(
                    value = targetAmountInput,
                    onValueChange = { value ->
                        if (value.isEmpty() || value.matches(Regex("^\\d*\\.?\\d{0,2}$"))) {
                            targetAmountInput = value
                        }
                    },
                    label = { Text("Target amount") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    isError = targetAmountInput.isNotEmpty() && !isAmountValid
                )

                Text(
                    text = "Target date: $dateLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showDatePicker = true }) {
                        Text(if (targetDateMillis == null) "Set date" else "Change date")
                    }
                    if (targetDateMillis != null) {
                        TextButton(onClick = { targetDateMillis = null }) {
                            Text("Clear")
                        }
                    }
                }

                Text(
                    text = "Protection level",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GoalProtectionLevel.entries.forEach { level ->
                        FilterChip(
                            selected = protectionLevel == level,
                            onClick = { protectionLevel = level },
                            label = { Text(level.name.lowercase().replaceFirstChar { it.uppercase() }) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = isNameValid && isAmountValid,
                onClick = {
                    onConfirm(
                        goalName.trim(),
                        parsedAmount ?: 0.0,
                        targetDateMillis,
                        protectionLevel
                    )
                }
            ) {
                Text(stringResource(R.string.action_create))
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
            initialSelectedDateMillis = targetDateMillis
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        targetDateMillis = datePickerState.selectedDateMillis
                        showDatePicker = false
                    }
                ) {
                    Text(stringResource(R.string.action_confirm))
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
private fun GoalCard(
    goal: com.yourname.expensetracker.data.database.entity.SavingsGoal,
    dateFormat: SimpleDateFormat,
    onClick: () -> Unit,
    onQuickAdd: () -> Unit
) {
    val progress = (goal.currentAmount / goal.targetAmount).coerceIn(0.0, 1.0)
    val isCompleted = goal.currentAmount >= goal.targetAmount
    
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = goal.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    
                if (goal.targetDate != null) {
                    Text(
                        text = stringResource(R.string.savings_target_date, dateFormat.format(Date(goal.targetDate))),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                }
                
                if (isCompleted) {
                    Badge(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    ) {
                        Text(stringResource(R.string.savings_done))
                    }
                } else {
                    TextButton(onClick = onQuickAdd) {
                        Text(stringResource(R.string.savings_action_add_amount, "10"))
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Progress bar
            LinearProgressIndicator(
                progress = { progress.toFloat() },
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.savings_amount_eur, String.format("%.2f", goal.currentAmount)),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(R.string.savings_of_target, String.format("%.2f", goal.targetAmount)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Text(
                text = stringResource(R.string.savings_progress_percent_complete, (progress * 100).toInt()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EmptyGoalsState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "🎯",
                style = MaterialTheme.typography.displayLarge
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.savings_empty_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(R.string.savings_empty_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LifestyleRecommendationCard(
    recommendation: com.yourname.expensetracker.domain.usecase.savings.LifestyleSavingsRecommendation,
    onAccept: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = androidx.compose.material.icons.Icons.Rounded.TrendingUp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Text(
                    text = "💡 Lifestyle Inflation Alert",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = recommendation.reason,
                style = MaterialTheme.typography.bodyMedium
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Show suggested uplift
            Text(
                text = "Suggested savings boost: +${String.format("%.1f", recommendation.suggestedMonthlyUplift)}% monthly",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
                fontWeight = FontWeight.Medium
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OutlinedButton(
                    onClick = onDismiss
                ) {
                    Text("Not Now")
                }
                
                Button(
                    onClick = onAccept
                ) {
                    Icon(androidx.compose.material.icons.Icons.Rounded.Savings, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Boost Savings")
                }
            }
        }
    }
}

@Composable
private fun SweepRecommendationCard(
    recommendation: SavingsSweepRecommendation,
    onAccept: () -> Unit,
    onDismiss: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM d", Locale.getDefault()) }
    val monthEndDate = remember(recommendation.monthEnd) { Date(recommendation.monthEnd) }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = androidx.compose.material.icons.Icons.Rounded.Savings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Column {
                    Text(
                        text = "End of Month Sweep",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Month ends ${dateFormat.format(monthEndDate)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Safe to save amount
            Text(
                text = "Safe to Save: €${String.format("%.2f", recommendation.safeSweepAmount)}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            // Underspend and buffer details
            Text(
                text = "Underspend: €${String.format("%.2f", recommendation.totalUnderspend)} • " +
                       "Buffer: €${String.format("%.2f", recommendation.riskBuffer)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Confidence indicator
            if (recommendation.confidence >= 0.6) {
                val confidenceText = when {
                    recommendation.confidence >= 0.8 -> "High confidence"
                    else -> "Good confidence"
                }
                Text(
                    text = "✓ $confidenceText",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            
            // Goal allocations
            if (recommendation.goalAllocations.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    text = "Suggested allocation:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                recommendation.goalAllocations.take(3).forEach { allocation ->
                    GoalAllocationRow(allocation)
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OutlinedButton(
                    onClick = onDismiss
                ) {
                    Text("Skip")
                }
                
                Button(
                    onClick = onAccept
                ) {
                    Icon(androidx.compose.material.icons.Icons.Rounded.Savings, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Sweep to Goals")
                }
            }
        }
    }
}

@Composable
private fun GoalAllocationRow(allocation: GoalAllocation) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = allocation.goalName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            
            val progressAfter = allocation.getProgressAfterAllocation()
            Text(
                text = "Progress: ${String.format("%.0f", progressAfter)}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
        }
        
        Text(
            text = "+€${String.format("%.2f", allocation.suggestedAllocation)}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
