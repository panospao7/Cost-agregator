package com.yourname.expensetracker.domain.recurring.lifecycle

import androidx.room.withTransaction
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ManualRecurringExpenseDao
import com.yourname.expensetracker.data.database.dao.PlannedExpenseDao
import com.yourname.expensetracker.data.database.dao.RecurringLifecycleEventDao
import com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao
import com.yourname.expensetracker.data.database.dao.RecurringReminderDeliveryDao
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.data.database.entity.RecurringLifecycleEvent
import com.yourname.expensetracker.domain.util.TimeProvider
import javax.inject.Inject
import javax.inject.Singleton
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
    private val lifecycleCoordinator: dagger.Lazy<RecurringLifecycleCoordinator>
) {
    companion object {
        private const val SOURCE_TYPE = RecurringLifecycleCoordinator.SOURCE_TYPE_RECURRING_RULE
    }

    /**
     * Deactivates a rule: sets isActive=false, cancels future PLANNED occurrences,
     * suppresses their reminders, and cancels their planned expenses.
     */
    suspend fun deactivateRule(ruleId: Long) {
        writeBarrier.checkWritesAllowed("RecurringRuleLifecycleCoordinator.deactivateRule")
        val now = timeProvider.now()
        val existing = manualRecurringExpenseDao.getById(ruleId)

        database.withTransaction {
            manualRecurringExpenseDao.setActiveStatus(ruleId, false)

            // Cancel future PLANNED occurrences
            val plannedIds = occurrenceDao.getPlannedIdsBySource(SOURCE_TYPE, ruleId)
            if (plannedIds.isNotEmpty()) {
                occurrenceDao.updateStatus(plannedIds, "CANCELLED", now)
                reminderDeliveryDao.deleteByOccurrenceIds(plannedIds)
            }

            // Cancel planned expenses for this rule
            plannedExpenseDao.cancelPlannedByRecurringRuleId(ruleId, now)

            lifecycleEventDao.insert(
                RecurringLifecycleEvent(
                    occurrenceId = null,
                    eventType = "RULE_DEACTIVATED",
                    occurredAt = now,
                    oldStatus = "ACTIVE",
                    newStatus = "INACTIVE",
                    metadata = """{"ruleId":$ruleId,"merchant":"${existing?.merchant.orEmpty()}"}"""
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
                    metadata = """{"ruleId":$ruleId,"merchant":"${existing?.merchant.orEmpty()}","amount":${existing?.amount ?: 0.0}}"""
                )
            )
        }
    }

    /**
     * Creates a new recurring rule and writes RULE_CREATED event atomically.
     */
    suspend fun createRule(expense: ManualRecurringExpense): Long {
        writeBarrier.checkWritesAllowed("RecurringRuleLifecycleCoordinator.createRule")
        val now = timeProvider.now()
        val entity = if (expense.createdAt == 0L) expense.copy(createdAt = now) else expense
        return database.withTransaction {
            val id = manualRecurringExpenseDao.insert(entity)
            lifecycleEventDao.insert(
                RecurringLifecycleEvent(
                    occurrenceId = null,
                    eventType = "RULE_CREATED",
                    occurredAt = now,
                    oldStatus = null,
                    newStatus = null,
                    metadata = """{"ruleId":$id,"merchant":"${entity.merchant}","amount":${entity.amount},"frequency":"${entity.frequency}"}"""
                )
            )
            id
        }
    }

    /**
     * Activates a previously deactivated rule, writes RULE_ACTIVATED event,
     * and generates future occurrences/reminders.
     */
    suspend fun activateRule(ruleId: Long) {
        writeBarrier.checkWritesAllowed("RecurringRuleLifecycleCoordinator.activateRule")
        val now = timeProvider.now()
        val existing = manualRecurringExpenseDao.getById(ruleId)
        database.withTransaction {
            manualRecurringExpenseDao.setActiveStatus(ruleId, true)
            lifecycleEventDao.insert(
                RecurringLifecycleEvent(
                    occurrenceId = null,
                    eventType = "RULE_ACTIVATED",
                    occurredAt = now,
                    oldStatus = "INACTIVE",
                    newStatus = "ACTIVE",
                    metadata = """{"ruleId":$ruleId,"merchant":"${existing?.merchant.orEmpty()}","isActive":true}"""
                )
            )
        }

        // Generate future occurrences for the reactivated rule
        val rule = existing ?: return
        val regenerateStart = maxOf(rule.nextDate, com.yourname.expensetracker.domain.util.TimePeriodUtils.getStartOfDay(now))
        val regenerateEnd = com.yourname.expensetracker.domain.util.TimePeriodUtils.addMonths(regenerateStart, 12)
        try {
            lifecycleCoordinator.get().generateOccurrences(
                ruleId = ruleId,
                startDate = regenerateStart,
                endDate = regenerateEnd,
                options = OccurrenceGenerationOptions(
                    createReminderDeliveries = true,
                    generationSource = OccurrenceGenerationSource.RULE_CREATE,
                    allowPastDueReminderDeliveries = false
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "Rule %d activated but occurrence generation failed", ruleId)
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
                    metadata = """{"ruleId":$ruleId,"nextDate":$nextDate}"""
                )
            )
        }
    }

    /**
     * Updates a recurring rule and regenerates open occurrences/planned rows/reminders.
     *
     * Algorithm:
     * 1. Delete open PLANNED occurrences and their reminders
     * 2. Delete open planned expenses for this rule
     * 3. Update the rule itself
     * 4. Write RULE_UPDATED_OPEN_ROWS_CLEARED event
     * 5. Immediately regenerate future occurrences/reminders with updated rule fields
     * 6. Write RULE_UPDATED_REGENERATED or RULE_REGENERATION_FAILED
     *
     * Terminal occurrences (PAID/SKIPPED/CANCELLED/MISSED) are preserved.
     */
    suspend fun updateRule(updated: ManualRecurringExpense) {
        writeBarrier.checkWritesAllowed("RecurringRuleLifecycleCoordinator.updateRule")
        val now = timeProvider.now()
        val old = manualRecurringExpenseDao.getById(updated.id)
            ?: throw IllegalArgumentException("Recurring rule not found: id=${updated.id}")

        val normalized = if (updated.createdAt == 0L) updated.copy(createdAt = old.createdAt) else updated

        database.withTransaction {
            // Delete open PLANNED occurrences and their reminder deliveries
            val openIds = occurrenceDao.getPlannedIdsBySource(SOURCE_TYPE, updated.id)
            if (openIds.isNotEmpty()) {
                reminderDeliveryDao.deleteByOccurrenceIds(openIds)
                occurrenceDao.deleteOpenPlannedBySource(SOURCE_TYPE, updated.id)
            }

            // Delete open planned expenses for this rule
            plannedExpenseDao.deleteOpenPlannedByRecurringRuleId(updated.id)

            // Update the rule itself
            manualRecurringExpenseDao.update(normalized)

            // Write lifecycle event
            lifecycleEventDao.insert(
                RecurringLifecycleEvent(
                    occurrenceId = null,
                    eventType = "RULE_UPDATED_OPEN_ROWS_CLEARED",
                    occurredAt = now,
                    oldStatus = null,
                    newStatus = null,
                    metadata = """{"ruleId":${updated.id},"oldAmount":${old.amount},"newAmount":${updated.amount},"oldFrequency":"${old.frequency}","newFrequency":"${updated.frequency}"}"""
                )
            )
        }

        // Regenerate future occurrences with updated rule fields
        // Use a 12-month horizon from the updated nextDate or today
        val regenerateStart = maxOf(updated.nextDate, com.yourname.expensetracker.domain.util.TimePeriodUtils.getStartOfDay(now))
        val regenerateEnd = com.yourname.expensetracker.domain.util.TimePeriodUtils.addMonths(regenerateStart, 12)
        try {
            lifecycleCoordinator.get().generateOccurrences(
                ruleId = updated.id,
                startDate = regenerateStart,
                endDate = regenerateEnd,
                options = OccurrenceGenerationOptions(
                    createReminderDeliveries = true,
                    generationSource = OccurrenceGenerationSource.RULE_UPDATE_REGENERATION,
                    allowPastDueReminderDeliveries = false
                )
            )
            // Write success event (best-effort, outside transaction)
            try {
                lifecycleEventDao.insert(
                    RecurringLifecycleEvent(
                        occurrenceId = null,
                        eventType = "RULE_UPDATED_REGENERATED",
                        occurredAt = now,
                        oldStatus = null,
                        newStatus = null,
                        metadata = """{"ruleId":${updated.id}}"""
                    )
                )
            } catch (_: Exception) { /* best-effort */ }
        } catch (e: Exception) {
            Timber.e(e, "Rule %d updated but regeneration failed", updated.id)
            try {
                lifecycleEventDao.insert(
                    RecurringLifecycleEvent(
                        occurrenceId = null,
                        eventType = "RULE_REGENERATION_FAILED",
                        occurredAt = now,
                        oldStatus = null,
                        newStatus = null,
                        metadata = """{"ruleId":${updated.id},"error":"${e.message}"}"""
                    )
                )
            } catch (_: Exception) { /* best-effort */ }
        }
    }
}
