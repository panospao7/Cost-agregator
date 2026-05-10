package com.yourname.expensetracker.domain.privacy

/**
 * Standardized privacy-denied states for UI messaging.
 *
 * Each subclass maps to a specific [PrivacyCapability] that was denied, with a
 * human-readable [reason] string. UI components should use these classes for
 * consistent privacy-denied messaging across all screens rather than
 * constructing ad-hoc error strings.
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

    data class Custom(
        override val capability: PrivacyCapability,
        override val reason: String
    ) : PrivacyBlocked
}
