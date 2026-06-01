package com.yourname.expensetracker.domain.privacy

object RawContentSanitizer {
    /**
     * P8-PR3 (NEW-P8-007): Sanitises OCR text for the given storage mode.
     *
     * This overload returns a non-null [String] — callers that need to distinguish
     * between "no data was ever captured" (null) and "data was cleared" (empty
     * string) should use [sanitizeRawOcrNullable] instead.
     *
     * Under STORE_RAW, null input is converted to empty string ("") so callers
     * can check `rawOcrText.isEmpty()` vs `rawOcrTextPurgedAt` to determine
     * whether data was ever present.
     */
    fun sanitizeRawOcr(text: String?, mode: RawStorageMode): String = when (mode) {
        RawStorageMode.STORE_RAW -> text ?: ""
        RawStorageMode.STORE_REDACTED -> "[REDACTED]"
        RawStorageMode.STORE_METADATA_ONLY -> ""
        RawStorageMode.DO_NOT_STORE -> ""
    }

    /**
     * P8-PR3 (NEW-P8-007): Null-aware OCR sanitisation that preserves the
     * distinction between null (no data) and empty string (data cleared).
     *
     * - null input is returned as null so callers can differentiate "never captured"
     *   from "captured and cleared".
     * - Empty string input ("") stays as "" — the field was set but cleared.
     * - Under STORE_RAW, non-null text is returned as-is.
     * - Under STORE_REDACTED, returns "[REDACTED]" only if text was non-null.
     * - Under STORE_METADATA_ONLY / DO_NOT_STORE, returns null.
     */
    fun sanitizeRawOcrNullable(text: String?, mode: RawStorageMode): String? = when (mode) {
        RawStorageMode.STORE_RAW -> text  // preserve null vs "" distinction
        RawStorageMode.STORE_REDACTED -> if (text != null) "[REDACTED]" else null
        RawStorageMode.STORE_METADATA_ONLY -> null
        RawStorageMode.DO_NOT_STORE -> null
    }

    fun sanitizeEmailSubject(subject: String?, mode: RawStorageMode): String? = when (mode) {
        RawStorageMode.STORE_RAW -> subject
        RawStorageMode.STORE_REDACTED -> "[REDACTED]"
        else -> null
    }

    fun sanitizeEmailSender(sender: String?, mode: RawStorageMode): String? = when (mode) {
        RawStorageMode.STORE_RAW -> sender
        RawStorageMode.STORE_REDACTED -> "[REDACTED]"
        else -> null
    }

    /**
     * PR2: messageId hash must NOT use String.hashCode().
     * Callers should use [SensitiveHashingService.hmacSha256Prefix] instead for production paths.
     * This overload accepts an already-computed hash string for backward-compatible callers.
     */
    fun sanitizeEmailMessageId(messageId: String?, mode: RawStorageMode): String? = when (mode) {
        RawStorageMode.STORE_RAW -> messageId
        RawStorageMode.STORE_REDACTED -> "[REDACTED]"
        // PR2: METADATA_ONLY must NOT use hashCode() — callers must supply a pre-hashed value.
        // This function returns null here; callers must compute the hash via SensitiveHashingService.
        RawStorageMode.STORE_METADATA_ONLY -> null
        RawStorageMode.DO_NOT_STORE -> null
    }

    /**
     * PR2-safe variant: accepts an already-computed HMAC hash for deduplication under
     * METADATA_ONLY / DO_NOT_STORE modes.
     */
    fun sanitizeEmailMessageIdWithHash(
        messageId: String?,
        messageIdHash: String?,
        mode: RawStorageMode
    ): String? = when (mode) {
        RawStorageMode.STORE_RAW -> messageId
        RawStorageMode.STORE_REDACTED -> "[REDACTED]"
        RawStorageMode.STORE_METADATA_ONLY -> messageIdHash   // hash for dedup, no plaintext
        RawStorageMode.DO_NOT_STORE -> null
    }

    fun sanitizedOcrReviewSnippet(raw: String, mode: RawStorageMode): String = when (mode) {
        RawStorageMode.STORE_RAW -> raw.take(200)
        RawStorageMode.STORE_REDACTED -> "[REDACTED]"
        RawStorageMode.STORE_METADATA_ONLY -> "Receipt OCR captured; raw text storage disabled."
        RawStorageMode.DO_NOT_STORE -> "Receipt OCR captured; raw text not stored."
    }

    fun sanitizeNotificationText(text: String?, mode: RawStorageMode): String? = when (mode) {
        RawStorageMode.STORE_RAW -> text
        RawStorageMode.STORE_REDACTED -> if (text != null) "[REDACTED]" else null
        RawStorageMode.STORE_METADATA_ONLY -> null
        RawStorageMode.DO_NOT_STORE -> null
    }

    fun sanitizeNotificationExtras(extrasJson: String?, mode: RawStorageMode): String? = when (mode) {
        RawStorageMode.STORE_RAW -> extrasJson
        else -> null  // extras are never stored unless STORE_RAW
    }

    fun sanitizeBankDescription(text: String?, mode: RawStorageMode): String? = when (mode) {
        RawStorageMode.STORE_RAW -> text
        RawStorageMode.STORE_REDACTED -> if (text != null) "[REDACTED]" else null
        RawStorageMode.STORE_METADATA_ONLY -> null
        RawStorageMode.DO_NOT_STORE -> null
    }
}
