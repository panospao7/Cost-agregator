package com.yourname.expensetracker.domain.recurring.lifecycle

import com.yourname.expensetracker.data.database.dao.RecurringLifecycleEventDao
import com.yourname.expensetracker.data.database.entity.RecurringLifecycleEvent
import com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata
import com.yourname.expensetracker.domain.util.TimeProvider
import javax.inject.Inject
import javax.inject.Singleton

data class RecurringLifecycleEventModel(
    val occurrenceId: Long?,
    val eventType: String,
    val oldStatus: String? = null,
    val newStatus: String? = null,
    val metadata: SafeEventMetadata = SafeEventMetadata.empty()
)

interface RecurringLifecycleEventWriter {
    suspend fun write(event: RecurringLifecycleEventModel)
}

@Singleton
class RoomRecurringLifecycleEventWriter @Inject constructor(
    private val dao: RecurringLifecycleEventDao,
    private val timeProvider: TimeProvider
) : RecurringLifecycleEventWriter {

    override suspend fun write(event: RecurringLifecycleEventModel) {
        dao.insert(
            RecurringLifecycleEvent(
                occurrenceId = event.occurrenceId,
                eventType = event.eventType,
                occurredAt = timeProvider.now(),
                oldStatus = event.oldStatus,
                newStatus = event.newStatus,
                metadata = if (event.metadata.isEmpty()) null else event.metadata.toJson()
            )
        )
    }
}
