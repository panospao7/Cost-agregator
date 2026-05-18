package com.yourname.expensetracker.domain.recurring.lifecycle

import androidx.room.withTransaction
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.ManualRecurringExpenseDao
import com.yourname.expensetracker.data.database.dao.PlannedExpenseDao
import com.yourname.expensetracker.data.database.dao.RecurringLifecycleEventDao
import com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao
import com.yourname.expensetracker.data.database.dao.RecurringReminderDeliveryDao
import com.yourname.expensetracker.data.database.entity.RecurringLifecycleEvent
import com.yourname.expensetracker.data.database.entity.RecurringOccurrence
import com.yourname.expensetracker.data.database.entity.RecurringReminderDelivery
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.recurring.OccurrenceConflictResolver
import com.yourname.expensetracker.domain.recurring.RecurringOccurrenceExpander
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringOccurrenceMaterializer.MaterializationResult
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber
import kotlin.math.abs

sealed interface ReminderActionResult {
    data object Updated : ReminderActionResult
    data class NoOp(val reason: String) : ReminderActionResult
    data object NotFound : ReminderActionResult
}

/**
 * Orchestrates the full lifecycle of recurring-expense occurrences:
 * expansion, conflict resolution, materialization, and linkage.
 *
 * This is the primary entry point for generating and managing recurring
 * occurrences from manual recurring rules.
 */
@Singleton
class RecurringLifecycleCoordinator @Inject constructor(
    private val database: AppDatabase,
    private val expander: RecurringOccurrenceExpander,
    private val resolver: OccurrenceConflictResolver,
    private val materializer: RecurringOccurrenceMaterializer,
    private val occurrenceDao: RecurringOccurrenceDao,
    private val expenseDao: ExpenseDao,
    private val timeProvider: TimeProvider,
    private val manualRecurringExpenseDao: ManualRecurringExpenseDao,
    private val reminderDeliveryDao: RecurringReminderDeliveryDao,
    private val lifecycleEventDao: RecurringLifecycleEventDao,
    private val restoreMaintenanceMode: RestoreMaintenanceMode,
    private val writeBarrier: DatabaseWriteBarrier,
    private val plannedExpenseDao: PlannedExpenseDao
) {
    companion object {
        /** Source type used for manual recurring rules. */
        const val SOURCE_TYPE_RECURRING_RULE = "RECURRING_RULE"

        /** Default reminder windows applied when no explicit windows are provided and reminders are enabled. */
        val DEFAULT_REMINDER_WINDOWS = listOf("3_DAYS_BEFORE", "DUE_DAY", "OVERDUE")

        /** Statuses from which dismiss/snooze are no-ops. */
        val TERMINAL_STATUSES = setOf("DISMISSED", "CANCELLED", "FAILED_FINAL", "SENT")
    }

    /**
     * Expands, resolves, and materializes occurrences for a recurring rule
     * within a date range.
     *
     * @param ruleId The ID of the [ManualRecurringExpense] rule.
     * @param startDate Start of the range (inclusive, epoch ms).
     * @param endDate End of the range (exclusive, epoch ms).
     * @param reminderWindows Reminder window names to schedule. If empty, defaults to [DEFAULT_REMINDER_WINDOWS].
     * @return The [MaterializationResult] from persisting.
     * @throws IllegalArgumentException if the rule is not found.
     */
    suspend fun generateOccurrences(
        ruleId: Long,
        startDate: Long,
        endDate: Long,
        reminderWindows: List<String> = emptyList()
    ): MaterializationResult {
        val resolvedWindows = if (reminderWindows.isEmpty()) DEFAULT_REMINDER_WINDOWS else reminderWindows
        writeBarrier.checkWritesAllowed("RecurringLifecycleCoordinator.generateOccurrences")

        val rule = manualRecurringExpenseDao.getById(ruleId)
            ?: throw IllegalArgumentException("Recurring rule not found: id=$ruleId")

        // P4-CURRENT-010: Inactive rules must not generate occurrences
        if (!rule.isActive) return MaterializationResult(0, 0, 0, 0)

        // Use rule.nextDate as the expansion anchor. If it's before startDate,
        // advance it by the frequency until it falls within the range.
        var anchorDate = rule.nextDate
        var advanceIterations = 0
        while (anchorDate < startDate && rule.frequency != RecurrenceFrequency.IRREGULAR) {
            if (++advanceIterations > 1000) {
                Timber.w("Anchor advance loop exceeded 1000 iterations for ruleId=%d, breaking", ruleId)
                break
            }
            anchorDate = expander.advanceDate(anchorDate, rule.frequency)
        }

        val request = RecurringOccurrenceExpander.ExpandRequest(
            merchant = rule.merchant,
            amount = rule.amount,
            currency = rule.currency,
            frequency = rule.frequency,
            categoryId = rule.categoryId, // P4-CURRENT-014: Preserve rule category
            startDate = startDate,
            endDate = endDate,
            anchorDate = anchorDate,
            sourceType = SOURCE_TYPE_RECURRING_RULE,
            sourceId = rule.id
        )

        val candidates = expander.expand(request)
        val actualExpenses = expenseDao.getExpensesBetween(startDate, endDate)
        val resolved = resolver.resolve(candidates, actualExpenses)

        return materializer.materialize(resolved, resolvedWindows)
    }

    /**
     * Links an actual expense to a planned occurrence (marking it PAID).
     *
     * Matching rules (all must hold):
     * 1. Occurrence status is PLANNED and not yet linked.
     * 2. Same calendar day.
     * 3. Merchant key matches (case-insensitive, via [MerchantKeyGenerator]).
     * 4. Amount within ±10% tolerance of the expected amount.
     * 5. Same currency (case-insensitive).
     * 6. Expense is not excluded: isNotMine, TRANSFER, and DEPOSIT are skipped.
     *
     * @param expenseId The ID of the expense to link.
     * @return `true` if a matching occurrence was found and linked.
     */
    suspend fun linkExpenseToOccurrence(expenseId: Long): Boolean {
        writeBarrier.checkWritesAllowed("RecurringLifecycleCoordinator.linkExpenseToOccurrence")

        val expense = expenseDao.getById(expenseId) ?: return false

        // Exclude non-ownership and non-spending types
        if (expense.isNotMine) return false
        if (expense.transactionType == TransactionType.TRANSFER ||
            expense.transactionType == TransactionType.DEPOSIT
        ) return false

        val expenseDayStart = TimePeriodUtils.getStartOfDay(expense.date)
        val expenseDayEnd = TimePeriodUtils.getEndOfDay(expense.date)
        val expenseMerchantKey = MerchantKeyGenerator.generate(expense.merchant)

        // Find PLANNED occurrences on the same calendar day with no linked expense
        val occurrences = occurrenceDao.getByDateRange(expenseDayStart, expenseDayEnd)
        val match = occurrences.firstOrNull { occ ->
            if (occ.status != "PLANNED" || occ.linkedExpenseId != null) return@firstOrNull false

            // Merchant key match (case-insensitive)
            val occMerchantKey = MerchantKeyGenerator.generate(occ.merchant.orEmpty())
            if (occMerchantKey.isBlank() || expenseMerchantKey.isBlank()) return@firstOrNull false
            if (occMerchantKey != expenseMerchantKey) return@firstOrNull false

            // Amount within ±10% tolerance
            if (!amountMatches(occ.expectedAmount, expense.amount)) return@firstOrNull false

            // Same currency (case-insensitive)
            if (!occ.expectedCurrency.equals(expense.currency, ignoreCase = true)) return@firstOrNull false

            true
        } ?: return false

        val now = timeProvider.now()
        var claimed = false
        database.withTransaction {
            // P4-CURRENT-001: Use conditional DAO method to atomically claim the occurrence.
            // If another thread already claimed it, claimForExpense returns 0.
            val rows = occurrenceDao.claimForExpense(
                occurrenceId = match.id,
                expenseId = expenseId,
                amount = expense.amount,
                currency = expense.currency,
                paidAt = now
            )
            if (rows == 0) {
                // Already claimed by another thread — abort without side effects
                return@withTransaction
            }
            claimed = true

            lifecycleEventDao.insert(
                RecurringLifecycleEvent(
                    occurrenceId = match.id,
                    eventType = "OCCURRENCE_PAID",
                    occurredAt = now,
                    oldStatus = "PLANNED",
                    newStatus = "PAID",
                    metadata = """{"expenseId":$expenseId,"amount":${expense.amount},"currency":"${expense.currency}"}"""
                )
            )

            val planned = plannedExpenseDao.getBySourceOccurrenceKey(match.occurrenceKey)
            if (planned != null) {
                plannedExpenseDao.linkToActualExpense(planned.id, expenseId, now)
            }

            reminderDeliveryDao.suppressOpenDeliveriesForOccurrence(match.id)
        }

        if (!claimed) return false

        Timber.d(
            "Recurring: expense %d linked to occurrence %d (occurrenceKey=%s) — PAID, planned fulfilled, reminders suppressed",
            expenseId, match.id, match.occurrenceKey
        )
        return true
    }

    /**
     * Checks whether [actualAmount] is within ±10% of [expectedAmount].
     * Both values must be finite; returns `false` for zero or negative expected amounts.
     */
    private fun amountMatches(expectedAmount: Double, actualAmount: Double): Boolean {
        if (!expectedAmount.isFinite() || !actualAmount.isFinite()) return false
        if (expectedAmount == 0.0) return false

        val tolerance = abs(expectedAmount * 0.10)
        val difference = abs(actualAmount - expectedAmount)
        return difference <= tolerance
    }

    /**
     * Unlinks an expense from its linked occurrence, resetting it back to PLANNED.
     *
     * Called when an expense is deleted so that the recurring occurrence is no
     * longer considered PAID. Looks back up to 1 year and forward 1 week for
     * an occurrence linked to the given expense ID.
     *
     * @param expenseId The ID of the expense being deleted.
     * @param reason Why the expense was unlinked (e.g. "expense_deleted", "manual_unlink").
     *               Defaults to "expense_deleted".
     */
    suspend fun unlinkExpenseFromOccurrence(expenseId: Long, reason: String = "expense_deleted") {
        writeBarrier.checkWritesAllowed("RecurringLifecycleCoordinator.unlinkExpenseFromOccurrence")

        val now = timeProvider.now()

        // Direct lookup by linkedExpenseId — handles any date range (historical, backdated, restored)
        val linked = occurrenceDao.getByLinkedExpenseId(expenseId)
            ?: return // No linked occurrence, nothing to do

        database.withTransaction {
            // Reset to PLANNED — the recurring bill is not yet paid
            occurrenceDao.update(
                linked.copy(
                    status = "PLANNED",
                    linkedExpenseId = null,
                    paidAmount = null,
                    paidCurrency = null,
                    paidAt = null,
                    updatedAt = now
                )
            )

            // P4-CURRENT-002: Use atomic unlinkActualExpense to avoid corrupting
            // planned expense with linkedActualExpenseId=0.
            val planned = plannedExpenseDao.getBySourceOccurrenceKey(linked.occurrenceKey)
            if (planned != null) {
                plannedExpenseDao.unlinkActualExpense(planned.id, now)
                // Reminder deliveries should be regenerated for the unlinked occurrence
                // so that the user gets notified again. Currently deliveries are
                // suppressed during linkExpenseToOccurrence, and unlink does not
                // regenerate them — this is a known gap (see TODO below).
                // TODO: regenerate reminder deliveries for the unlinked occurrence
            }

            // Write lifecycle event
            lifecycleEventDao.insert(
                RecurringLifecycleEvent(
                    occurrenceId = linked.id,
                    eventType = "OCCURRENCE_UNLINKED",
                    occurredAt = now,
                    oldStatus = "PAID",
                    newStatus = "PLANNED",
                    metadata = """{"expenseId":$expenseId,"reason":"$reason"}"""
                )
            )
        }
    }

    /**
     * Gets all occurrences for a given source/rule.
     *
     * @param ruleId The ID of the recurring rule.
     * @return The list of occurrences, ordered by due date.
     */
    suspend fun getOccurrences(ruleId: Long): List<RecurringOccurrence> {
        return occurrenceDao.getBySource(SOURCE_TYPE_RECURRING_RULE, ruleId)
    }

    suspend fun getOccurrenceById(occurrenceId: Long): RecurringOccurrence? {
        return occurrenceDao.getById(occurrenceId)
    }

    /**
     * Marks an occurrence as SKIPPED, MISSED, or CANCELLED.
     *
     * @param occurrenceId The ID of the occurrence to update.
     * @param newStatus The new status value.
     */
    suspend fun updateOccurrenceStatus(occurrenceId: Long, newStatus: String) {
        writeBarrier.checkWritesAllowed("RecurringLifecycleCoordinator.updateOccurrenceStatus")

        val now = timeProvider.now()
        // Load the current occurrence to get the old status
        val occurrence = occurrenceDao.getById(occurrenceId)
        val oldStatus = occurrence?.status
        occurrenceDao.updateStatus(listOf(occurrenceId), newStatus, now)

        // Write lifecycle event for skip/cancel transitions
        val eventType = when (newStatus) {
            "SKIPPED" -> "OCCURRENCE_SKIPPED"
            "CANCELLED" -> "OCCURRENCE_CANCELLED"
            "MISSED" -> "OCCURRENCE_SKIPPED"
            else -> null
        }
        if (eventType != null) {
            lifecycleEventDao.insert(
                RecurringLifecycleEvent(
                    occurrenceId = occurrenceId,
                    eventType = eventType,
                    occurredAt = now,
                    oldStatus = oldStatus,
                    newStatus = newStatus,
                    metadata = null
                )
            )
        }
    }

    /**
     * Returns all reminder deliveries whose scheduled time has passed and
     * whose status is still SCHEDULED (i.e. they are due to be dispatched).
     *
     * This is intended to be called by a [ReminderDispatchWorker] (WorkManager)
     * that runs periodically to check for and dispatch due reminders.
     *
     * @return The list of pending [RecurringReminderDelivery] items, ordered by scheduledAt.
     */
    suspend fun getDueReminders(): List<RecurringReminderDelivery> {
        // P4-CURRENT-005: Recover stale CLAIMED deliveries before querying due ones
        recoverStaleClaimedDeliveries()
        return reminderDeliveryDao.getPendingDeliveriesForPlannedOccurrences(timeProvider.now())
    }

    /**
     * P4-CURRENT-005: Resets CLAIMED deliveries older than [staleThresholdMs] back to SCHEDULED.
     * Prevents deliveries from being stuck forever if a worker crashes after claiming.
     */
    private suspend fun recoverStaleClaimedDeliveries(staleThresholdMs: Long = 300_000) {
        val staleThreshold = timeProvider.now() - staleThresholdMs
        val recovered = reminderDeliveryDao.recoverStaleClaimedDeliveries(staleThreshold)
        if (recovered > 0) {
            Timber.d("Recovered %d stale CLAIMED reminder deliveries", recovered)
        }
    }

    /**
     * Atomically claim a reminder delivery for processing.
     * Returns true if the claim succeeded, false if another worker already claimed it.
     * The caller should only dispatch a notification after a successful claim.
     */
    suspend fun claimReminderDelivery(deliveryId: Long): Boolean {
        writeBarrier.checkWritesAllowed("RecurringLifecycleCoordinator.claimReminderDelivery")
        return reminderDeliveryDao.claimDelivery(deliveryId, timeProvider.now()) > 0
    }

    /**
     * Marks a reminder delivery as SENT and records the timestamp.
     * Called by [BillReminderWorker] after dispatching the notification.
     */
    suspend fun markReminderSent(deliveryId: Long) {
        writeBarrier.checkWritesAllowed("RecurringLifecycleCoordinator.markReminderSent")

        val now = timeProvider.now()
        val existing = reminderDeliveryDao.getById(deliveryId) ?: return
        reminderDeliveryDao.update(
            existing.copy(
                status = "SENT",
                lastSentAt = now
            )
        )

        // Write lifecycle event
        try {
            lifecycleEventDao.insert(
                RecurringLifecycleEvent(
                    occurrenceId = existing.occurrenceId,
                    eventType = "REMINDER_SENT",
                    occurredAt = now,
                    oldStatus = null,
                    newStatus = null,
                    metadata = """{"deliveryId":$deliveryId}"""
                )
            )
        } catch (e: Exception) {
            Timber.w(e, "Non-critical: failed to write REMINDER_SENT event for delivery %d", deliveryId)
        }
    }

    suspend fun markReminderFailed(deliveryId: Long, reason: String) {
        writeBarrier.checkWritesAllowed("RecurringLifecycleCoordinator.markReminderFailed")
        val existing = reminderDeliveryDao.getById(deliveryId) ?: return
        val status = if (reason.contains("permission", ignoreCase = true)) "FAILED_PERMISSION" else "FAILED_TRANSIENT"
        val now = timeProvider.now()
        reminderDeliveryDao.update(
            existing.copy(status = status)
        )
        try {
            lifecycleEventDao.insert(
                RecurringLifecycleEvent(
                    occurrenceId = existing.occurrenceId,
                    eventType = "REMINDER_DELIVERY_FAILED",
                    occurredAt = now,
                    oldStatus = existing.status,
                    newStatus = status,
                    metadata = """{"deliveryId":$deliveryId,"reason":"$reason"}"""
                )
            )
        } catch (e: Exception) {
            Timber.w(e, "Non-critical: failed to write REMINDER_DELIVERY_FAILED event for delivery %d", deliveryId)
        }
    }

    /**
     * Dismisses a reminder delivery. Called from [DismissReminderReceiver] via goAsync.
     * Checks write barrier inside the transaction.
     */
    suspend fun dismissReminderDelivery(deliveryId: Long): ReminderActionResult {
        writeBarrier.checkWritesAllowed("RecurringLifecycleCoordinator.dismissReminderDelivery")
        val now = timeProvider.now()
        return database.withTransaction {
            writeBarrier.checkWritesAllowed("RecurringLifecycleCoordinator.dismissReminderDelivery.tx")
            val delivery = reminderDeliveryDao.getById(deliveryId)
                ?: return@withTransaction ReminderActionResult.NotFound

            // Only transition from non-terminal states
            if (delivery.status in TERMINAL_STATUSES) {
                return@withTransaction ReminderActionResult.NoOp("Already in terminal state: ${delivery.status}")
            }

            reminderDeliveryDao.update(delivery.copy(status = "DISMISSED", dismissedAt = now))
            lifecycleEventDao.insert(
                RecurringLifecycleEvent(
                    occurrenceId = delivery.occurrenceId,
                    eventType = "REMINDER_DISMISSED",
                    occurredAt = now,
                    oldStatus = delivery.status,
                    newStatus = "DISMISSED",
                    metadata = """{"deliveryId":$deliveryId}"""
                )
            )
            ReminderActionResult.Updated
        }
    }

    /**
     * Snoozes a reminder delivery for [snoozeMs] milliseconds. Called from [SnoozeReminderReceiver] via goAsync.
     * Checks write barrier inside the transaction.
     */
    suspend fun snoozeReminderDelivery(
        deliveryId: Long,
        snoozeMs: Long = 24L * 60L * 60L * 1000L
    ): ReminderActionResult {
        writeBarrier.checkWritesAllowed("RecurringLifecycleCoordinator.snoozeReminderDelivery")
        val now = timeProvider.now()
        val snoozedUntil = now + snoozeMs
        return database.withTransaction {
            writeBarrier.checkWritesAllowed("RecurringLifecycleCoordinator.snoozeReminderDelivery.tx")
            val delivery = reminderDeliveryDao.getById(deliveryId)
                ?: return@withTransaction ReminderActionResult.NotFound

            if (delivery.status in TERMINAL_STATUSES) {
                return@withTransaction ReminderActionResult.NoOp("Already in terminal state: ${delivery.status}")
            }

            reminderDeliveryDao.update(delivery.copy(status = "SNOOZED", snoozedUntil = snoozedUntil))
            lifecycleEventDao.insert(
                RecurringLifecycleEvent(
                    occurrenceId = delivery.occurrenceId,
                    eventType = "REMINDER_SNOOZED",
                    occurredAt = now,
                    oldStatus = delivery.status,
                    newStatus = "SNOOZED",
                    metadata = """{"deliveryId":$deliveryId,"snoozedUntil":$snoozedUntil}"""
                )
            )
            ReminderActionResult.Updated
        }
    }

    /**
     * Planned-vs-Actual reconciliation report for a recurring rule.
     *
     * @param totalPlanned Sum of expected amounts for all generated occurrences.
     * @param totalActual Sum of actual paid amounts for matched occurrences.
     * @param drift Difference: totalActual - totalPlanned (positive = overspend).
     * @param driftPercent Percentage drift relative to totalPlanned.
     * @param matchedCount Number of occurrences that were paid.
     * @param unmatchedCount Number of occurrences still PLANNED (no actual expense linked).
     * @param overBudgetCount Number of PAID occurrences where paidAmount > expectedAmount.
     */
    data class ReconciliationReport(
        val totalPlanned: Double,
        val totalActual: Double,
        val drift: Double,
        val driftPercent: Double,
        val matchedCount: Int,
        val unmatchedCount: Int,
        val overBudgetCount: Int
    )

    /**
     * Compares planned vs actual spending for a recurring rule over the past N months.
     *
     * **Note:** This method has write side-effects — it calls [generateOccurrences]
     * internally to ensure the database is up-to-date before computing the report.
     *
     * Logic:
     * 1. Generate occurrences for the past N months via [generateOccurrences].
     * 2. Load all occurrences in that range.
     * 3. Compare PAID occurrences (expectedAmount vs paidAmount).
     * 4. Count PLANNED occurrences that have no linked expense.
     * 5. Return a [ReconciliationReport] with drift analysis.
     *
     * @param ruleId The ID of the recurring rule.
     * @param monthsBack Number of months to look back (default 3).
     */
    suspend fun reconcilePlannedVsActual(ruleId: Long, monthsBack: Int = 3): ReconciliationReport {
        val now = timeProvider.now()
        val endDate = TimePeriodUtils.getStartOfDay(now)
        val startDate = TimePeriodUtils.getStartOfMonth(
            TimePeriodUtils.addMonths(now, -monthsBack)
        )

        // 1. Generate occurrences so the DB is up to date
        generateOccurrences(ruleId, startDate, endDate)

        // 2. Load all occurrences for this rule in the period
        val occurrences = occurrenceDao.getByDateRange(startDate, endDate)
            .filter { it.sourceType == SOURCE_TYPE_RECURRING_RULE && it.sourceId == ruleId }

        var totalPlanned = 0.0
        var totalActual = 0.0
        var matchedCount = 0
        var unmatchedCount = 0
        var overBudgetCount = 0

        for (occ in occurrences) {
            totalPlanned += occ.expectedAmount
            when (occ.status) {
                "PAID" -> {
                    val paid = occ.paidAmount ?: 0.0
                    totalActual += paid
                    matchedCount++
                    if (paid > occ.expectedAmount) {
                        overBudgetCount++
                    }
                }
                "PLANNED" -> {
                    unmatchedCount++
                }
                // Skip SKIPPED, CANCELLED, MISSED, IGNORED — they are not relevant
            }
        }

        val drift = totalActual - totalPlanned
        val driftPercent = if (totalPlanned > 0.0) {
            (drift / totalPlanned) * 100.0
        } else {
            0.0
        }

        return ReconciliationReport(
            totalPlanned = totalPlanned,
            totalActual = totalActual,
            drift = drift,
            driftPercent = driftPercent,
            matchedCount = matchedCount,
            unmatchedCount = unmatchedCount,
            overBudgetCount = overBudgetCount
        )
    }
}
