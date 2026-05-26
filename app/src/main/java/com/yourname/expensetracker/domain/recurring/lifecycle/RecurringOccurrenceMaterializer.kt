package com.yourname.expensetracker.domain.recurring.lifecycle

import androidx.room.withTransaction
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.RecurringLifecycleEventDao
import com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao
import com.yourname.expensetracker.data.database.dao.RecurringReminderDeliveryDao
import com.yourname.expensetracker.data.database.entity.RecurringLifecycleEvent
import com.yourname.expensetracker.data.database.entity.RecurringOccurrence
import com.yourname.expensetracker.data.database.entity.RecurringReminderDelivery
import com.yourname.expensetracker.domain.recurring.OccurrenceConflictResolver
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists resolved occurrences and creates reminder deliveries.
 *
 * - New occurrences: INSERT with IGNORE (occurrenceKey unique constraint handles dedup)
 * - Existing occurrences: UPDATE if status changed (e.g., PLANNED → PAID)
 * - For PLANNED occurrences: create reminder deliveries for configured windows
 *
 * All persistence operations within [materialize] are wrapped in a single
 * Room transaction so that occurrence inserts/updates and delivery inserts
 * are atomic.
 */
@Singleton
class RecurringOccurrenceMaterializer @Inject constructor(
    private val database: AppDatabase,
    private val occurrenceDao: RecurringOccurrenceDao,
    private val reminderDeliveryDao: RecurringReminderDeliveryDao,
    private val timeProvider: TimeProvider,
    private val lifecycleEventDao: RecurringLifecycleEventDao,
    private val plannedExpenseDao: com.yourname.expensetracker.data.database.dao.PlannedExpenseDao
) {
    data class MaterializationResult(
        val created: Int = 0,
        val updated: Int = 0,
        val skipped: Int = 0,
        val remindersCreated: Int = 0
    )

    /**
     * Options controlling reminder-delivery creation during materialization.
     */
    data class MaterializationOptions(
        val createReminderDeliveries: Boolean,
        val reminderWindows: List<String>,
        val generationSource: String,
        val allowPastDueReminderDeliveries: Boolean = false
    )

    /**
     * Persists resolved occurrences and creates reminder deliveries.
     *
     * @param resolved The resolved occurrence candidates from [OccurrenceConflictResolver].
     * @param options Controls whether and how reminder deliveries are created.
     * @return Counts of created, updated, skipped occurrences and created reminders.
     */
    suspend fun materialize(
        resolved: List<OccurrenceConflictResolver.ResolvedOccurrence>,
        options: MaterializationOptions
    ): MaterializationResult = database.withTransaction {
        var created = 0
        var updated = 0
        var skipped = 0
        var remindersCreated = 0
        val now = timeProvider.now()

        for (r in resolved) {
            val entity = buildEntity(r, now)
            val insertResult = occurrenceDao.insert(entity)

            // P4-CURRENT-004: Determine the final persisted status to decide on reminders
            var finalStatus = r.status

            if (insertResult == -1L) {
                // Already exists — load existing and update if status changed
                val existing = occurrenceDao.getByKey(entity.occurrenceKey)
                if (existing != null) {
                    if (existing.status != entity.status && existing.status !in TERMINAL_STATUSES) {
                        occurrenceDao.update(
                            entity.copy(
                                id = existing.id,
                                createdAt = existing.createdAt,
                                updatedAt = now
                            )
                        )
                        lifecycleEventDao.insert(
                            RecurringLifecycleEvent(
                                occurrenceId = existing.id,
                                eventType = "OCCURRENCE_STATUS_CHANGED",
                                occurredAt = now,
                                oldStatus = existing.status,
                                newStatus = entity.status,
                                metadata = "{\"oldStatus\":\"${existing.status}\",\"newStatus\":\"${entity.status}\"}"
                            )
                        )
                        finalStatus = entity.status

                        // P4-CURRENT-003: If materializer auto-transitions to PAID, fulfill planned and suppress reminders
                        if (entity.status == "PAID") {
                            val expenseId = r.linkedExpenseId
                            if (expenseId != null) {
                                val fulfilled = plannedExpenseDao.fulfillByOccurrenceKey(entity.occurrenceKey, expenseId, now)
                                lifecycleEventDao.insert(
                                    RecurringLifecycleEvent(
                                        occurrenceId = existing.id,
                                        eventType = if (fulfilled > 0) "PLANNED_FULFILLED" else "PLANNED_FULFILLMENT_SKIPPED",
                                        occurredAt = now,
                                        oldStatus = if (fulfilled > 0) "PLANNED" else null,
                                        newStatus = if (fulfilled > 0) "FULFILLED" else null,
                                        metadata = """{"occurrenceKey":"${entity.occurrenceKey}","expenseId":$expenseId,"rows":$fulfilled,"source":"materializer_auto_paid"}"""
                                    )
                                )
                            } else {
                                lifecycleEventDao.insert(
                                    RecurringLifecycleEvent(
                                        occurrenceId = existing.id,
                                        eventType = "PLANNED_FULFILLMENT_SKIPPED",
                                        occurredAt = now,
                                        oldStatus = null,
                                        newStatus = null,
                                        metadata = """{"occurrenceKey":"${entity.occurrenceKey}","reason":"missing_linkedExpenseId","source":"materializer_auto_paid"}"""
                                    )
                                )
                            }
                            reminderDeliveryDao.suppressByOccurrenceId(existing.id, now)
                        }

                        updated++
                    } else {
                        finalStatus = existing.status
                        skipped++
                    }
                }
            } else {
                created++

                // P4-CURRENT-003: If newly created as PAID, fulfill planned and suppress reminders
                if (entity.status == "PAID") {
                    val expenseId = r.linkedExpenseId
                    if (expenseId != null) {
                        val fulfilled = plannedExpenseDao.fulfillByOccurrenceKey(entity.occurrenceKey, expenseId, now)
                        lifecycleEventDao.insert(
                            RecurringLifecycleEvent(
                                occurrenceId = insertResult,
                                eventType = if (fulfilled > 0) "PLANNED_FULFILLED" else "PLANNED_FULFILLMENT_SKIPPED",
                                occurredAt = now,
                                oldStatus = if (fulfilled > 0) "PLANNED" else null,
                                newStatus = if (fulfilled > 0) "FULFILLED" else null,
                                metadata = """{"occurrenceKey":"${entity.occurrenceKey}","expenseId":$expenseId,"rows":$fulfilled,"source":"materializer_auto_paid"}"""
                            )
                        )
                    } else {
                        lifecycleEventDao.insert(
                            RecurringLifecycleEvent(
                                occurrenceId = insertResult,
                                eventType = "PLANNED_FULFILLMENT_SKIPPED",
                                occurredAt = now,
                                oldStatus = null,
                                newStatus = null,
                                metadata = """{"occurrenceKey":"${entity.occurrenceKey}","reason":"missing_linkedExpenseId","source":"materializer_auto_paid"}"""
                            )
                        )
                    }
                    reminderDeliveryDao.suppressByOccurrenceId(insertResult, now)
                }

                // Write lifecycle event for newly created occurrence
                lifecycleEventDao.insert(
                    RecurringLifecycleEvent(
                        occurrenceId = insertResult,
                        eventType = "OCCURRENCE_GENERATED",
                        occurredAt = now,
                        oldStatus = null,
                        newStatus = r.status,
                        metadata = """{"merchant":"${r.candidate.merchant}","amount":${r.candidate.expectedAmount},"dueDate":${r.candidate.dueDate}}"""
                    )
                )
            }

            // P4-CURRENT-004: Only create reminder deliveries when finalStatus is PLANNED
            // and the caller explicitly opted in via MaterializationOptions.
            if (finalStatus == "PLANNED" && options.createReminderDeliveries) {
                val occurrenceId = if (insertResult != -1L) {
                    insertResult
                } else {
                    occurrenceDao.getByKey(entity.occurrenceKey)?.id ?: continue
                }

                for (window in options.reminderWindows) {
                    val scheduledAt = computeScheduledAt(r.candidate.dueDate, window)

                    // Skip past-due reminders unless explicitly allowed
                    if (!options.allowPastDueReminderDeliveries && scheduledAt < now) {
                        lifecycleEventDao.insert(
                            RecurringLifecycleEvent(
                                occurrenceId = occurrenceId,
                                eventType = "REMINDER_SCHEDULE_SKIPPED",
                                occurredAt = now,
                                oldStatus = null,
                                newStatus = null,
                                metadata = """{"window":"$window","scheduledAt":$scheduledAt,"reason":"past_due_generation_disallowed","source":"${options.generationSource}"}"""
                            )
                        )
                        continue
                    }
                    val existingDelivery =
                        reminderDeliveryDao.getByOccurrenceAndWindow(occurrenceId, window)
                    if (existingDelivery == null) {
                        val scheduledAt = computeScheduledAt(r.candidate.dueDate, window)
                        // P4-CURRENT-015: Check insert return value before counting
                        val deliveryId = reminderDeliveryDao.insert(
                            RecurringReminderDelivery(
                                occurrenceId = occurrenceId,
                                reminderWindow = window,
                                scheduledAt = scheduledAt,
                                status = "SCHEDULED",
                                createdAt = now,
                                updatedAt = now
                            )
                        )
                        if (deliveryId > 0) {
                            remindersCreated++

                            // Write lifecycle event for scheduled reminder
                            lifecycleEventDao.insert(
                                RecurringLifecycleEvent(
                                    occurrenceId = occurrenceId,
                                    eventType = "REMINDER_SCHEDULED",
                                    occurredAt = now,
                                    oldStatus = null,
                                    newStatus = "SCHEDULED",
                                    metadata = """{"window":"$window","scheduledAt":$scheduledAt}"""
                                )
                            )
                        }
                    }
                }
            }
        }

        MaterializationResult(
            created = created,
            updated = updated,
            skipped = skipped,
            remindersCreated = remindersCreated
        )
    }

    /**
     * Builds a [RecurringOccurrence] entity from a resolved occurrence.
     */
    private fun buildEntity(
        resolved: OccurrenceConflictResolver.ResolvedOccurrence,
        now: Long
    ): RecurringOccurrence {
        val candidate = resolved.candidate
        return RecurringOccurrence(
            sourceType = candidate.sourceType,
            sourceId = candidate.sourceId,
            occurrenceKey = candidate.occurrenceKey,
            dueDate = candidate.dueDate,
            status = resolved.status,
            linkedExpenseId = resolved.linkedExpenseId,
            expectedAmount = candidate.expectedAmount,
            expectedCurrency = candidate.expectedCurrency,
            paidAt = if (resolved.status == "PAID") now else null,
            paidAmount = resolved.paidAmount,
            paidCurrency = resolved.paidCurrency,
            frequency = candidate.frequency,
            merchant = candidate.merchant,
            categoryId = candidate.categoryId,
            createdAt = now,
            updatedAt = now
        )
    }

    /**
     * Computes the scheduled-at timestamp for a reminder delivery based on the
     * occurrence due date and the reminder window name.
     *
     * Supported window formats:
     * - "DUE_DAY" → scheduled at [dueDate]
     * - "OVERDUE" → scheduled at [dueDate] + 1 day
     * - "{N}_DAYS_BEFORE" → scheduled at [dueDate] - N days
     */
    private fun computeScheduledAt(dueDate: Long, window: String): Long {
        return when {
            window == "DUE_DAY" -> dueDate
            window == "OVERDUE" -> TimePeriodUtils.addDays(dueDate, 1)
            window.endsWith("_DAYS_BEFORE") -> {
                val prefix = window.removeSuffix("_DAYS_BEFORE")
                val days = prefix.toIntOrNull()
                if (days != null && days > 0) {
                    TimePeriodUtils.addDays(dueDate, -days)
                } else {
                    dueDate
                }
            }
            else -> dueDate
        }
    }

    companion object {
        /**
         * Terminal statuses that are never auto-transitioned.
         *
         * == Downgrade Protection Policy ==
         *
         * PAID, CANCELLED, SKIPPED, IGNORED, and MISSED are terminal
         * occurrence statuses. Once an occurrence reaches one of these
         * statuses, the materializer will never downgrade or overwrite it
         * during re-materialization — only PLANNED occurrences can be
         * auto-transitioned (e.g. to PAID when linked to an expense, or
         * to SKIPPED/MISSED when their due date passes unresolved).
         *
         * This is enforced by the guard at line 69 (`existing.status !in TERMINAL_STATUSES`)
         * which skips the update if the existing row already has a terminal status.
         * The policy prevents:
         *  - A manually skipped occurrence from being re-materialized back to PLANNED.
         *  - A paid occurrence from being downgraded if the linked expense is also a
         *    valid match for a later re-run of the resolver.
         *  - A cancelled recurrence from being silently resurrected by the expander.
         *
         * Status transitions are only allowed by explicit lifecycle operations
         * (e.g. [RecurringLifecycleCoordinator.unlinkExpenseFromOccurrence])
         * which bypass the materializer and use their own transaction guard.
         */
        private val TERMINAL_STATUSES = setOf("PAID", "CANCELLED", "SKIPPED", "IGNORED", "MISSED")
    }
}
