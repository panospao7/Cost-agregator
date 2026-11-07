package com.yourname.expensetracker.ui.screens.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.core.money.MoneyAmount
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import com.yourname.expensetracker.ui.theme.Dimens
import com.yourname.expensetracker.ui.theme.SemanticColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// ---------------------------------------------------------------------------------------------
// Refund netting (DR-05) S4 UI (ux-flows 2). Row-local pieces (notice, chips, menu items) and the
// sheets and dialogs driven by RefundLinkViewModel. Every amount is shown with its own currency;
// an unavailable figure is omitted, never rendered as zero. No log line here carries text or amounts.
// ---------------------------------------------------------------------------------------------

private fun money(amount: MoneyAmount): String = CurrencyFormatter.formatMoney(amount.amount, amount.currency.code)

private fun money(amount: Double, currency: String): String = CurrencyFormatter.formatMoney(amount, currency)

private fun shortDate(epochMs: Long): String =
    DateTimeFormatter.ofPattern("MMM dd", Locale.getDefault()).format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

private fun monthYear(epochMs: Long): String =
    DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault()).format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

/** Rows that offer "This is a refund of...": unlinked, not installment-flagged deposits (Q10, AE-28). */
internal fun Expense.canApplyAsRefund(): Boolean =
    transactionType == TransactionType.DEPOSIT && !(refundNettedAmount > 0.0) && spendingExclusionReason == null

internal fun Expense.hasRefundLink(): Boolean = refundNettedAmount > 0.0

// ------------------------------------------------------------------ row pieces

/** The one shared likely-refund notice (Q11): the row and ChangeTypeDialog both render it. */
@Composable
internal fun RefundLikelyNotice(onApply: () -> Unit, enabled: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = stringResource(R.string.transactions_refund_likelihood_notice),
            style = MaterialTheme.typography.bodySmall,
            color = SemanticColors.WarningOrange
        )
        TextButton(
            onClick = onApply,
            enabled = enabled,
            modifier = Modifier.heightIn(min = Dimens.TouchTargetMin)
        ) {
            Text(stringResource(R.string.refund_notice_action))
        }
    }
}

/**
 * Chips of a linked row, from the row's own values (no read). Purchase: "Refunded x" then "Net y".
 * Deposit: "Applied to a purchase: x" and, when part of it is unused, "z still counts as income".
 */
@Composable
internal fun RefundRowChips(expense: Expense) {
    if (!expense.hasRefundLink()) return
    val currency = expense.currency
    when (expense.transactionType) {
        TransactionType.PURCHASE -> {
            val net = RefundUiMoney.net(expense.amount, expense.refundNettedAmount, currency)
            val refundedText = money(expense.refundNettedAmount, currency)
            val cd = if (net != null) {
                stringResource(R.string.refund_chip_purchase_cd_format, refundedText, money(expense.amount, currency), money(net, currency))
            } else {
                stringResource(R.string.refund_chip_purchase_format, refundedText)
            }
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = cd }
            ) {
                Text(
                    text = stringResource(R.string.refund_chip_purchase_format, refundedText),
                    style = MaterialTheme.typography.labelSmall,
                    color = SemanticColors.SuccessGreen
                )
                if (net != null) {
                    Text(
                        text = stringResource(R.string.refund_chip_net_format, money(net, currency)),
                        style = MaterialTheme.typography.labelSmall,
                        color = SemanticColors.TextSecondary
                    )
                }
            }
        }
        TransactionType.DEPOSIT -> {
            val appliedText = money(expense.refundNettedAmount, currency)
            val cd = stringResource(R.string.refund_chip_deposit_cd_format, appliedText, money(expense.amount, currency))
            Text(
                text = stringResource(R.string.refund_chip_deposit_format, appliedText),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = cd }
            )
            if (RefundUiMoney.isLess(expense.refundNettedAmount, expense.amount, currency)) {
                RefundUiMoney.subtract(expense.amount, expense.refundNettedAmount, currency)?.let { left ->
                    Text(
                        text = stringResource(R.string.refund_chip_deposit_left_format, money(left, currency)),
                        style = MaterialTheme.typography.labelSmall,
                        color = SemanticColors.TextSecondary
                    )
                }
            }
        }
        else -> Unit
    }
}

/** Overflow items: "This is a refund of..." (unlinked eligible deposit) or "Refund details" (linked row). */
@Composable
internal fun RefundMenuItems(
    expense: Expense,
    busy: Boolean,
    onApply: () -> Unit,
    onDetails: () -> Unit,
    onDismiss: () -> Unit
) {
    if (expense.canApplyAsRefund()) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.refund_menu_apply)) },
            enabled = !busy,
            onClick = {
                onDismiss()
                onApply()
            },
            leadingIcon = { Icon(imageVector = Icons.Rounded.Undo, contentDescription = null) }
        )
    }
    if (expense.hasRefundLink()) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.refund_menu_details)) },
            enabled = !busy,
            onClick = {
                onDismiss()
                onDetails()
            },
            leadingIcon = { Icon(imageVector = Icons.Rounded.Receipt, contentDescription = null) }
        )
    }
}

// ------------------------------------------------------------------ host

/** Sheets and dialogs of the flow. Rendered once by TransactionsScreen; closed states render nothing. */
@Composable
fun RefundLinkHost(viewModel: RefundLinkViewModel) {
    val picker by viewModel.picker.collectAsState()
    val confirm by viewModel.confirm.collectAsState()
    val details by viewModel.details.collectAsState()
    val unlink by viewModel.unlinkConfirm.collectAsState()

    RefundPickerSheet(
        state = picker,
        onSelect = { viewModel.selectCandidate(it) },
        onRetry = { viewModel.retryPicker() },
        onClose = { viewModel.closePicker() }
    )
    val confirmState = confirm
    if (confirmState != null) {
        RefundConfirmDialog(
            state = confirmState,
            onConfirm = { viewModel.confirmLink() },
            onDismiss = { viewModel.dismissConfirm() }
        )
    }
    RefundDetailsSheet(
        state = details,
        onRemove = { viewModel.requestUnlink(it) },
        onRetry = { viewModel.retryDetails() },
        onClose = { viewModel.closeDetails() }
    )
    val unlinkState = unlink
    if (unlinkState != null) {
        RefundUnlinkDialog(
            state = unlinkState,
            onConfirm = { viewModel.confirmUnlink() },
            onDismiss = { viewModel.dismissUnlink() }
        )
    }
}

// ------------------------------------------------------------------ picker sheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RefundPickerSheet(
    state: RefundPickerUiState,
    onSelect: (RefundCandidateRowUi) -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit
) {
    val deposit = when (state) {
        RefundPickerUiState.Idle -> null
        is RefundPickerUiState.Loading -> state.deposit
        is RefundPickerUiState.Content -> state.deposit
        is RefundPickerUiState.None -> state.deposit
        is RefundPickerUiState.Unavailable -> state.deposit
    }
    if (deposit == null) return

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.refund_picker_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() }
                )
                IconButton(onClick = onClose, modifier = Modifier.size(Dimens.TouchTargetMin)) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.a11y_close))
                }
            }
            Text(
                text = stringResource(R.string.refund_picker_subtitle_format, money(deposit.amount), shortDate(deposit.dateMs)),
                style = MaterialTheme.typography.bodySmall,
                color = SemanticColors.TextSecondary
            )
            HorizontalDivider()
            when (state) {
                is RefundPickerUiState.Loading -> {
                    val loadingCd = stringResource(R.string.refund_picker_loading_cd)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = loadingCd })
                    }
                }
                is RefundPickerUiState.Content -> {
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                        items(items = state.candidates, key = { it.purchaseId }) { candidate ->
                            RefundCandidateRow(candidate = candidate, onClick = { onSelect(candidate) })
                            HorizontalDivider()
                        }
                    }
                }
                is RefundPickerUiState.None -> {
                    Text(
                        text = stringResource(R.string.refund_picker_none_format, state.currencyCode, state.windowDays),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                is RefundPickerUiState.Unavailable -> {
                    // Never the "none" text and never a zero: the read did not happen.
                    Text(
                        text = stringResource(R.string.refund_picker_unavailable),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = Dimens.TouchTargetMin)) {
                        Text(stringResource(R.string.retry))
                    }
                }
                RefundPickerUiState.Idle -> Unit
            }
        }
    }
}

@Composable
private fun RefundCandidateRow(candidate: RefundCandidateRowUi, onClick: () -> Unit) {
    val selectLabel = stringResource(R.string.refund_candidate_select_cd_format, candidate.merchant)
    val category = candidate.categoryName ?: stringResource(R.string.uncategorized_label)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClickLabel = selectLabel, role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = candidate.merchant,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = money(candidate.amount),
                style = MaterialTheme.typography.bodyLarge
            )
        }
        Text(
            text = "${shortDate(candidate.dateMs)} - $category",
            style = MaterialTheme.typography.bodySmall,
            color = SemanticColors.TextSecondary
        )
        if (candidate.partlyRefunded) {
            Text(
                text = stringResource(R.string.refund_candidate_remaining_format, money(candidate.remaining)),
                style = MaterialTheme.typography.bodySmall,
                color = SemanticColors.TextSecondary
            )
        }
    }
}

// ------------------------------------------------------------------ confirm dialog

@Composable
internal fun RefundConfirmDialog(
    state: RefundConfirmUiState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val category = state.candidate.categoryName ?: stringResource(R.string.uncategorized_label)
    AlertDialog(
        onDismissRequest = { if (!state.submitting) onDismiss() },
        title = { Text(stringResource(R.string.refund_confirm_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LabelValueRow(
                    label = stringResource(R.string.refund_label_purchase),
                    detail = "${state.candidate.merchant}, ${shortDate(state.candidate.dateMs)}",
                    value = money(state.candidate.amount)
                )
                LabelValueRow(
                    label = stringResource(R.string.refund_label_refunded),
                    detail = null,
                    value = money(state.applied)
                )
                LabelValueRow(
                    label = stringResource(R.string.refund_label_net),
                    detail = null,
                    value = money(state.netAfter)
                )
                Text(
                    text = stringResource(
                        R.string.refund_confirm_effect_format,
                        monthYear(state.candidate.dateMs),
                        category,
                        money(state.applied)
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (state.depositPartlyUsed) {
                    Text(
                        text = stringResource(
                            R.string.refund_confirm_partial_format,
                            money(state.applied),
                            money(state.deposit.amount)
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (state.submitting) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !state.submitting,
                modifier = Modifier.heightIn(min = Dimens.TouchTargetMin)
            ) {
                Text(stringResource(R.string.refund_confirm_action))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !state.submitting,
                modifier = Modifier.heightIn(min = Dimens.TouchTargetMin)
            ) {
                Text(stringResource(R.string.cancel_button))
            }
        }
    )
}

/** One label and value pair as a single merged accessibility node. */
@Composable
private fun LabelValueRow(label: String, detail: String?, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = SemanticColors.TextSecondary
                )
            }
        }
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

// ------------------------------------------------------------------ details sheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RefundDetailsSheet(
    state: RefundDetailsUiState,
    onRemove: (RefundLinkRowUi) -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit
) {
    val subject = when (state) {
        RefundDetailsUiState.Idle -> null
        is RefundDetailsUiState.Loading -> state.subject
        is RefundDetailsUiState.Content -> state.subject
        is RefundDetailsUiState.Unavailable -> state.subject
    }
    if (subject == null) return

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.refund_menu_details),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() }
                )
                IconButton(onClick = onClose, modifier = Modifier.size(Dimens.TouchTargetMin)) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.a11y_close))
                }
            }
            if (subject.isDeposit) {
                Text(
                    text = stringResource(R.string.refund_picker_subtitle_format, money(subject.amount), shortDate(subject.dateMs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = SemanticColors.TextSecondary
                )
            } else {
                Text(
                    text = "${subject.merchant} - ${shortDate(subject.dateMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = SemanticColors.TextSecondary
                )
                // Gross, refunded and net come from the row, so they show while the links load.
                LabelValueRow(label = stringResource(R.string.refund_label_purchase), detail = null, value = money(subject.amount))
                LabelValueRow(label = stringResource(R.string.refund_label_refunded), detail = null, value = money(subject.refunded))
                RefundUiMoney.net(subject.amount.amount, subject.refunded.amount, subject.amount.currency.code)?.let { net ->
                    LabelValueRow(
                        label = stringResource(R.string.refund_label_net),
                        detail = null,
                        value = money(net, subject.amount.currency.code)
                    )
                }
            }
            HorizontalDivider()
            Text(
                text = stringResource(
                    if (subject.isDeposit) R.string.refund_details_deposit_header else R.string.refund_details_purchase_header
                ),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() }
            )
            when (state) {
                is RefundDetailsUiState.Loading -> {
                    val loadingCd = stringResource(R.string.refund_picker_loading_cd)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = loadingCd })
                    }
                }
                is RefundDetailsUiState.Content -> {
                    state.links.forEach { link ->
                        RefundLinkRow(link = link, showCategory = subject.isDeposit, onRemove = { onRemove(link) })
                    }
                }
                is RefundDetailsUiState.Unavailable -> {
                    Text(
                        text = stringResource(R.string.refund_details_unavailable),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = Dimens.TouchTargetMin)) {
                        Text(stringResource(R.string.retry))
                    }
                }
                RefundDetailsUiState.Idle -> Unit
            }
        }
    }
}

@Composable
private fun RefundLinkRow(link: RefundLinkRowUi, showCategory: Boolean, onRemove: () -> Unit) {
    val category = link.categoryName ?: stringResource(R.string.uncategorized_label)
    val removeCd = stringResource(R.string.refund_details_remove_cd_format, money(link.applied), shortDate(link.dateMs))
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (showCategory) {
                    "${link.merchant} - ${shortDate(link.dateMs)} - $category"
                } else {
                    "${link.merchant} - ${shortDate(link.dateMs)}"
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Text(text = money(link.applied), style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(
            onClick = onRemove,
            modifier = Modifier
                .align(Alignment.End)
                .heightIn(min = Dimens.TouchTargetMin)
                .semantics { contentDescription = removeCd }
        ) {
            Text(stringResource(R.string.refund_details_remove))
        }
    }
}

// ------------------------------------------------------------------ unlink dialog

@Composable
internal fun RefundUnlinkDialog(
    state: RefundUnlinkConfirmUiState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    // Q7: "go back to" only when this is the last link, otherwise "show" the new net.
    val body = stringResource(
        if (state.lastLink) R.string.refund_unlink_body_format else R.string.refund_unlink_body_remaining_format,
        money(state.link.applied),
        state.purchaseMerchant,
        money(state.shownAmount)
    )
    AlertDialog(
        onDismissRequest = { if (!state.submitting) onDismiss() },
        title = { Text(stringResource(R.string.refund_unlink_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(text = body, style = MaterialTheme.typography.bodyMedium)
                if (state.submitting) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !state.submitting,
                modifier = Modifier.heightIn(min = Dimens.TouchTargetMin)
            ) {
                Text(stringResource(R.string.refund_details_remove))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !state.submitting,
                modifier = Modifier.heightIn(min = Dimens.TouchTargetMin)
            ) {
                Text(stringResource(R.string.cancel_button))
            }
        }
    )
}

/** Delete dialog extra line (ux-flows 5): shown only for a row that carries a refund link. */
@Composable
internal fun RefundDeleteNote(expense: Expense) {
    if (!expense.hasRefundLink()) return
    Text(
        text = stringResource(R.string.transactions_delete_refund_linked_note),
        style = MaterialTheme.typography.bodySmall
    )
}

/** Change-type dialog lock line (ux-flows 3.3 prevention). */
@Composable
internal fun RefundLockedNote() {
    Text(
        text = stringResource(R.string.transactions_refund_linked_locked),
        style = MaterialTheme.typography.bodySmall,
        color = SemanticColors.WarningOrange
    )
}
