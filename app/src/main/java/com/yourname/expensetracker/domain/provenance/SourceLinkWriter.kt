package com.yourname.expensetracker.domain.provenance

import com.yourname.expensetracker.domain.provenance.TargetEntityType

/**
 * CURR-SL-01: Single entry point for creating source links.
 *
 * Responsibilities:
 * 1. Validate payload
 * 2. Build deterministic sourceIdentityKey
 * 3. Hash external IDs/fingerprints
 * 4. Validate/redact metadata
 * 5. Insert with IGNORE on conflict
 * 6. Return write result (never throw on duplicates)
 */
interface SourceLinkWriter {
    suspend fun linkExpense(
        expenseId: Long,
        payload: SourceLinkPayload,
        correlationId: String? = null
    ): SourceLinkWriteResult

    suspend fun linkTarget(
        targetType: TargetEntityType,
        targetId: Long,
        payload: SourceLinkPayload,
        correlationId: String? = null
    ): SourceLinkWriteResult

    suspend fun linkExpenseSourcesFromRequest(
        expenseId: Long,
        requestSourceFields: ExpenseSourceFields,
        correlationId: String? = null
    ): List<SourceLinkWriteResult>
}

/**
 * Legacy source-link fields from CreateExpenseRequest.
 */
data class ExpenseSourceFields(
    val rawNotificationId: Long? = null,
    val pendingReviewId: Long? = null,
    val scannedReceiptId: Long? = null,
    val emailReceiptSourceId: Long? = null,
    val groupId: Long? = null,
    val csvImportBatchId: String? = null,
    val csvRowNumber: Int? = null,
    val externalFingerprint: String? = null,
    val sourceName: String? = null,
    val providerId: String? = null,
    val operationRunId: Long? = null,
    val importBatchId: String? = null,
    val importRowNumber: Int? = null
)

// SourceLinkWriteResult is now defined in SourceLinkWriteResult.kt
