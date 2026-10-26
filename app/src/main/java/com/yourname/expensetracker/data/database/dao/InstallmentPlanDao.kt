package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.yourname.expensetracker.data.database.entity.InstallmentEntry
import com.yourname.expensetracker.data.database.entity.InstallmentPlan
import kotlinx.coroutines.flow.Flow

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

    /**
     * Batch read (S6c-2b): the purchase expense id of every plan that has one, of any plan status.
     * Read-only. Used by the recurring generation path to exclude plan purchase records in one read.
     */
    @Query("SELECT DISTINCT purchaseExpenseId FROM installment_plans WHERE purchaseExpenseId IS NOT NULL")
    suspend fun getPlanPurchaseExpenseIds(): List<Long>

    @Query("SELECT * FROM installment_plans ORDER BY createdAt ASC, id ASC")
    suspend fun getAllPlans(): List<InstallmentPlan>

    @Query("SELECT * FROM installment_plans WHERE status = :status ORDER BY createdAt ASC, id ASC")
    suspend fun getPlansByStatus(status: String): List<InstallmentPlan>

    /** Observed by the read repository; re-emits when any plan row changes. Read-only. */
    @Query("SELECT * FROM installment_plans ORDER BY createdAt ASC, id ASC")
    fun observeAllPlans(): Flow<List<InstallmentPlan>>

    // ── Entry reads ──────────────────────────────────────────────

    /** Observed by the read repository; re-emits when any entry row changes. Read-only. */
    @Query("SELECT * FROM installment_entries ORDER BY planId ASC, dueDate ASC, seq ASC, id ASC")
    fun observeAllEntries(): Flow<List<InstallmentEntry>>

    @Query("SELECT * FROM installment_entries WHERE id = :entryId LIMIT 1")
    suspend fun getEntryById(entryId: Long): InstallmentEntry?

    @Query("SELECT * FROM installment_entries WHERE planId = :planId ORDER BY dueDate ASC, seq ASC, id ASC")
    suspend fun getEntriesForPlan(planId: Long): List<InstallmentEntry>

    @Query("SELECT * FROM installment_entries WHERE linkedExpenseId = :expenseId LIMIT 1")
    suspend fun getEntryByLinkedExpenseId(expenseId: Long): InstallmentEntry?

    /**
     * Batch read (S6c-2b): the expense id linked by every installment entry, of any entry status
     * (PAID and REVERSED included). Read-only. Shared by the recurring generation path and the
     * pattern-detector exclusion (S6c-3).
     */
    @Query("SELECT DISTINCT linkedExpenseId FROM installment_entries WHERE linkedExpenseId IS NOT NULL")
    suspend fun getLinkedExpenseIds(): List<Long>

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

    /**
     * Manual adjust of one entry (spec 7.4 E1), compare-and-set on `status = 'SCHEDULED'` AND
     * `linkedExpenseId IS NULL`. The first adjust keeps the pre-edit amount in
     * `originalScheduledAmount` (audit); later adjusts leave it as is. [adjustReason] is a
     * bounded code. Status is untouched. Returns affected rows (1 = applied, 0 = not an
     * unlinked SCHEDULED entry).
     */
    @Query("""
        UPDATE installment_entries
        SET originalScheduledAmount = COALESCE(originalScheduledAmount, scheduledAmount),
            scheduledAmount = :newAmount,
            dueDate = :newDueDate,
            adjustReason = :adjustReason,
            adjustedAt = :now
        WHERE id = :entryId
          AND status = 'SCHEDULED'
          AND linkedExpenseId IS NULL
    """)
    suspend fun updateEntryScheduleIfScheduled(
        entryId: Long,
        newAmount: Double,
        newDueDate: Long,
        adjustReason: String,
        now: Long
    ): Int

    /**
     * REVERSED -> CANCELLED when the refunded expense is gone (reconcile R2): clears the link
     * so no cancelled entry keeps a dangling expense id. Compare-and-set on status REVERSED AND
     * `linkedExpenseId = [expectedExpenseId]`. [statusReason] is a bounded code.
     * Returns affected rows (1 = applied, 0 = not REVERSED or linked to a different expense).
     */
    @Query("""
        UPDATE installment_entries
        SET status = 'CANCELLED',
            statusReason = :statusReason,
            linkedExpenseId = NULL,
            linkOrigin = NULL,
            statusChangedAt = :now
        WHERE id = :entryId
          AND status = 'REVERSED'
          AND linkedExpenseId = :expectedExpenseId
    """)
    suspend fun updateReversedEntryToCancelledIfLinkedTo(
        entryId: Long,
        expectedExpenseId: Long,
        statusReason: String?,
        now: Long
    ): Int

    /**
     * Detach a plan whose purchase expense no longer exists (reconcile R5): kind becomes
     * STANDALONE and `purchaseExpenseId` becomes NULL. Compare-and-set on kind PURCHASE_ATTACHED
     * AND `purchaseExpenseId = [expectedPurchaseExpenseId]`. Status is untouched.
     * Returns affected rows (1 = detached, 0 = already detached or a different purchase).
     */
    @Query("""
        UPDATE installment_plans
        SET kind = 'STANDALONE',
            purchaseExpenseId = NULL,
            updatedAt = :now
        WHERE id = :planId
          AND kind = 'PURCHASE_ATTACHED'
          AND purchaseExpenseId = :expectedPurchaseExpenseId
    """)
    suspend fun updatePlanDetachedFromPurchaseIfLinkedTo(
        planId: Long,
        expectedPurchaseExpenseId: Long,
        now: Long
    ): Int

    /**
     * PAID -> REVERSED (refund, spec 7.2, INV-2). Compare-and-set on status PAID AND
     * `linkedExpenseId = [expectedExpenseId]`. The link is kept: a REVERSED entry still references
     * its refunded expense (reconcile R2/R3 read it). The expense itself is never touched here.
     * Returns affected rows (1 = reversed, 0 = not PAID or linked to a different expense).
     */
    @Query("""
        UPDATE installment_entries
        SET status = 'REVERSED',
            statusReason = NULL,
            statusChangedAt = :now
        WHERE id = :entryId
          AND status = 'PAID'
          AND linkedExpenseId = :expectedExpenseId
    """)
    suspend fun updatePaidEntryToReversedIfLinkedTo(
        entryId: Long,
        expectedExpenseId: Long,
        now: Long
    ): Int

    /**
     * Edit of the plan details (spec 7.4 E3, plus the currency change allowed before any payment),
     * compare-and-set on the plan status. An ACTIVE plan accepts every field. A COMPLETED plan
     * accepts the display name, lender label and category only: its currency and fulfilment mode
     * must equal the stored ones. A currency change additionally requires that the plan has no
     * PAID or REVERSED entry (`CURRENCY_LOCKED`); the guards are part of the same statement so a
     * concurrent claim cannot slip between a read and this write. Returns affected rows
     * (1 = applied, 0 = plan not editable, currency locked, or currency / mode change on a
     * COMPLETED plan).
     */
    @Query("""
        UPDATE installment_plans
        SET displayName = :displayName,
            lenderLabel = :lenderLabel,
            categoryId = :categoryId,
            fulfilmentMode = :fulfilmentMode,
            currency = :currency,
            updatedAt = :now
        WHERE id = :planId
          AND (status = 'ACTIVE'
               OR (status = 'COMPLETED'
                   AND fulfilmentMode = :fulfilmentMode
                   AND currency = :currency))
          AND (currency = :currency
               OR NOT EXISTS (
                   SELECT 1 FROM installment_entries
                   WHERE planId = :planId AND status IN ('PAID', 'REVERSED')))
    """)
    suspend fun updatePlanDetailsIfEditable(
        planId: Long,
        displayName: String,
        lenderLabel: String?,
        categoryId: Long?,
        fulfilmentMode: String,
        currency: String,
        now: Long
    ): Int

    /**
     * Schedule shape of an ACTIVE plan after extend (new `installmentCount`) or a due-day change
     * (new `anchorDayOfMonth`), compare-and-set on `status = 'ACTIVE'`. Returns affected rows
     * (1 = applied, 0 = plan not ACTIVE).
     */
    @Query("""
        UPDATE installment_plans
        SET installmentCount = :installmentCount,
            anchorDayOfMonth = :anchorDayOfMonth,
            updatedAt = :now
        WHERE id = :planId
          AND status = 'ACTIVE'
    """)
    suspend fun updatePlanShapeIfActive(
        planId: Long,
        installmentCount: Int,
        anchorDayOfMonth: Int,
        now: Long
    ): Int
}
