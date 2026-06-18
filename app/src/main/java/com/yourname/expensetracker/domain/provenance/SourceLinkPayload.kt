package com.yourname.expensetracker.domain.provenance

/**
 * CURR-SL-01: Intent to create a source link.
 *
 * Contains raw inputs and safe metadata. The SourceLinkWriter transforms
 * this into a persisted EntitySourceLink with hashed external IDs and
 * validated metadata.
 */
data class SourceLinkPayload(
    val sourceType: String,               // ExpenseSource name
    val sourceEntityType: SourceEntityType,
    val sourceEntityLocalId: Long? = null,
    val externalId: String? = null,        // Will be hashed before persistence
    val externalFingerprint: String? = null, // Will be hashed before persistence
    val providerId: String? = null,
    val accountId: String? = null,         // Will be hashed before persistence
    val operationRunId: Long? = null,
    val importBatchId: String? = null,
    val importRowNumber: Int? = null,
    val role: SourceLinkRole,
    val status: SourceLinkStatus = SourceLinkStatus.ACTIVE,
    val confidence: Float? = null,
    val isPrimary: Boolean = false,
    val createdBy: String? = null,
    val metadata: SafeProvenanceMetadata = SafeProvenanceMetadata.empty()
)
