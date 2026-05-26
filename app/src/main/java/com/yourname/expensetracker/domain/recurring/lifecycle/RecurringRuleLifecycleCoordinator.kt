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
    private val lifecycleCoordinator: dagger.Lazy<RecurringLifecycleCoordinator>,
    private val expander: com.yourname.expensetracker.domain.recurring.RecurringOccurrenceExpander,
    private val resolver: com.yourname.expensetracker.domain.recurring.OccurrenceConflictResolver,
    private val materializer: RecurringOccurrenceMaterializer,
    private val expenseDao: com.yourname.expensetracker.data.database.dao.ExpenseDao
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
     * Updates a recurring rule and atomically regenerates all open rows in a single transaction.
     * If regeneration fails, the entire update rolls back.
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

            // Regenerate future occurrences atomically
            val regenerateStart = maxOf(
                normalized.nextDate,
                com.yourname.expensetracker.domain.util.TimePeriodUtils.getStartOfDay(now)
            )
            val regenerateEnd = com.yourname.expensetracker.domain.util.TimePeriodUtils.addMonths(regenerateStart, 12)

            // Expand future candidates from the updated rule
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
                sourceId = normalized.id
            )
            val candidates = expander.expand(request)
            val actualExpenses = expenseDao.getExpensesBetween(regenerateStart, regenerateEnd)
            val resolved = resolver.resolve(candidates, actualExpenses)

            // Materialize in the current transaction (atomic!)
            materializer.materializeInCurrentTransaction(
                resolved = resolved,
                options = RecurringOccurrenceMaterializer.MaterializationOptions(
                    createReminderDeliveries = true,
                    reminderWindows = RecurringLifecycleCoordinator.DEFAULT_REMINDER_WINDOWS,
                    generationSource = OccurrenceGenerationSource.RULE_UPDATE_REGENERATION.name,
                    allowPastDueReminderDeliveries = false
                )
            )

            // Write success event
            lifecycleEventDao.insert(
                RecurringLifecycleEvent(
                    occurrenceId = null,
                    eventType = "RULE_UPDATED_REGENERATED",
                    occurredAt = now,
                    oldStatus = null,
                    newStatus = null,
                    metadata = """{"ruleId":${updated.id},"oldAmount":${old.amount},"newAmount":${updated.amount},"oldFrequency":"${old.frequency}","newFrequency":"${updated.frequency}"}"""
                )
            )
        }
    }
}
