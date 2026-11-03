package com.yourname.expensetracker.ui.screens.savings

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.domain.savings.SavingsStreak
import androidx.compose.ui.res.stringResource
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.model.GoalProtectionLevel
import com.yourname.expensetracker.domain.model.SavingsGoal
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import com.yourname.expensetracker.ui.components.asString
import com.yourname.expensetracker.domain.usecase.savings.GoalAllocation
import com.yourname.expensetracker.domain.usecase.savings.SavingsSweepRecommendation
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
    var showAddGoalDialog by remember { mutableStateOf(false) }
    var recommendationToConfirm by remember { mutableStateOf<SmartRecommendation?>(null) }

    // True once an event reports that the last delete attempt did not remove the goal.
    var deleteRetainedOutcome by remember { mutableStateOf(false) }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            if (event is SavingsGoalsEvent.DeleteRejected || event is SavingsGoalsEvent.WriteBlocked) {
                deleteRetainedOutcome = true
            }
            val message = when (event) {
                SavingsGoalsEvent.GoalCreated -> context.getString(R.string.savings_goal_created_message)
                is SavingsGoalsEvent.WriteBlocked -> event.message.asString(context)
                SavingsGoalsEvent.GoalDeleted -> context.getString(R.string.savings_goal_deleted_message)
                is SavingsGoalsEvent.DeleteRejected -> context.getString(
                    when (event.reason) {
                        DeleteRejectReason.NOT_EMPTY -> R.string.savings_goal_delete_not_empty_message
                        DeleteRejectReason.HISTORY_UNAVAILABLE -> R.string.savings_goal_delete_history_unavailable_message
                        DeleteRejectReason.GENERIC -> R.string.error_delete_failed
                    }
                )
            }
            snackbarHostState.showSnackbar(message = message, withDismissAction = true)
        }
    }

    // INV-11 (screen-local): drop the pending recommendation confirm once its goal has left the list.
    LaunchedEffect(state.goals, recommendationToConfirm) {
        val pending = recommendationToConfirm
        if (pending != null && state.goals.none { it.id == pending.goal.id }) {
            recommendationToConfirm = null
        }
    }

    // D1/D3 dismiss silently when their goal disappears (treated as already absent; no snackbar here).
    val deleteFlow = state.deleteFlow
    LaunchedEffect(deleteFlow, state.goals) {
        val flowGoalId = when (deleteFlow) {
            is DeleteFlowState.Confirm -> deleteFlow.goalId
            is DeleteFlowState.NotAllowed -> deleteFlow.goalId
            else -> null
        }
        if (flowGoalId != null && state.goals.none { it.id == flowGoalId }) {
            viewModel.dismissDeleteFlow()
        }
    }

    // Focus return (ux-flows section 6): after the delete flow ends, focus goes back to the origin
    // card's overflow button, or to the "Your Goals" heading when that card is gone.
    val overflowFocusRequesters = remember { mutableMapOf<Long, FocusRequester>() }
    val goalsHeadingFocusRequester = remember { FocusRequester() }
    var deleteOriginGoalId by remember { mutableStateOf<Long?>(null) }
    // After a delete attempt the flow returns to Idle before state.goals drops the goal, so the
    // origin is kept until the goal leaves the list (heading) or the attempt is reported as not
    // deleting it (rejected/blocked: overflow).
    var originWasDeleting by remember { mutableStateOf(false) }
    LaunchedEffect(deleteFlow, state.goals, deleteRetainedOutcome) {
        when (deleteFlow) {
            is DeleteFlowState.Checking -> {
                deleteOriginGoalId = deleteFlow.goalId
                originWasDeleting = false
            }
            is DeleteFlowState.Confirm -> {
                deleteOriginGoalId = deleteFlow.goalId
                originWasDeleting = false
            }
            is DeleteFlowState.NotAllowed -> {
                deleteOriginGoalId = deleteFlow.goalId
                originWasDeleting = false
            }
            is DeleteFlowState.Deleting -> {
                deleteOriginGoalId = deleteFlow.goalId
                originWasDeleting = true
                deleteRetainedOutcome = false
            }
            DeleteFlowState.Idle -> {
                val origin = deleteOriginGoalId
                if (origin != null) {
                    val originPresent = state.goals.any { it.id == origin }
                    if (originWasDeleting && originPresent && !deleteRetainedOutcome) {
                        // Delete outcome not yet visible in the list: keep the origin and wait.
                        return@LaunchedEffect
                    }
                    deleteOriginGoalId = null
                    originWasDeleting = false
                    val originRequester = if (originPresent) {
                        overflowFocusRequesters[origin]
                    } else {
                        null
                    }
                    // requestFocus is non-suspending; only the not-attached failure is tolerated.
                    var originFocused = false
                    if (originRequester != null) {
                        try {
                            originRequester.requestFocus()
                            originFocused = true
                        } catch (_: IllegalStateException) {
                            // Origin overflow not attached (scrolled out): fall back to the heading.
                        }
                    }
                    if (!originFocused) {
                        try {
                            goalsHeadingFocusRequester.requestFocus()
                        } catch (_: IllegalStateException) {
                            // Heading not attached either: focus is best effort.
                        }
                    }
                }
            }
        }
    }

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

            if (state.contributionHistoryWriteFailure != null) {
                Text(
                    text = stringResource(R.string.savings_history_write_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (state.smartUnavailableReason != null || state.sweepUnavailableReason != null) {
                Text(
                    text = stringResource(R.string.savings_recommendation_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Monthly Savings Sweep Recommendation
            val sweepRec = state.sweepRecommendation
            if (sweepRec != null) {
            SweepRecommendationCard(
                recommendation = sweepRec,
                onAccept = { viewModel.acceptSweepRecommendation() },
                onDismiss = { viewModel.dismissSweepRecommendation() },
                homeCurrency = sweepRec.currency
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
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .focusRequester(goalsHeadingFocusRequester)
                    .focusable()
                    .semantics { heading() }
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
                        items(loadable.value, key = { it.id }) { goal ->
                            val deleteCheckInProgress =
                                deleteFlow is DeleteFlowState.Checking && deleteFlow.goalId == goal.id
                            GoalCard(
                                goal = goal,
                                dateFormat = dateFormat,
                                onClick = { viewModel.selectGoal(goal) },
                                canContribute = state.contributionHistoryWriteFailure == null &&
                                    !deleteCheckInProgress,
                                onQuickAdd = { viewModel.quickAdd(goal.id) },
                                canRequestDelete = deleteFlow == DeleteFlowState.Idle,
                                onDelete = { viewModel.requestDeleteGoal(goal.id) },
                                overflowFocusRequester = overflowFocusRequesters.getOrPut(goal.id) { FocusRequester() }
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
                    // R18b: the "created" snackbar is shown on SavingsGoalsEvent.GoalCreated,
                    // i.e. only after the write succeeded (see LaunchedEffect above).
                    showAddGoalDialog = false
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
                            CurrencyFormatter.formatMoney(recommendation.recommendedAmount, recommendation.currency)
                        )
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.acceptSmartRecommendation(recommendation)
                            recommendationToConfirm = null
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

        // D1 (Confirm) and D2 (Deleting) share one dialog so the transition does not recompose it away.
        val confirmGoalId = when (deleteFlow) {
            is DeleteFlowState.Confirm -> deleteFlow.goalId
            is DeleteFlowState.Deleting -> deleteFlow.goalId
            else -> null
        }
        val confirmGoal = confirmGoalId?.let { id -> state.goals.firstOrNull { it.id == id } }
        if (confirmGoal != null) {
            DeleteGoalDialog(
                goalName = confirmGoal.name,
                isDeleting = deleteFlow is DeleteFlowState.Deleting,
                onConfirm = { viewModel.deleteGoal(confirmGoal) },
                onDismiss = { viewModel.dismissDeleteFlow() }
            )
        }

        // D3 (NotAllowed): neutral explanation, no destructive action.
        if (deleteFlow is DeleteFlowState.NotAllowed && state.goals.any { it.id == deleteFlow.goalId }) {
            AlertDialog(
                onDismissRequest = { viewModel.dismissDeleteFlow() },
                title = { Text(stringResource(R.string.savings_goal_delete_blocked_title)) },
                text = {
                    Text(
                        text = stringResource(R.string.savings_goal_delete_blocked_message),
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    )
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.dismissDeleteFlow() }) {
                        Text(stringResource(R.string.action_got_it))
                    }
                }
            )
        }
    }
}

@Composable
private fun DeleteGoalDialog(
    goalName: String,
    isDeleting: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val deletingDescription = stringResource(R.string.savings_goal_deleting_cd)
    AlertDialog(
        // D2: outside taps and Back are ignored while the delete runs.
        onDismissRequest = { if (!isDeleting) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !isDeleting,
            dismissOnClickOutside = !isDeleting
        ),
        title = { Text(stringResource(R.string.savings_goal_delete_confirm_title)) },
        text = {
            Text(
                text = stringResource(R.string.savings_goal_delete_confirm_message_format, goalName),
                modifier = Modifier.verticalScroll(rememberScrollState())
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isDeleting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                if (isDeleting) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(18.dp)
                            .semantics { contentDescription = deletingDescription },
                        strokeWidth = 2.dp,
                        color = LocalContentColor.current
                    )
                } else {
                    Text(stringResource(R.string.action_delete))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isDeleting) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun GamificationHeader(
    level: Int?,
    title: String,
    totalSaved: Double?,
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
                        text = level?.let { stringResource(R.string.savings_level_format, it) } ?: "Unavailable",
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
                text = stringResource(R.string.savings_label_total_saved, totalSaved?.let { CurrencyFormatter.formatMoney(it, homeCurrency) } ?: "Unavailable"),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            
            if (streak?.isAvailable == true) {
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
                    text = stringResource(R.string.savings_safe_to_save, CurrencyFormatter.formatMoney(rec.recommendedAmount, rec.currency)),
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

    val parsedAmount = com.yourname.expensetracker.ui.util.AmountInputSanitizer.parseOrNull(targetAmountInput)
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
                        // R16: locale-aware; keeps the comma decimal separator instead of rejecting it.
                        targetAmountInput = com.yourname.expensetracker.ui.util.AmountInputSanitizer.sanitize(value)
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
            // R20c: picker values are UTC-midnight millis; convert to/from the local calendar day.
            initialSelectedDateMillis = targetDateMillis?.let {
                com.yourname.expensetracker.ui.util.UiTimeUtils.instantToPickerMillis(it)
            }
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let {
                            targetDateMillis =
                                com.yourname.expensetracker.ui.util.UiTimeUtils.pickerMillisToLocalStartOfDay(it)
                        }
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
    canContribute: Boolean,
    onQuickAdd: () -> Unit,
    canRequestDelete: Boolean,
    onDelete: () -> Unit,
    overflowFocusRequester: FocusRequester
) {
    if (com.yourname.expensetracker.domain.savings.SavingsGoalCurrencyPolicy.knownCurrencyOrNull(goal) == null) {
        Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(goal.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.savings_goal_currency_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                GoalOverflowMenu(
                    goalName = goal.name,
                    enabled = canRequestDelete,
                    onDelete = onDelete,
                    focusRequester = overflowFocusRequester
                )
            }
        }
        return
    }
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
                    // Same helper as the ViewModel write; no amount left to add (dust, unknown
                    // fraction digits) hides the action instead of leaving a silent no-op.
                    val quickAddAmount = SavingsQuickAdd.amountOrNull(goal)
                    if (quickAddAmount != null) {
                        TextButton(onClick = onQuickAdd, enabled = canContribute) {
                            Text(stringResource(R.string.savings_action_add_amount, quickAddAmount.toPlainString()))
                        }
                    }
                }

                GoalOverflowMenu(
                    goalName = goal.name,
                    enabled = canRequestDelete,
                    onDelete = onDelete,
                    focusRequester = overflowFocusRequester
                )
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
                    text = CurrencyFormatter.formatMoney(goal.currentAmount, goal.currency),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(R.string.savings_of_target, CurrencyFormatter.formatMoney(goal.targetAmount, goal.currency)),
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

/**
 * Per-card overflow: a 48dp MoreVert button with a single "Delete" item. Eligibility is not decided
 * here (INV-16); the item only asks the ViewModel to start the delete flow.
 */
@Composable
private fun GoalOverflowMenu(
    goalName: String,
    enabled: Boolean,
    onDelete: () -> Unit,
    focusRequester: FocusRequester
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier
                .size(48.dp)
                .focusRequester(focusRequester)
        ) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.savings_goal_more_actions_cd_format, goalName)
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_delete)) },
                onClick = {
                    expanded = false
                    onDelete()
                }
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
                    CurrencyFormatter.formatMoney(recommendation.safeSweepAmount, homeCurrency)
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
                    CurrencyFormatter.formatMoney(recommendation.totalUnderspend, homeCurrency),
                    CurrencyFormatter.formatMoney(recommendation.riskBuffer, homeCurrency)
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
            text = "+${CurrencyFormatter.formatMoney(allocation.suggestedAllocation, homeCurrency)}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
