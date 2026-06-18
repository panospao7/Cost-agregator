package com.yourname.expensetracker.domain.privacy

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PR3: Gate that consolidates all notification-capture privacy decisions.
 *
 * Checks (in order):
 * 1. Fast in-memory flag (notificationCaptureEnabled setting).
 * 2. Full [PrivacyGate] decision for [PrivacyCapability.NOTIFICATION_CAPTURE].
 *
 * The gate does NOT check blocked-package policy (that remains a repository concern)
 * but it does enforce that the capture gate is checked BEFORE extras extraction.
 */
@Singleton
class NotificationCaptureGate @Inject constructor(
    private val privacyGate: PrivacyGate,
    private val privacySettingsRepository: PrivacySettingsRepository
) {
    /**
     * True if notification capture is currently allowed.
     * Callers MUST check this BEFORE reading any notification extras.
     */
    suspend fun isCaptureAllowed(): Boolean {
        val settings = privacySettingsRepository.getSettings()
        if (!settings.notificationCaptureEnabled) {
            Timber.d("NotificationCaptureGate: capture disabled by settings")
            return false
        }
        val decision = privacyGate.check(PrivacyCapability.NOTIFICATION_CAPTURE)
        return !decision.blocksExecution()
    }

    /**
     * Returns the raw storage mode for notifications under current settings.
     * Used to build [NotificationPersistencePayload].
     */
    suspend fun rawStorageMode(): RawStorageMode =
        privacySettingsRepository.getSettings().rawNotificationStorageMode
}
