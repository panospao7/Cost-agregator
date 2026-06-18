package com.yourname.expensetracker.domain.privacy

/** Identifies which data source a raw persistence policy applies to. */
enum class RawSourceType {
    NOTIFICATION,
    RECEIPT_OCR,
    EMAIL_RECEIPT,
    BANK_STATEMENT,
    BANK_API,
    AI_ARTIFACT,
    EXPORT_DEBUG
}
