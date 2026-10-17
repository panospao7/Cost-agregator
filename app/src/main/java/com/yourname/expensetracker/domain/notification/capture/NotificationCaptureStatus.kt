package com.yourname.expensetracker.domain.notification.capture

/**
 * R17: user-facing notification-capture status derived from privacy consent and the
 * OS notification-listener access grant. Display-only; it never enables capture.
 */
enum class NotificationCaptureStatus {
    /** Consent is on and OS listener access is granted. */
    ACTIVE,

    /** Consent is on but OS listener access is not granted. */
    ACCESS_OFF,

    /** Consent is off (or settings are corrupted/fail-closed). Consent wins over access. */
    CONSENT_OFF;

    companion object {
        fun resolve(consentOn: Boolean, accessGranted: Boolean): NotificationCaptureStatus = when {
            !consentOn -> CONSENT_OFF
            accessGranted -> ACTIVE
            else -> ACCESS_OFF
        }
    }
}
