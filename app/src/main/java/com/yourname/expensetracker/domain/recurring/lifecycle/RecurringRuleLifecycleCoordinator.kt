package com.yourname.expensetracker.domain.recurring.lifecycle

import androidx.room.withTransaction
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ManualRecurringExpenseDao
import com.yourname.expensetracker.data.database.dao.PlannedExpenseDao
import com.yourname.expensetracker.data.database.dao.RecurringLifecycleEventDao
import com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao
import com.yourname.expensetracker.data.database.dao.RecurringReminderDeliveryDao
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.data.database.entity.RecurringLifecycleEvent
import com.yourname.expensetracker.data.database.entity.RecurringOccurrence
import com.yourname.expensetracker.domain.util.TimeProvider
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject
import timber.log.Timber

/**
 * Single writer for all recurring rule lifecycle mutations.
 *
 * All create/update/delete/deactivate/activate operations must go through this
 * coordinator. Repositories must delegate — no direct DAO mutation outside this class.
 */
@Singleton
class RecurringRuleLifecycleCoordinator @Inject constructor(
    private val database: AppDatabase,
    private val writeBarrier: DatabaseWriteBarrier,
    private val timeProvider: TimeProvider,
    private val manualRecurringExpenseDao: ManualRecurringExpenseDao,
    private val occurrenceDao: RecurringOccurrenceDao,
    private val reminderDeliveryDao: RecurringReminderDeliveryDao,
    private val plannedExpenseDao: PlannedExpenseDao,
    private val lifecycleEventDao: RecurringLifecycleEventDao,
    private val lifecycleCoordinator: dagger.Lazy<RecurringLifecycleCoordinator>,
    private val expander: com.yourname.expensetracker.domain.recurring.RecurringOccurrenceExpander,
    private val resolver: com.yourname.expensetracker.domain.recurring.OccurrenceConflictResolver,
    private val materializer: RecurringOccurrenceMaterializer,
    private val expenseDao: com.yourname.expensetracker.data.database.dao.ExpenseDao,
    private val eventWriter: RecurringLifecycleEventWriter,
    private val planProjectionService: dagger.Lazy<com.yourname.expensetracker.domain.recurring.RecurringPlanProjectionService>
) {
    companion object {
        private const val SOURCE_TYPE = RecurringLifecycleCoordinator.SOURCE_TYPE_RECURRING_RULE

        // RP-04 A2: controlled conflict/invariant reason codes — never raw exception text.
        private const val REASON_SLOT_CONFLICT_UNRESOLVABLE = "SLOT_CONFLICT_UNRESOLVABLE"
        private const val REASON_DUPLICATE_OCCURRENCE_KEY = "DUPLICATE_OCCURRENCE_KEY"
        private const val REASON_DUPLICATE_OPEN_SLOT = "DUPLICATE_OPEN_SLOT"
        private const val REASON_ORPHAN_PLANNED_ROW = "ORPHAN_PLANNED_ROW"
        private const val REASON_ORPHAN_OPEN_DELIVERY = "ORPHAN_OPEN_DELIVERY"

        /** Delivery statuses considered open/retryable by the reconciliation invariants. */
        private val OPEN_DELIVERY_STATUSES = setOf("SCHEDULED", "SNOOZED", "CLAIMED", "FAILED_TRANSIENT")
    }

    /**
     * Deactivates a rule: sets isActive=false, deletes future PLANNED occurrences
     * and their reminders, and cancels planned expenses.
     *
     * Deleting (not cancelling) occurrences ensures reactivation can freely
     * regenerate them — cancelled rows would be skipped by the materializer's
     * terminal-status protection.
     */
    suspend fun deactivateRule(ruleId: Long) {
        writeBarrier.checkWritesAllowed("RecurringRuleLifecycleCoordinator.deactivateRule")
        val now = timeProvider.now()
        val existing = manualRecurringExpenseDao.getById(ruleId)

        database.withTransaction {
            manualRecurringExpenseDao.setActiveStatus(ruleId, false)

            // Delete future PLANNED occurrences and their reminders
            val plannedIds = occurrenceDao.getPlannedIdsBySource(SOURCE_TYPE, ruleId)
            if (plannedIds.isNotEmpty()) {
                reminderDeliveryDao.deleteByOccurrenceIds(plannedIds)
                occurrenceDao.deleteOpenPlannedBySource(SOURCE_TYPE, ruleId)
            }

            // Delete open generated planned rows (deleting, not cancelling,
            // so reactivation can freely project new rows by sourceOccurrenceKey)
            plannedExpenseDao.deleteOpenPlannedByRecurringRuleId(ruleId)

            lifecycleEventDao.insert(
                RecurringLifecycleEvent(
                    occurrenceId = null,
                    eventType = "RULE_DEACTIVATED",
                    occurredAt = now,
                    oldStatus = "ACTIVE",
                    newStatus = "INACTIVE",
                    metadata = JSONObject().apply {
                        put("ruleId", ruleId)
                        put("merchant", existing?.merchant.orEmpty())
                    }.toString()
                )
            )
        }
    }

    /**
     * Deletes a rule and all its occurrences, reminders, and planned expenses atomically.
     */
    suspend fun deleteRule(ruleId: Long) {
        writeBarrier.checkWritesAllowed("RecurringRuleLifecycleCoordinator.deleteRule")
        val now = timeProvider.now()
        val existing = manualRecurringExpenseDao.getById(ruleId)

        // GR-14p: canonical direct scope — the mutations' proof is local
        // to the legal writer, independent of caller context.
        writeBarrier.runWrite(
            DatabaseAccessOperation("RecurringRuleLifecycleCoordinator.deleteRule")
        ) {
            database.withTransaction {
            // Delete reminders for all occurrences of this rule
            val occurrenceIds = occurrenceDao.getIdsBySource(SOURCE_TYPE, ruleId)
            if (occurrenceIds.isNotEmpty()) {
                reminderDeliveryDao.deleteByOccurrenceIds(occurrenceIds)
            }

            // Delete planned expenses, occurrences, then the rule itself
            plannedExpenseDao.deleteByRecurringRuleId(ruleId)
            occurrenceDao.deleteBySource(SOURCE_TYPE, ruleId)
            manualRecurringExpenseDao.deleteById(ruleId)

            lifecycleEventDao.insert(
                RecurringLifecycleEvent(
                    occurrenceId = null,
                    eventType = "RULE_DELETED",
                    occurredAt = now,
                    oldStatus = null,
                    newStatus = null,
                    metadata = JSONObject().apply {
                        put("ruleId", ruleId)
                        put("merchant", existing?.merchant.orEmpty())
                        put("amount", existing?.amount ?: 0.0)
                    }.toString()
                )
            )
            }
        }
    }

    /**
     * Creates a new recurring rule, generates future occurrences/reminders/planned rows
     * in one atomic transaction, and writes RULE_CREATED_GENERATED event.
     */
    suspend fun createRule(expense: ManualRecurringExpense): Long {
        writeBarrier.checkWritesAllowed("RecurringRuleLifecycleCoordinator.createRule")
        val now = timeProvider.now()
        val entity = if (expense.createdAt == 0L) expense.copy(createdAt = now) else expense

        // GR-14p: canonical direct scope — the mutations' proof is local
        // to the legal writer, independent of caller context.
        return writeBarrier.runWrite(
            DatabaseAccessOperation("RecurringRuleLifecycleCoordinator.createRule")
        ) {
            database.withTransaction {
            val id = manualRecurringExpenseDao.insert(entity)
            val saved = entity.copy(id = id)

            // Generate future occurrences for the new rule
            val regenerateStart = maxOf(
                saved.nextDate,
                com.yourname.expensetracker.domain.util.TimePeriodUtils.getStartOfDay(now)
            )
            val regenerateEnd = com.yourname.expensetracker.domain.util.TimePeriodUtils.addMonths(regenerateStart, 12)

            val request = com.yourname.expensetracker.domain.recurring.RecurringOccurrenceExpander.ExpandRequest(
                merchant = saved.merchant, amount = saved.amount, currency = saved.currency,
                frequency = saved.frequency, categoryId = saved.categoryId,
                startDate = regenerateStart, endDate = regenerateEnd,
                anchorDate = saved.nextDate, sourceType = SOURCE_TYPE, sourceId = id
            )
            val candidates = expander.expand(request)
            val actualExpenses = expenseDao.getExpensesBetween(regenerateStart, regenerateEnd)
            val resolved = resolver.resolve(candidates, actualExpenses)

            materializer.materializeInCurrentTransaction(
                resolved = resolved,
                options = RecurringOccurrenceMaterializer.MaterializationOptions(
                    createReminderDeliveries = true,
                    reminderWindows = RecurringLifecycleCoordinator.DEFAULT_REMINDER_WINDOWS,
                    generationSource = OccurrenceGenerationSource.RULE_CREATE.name,
                    allowPastDueReminderDeliveries = false
                )
            )

            // Project planned rows
            planProjectionService.get().projectFromOccurrencesInCurrentTransaction(
                ruleId = id, startDate = regenerateStart, endDate = regenerateEnd, now = now
            )

            lifecycleEventDao.insert(
                RecurringLifecycleEvent(
                    occurrenceId = null,
                    eventType = "RULE_CREATED_GENERATED",
                    occurredAt = now,
                    oldStatus = null, newStatus = null,
                    metadata = JSONObject().apply {
                        put("ruleId", id)
                        put("merchant", saved.merchant)
                        put("amount", saved.amount)
                        put("frequency", saved.frequency)
                    }.toString()
                )
            )
            id
            }
        }
    }

    /**
     * Activates a previously deactivated rule and atomically generates future state.
     * If generation fails, activation rolls back.
     */
    suspend fun activateRule(ruleId: Long) {
        writeBarrier.checkWritesAllowed("RecurringRuleLifecycleCoordinator.activateRule")
        val now = timeProvider.now()
        val existing = manualRecurringExpenseDao.getById(ruleId) ?: return
        database.withTransaction {
            manualRecurringExpenseDao.setActiveStatus(ruleId, true)

            val regenerateStart = maxOf(
                existing.nextDate,
                com.yourname.expensetracker.domain.util.TimePeriodUtils.getStartOfDay(now)
            )
            val regenerateEnd = com.yourname.expensetracker.domain.util.TimePeriodUtils.addMonths(regenerateStart, 12)

            val request = com.yourname.expensetracker.domain.recurring.RecurringOccurrenceExpander.ExpandRequest(
                merchant = existing.merchant, amount = existing.amount, currency = existing.currency,
                frequency = existing.frequency, categoryId = existing.categoryId,
                startDate = regenerateStart, endDate = regenerateEnd,
                anchorDate = existing.nextDate, sourceType = SOURCE_TYPE, sourceId = ruleId
            )
            val candidates = expander.expand(request)
            val actualExpenses = expenseDao.getExpensesBetween(regenerateStart, regenerateEnd)
            val resolved = resolver.resolve(candidates, actualExpenses)

            materializer.materializeInCurrentTransaction(
                resolved = resolved,
                options = RecurringOccurrenceMaterializer.MaterializationOptions(
                    createReminderDeliveries = true,
                    reminderWindows = RecurringLifecycleCoordinator.DEFAULT_REMINDER_WINDOWS,
                    generationSource = OccurrenceGenerationSource.RULE_CREATE.name,
                    allowPastDueReminderDeliveries = false
                )
            )

            planProjectionService.get().projectFromOccurrencesInCurrentTransaction(
                ruleId = ruleId, startDate = regenerateStart, endDate = regenerateEnd, now = now
            )

            lifecycleEventDao.insert(
                RecurringLifecycleEvent(
                    occurrenceId = null,
                    eventType = "RULE_ACTIVATED_REGENERATED",
                    occurredAt = now,
                    oldStatus = "INACTIVE", newStatus = "ACTIVE",
                    metadata = JSONObject().apply {
                        put("ruleId", ruleId)
                        put("merchant", existing.merchant)
                        put("isActive", true)
                    }.toString()
                )
            )
        }
    }

    /**
     * Advances the nextDate for a rule and writes a lifecycle event.
     */
    suspend fun advanceNextDate(ruleId: Long, nextDate: Long) {
        writeBarrier.checkWritesAllowed("RecurringRuleLifecycleCoordinator.advanceNextDate")
        val now = timeProvider.now()
        database.withTransaction {
            manualRecurringExpenseDao.updateNextDate(ruleId, nextDate)
            lifecycleEventDao.insert(
                RecurringLifecycleEvent(
                    occurrenceId = null,
                    eventType = "NEXT_DATE_ADVANCED",
                    occurredAt = now,
                    oldStatus = null,
                    newStatus = null,
                    metadata = JSONObject().apply {
                        put("ruleId", ruleId)
                        put("nextDate", nextDate)
                    }.toString()
                )
            )
        }
    }

    /**
     * Updates a recurring rule and reconciles its derived state in ONE transaction
     * (RP-04 P4-003/004 logical occurrence reconciliation — replaces the old
     * delete-all-PLANNED-then-regenerate algorithm).
     *
     * ## Logical identity
     * A logical occurrence is `(sourceType=RECURRING_RULE, sourceId=ruleId, recurrence slot,
     * due local date)`. Old/new slot maps are built from the rule's schedule+anchor and mapped
     * one-to-one by due local date. The persisted [RecurringOccurrence.occurrenceKey] remains a
     * storage/dedup key, not the only matching rule (a frequency change re-keys matched rows).
     *
     * ## State policy
     * - Terminal rows (PAID/SKIPPED/MISSED/CANCELLED/IGNORED): never touched, never regenerated
     *   as PLANNED; paid snapshots and linked actual expenses are preserved.
     * - Overdue open PLANNED (due at/before the reconciliation reference day = start of today):
     *   preserved as an obligation with its old snapshot unless explicitly matched by a new
     *   logical slot (matched overdue rows adopt the new snapshot).
     * - Future open PLANNED: adopted in place through the mapping when a new slot shares their
     *   due date; retired atomically (occurrence + open deliveries + open planned row) when the
     *   slot moved and no new candidate covers them.
     * - A candidate is never inserted when its logical slot is already represented by an
     *   existing occurrence (terminal → skip candidate; open → matched in place).
     * - Two open rows on one due date = unresolvable conflict → fail closed (rollback) with a
     *   controlled reason code.
     * - Reminders: reconciled atomically by (occurrenceId, reminderWindow); SENT/DISMISSED
     *   terminal delivery history is never replayed; retired slots' open deliveries are deleted.
     * - Linked actual expenses: rule updates never unlink a PAID row (terminal rows are not
     *   modified); expense-side changes keep flowing through
     *   [RecurringLifecycleCoordinator.reconcileExpenseLinkAfterUpdate].
     *
     * ## Transaction boundary (single `withTransaction`)
     * barrier check → load old rule + occurrences → validate/normalize new rule → compute slot
     * maps → apply rule row update + permitted occurrence/planned/reminder writes + critical
     * RULE_UPDATED_RECONCILED event (via [RecurringLifecycleEventWriter]) → pre-commit invariant
     * assertions. Any conflict throws before commit, rolling back every derived write.
     *
     * Active-state changes are intentionally out of scope here: [updateRule] preserves the
     * loaded rule's isActive flag; toggling goes through [activateRule]/[deactivateRule].
     */
    suspend fun updateRule(updated: ManualRecurringExpense) {
        writeBarrier.checkWritesAllowed("RecurringRuleLifecycleCoordinator.updateRule")
        val now = timeProvider.now()
        val old = manualRecurringExpenseDao.getById(updated.id)
            ?: throw IllegalArgumentException("Recurring rule not found: id=${updated.id}")

        // Validate + normalize date-only values. createdAt sentinel inherited from the row.
        val normalized = (if (updated.createdAt == 0L) updated.copy(createdAt = old.createdAt) else updated)
            .copy(
                id = old.id,
                isActive = old.isActive, // active state changes are activateRule/deactivateRule's job
                nextDate = com.yourname.expensetracker.domain.util.TimePeriodUtils.getStartOfDay(updated.nextDate)
            )

        // GR-14p: canonical direct scope — the mutations' proof is local
        // to the legal writer, independent of caller context.
        writeBarrier.runWrite(
            DatabaseAccessOperation("RecurringRuleLifecycleCoordinator.updateRule")
        ) {
            database.withTransaction {
                reconcileUpdateInCurrentTransaction(old, normalized, now)
            }
        }
    }

    /**
     * RP-04 A2 reconciler. Called ONLY from [updateRule]'s transaction — never elsewhere.
     * Throws on conflict so the enclosing transaction rolls back (fail closed).
     */
    private suspend fun reconcileUpdateInCurrentTransaction(
        old: ManualRecurringExpense,
        normalized: ManualRecurringExpense,
        now: Long
    ) {
        val ruleId = normalized.id
        val referenceDay = com.yourname.expensetracker.domain.util.TimePeriodUtils.getStartOfDay(now)

        // ── 1. Load all occurrences of this rule ─────────────────────────────
        // Terminal rows are only read through `existing` below: they are never
        // adopted, retired, or re-keyed — their presence simply blocks candidate
        // insertion for their logical slot (step 4).
        val existing = occurrenceDao.getBySource(SOURCE_TYPE, ruleId)
        val open = existing.filter { it.status == RecurringOccurrenceStatus.PLANNED.dbValue }

        // Unresolvable pre-condition: two open rows on one due date — the reconciler
        // must not choose between two actual obligations (fail closed).
        val openByDate = open.groupBy { it.dueDate }
        val duplicatedDate = openByDate.entries.firstOrNull { it.value.size > 1 }
        if (duplicatedDate != null) {
            Timber.w(
                "Rule update reconciliation conflict ruleId=%d reason=%s count=%d",
                ruleId, REASON_SLOT_CONFLICT_UNRESOLVABLE, duplicatedDate.value.size
            )
            throw IllegalStateException(
                "SLOT_CONFLICT ${REASON_SLOT_CONFLICT_UNRESOLVABLE} ruleId=$ruleId dueDate=${duplicatedDate.key}"
            )
        }

        // ── 2. Compute new slot map from the updated rule ────────────────────
        val regenerateStart = maxOf(normalized.nextDate, referenceDay)
        val regenerateEnd = com.yourname.expensetracker.domain.util.TimePeriodUtils.addMonths(regenerateStart, 12)
        val request = com.yourname.expensetracker.domain.recurring.RecurringOccurrenceExpander.ExpandRequest(
            merchant = normalized.merchant,
            amount = normalized.amount,
            currency = normalized.currency,
            frequency = normalized.frequency,
            categoryId = normalized.categoryId,
            startDate = regenerateStart,
            endDate = regenerateEnd,
            anchorDate = normalized.nextDate,
            sourceType = SOURCE_TYPE,
            sourceId = ruleId
        )
        val newCandidates = expander.expand(request)
        val newByDate = newCandidates.groupBy { it.dueDate }

        // ── 3. Classify and map open rows ────────────────────────────────────
        val updatedInPlace = mutableListOf<RecurringOccurrence>()
        val retiredKeys = mutableListOf<String>()
        val retiredOccurrenceIds = mutableListOf<Long>()
        var skippedRepresentedSlots = 0

        for ((dueDate, bucket) in openByDate) {
            val row = bucket.first()
            val matched = newByDate[dueDate]
            if (matched != null) {
                // One-to-one mapping: same logical slot → adopt the new snapshot in place.
                updatedInPlace += adoptSnapshotInPlace(row, matched.first(), normalized, now)
            } else if (dueDate > referenceDay) {
                // Future open row whose slot moved away: retire atomically.
                // Safe: PLANNED rows carry no linked expense and no terminal history.
                retiredKeys += row.occurrenceKey
                retiredOccurrenceIds += row.id
            }
            // else: overdue open row with no matching new slot → preserved obligation
            // with its old snapshot (occurrenceKey and dueDate unchanged).
        }

        // ── 4. Unmatched new candidates: insert only free logical slots ──────
        // P4-003/004 logical identity: a slot is represented by ANY open planned
        // row of this rule, not only by an occurrence. Without this clause an
        // in-window orphan row (open planned row with no backing occurrence) is
        // silently re-backed by a fresh candidate carrying the new snapshot while
        // the row keeps its stale one — gate (e) below then never fires. With the
        // clause, the orphan slot stays candidate-free, projection cannot re-key
        // the row, and gate (e) fails the whole update closed (rollback).
        val openPlannedRows = plannedExpenseDao.getOpenPlannedByRecurringRuleId(ruleId)
        val openPlannedRowDates = openPlannedRows.map { it.date }.toSet()
        val freeCandidates = newCandidates.filter { candidate ->
            val represented = existing.any {
                it.dueDate == candidate.dueDate && it.id !in retiredOccurrenceIds
            } || candidate.dueDate in openPlannedRowDates
            if (represented) {
                // Terminal row, preserved overdue row, or open planned row occupies
                // this logical slot — never insert a second row for it.
                skippedRepresentedSlots++
            }
            !represented
        }

        val resolved = if (freeCandidates.isEmpty()) {
            emptyList()
        } else {
            val actualExpenses = expenseDao.getExpensesBetween(regenerateStart, regenerateEnd)
            resolver.resolve(freeCandidates, actualExpenses)
        }

        // ── 5. Apply: rule row → retirements → in-place adopts → inserts ─────
        manualRecurringExpenseDao.update(normalized)

        if (retiredOccurrenceIds.isNotEmpty()) {
            reminderDeliveryDao.deleteOpenDeliveriesByOccurrenceIds(retiredOccurrenceIds)
            occurrenceDao.deletePlannedByIds(retiredOccurrenceIds)
            plannedExpenseDao.deleteOpenPlannedBySourceKeys(retiredKeys)
        }

        for (row in updatedInPlace) {
            occurrenceDao.update(row)
            val rows = plannedExpenseDao.updateDerivedSnapshotForKey(
                oldKey = existing.first { it.id == row.id }.occurrenceKey,
                newKey = row.occurrenceKey,
                description = row.merchant ?: "Recurring Expense",
                amount = row.expectedAmount,
                currency = row.expectedCurrency,
                date = row.dueDate,
                categoryId = row.categoryId,
                merchantKey = com.yourname.expensetracker.domain.util.MerchantKeyGenerator.generate(row.merchant.orEmpty()),
                updatedAt = now
            )
            if (rows == 0) {
                // No open derived planned row for this key: either it never had one
                // (out of projection range) or it was already fulfilled — both legal.
            }
        }

        val materialization = materializer.materializeInCurrentTransaction(
            resolved = resolved,
            options = RecurringOccurrenceMaterializer.MaterializationOptions(
                createReminderDeliveries = true,
                reminderWindows = RecurringLifecycleCoordinator.DEFAULT_REMINDER_WINDOWS,
                generationSource = OccurrenceGenerationSource.RULE_UPDATE_REGENERATION.name,
                allowPastDueReminderDeliveries = false
            )
        )

        // Project planned rows for newly created PLANNED occurrences (dedup by key).
        planProjectionService.get().projectFromOccurrencesInCurrentTransaction(
            ruleId = ruleId,
            startDate = regenerateStart,
            endDate = regenerateEnd,
            now = now
        )

        // ── 6. Pre-commit invariant assertions (fail closed → rollback) ──────
        assertReconciliationInvariants(ruleId, retiredKeys)

        // ── 7. Critical lifecycle event via the event writer ─────────────────
        eventWriter.writeCritical(
            occurrenceId = null,
            eventType = "RULE_UPDATED_RECONCILED",
            oldStatus = null,
            newStatus = null,
            metadata = JSONObject().apply {
                put("ruleId", ruleId)
                put("oldAmount", old.amount)
                put("newAmount", normalized.amount)
                put("oldCurrency", old.currency)
                put("newCurrency", normalized.currency)
                put("oldFrequency", old.frequency.name)
                put("newFrequency", normalized.frequency.name)
                put("adopted", updatedInPlace.size)
                put("retired", retiredOccurrenceIds.size)
                put("created", materialization.created)
                put("skippedRepresentedSlots", skippedRepresentedSlots)
            }.toString(),
            occurredAt = now
        )
    }

    /**
     * Builds the in-place adopt update for a matched open occurrence: new snapshot
     * (amount/currency/merchant/category/frequency) + re-keyed occurrenceKey.
     * Status, linked expense, paid snapshot, and createdAt are never modified.
     */
    private fun adoptSnapshotInPlace(
        row: RecurringOccurrence,
        candidate: com.yourname.expensetracker.domain.recurring.RecurringOccurrenceExpander.OccurrenceCandidate,
        normalized: ManualRecurringExpense,
        now: Long
    ): RecurringOccurrence = row.copy(
        occurrenceKey = candidate.occurrenceKey,
        expectedAmount = normalized.amount,
        expectedCurrency = normalized.currency,
        frequency = normalized.frequency.name,
        merchant = normalized.merchant,
        categoryId = normalized.categoryId,
        updatedAt = now
    )

    /**
     * Pre-commit invariant checks for the rule-update reconciliation. Any violation
     * throws so the enclosing transaction rolls back (fail closed, never best effort).
     */
    private suspend fun assertReconciliationInvariants(ruleId: Long, retiredKeys: List<String>) {
        val after = occurrenceDao.getBySource(SOURCE_TYPE, ruleId)

        // (a) occurrenceKey uniqueness within the rule.
        val duplicateKey = after.groupBy { it.occurrenceKey }.entries.firstOrNull { it.value.size > 1 }
        if (duplicateKey != null) {
            throw IllegalStateException(
                "INVARIANT_VIOLATION ${REASON_DUPLICATE_OCCURRENCE_KEY} ruleId=$ruleId"
            )
        }

        // (b) at most one open PLANNED row per due local date.
        val open = after.filter { it.status == RecurringOccurrenceStatus.PLANNED.dbValue }
        val duplicateSlot = open.groupBy { it.dueDate }.entries.firstOrNull { it.value.size > 1 }
        if (duplicateSlot != null) {
            throw IllegalStateException(
                "INVARIANT_VIOLATION ${REASON_DUPLICATE_OPEN_SLOT} ruleId=$ruleId"
            )
        }

        // (c) retired open planned rows are really gone (or no longer PLANNED).
        for (key in retiredKeys) {
            val plannedRow = plannedExpenseDao.getBySourceOccurrenceKey(key)
            if (plannedRow != null && plannedRow.status == "PLANNED") {
                throw IllegalStateException(
                    "INVARIANT_VIOLATION ${REASON_ORPHAN_PLANNED_ROW} ruleId=$ruleId"
                )
            }
        }

        // (d) no open reminder delivery may reference a missing/non-PLANNED occurrence.
        val openIds = open.map { it.id }
        val deliveries = if (after.isEmpty()) {
            emptyList()
        } else {
            reminderDeliveryDao.getByOccurrenceIds(after.map { it.id })
        }
        val orphanDelivery = deliveries.any {
            it.status in OPEN_DELIVERY_STATUSES && it.occurrenceId !in openIds
        }
        if (orphanDelivery) {
            throw IllegalStateException(
                "INVARIANT_VIOLATION ${REASON_ORPHAN_OPEN_DELIVERY} ruleId=$ruleId"
            )
        }

        // (e) RP-04 P4-004 general check: NO open planned row may exist without a
        // current PLANNED occurrence backing it — including rows that pre-date this
        // update (pre-existing corruption must fail closed, not survive the gate).
        // Loaded via the scoped read so the check covers the rule's whole open set,
        // not just the keys this update retired.
        val openPlannedRows = plannedExpenseDao.getOpenPlannedByRecurringRuleId(ruleId)
        val openKeys = open.map { it.occurrenceKey }.toSet()
        val orphanPlannedRow = openPlannedRows.firstOrNull { it.sourceOccurrenceKey !in openKeys }
        if (orphanPlannedRow != null) {
            Timber.w(
                "Rule update reconciliation conflict ruleId=%d reason=%s key=%s",
                ruleId, REASON_ORPHAN_PLANNED_ROW, orphanPlannedRow.sourceOccurrenceKey
            )
            throw IllegalStateException(
                "INVARIANT_VIOLATION ${REASON_ORPHAN_PLANNED_ROW} ruleId=$ruleId"
            )
        }
    }
}
