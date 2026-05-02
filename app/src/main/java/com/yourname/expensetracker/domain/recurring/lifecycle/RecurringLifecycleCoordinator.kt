package com.yourname.expensetracker.domain.recurring.lifecycle

import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.ManualRecurringExpenseDao
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
import kotlin.math.abs

/**
 * Orchestrates the full lifecycle of recurring-expense occurrences:
 * expansion, conflict resolution, materialization, and linkage.
 *
 * This is the primary entry point for generating and managing recurring
 * occurrences from manual recurring rules.
 */
@Singleton
class RecurringLifecycleCoordinator @Inject constructor(
    private val expander: RecurringOccurrenceExpander,
    private val resolver: OccurrenceConflictResolver,
    private val materializer: RecurringOccurrenceMaterializer,
    private val occurrenceDao: RecurringOccurrenceDao,
    private val expenseDao: ExpenseDao,
    private val timeProvider: TimeProvider,
    private val manualRecurringExpenseDao: ManualRecurringExpenseDao,
    private val reminderDeliveryDao: RecurringReminderDeliveryDao,
    private val lifecycleEventDao: RecurringLifecycleEventDao,
    private val restoreMaintenanceMode: RestoreMaintenanceMode
) {
    companion object {
        /** Source type used for manual recurring rules. */
        const val SOURCE_TYPE_RECURRING_RULE = "RECURRING_RULE"
    }

    /**
     * Expands, resolves, and materializes occurrences for a recurring rule
     * within a date range.
     *
     * @param ruleId The ID of the [ManualRecurringExpense] rule.
     * @param startDate Start of the range (inclusive, epoch ms).
     * @param endDate End of the range (exclusive, epoch ms).
     * @param reminderWindows Reminder window names to schedule.
     * @return The [MaterializationResult] from persisting.
     * @throws IllegalArgumentException if the rule is not found.
     */
    suspend fun generateOccurrences(
        ruleId: Long,
        startDate: Long,
        endDate: Long,
        reminderWindows: List<String> = listOf("DUE_DAY")
    ): MaterializationResult {
        // Guard: block writes during restore maintenance mode
        if (!restoreMaintenanceMode.isWritesAllowed()) {
            throw IllegalStateException("Database writes blocked during restore")
        }

        val rule = manualRecurringExpenseDao.getById(ruleId)
            ?: throw IllegalArgumentException("Recurring rule not found: id=$ruleId")

        // Use rule.nextDate as the expansion anchor. If it's before startDate,
        // advance it by the frequency until it falls within the range.
        var anchorDate = rule.nextDate
        while (anchorDate < startDate && rule.frequency != RecurrenceFrequency.IRREGULAR) {
            anchorDate = expander.advanceDate(anchorDate, rule.frequency)
        }

        val request = RecurringOccurrenceExpander.ExpandRequest(
            merchant = rule.merchant,
            amount = rule.amount,
            currency = rule.currency,
            frequency = rule.frequency,
            categoryId = null, // ManualRecurringExpense does not carry a categoryId
            startDate = startDate,
            endDate = endDate,
            anchorDate = anchorDate,
            sourceType = SOURCE_TYPE_RECURRING_RULE,
            sourceId = rule.id
        )

        val candidates = expander.expand(request)
        val actualExpenses = expenseDao.getExpensesBetween(startDate, endDate)
        val resolved = resolver.resolve(candidates, actualExpenses)

        return materializer.materialize(resolved, reminderWindows)
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
        occurrenceDao.update(
            match.copy(
                status = "PAID",
                linkedExpenseId = expenseId,
                paidAmount = expense.amount,
                paidCurrency = expense.currency,
                paidAt = now,
                updatedAt = now
            )
        )

        // Write lifecycle event: OCCURRENCE_PAID
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
     * Gets all occurrences for a given source/rule.
     *
     * @param ruleId The ID of the recurring rule.
     * @return The list of occurrences, ordered by due date.
     */
    suspend fun getOccurrences(ruleId: Long): List<RecurringOccurrence> {
        return occurrenceDao.getBySource(SOURCE_TYPE_RECURRING_RULE, ruleId)
    }

    /**
     * Marks an occurrence as SKIPPED, MISSED, or CANCELLED.
     *
     * @param occurrenceId The ID of the occurrence to update.
     * @param newStatus The new status value.
     */
    suspend fun updateOccurrenceStatus(occurrenceId: Long, newStatus: String) {
        // Guard: block writes during restore maintenance mode
        if (!restoreMaintenanceMode.isWritesAllowed()) {
            throw IllegalStateException("Database writes blocked during restore")
        }

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
        return reminderDeliveryDao.getPendingDeliveries(timeProvider.now())
    }

    /**
     * Marks a reminder delivery as SENT and records the timestamp.
     * Called by [BillReminderWorker] after dispatching the notification.
     */
    /**
     * Marks a reminder delivery as SENT and records the timestamp.
     * Called by [BillReminderWorker] after dispatching the notification.
     */
    suspend fun markReminderSent(deliveryId: Long) {
        val now = timeProvider.now()
        val existing = reminderDeliveryDao.getById(deliveryId) ?: return
        reminderDeliveryDao.update(
            existing.copy(
                status = "SENT",
                lastSentAt = now
            )
        )
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
