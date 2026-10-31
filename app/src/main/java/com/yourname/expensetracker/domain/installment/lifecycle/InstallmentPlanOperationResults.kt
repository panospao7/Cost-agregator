package com.yourname.expensetracker.domain.installment.lifecycle

import com.yourname.expensetracker.domain.installment.model.InstallmentEntryStatus
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanStatus
import com.yourname.expensetracker.domain.installment.model.InstallmentRefusalCode
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionBatch
import com.yourname.expensetracker.domain.transaction.RateRefusalReason

/**
 * Bounded failures of a plan coordinator operation that are not input refusals (domain spec 3.1,
 * 3.2: a lost compare-and-set, a missing row, a constraint hit, a refused expense create).
 * Only the enum name may be logged or shown; never a message, id, amount or text.
 */
enum class InstallmentOpFailure {
    PLAN_NOT_FOUND,
    ENTRY_NOT_FOUND,
    EXPENSE_NOT_FOUND,

    /** A compare-and-set lost the race or found the row in another state; the transaction rolled back. */
    CONFLICT,

    /** A unique or foreign key constraint rejected a write; the transaction rolled back. */
    CONSTRAINT_VIOLATION,

    /** declareWithNewExpense: the purchase create was a duplicate of an existing expense. Nothing is adopted. */
    PURCHASE_DUPLICATE_SKIPPED,

    /** declareWithNewExpense: the purchase insert conflicted on a unique key. */
    PURCHASE_INSERT_CONFLICT,

    /** declareWithNewExpense: the purchase create failed validation or errored (not a rate refusal). */
    PURCHASE_CREATE_FAILED,

    /** confirm: the installment expense create failed validation or errored. */
    RECORD_FAILED,

    /** confirm: the STRICT key already exists and the row does not belong to this entry. */
    RECORD_DUPLICATE_NOT_ADOPTABLE,

    /** confirm: the installment expense insert conflicted on a unique key. */
    RECORD_INSERT_CONFLICT
}

/**
 * Outcome of one plan coordinator operation. A refusal or failure writes no plan, entry or expense
 * row (TLC's own audit events may still commit, see [Refused] and [Failed]). Post-commit
 * batches (from TLC) are returned in [Done]; the caller dispatches them only after the
 * coordinator transaction has committed, never inside it.
 */
sealed interface InstallmentOpResult<out T> {

    /** The operation applied. [postCommit] holds the batches TLC planned inside the transaction. */
    data class Done<T>(
        val value: T,
        val postCommit: List<PostCommitActionBatch> = emptyList()
    ) : InstallmentOpResult<T>

    /** The target state already holds; callers treat this as success. Nothing was written. */
    data class AlreadyInState<T>(val value: T) : InstallmentOpResult<T>

    /**
     * Refused with a bounded code. No plan, entry or expense row is written; TLC's own audit
     * events may commit (RateRefused, PURCHASE_DUPLICATE_SKIPPED).
     */
    data class Refused(val code: InstallmentRefusalCode) : InstallmentOpResult<Nothing>

    /**
     * declareWithNewExpense: TLC refused the flagged purchase create for rate reasons (MEC INV-18).
     * No plan, entry or expense row is written; TLC's own audit events may commit (RateRefused).
     * Empty batch.
     */
    data class RateRefused(val reason: RateRefusalReason) : InstallmentOpResult<Nothing>

    /**
     * A bounded non-input failure. No plan, entry or expense row is written; TLC's own audit
     * events may commit (PURCHASE_DUPLICATE_SKIPPED).
     */
    data class Failed(val failure: InstallmentOpFailure) : InstallmentOpResult<Nothing>
}

/** The plan a declare or edit operation produced. */
data class InstallmentPlanRef(
    val planId: Long,
    val planStatus: InstallmentPlanStatus
)

/** The entry an entry operation acted on, with the plan status after the operation. */
data class InstallmentEntryRef(
    val entryId: Long,
    val planId: Long,
    val entryStatus: InstallmentEntryStatus,
    val planStatus: InstallmentPlanStatus,
    val linkedExpenseId: Long?
)

/**
 * Integer counts per reconcile rule (domain spec 5.4). This is the only diagnostic the reconcile
 * emits: no ids, names, amounts or dates. The post-commit batches of the flag repairs are carried
 * only by [InstallmentOpResult.Done.postCommit], never here.
 *
 * @property orphanFlagsCleared installment flags on expenses that no plan or entry references,
 *   cleared by the orphan scan (the second halves of R1 and R3).
 * @property plansFailed plans whose repair transaction failed, was refused or threw an unmapped
 *   exception and rolled back; the reconcile continues with the next plan.
 * @property orphanFlagsFailed orphan-flag candidates whose clearing transaction failed, was refused
 *   or threw an unmapped exception and rolled back; the scan continues with the next candidate.
 */
data class InstallmentReconcileReport(
    val r1Reflagged: Int = 0,
    val r2Reopened: Int = 0,
    val r3Reflagged: Int = 0,
    val r4Unlinked: Int = 0,
    val r5Detached: Int = 0,
    val r6Completed: Int = 0,
    val orphanFlagsCleared: Int = 0,
    val plansFailed: Int = 0,
    val orphanFlagsFailed: Int = 0
) {
    operator fun plus(other: InstallmentReconcileReport): InstallmentReconcileReport =
        InstallmentReconcileReport(
            r1Reflagged + other.r1Reflagged,
            r2Reopened + other.r2Reopened,
            r3Reflagged + other.r3Reflagged,
            r4Unlinked + other.r4Unlinked,
            r5Detached + other.r5Detached,
            r6Completed + other.r6Completed,
            orphanFlagsCleared + other.orphanFlagsCleared,
            plansFailed + other.plansFailed,
            orphanFlagsFailed + other.orphanFlagsFailed
        )
}
