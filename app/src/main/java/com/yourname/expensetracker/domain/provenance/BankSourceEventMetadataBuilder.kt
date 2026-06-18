package com.yourname.expensetracker.domain.provenance

import org.json.JSONObject

/**
 * P0: Builds safe JSON metadata summaries for bank sync source-link events.
 *
 * Privacy rules:
 * - Never include raw provider transaction IDs, account numbers, IBANs, or descriptions.
 * - Only include hashed identifiers (providerTransactionHash, accountHash).
 * - Include safe summary fields: syncRunId, bookingDate, valueDate, transactionStatus.
 */
object BankSourceEventMetadataBuilder {

    /**
     * Builds metadata for a bank sync source link creation event.
     */
    fun bankSyncCreatedMetadata(
        syncRunId: Long,
        providerTransactionHash: String? = null,
        accountHash: String? = null,
        bookingDate: String? = null,
        valueDate: String? = null,
        transactionStatus: String? = null
    ): String {
        return JSONObject().apply {
            put("syncRunId", syncRunId)
            providerTransactionHash?.let { put("providerTransactionHash", it) }
            accountHash?.let { put("accountHash", it) }
            bookingDate?.let { put("bookingDate", it) }
            valueDate?.let { put("valueDate", it) }
            transactionStatus?.let { put("transactionStatus", it) }
        }.toString()
    }

    /**
     * Builds metadata for a bank sync duplicate detection event.
     */
    fun bankSyncDuplicateMetadata(
        syncRunId: Long,
        matchedExpenseId: Long,
        providerTransactionHash: String? = null,
        accountHash: String? = null
    ): String {
        return JSONObject().apply {
            put("reason", "Bank sync duplicate detected")
            put("syncRunId", syncRunId)
            put("matchedExpenseId", matchedExpenseId)
            providerTransactionHash?.let { put("providerTransactionHash", it) }
            accountHash?.let { put("accountHash", it) }
        }.toString()
    }

    /**
     * Builds metadata for a bank sync review creation event.
     */
    fun bankSyncReviewMetadata(
        syncRunId: Long,
        providerTransactionHash: String? = null,
        accountHash: String? = null,
        bookingDate: String? = null,
        valueDate: String? = null
    ): String {
        return JSONObject().apply {
            put("syncRunId", syncRunId)
            put("reviewStage", "bank_sync_review")
            providerTransactionHash?.let { put("providerTransactionHash", it) }
            accountHash?.let { put("accountHash", it) }
            bookingDate?.let { put("bookingDate", it) }
            valueDate?.let { put("valueDate", it) }
        }.toString()
    }
}
