package com.yourname.expensetracker.domain.provenance

/**
 * CURR-SL-01: Target entity types for source links.
 */
enum class TargetEntityType {
    EXPENSE,
    PENDING_REVIEW,
    SCANNED_RECEIPT,
    RAW_NOTIFICATION,
    OPERATION_RUN
}

/**
 * CURR-SL-01: Source entity types — what created or justified the target.
 */
enum class SourceEntityType {
    RAW_NOTIFICATION,
    PENDING_REVIEW,
    SCANNED_RECEIPT,
    EMAIL_RECEIPT_SOURCE,
    RECEIPT_EXPENSE_LINK,

    BANK_CONNECTION,
    BANK_ACCOUNT,
    BANK_TRANSACTION,
    BANK_SYNC_RUN,
    BANK_STATEMENT_IMPORT_RUN,
    BANK_STATEMENT_IMPORT_ITEM,

    CSV_IMPORT_RUN,
    CSV_IMPORT_ROW,
    JSON_IMPORT_RUN,
    JSON_IMPORT_ROW,
    FILE_IMPORT,

    GROUP,
    RECURRING_RULE,
    RECURRING_OCCURRENCE,
    PLANNED_EXPENSE,

    MANUAL_ENTRY,
    DEBUG_TOOL,
    MIGRATION,
    LEGACY_SOURCE_ONLY,
    UNKNOWN
}

/**
 * CURR-SL-01: Role of the source link — why does this relation exist?
 */
enum class SourceLinkRole {
    CREATED_FROM,
    APPROVED_FROM,
    REVIEWED_FROM,
    LINKED_PROOF,
    DUPLICATE_MATCHED,
    IMPORTED_FROM,
    GENERATED_FROM,
    ENRICHED_BY,
    LEGACY_BACKFILL
}

/**
 * CURR-SL-01: Status of the source link.
 */
enum class SourceLinkStatus {
    ACTIVE,
    SUPERSEDED,
    DUPLICATE,
    FAILED,
    REDACTED,
    LEGACY_PARTIAL
}
