package com.yourname.expensetracker.domain.backup

/**
 * Privacy mode for backup creation. (ARCH-03/P8-P1-6)
 *
 * Stage 1: Enum definition only — backward-compatible.
 * Stage 2: Wire into BackupOptions and manifest.
 * Stage 3: UI migration.
 */
enum class BackupPrivacyMode(val label: String, val redactsRawText: Boolean, val includesReceiptImages: Boolean) {
    FULL_ENCRYPTED("Full encrypted backup", false, true),
    REDACT_RAW_TEXT("Redacted DB (images included)", true, true),
    REDACT_RAW_TEXT_EXCLUDE_IMAGES("Redacted DB (no images)", true, false),
    ANONYMIZED_EXPORT("Anonymized export", true, false)
}
