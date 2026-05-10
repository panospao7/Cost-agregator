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

/**
 * P8-P1-11: Explicit backup export policy to prevent raw/plaintext exports
 * from being reachable in release builds without explicit consent.
 */
enum class BackupExportPolicy(val label: String) {
    ENCRYPTED_ONLY("Only encrypted backups allowed"),
    ENCRYPTED_REDACTED("Encrypted redacted backups"),
    DISABLED("Backup export disabled"),
    AUTHENTICATED_ONLY("Backup only after explicit password confirmation")
}

/**
 * P8-P1-11 / P12-P1-06: Privacy mode for expense exports (CSV/JSON/accounting).
 */
enum class ExpenseExportPrivacyMode(val label: String, val redactMerchant: Boolean, val redactNotes: Boolean) {
    FULL_PLAINTEXT("Full plaintext export", false, false),
    REDACT_NOTES_AND_MERCHANTS("Redacted notes and merchants", true, true),
    METADATA_ONLY("Metadata only (dates, amounts, categories)", true, true),
    ENCRYPTED("Encrypted export", false, false)
}
