package com.yourname.expensetracker.domain.privacy

/**
 * Sanitized payload for persisting OCR/receipt data according to the current
 * raw OCR [RawStorageMode].
 */
data class ReceiptPersistencePayload(
    /** Raw OCR text — null unless STORE_RAW. */
    val rawOcrText: String?,
    /** Snippet for PendingReview — sanitized. */
    val reviewSnippet: String?,
    /** Serialized parsed items JSON — null unless STORE_RAW or STORE_REDACTED. */
    val parsedItemsJson: String?,
    val mode: RawStorageMode
) {
    companion object {
        fun build(
            mode: RawStorageMode,
            rawOcrText: String,
            parsedItemsJson: String?
        ): ReceiptPersistencePayload = ReceiptPersistencePayload(
            rawOcrText = when (mode) {
                RawStorageMode.STORE_RAW -> rawOcrText
                else -> null
            },
            reviewSnippet = RawContentSanitizer.sanitizedOcrReviewSnippet(rawOcrText, mode),
            parsedItemsJson = when (mode) {
                RawStorageMode.STORE_RAW -> parsedItemsJson
                RawStorageMode.STORE_REDACTED -> parsedItemsJson  // items may be kept in redacted mode
                else -> null
            },
            mode = mode
        )
    }
}
