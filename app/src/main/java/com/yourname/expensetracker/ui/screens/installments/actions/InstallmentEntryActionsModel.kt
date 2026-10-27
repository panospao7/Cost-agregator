package com.yourname.expensetracker.ui.screens.installments.actions

import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentOpFailure
import com.yourname.expensetracker.domain.installment.model.InstallmentMinorUnits
import com.yourname.expensetracker.domain.installment.model.InstallmentRefusalCode
import com.yourname.expensetracker.domain.installment.model.InstallmentStatusReason
import com.yourname.expensetracker.ui.model.MoneyDisplayUi
import java.math.BigDecimal

/**
 * Entry row actions of the plan detail (loan-installments S10b-1, ux-flows 3.6). Every action reaches
 * exactly one coordinator operation. Delete, create, edit and end flows (domain-spec AE-7 Stop,
 * AE-8 Refund, AE-9 Early payoff, AE-14 Delete = undo) are S10b-2.
 */
enum class InstallmentEntryActionKind { RECORD, LINK, SKIP, ADJUST, UNSKIP }

/** The bounded skip reasons offered to the user (OQ-20); no free text. */
enum class InstallmentSkipReasonUi(val reason: InstallmentStatusReason) {
    LENDER_HOLIDAY(InstallmentStatusReason.USER_SKIP_LENDER_HOLIDAY),
    WAIVED(InstallmentStatusReason.USER_SKIP_WAIVED),
    OTHER(InstallmentStatusReason.USER_SKIP_OTHER)
}

/** An unlinked expense that matches an entry (INV-13). Carries ids and display values only. */
data class InstallmentLinkSuggestionUi(
    val expenseId: Long,
    val amount: MoneyDisplayUi,
    val dateEpochDay: Long,
    /** Expense gross minus the scheduled amount; zero means an exact match. */
    val deltaMinor: Long
)

/** What a sheet needs to know about the entry it acts on; built from a detail row. */
data class InstallmentActionTarget(
    val entryId: Long,
    val seq: Int,
    val dueEpochDay: Long,
    val scheduledMinor: Long,
    val currencyCode: String,
    val amount: MoneyDisplayUi,
    val suggestions: List<InstallmentLinkSuggestionUi>
)

/** The coordinator operation a mutation runs; also the operation named in diagnostics. */
enum class InstallmentOperation { RECORD, LINK, SKIP, ADJUST, UNSKIP, UNDO_SKIP, UNDO_LINK, UNDO_ADJUST }

/** Bounded non-input failure codes of an action (INV-31). The text comes from fixed strings. */
enum class InstallmentActionFailureCode { CONFLICT, NOT_FOUND, RECORD_FAILED, UNEXPECTED }

/** State of the running action: idle / in progress / success / refused(code) / failed(code). */
sealed interface InstallmentMutationState {
    object Idle : InstallmentMutationState
    data class InProgress(val operation: InstallmentOperation) : InstallmentMutationState
    data class Success(val operation: InstallmentOperation, val alreadyInState: Boolean) : InstallmentMutationState
    data class Refused(val operation: InstallmentOperation, val code: InstallmentRefusalCode) : InstallmentMutationState
    data class Failed(val operation: InstallmentOperation, val code: InstallmentActionFailureCode) : InstallmentMutationState
}

/**
 * The snackbar notice after an action. Fixed strings only. `AlreadyInState` is a silent success
 * (ux-flows 3.6) and has no notice.
 */
enum class InstallmentActionNotice {
    RECORDED,
    LINKED,
    SKIPPED,
    SKIPPED_LAST_PAYMENT,
    ADJUSTED,
    UNSKIPPED,
    UNDONE,
    UNDO_FAILED
}

/**
 * The row Undo of a clean action (ux-flows 3.6): Skip -> unskip, Link -> unlink, Adjust -> restore.
 * This is not the domain-spec AE-14 (Delete = undo), which belongs to S10b-2.
 */
sealed interface InstallmentUndo {
    val entryId: Long

    data class Unskip(override val entryId: Long) : InstallmentUndo
    data class Unlink(override val entryId: Long) : InstallmentUndo

    /** A null field was not changed by the adjust and is not touched by the undo. */
    data class RestoreSchedule(
        override val entryId: Long,
        val previousAmount: Double?,
        val previousDueEpochDay: Long?
    ) : InstallmentUndo
}

/** Notice plus its optional undo, shown once in a snackbar. */
data class InstallmentActionNoticeUi(
    val notice: InstallmentActionNotice,
    val undo: InstallmentUndo? = null
)

/** The open sheet and its input. [hasInput] drives the discard dialog. */
data class InstallmentActionSheetState(
    val kind: InstallmentEntryActionKind,
    val target: InstallmentActionTarget,
    val skipReason: InstallmentSkipReasonUi? = null,
    val amountText: String = InstallmentAmountInput.formatMinor(target.scheduledMinor),
    val dueEpochDay: Long = target.dueEpochDay,
    val selectedExpenseId: Long? = null
) {
    val hasInput: Boolean
        get() = when (kind) {
            InstallmentEntryActionKind.RECORD, InstallmentEntryActionKind.UNSKIP -> false
            InstallmentEntryActionKind.SKIP -> skipReason != null
            InstallmentEntryActionKind.LINK -> selectedExpenseId != null
            InstallmentEntryActionKind.ADJUST ->
                amountText != InstallmentAmountInput.formatMinor(target.scheduledMinor) ||
                    dueEpochDay != target.dueEpochDay
        }

    /** Parsed amount in minor units, or null when the text is not a valid positive amount. */
    val amountMinor: Long? get() = InstallmentAmountInput.parseMinor(amountText)

    /** Whether the primary button can run. */
    val canSubmit: Boolean
        get() = when (kind) {
            InstallmentEntryActionKind.RECORD, InstallmentEntryActionKind.UNSKIP -> true
            InstallmentEntryActionKind.SKIP -> skipReason != null
            InstallmentEntryActionKind.LINK -> selectedExpenseId != null
            InstallmentEntryActionKind.ADJUST -> amountMinor != null && hasInput
        }
}

data class InstallmentActionsUiState(
    val sheet: InstallmentActionSheetState? = null,
    val mutation: InstallmentMutationState = InstallmentMutationState.Idle,
    val showDiscardDialog: Boolean = false,
    val notice: InstallmentActionNoticeUi? = null
)

/** Amount text input for Adjust. Strict: at most 2 decimals, positive, no silent rounding. */
internal object InstallmentAmountInput {
    private val pattern = Regex("^\\d{1,9}([.]\\d{1,2})?$")

    fun parseMinor(text: String): Long? {
        val normalized = text.trim().replace(',', '.')
        if (!pattern.matches(normalized)) return null
        val minor = BigDecimal(normalized).movePointRight(2).toLong()
        return minor.takeIf { it > 0L }
    }

    fun formatMinor(minor: Long): String = BigDecimal.valueOf(minor, 2).toPlainString()

    fun toAmount(minor: Long): Double = InstallmentMinorUnits.fromMinor(minor)
}

/**
 * Total mappings from bounded codes to fixed string resources (INV-31). An exception message never
 * reaches the UI; a code with no specific copy shows the generic refusal text.
 */
object InstallmentActionMessages {

    fun refusalText(code: InstallmentRefusalCode): Int = when (code) {
        // ux-flows 3.6: a different current state reads "already updated" (the detail refreshes by itself).
        InstallmentRefusalCode.ILLEGAL_TRANSITION -> R.string.installments_action_refused_already_updated
        InstallmentRefusalCode.PLAN_HAS_PAYMENTS -> R.string.installments_action_refused_illegal
        InstallmentRefusalCode.INVALID_AMOUNT,
        InstallmentRefusalCode.AMOUNT_TOO_SMALL_FOR_COUNT -> R.string.installments_action_refused_amount
        InstallmentRefusalCode.INVALID_DATE -> R.string.installments_action_refused_date
        InstallmentRefusalCode.CURRENCY_MISMATCH -> R.string.installments_action_refused_currency
        InstallmentRefusalCode.LINKED_ELSEWHERE,
        InstallmentRefusalCode.ALREADY_DECLARED -> R.string.installments_action_refused_linked_elsewhere
        InstallmentRefusalCode.NOT_A_SPENDING_EXPENSE,
        InstallmentRefusalCode.NOT_MINE,
        InstallmentRefusalCode.SHARED_NOT_SUPPORTED,
        InstallmentRefusalCode.SPLIT_NOT_SUPPORTED -> R.string.installments_action_refused_expense_not_eligible
        InstallmentRefusalCode.PLAN_NAME_REQUIRED -> R.string.installments_action_refused_name_required
        InstallmentRefusalCode.PURCHASE_NOT_FOUND -> R.string.installments_action_refused_purchase_missing
        InstallmentRefusalCode.INVALID_COUNT,
        InstallmentRefusalCode.UNSUPPORTED_CURRENCY,
        InstallmentRefusalCode.FIRST_DUE_BEFORE_PURCHASE,
        InstallmentRefusalCode.INVALID_PAID_BEFORE_TRACKING,
        InstallmentRefusalCode.CATEGORY_REQUIRED,
        InstallmentRefusalCode.CATEGORY_NOT_FOUND,
        InstallmentRefusalCode.CURRENCY_LOCKED,
        InstallmentRefusalCode.INVALID_VALUE -> R.string.installments_action_refused_generic
    }

    fun failureText(code: InstallmentActionFailureCode): Int = when (code) {
        InstallmentActionFailureCode.CONFLICT -> R.string.installments_action_failed_conflict
        InstallmentActionFailureCode.NOT_FOUND -> R.string.installments_action_failed_not_found
        InstallmentActionFailureCode.RECORD_FAILED -> R.string.installments_action_failed_record
        InstallmentActionFailureCode.UNEXPECTED -> R.string.installments_action_failed_unexpected
    }

    fun failureCode(failure: InstallmentOpFailure): InstallmentActionFailureCode = when (failure) {
        InstallmentOpFailure.CONFLICT,
        InstallmentOpFailure.CONSTRAINT_VIOLATION -> InstallmentActionFailureCode.CONFLICT
        InstallmentOpFailure.PLAN_NOT_FOUND,
        InstallmentOpFailure.ENTRY_NOT_FOUND,
        InstallmentOpFailure.EXPENSE_NOT_FOUND -> InstallmentActionFailureCode.NOT_FOUND
        InstallmentOpFailure.RECORD_FAILED,
        InstallmentOpFailure.RECORD_DUPLICATE_NOT_ADOPTABLE,
        InstallmentOpFailure.RECORD_INSERT_CONFLICT -> InstallmentActionFailureCode.RECORD_FAILED
        InstallmentOpFailure.PURCHASE_DUPLICATE_SKIPPED,
        InstallmentOpFailure.PURCHASE_INSERT_CONFLICT,
        InstallmentOpFailure.PURCHASE_CREATE_FAILED -> InstallmentActionFailureCode.UNEXPECTED
    }

    fun noticeText(notice: InstallmentActionNotice): Int = when (notice) {
        InstallmentActionNotice.RECORDED -> R.string.installments_action_notice_recorded
        InstallmentActionNotice.LINKED -> R.string.installments_action_notice_linked
        InstallmentActionNotice.SKIPPED -> R.string.installments_action_notice_skipped
        InstallmentActionNotice.SKIPPED_LAST_PAYMENT -> R.string.installments_action_notice_skipped_last
        InstallmentActionNotice.ADJUSTED -> R.string.installments_action_notice_adjusted
        InstallmentActionNotice.UNSKIPPED -> R.string.installments_action_notice_unskipped
        InstallmentActionNotice.UNDONE -> R.string.installments_action_notice_undone
        InstallmentActionNotice.UNDO_FAILED -> R.string.installments_action_notice_undo_failed
    }
}
