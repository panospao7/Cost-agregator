package com.yourname.expensetracker.domain.privacy

/**
 * Standardized privacy-denied states for UI messaging.
 *
 * Each subclass maps to a specific [PrivacyCapability] that was denied, with a
 * human-readable [reason] string. UI components should use these classes for
 * consistent privacy-denied messaging across all screens rather than
 * constructing ad-hoc error strings.
 *
 * ## Usage in providers
 *
 * P8-PR2 (P8-P1-12): Cloud providers (CloudReceiptAssistService, etc.) MUST use
 * [AiServiceError.PrivacyDenied] wrapping the appropriate [PrivacyBlocked] subclass
 * when a privacy-gate check denies execution. Ad-hoc denial strings are not allowed.
 *
 * ## Adding new denied states
 *
 * When a new [PrivacyCapability] is added, a corresponding [PrivacyBlocked] subclass
 * should be created and registered in the [toPrivacyBlocked] mapper. If no specific
 * subclass exists, [Custom] is returned.
 *
 * ## Contract
 *
 * - [toPrivacyBlocked] is the single entry point for mapping [PrivacyDecision] → [PrivacyBlocked].
 * - No provider constructs [PrivacyBlocked] subclasses directly; they always go through
 *   [toPrivacyBlocked] (or [toPrivacyBlockedOrFallback] when they need a value for
 *   every outcome) and receive a typed [AiServiceError.PrivacyDenied] from the gate layer.
 * - RP-15 (15-B): the [reason] string is ALWAYS a static default of the typed subclass
 *   or a bounded controlled code from [PrivacyGateReasonCodes]. Gate-decision reasons
 *   are deliberately NOT passed through here — [PrivacyBlocked.Custom] instances
 *   created by this mapping never carry untrusted text.
 */
sealed interface PrivacyBlocked {
    val capability: PrivacyCapability
    val reason: String

    data class CloudAiDisabled(override val reason: String = "Cloud AI is disabled by user setting") : PrivacyBlocked {
        override val capability = PrivacyCapability.CLOUD_AI_GENERAL
    }

    data class ReceiptImageUploadDisabled(override val reason: String = "Receipt image cloud upload is disabled") : PrivacyBlocked {
        override val capability = PrivacyCapability.RECEIPT_IMAGE_CLOUD_UPLOAD
    }

    data class ExternalGeocodingDisabled(override val reason: String = "External geocoding is disabled") : PrivacyBlocked {
        override val capability = PrivacyCapability.EXTERNAL_GEOCODING
    }

    data class NotificationCaptureDisabled(override val reason: String = "Notification capture is disabled") : PrivacyBlocked {
        override val capability = PrivacyCapability.NOTIFICATION_CAPTURE
    }

    data class RawExportDisabled(override val reason: String = "Raw/plaintext export is disabled") : PrivacyBlocked {
        override val capability = PrivacyCapability.RAWBACKUP_EXPORT
    }

    // S3-001/002: Additional typed states for all user-facing capabilities
    data class DeviceGpsDisabled(override val reason: String = "Device GPS location is disabled by privacy settings") : PrivacyBlocked {
        override val capability = PrivacyCapability.DEVICE_GPS_LOCATION
    }

    data class BackgroundLocationDisabled(override val reason: String = "Background location backfill is disabled") : PrivacyBlocked {
        override val capability = PrivacyCapability.BACKGROUND_LOCATION_BACKFILL
    }

    data class BankStatementAiDisabled(override val reason: String = "Bank statement AI parsing is disabled") : PrivacyBlocked {
        override val capability = PrivacyCapability.CLOUD_AI_BANK_STATEMENT
    }

    data class EncryptedBackupDisabled(override val reason: String = "Encrypted backup is disabled") : PrivacyBlocked {
        override val capability = PrivacyCapability.ENCRYPTED_BACKUP
    }

    data class OverpassDisabled(override val reason: String = "Overpass API (map data) is disabled") : PrivacyBlocked {
        override val capability = PrivacyCapability.OVERPASS_API
    }

    data class DebugDataPersistenceDisabled(override val reason: String = "Debug data persistence is disabled") : PrivacyBlocked {
        override val capability = PrivacyCapability.DEBUG_DATA_PERSISTENCE
    }

    data class Custom(
        override val capability: PrivacyCapability,
        override val reason: String
    ) : PrivacyBlocked
}

/**
 * Maps a [PrivacyDecision] + [capability] to a typed [PrivacyBlocked], or null if allowed.
 *
 * P8-PR2 (P8-P1-12): This is the SINGLE entry point for mapping decisions to user-facing
 * blocked states. Cloud providers MUST use [AiServiceError.PrivacyDenied] wrapping the
 * result of this function. Direct construction of [PrivacyBlocked] subclasses outside
 * this mapper is not allowed.
 *
 * RP-15 (15-B): mapping is CENTRALIZED here. Reasons are static defaults or controlled
 * codes from [PrivacyGateReasonCodes] — never gate-decision or exception text.
 */
fun PrivacyDecision.toPrivacyBlocked(capability: PrivacyCapability): PrivacyBlocked? = when (this) {
    is PrivacyDecision.Allowed, is PrivacyDecision.NotApplicable -> null
    is PrivacyDecision.Denied -> privacyBlockedFromCapability(capability)
    is PrivacyDecision.FailClosed ->
        PrivacyBlocked.Custom(capability, PrivacyGateReasonCodes.PRIVACY_GATE_FAILURE)
}

/**
 * RP-15 (15-B): centralized fallback for providers that need a blocked state for
 * every outcome (e.g. when a gate decision was unexpectedly permissive). The only
 * sanctioned place where [PrivacyBlocked.Custom] may be created for a non-blocked
 * decision, and its reason is a bounded controlled code.
 */
fun PrivacyDecision.toPrivacyBlockedOrFallback(capability: PrivacyCapability): PrivacyBlocked =
    toPrivacyBlocked(capability) ?: PrivacyBlocked.Custom(capability, PrivacyGateReasonCodes.PRIVACY_BLOCKED)

private fun privacyBlockedFromCapability(capability: PrivacyCapability): PrivacyBlocked = when (capability) {
    PrivacyCapability.CLOUD_AI_GENERAL,
    PrivacyCapability.CLOUD_AI_RECEIPT_ASSIST,
    PrivacyCapability.CLOUD_AI_ITEM_CATEGORIZATION,
    PrivacyCapability.CLOUD_AI_WARRANTY_EXTRACTION,
    PrivacyCapability.CLOUD_AI_DAILY_BRIEFING -> PrivacyBlocked.CloudAiDisabled()
    PrivacyCapability.CLOUD_AI_BANK_STATEMENT,
    PrivacyCapability.AI_BANK_STATEMENT_PARSING -> PrivacyBlocked.BankStatementAiDisabled()
    PrivacyCapability.RECEIPT_IMAGE_CLOUD_UPLOAD -> PrivacyBlocked.ReceiptImageUploadDisabled()
    PrivacyCapability.EXTERNAL_GEOCODING -> PrivacyBlocked.ExternalGeocodingDisabled()
    PrivacyCapability.OVERPASS_API -> PrivacyBlocked.OverpassDisabled()
    PrivacyCapability.NOTIFICATION_CAPTURE,
    PrivacyCapability.NOTIFICATION_PACKAGE_ALLOWLIST -> PrivacyBlocked.NotificationCaptureDisabled()
    PrivacyCapability.BACKGROUND_LOCATION_BACKFILL -> PrivacyBlocked.BackgroundLocationDisabled()
    PrivacyCapability.DEVICE_GPS_LOCATION -> PrivacyBlocked.DeviceGpsDisabled()
    PrivacyCapability.RAWBACKUP_EXPORT -> PrivacyBlocked.RawExportDisabled()
    PrivacyCapability.ENCRYPTED_BACKUP -> PrivacyBlocked.EncryptedBackupDisabled()
    PrivacyCapability.DEBUG_DATA_PERSISTENCE -> PrivacyBlocked.DebugDataPersistenceDisabled()
    else -> PrivacyBlocked.Custom(capability, PrivacyGateReasonCodes.PRIVACY_BLOCKED)
}
