package com.yourname.expensetracker.domain.provenance

import com.yourname.expensetracker.domain.transaction.ExpenseSource

/**
 * PR4: Builds source-link payloads for receipt and email provenance.
 *
 * Safe fields only: receiptId, expenseId, emailReceiptSourceId, provider,
 * matchType, confidence, messageIdHash, contentFingerprintHash.
 *
 * Never include: raw email subject/body/sender, raw message IDs, raw OCR text, raw image paths.
 */
object ReceiptSourceLinkPayloadFactory {

    /**
     * Creates a payload linking an EMAIL_RECEIPT_SOURCE to a SCANNED_RECEIPT.
     */
    fun forEmailReceiptToScannedReceipt(
        emailReceiptSourceId: Long,
        scannedReceiptId: Long,
        provider: String,
        messageIdHash: String? = null,
        contentFingerprintHash: String? = null
    ): SourceLinkPayload {
        val metadataMap = mutableMapOf<String, Any?>()
        metadataMap["provider"] = provider
        messageIdHash?.let { metadataMap["messageIdHash"] = it }
        contentFingerprintHash?.let { metadataMap["contentFingerprintHash"] = it }
        val metadata = SafeProvenanceMetadata.fromMap(metadataMap)

        return SourceLinkPayload(
            sourceType = ExpenseSource.EMAIL_RECEIPT.name,
            sourceEntityType = SourceEntityType.EMAIL_RECEIPT_SOURCE,
            sourceEntityLocalId = emailReceiptSourceId,
            role = SourceLinkRole.CREATED_FROM,
            status = SourceLinkStatus.ACTIVE,
            isPrimary = true,
            metadata = metadata
        )
    }

    /**
     * Creates a payload linking a SCANNED_RECEIPT to an EXPENSE.
     */
    fun forScannedReceiptToExpense(
        scannedReceiptId: Long,
        linkType: String
    ): SourceLinkPayload {
        val role = when (linkType) {
            "DIRECT_SAVE", "REVIEW_APPROVAL" -> SourceLinkRole.CREATED_FROM
            "EMAIL_RECEIPT" -> SourceLinkRole.LINKED_PROOF
            else -> SourceLinkRole.LINKED_PROOF
        }
        return SourceLinkPayload(
            sourceType = ExpenseSource.RECEIPT_SCAN.name,
            sourceEntityType = SourceEntityType.SCANNED_RECEIPT,
            sourceEntityLocalId = scannedReceiptId,
            role = role,
            status = SourceLinkStatus.ACTIVE,
            isPrimary = true
        )
    }

    /**
     * Creates a payload for a duplicate match.
     */
    fun forDuplicateMatch(
        sourceEntityType: SourceEntityType,
        sourceEntityLocalId: Long?,
        existingExpenseId: Long,
        matchType: String
    ): SourceLinkPayload {
        val metadata = SafeProvenanceMetadata.fromMap(
            mapOf("matchType" to matchType, "matchedExpenseId" to existingExpenseId)
        )
        return SourceLinkPayload(
            sourceType = ExpenseSource.RECEIPT_SCAN.name,
            sourceEntityType = sourceEntityType,
            sourceEntityLocalId = sourceEntityLocalId,
            role = SourceLinkRole.DUPLICATE_MATCHED,
            status = SourceLinkStatus.DUPLICATE,
            isPrimary = false,
            metadata = metadata
        )
    }
}
