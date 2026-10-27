package com.yourname.expensetracker.ui.screens.installments.actions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentEntryRef
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentOpResult
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPlanLifecycleCoordinator
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanStatus
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionBatch
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunner
import com.yourname.expensetracker.domain.util.TimeProvider
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
 * Entry row actions of the installment plan detail (loan-installments S10b-1, ux-flows 3.6: Record,
 * Link, Skip, Adjust, Unskip, and the row Undo of Skip, Link and Adjust). The domain-spec AE-7, AE-8,
 * AE-9 (Stop, Refund, Early payoff) and AE-14 (Delete = undo) are S10b-2, not this class.
 *
 * ## Screen exit
 * [onScreenExit] ends the screen's session: a pending notice (and its Undo) never replays after
 * re-entry, and an idle sheet is dropped unless the exit is a configuration change.
 *
 * ## Legal path
 * Every action calls exactly one [InstallmentPlanLifecycleCoordinator] operation; this class never
 * touches a DAO, the transaction lifecycle coordinator or a repository write (INV-24, INV-25).
 * Post-commit batches returned by the coordinator are dispatched after it returns, never inside it.
 *
 * ## Diagnostics and messages
 * Only the [InstallmentOperation] name and a bounded refusal or failure code reach the log and the
 * UI. An exception message, id, amount or date is never shown or logged (INV-31).
 * `CancellationException` is always rethrown.
 */
@HiltViewModel
class InstallmentEntryActionsViewModel @Inject constructor(
    private val coordinator: InstallmentPlanLifecycleCoordinator,
    private val postCommitActionRunner: PostCommitActionRunner,
    private val timeProvider: TimeProvider
) : ViewModel() {

    private val _state = MutableStateFlow(InstallmentActionsUiState())
    val state: StateFlow<InstallmentActionsUiState> = _state.asStateFlow()

    // ------------------------------------------------------------------ sheet input

    /**
     * True after a real screen exit (not a configuration change) until the user opens a sheet again.
     * An action that finishes while detached attaches no notice, so no Undo can replay on re-entry.
     */
    @Volatile
    private var detached = false

    /**
     * The screen left composition. Always drops the pending notice and discard dialog. Unless this is
     * a configuration change and unless an action is running, it also drops the sheet and resets the
     * mutation to Idle. A running action keeps its state; it finishes without a notice.
     */
    fun onScreenExit(changingConfigurations: Boolean) {
        if (!changingConfigurations) detached = true
        _state.update { current ->
            val reset = !changingConfigurations && current.mutation !is InstallmentMutationState.InProgress
            current.copy(
                sheet = if (reset) null else current.sheet,
                mutation = if (reset) InstallmentMutationState.Idle else current.mutation,
                showDiscardDialog = false,
                notice = null
            )
        }
    }

    /** Opens the sheet of [kind] for [target]. Ignored while an action is running. */
    fun open(kind: InstallmentEntryActionKind, target: InstallmentActionTarget) {
        if (_state.value.mutation is InstallmentMutationState.InProgress) return
        detached = false
        _state.value = _state.value.copy(
            sheet = InstallmentActionSheetState(kind = kind, target = target),
            mutation = InstallmentMutationState.Idle,
            showDiscardDialog = false
        )
    }

    fun setSkipReason(reason: InstallmentSkipReasonUi) = updateSheet { it.copy(skipReason = reason) }

    fun setAmountText(text: String) = updateSheet { it.copy(amountText = text.take(MAX_AMOUNT_CHARS)) }

    fun setDueEpochDay(epochDay: Long) = updateSheet { it.copy(dueEpochDay = epochDay) }

    fun selectExpense(expenseId: Long) = updateSheet { it.copy(selectedExpenseId = expenseId) }

    private fun updateSheet(change: (InstallmentActionSheetState) -> InstallmentActionSheetState) {
        _state.update { current ->
            val sheet = current.sheet ?: return@update current
            if (current.mutation is InstallmentMutationState.InProgress) return@update current
            // Editing after a refusal clears it so the user sees the field is live again.
            current.copy(sheet = change(sheet), mutation = InstallmentMutationState.Idle)
        }
    }

    /**
     * Back, swipe or Cancel on the sheet. A sheet with input asks first (discard dialog); a sheet
     * without input closes. Nothing closes while an action runs.
     */
    fun requestDismiss() {
        val current = _state.value
        val sheet = current.sheet ?: return
        if (current.mutation is InstallmentMutationState.InProgress) return
        if (sheet.hasInput) {
            _state.value = current.copy(showDiscardDialog = true)
        } else {
            closeSheet()
        }
    }

    fun confirmDiscard() = closeSheet()

    fun cancelDiscard() {
        _state.update { it.copy(showDiscardDialog = false) }
    }

    private fun closeSheet() {
        _state.update {
            it.copy(sheet = null, showDiscardDialog = false, mutation = InstallmentMutationState.Idle)
        }
    }

    /** The snackbar was shown or dismissed. */
    fun consumeNotice() {
        _state.update { it.copy(notice = null) }
    }

    // ------------------------------------------------------------------ submit

    /** Runs the open sheet's action: exactly one coordinator operation. */
    fun submit() {
        val current = _state.value
        val sheet = current.sheet ?: return
        if (current.mutation is InstallmentMutationState.InProgress || !sheet.canSubmit) return
        val entryId = sheet.target.entryId
        when (sheet.kind) {
            InstallmentEntryActionKind.RECORD -> run(InstallmentOperation.RECORD, closeSheetOnSuccess = true) {
                val zone = ZoneId.systemDefault()
                coordinator.confirmEntry(entryId, todayEpochDay(zone), zone)
            }

            InstallmentEntryActionKind.LINK -> {
                val expenseId = sheet.selectedExpenseId ?: return
                run(InstallmentOperation.LINK, closeSheetOnSuccess = true, undoOnDone = InstallmentUndo.Unlink(entryId)) {
                    coordinator.linkExpense(entryId, expenseId)
                }
            }

            InstallmentEntryActionKind.SKIP -> {
                val reason = sheet.skipReason ?: return
                run(InstallmentOperation.SKIP, closeSheetOnSuccess = true, undoOnDone = InstallmentUndo.Unskip(entryId)) {
                    coordinator.skip(entryId, reason.reason)
                }
            }

            InstallmentEntryActionKind.UNSKIP -> run(InstallmentOperation.UNSKIP, closeSheetOnSuccess = true) {
                coordinator.unskip(entryId)
            }

            InstallmentEntryActionKind.ADJUST -> {
                val minor = sheet.amountMinor ?: return
                val amountChanged = minor != sheet.target.scheduledMinor
                val dateChanged = sheet.dueEpochDay != sheet.target.dueEpochDay
                if (!amountChanged && !dateChanged) return
                val undo = InstallmentUndo.RestoreSchedule(
                    entryId = entryId,
                    previousAmount = if (amountChanged) InstallmentAmountInput.toAmount(sheet.target.scheduledMinor) else null,
                    previousDueEpochDay = if (dateChanged) sheet.target.dueEpochDay else null
                )
                run(InstallmentOperation.ADJUST, closeSheetOnSuccess = true, undoOnDone = undo) {
                    coordinator.adjustEntry(
                        entryId,
                        if (amountChanged) InstallmentAmountInput.toAmount(minor) else null,
                        if (dateChanged) sheet.dueEpochDay else null
                    )
                }
            }
        }
    }

    /** Runs the inverse of the last clean action (ux-flows 3.6). One coordinator operation, no further undo. */
    fun undo(undo: InstallmentUndo) {
        if (_state.value.mutation is InstallmentMutationState.InProgress) return
        when (undo) {
            is InstallmentUndo.Unskip -> run(InstallmentOperation.UNDO_SKIP, isUndo = true) {
                coordinator.unskip(undo.entryId)
            }

            is InstallmentUndo.Unlink -> run(InstallmentOperation.UNDO_LINK, isUndo = true) {
                coordinator.unlinkExpense(undo.entryId)
            }

            is InstallmentUndo.RestoreSchedule -> run(InstallmentOperation.UNDO_ADJUST, isUndo = true) {
                coordinator.adjustEntry(undo.entryId, undo.previousAmount, undo.previousDueEpochDay)
            }
        }
    }

    private fun run(
        operation: InstallmentOperation,
        closeSheetOnSuccess: Boolean = false,
        isUndo: Boolean = false,
        undoOnDone: InstallmentUndo? = null,
        call: suspend () -> InstallmentOpResult<InstallmentEntryRef>
    ) {
        _state.update { it.copy(mutation = InstallmentMutationState.InProgress(operation), notice = null) }
        viewModelScope.launch {
            val result: InstallmentOpResult<InstallmentEntryRef>? = try {
                call()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Bounded operation name only: no exception text, ids, amounts or dates (INV-31).
                Timber.w("Installment action failed unexpectedly: op=%s", operation.name)
                null
            }
            if (result == null) {
                finish(InstallmentMutationState.Failed(operation, InstallmentActionFailureCode.UNEXPECTED), isUndo)
                return@launch
            }
            when (result) {
                is InstallmentOpResult.Done -> {
                    // The coordinator transaction has committed: dispatch its side effects now.
                    dispatchBatches(result.postCommit)
                    onSuccess(operation, result.value, alreadyInState = false, closeSheetOnSuccess, isUndo, undoOnDone)
                }

                is InstallmentOpResult.AlreadyInState ->
                    onSuccess(operation, result.value, alreadyInState = true, closeSheetOnSuccess, isUndo, null)

                is InstallmentOpResult.Refused -> {
                    Timber.w("Installment action refused: op=%s code=%s", operation.name, result.code.name)
                    finish(InstallmentMutationState.Refused(operation, result.code), isUndo)
                }

                is InstallmentOpResult.Failed -> {
                    val code = InstallmentActionMessages.failureCode(result.failure)
                    Timber.w("Installment action failed: op=%s failure=%s", operation.name, result.failure.name)
                    finish(InstallmentMutationState.Failed(operation, code), isUndo)
                }

                // Entry operations never return a rate refusal; treat it as a bounded failure.
                is InstallmentOpResult.RateRefused ->
                    finish(InstallmentMutationState.Failed(operation, InstallmentActionFailureCode.UNEXPECTED), isUndo)
            }
        }
    }

    private fun onSuccess(
        operation: InstallmentOperation,
        ref: InstallmentEntryRef,
        alreadyInState: Boolean,
        closeSheetOnSuccess: Boolean,
        isUndo: Boolean,
        undo: InstallmentUndo?
    ) {
        // AlreadyInState is a silent success (ux-flows 3.6): no snackbar and no undo.
        val notice: InstallmentActionNotice? = when {
            alreadyInState -> null
            isUndo -> InstallmentActionNotice.UNDONE
            else -> when (operation) {
                InstallmentOperation.RECORD -> InstallmentActionNotice.RECORDED
                InstallmentOperation.LINK -> InstallmentActionNotice.LINKED
                // INV-21: skipping the last open payment completes the plan in the same step.
                InstallmentOperation.SKIP ->
                    if (ref.planStatus == InstallmentPlanStatus.COMPLETED) {
                        InstallmentActionNotice.SKIPPED_LAST_PAYMENT
                    } else {
                        InstallmentActionNotice.SKIPPED
                    }
                InstallmentOperation.ADJUST -> InstallmentActionNotice.ADJUSTED
                InstallmentOperation.UNSKIP -> InstallmentActionNotice.UNSKIPPED
                InstallmentOperation.UNDO_SKIP,
                InstallmentOperation.UNDO_LINK,
                InstallmentOperation.UNDO_ADJUST -> InstallmentActionNotice.UNDONE
            }
        }
        _state.update {
            it.copy(
                sheet = if (closeSheetOnSuccess) null else it.sheet,
                showDiscardDialog = false,
                mutation = InstallmentMutationState.Success(operation, alreadyInState),
                notice = if (notice == null || detached) null else InstallmentActionNoticeUi(notice, undo)
            )
        }
    }

    private fun finish(mutation: InstallmentMutationState, isUndo: Boolean) {
        _state.update {
            it.copy(
                mutation = mutation,
                // An undo has no sheet to show the failure in: surface a fixed snackbar instead.
                notice = if (isUndo && !detached) InstallmentActionNoticeUi(InstallmentActionNotice.UNDO_FAILED) else null
            )
        }
    }

    /**
     * Runs each batch in order; one failing batch does not stop the rest and never changes the
     * result of the committed operation. Cancellation is rethrown.
     */
    private suspend fun dispatchBatches(batches: List<PostCommitActionBatch>) {
        for (batch in batches) {
            if (batch.actions.isEmpty()) continue
            try {
                postCommitActionRunner.run(batch)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.w("Installment post-commit dispatch failed: class=%s", e::class.java.simpleName)
            }
        }
    }

    private fun todayEpochDay(zone: ZoneId): Long =
        Instant.ofEpochMilli(timeProvider.now()).atZone(zone).toLocalDate().toEpochDay()

    private companion object {
        const val MAX_AMOUNT_CHARS = 12
    }
}
