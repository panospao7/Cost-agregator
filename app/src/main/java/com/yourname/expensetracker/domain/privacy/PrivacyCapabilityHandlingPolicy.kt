package com.yourname.expensetracker.domain.privacy

/**
 * PRIV-441-03: Production policy object that defines which capabilities must be
 * handled by at least one gate in [CompositePrivacyGate].
 *
 * If a capability is in [gateHandledCapabilities] but no gate returns Allowed or
 * Denied (all return NotApplicable), the composite gate fails closed.
 *
 * Every [PrivacyCapability] must appear in exactly one of the two sets.
 */
object PrivacyCapabilityHandlingPolicy {

    /**
     * Capabilities that require an explicit gate decision.
     * If no gate handles one of these, [CompositePrivacyGate] returns FailClosed.
     */
    val gateHandledCapabilities: Set<PrivacyCapability> = setOf(
        PrivacyCapability.NOTIFICATION_CAPTURE,
        PrivacyCapability.NOTIFICATION_PACKAGE_ALLOWLIST,
        PrivacyCapability.CLOUD_AI_RECEIPT_ASSIST,
        PrivacyCapability.CLOUD_AI_RECEIPT_OCR,
        PrivacyCapability.CLOUD_AI_ITEM_CATEGORIZATION,
        PrivacyCapability.CLOUD_AI_WARRANTY_EXTRACTION,
        PrivacyCapability.CLOUD_AI_BANK_STATEMENT,
        PrivacyCapability.AI_BANK_STATEMENT_PARSING,
        PrivacyCapability.CLOUD_AI_DAILY_BRIEFING,
        PrivacyCapability.CLOUD_AI_GENERAL,
        PrivacyCapability.RECEIPT_IMAGE_CLOUD_UPLOAD,
        PrivacyCapability.EXTERNAL_GEOCODING,
        PrivacyCapability.BACKGROUND_LOCATION_BACKFILL,
        PrivacyCapability.DEVICE_GPS_LOCATION,
        PrivacyCapability.RAWBACKUP_EXPORT,
        PrivacyCapability.ENCRYPTED_BACKUP,
        PrivacyCapability.OVERPASS_API,
        PrivacyCapability.EXPENSE_EXPORT,
        PrivacyCapability.EXPENSE_EXPORT_RAW,
        PrivacyCapability.EXPENSE_EXPORT_REDACTED,
        PrivacyCapability.EXPENSE_EXPORT_ENCRYPTED,
        PrivacyCapability.DEBUG_RAW_EXPORT,
        PrivacyCapability.RAW_DATABASE_EXPORT
    )

    /**
     * Capabilities that are local-only (no external call, no gate needed).
     * These default to Allowed when no gate handles them.
     */
    val localOnlyCapabilities: Set<PrivacyCapability> = setOf(
        PrivacyCapability.RAW_NOTIFICATION_RETENTION,
        PrivacyCapability.RAW_OCR_RETENTION,
        PrivacyCapability.DEBUG_DATA_PERSISTENCE,
        PrivacyCapability.TIMBER_PII_LOGGING
    )
}
