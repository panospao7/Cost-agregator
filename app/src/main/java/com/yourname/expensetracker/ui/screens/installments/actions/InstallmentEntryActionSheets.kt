package com.yourname.expensetracker.ui.screens.installments.actions

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.yourname.expensetracker.R
import com.yourname.expensetracker.ui.screens.installments.formatInstallmentDate

private const val MILLIS_PER_DAY = 86_400_000L

/**
 * The row action list (ux-flows 3.6): only the actions the row offers. [canUnlink] adds the Unlink item
 * after the others (owner-approvals 56); [onUnlink] opens the plan-level Unlink dialog, which states the
 * consequence first. Unlink is not an [InstallmentEntryActionKind] because it runs through the plan
 * actions ViewModel, not the entry actions one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstallmentEntryActionMenuSheet(
    seq: Int,
    actions: Set<InstallmentEntryActionKind>,
    onPick: (InstallmentEntryActionKind) -> Unit,
    onDismiss: () -> Unit,
    canUnlink: Boolean = false,
    onUnlink: () -> Unit = {}
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.installments_entry_number, seq),
                style = MaterialTheme.typography.titleMedium
            )
            // Fixed order, independent of the set's iteration order.
            InstallmentEntryActionKind.entries.filter { it in actions }.forEach { kind ->
                TextButton(onClick = { onPick(kind) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(menuLabel(kind)), modifier = Modifier.fillMaxWidth())
                }
            }
            if (canUnlink) {
                TextButton(onClick = onUnlink, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.installments_unlink_confirm), modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

private fun menuLabel(kind: InstallmentEntryActionKind): Int = when (kind) {
    InstallmentEntryActionKind.RECORD -> R.string.installments_action_record
    InstallmentEntryActionKind.LINK -> R.string.installments_action_link
    InstallmentEntryActionKind.SKIP -> R.string.installments_action_skip
    InstallmentEntryActionKind.ADJUST -> R.string.installments_action_adjust
    InstallmentEntryActionKind.UNSKIP -> R.string.installments_action_unskip
}

/** The sheet of one action. Close only on success; a refusal or failure is shown in the sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstallmentEntryActionSheet(
    state: InstallmentActionsUiState,
    sheet: InstallmentActionSheetState,
    viewModel: InstallmentEntryActionsViewModel
) {
    val running = state.mutation is InstallmentMutationState.InProgress
    // The ViewModel refuses to close a running or edited sheet, so a swipe must not hide it either
    // (the sheet would disappear while the state still holds it). With input (not running) the
    // Hidden request is routed to requestDismiss (discard dialog) and vetoed; while running it is
    // vetoed silently; otherwise the hide is allowed and onDismissRequest closes the state.
    // rememberUpdatedState: the veto reads the latest values.
    val isRunning by rememberUpdatedState(running)
    val hasInput by rememberUpdatedState(sheet.hasInput)
    val sheetState = rememberModalBottomSheetState(
        confirmValueChange = { target ->
            when {
                target != SheetValue.Hidden -> true
                isRunning -> false
                hasInput -> {
                    viewModel.requestDismiss()
                    false
                }
                else -> true
            }
        }
    )
    ModalBottomSheet(onDismissRequest = viewModel::requestDismiss, sheetState = sheetState) {
        // System back goes through the same dismiss request (discard dialog / silent while running).
        // Registered inside the sheet's dialog window so it takes precedence over the sheet's own handler.
        BackHandler(onBack = viewModel::requestDismiss)
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(sheetTitle(sheet.kind), sheet.target.seq),
                style = MaterialTheme.typography.titleMedium
            )
            when (sheet.kind) {
                InstallmentEntryActionKind.RECORD -> RecordBody(sheet)
                InstallmentEntryActionKind.LINK -> LinkBody(sheet, running, viewModel::selectExpense)
                InstallmentEntryActionKind.SKIP -> SkipBody(sheet, running, viewModel::setSkipReason)
                InstallmentEntryActionKind.ADJUST ->
                    AdjustBody(sheet, running, viewModel::setAmountText, viewModel::setDueEpochDay)
                InstallmentEntryActionKind.UNSKIP -> Text(
                    text = stringResource(R.string.installments_action_unskip_body),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            MutationMessage(state.mutation)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End)
            ) {
                TextButton(onClick = viewModel::requestDismiss, enabled = !running) {
                    Text(stringResource(R.string.installments_action_cancel))
                }
                Button(onClick = viewModel::submit, enabled = sheet.canSubmit && !running) {
                    Text(stringResource(if (running) R.string.installments_action_working else primaryLabel(sheet.kind)))
                }
            }
        }
    }
    if (state.showDiscardDialog) {
        DiscardChangesDialog(onDiscard = viewModel::confirmDiscard, onKeepEditing = viewModel::cancelDiscard)
    }
}

private fun sheetTitle(kind: InstallmentEntryActionKind): Int = when (kind) {
    InstallmentEntryActionKind.RECORD -> R.string.installments_action_record_title
    InstallmentEntryActionKind.LINK -> R.string.installments_action_link_title
    InstallmentEntryActionKind.SKIP -> R.string.installments_action_skip_title
    InstallmentEntryActionKind.ADJUST -> R.string.installments_action_adjust_title
    InstallmentEntryActionKind.UNSKIP -> R.string.installments_action_unskip_title
}

private fun primaryLabel(kind: InstallmentEntryActionKind): Int = when (kind) {
    InstallmentEntryActionKind.RECORD -> R.string.installments_action_record
    InstallmentEntryActionKind.LINK -> R.string.installments_action_link_confirm
    InstallmentEntryActionKind.SKIP -> R.string.installments_action_skip_confirm
    InstallmentEntryActionKind.ADJUST -> R.string.installments_action_adjust_confirm
    InstallmentEntryActionKind.UNSKIP -> R.string.installments_action_unskip
}

@Composable
private fun RecordBody(sheet: InstallmentActionSheetState) {
    Text(
        text = stringResource(
            R.string.installments_action_record_body,
            sheet.target.amount.formatted,
            formatInstallmentDate(sheet.target.dueEpochDay)
        ),
        style = MaterialTheme.typography.bodyMedium
    )
    // INV-13 / INV-7: a matching charge exists, so recording could count the payment twice.
    if (sheet.target.suggestions.isNotEmpty()) {
        Text(
            text = stringResource(R.string.installments_action_record_match_warning),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.tertiary
        )
    }
}

@Composable
private fun LinkBody(
    sheet: InstallmentActionSheetState,
    running: Boolean,
    onSelect: (Long) -> Unit
) {
    if (sheet.target.suggestions.isEmpty()) {
        Text(stringResource(R.string.installments_action_link_empty), style = MaterialTheme.typography.bodyMedium)
        return
    }
    Text(stringResource(R.string.installments_action_link_body), style = MaterialTheme.typography.bodyMedium)
    sheet.target.suggestions.forEach { suggestion ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(
                    selected = sheet.selectedExpenseId == suggestion.expenseId,
                    enabled = !running,
                    role = Role.RadioButton,
                    onClick = { onSelect(suggestion.expenseId) }
                ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            RadioButton(selected = sheet.selectedExpenseId == suggestion.expenseId, onClick = null, enabled = !running)
            Text(
                text = stringResource(
                    R.string.installments_two_parts,
                    suggestion.amount.formatted,
                    formatInstallmentDate(suggestion.dateEpochDay)
                ),
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
private fun SkipBody(
    sheet: InstallmentActionSheetState,
    running: Boolean,
    onSelect: (InstallmentSkipReasonUi) -> Unit
) {
    Text(stringResource(R.string.installments_action_skip_body), style = MaterialTheme.typography.bodyMedium)
    InstallmentSkipReasonUi.entries.forEach { reason ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(
                    selected = sheet.skipReason == reason,
                    enabled = !running,
                    role = Role.RadioButton,
                    onClick = { onSelect(reason) }
                ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            RadioButton(selected = sheet.skipReason == reason, onClick = null, enabled = !running)
            Text(stringResource(skipReasonLabel(reason)), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

private fun skipReasonLabel(reason: InstallmentSkipReasonUi): Int = when (reason) {
    InstallmentSkipReasonUi.LENDER_HOLIDAY -> R.string.installments_action_skip_reason_holiday
    InstallmentSkipReasonUi.WAIVED -> R.string.installments_action_skip_reason_waived
    InstallmentSkipReasonUi.OTHER -> R.string.installments_action_skip_reason_other
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdjustBody(
    sheet: InstallmentActionSheetState,
    running: Boolean,
    onAmountChange: (String) -> Unit,
    onDueChange: (Long) -> Unit
) {
    var showDatePicker by remember { mutableStateOf(false) }
    Text(
        text = stringResource(
            R.string.installments_action_adjust_was,
            sheet.target.amount.formatted,
            formatInstallmentDate(sheet.target.dueEpochDay)
        ),
        style = MaterialTheme.typography.bodyMedium
    )
    OutlinedTextField(
        value = sheet.amountText,
        onValueChange = onAmountChange,
        enabled = !running,
        singleLine = true,
        label = { Text(stringResource(R.string.installments_action_adjust_amount, sheet.target.currencyCode)) },
        isError = sheet.amountMinor == null,
        supportingText = if (sheet.amountMinor == null) {
            { Text(stringResource(R.string.installments_action_refused_amount)) }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth()
    )
    TextButton(onClick = { showDatePicker = true }, enabled = !running) {
        Text(stringResource(R.string.installments_action_adjust_date, formatInstallmentDate(sheet.dueEpochDay)))
    }
    if (showDatePicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = sheet.dueEpochDay * MILLIS_PER_DAY)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    // The picker reports UTC midnight of the chosen civil date.
                    pickerState.selectedDateMillis?.let { onDueChange(Math.floorDiv(it, MILLIS_PER_DAY)) }
                    showDatePicker = false
                }) { Text(stringResource(R.string.installments_action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.installments_action_cancel))
                }
            }
        ) { DatePicker(state = pickerState) }
    }
}

/** Refusals and failures are shown in the sheet as fixed strings (INV-31). */
@Composable
private fun MutationMessage(mutation: InstallmentMutationState) {
    val text = when (mutation) {
        is InstallmentMutationState.Refused -> InstallmentActionMessages.refusalText(mutation.code)
        is InstallmentMutationState.Failed -> InstallmentActionMessages.failureText(mutation.code)
        else -> return
    }
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error
    )
}

@Composable
private fun DiscardChangesDialog(onDiscard: () -> Unit, onKeepEditing: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeepEditing,
        title = { Text(stringResource(R.string.installments_action_discard_title)) },
        text = { Text(stringResource(R.string.installments_action_discard_body)) },
        confirmButton = {
            TextButton(onClick = onDiscard) { Text(stringResource(R.string.installments_action_discard)) }
        },
        dismissButton = {
            TextButton(onClick = onKeepEditing) { Text(stringResource(R.string.installments_action_keep_editing)) }
        }
    )
}

/**
 * Shows the notice snackbar once. Undo (ux-flows 3.6 row Undo, not the domain-spec AE-14) is offered
 * only where the notice carries a clean inverse; Record has none. The notice is consumed after it is
 * shown or dismissed.
 */
@Composable
fun InstallmentActionNoticeEffect(
    state: InstallmentActionsUiState,
    snackbarHostState: SnackbarHostState,
    viewModel: InstallmentEntryActionsViewModel
) {
    val notice = state.notice ?: return
    val message = stringResource(InstallmentActionMessages.noticeText(notice.notice))
    val undoLabel = stringResource(R.string.installments_action_undo)
    LaunchedEffect(notice) {
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = if (notice.undo != null) undoLabel else null,
            duration = SnackbarDuration.Long
        )
        viewModel.consumeNotice()
        if (result == SnackbarResult.ActionPerformed) notice.undo?.let(viewModel::undo)
    }
}

/** Makes a row tappable only when it has actions. */
fun Modifier.installmentRowClickable(enabled: Boolean, onClick: () -> Unit): Modifier =
    if (enabled) clickable(onClick = onClick) else this
