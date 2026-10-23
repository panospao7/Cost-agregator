package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.yourname.expensetracker.data.database.entity.InstallmentEntry
import com.yourname.expensetracker.data.database.entity.InstallmentPlan

/**
 * Loan installments (schema 152): one DAO for `installment_plans` and
 * `installment_entries`.
 *
 * ## Ownership
 * Every write method here is reserved for the installment plan lifecycle coordinator
 * (added in a later slice, which also adds the db_ownership_policy rows). Nothing
 * calls this DAO yet. Reads are free for the read-only projection port.
 *
 * ## Compare-and-set writes
 * State transitions are compare-and-set on the current status
 * (domain spec 3.1 / 3.2): each `UPDATE` carries the expected status in its `WHERE`
 * clause and returns the affected row count. `1` = applied, `0` = the row was not in
 * the expected state (already moved, or missing), so the coordinator reports
 * AlreadyInState / IllegalTransition after re-reading. Callers run these inside the
 * coordinator's `writeBarrier.runWrite { database.withTransaction { ... } }`.
 *
 * ## Method naming
 * Write methods deliberately start with a verb from the DB access guard's mutation
 * grammar (insert / update / claim / release / delete) so that call sites are
 * detected by `verify_db_access_boundaries.py`. Do not rename them to a non-verb
 * prefix.
 *
 * ## Constraints surfaced to callers
 * Plain `@Insert` (ABORT): the unique indexes on `planUuid`, `purchaseExpenseId`
 * (INV-3), `(planId, kind, seq)` and `linkedExpenseId` (INV-4) raise a constraint
 * exception inside the transaction instead of being silently ignored.
 */
@Dao
interface InstallmentPlanDao {

    // ── Plan reads ───────────────────────────────────────────────

    @Query("SELECT * FROM installment_plans WHERE id = :planId LIMIT 1")
    suspend fun getPlanById(planId: Long): InstallmentPlan?

    @Query("SELECT * FROM installment_plans WHERE planUuid = :planUuid LIMIT 1")
    suspend fun getPlanByUuid(planUuid: String): InstallmentPlan?

    @Query("SELECT * FROM installment_plans WHERE purchaseExpenseId = :expenseId LIMIT 1")
    suspend fun getPlanByPurchaseExpenseId(expenseId: Long): InstallmentPlan?

    @Query("SELECT * FROM installment_plans ORDER BY createdAt ASC, id ASC")
    suspend fun getAllPlans(): List<InstallmentPlan>

    @Query("SELECT * FROM installment_plans WHERE status = :status ORDER BY createdAt ASC, id ASC")
    suspend fun getPlansByStatus(status: String): List<InstallmentPlan>

    // ── Entry reads ──────────────────────────────────────────────

    @Query("SELECT * FROM installment_entries WHERE id = :entryId LIMIT 1")
    suspend fun getEntryById(entryId: Long): InstallmentEntry?

    @Query("SELECT * FROM installment_entries WHERE planId = :planId ORDER BY dueDate ASC, seq ASC, id ASC")
    suspend fun getEntriesForPlan(planId: Long): List<InstallmentEntry>

    @Query("SELECT * FROM installment_entries WHERE linkedExpenseId = :expenseId LIMIT 1")
    suspend fun getEntryByLinkedExpenseId(expenseId: Long): InstallmentEntry?

    /** Entries in [status] due on or before [dueOnOrBefore] (epochDay), oldest first. */
    @Query("SELECT * FROM installment_entries WHERE status = :status AND dueDate <= :dueOnOrBefore ORDER BY dueDate ASC, id ASC")
    suspend fun getEntriesByStatusDueOnOrBefore(status: String, dueOnOrBefore: Long): List<InstallmentEntry>

    @Query("SELECT COUNT(*) FROM installment_entries WHERE planId = :planId AND status = :status")
    suspend fun countEntriesForPlanByStatus(planId: Long, status: String): Int

    // ── Plan writes (coordinator only) ───────────────────────────

    @Insert
    suspend fun insertPlan(plan: InstallmentPlan): Long

    /**
     * Plan status transition, compare-and-set on [expectedStatus]. [statusReason] is a
     * bounded code or null. [terminalAt] is set for terminal states and null otherwise.
     * Returns affected rows (1 = applied, 0 = not in the expected state).
     */
    @Query("""
        UPDATE installment_plans
        SET status = :newStatus,
            statusReason = :statusReason,
            terminalAt = :terminalAt,
            updatedAt = :now
        WHERE id = :planId
          AND status = :expectedStatus
    """)
    suspend fun updatePlanStatusIfCurrent(
        planId: Long,
        expectedStatus: String,
        newStatus: String,
        statusReason: String?,
        terminalAt: Long?,
        now: Long
    ): Int

    /** Removes the plan; its entries go with it through the `ON DELETE CASCADE` foreign key. */
    @Query("DELETE FROM installment_plans WHERE id = :planId")
    suspend fun deletePlanById(planId: Long): Int

    // ── Entry writes (coordinator only) ──────────────────────────

    @Insert
    suspend fun insertEntries(entries: List<InstallmentEntry>): List<Long>

    /**
     * Entry status transition that carries no expense link (skip, unskip, cancel,
     * paid-before-tracking and back), compare-and-set on [expectedStatus].
     * Never moves an entry into or out of PAID (link invariant: linked iff PAID); only
     * [claimEntryForExpense] and [releaseEntryLink] may do that, so any call with
     * [expectedStatus] or [newStatus] = 'PAID' affects 0 rows.
     * Returns affected rows (1 = applied, 0 = not in the expected state or PAID involved).
     */
    @Query("""
        UPDATE installment_entries
        SET status = :newStatus,
            statusReason = :statusReason,
            statusChangedAt = :now
        WHERE id = :entryId
          AND status = :expectedStatus
          AND status <> 'PAID'
          AND :newStatus <> 'PAID'
    """)
    suspend fun updateEntryStatusIfCurrent(
        entryId: Long,
        expectedStatus: String,
        newStatus: String,
        statusReason: String?,
        now: Long
    ): Int

    /**
     * SCHEDULED -> PAID with the expense link, atomically: succeeds only while the entry
     * is still SCHEDULED and unlinked. [markAutoRecordConsumed] latches
     * `autoRecordConsumed` to true (it never resets, INV-12); pass false to leave it as is.
     * Returns affected rows (1 = claimed, 0 = lost the race or wrong state).
     */
    @Query("""
        UPDATE installment_entries
        SET status = 'PAID',
            statusReason = NULL,
            linkedExpenseId = :expenseId,
            linkOrigin = :linkOrigin,
            autoRecordConsumed = CASE WHEN :markAutoRecordConsumed THEN 1 ELSE autoRecordConsumed END,
            statusChangedAt = :now
        WHERE id = :entryId
          AND status = 'SCHEDULED'
          AND linkedExpenseId IS NULL
    """)
    suspend fun claimEntryForExpense(
        entryId: Long,
        expenseId: Long,
        linkOrigin: String,
        markAutoRecordConsumed: Boolean,
        now: Long
    ): Int

    /**
     * PAID -> SCHEDULED (unlink, or the linked expense is gone): clears the link and
     * bumps `recordGeneration` by one so the next record gets a fresh idempotency key.
     * `autoRecordConsumed` is untouched. Compare-and-set on status PAID AND on
     * `linkedExpenseId = [expectedExpenseId]`, so a reconcile for a deleted expense X
     * cannot release an entry that has since been re-linked to expense Y.
     * Returns affected rows (1 = released, 0 = not PAID or linked to a different expense).
     */
    @Query("""
        UPDATE installment_entries
        SET status = 'SCHEDULED',
            statusReason = :statusReason,
            linkedExpenseId = NULL,
            linkOrigin = NULL,
            recordGeneration = recordGeneration + 1,
            statusChangedAt = :now
        WHERE id = :entryId
          AND status = 'PAID'
          AND linkedExpenseId = :expectedExpenseId
    """)
    suspend fun releaseEntryLink(
        entryId: Long,
        expectedExpenseId: Long,
        statusReason: String?,
        now: Long
    ): Int
}
