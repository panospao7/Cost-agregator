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
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.domain.savings.SavingsStreak
import androidx.compose.ui.res.stringResource
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.model.GoalProtectionLevel
import com.yourname.expensetracker.domain.model.SavingsGoal
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import com.yourname.expensetracker.domain.usecase.savings.GoalAllocation
import com.yourname.expensetracker.domain.usecase.savings.SavingsSweepRecommendation
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavingsGoalsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SavingsGoalsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val dateFormat = remember { DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault()) }
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
            streak = state.streak,
            homeCurrency = state.homeCurrency
        )

            Spacer(modifier = Modifier.height(16.dp))

            // Monthly Savings Sweep Recommendation
            val sweepRec = state.sweepRecommendation
            if (sweepRec != null) {
            SweepRecommendationCard(
                recommendation = sweepRec,
                onAccept = { viewModel.acceptSweepRecommendation() },
                onDismiss = { viewModel.dismissSweepRecommendation() },
                homeCurrency = state.homeCurrency
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
                onSave = { recommendationToConfirm = rec },
                homeCurrency = state.homeCurrency
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

            when (val loadable = state.loadableState) {
                is com.yourname.expensetracker.ui.model.LoadableUiState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                is com.yourname.expensetracker.ui.model.LoadableUiState.Empty -> {
                    EmptyGoalsState()
                }
                is com.yourname.expensetracker.ui.model.LoadableUiState.Error -> {
                    EmptyGoalsState() // Fallback to empty state on error
                }
                is com.yourname.expensetracker.ui.model.LoadableUiState.Data -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(loadable.value) { goal ->
                            GoalCard(
                                goal = goal,
                                dateFormat = dateFormat,
                                onClick = { viewModel.selectGoal(goal) },
                                onQuickAdd = { viewModel.contributeToGoal(goal.id, 10.0) },
                                homeCurrency = state.homeCurrency
                            )
                        }
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
                    val createdMessage = context.getString(R.string.savings_goal_created_message)
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            message = createdMessage,
                            withDismissAction = true
                        )
                    }
                }
            )
        }

        recommendationToConfirm?.let { recommendation ->
            AlertDialog(
                onDismissRequest = { recommendationToConfirm = null },
                title = {
                    Text(
                        stringResource(
                            R.string.savings_contribute_dialog_title_format,
                            recommendation.goal.name
                        )
                    )
                },
                text = {
                    Text(
                        stringResource(
                            R.string.savings_contribute_dialog_message_format,
                            CurrencyFormatter.format(recommendation.recommendedAmount, state.homeCurrency)
                        )
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
                            val savedMessage = context.getString(
                                R.string.savings_saved_amount_message_format,
                                CurrencyFormatter.format(recommendation.recommendedAmount, state.homeCurrency)
                            )
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    message = savedMessage,
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
    streak: SavingsStreak?,
    homeCurrency: String
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
                text = stringResource(R.string.savings_label_total_saved, CurrencyFormatter.format(totalSaved, homeCurrency)),
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
    onSave: () -> Unit,
    homeCurrency: String
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
                    text = stringResource(R.string.savings_safe_to_save, CurrencyFormatter.format(rec.recommendedAmount, homeCurrency)),
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
            DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.getDefault()).format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
        } ?: ""
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.savings_add_goal_dialog_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = goalName,
                    onValueChange = { goalName = it },
                    label = { Text(stringResource(R.string.savings_goal_name_label)) },
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
                    label = { Text(stringResource(R.string.savings_target_amount_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    isError = targetAmountInput.isNotEmpty() && !isAmountValid
                )

                Text(
                    text = stringResource(R.string.savings_target_date_label_format, dateLabel),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showDatePicker = true }) {
                        Text(
                            if (targetDateMillis == null) {
                                stringResource(R.string.savings_set_date)
                            } else {
                                stringResource(R.string.savings_change_date)
                            }
                        )
                    }
                    if (targetDateMillis != null) {
                        TextButton(onClick = { targetDateMillis = null }) {
                            Text(stringResource(R.string.savings_action_clear_date))
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.savings_protection_level_label),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GoalProtectionLevel.entries.forEach { level ->
                        FilterChip(
                            selected = protectionLevel == level,
                            onClick = { protectionLevel = level },
                            label = {
                                Text(
                                    stringResource(
                                        when (level) {
                                            GoalProtectionLevel.STRICT -> R.string.savings_goal_protection_strict
                                            GoalProtectionLevel.WARNING -> R.string.savings_goal_protection_warning
                                            GoalProtectionLevel.TRACKING -> R.string.savings_goal_protection_tracking
                                        }
                                    )
                                )
                            }
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
    goal: SavingsGoal,
    dateFormat: DateTimeFormatter,
    onClick: () -> Unit,
    onQuickAdd: () -> Unit,
    homeCurrency: String
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
                        text = stringResource(R.string.savings_target_date, dateFormat.format(Instant.ofEpochMilli(goal.targetDate).atZone(ZoneId.systemDefault()))),
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
                    text = CurrencyFormatter.format(goal.currentAmount, homeCurrency),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(R.string.savings_of_target, CurrencyFormatter.format(goal.targetAmount, homeCurrency)),
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
                    text = stringResource(R.string.savings_lifestyle_alert_title),
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
                text = stringResource(
                    R.string.savings_lifestyle_boost_format,
                    String.format("%.1f", recommendation.suggestedMonthlyUplift)
                ),
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
                    Text(stringResource(R.string.savings_not_now))
                }
                
                Button(
                    onClick = onAccept
                ) {
                    Icon(androidx.compose.material.icons.Icons.Rounded.Savings, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.savings_boost_action))
                }
            }
        }
    }
}

@Composable
private fun SweepRecommendationCard(
    recommendation: SavingsSweepRecommendation,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
    homeCurrency: String
) {
    val dateFormat = remember { DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()) }
    val monthEndDate = remember(recommendation.monthEnd) { Instant.ofEpochMilli(recommendation.monthEnd).atZone(ZoneId.systemDefault()) }
    
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
                        text = stringResource(R.string.savings_end_of_month_sweep_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(
                            R.string.savings_month_ends_format,
                            dateFormat.format(monthEndDate.toLocalDate())
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Safe to save amount
            Text(
                text = stringResource(
                    R.string.savings_safe_to_save_sweep_format,
                    CurrencyFormatter.format(recommendation.safeSweepAmount, homeCurrency)
                ),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            // Underspend and buffer details
            Text(
                text = stringResource(
                    R.string.savings_underspend_buffer_format,
                    CurrencyFormatter.format(recommendation.totalUnderspend, homeCurrency),
                    CurrencyFormatter.format(recommendation.riskBuffer, homeCurrency)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Confidence indicator
            if (recommendation.confidence >= 0.6) {
                val confidenceText = when {
                    recommendation.confidence >= 0.8 -> stringResource(R.string.savings_confidence_high)
                    else -> stringResource(R.string.savings_confidence_good)
                }
                Text(
                    text = stringResource(R.string.savings_confidence_prefix_format, confidenceText),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            
            // Goal allocations
            if (recommendation.goalAllocations.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    text = stringResource(R.string.savings_suggested_allocation),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                recommendation.goalAllocations.take(3).forEach { allocation ->
                    GoalAllocationRow(allocation, homeCurrency = homeCurrency)
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
                    Text(stringResource(R.string.action_skip))
                }
                
                Button(
                    onClick = onAccept
                ) {
                    Icon(androidx.compose.material.icons.Icons.Rounded.Savings, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.savings_sweep_to_goals))
                }
            }
        }
    }
}

@Composable
private fun GoalAllocationRow(allocation: GoalAllocation, homeCurrency: String) {
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
                text = stringResource(
                    R.string.savings_progress_label_format,
                    String.format("%.0f", progressAfter)
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
        }
        
        Text(
            text = "+${CurrencyFormatter.format(allocation.suggestedAllocation, homeCurrency)}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
