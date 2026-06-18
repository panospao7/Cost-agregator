package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * CURR-SL-01: Universal source-link entity for expense provenance.
 *
 * Links any target entity (expense, pending review, receipt) to its origin source.
 * External IDs are never stored plaintext — use hashes.
 */
@Entity(
    tableName = "entity_source_links",
    indices = [
        Index(value = ["targetEntityType", "targetEntityId"]),
        Index(value = ["sourceType"]),
        Index(value = ["sourceEntityType", "sourceEntityLocalId"]),
        Index(value = ["sourceIdentityKey"]),
        Index(value = ["operationRunId"]),
        Index(value = ["correlationId"]),
        Index(
            value = ["targetEntityType", "targetEntityId", "sourceIdentityKey"],
            unique = true
        )
    ]
)
data class EntitySourceLink(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // Target entity
    val targetEntityType: String,   // "EXPENSE", "PENDING_REVIEW", etc.
    val targetEntityId: Long,

    // High-level source
    val sourceType: String,         // "NOTIFICATION_AUTO_ACCEPT", "RECEIPT_SCAN", etc.

    // Concrete source object
    val sourceEntityType: String,   // "RAW_NOTIFICATION", "SCANNED_RECEIPT", etc.
    val sourceEntityLocalId: Long?, // Local Room entity ID (nullable for external sources)

    // Canonical identity key — non-null, deterministic, privacy-safe
    val sourceIdentityKey: String,  // e.g. "local:raw_notification:45"

    // External/privacy-safe identities (HMAC hashes, never plaintext)
    val externalIdHash: String?,
    val externalFingerprintHash: String?,

    // Provider/run context
    val providerId: String?,
    val accountIdHash: String?,
    val operationRunId: Long?,
    val importBatchId: String?,
    val importRowNumber: Int?,

    // Link semantics
    val linkRole: String,           // "CREATED_FROM", "APPROVED_FROM", etc.
    val linkStatus: String,         // "ACTIVE", "DUPLICATE", "SUPERSEDED", etc.
    val confidence: Float?,
    val isPrimary: Boolean,

    // Audit
    val createdAt: Long,
    val createdBy: String?,
    val correlationId: String?,
    val metadataJson: String?,
    val metadataSchemaVersion: Int = 1
)
