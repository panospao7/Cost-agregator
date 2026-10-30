package com.yourname.expensetracker.ui.screens.privacysettings.applockpin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yourname.expensetracker.R
import com.yourname.expensetracker.ui.theme.SemanticColors

/**
 * App PIN settings screen (S27 b2, ux-flows 2.5a): status, Set up / Change / Remove entries and the in-app PIN steps.
 * Back during a step cancels the step (nothing applied); Back otherwise leaves the screen. "Back up now" only navigates
 * to Backup & Restore (AE-104). Fingerprint enable is not part of this screen yet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPinSettingsScreen(
    onNavigateBack: () -> Unit,
    onOpenBackupRestore: () -> Unit,
    viewModel: AppPinSettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val openBackup by rememberUpdatedState(onOpenBackupRestore)
    val messages = AppPinSnackbarTexts(
        set = stringResource(R.string.applock_pin_snackbar_set),
        changed = stringResource(R.string.applock_pin_snackbar_changed),
        removed = stringResource(R.string.applock_pin_snackbar_removed),
        removedLockOff = stringResource(R.string.privacy_app_lock_snackbar_off_pin_removed),
        turnOffIncomplete = stringResource(R.string.applock_pin_turn_off_incomplete),
        failed = stringResource(R.string.applock_pin_failed)
    )
    val currentMessages by rememberUpdatedState(messages)
    LaunchedEffect(viewModel) {
        viewModel.flowEvents.collect { event ->
            val text = currentMessages.textOf(event)
            if (text == null) openBackup() else snackbarHostState.showSnackbar(text)
        }
    }
    // The ViewModel is activity-scoped, so leaving the screen must zero held digits and close the step.
    DisposableEffect(viewModel) { onDispose { viewModel.onScreenLeft() } }
    val inStep = state.step != PinFlowStep.NONE
    val back: () -> Unit = { if (inStep) viewModel.cancel() else onNavigateBack() }
    BackHandler(onBack = back)
    Scaffold(
        containerColor = SemanticColors.BaseNavy,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.applock_pin_settings_title), color = SemanticColors.TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = back) {
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
        AppPinSettingsBody(state, viewModel, padding)
    }
}

/** Resolved snackbar texts; [textOf] is null for the navigation-only event (no text, no backup call: AE-104). */
private class AppPinSnackbarTexts(
    val set: String,
    val changed: String,
    val removed: String,
    val removedLockOff: String,
    val turnOffIncomplete: String,
    val failed: String
) {
    fun textOf(event: PinFlowEvent): String? = when (event) {
        PinFlowEvent.PIN_SET -> set
        PinFlowEvent.PIN_CHANGED -> changed
        PinFlowEvent.PIN_REMOVED -> removed
        PinFlowEvent.PIN_REMOVED_LOCK_OFF -> removedLockOff
        PinFlowEvent.TURN_OFF_INCOMPLETE -> turnOffIncomplete
        PinFlowEvent.OPERATION_FAILED -> failed
        PinFlowEvent.OPEN_BACKUP_RESTORE -> null
    }
}

@Composable
private fun AppPinSettingsBody(state: AppPinSettingsUiState, viewModel: AppPinSettingsViewModel, padding: PaddingValues) {
    val actions = remember(viewModel) {
        PinSetupFlowActions(
            onAcknowledge = viewModel::acknowledge,
            onContinue = viewModel::continueSetup,
            onBackUpNow = viewModel::onBackUpNow,
            onSubmitEntry = viewModel::submitEntry,
            onConfirmRemove = viewModel::confirmRemove,
            onCancel = viewModel::cancel,
            onConfirmTurnOff = viewModel::confirmTurnOff
        )
    }
    // The remove and turn-off dialogs are drawn over the list; every other step replaces it.
    if (state.step != PinFlowStep.NONE &&
        state.step != PinFlowStep.REMOVE_CONFIRM &&
        state.step != PinFlowStep.TURN_OFF_CONFIRM
    ) {
        PinSetupFlow(state, actions, Modifier.padding(padding))
        return
    }
    val usable = state.availability == AppPinAvailability.AVAILABLE && !state.busy
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(if (state.pinSet) R.string.applock_pin_status_on else R.string.applock_pin_status_off),
            style = MaterialTheme.typography.bodyLarge,
            color = SemanticColors.TextPrimary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        if (state.pinSet) {
            ActionRow(stringResource(R.string.applock_pin_action_change), usable, viewModel::startChange)
            ActionRow(stringResource(R.string.applock_pin_action_remove), usable, viewModel::startRemove)
            ActionRow(stringResource(R.string.applock_pin_action_turn_off), usable, viewModel::startTurnOff)
        } else {
            ActionRow(stringResource(R.string.applock_pin_action_setup), usable, viewModel::startSetup)
        }
        if (state.availability == AppPinAvailability.PAUSED) {
            Text(
                text = stringResource(R.string.applock_pin_row_unavailable_paused),
                style = MaterialTheme.typography.bodySmall,
                color = SemanticColors.TextSecondary
            )
        }
        if (state.pinSet) {
            Text(
                text = stringResource(R.string.applock_pin_settings_forgot_note),
                style = MaterialTheme.typography.bodySmall,
                color = SemanticColors.TextSecondary,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
    PinSetupFlow(state, actions)
}

@Composable
private fun ActionRow(label: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = if (enabled) SemanticColors.TextPrimary else SemanticColors.TextSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp)
    )
}
