package com.yourname.expensetracker.domain.provenance

import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest

/**
 * Maps a [CreateExpenseRequest] into a list of [SourceLinkPayload] objects,
 * mirroring the mapping logic in [SourceLinkWriterImpl.linkExpenseSourcesFromRequest]
 * but returning payloads instead of writing them.
 *
 * This allows the [TransactionLifecycleCoordinator][com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator]
 * to call the mapper, then pass the resulting payloads to the writer inside
 * the same database transaction.
 *
 * Explicit `request.sourceLinks` are merged with legacy-field-derived payloads.
 * Duplicates by (sourceEntityType, sourceEntityLocalId) are deduplicated.
 */
object CreateExpenseSourceLinkMapper {

    fun fromRequest(request: CreateExpenseRequest): List<SourceLinkPayload> {
        val payloads = mutableListOf<SourceLinkPayload>()

        // Start with explicit sourceLinks from the modern API
        payloads.addAll(request.sourceLinks)

        // rawNotificationId → RAW_NOTIFICATION / CREATED_FROM
        request.rawNotificationId?.let { id ->
            payloads.add(
                SourceLinkPayload(
                    sourceType = request.source.name,
                    sourceEntityType = SourceEntityType.RAW_NOTIFICATION,
                    sourceEntityLocalId = id,
                    role = SourceLinkRole.CREATED_FROM,
                    isPrimary = true
                )
            )
        }

        // pendingReviewId → PENDING_REVIEW / APPROVED_FROM
        request.pendingReviewId?.let { id ->
            payloads.add(
                SourceLinkPayload(
                    sourceType = request.source.name,
                    sourceEntityType = SourceEntityType.PENDING_REVIEW,
                    sourceEntityLocalId = id,
                    role = SourceLinkRole.APPROVED_FROM,
                    isPrimary = true
                )
            )
        }

        // scannedReceiptId → SCANNED_RECEIPT / CREATED_FROM
        request.scannedReceiptId?.let { id ->
            payloads.add(
                SourceLinkPayload(
                    sourceType = request.source.name,
                    sourceEntityType = SourceEntityType.SCANNED_RECEIPT,
                    sourceEntityLocalId = id,
                    role = SourceLinkRole.CREATED_FROM,
                    isPrimary = true
                )
            )
        }

        // emailReceiptSourceId → EMAIL_RECEIPT_SOURCE / CREATED_FROM
        request.emailReceiptSourceId?.let { id ->
            payloads.add(
                SourceLinkPayload(
                    sourceType = request.source.name,
                    sourceEntityType = SourceEntityType.EMAIL_RECEIPT_SOURCE,
                    sourceEntityLocalId = id,
                    role = SourceLinkRole.CREATED_FROM,
                    isPrimary = true
                )
            )
        }

        // groupId → GROUP / GENERATED_FROM
        request.groupId?.let { id ->
            payloads.add(
                SourceLinkPayload(
                    sourceType = request.source.name,
                    sourceEntityType = SourceEntityType.GROUP,
                    sourceEntityLocalId = id,
                    role = SourceLinkRole.GENERATED_FROM,
                    isPrimary = true
                )
            )
        }

        // bankSyncRunId → BANK_SYNC_RUN / CREATED_FROM
        request.bankSyncRunId?.let { syncRunId ->
            val metadataMap = mutableMapOf<String, Any?>()
            request.bankProviderTransactionIdHash?.let { metadataMap["providerTransactionHash"] = it }
            request.bankAccountIdHash?.let { metadataMap["accountHash"] = it }
            val metadata = if (metadataMap.isNotEmpty()) {
                SafeProvenanceMetadata.fromMap(metadataMap)
            } else {
                SafeProvenanceMetadata.empty()
            }
            payloads.add(
                SourceLinkPayload(
                    sourceType = request.source.name,
                    sourceEntityType = SourceEntityType.BANK_SYNC_RUN,
                    sourceEntityLocalId = syncRunId,
                    role = SourceLinkRole.CREATED_FROM,
                    status = SourceLinkStatus.ACTIVE,
                    isPrimary = false,
                    operationRunId = syncRunId,
                    metadata = metadata
                )
            )
        }

        // fileImportRunId → FILE_IMPORT / CREATED_FROM
        request.fileImportRunId?.let { importRunId ->
            payloads.add(
                SourceLinkPayload(
                    sourceType = request.source.name,
                    sourceEntityType = SourceEntityType.FILE_IMPORT,
                    sourceEntityLocalId = importRunId,
                    role = SourceLinkRole.CREATED_FROM,
                    status = SourceLinkStatus.ACTIVE,
                    isPrimary = false,
                    operationRunId = importRunId
                )
            )
        }

        // csvImportBatchId + csvRowNumber → CSV_IMPORT_ROW / IMPORTED_FROM
        if (request.csvImportBatchId != null && request.csvRowNumber != null) {
            payloads.add(
                SourceLinkPayload(
                    sourceType = request.source.name,
                    sourceEntityType = SourceEntityType.CSV_IMPORT_ROW,
                    role = SourceLinkRole.IMPORTED_FROM,
                    isPrimary = true,
                    importBatchId = request.csvImportBatchId,
                    importRowNumber = request.csvRowNumber,
                    externalFingerprint = request.externalFingerprint
                )
            )
        }

        // externalFingerprint alone → source-specific external fingerprint
        // Only when no other source-specific fields are populated (or CSV fields are incomplete)
        if (request.externalFingerprint != null &&
            request.rawNotificationId == null &&
            request.pendingReviewId == null &&
            request.scannedReceiptId == null &&
            request.emailReceiptSourceId == null &&
            request.groupId == null &&
            (request.csvImportBatchId == null || request.csvRowNumber == null)
        ) {
            payloads.add(
                SourceLinkPayload(
                    sourceType = request.source.name,
                    sourceEntityType = SourceEntityType.UNKNOWN,
                    role = SourceLinkRole.CREATED_FROM,
                    externalFingerprint = request.externalFingerprint
                )
            )
        }

        // If no specific source fields and no explicit sourceLinks, create a legacy backfill link
        if (payloads.isEmpty()) {
            payloads.add(
                SourceLinkPayload(
                    sourceType = request.source.name,
                    sourceEntityType = SourceEntityType.LEGACY_SOURCE_ONLY,
                    role = SourceLinkRole.LEGACY_BACKFILL,
                    status = SourceLinkStatus.LEGACY_PARTIAL
                )
            )
        }

        // Deduplicate by (sourceEntityType, sourceEntityLocalId) — keep first occurrence
        return deduplicatePayloads(payloads)
    }

    /**
     * Removes duplicate payloads that describe the same source entity.
     * Keeps the first occurrence of each (sourceEntityType, sourceEntityLocalId) pair.
     * For payloads without a local ID, all are kept (they may differ by external ID, etc.).
     */
    private fun deduplicatePayloads(payloads: List<SourceLinkPayload>): List<SourceLinkPayload> {
        val seen = mutableSetOf<Pair<SourceEntityType, Long?>>()
        val result = mutableListOf<SourceLinkPayload>()

        for (payload in payloads) {
            val key = payload.sourceEntityType to payload.sourceEntityLocalId
            // Only deduplicate when there's a local ID to match on
            if (payload.sourceEntityLocalId != null) {
                if (seen.add(key)) {
                    result.add(payload)
                }
                // else: duplicate, skip
            } else {
                // No local ID — keep it (may differ by external fingerprint, import batch, etc.)
                result.add(payload)
            }
        }

        return result
    }
}
