package com.yourname.expensetracker.ui.screens.captureskip

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yourname.expensetracker.R
import com.yourname.expensetracker.ui.components.asString
import com.yourname.expensetracker.ui.theme.SemanticColors

/**
 * "Apps to skip" (P-01-3): lists the apps whose notifications are never captured (with a way to
 * capture them again) and the apps already seen in captured notifications that are not skipped yet
 * (with a way to skip them). Labels come from the OS where it lets us read them, else the package name.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsToSkipScreen(
    onNavigateBack: () -> Unit,
    viewModel: AppsToSkipViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val currentContext by rememberUpdatedState(context)
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            snackbarHostState.showSnackbar(message.asString(currentContext))
        }
    }
    state.pendingBlockConfirm?.let { pending ->
        AlertDialog(
            onDismissRequest = viewModel::dismissPendingBlock,
            title = { Text(stringResource(R.string.capture_skip_confirm_title)) },
            text = { Text(stringResource(R.string.capture_skip_confirm_message_format, pending.label)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmPendingBlock) {
                    Text(stringResource(R.string.capture_skip_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissPendingBlock) {
                    Text(stringResource(R.string.capture_skip_confirm_cancel))
                }
            }
        )
    }
    Scaffold(
        containerColor = SemanticColors.BaseNavy,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.capture_skip_title), color = SemanticColors.TextPrimary)
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                            tint = SemanticColors.TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SemanticColors.BaseNavy)
            )
        }
    ) { padding ->
        if (state.isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(padding))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item {
                    Text(
                        text = stringResource(R.string.capture_skip_intro),
                        color = SemanticColors.TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                if (state.loadFailed) {
                    item {
                        Text(
                            text = stringResource(R.string.capture_skip_load_failed),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else {
                    item { SectionTitle(stringResource(R.string.capture_skip_section_skipped)) }
                    if (state.blocked.isEmpty()) {
                        item { EmptyHint(stringResource(R.string.capture_skip_empty_skipped)) }
                    } else {
                        items(state.blocked, key = { "blocked:" + it.packageName }) { app ->
                            AppRow(
                                app = app,
                                actionLabel = stringResource(R.string.capture_skip_action_resume),
                                actionDescription = stringResource(R.string.capture_skip_cd_resume_format, app.label),
                                enabled = app.packageName !in state.inFlight,
                                onAction = { viewModel.unblock(app.packageName) }
                            )
                        }
                    }
                    item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
                    item { SectionTitle(stringResource(R.string.capture_skip_section_candidates)) }
                    if (state.candidates.isEmpty()) {
                        item { EmptyHint(stringResource(R.string.capture_skip_empty_candidates)) }
                    } else {
                        items(state.candidates, key = { "candidate:" + it.packageName }) { app ->
                            AppRow(
                                app = app,
                                actionLabel = stringResource(R.string.capture_skip_action_skip),
                                actionDescription = stringResource(R.string.capture_skip_cd_skip_format, app.label),
                                enabled = app.packageName !in state.inFlight,
                                onAction = { viewModel.block(app.packageName) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = SemanticColors.TextPrimary,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        color = SemanticColors.TextMuted,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun AppRow(
    app: SkipAppItem,
    actionLabel: String,
    actionDescription: String,
    enabled: Boolean,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = app.label,
                color = SemanticColors.TextPrimary,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (app.label != app.packageName) {
                Text(
                    text = app.packageName,
                    color = SemanticColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        TextButton(
            onClick = onAction,
            enabled = enabled,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics { contentDescription = actionDescription }
        ) {
            Text(actionLabel)
        }
    }
}
