package com.yourname.expensetracker.domain.privacy

object RawContentSanitizer {
    fun sanitizeRawOcr(text: String?, mode: RawStorageMode): String = when (mode) {
        RawStorageMode.STORE_RAW -> text ?: ""
        RawStorageMode.STORE_REDACTED -> "[REDACTED]"
        RawStorageMode.STORE_METADATA_ONLY -> ""
        RawStorageMode.DO_NOT_STORE -> ""
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

    fun sanitizedOcrReviewSnippet(raw: String, mode: RawStorageMode): String = when (mode) {
        RawStorageMode.STORE_RAW -> raw.take(200)
        RawStorageMode.STORE_REDACTED -> "[REDACTED]"
        RawStorageMode.STORE_METADATA_ONLY -> "Receipt OCR captured; raw text storage disabled."
        RawStorageMode.DO_NOT_STORE -> "Receipt OCR captured; raw text not stored."
    }
}
