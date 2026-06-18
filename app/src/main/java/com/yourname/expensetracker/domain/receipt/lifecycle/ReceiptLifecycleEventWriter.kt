package com.yourname.expensetracker.domain.receipt.lifecycle

import com.yourname.expensetracker.data.database.dao.ReceiptEventDao
import com.yourname.expensetracker.data.database.entity.ReceiptEvent
import com.yourname.expensetracker.domain.diagnostics.EventMetadataSanitizer
import com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata
import com.yourname.expensetracker.domain.util.TimeProvider
import javax.inject.Inject
import javax.inject.Singleton

data class ReceiptLifecycleEvent(
    val receiptId: Long?,
    val sourceType: String,
    val documentType: String,
    val eventType: String,
    val oldStatus: String? = null,
    val newStatus: String? = null,
    val actor: String? = null,
    val message: String? = null,
    val metadata: SafeEventMetadata = SafeEventMetadata.empty(),
    val errorDetails: String? = null
)

interface ReceiptLifecycleEventWriter {
    suspend fun write(event: ReceiptLifecycleEvent)
}

@Singleton
class RoomReceiptLifecycleEventWriter @Inject constructor(
    private val dao: ReceiptEventDao,
    private val sanitizer: EventMetadataSanitizer,
    private val timeProvider: TimeProvider
) : ReceiptLifecycleEventWriter {

    override suspend fun write(event: ReceiptLifecycleEvent) {
        dao.insert(
            ReceiptEvent(
                receiptId = event.receiptId,
                sourceType = event.sourceType,
                documentType = event.documentType,
                eventType = event.eventType,
                occurredAt = timeProvider.now(),
                oldStatus = event.oldStatus,
                newStatus = event.newStatus,
                actor = event.actor,
                message = event.message,
                metadata = if (event.metadata.isEmpty()) null else event.metadata.toJson(),
                errorDetails = sanitizer.sanitizeExceptionMessage(event.errorDetails)
            )
        )
    }
}
