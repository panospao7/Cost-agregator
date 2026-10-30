package com.yourname.expensetracker.ui.screens.transactions

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EventRepeat
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.domain.transaction.SpendingExclusionReason

/**
 * PE-D: the role of an expense in the Transactions delete dialog (ux-flows 3.7, spec 7.6). A static
 * label from the row marks; the stored flag is the fallback when no mark exists (marks failed or not
 * loaded yet), so the dialog never needs a plan read. A PAYOFF payment counts as [INSTALLMENT].
 */
enum class InstallmentDeleteRole {
    PURCHASE,
    INSTALLMENT,
    REVERSED;

    @StringRes
    fun messageRes(): Int = when (this) {
        PURCHASE -> R.string.transactions_installment_delete_role_purchase
        INSTALLMENT -> R.string.transactions_installment_delete_role_installment
        REVERSED -> R.string.transactions_installment_delete_role_reversed
    }

    companion object {
        /** Null when the expense has no installment role (the dialog is then unchanged). */
        fun of(expense: Expense, marks: InstallmentRowMarks): InstallmentDeleteRole? =
            when (val mark = marks.byExpenseId[expense.id]) {
                is InstallmentRowMark.PurchaseRecord -> PURCHASE
                is InstallmentRowMark.Payment -> if (mark.reversed) REVERSED else INSTALLMENT
                null -> when (expense.spendingExclusionReason) {
                    SpendingExclusionReason.INSTALLMENT_PURCHASE.name -> PURCHASE
                    SpendingExclusionReason.INSTALLMENT_REVERSED.name -> REVERSED
                    else -> null
                }
            }
    }
}

/** Chips of one row (OQ-22, AE-17). Rendered only when [ui] is non-null, that is never while dormant. */
@Composable
fun InstallmentRowChips(ui: InstallmentRowUi?, onOpenPlan: (Long) -> Unit) {
    if (ui == null) return
    if (ui.mark == null && ui.suggestionSeq == null && ui.duplicateSeq == null) return
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        when (val mark = ui.mark) {
            is InstallmentRowMark.PurchaseRecord -> {
                RowChip(
                    pluralStringResource(R.plurals.transactions_installment_chip_paid_in, mark.installmentCount, mark.installmentCount)
                ) { onOpenPlan(mark.planId) }
                RowChip(stringResource(R.string.transactions_installment_chip_not_counted)) { onOpenPlan(mark.planId) }
            }
            is InstallmentRowMark.Payment -> {
                // Source badge: a UI join, never stored text.
                RowChip(stringResource(R.string.transactions_installment_source_badge)) { onOpenPlan(mark.planId) }
                if (mark.showSequence) {
                    RowChip(stringResource(R.string.transactions_installment_chip_n_of_m, mark.seq, mark.total)) { onOpenPlan(mark.planId) }
                }
                if (mark.reversed) {
                    RowChip(stringResource(R.string.transactions_installment_chip_not_counted_refunded)) { onOpenPlan(mark.planId) }
                }
            }
            null -> Unit
        }
        ui.suggestionSeq?.let { RowChip(stringResource(R.string.transactions_installment_chip_matches, it)) {} }
        ui.duplicateSeq?.let { RowChip(stringResource(R.string.transactions_installment_chip_duplicate, it)) {} }
    }
}

@Composable
private fun RowChip(text: String, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(text, style = MaterialTheme.typography.labelSmall) }
    )
}

/** The row overflow entry (ux-flows 3.2). Statements inside the existing DropdownMenu content. */
@Composable
fun InstallmentMenuItems(
    menu: InstallmentMenuAction?,
    onDismiss: () -> Unit,
    onPay: () -> Unit,
    onViewPlan: (Long) -> Unit
) {
    when (menu) {
        null, InstallmentMenuAction.Hidden -> Unit
        InstallmentMenuAction.PayInInstallments -> DropdownMenuItem(
            text = { Text(stringResource(R.string.transactions_installment_menu_pay)) },
            onClick = { onDismiss(); onPay() },
            leadingIcon = { Icon(Icons.Rounded.EventRepeat, contentDescription = null) }
        )
        is InstallmentMenuAction.ViewPlan -> DropdownMenuItem(
            text = { Text(stringResource(R.string.transactions_installment_menu_view)) },
            onClick = { onDismiss(); onViewPlan(menu.planId) },
            leadingIcon = { Icon(Icons.Rounded.EventRepeat, contentDescription = null) }
        )
        is InstallmentMenuAction.Disabled -> DropdownMenuItem(
            enabled = false,
            text = {
                Column {
                    Text(stringResource(R.string.transactions_installment_menu_pay))
                    Text(
                        stringResource(
                            when (menu.reason) {
                                InstallmentMenuDisabledReason.NOT_MINE -> R.string.transactions_installment_menu_disabled_not_mine
                                InstallmentMenuDisabledReason.SHARED_OR_SPLIT -> R.string.transactions_installment_menu_disabled_shared
                            }
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            onClick = {},
            leadingIcon = { Icon(Icons.Rounded.EventRepeat, contentDescription = null) }
        )
    }
}

/** The installments filter chip. Shown by the screen only when the feature flag is on. */
@Composable
fun InstallmentFilterChipRow(selected: Boolean, onToggle: () -> Unit) {
    Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        FilterChip(
            selected = selected,
            onClick = onToggle,
            label = { Text(stringResource(R.string.transactions_installment_filter)) }
        )
    }
}
