package com.yourname.expensetracker.domain.recurring.lifecycle

import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.dao.RecurringLifecycleEventDao
import com.yourname.expensetracker.data.database.entity.RecurringLifecycleEvent
import com.yourname.expensetracker.domain.util.TimeProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Interface for writing recurring lifecycle events.
 * Critical events must never be swallowed.
 */
interface RecurringLifecycleEventWriter {
    suspend fun writeCritical(
        occurrenceId: Long?,
        eventType: String,
        oldStatus: String? = null,
        newStatus: String? = null,
        metadata: String? = null,
        occurredAt: Long = 0L
    ): Long
}

/**
 * Room-backed implementation of [RecurringLifecycleEventWriter].
 */
@Singleton
class RoomRecurringLifecycleEventWriter @Inject constructor(
    private val dao: RecurringLifecycleEventDao,
    private val timeProvider: TimeProvider,
    // RP-02 U-004: barrier ownership at the recurring event write boundary.
    private val writeBarrier: DatabaseWriteBarrier
) : RecurringLifecycleEventWriter {
    override suspend fun writeCritical(
        occurrenceId: Long?,
        eventType: String,
        oldStatus: String?,
        newStatus: String?,
        metadata: String?,
        occurredAt: Long
    ): Long {
        val at = if (occurredAt == 0L) timeProvider.now() else occurredAt
        // RP-02 U-004: barrier check immediately before the critical event insert.
        // A block propagates as DatabaseAccessBlockedException so the enclosing
        // transaction fails — critical events are never swallowed.
        writeBarrier.checkWritesAllowed("recurring.lifecycle_event.critical")
        return dao.insert(
            RecurringLifecycleEvent(
                occurrenceId = occurrenceId,
                eventType = eventType,
                occurredAt = at,
                oldStatus = oldStatus,
                newStatus = newStatus,
                metadata = metadata
            )
        )
    }
}
