package com.yourname.expensetracker.domain.provenance

import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.domain.transaction.ExpenseSource

/**
 * PR3: Maps a [PendingReview] into source-link payloads.
 *
 * Mapping rules:
 * - `rawNotificationId` present → RAW_NOTIFICATION / REVIEWED_FROM, isPrimary=true
 * - `scannedReceiptId` present → SCANNED_RECEIPT / REVIEWED_FROM, isPrimary=(rawNotificationId==null)
 * - Both null → empty list
 * - Includes confidence from review
 * - Metadata uses SafeProvenanceMetadata with allowed keys only
 */
object PendingReviewSourcePayloadFactory {

    fun fromReview(
        review: PendingReview,
        sourceType: ExpenseSource = ExpenseSource.REVIEW_APPROVAL,
        context: PendingReviewSourceContext = PendingReviewSourceContext.empty()
    ): List<SourceLinkPayload> {
        val payloads = mutableListOf<SourceLinkPayload>()
        val sourceTypeName = sourceType.name

        // Build safe metadata from context
        val metadataMap = mutableMapOf<String, Any?>()
        context.stage?.let { metadataMap["stage"] = it }
        context.reason?.let { metadataMap["reason"] = it }
        context.confidence?.let { metadataMap["confidence"] = it }
        context.extractionState?.let { metadataMap["extractionState"] = it }
        context.routingDecision?.let { metadataMap["routingDecision"] = it }
        context.parserId?.let { metadataMap["parserId"] = it }
        context.parserVersion?.let { metadataMap["parserVersion"] = it }

        // Add review confidence as fallback if context doesn't provide one
        if (context.confidence == null) {
            metadataMap["confidence"] = review.confidence
        }

        val metadata = SafeProvenanceMetadata.fromMap(metadataMap)

        // rawNotificationId → RAW_NOTIFICATION / REVIEWED_FROM, isPrimary=true
        review.rawNotificationId?.let { rawId ->
            payloads.add(
                SourceLinkPayload(
                    sourceType = sourceTypeName,
                    sourceEntityType = SourceEntityType.RAW_NOTIFICATION,
                    sourceEntityLocalId = rawId,
                    role = SourceLinkRole.REVIEWED_FROM,
                    status = SourceLinkStatus.ACTIVE,
                    confidence = review.confidence,
                    isPrimary = true,
                    metadata = metadata
                )
            )
        }

        // scannedReceiptId → SCANNED_RECEIPT / REVIEWED_FROM, isPrimary=(rawNotificationId==null)
        review.scannedReceiptId?.let { receiptId ->
            payloads.add(
                SourceLinkPayload(
                    sourceType = sourceTypeName,
                    sourceEntityType = SourceEntityType.SCANNED_RECEIPT,
                    sourceEntityLocalId = receiptId,
                    role = SourceLinkRole.REVIEWED_FROM,
                    status = SourceLinkStatus.ACTIVE,
                    confidence = review.confidence,
                    isPrimary = review.rawNotificationId == null,
                    metadata = metadata
                )
            )
        }

        return payloads
    }
}
