package com.yourname.expensetracker.domain.provenance

import com.yourname.expensetracker.domain.transaction.ExpenseSource

/**
 * PR6: Builds source-link payloads for bank sync provenance.
 *
 * Safe fields only: syncRunId, providerTransactionHash, accountHash,
 * bookingDate, valueDate, transactionStatus.
 *
 * Never include: raw bank description, raw reference, IBAN, account number, card number.
 */
object BankSourceLinkPayloadFactory {

    /**
     * Creates a payload linking a BANK_SYNC_RUN to an EXPENSE.
     */
    fun forBankSyncToExpense(
        syncRunId: Long,
        providerTransactionHash: String? = null,
        accountHash: String? = null,
        bookingDate: String? = null,
        valueDate: String? = null,
        transactionStatus: String? = null
    ): SourceLinkPayload {
        val metadataMap = mutableMapOf<String, Any?>()
        providerTransactionHash?.let { metadataMap["providerTransactionHash"] = it }
        accountHash?.let { metadataMap["accountHash"] = it }
        bookingDate?.let { metadataMap["bookingDate"] = it }
        valueDate?.let { metadataMap["valueDate"] = it }
        transactionStatus?.let { metadataMap["transactionStatus"] = it }
        return SourceLinkPayload(
            sourceType = ExpenseSource.BANK_SYNC.name,
            sourceEntityType = SourceEntityType.BANK_SYNC_RUN,
            sourceEntityLocalId = syncRunId,
            role = SourceLinkRole.CREATED_FROM,
            status = SourceLinkStatus.ACTIVE,
            isPrimary = true,
            metadata = SafeProvenanceMetadata.fromMap(metadataMap)
        )
    }

    /**
     * Creates a payload linking a BANK_SYNC_RUN to a PENDING_REVIEW.
     */
    fun forBankSyncToReview(
        syncRunId: Long,
        providerTransactionHash: String? = null,
        accountHash: String? = null,
        bookingDate: String? = null,
        valueDate: String? = null,
        transactionStatus: String? = null
    ): SourceLinkPayload {
        val metadataMap = mutableMapOf<String, Any?>()
        providerTransactionHash?.let { metadataMap["providerTransactionHash"] = it }
        accountHash?.let { metadataMap["accountHash"] = it }
        bookingDate?.let { metadataMap["bookingDate"] = it }
        valueDate?.let { metadataMap["valueDate"] = it }
        transactionStatus?.let { metadataMap["transactionStatus"] = it }
        return SourceLinkPayload(
            sourceType = ExpenseSource.BANK_SYNC.name,
            sourceEntityType = SourceEntityType.BANK_SYNC_RUN,
            sourceEntityLocalId = syncRunId,
            role = SourceLinkRole.REVIEWED_FROM,
            status = SourceLinkStatus.ACTIVE,
            isPrimary = true,
            metadata = SafeProvenanceMetadata.fromMap(metadataMap)
        )
    }

    /**
     * Creates a payload for a bank sync dedupe match.
     */
    fun forDedupeMatch(
        syncRunId: Long,
        matchedExpenseId: Long,
        matchType: String
    ): SourceLinkPayload {
        val metadataMap = mutableMapOf<String, Any?>()
        metadataMap["matchedExpenseId"] = matchedExpenseId
        metadataMap["matchType"] = matchType
        return SourceLinkPayload(
            sourceType = ExpenseSource.BANK_SYNC.name,
            sourceEntityType = SourceEntityType.BANK_SYNC_RUN,
            sourceEntityLocalId = syncRunId,
            role = SourceLinkRole.DUPLICATE_MATCHED,
            status = SourceLinkStatus.DUPLICATE,
            isPrimary = false,
            metadata = SafeProvenanceMetadata.fromMap(metadataMap)
        )
    }
}
