package com.yourname.expensetracker.ui.screens.privacysettings.applockpin

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yourname.expensetracker.R
import com.yourname.expensetracker.ui.applock.pin.PinDotRow
import com.yourname.expensetracker.ui.applock.pin.PinPad
import com.yourname.expensetracker.ui.applock.pin.coarseRemainingSeconds
import com.yourname.expensetracker.ui.applock.pin.pinDurationText
import com.yourname.expensetracker.ui.applock.pin.rememberPinEntryBuffer
import kotlinx.coroutines.delay

/** Stable test tags for the PIN setup, change and remove steps. Tags carry no user data. */
object PinSetupFlowTags {
    const val ACK_ROW = "applock_pin_setup_ack_row"
    const val CONTINUE = "applock_pin_setup_continue"
    const val BACKUP_NOW = "applock_pin_setup_backup_now"
    const val CANCEL = "applock_pin_flow_cancel"
    const val MESSAGE = "applock_pin_flow_message"
    const val REMOVE_DIALOG = "applock_pin_remove_dialog"
    const val REMOVE_CONFIRM = "applock_pin_remove_confirm"
    const val TURN_OFF_DIALOG = "applock_pin_turn_off_dialog"
    const val TURN_OFF_CONFIRM = "applock_pin_turn_off_confirm"
}

/** What the PIN steps can ask of their host. The host wires them to [AppPinSettingsViewModel]. */
class PinSetupFlowActions(
    val onAcknowledge: (Boolean) -> Unit,
    val onContinue: () -> Unit,
    val onBackUpNow: () -> Unit,
    val onSubmitEntry: (CharArray) -> Unit,
    val onConfirmRemove: () -> Unit,
    val onCancel: () -> Unit,
    val onConfirmTurnOff: () -> Unit = {}
)

/**
 * Renders the current PIN step (ux-flows 2.5b-2.5d): the acknowledgement step, the entry steps (dots and pad) and the
 * remove dialog. It is an in-app screen, not a system prompt (INV-54 [2]). The pad buffer is `remember`-only
 * ([rememberPinEntryBuffer]), so after a recreate or process death the step starts empty (AE-119, INV-38). Nothing here
 * logs or saves a digit; the copy of the digits handed to [PinSetupFlowActions.onSubmitEntry] is owned by the receiver.
 */
@Composable
fun PinSetupFlow(state: AppPinSettingsUiState, actions: PinSetupFlowActions, modifier: Modifier = Modifier) {
    when (state.step) {
        PinFlowStep.NONE -> Unit
        PinFlowStep.SETUP_ACK -> SetupAckStep(state, actions, modifier)
        PinFlowStep.REMOVE_CONFIRM -> RemoveDialog(state, actions)
        PinFlowStep.TURN_OFF_CONFIRM -> TurnOffDialog(state, actions)
        else -> EntryStep(state, actions, modifier)
    }
}

@Composable
private fun SetupAckStep(state: AppPinSettingsUiState, actions: PinSetupFlowActions, modifier: Modifier) {
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(stringResource(R.string.applock_pin_setup_intro), style = MaterialTheme.typography.bodyLarge)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.Warning, contentDescription = null)
                Column {
                    Text(stringResource(R.string.applock_pin_setup_warning), style = MaterialTheme.typography.bodyMedium)
                    // AE-104: navigation only. It never starts a backup.
                    TextButton(onClick = actions.onBackUpNow, modifier = Modifier.testTag(PinSetupFlowTags.BACKUP_NOW)) {
                        Text(stringResource(R.string.applock_pin_setup_backup_now))
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(PinSetupFlowTags.ACK_ROW).toggleable(
                value = state.ackChecked,
                enabled = !state.busy,
                role = Role.Checkbox,
                onValueChange = actions.onAcknowledge
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Checkbox(checked = state.ackChecked, onCheckedChange = null)
            Text(stringResource(R.string.applock_pin_setup_ack), style = MaterialTheme.typography.bodyMedium)
        }
        if (state.message == PinFlowMessage.FAILED) {
            Text(
                stringResource(R.string.applock_pin_failed),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(PinSetupFlowTags.MESSAGE)
            )
        }
        Button(
            onClick = actions.onContinue,
            enabled = state.ackChecked && !state.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(PinSetupFlowTags.CONTINUE)
        ) { Text(stringResource(R.string.applock_pin_setup_continue)) }
        TextButton(
            onClick = actions.onCancel,
            enabled = !state.busy,
            modifier = Modifier.testTag(PinSetupFlowTags.CANCEL)
        ) { Text(stringResource(R.string.action_cancel)) }
    }
}

@Composable
private fun EntryStep(state: AppPinSettingsUiState, actions: PinSetupFlowActions, modifier: Modifier) {
    key(state.step) { // a new step gets a new, empty buffer (the old one is zero-filled on dispose)
        val buffer = rememberPinEntryBuffer()
        val remaining = rememberDelayRemaining(state)
        Column(
            modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                stringResource(entryTitle(state.step)),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            PinDotRow(buffer.length)
            flowMessage(state, remaining)?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag(PinSetupFlowTags.MESSAGE)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
            PinPad(
                buffer = buffer,
                enabled = !state.busy && remaining <= 0L,
                onConfirm = actions.onSubmitEntry,
                confirmText = stringResource(entryConfirmLabel(state.step))
            )
            TextButton(
                onClick = actions.onCancel,
                enabled = !state.busy,
                modifier = Modifier.testTag(PinSetupFlowTags.CANCEL)
            ) { Text(stringResource(R.string.action_cancel)) }
        }
    }
}

private fun entryTitle(step: PinFlowStep): Int = when (step) {
    PinFlowStep.CHANGE_CURRENT, PinFlowStep.REMOVE_ENTER -> R.string.applock_pin_current_title
    PinFlowStep.TURN_OFF_ENTER -> R.string.applock_pin_turn_off_enter_title
    PinFlowStep.SETUP_CONFIRM, PinFlowStep.CHANGE_CONFIRM -> R.string.applock_pin_confirm_title
    else -> R.string.applock_pin_new_title
}

private fun entryConfirmLabel(step: PinFlowStep): Int = when (step) {
    PinFlowStep.SETUP_CONFIRM, PinFlowStep.CHANGE_CONFIRM -> R.string.applock_pin_set_button
    else -> R.string.applock_pin_next
}

/** Remaining wait for a delayed message, counted down locally from the bounded delay (never saved). 0 when none. */
@Composable
private fun rememberDelayRemaining(state: AppPinSettingsUiState): Long {
    var remaining by remember(state.delayToken) { mutableLongStateOf(state.delayMillis) }
    LaunchedEffect(state.delayToken) {
        val end = SystemClock.elapsedRealtime() + state.delayMillis
        while (state.delayMillis > 0L) {
            val left = end - SystemClock.elapsedRealtime()
            if (left <= 0L) {
                remaining = 0L
                break
            }
            remaining = left
            delay(left.coerceAtMost(500L))
        }
    }
    return if (state.delayMillis > 0L) remaining else 0L
}

/** The line under the dots: a bounded message only. No failure count is ever shown (INV-38). */
@Composable
private fun flowMessage(state: AppPinSettingsUiState, remaining: Long): String? = when (state.message) {
    PinFlowMessage.NONE -> null
    PinFlowMessage.MISMATCH -> stringResource(R.string.applock_pin_mismatch)
    PinFlowMessage.TOO_WEAK -> stringResource(R.string.applock_pin_too_weak)
    PinFlowMessage.WRONG -> stringResource(R.string.applock_pin_wrong)
    PinFlowMessage.UNREADABLE -> stringResource(R.string.applock_pin_unreadable)
    PinFlowMessage.FAILED -> stringResource(R.string.applock_pin_failed)
    PinFlowMessage.DELAYED, PinFlowMessage.DELAYED_NEUTRAL ->
        if (remaining > 0L) {
            stringResource(
                if (state.message == PinFlowMessage.DELAYED_NEUTRAL) R.string.applock_pin_wait_neutral else R.string.applock_pin_wait,
                pinDurationText(coarseRemainingSeconds(remaining))
            )
        } else {
            stringResource(R.string.applock_pin_wait_over)
        }
}

/** "Turn off app lock?" (T30 disable variant), shown after the current PIN was entered. The action is in the error colour. */
@Composable
private fun TurnOffDialog(state: AppPinSettingsUiState, actions: PinSetupFlowActions) {
    AlertDialog(
        onDismissRequest = { if (!state.busy) actions.onCancel() },
        modifier = Modifier.testTag(PinSetupFlowTags.TURN_OFF_DIALOG),
        title = { Text(stringResource(R.string.applock_pin_turn_off_title)) },
        text = { Text(stringResource(R.string.applock_pin_turn_off_body)) },
        confirmButton = {
            TextButton(
                onClick = actions.onConfirmTurnOff,
                enabled = !state.busy,
                modifier = Modifier.testTag(PinSetupFlowTags.TURN_OFF_CONFIRM)
            ) { Text(stringResource(R.string.applock_pin_turn_off_confirm), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = actions.onCancel, enabled = !state.busy) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

/** "Remove app PIN?" (ux-flows 2.5d), shown after the current PIN was entered. Remove is in the error colour. */
@Composable
private fun RemoveDialog(state: AppPinSettingsUiState, actions: PinSetupFlowActions) {
    AlertDialog(
        onDismissRequest = { if (!state.busy) actions.onCancel() },
        modifier = Modifier.testTag(PinSetupFlowTags.REMOVE_DIALOG),
        title = { Text(stringResource(R.string.applock_pin_remove_title)) },
        text = {
            Text(
                stringResource(
                    if (state.removeNoSecure) R.string.applock_pin_remove_body_no_secure else R.string.applock_pin_remove_body
                )
            )
        },
        confirmButton = {
            TextButton(
                onClick = actions.onConfirmRemove,
                enabled = !state.busy,
                modifier = Modifier.testTag(PinSetupFlowTags.REMOVE_CONFIRM)
            ) { Text(stringResource(R.string.applock_pin_remove_confirm), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = actions.onCancel, enabled = !state.busy) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
