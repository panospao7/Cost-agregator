package com.yourname.expensetracker.domain.receipt

import com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/** Page coverage only: no receipt text, paths or financial data. Null counts are normal for images. */
data class ReceiptOcrCoverage(
    val pagesProcessed: Int? = null,
    val totalPages: Int? = null,
    val failedPages: Int? = null
) {
    val isPartial: Boolean
        get() = (failedPages ?: 0) > 0 ||
            (pagesProcessed != null && totalPages != null && pagesProcessed < totalPages)

    /** A versioned envelope distinguishes known coverage from missing or retained legacy data. */
    fun toSavedMetadata(): SafeEventMetadata = SafeEventMetadata.builder()
        .put("pageCoverageVersion", 1)
        .apply {
            if (isPartial) {
                put("partial", true)
                put("reasonCode", "PDF_PARTIAL")
            }
            pagesProcessed?.let { put("pagesProcessed", it) }
            totalPages?.let { put("totalPages", it) }
            failedPages?.let { put("failedPages", it) }
        }
        .build()

    companion object {
        const val MAX_SAVED_METADATA_CHARS = 2048

        /** Missing, malformed or unsupported evidence is unknown, never proof of complete OCR. */
        fun fromSavedMetadata(metadata: String?): ReceiptOcrCoverage? {
            if (metadata.isNullOrBlank() || metadata.length > MAX_SAVED_METADATA_CHARS) return null
            return try {
                val tokens = JSONTokener(metadata)
                val fields = tokens.nextValue() as? JSONObject ?: return null
                if (tokens.nextClean() != 0.toChar()) return null
                val versioned = fields.has("pageCoverageVersion")
                if (versioned && fields.opt("pageCoverageVersion") != 1) return null
                val partial = fields.opt("partial") == true
                if (fields.has("partial") && !partial) return null
                if (partial) {
                    if (fields.opt("reasonCode") != "PDF_PARTIAL") return null
                } else if (fields.has("reasonCode")) {
                    return null
                }
                // Older S1 saves carry positive partial evidence but no version marker.
                if (!versioned && !partial) return null
                val coverage = ReceiptOcrCoverage(
                    pagesProcessed = fields.pageCount("pagesProcessed"),
                    totalPages = fields.pageCount("totalPages"),
                    failedPages = fields.pageCount("failedPages")
                )
                val total = coverage.totalPages
                if (total != null && (total == 0 ||
                        (coverage.pagesProcessed ?: 0) > total ||
                        (coverage.failedPages ?: 0) > total)) return null
                // Legacy producers counted attempted pages, so processed + failed may exceed total.
                if (coverage.isPartial != partial) return null
                coverage
            } catch (_: JSONException) {
                null
            }
        }

        private fun JSONObject.pageCount(key: String): Int? = when (val value = opt(key)) {
            null -> null
            is Int -> value.takeIf { it >= 0 } ?: throw JSONException("INVALID_PAGE_COUNT")
            is Long -> value.takeIf { it in 0L..Int.MAX_VALUE.toLong() }?.toInt()
                ?: throw JSONException("INVALID_PAGE_COUNT")
            else -> throw JSONException("INVALID_PAGE_COUNT")
        }
    }
}
