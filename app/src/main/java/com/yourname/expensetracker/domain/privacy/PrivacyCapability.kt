package com.yourname.expensetracker.domain.privacy

enum class PrivacyCapability {
    NOTIFICATION_CAPTURE,
    NOTIFICATION_PACKAGE_ALLOWLIST,
    CLOUD_AI_RECEIPT_ASSIST,
    CLOUD_AI_ITEM_CATEGORIZATION,
    CLOUD_AI_WARRANTY_EXTRACTION,
    CLOUD_AI_BANK_STATEMENT,
    AI_BANK_STATEMENT_PARSING,
    CLOUD_AI_DAILY_BRIEFING,
    CLOUD_AI_GENERAL,
    RECEIPT_IMAGE_CLOUD_UPLOAD,
    EXTERNAL_GEOCODING,
    BACKGROUND_LOCATION_BACKFILL,
    DEVICE_GPS_LOCATION,
    RAWBACKUP_EXPORT,
    ENCRYPTED_BACKUP,
    RAW_NOTIFICATION_RETENTION,
    RAW_OCR_RETENTION,
    DEBUG_DATA_PERSISTENCE,
    OVERPASS_API,
    TIMBER_PII_LOGGING,

    /** PR8: Standard expense export (CSV/JSON). */
    EXPENSE_EXPORT,
    /** PR8: Raw (un-redacted) expense export — requires explicit opt-in. */
    EXPENSE_EXPORT_RAW,
    /** PR8: Redacted expense export — safe for sharing. */
    EXPENSE_EXPORT_REDACTED,
    /** PR8: Encrypted expense export. */
    EXPENSE_EXPORT_ENCRYPTED,
    /** PR8: Debug raw export — requires debug build + privacy consent. */
    DEBUG_RAW_EXPORT,
    /** PR8: Raw database export — release-disabled. */
    RAW_DATABASE_EXPORT
}
