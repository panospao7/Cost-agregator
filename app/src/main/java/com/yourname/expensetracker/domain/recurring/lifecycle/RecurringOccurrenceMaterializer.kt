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
import org.json.JSONObject
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
     * Persists resolved occurrences and creates reminder deliveries in a new transaction.
     * Delegates to [materializeInCurrentTransaction] inside `database.withTransaction`.
     */
    suspend fun materialize(
        resolved: List<OccurrenceConflictResolver.ResolvedOccurrence>,
        options: MaterializationOptions
    ): MaterializationResult = database.withTransaction {
        materializeInCurrentTransaction(resolved, options)
    }

    /**
     * Transaction-safe internal materialization. Call this from inside an existing
     * `database.withTransaction` block when materialization must be atomic with
     * other mutations (e.g., rule update regeneration).
     *
     * @param resolved The resolved occurrence candidates.
     * @param options Controls whether and how reminder deliveries are created.
     * @return Counts of created, updated, skipped occurrences and created reminders.
     */
    suspend fun materializeInCurrentTransaction(
        resolved: List<OccurrenceConflictResolver.ResolvedOccurrence>,
        options: MaterializationOptions
    ): MaterializationResult {
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
                    if (existing.status != entity.status && existing.status !in RecurringOccurrenceStatus.terminalDbValues) {
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
                                metadata = JSONObject().apply {
                                    put("oldStatus", existing.status)
                                    put("newStatus", entity.status)
                                }.toString()
                            )
                        )
                        finalStatus = entity.status

                        // P4-CURRENT-003: If materializer auto-transitions to PAID, fulfill planned and suppress reminders
                if (entity.status == "PAID") {
                    val expenseId = r.linkedExpenseId
                    if (expenseId != null) {
                        val fulfilled = plannedExpenseDao.fulfillByOccurrenceKey(entity.occurrenceKey, expenseId, now)
                        // P4-NEW-009: JSONObject.put() auto-escapes strings
                        lifecycleEventDao.insert(
                            RecurringLifecycleEvent(
                                occurrenceId = existing.id,
                                eventType = if (fulfilled > 0) "PLANNED_FULFILLED" else "PLANNED_FULFILLMENT_SKIPPED",
                                occurredAt = now,
                                oldStatus = if (fulfilled > 0) "PLANNED" else null,
                                newStatus = if (fulfilled > 0) "FULFILLED" else null,
                                metadata = JSONObject().apply {
                                    put("occurrenceKey", entity.occurrenceKey)
                                    put("expenseId", expenseId)
                                    put("rows", fulfilled)
                                    put("source", "materializer_auto_paid")
                                }.toString()
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
                                metadata = JSONObject().apply {
                                    put("occurrenceKey", entity.occurrenceKey)
                                    put("reason", "missing_linkedExpenseId")
                                    put("source", "materializer_auto_paid")
                                }.toString()
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
                        // P4-NEW-009: JSONObject.put() auto-escapes strings
                        lifecycleEventDao.insert(
                            RecurringLifecycleEvent(
                                occurrenceId = insertResult,
                                eventType = if (fulfilled > 0) "PLANNED_FULFILLED" else "PLANNED_FULFILLMENT_SKIPPED",
                                occurredAt = now,
                                oldStatus = if (fulfilled > 0) "PLANNED" else null,
                                newStatus = if (fulfilled > 0) "FULFILLED" else null,
                                metadata = JSONObject().apply {
                                    put("occurrenceKey", entity.occurrenceKey)
                                    put("expenseId", expenseId)
                                    put("rows", fulfilled)
                                    put("source", "materializer_auto_paid")
                                }.toString()
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
                                metadata = JSONObject().apply {
                                    put("occurrenceKey", entity.occurrenceKey)
                                    put("reason", "missing_linkedExpenseId")
                                    put("source", "materializer_auto_paid")
                                }.toString()
                            )
                        )
                    }
                    reminderDeliveryDao.suppressByOccurrenceId(insertResult, now)
                }

                // Write lifecycle event for newly created occurrence
                // P4-NEW-009: JSONObject.put() auto-escapes user-provided strings (merchant)
                lifecycleEventDao.insert(
                    RecurringLifecycleEvent(
                        occurrenceId = insertResult,
                        eventType = "OCCURRENCE_GENERATED",
                        occurredAt = now,
                        oldStatus = null,
                        newStatus = r.status,
                        metadata = JSONObject().apply {
                            put("merchant", r.candidate.merchant)
                            put("amount", r.candidate.expectedAmount)
                            put("dueDate", r.candidate.dueDate)
                        }.toString()
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
                        // P4-NEW-009: JSONObject.put() auto-escapes strings
                        lifecycleEventDao.insert(
                            RecurringLifecycleEvent(
                                occurrenceId = occurrenceId,
                                eventType = "REMINDER_SCHEDULE_SKIPPED",
                                occurredAt = now,
                                oldStatus = null,
                                newStatus = null,
                                metadata = JSONObject().apply {
                                    put("window", window)
                                    put("scheduledAt", scheduledAt)
                                    put("reason", "past_due_generation_disallowed")
                                    put("source", options.generationSource)
                                }.toString()
                            )
                        )
                        continue
                    }
                    val existingDelivery =
                        reminderDeliveryDao.getByOccurrenceAndWindow(occurrenceId, window)
                    if (existingDelivery == null) {
                        // P4-NEW-002: scheduledAt computed once above (line 225) —
                        // the redundant shadowing variable was removed.
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
                            // P4-NEW-009: JSONObject.put() auto-escapes window string
                            lifecycleEventDao.insert(
                                RecurringLifecycleEvent(
                                    occurrenceId = occurrenceId,
                                    eventType = "REMINDER_SCHEDULED",
                                    occurredAt = now,
                                    oldStatus = null,
                                    newStatus = "SCHEDULED",
                                    metadata = JSONObject().apply {
                                        put("window", window)
                                        put("scheduledAt", scheduledAt)
                                    }.toString()
                                )
                            )
                        }
                    }
                }
            }
        }

        return MaterializationResult(
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
         * Terminal occurrence statuses that are never auto-transitioned by the materializer.
         *
         * This references the shared [RecurringOccurrenceStatus.terminalDbValues] for consistency
         * with [RecurringOccurrenceTransitionPolicy].
         *
         * == Downgrade Protection Policy ==
         *
         * PAID, CANCELLED, SKIPPED, IGNORED, and MISSED are terminal. Once an occurrence reaches
         * one of these statuses, the materializer will never downgrade or overwrite it during
         * re-materialization — only PLANNED occurrences can be auto-transitioned.
         */
        @Deprecated("Use RecurringOccurrenceStatus.terminalDbValues directly instead.")
        private val TERMINAL_STATUSES = RecurringOccurrenceStatus.terminalDbValues
    }
}
