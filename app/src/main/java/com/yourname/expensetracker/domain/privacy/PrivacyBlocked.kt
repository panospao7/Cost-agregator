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
 *   [toPrivacyBlocked] or receive a typed [AiServiceError.PrivacyDenied] from the gate layer.
 * - The [reason] string is a static default — the [PrivacyDecision.Denied.reason] from
 *   the gate is passed through so the decision reason is preserved end-to-end.
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
 */
fun PrivacyDecision.toPrivacyBlocked(capability: PrivacyCapability): PrivacyBlocked? = when (this) {
    is PrivacyDecision.Allowed, is PrivacyDecision.NotApplicable -> null
    is PrivacyDecision.Denied -> privacyBlockedFromCapability(capability, reason)
    is PrivacyDecision.FailClosed -> PrivacyBlocked.Custom(capability, "Privacy check failed safely: $reason")
}

private fun privacyBlockedFromCapability(capability: PrivacyCapability, reason: String): PrivacyBlocked = when (capability) {
    PrivacyCapability.CLOUD_AI_GENERAL,
    PrivacyCapability.CLOUD_AI_RECEIPT_ASSIST,
    PrivacyCapability.CLOUD_AI_ITEM_CATEGORIZATION,
    PrivacyCapability.CLOUD_AI_WARRANTY_EXTRACTION,
    PrivacyCapability.CLOUD_AI_DAILY_BRIEFING -> PrivacyBlocked.CloudAiDisabled(reason)
    PrivacyCapability.CLOUD_AI_BANK_STATEMENT,
    PrivacyCapability.AI_BANK_STATEMENT_PARSING -> PrivacyBlocked.BankStatementAiDisabled(reason)
    PrivacyCapability.RECEIPT_IMAGE_CLOUD_UPLOAD -> PrivacyBlocked.ReceiptImageUploadDisabled(reason)
    PrivacyCapability.EXTERNAL_GEOCODING -> PrivacyBlocked.ExternalGeocodingDisabled(reason)
    PrivacyCapability.OVERPASS_API -> PrivacyBlocked.OverpassDisabled(reason)
    PrivacyCapability.NOTIFICATION_CAPTURE,
    PrivacyCapability.NOTIFICATION_PACKAGE_ALLOWLIST -> PrivacyBlocked.NotificationCaptureDisabled(reason)
    PrivacyCapability.BACKGROUND_LOCATION_BACKFILL -> PrivacyBlocked.BackgroundLocationDisabled(reason)
    PrivacyCapability.DEVICE_GPS_LOCATION -> PrivacyBlocked.DeviceGpsDisabled(reason)
    PrivacyCapability.RAWBACKUP_EXPORT -> PrivacyBlocked.RawExportDisabled(reason)
    PrivacyCapability.ENCRYPTED_BACKUP -> PrivacyBlocked.EncryptedBackupDisabled(reason)
    PrivacyCapability.DEBUG_DATA_PERSISTENCE -> PrivacyBlocked.DebugDataPersistenceDisabled(reason)
    else -> PrivacyBlocked.Custom(capability, reason)
}
