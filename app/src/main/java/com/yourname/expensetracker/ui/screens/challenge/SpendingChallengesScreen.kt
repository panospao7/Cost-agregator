package com.yourname.expensetracker.ui.screens.challenge

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.challenge.NoSpendStatus
import com.yourname.expensetracker.domain.challenge.SpendingChallenge
import com.yourname.expensetracker.ui.components.common.EmptyStateType
import com.yourname.expensetracker.ui.components.common.EnhancedEmptyState
import com.yourname.expensetracker.ui.components.emptystate.ContextualActionRegistry
import com.yourname.expensetracker.ui.components.emptystate.EmptyStateAction
import com.yourname.expensetracker.ui.components.emptystate.EmptyStateActionType
import com.yourname.expensetracker.ui.components.emptystate.EmptyStateScreenKeys
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpendingChallengesScreen(
    onNavigateBack: () -> Unit,
    onCreateChallenge: () -> Unit,
    actionRegistry: ContextualActionRegistry,
    viewModel: SpendingChallengesViewModel = hiltViewModel(),
) {
    val noSpendStatus by viewModel.noSpendStatus.collectAsState()
    val activeChallenges by viewModel.activeChallenges.collectAsState()
    val challengesAvailability by viewModel.challengesAvailability.collectAsState()
    val completedActionKeys by actionRegistry.completedActions.collectAsState()
    
    // Get contextual actions for empty state
    val emptyStateActions by remember(completedActionKeys) {
        derivedStateOf {
            actionRegistry.getActions(EmptyStateScreenKeys.CHALLENGES)
        }
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
    val currencyFormat = NumberFormat.getCurrencyInstance(Locale.getDefault())
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
                            val baseline = challenge.baselineAmount?.let(currencyFormat::format)
                            val reduction = currencyFormat.format(targetAmount)
                            if (baseline != null) {
                                "Baseline $baseline, reduce by $reduction"
                            } else {
                                "Reduce spend by $reduction"
                            }
                        }

                        else -> "Target: ${currencyFormat.format(targetAmount)}"
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
    val currencyFormat = NumberFormat.getCurrencyInstance(Locale.getDefault())
    
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
                        text = stringResource(R.string.challenges_saved_today_format, currencyFormat.format(saved)),
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
