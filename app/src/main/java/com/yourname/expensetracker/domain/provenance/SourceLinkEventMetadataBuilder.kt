package com.yourname.expensetracker.domain.provenance

import com.yourname.expensetracker.domain.transaction.DeduplicationMode
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds safe JSON metadata summaries for lifecycle events that involve source links.
 *
 * Privacy rules:
 * - Never include raw externalFingerprint, email message IDs, bank transaction IDs, etc.
 * - Only include safe summary fields: sourceType, sourceEntityType, role, status, isPrimary,
 *   sourceEntityLocalId (for local Room IDs), importBatchId, importRowNumber.
 * - For external fingerprints, only indicate presence via hasExternalFingerprint flag.
 */
object SourceLinkEventMetadataBuilder {

    /**
     * Builds metadata for CREATE_ATTEMPTED events with source-link context.
     * Returns null if no source links exist.
     */
    fun createAttemptMetadata(payloads: List<SourceLinkPayload>): String? {
        if (payloads.isEmpty()) return null
        return buildSourceLinkSummary(payloads)
    }

    /**
     * Builds metadata for CREATE_VALIDATION_FAILED events.
     */
    fun validationFailedMetadata(
        errors: List<String>,
        payloads: List<SourceLinkPayload>
    ): String {
        return JSONObject().apply {
            put("errors", errors.joinToString("; "))
            if (payloads.isNotEmpty()) {
                put("sourceLinkCount", payloads.size)
                put("sourceLinks", buildSafeLinksArray(payloads))
            }
        }.toString()
    }

    /**
     * Builds metadata for CREATED events with source-link summary.
     * Returns null if no source links exist.
     */
    fun createdMetadata(payloads: List<SourceLinkPayload>): String? {
        if (payloads.isEmpty()) return null
        return buildSourceLinkSummary(payloads)
    }

    /**
     * Builds metadata for SOURCE_LINKED events.
     */
    fun sourceLinkedMetadata(
        payloads: List<SourceLinkPayload>,
        results: List<SourceLinkWriteResult>
    ): String {
        return JSONObject().apply {
            put("linkCount", payloads.size)
            put("links", buildSafeLinksArray(payloads))
            put("results", buildResultsArray(results))
        }.toString()
    }

    /**
     * Builds metadata for CREATE_DUPLICATE_SKIPPED events.
     */
    fun duplicateMetadata(
        policy: DuplicateSourceLinkPolicy,
        attemptedExpense: com.yourname.expensetracker.data.database.entity.Expense,
        sourceLinkPayloads: List<SourceLinkPayload>,
        duplicateLinkResults: List<SourceLinkWriteResult> = emptyList()
    ): String {
        return JSONObject().apply {
            put("reason", "Duplicate expense detected")
            put("duplicateSourceLinkPolicy", policy.name)
            put("attemptedAmount", attemptedExpense.amount)
            put("attemptedMerchant", attemptedExpense.merchant)
            put("attemptedDate", attemptedExpense.date)
            put("attemptedCurrency", attemptedExpense.currency)
            put("attemptedMerchantKey", attemptedExpense.merchantKey)
            if (sourceLinkPayloads.isNotEmpty()) {
                put("sourceLinkCount", sourceLinkPayloads.size)
                put("sourceLinks", buildSafeLinksArray(sourceLinkPayloads))
            }
            if (duplicateLinkResults.isNotEmpty()) {
                put("duplicateLinkResults", buildResultsArray(duplicateLinkResults))
            }
        }.toString()
    }

    /**
     * Builds metadata for CREATE_INSERT_CONFLICT events.
     */
    fun insertConflictMetadata(
        dedupMode: DeduplicationMode,
        dedupeKey: String?,
        payloads: List<SourceLinkPayload>
    ): String {
        return JSONObject().apply {
            put("dedupMode", dedupMode.name)
            put("dedupeKey", dedupeKey ?: "unknown")
            if (payloads.isNotEmpty()) {
                put("sourceLinkCount", payloads.size)
                put("sourceLinks", buildSafeLinksArray(payloads))
            }
        }.toString()
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    private fun buildSourceLinkSummary(payloads: List<SourceLinkPayload>): String {
        return JSONObject().apply {
            put("sourceLinkCount", payloads.size)
            put("sourceLinks", buildSafeLinksArray(payloads))
        }.toString()
    }

    private fun buildSafeLinksArray(payloads: List<SourceLinkPayload>): JSONArray {
        return JSONArray().apply {
            payloads.forEach { p ->
                put(JSONObject().apply {
                    put("sourceType", p.sourceType)
                    put("sourceEntityType", p.sourceEntityType.name)
                    put("role", p.role.name)
                    put("status", p.status.name)
                    put("isPrimary", p.isPrimary)
                    p.sourceEntityLocalId?.let { put("sourceEntityLocalId", it) }
                    p.importBatchId?.let { put("importBatchId", it) }
                    p.importRowNumber?.let { put("importRowNumber", it) }
                    if (p.externalFingerprint != null || p.externalId != null) {
                        put("hasExternalFingerprint", true)
                    }
                })
            }
        }
    }

    private fun buildResultsArray(results: List<SourceLinkWriteResult>): JSONArray {
        return JSONArray().apply {
            results.forEach { r ->
                put(when (r) {
                    is SourceLinkWriteResult.Inserted -> "Inserted(id=${r.id})"
                    SourceLinkWriteResult.AlreadyExists -> "AlreadyExists"
                    is SourceLinkWriteResult.Rejected -> "Rejected(reason=${r.reason})"
                })
            }
        }
    }
}
