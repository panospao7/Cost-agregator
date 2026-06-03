package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * W03: Audit trail for warranty lifecycle events.
 *
 * Records every state transition in a warranty's lifecycle so that
 * the UI can display a timeline and the system can analyse patterns
 * (e.g. average time-to-claim, most common expiry reasons).
 *
 * Event types: See [WarrantyLifecycleEventTypes].
 */
@Entity(tableName = "warranty_lifecycle_events",
    indices = [Index("warrantyId"), Index("occurredAt")])
data class WarrantyLifecycleEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val warrantyId: Long,
    val eventType: String, // See WarrantyLifecycleEventTypes
    val occurredAt: Long,
    val description: String? = null,
    val metadata: String? = null
)

object WarrantyLifecycleEventTypes {
    const val CREATED = "CREATED"
    const val CLAIMED = "CLAIMED"
    const val UPDATED = "UPDATED"
    const val DELETED = "DELETED"
    const val EXPIRED = "EXPIRED"
    const val AI_WARRANTY_CREATED = "AI_WARRANTY_CREATED"
    const val AI_EXTRACTION_DISCARDED = "AI_EXTRACTION_DISCARDED"
    const val RETURN_WINDOW_RETURNED = "RETURN_WINDOW_RETURNED"
}
