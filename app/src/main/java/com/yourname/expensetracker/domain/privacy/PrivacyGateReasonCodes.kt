package com.yourname.expensetracker.domain.privacy

/**
 * RP-15 (15-B): Bounded, controlled reason codes for privacy-gate failures.
 *
 * Contract (RP-15 plan §15-B):
 * - Gate failures NEVER derive their reason from exception text. Diagnostics may
 *   include the exception CLASS NAME only; persisted/logged/rendered reasons are
 *   one of these compile-time constants.
 * - Every [PrivacyBlocked.Custom] created by the central blocked mapping carries
 *   one of these codes — never untrusted text.
 */
object PrivacyGateReasonCodes {
    /** Generic privacy-gate failure (e.g. a gate threw; the exception itself is not diagnosed). */
    const val PRIVACY_GATE_FAILURE = "PRIVACY_GATE_FAILURE"

    /** Catch-all blocked marker used when no more specific code applies. */
    const val PRIVACY_BLOCKED = "PRIVACY_BLOCKED"

    /** A gate-handled capability had no registered gate handler — fail closed. */
    const val NO_GATE_HANDLER = "NO_GATE_HANDLER"

    /** Cloud AI globally disabled (privacy settings or AI settings). */
    const val CLOUD_AI_DISABLED = "CLOUD_AI_DISABLED"

    /** Receipt image cloud upload disabled by dedicated settings. */
    const val RECEIPT_IMAGE_UPLOAD_DISABLED = "RECEIPT_IMAGE_UPLOAD_DISABLED"

    /** Image upload suppressed because redaction-before-cloud is required. */
    const val IMAGE_UPLOAD_REDACTION_REQUIRED = "IMAGE_UPLOAD_REDACTION_REQUIRED"

    /** Bank statement AI disabled by its dedicated setting. */
    const val BANK_STATEMENT_AI_DISABLED = "BANK_STATEMENT_AI_DISABLED"

    /**
     * [PrivacyCapability] is not a registered cloud capability (non-cloud,
     * unknown, or future). Cloud policy fails closed for it by design.
     */
    const val CAPABILITY_NOT_CLOUD = "CAPABILITY_NOT_CLOUD"

    // RP-15 (15-D): per-capability codes used by the UI adapter
    // (PrivacyBlockedUiState.reasonCode). Controlled constants only.

    const val EXTERNAL_GEOCODING_DISABLED = "EXTERNAL_GEOCODING_DISABLED"
    const val NOTIFICATION_CAPTURE_DISABLED = "NOTIFICATION_CAPTURE_DISABLED"
    const val RAW_EXPORT_DISABLED = "RAW_EXPORT_DISABLED"
    const val DEVICE_GPS_DISABLED = "DEVICE_GPS_DISABLED"
    const val BACKGROUND_LOCATION_DISABLED = "BACKGROUND_LOCATION_DISABLED"
    const val ENCRYPTED_BACKUP_DISABLED = "ENCRYPTED_BACKUP_DISABLED"
    const val OVERPASS_DISABLED = "OVERPASS_DISABLED"
    const val DEBUG_PERSISTENCE_DISABLED = "DEBUG_PERSISTENCE_DISABLED"

    /** All codes — used by tests to assert boundedness. */
    val ALL: Set<String> = setOf(
        PRIVACY_GATE_FAILURE,
        PRIVACY_BLOCKED,
        NO_GATE_HANDLER,
        CLOUD_AI_DISABLED,
        RECEIPT_IMAGE_UPLOAD_DISABLED,
        IMAGE_UPLOAD_REDACTION_REQUIRED,
        BANK_STATEMENT_AI_DISABLED,
        CAPABILITY_NOT_CLOUD,
        EXTERNAL_GEOCODING_DISABLED,
        NOTIFICATION_CAPTURE_DISABLED,
        RAW_EXPORT_DISABLED,
        DEVICE_GPS_DISABLED,
        BACKGROUND_LOCATION_DISABLED,
        ENCRYPTED_BACKUP_DISABLED,
        OVERPASS_DISABLED,
        DEBUG_PERSISTENCE_DISABLED
    )
}
