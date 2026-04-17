package com.yourname.expensetracker.ui.screens.challenge

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.challenge.ChallengeType
import com.yourname.expensetracker.domain.challenge.NoSpendStatus
import com.yourname.expensetracker.domain.challenge.SpendingChallenge
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import com.yourname.expensetracker.ui.components.common.EmptyStateType
import com.yourname.expensetracker.ui.components.common.EnhancedEmptyState
import com.yourname.expensetracker.ui.components.emptystate.ContextualActionRegistry
import com.yourname.expensetracker.ui.components.emptystate.EmptyStateAction
import com.yourname.expensetracker.ui.components.emptystate.EmptyStateActionType
import com.yourname.expensetracker.ui.components.emptystate.EmptyStateScreenKeys

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpendingChallengesScreen(
    initialShowCreateDialog: Boolean = false,
    onNavigateBack: () -> Unit,
    onCreateChallenge: () -> Unit,
    actionRegistry: ContextualActionRegistry,
    viewModel: SpendingChallengesViewModel = hiltViewModel(),
) {
    val noSpendStatus by viewModel.noSpendStatus.collectAsState()
    val activeChallenges by viewModel.activeChallenges.collectAsState()
    val challengesAvailability by viewModel.challengesAvailability.collectAsState()
    val createChallengeUiState by viewModel.createChallengeUiState.collectAsState()
    val completedActionKeys by actionRegistry.completedActions.collectAsState()
    var showCreateDialog by rememberSaveable(initialShowCreateDialog) {
        mutableStateOf(initialShowCreateDialog)
    }

    LaunchedEffect(initialShowCreateDialog) {
        showCreateDialog = initialShowCreateDialog
    }

    LaunchedEffect(viewModel) {
        viewModel.createChallengeEvents.collect { event ->
            when (event) {
                SpendingChallengesViewModel.CreateChallengeEvent.Created -> {
                    showCreateDialog = false
                    onNavigateBack()
                }
            }
        }
    }
    
    // Get contextual actions for empty state
    val emptyStateActions by remember(completedActionKeys) {
        derivedStateOf {
            actionRegistry.getActions(EmptyStateScreenKeys.CHALLENGES)
        }
    }

    if (showCreateDialog) {
        CreateChallengeDialog(
            categories = createChallengeUiState.categories,
            isSaving = createChallengeUiState.isCreating,
            errorMessage = createChallengeUiState.errorMessage,
            onDismiss = {
                showCreateDialog = false
                viewModel.clearCreateChallengeError()
                onNavigateBack()
            },
            onConfirm = { name, type, durationDays, targetAmount, categoryId ->
                viewModel.createChallenge(
                    name = name,
                    type = type,
                    durationDays = durationDays,
                    targetAmount = targetAmount,
                    categoryId = categoryId
                )
            }
        )
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.challenges_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = onCreateChallenge) {
                        Icon(Icons.Default.Add, stringResource(R.string.challenges_create_cd))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                NoSpendStreakCard(noSpendStatus)
            }
            
            item {
                Text(
                    text = stringResource(R.string.challenges_active_section),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            
            // Show active challenges or empty state
            if (!challengesAvailability.hasCanonicalSource) {
                item {
                    ChallengesUnavailableCard(
                        reason = challengesAvailability.unavailableReason,
                        onCreateChallenge = onCreateChallenge,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else if (activeChallenges.isEmpty()) {
                item {
                    // Enhanced empty state with contextual actions
                    EnhancedEmptyState(
                        type = EmptyStateType.GENERIC,
                        title = stringResource(R.string.challenges_empty_title),
                        message = stringResource(R.string.challenges_empty_message),
                        actions = emptyStateActions,
                        onActionClick = { action ->
                            when (val actionType = action.action) {
                                is EmptyStateActionType.NavigateToDestination -> {
                                    // Handle navigation if needed
                                }
                                is EmptyStateActionType.ExecuteAction -> actionType.action.invoke()
                                is EmptyStateActionType.OpenFeature -> {
                                    when (actionType.feature) {
                                        "create_challenge" -> onCreateChallenge()
                                        "no_spend_streak" -> {
                                            // Handle no spend streak
                                        }
                                    }
                                }
                            }
                        },
                        onDismissAction = { actionId ->
                            actionRegistry.markCompleted(EmptyStateScreenKeys.CHALLENGES, actionId)
                        },
                        actionLabel = stringResource(R.string.challenges_action_start),
                        actionIcon = Icons.Default.Add,
                        onPrimaryClick = onCreateChallenge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                    )
                }
            } else {
                items(activeChallenges, key = { it.id }) { challenge ->
                    ActiveChallengeCard(
                        challenge = challenge,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun ChallengesUnavailableCard(
    reason: String?,
    onCreateChallenge: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Active challenges unavailable",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = reason ?: "This build does not have a persisted active-challenges source yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedButton(onClick = onCreateChallenge) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(R.string.challenges_create_cd))
            }
        }
    }
}

@Composable
private fun ActiveChallengeCard(
    challenge: SpendingChallenge,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = challenge.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = challenge.type.name.replace('_', ' '),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            LinearProgressIndicator(
                progress = { ((challenge.progress / 100.0).coerceIn(0.0, 1.0)).toFloat() },
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = "${challenge.progress.toInt()}% through challenge window",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            challenge.targetAmount?.let { targetAmount ->
                Text(
                    text = when (challenge.type) {
                        com.yourname.expensetracker.domain.challenge.ChallengeType.REDUCE_SPENDING -> {
                            val baseline = challenge.baselineAmount?.let(CurrencyFormatter::format)
                            val reduction = CurrencyFormatter.format(targetAmount)
                            if (baseline != null) {
                                "Baseline $baseline, reduce by $reduction"
                            } else {
                                "Reduce spend by $reduction"
                            }
                        }

                        else -> "Target: ${CurrencyFormatter.format(targetAmount)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun NoSpendStreakCard(status: NoSpendStatus?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (status?.hasNoSpendToday == true)
                MaterialTheme.colorScheme.tertiaryContainer
            else
                MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.LocalFireDepartment,
                    contentDescription = null,
                    tint = if (status?.hasNoSpendToday == true)
                        MaterialTheme.colorScheme.tertiary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(32.dp)
                )
                
                val streakDays = status?.currentStreakDays ?: 0
                val suffix = if (streakDays == 1) stringResource(R.string.challenges_streak_suffix_single) else stringResource(R.string.challenges_streak_suffix_plural)
                Text(
                    text = stringResource(R.string.challenges_streak_format, streakDays, suffix),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (status?.hasNoSpendToday == true)
                        MaterialTheme.colorScheme.tertiary
                    else
                        MaterialTheme.colorScheme.onSurface
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            if (status?.hasNoSpendToday == true) {
                Text(
                    text = stringResource(R.string.challenges_no_spend_today),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.tertiary
                )
                
                status.savedToday?.let { saved ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.challenges_saved_today_format, CurrencyFormatter.format(saved)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
                
                if (status.achievementUnlocked) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.EmojiEvents,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        Text(
                            text = stringResource(R.string.challenges_7day_achievement),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            } else {
                Text(
                    text = stringResource(R.string.challenges_start_streak),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateChallengeDialog(
    categories: List<com.yourname.expensetracker.data.database.entity.Category>,
    isSaving: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirm: (name: String, type: ChallengeType, durationDays: Int, targetAmount: Double?, categoryId: Long?) -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    var selectedType by rememberSaveable { mutableStateOf(ChallengeType.NO_SPEND) }
    var durationDaysInput by rememberSaveable { mutableStateOf("7") }
    var targetAmountInput by rememberSaveable { mutableStateOf("") }
    var selectedCategoryId by rememberSaveable { mutableStateOf<Long?>(null) }

    val parsedDuration = durationDaysInput.toIntOrNull()
    val parsedTargetAmount = targetAmountInput.toDoubleOrNull()
    val requiresTarget = selectedType == ChallengeType.BUDGET_LIMIT ||
        selectedType == ChallengeType.CATEGORY_SPECIFIC ||
        selectedType == ChallengeType.REDUCE_SPENDING
    val requiresCategory = selectedType == ChallengeType.CATEGORY_SPECIFIC
    val isValid = name.isNotBlank() &&
        (parsedDuration ?: 0) > 0 &&
        (!requiresTarget || (parsedTargetAmount ?: 0.0) > 0.0) &&
        (!requiresCategory || selectedCategoryId != null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.challenges_create_cd)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Challenge name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "Challenge type",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ChallengeType.entries.forEach { type ->
                        FilterChip(
                            selected = selectedType == type,
                            onClick = {
                                selectedType = type
                                if (type != ChallengeType.CATEGORY_SPECIFIC) {
                                    selectedCategoryId = null
                                }
                                if (type == ChallengeType.NO_SPEND) {
                                    targetAmountInput = ""
                                }
                            },
                            label = { Text(type.toDisplayLabel()) }
                        )
                    }
                }

                OutlinedTextField(
                    value = durationDaysInput,
                    onValueChange = { value ->
                        if (value.isEmpty() || value.all(Char::isDigit)) {
                            durationDaysInput = value
                        }
                    },
                    label = { Text("Duration (days)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    isError = durationDaysInput.isNotEmpty() && (parsedDuration ?: 0) <= 0
                )

                if (requiresTarget) {
                    OutlinedTextField(
                        value = targetAmountInput,
                        onValueChange = { value ->
                            if (value.isEmpty() || value.matches(Regex("^\\d*\\.?\\d{0,2}$"))) {
                                targetAmountInput = value
                            }
                        },
                        label = {
                            Text(
                                when (selectedType) {
                                    ChallengeType.REDUCE_SPENDING -> "Reduce by amount"
                                    else -> "Target amount"
                                }
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                        isError = targetAmountInput.isNotEmpty() && (parsedTargetAmount ?: 0.0) <= 0.0
                    )
                }

                if (requiresCategory) {
                    Text(
                        text = "Category",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        categories.forEach { category ->
                            FilterChip(
                                selected = selectedCategoryId == category.id,
                                onClick = { selectedCategoryId = category.id },
                                label = { Text("${category.icon} ${category.name}") }
                            )
                        }
                    }
                }

                if (!errorMessage.isNullOrBlank()) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        name.trim(),
                        selectedType,
                        parsedDuration ?: 0,
                        parsedTargetAmount?.takeIf { requiresTarget },
                        selectedCategoryId
                    )
                },
                enabled = isValid && !isSaving
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(text = stringResource(R.string.action_create))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) {
                Text(text = stringResource(R.string.action_cancel))
            }
        }
    )
}

private fun ChallengeType.toDisplayLabel(): String = when (this) {
    ChallengeType.NO_SPEND -> "No Spend"
    ChallengeType.BUDGET_LIMIT -> "Budget Limit"
    ChallengeType.REDUCE_SPENDING -> "Reduce Spending"
    ChallengeType.CATEGORY_SPECIFIC -> "Category Specific"
}
