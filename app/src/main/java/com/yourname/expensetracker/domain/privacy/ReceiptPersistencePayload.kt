package com.yourname.expensetracker.domain.privacy

import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptStructuredDataPolicy

/**
 * Sanitized payload for persisting OCR/receipt data according to the current
 * raw OCR [RawStorageMode].
 */
data class ReceiptPersistencePayload(
    /** Raw OCR text — null unless STORE_RAW. */
    val rawOcrText: String?,
    /** Snippet for PendingReview — sanitized. */
    val reviewSnippet: String?,
    /**
     * Serialized parsed items JSON. STORE_REDACTED contains only the
     * ReceiptStructuredDataPolicy numeric projection; raw descriptions and
     * other line-item text are never allowed in that mode. If no currency is
     * available for the projection, the field is omitted fail-closed.
     */
    val parsedItemsJson: String?,
    val mode: RawStorageMode
) {
    companion object {
        fun build(
            mode: RawStorageMode,
            rawOcrText: String,
            parsedItemsJson: String?,
            currency: String? = null
        ): ReceiptPersistencePayload = ReceiptPersistencePayload(
            rawOcrText = when (mode) {
                RawStorageMode.STORE_RAW -> rawOcrText
                else -> null
            },
            reviewSnippet = RawContentSanitizer.sanitizedOcrReviewSnippet(rawOcrText, mode),
            parsedItemsJson = when (mode) {
                RawStorageMode.STORE_RAW -> parsedItemsJson
                RawStorageMode.STORE_REDACTED -> currency?.let {
                    ReceiptStructuredDataPolicy.persistedItemsFromParserJson(
                        fullItemsJson = parsedItemsJson,
                        currency = it,
                        mode = RawStorageMode.STORE_REDACTED
                    )
                }
                else -> null
            },
            mode = mode
        )
    }
}
