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
 * Event types: CREATED, CLAIMED, EXPIRED, EXTENDED, TRANSFERRED
 */
@Entity(tableName = "warranty_lifecycle_events",
    indices = [Index("warrantyId"), Index("occurredAt")])
data class WarrantyLifecycleEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val warrantyId: Long,
    val eventType: String, // CREATED, CLAIMED, EXPIRED, EXTENDED, TRANSFERRED
    val occurredAt: Long,
    val description: String? = null,
    val metadata: String? = null
)
