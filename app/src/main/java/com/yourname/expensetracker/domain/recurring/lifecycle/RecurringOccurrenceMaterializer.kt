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
    private val lifecycleEventDao: RecurringLifecycleEventDao
) {
    data class MaterializationResult(
        val created: Int = 0,
        val updated: Int = 0,
        val skipped: Int = 0,
        val remindersCreated: Int = 0
    )

    /**
     * Persists resolved occurrences and creates reminder deliveries.
     *
     * @param resolved The resolved occurrence candidates from [OccurrenceConflictResolver].
     * @param reminderWindows The reminder windows for which to create deliveries (e.g. "DUE_DAY").
     * @return Counts of created, updated, skipped occurrences and created reminders.
     */
    suspend fun materialize(
        resolved: List<OccurrenceConflictResolver.ResolvedOccurrence>,
        reminderWindows: List<String> = emptyList()
    ): MaterializationResult = database.withTransaction {
        var created = 0
        var updated = 0
        var skipped = 0
        var remindersCreated = 0
        val now = timeProvider.now()

        for (r in resolved) {
            val entity = buildEntity(r, now)
            val insertResult = occurrenceDao.insert(entity)
            val isPlanned = r.status == "PLANNED"

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
                        updated++
                    } else {
                        skipped++
                    }
                }
            } else {
                created++
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

            // Create reminder deliveries for PLANNED occurrences
            if (isPlanned) {
                val occurrenceId = if (insertResult != -1L) {
                    insertResult
                } else {
                    occurrenceDao.getByKey(entity.occurrenceKey)?.id ?: continue
                }

                for (window in reminderWindows) {
                    val existingDelivery =
                        reminderDeliveryDao.getByOccurrenceAndWindow(occurrenceId, window)
                    if (existingDelivery == null) {
                        val scheduledAt = computeScheduledAt(r.candidate.dueDate, window)
                        reminderDeliveryDao.insert(
                            RecurringReminderDelivery(
                                occurrenceId = occurrenceId,
                                reminderWindow = window,
                                scheduledAt = scheduledAt,
                                status = "SCHEDULED",
                                createdAt = now
                            )
                        )
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
     * - "OVERDUE" → scheduled at [dueDate]
     * - "{N}_DAYS_BEFORE" → scheduled at [dueDate] - N days
     */
    private fun computeScheduledAt(dueDate: Long, window: String): Long {
        return when {
            window == "DUE_DAY" || window == "OVERDUE" -> dueDate
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
        private val TERMINAL_STATUSES = setOf("PAID", "CANCELLED", "SKIPPED", "IGNORED", "MISSED")
    }
}
