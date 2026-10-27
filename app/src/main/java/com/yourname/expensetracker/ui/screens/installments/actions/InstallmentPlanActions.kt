package com.yourname.expensetracker.ui.screens.installments.actions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.RadioButton
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentOpResult
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPlanLifecycleCoordinator
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPostCommitDispatcher
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanKind
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanStatus
import com.yourname.expensetracker.domain.installment.model.InstallmentRefusalCode
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.ui.model.MoneyDisplayUi
import com.yourname.expensetracker.ui.screens.installments.detail.InstallmentEntryLabelUi
import com.yourname.expensetracker.ui.screens.installments.detail.InstallmentPlanDetailContent
import com.yourname.expensetracker.ui.screens.installments.edit.InstallmentEditScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Plan level flows of loan-installments S10b-2 (create, edit, End plan: Stop / Refund / Pay off early
 * / Delete, and the restricted entry Unlink). Shared by the create, edit and end ViewModels.
 */
enum class InstallmentPlanOperation { CREATE, EDIT, STOP, REFUND, PAY_OFF, DELETE, UNLINK }

/** MutationState of a plan level flow: idle / in progress / success / refused(code) / failed(code). */
sealed interface InstallmentPlanMutationState {
    object Idle : InstallmentPlanMutationState
    data class InProgress(val operation: InstallmentPlanOperation) : InstallmentPlanMutationState
    data class Success(
        val operation: InstallmentPlanOperation,
        val alreadyInState: Boolean,
        val planId: Long?
    ) : InstallmentPlanMutationState
    data class Refused(
        val operation: InstallmentPlanOperation,
        val code: InstallmentRefusalCode
    ) : InstallmentPlanMutationState
    data class Failed(
        val operation: InstallmentPlanOperation,
        val code: InstallmentActionFailureCode
    ) : InstallmentPlanMutationState
}

/**
 * Runs one coordinator operation and maps its result to a [InstallmentPlanMutationState]. The single
 * place where plan level flows dispatch post-commit batches (through the shared dispatcher, after the
 * coordinator returned) and where unmapped exceptions become a bounded code (INV-31).
 * `CancellationException` is always rethrown.
 */
internal suspend fun <T> executeInstallmentPlanOp(
    operation: InstallmentPlanOperation,
    dispatcher: InstallmentPostCommitDispatcher,
    planIdOf: (T) -> Long?,
    call: suspend () -> InstallmentOpResult<T>
): InstallmentPlanMutationState {
    val result: InstallmentOpResult<T> = try {
        call()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Bounded operation name only: no exception text, ids, names or amounts (INV-31).
        Timber.w("Installment plan operation failed unexpectedly: op=%s", operation.name)
        return InstallmentPlanMutationState.Failed(operation, InstallmentActionFailureCode.UNEXPECTED)
    }
    return when (result) {
        is InstallmentOpResult.Done -> {
            dispatcher.dispatch(result.postCommit)
            InstallmentPlanMutationState.Success(operation, alreadyInState = false, planId = planIdOf(result.value))
        }
        // AlreadyInState is a silent success.
        is InstallmentOpResult.AlreadyInState ->
            InstallmentPlanMutationState.Success(operation, alreadyInState = true, planId = planIdOf(result.value))
        is InstallmentOpResult.Refused -> {
            Timber.w("Installment plan operation refused: op=%s code=%s", operation.name, result.code.name)
            InstallmentPlanMutationState.Refused(operation, result.code)
        }
        is InstallmentOpResult.Failed -> {
            Timber.w("Installment plan operation failed: op=%s failure=%s", operation.name, result.failure.name)
            InstallmentPlanMutationState.Failed(operation, InstallmentActionMessages.failureCode(result.failure))
        }
        is InstallmentOpResult.RateRefused ->
            InstallmentPlanMutationState.Failed(operation, InstallmentActionFailureCode.UNEXPECTED)
    }
}

/** Fixed texts for plan level mutation outcomes; `null` while nothing is wrong. */
fun installmentPlanMutationMessage(state: InstallmentPlanMutationState): Int? = when (state) {
    is InstallmentPlanMutationState.Refused -> InstallmentActionMessages.refusalText(state.code)
    is InstallmentPlanMutationState.Failed -> InstallmentActionMessages.failureText(state.code)
    else -> null
}

enum class InstallmentPlanDialogKind {
    END_CHOOSER, STOP, REFUND_QUESTION, REFUND_ALREADY_RECORDED, REFUND_CONFIRM, PAY_OFF, DELETE, UNLINK
}

enum class InstallmentEndOption { STOP, REFUND, PAY_OFF }

enum class InstallmentPlanNotice { STOPPED, REFUNDED, PAID_OFF, UNLINKED, NOTHING_TO_CHANGE }

/** What the dialogs need to know about the plan; built from the loaded detail. Display values only. */
data class InstallmentPlanActionContext(
    val planId: Long,
    val status: InstallmentPlanStatus,
    val kind: InstallmentPlanKind,
    val unpaidCount: Int,
    val paidCount: Int,
    val unpaidTotal: MoneyDisplayUi,
    /** Sum of the unpaid scheduled payments in minor units: the payoff prefill. */
    val unpaidTotalMinor: Long,
    val hasPayments: Boolean
)

/** Which plan actions the status table of ux-flows 3.7 offers. */
data class InstallmentPlanAvailability(
    val edit: Boolean,
    val stop: Boolean,
    val refund: Boolean,
    val payOff: Boolean,
    val delete: Boolean
) {
    val canEnd: Boolean get() = stop || refund || payOff

    companion object {
        fun of(context: InstallmentPlanActionContext): InstallmentPlanAvailability {
            val status = context.status
            val active = status == InstallmentPlanStatus.ACTIVE
            return InstallmentPlanAvailability(
                edit = active || status == InstallmentPlanStatus.COMPLETED,
                stop = active,
                refund = active || status == InstallmentPlanStatus.COMPLETED,
                payOff = active && context.unpaidCount >= 1,
                delete = (active || status == InstallmentPlanStatus.CANCELLED) && !context.hasPayments
            )
        }
    }
}

/** Builds the action context from the detail content (pure; no I/O). */
fun InstallmentPlanDetailContent.toActionContext(): InstallmentPlanActionContext {
    val openLabels = setOf(
        InstallmentEntryLabelUi.UPCOMING,
        InstallmentEntryLabelUi.DUE_TODAY,
        InstallmentEntryLabelUi.OVERDUE
    )
    val paymentLabels = setOf(InstallmentEntryLabelUi.PAID, InstallmentEntryLabelUi.PAYOFF, InstallmentEntryLabelUi.REFUNDED)
    val open = entries.filter { it.label in openLabels }
    return InstallmentPlanActionContext(
        planId = planId,
        status = status,
        kind = kind,
        unpaidCount = open.size,
        paidCount = paidCount,
        unpaidTotal = remaining,
        unpaidTotalMinor = open.sumOf { it.scheduledMinor },
        hasPayments = entries.any { it.label in paymentLabels }
    )
}

/** The open dialog and its input. [hasInput] drives the discard dialog (Pay off early only). */
data class InstallmentPlanDialogState(
    val kind: InstallmentPlanDialogKind,
    val context: InstallmentPlanActionContext,
    val entryId: Long? = null,
    val entrySeq: Int = 0,
    val payoffAmountText: String = InstallmentAmountInput.formatMinor(context.unpaidTotalMinor),
    val payoffEpochDay: Long = 0L,
    val todayEpochDay: Long = 0L
) {
    val payoffMinor: Long? get() = InstallmentAmountInput.parseMinor(payoffAmountText)

    val hasInput: Boolean
        get() = kind == InstallmentPlanDialogKind.PAY_OFF &&
            (payoffAmountText != InstallmentAmountInput.formatMinor(context.unpaidTotalMinor) ||
                payoffEpochDay != todayEpochDay)

    /** Pay off needs a positive amount and a date that is not in the future. */
    val canConfirm: Boolean
        get() = kind != InstallmentPlanDialogKind.PAY_OFF || (payoffMinor != null && payoffEpochDay <= todayEpochDay)
}

data class InstallmentPlanActionsUiState(
    val dialog: InstallmentPlanDialogState? = null,
    val mutation: InstallmentPlanMutationState = InstallmentPlanMutationState.Idle,
    val showDiscardDialog: Boolean = false,
    val notice: InstallmentPlanNotice? = null,
    val editOpen: Boolean = false
)

/**
 * End flows (domain-spec AE-7 Stop, AE-8 Refund, AE-9 Early payoff, AE-14 Delete = undo of the
 * declaration) and the restricted entry Unlink. Every confirmation reaches exactly one
 * [InstallmentPlanLifecycleCoordinator] operation; this class never touches a DAO, the transaction
 * lifecycle coordinator or an expense repository (INV-24, INV-25, INV-23: no expense is deleted here).
 * Post-commit batches go through the shared [InstallmentPostCommitDispatcher].
 *
 * "Delete expense and reopen" (OQ-21) is NOT offered here: it needs the standard expense-delete path,
 * which the INV-23 architecture guard forbids inside installment UI code (see the S10b-2 handoff).
 */
@HiltViewModel
class InstallmentPlanActionsViewModel @Inject constructor(
    private val coordinator: InstallmentPlanLifecycleCoordinator,
    private val postCommitDispatcher: InstallmentPostCommitDispatcher,
    private val timeProvider: TimeProvider
) : ViewModel() {

    private val _state = MutableStateFlow(InstallmentPlanActionsUiState())
    val state: StateFlow<InstallmentPlanActionsUiState> = _state.asStateFlow()

    fun openEnd(context: InstallmentPlanActionContext) = open(InstallmentPlanDialogKind.END_CHOOSER, context)

    fun openDelete(context: InstallmentPlanActionContext) = open(InstallmentPlanDialogKind.DELETE, context)

    /** Restricted Unlink of one PAID entry (OQ-21): the dialog states the consequence first. */
    fun openUnlink(context: InstallmentPlanActionContext, entryId: Long, seq: Int) =
        open(InstallmentPlanDialogKind.UNLINK, context, entryId, seq)

    fun openEdit() = _state.update { it.copy(editOpen = true) }

    fun closeEdit() = _state.update { it.copy(editOpen = false) }

    private fun open(
        kind: InstallmentPlanDialogKind,
        context: InstallmentPlanActionContext,
        entryId: Long? = null,
        seq: Int = 0
    ) {
        if (_state.value.mutation is InstallmentPlanMutationState.InProgress) return
        val today = todayEpochDay()
        _state.update {
            it.copy(
                dialog = InstallmentPlanDialogState(
                    kind = kind,
                    context = context,
                    entryId = entryId,
                    entrySeq = seq,
                    payoffEpochDay = today,
                    todayEpochDay = today
                ),
                mutation = InstallmentPlanMutationState.Idle,
                showDiscardDialog = false
            )
        }
    }

    /** "Next" of the End plan chooser. An option the plan status does not allow is ignored. */
    fun pickEndOption(option: InstallmentEndOption) {
        val dialog = _state.value.dialog ?: return
        if (dialog.kind != InstallmentPlanDialogKind.END_CHOOSER) return
        val availability = InstallmentPlanAvailability.of(dialog.context)
        val next = when (option) {
            InstallmentEndOption.STOP -> InstallmentPlanDialogKind.STOP.takeIf { availability.stop }
            InstallmentEndOption.REFUND -> InstallmentPlanDialogKind.REFUND_QUESTION.takeIf { availability.refund }
            InstallmentEndOption.PAY_OFF -> InstallmentPlanDialogKind.PAY_OFF.takeIf { availability.payOff }
        } ?: return
        _state.update { it.copy(dialog = dialog.copy(kind = next), mutation = InstallmentPlanMutationState.Idle) }
    }

    /** Refund step 1 (AE-8): is the refund already recorded as a transaction? */
    fun answerRefundAlreadyRecorded(recorded: Boolean) {
        val dialog = _state.value.dialog ?: return
        if (dialog.kind != InstallmentPlanDialogKind.REFUND_QUESTION) return
        if (recorded && dialog.context.status == InstallmentPlanStatus.COMPLETED) {
            // F5: a COMPLETED plan cannot be stopped, so "then stop the plan" is not offered. Close with a
            // fixed notice; no coordinator operation runs.
            _state.update {
                it.copy(
                    dialog = null,
                    showDiscardDialog = false,
                    mutation = InstallmentPlanMutationState.Idle,
                    notice = InstallmentPlanNotice.NOTHING_TO_CHANGE
                )
            }
            return
        }
        val next = if (recorded) {
            InstallmentPlanDialogKind.REFUND_ALREADY_RECORDED
        } else {
            InstallmentPlanDialogKind.REFUND_CONFIRM
        }
        _state.update { it.copy(dialog = dialog.copy(kind = next)) }
    }

    fun setPayoffAmountText(text: String) = updateDialog { it.copy(payoffAmountText = text.take(MAX_AMOUNT_CHARS)) }

    fun setPayoffEpochDay(epochDay: Long) = updateDialog { it.copy(payoffEpochDay = epochDay) }

    private fun updateDialog(change: (InstallmentPlanDialogState) -> InstallmentPlanDialogState) {
        _state.update { current ->
            val dialog = current.dialog ?: return@update current
            if (current.mutation is InstallmentPlanMutationState.InProgress) return@update current
            current.copy(dialog = change(dialog), mutation = InstallmentPlanMutationState.Idle)
        }
    }

    /** Back or Cancel. An edited Pay off form asks first (discard dialog); nothing closes while running. */
    fun requestDismiss() {
        val current = _state.value
        val dialog = current.dialog ?: return
        if (current.mutation is InstallmentPlanMutationState.InProgress) return
        if (dialog.hasInput) {
            _state.value = current.copy(showDiscardDialog = true)
        } else {
            close()
        }
    }

    fun confirmDiscard() = close()

    fun cancelDiscard() {
        _state.update { it.copy(showDiscardDialog = false) }
    }

    private fun close() {
        _state.update { it.copy(dialog = null, showDiscardDialog = false, mutation = InstallmentPlanMutationState.Idle) }
    }

    fun consumeNotice() {
        _state.update { it.copy(notice = null) }
    }

    /** The Delete success was handled (the screen navigated back). */
    fun consumeMutation() {
        _state.update { it.copy(mutation = InstallmentPlanMutationState.Idle) }
    }

    /** The screen left composition: drops dialogs and the pending notice unless a mutation is running. */
    fun onScreenExit() {
        _state.update { current ->
            if (current.mutation is InstallmentPlanMutationState.InProgress) {
                current.copy(notice = null, showDiscardDialog = false, editOpen = false)
            } else {
                InstallmentPlanActionsUiState()
            }
        }
    }

    /** Runs the open dialog's confirmation: exactly one coordinator operation. */
    fun confirm() {
        val current = _state.value
        val dialog = current.dialog ?: return
        if (current.mutation is InstallmentPlanMutationState.InProgress || !dialog.canConfirm) return
        val planId = dialog.context.planId
        when (dialog.kind) {
            InstallmentPlanDialogKind.STOP -> run(InstallmentPlanOperation.STOP, InstallmentPlanNotice.STOPPED) {
                coordinator.stop(planId)
            }

            // "Then stop the plan instead" (AE-8): the coordinator runs the Stop body.
            InstallmentPlanDialogKind.REFUND_ALREADY_RECORDED ->
                run(InstallmentPlanOperation.REFUND, InstallmentPlanNotice.STOPPED) {
                    coordinator.refund(planId, refundAlreadyRecorded = true)
                }

            InstallmentPlanDialogKind.REFUND_CONFIRM ->
                run(InstallmentPlanOperation.REFUND, InstallmentPlanNotice.REFUNDED) {
                    coordinator.refund(planId, refundAlreadyRecorded = false)
                }

            InstallmentPlanDialogKind.PAY_OFF -> {
                val minor = dialog.payoffMinor ?: return
                // The default amount is sent as null so the coordinator uses the sum of the SCHEDULED entries.
                val explicit = minor != dialog.context.unpaidTotalMinor
                val amount = if (explicit) InstallmentAmountInput.toAmount(minor) else null
                run(InstallmentPlanOperation.PAY_OFF, InstallmentPlanNotice.PAID_OFF) {
                    val zone = ZoneId.systemDefault()
                    coordinator.payOff(planId, amount, dialog.payoffEpochDay, todayEpochDay(zone), zone)
                }
            }

            InstallmentPlanDialogKind.DELETE -> run(InstallmentPlanOperation.DELETE, null) {
                coordinator.delete(planId)
            }

            InstallmentPlanDialogKind.UNLINK -> {
                val entryId = dialog.entryId ?: return
                runEntry(InstallmentPlanOperation.UNLINK, InstallmentPlanNotice.UNLINKED) {
                    coordinator.unlinkExpense(entryId)
                }
            }

            InstallmentPlanDialogKind.END_CHOOSER, InstallmentPlanDialogKind.REFUND_QUESTION -> Unit
        }
    }

    private fun run(
        operation: InstallmentPlanOperation,
        notice: InstallmentPlanNotice?,
        call: suspend () -> InstallmentOpResult<com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPlanRef>
    ) = launchOp(operation, notice, { it.planId }, call)

    private fun runEntry(
        operation: InstallmentPlanOperation,
        notice: InstallmentPlanNotice?,
        call: suspend () -> InstallmentOpResult<com.yourname.expensetracker.domain.installment.lifecycle.InstallmentEntryRef>
    ) = launchOp(operation, notice, { it.planId }, call)

    private fun <T> launchOp(
        operation: InstallmentPlanOperation,
        notice: InstallmentPlanNotice?,
        planIdOf: (T) -> Long?,
        call: suspend () -> InstallmentOpResult<T>
    ) {
        _state.update { it.copy(mutation = InstallmentPlanMutationState.InProgress(operation), notice = null) }
        viewModelScope.launch {
            try {
                val outcome = executeInstallmentPlanOp(operation, postCommitDispatcher, planIdOf, call)
                _state.update {
                    if (outcome is InstallmentPlanMutationState.Success) {
                        it.copy(
                            dialog = null,
                            showDiscardDialog = false,
                            mutation = outcome,
                            // AlreadyInState is silent: no notice (ux-flows 3.6).
                            notice = if (outcome.alreadyInState) null else notice
                        )
                    } else {
                        it.copy(mutation = outcome)
                    }
                }
            } catch (e: CancellationException) {
                // A cancelled run must not leave the flow stuck InProgress (S10b-2 F3); then rethrow.
                _state.update {
                    if (it.mutation is InstallmentPlanMutationState.InProgress) {
                        it.copy(mutation = InstallmentPlanMutationState.Idle)
                    } else {
                        it
                    }
                }
                throw e
            }
        }
    }

    private fun todayEpochDay(zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(timeProvider.now()).atZone(zone).toLocalDate().toEpochDay()

    private companion object {
        const val MAX_AMOUNT_CHARS = 12
    }
}

// ---------------------------------------------------------------------- Compose

/** Top bar overflow of the plan detail: Edit plan, End plan..., Delete plan (only what the status allows). */
@Composable
fun InstallmentPlanActionsMenu(
    content: InstallmentPlanDetailContent,
    viewModel: InstallmentPlanActionsViewModel = hiltViewModel(key = "installment_plan_actions_${content.planId}")
) {
    val context = content.toActionContext()
    val availability = InstallmentPlanAvailability.of(context)
    if (!availability.edit && !availability.canEnd && !availability.delete) return
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.installments_plan_actions_menu))
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        if (availability.edit) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.installments_plan_edit)) },
                onClick = { expanded = false; viewModel.openEdit() }
            )
        }
        if (availability.canEnd) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.installments_plan_end)) },
                onClick = { expanded = false; viewModel.openEnd(context) }
            )
        }
        if (availability.delete) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.installments_plan_delete)) },
                onClick = { expanded = false; viewModel.openDelete(context) }
            )
        }
    }
}

/** Dialogs, edit overlay and notices of the plan level flows; leaves the screen after a Delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstallmentPlanActionsHost(
    content: InstallmentPlanDetailContent?,
    onNavigateBack: () -> Unit,
    snackbarHostState: SnackbarHostState,
    viewModel: InstallmentPlanActionsViewModel
) {
    val state by viewModel.state.collectAsState()
    val mutation = state.mutation
    LaunchedEffect(mutation) {
        if (mutation is InstallmentPlanMutationState.Success && mutation.operation == InstallmentPlanOperation.DELETE) {
            viewModel.consumeMutation()
            onNavigateBack()
        }
    }
    val noticeText = state.notice?.let { stringResource(noticeRes(it)) }
    LaunchedEffect(state.notice) {
        if (noticeText != null) {
            snackbarHostState.showSnackbar(noticeText)
            viewModel.consumeNotice()
        }
    }
    if (state.editOpen && content != null) {
        InstallmentEditScreen(planId = content.planId, onClose = viewModel::closeEdit)
    }
    val dialog = state.dialog ?: return
    val running = mutation is InstallmentPlanMutationState.InProgress
    val message = installmentPlanMutationMessage(mutation)?.let { stringResource(it) }
    val ctx = dialog.context
    when (dialog.kind) {
        InstallmentPlanDialogKind.END_CHOOSER -> EndChooser(ctx, viewModel)
        InstallmentPlanDialogKind.REFUND_QUESTION -> AlertDialog(
            onDismissRequest = viewModel::requestDismiss,
            title = { Text(stringResource(R.string.installments_refund_question_title)) },
            text = { Text(stringResource(R.string.installments_refund_question_body)) },
            confirmButton = {
                TextButton(onClick = { viewModel.answerRefundAlreadyRecorded(false) }) {
                    Text(stringResource(R.string.installments_answer_no))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.answerRefundAlreadyRecorded(true) }) {
                    Text(stringResource(R.string.installments_answer_yes))
                }
            }
        )
        else -> PlanConfirmDialog(dialog, running, message, viewModel)
    }
    if (state.showDiscardDialog) {
        AlertDialog(
            onDismissRequest = viewModel::cancelDiscard,
            title = { Text(stringResource(R.string.installments_action_discard_title)) },
            text = { Text(stringResource(R.string.installments_action_discard_body)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDiscard) { Text(stringResource(R.string.installments_action_discard)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelDiscard) { Text(stringResource(R.string.installments_action_cancel)) }
            }
        )
    }
}

@Composable
private fun EndChooser(ctx: InstallmentPlanActionContext, viewModel: InstallmentPlanActionsViewModel) {
    val availability = InstallmentPlanAvailability.of(ctx)
    var choice by remember { mutableStateOf<InstallmentEndOption?>(null) }
    AlertDialog(
        onDismissRequest = viewModel::requestDismiss,
        title = { Text(stringResource(R.string.installments_end_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Options the status does not allow are hidden (ux-flows 3.7).
                if (availability.stop) EndOptionRow(R.string.installments_end_stop_title, R.string.installments_end_stop_body, choice == InstallmentEndOption.STOP) { choice = InstallmentEndOption.STOP }
                if (availability.refund) EndOptionRow(R.string.installments_end_refund_title, R.string.installments_end_refund_body, choice == InstallmentEndOption.REFUND) { choice = InstallmentEndOption.REFUND }
                if (availability.payOff) EndOptionRow(R.string.installments_end_payoff_title, R.string.installments_end_payoff_body, choice == InstallmentEndOption.PAY_OFF) { choice = InstallmentEndOption.PAY_OFF }
            }
        },
        confirmButton = {
            TextButton(onClick = { choice?.let(viewModel::pickEndOption) }, enabled = choice != null) {
                Text(stringResource(R.string.installments_end_next))
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::requestDismiss) { Text(stringResource(R.string.installments_action_cancel)) }
        }
    )
}

@Composable
private fun EndOptionRow(title: Int, body: Int, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RadioButton(selected = selected, onClick = null)
        Column {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(body), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlanConfirmDialog(
    dialog: InstallmentPlanDialogState,
    running: Boolean,
    message: String?,
    viewModel: InstallmentPlanActionsViewModel
) {
    val ctx = dialog.context
    var pickingDate by remember { mutableStateOf(false) }
    val (title, confirmLabel) = when (dialog.kind) {
        InstallmentPlanDialogKind.STOP -> R.string.installments_stop_title to R.string.installments_stop_confirm
        InstallmentPlanDialogKind.REFUND_ALREADY_RECORDED ->
            R.string.installments_refund_recorded_title to R.string.installments_refund_recorded_confirm
        InstallmentPlanDialogKind.REFUND_CONFIRM -> R.string.installments_refund_confirm_title to R.string.installments_refund_confirm
        InstallmentPlanDialogKind.PAY_OFF -> R.string.installments_payoff_title to R.string.installments_payoff_confirm
        InstallmentPlanDialogKind.DELETE -> R.string.installments_delete_title to R.string.installments_delete_confirm
        else -> R.string.installments_unlink_title to R.string.installments_unlink_confirm
    }
    AlertDialog(
        onDismissRequest = viewModel::requestDismiss,
        title = {
            if (dialog.kind == InstallmentPlanDialogKind.UNLINK) {
                Text(stringResource(title, dialog.entrySeq))
            } else {
                Text(stringResource(title))
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (dialog.kind) {
                    InstallmentPlanDialogKind.STOP -> Text(
                        pluralStringResource(
                            R.plurals.installments_stop_body,
                            ctx.unpaidCount,
                            ctx.unpaidCount,
                            ctx.unpaidTotal.formatted,
                            ctx.paidCount
                        )
                    )
                    InstallmentPlanDialogKind.REFUND_ALREADY_RECORDED ->
                        Text(stringResource(R.string.installments_refund_recorded_body))
                    InstallmentPlanDialogKind.REFUND_CONFIRM ->
                        Text(stringResource(R.string.installments_refund_confirm_body, ctx.paidCount, ctx.unpaidCount))
                    InstallmentPlanDialogKind.PAY_OFF -> {
                        Text(pluralStringResource(R.plurals.installments_payoff_body, ctx.unpaidCount, ctx.unpaidCount))
                        OutlinedTextField(
                            value = dialog.payoffAmountText,
                            onValueChange = viewModel::setPayoffAmountText,
                            label = { Text(stringResource(R.string.installments_payoff_amount)) },
                            singleLine = true,
                            enabled = !running,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth()
                        )
                        TextButton(onClick = { pickingDate = true }, enabled = !running) {
                            Text(
                                stringResource(
                                    R.string.installments_payoff_date,
                                    com.yourname.expensetracker.ui.screens.installments.formatInstallmentDate(dialog.payoffEpochDay)
                                )
                            )
                        }
                    }
                    InstallmentPlanDialogKind.DELETE -> Text(
                        stringResource(
                            if (ctx.kind == InstallmentPlanKind.PURCHASE_ATTACHED) {
                                R.string.installments_delete_body_purchase
                            } else {
                                R.string.installments_delete_body_standalone
                            }
                        )
                    )
                    else -> Text(stringResource(R.string.installments_unlink_body))
                }
                if (message != null) Text(message, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::confirm, enabled = dialog.canConfirm && !running) {
                Text(stringResource(if (running) R.string.installments_action_working else confirmLabel))
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::requestDismiss, enabled = !running) {
                Text(stringResource(R.string.installments_action_cancel))
            }
        }
    )
    if (pickingDate) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = dialog.payoffEpochDay * MILLIS_PER_DAY)
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { viewModel.setPayoffEpochDay(it / MILLIS_PER_DAY) }
                    pickingDate = false
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { pickingDate = false }) { Text(stringResource(R.string.installments_action_cancel)) }
            }
        ) { DatePicker(state = pickerState) }
    }
}

private const val MILLIS_PER_DAY = 86_400_000L

private fun noticeRes(notice: InstallmentPlanNotice): Int = when (notice) {
    InstallmentPlanNotice.STOPPED -> R.string.installments_plan_notice_stopped
    InstallmentPlanNotice.REFUNDED -> R.string.installments_plan_notice_refunded
    InstallmentPlanNotice.PAID_OFF -> R.string.installments_plan_notice_paid_off
    InstallmentPlanNotice.UNLINKED -> R.string.installments_plan_notice_unlinked
    InstallmentPlanNotice.NOTHING_TO_CHANGE -> R.string.installments_plan_notice_nothing_to_change
}
