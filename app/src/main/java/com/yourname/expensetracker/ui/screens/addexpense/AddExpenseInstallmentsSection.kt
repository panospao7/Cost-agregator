package com.yourname.expensetracker.ui.screens.addexpense

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.installment.model.InstallmentFulfilmentMode
import com.yourname.expensetracker.domain.installment.model.InstallmentRefusalCode
import com.yourname.expensetracker.domain.installment.model.InstallmentSplitMethod
import com.yourname.expensetracker.domain.installment.schedule.InstallmentScheduleCalculator
import com.yourname.expensetracker.ui.navigation.InstallmentsFeature
import com.yourname.expensetracker.ui.screens.installments.actions.InstallmentActionMessages
import com.yourname.expensetracker.ui.screens.installments.actions.InstallmentPlanMutationState
import com.yourname.expensetracker.ui.screens.installments.actions.InstallmentPlanOperation
import com.yourname.expensetracker.ui.screens.installments.declare.declareMutationMessage
import com.yourname.expensetracker.ui.screens.installments.formatInstallmentDate
import java.time.LocalDate
import java.time.ZoneId

/** Test tags of the section's inputs (the section has no other stable handle). */
internal object AddExpenseInstallmentsTags {
    const val SWITCH = "add_expense_installments_switch"
    const val COUNT = "add_expense_installments_count"
    const val SPLIT_EQUAL = "add_expense_installments_split_equal"
    const val SPLIT_FIXED = "add_expense_installments_split_fixed"
    const val FIXED_AMOUNT = "add_expense_installments_fixed_amount"
    const val FIRST_DUE = "add_expense_installments_first_due"
    const val MODE_AUTO = "add_expense_installments_mode_auto"
    const val MODE_CONFIRM = "add_expense_installments_mode_confirm"
    const val NAME = "add_expense_installments_name"
}

/**
 * Loan-installments S11b: the "Pay in installments" section of the Add expense form (ux-flows 3.1,
 * plan amendment "S11 re-anchor after MEC B2/C" sections 3 and 4). Placed under the Repeat row.
 *
 * Renders NOTHING unless [featureEnabled] ([InstallmentsFeature.ENABLED] by default): the section is
 * dormant, and the view model's `setInstallmentsEnabled` is gated by the same flag, so `installments.on`
 * can never become true while the feature is off. The currency is the form's one MEC currency control
 * (INSTALLMENT mode, EUR/USD); there is no plan-currency row here. All state lives in
 * [AddExpenseInstallmentsState] on the view model (no rememberSaveable); only the date-picker open flag
 * is local. Refusals arrive through the sheet's existing save-error card, never from this section.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddExpenseInstallmentsSection(
    state: AddExpenseState,
    viewModel: AddExpenseViewModel,
    modifier: Modifier = Modifier,
    featureEnabled: Boolean = InstallmentsFeature.ENABLED
) {
    if (!featureEnabled) return
    val plan = state.installments
    val blockReason: InstallmentRefusalCode? = state.installmentsBlockReason
    val blockText = blockReason
        ?.let { declareMutationMessage(InstallmentPlanMutationState.Refused(InstallmentPlanOperation.CREATE, it)) }
        ?.let { stringResource(it) }
    val editable = !state.isSaving

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                stringResource(R.string.declare_installments_title),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium
            )
            Switch(
                checked = plan.on,
                // Default `enabled` only: the view model's own gate keeps the section dormant.
                onCheckedChange = { viewModel.setInstallmentsEnabled(it) },
                enabled = editable && state.homeCurrency != null && (plan.on || blockReason == null),
                modifier = Modifier.testTag(AddExpenseInstallmentsTags.SWITCH)
            )
        }
        if (blockText != null && !plan.on) {
            Text(
                blockText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (!plan.on) return@Column

        Text(
            stringResource(R.string.mec_sheet_installment_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        )
        if (plan.currencyUnset != null) {
            Text(
                stringResource(R.string.mec_installment_unset),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )
        }

        // Number of payments: integer 2..480 (the same bound as the declare sheet).
        val countInvalid = plan.count == null
        OutlinedTextField(
            value = plan.countText,
            onValueChange = { viewModel.setInstallmentCountText(it) },
            label = { Text(stringResource(R.string.declare_installments_count)) },
            isError = countInvalid,
            supportingText = if (countInvalid) {
                { Text(stringResource(R.string.declare_installments_refused_count)) }
            } else {
                null
            },
            singleLine = true,
            enabled = editable,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(AddExpenseInstallmentsTags.COUNT)
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = plan.split == InstallmentSplitMethod.EQUAL_SPLIT,
                onClick = { viewModel.setInstallmentSplit(InstallmentSplitMethod.EQUAL_SPLIT) },
                enabled = editable,
                label = { Text(stringResource(R.string.declare_installments_split_equal)) },
                modifier = Modifier.testTag(AddExpenseInstallmentsTags.SPLIT_EQUAL)
            )
            FilterChip(
                selected = plan.split == InstallmentSplitMethod.FIXED_PAYMENT,
                onClick = { viewModel.setInstallmentSplit(InstallmentSplitMethod.FIXED_PAYMENT) },
                enabled = editable,
                label = { Text(stringResource(R.string.declare_installments_split_fixed)) },
                modifier = Modifier.testTag(AddExpenseInstallmentsTags.SPLIT_FIXED)
            )
        }

        if (plan.split == InstallmentSplitMethod.FIXED_PAYMENT) {
            val fixedInvalid = plan.fixedText.isNotBlank() && plan.fixedMinor == null
            OutlinedTextField(
                value = plan.fixedText,
                onValueChange = { viewModel.setInstallmentFixedText(it) },
                label = { Text(stringResource(R.string.declare_installments_fixed_amount)) },
                isError = fixedInvalid,
                supportingText = if (fixedInvalid) {
                    { Text(stringResource(InstallmentActionMessages.refusalText(InstallmentRefusalCode.INVALID_AMOUNT))) }
                } else {
                    null
                },
                singleLine = true,
                enabled = editable,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(AddExpenseInstallmentsTags.FIXED_AMOUNT)
            )
        }

        FirstDueRow(state = state, viewModel = viewModel, editable = editable)

        Text(stringResource(R.string.declare_installments_mode_title), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = plan.mode == InstallmentFulfilmentMode.AUTO_RECORD,
                onClick = { viewModel.setInstallmentMode(InstallmentFulfilmentMode.AUTO_RECORD) },
                enabled = editable,
                label = { Text(stringResource(R.string.declare_installments_mode_auto)) },
                modifier = Modifier.testTag(AddExpenseInstallmentsTags.MODE_AUTO)
            )
            FilterChip(
                selected = plan.mode == InstallmentFulfilmentMode.CONFIRM,
                onClick = { viewModel.setInstallmentMode(InstallmentFulfilmentMode.CONFIRM) },
                enabled = editable,
                label = { Text(stringResource(R.string.declare_installments_mode_confirm)) },
                modifier = Modifier.testTag(AddExpenseInstallmentsTags.MODE_CONFIRM)
            )
        }

        // The plan name follows the merchant until typed; blank returns to the merchant.
        OutlinedTextField(
            value = plan.nameText.orEmpty(),
            onValueChange = { viewModel.setInstallmentName(it) },
            label = { Text(stringResource(R.string.declare_installments_name)) },
            placeholder = if (state.merchant.isNotBlank()) {
                { Text(state.merchant) }
            } else {
                null
            },
            singleLine = true,
            enabled = editable,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(AddExpenseInstallmentsTags.NAME)
        )
    }
}

/** The first payment date: default purchase date + 1 month; the picker stores an explicit epoch day. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FirstDueRow(state: AddExpenseState, viewModel: AddExpenseViewModel, editable: Boolean) {
    val zone = remember { ZoneId.systemDefault() }
    val purchaseDay = InstallmentScheduleCalculator.civilEpochDay(state.date, zone)
    val firstDay = state.installments.firstDueEpochDay
        ?: LocalDate.ofEpochDay(purchaseDay).plusMonths(1).toEpochDay()
    var picking by remember { mutableStateOf(false) }

    TextButton(
        onClick = { picking = true },
        enabled = editable,
        modifier = Modifier.testTag(AddExpenseInstallmentsTags.FIRST_DUE)
    ) {
        Text(stringResource(R.string.declare_installments_first_due, formatInstallmentDate(firstDay)))
    }
    if (firstDay < purchaseDay) {
        Text(
            stringResource(R.string.declare_installments_refused_first_date),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
    if (picking) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = firstDay * MILLIS_PER_DAY)
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { viewModel.setInstallmentFirstDue(it / MILLIS_PER_DAY) }
                    picking = false
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { picking = false }) { Text(stringResource(R.string.installments_action_cancel)) }
            }
        ) { DatePicker(state = pickerState) }
    }
}

private const val MILLIS_PER_DAY = 86_400_000L
