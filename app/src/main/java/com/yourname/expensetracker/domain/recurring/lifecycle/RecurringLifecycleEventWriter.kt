package com.yourname.expensetracker.domain.recurring.lifecycle

import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.dao.RecurringLifecycleEventDao
import com.yourname.expensetracker.data.database.entity.RecurringLifecycleEvent
import com.yourname.expensetracker.domain.util.TimeProvider
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Interface for writing recurring lifecycle events.
 * Critical events must never be swallowed; diagnostic events may be best-effort.
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

    suspend fun writeDiagnostic(
        occurrenceId: Long?,
        eventType: String,
        oldStatus: String? = null,
        newStatus: String? = null,
        metadata: String? = null,
        occurredAt: Long = 0L
    )
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

    override suspend fun writeDiagnostic(
        occurrenceId: Long?,
        eventType: String,
        oldStatus: String?,
        newStatus: String?,
        metadata: String?,
        occurredAt: Long
    ) {
        try {
            val at = if (occurredAt == 0L) timeProvider.now() else occurredAt
            // RP-02 U-004: barrier check before the diagnostic insert. A block
            // (DatabaseAccessBlockedException) is non-cancellation and therefore
            // retained under the documented best-effort semantics below — no row
            // is written during restore/maintenance.
            writeBarrier.checkWritesAllowed("recurring.lifecycle_event.diagnostic")
            dao.insert(
                RecurringLifecycleEvent(
                    occurrenceId = occurrenceId,
                    eventType = eventType,
                    occurredAt = at,
                    oldStatus = oldStatus,
                    newStatus = newStatus,
                    metadata = metadata
                )
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Timber.w(e, "Non-critical: failed to write diagnostic recurring event %s", eventType)
        }
    }
}
