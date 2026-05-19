package com.yourname.expensetracker.domain.privacy

/**
 * Sanitized payload for persisting a notification according to the current
 * [RawStorageMode].
 *
 * This is the contract between the capture gate and the repository/database layer.
 * Fields that are null mean "do not store".
 * Fields that are "[REDACTED]" mean "store a redaction marker".
 */
data class NotificationPersistencePayload(
    /** Raw notification row fields — null when mode ≠ STORE_RAW. */
    val rawNotificationTitle: String?,
    val rawNotificationText: String?,
    val rawNotificationBigText: String?,
    val rawNotificationSubText: String?,
    /** Extras JSON — null unless STORE_RAW. */
    val rawNotificationExtrasJson: String?,

    /** PendingReview display fields — may be redacted or null. */
    val pendingReviewTitle: String?,
    val pendingReviewText: String?,

    /** Fingerprint for deduplication — always present. */
    val dedupeFingerprint: String,
    /** Keyed hash of the notification key for diagnostics — always present. */
    val notificationKeyHash: String?,

    val mode: RawStorageMode
) {
    companion object {
        fun build(
            mode: RawStorageMode,
            rawTitle: String?,
            rawText: String?,
            rawBigText: String?,
            rawSubText: String?,
            extrasJson: String?,
            dedupeFingerprint: String,
            notificationKeyHash: String?
        ): NotificationPersistencePayload = when (mode) {
            RawStorageMode.STORE_RAW -> NotificationPersistencePayload(
                rawNotificationTitle = rawTitle,
                rawNotificationText = rawText,
                rawNotificationBigText = rawBigText,
                rawNotificationSubText = rawSubText,
                rawNotificationExtrasJson = extrasJson,
                pendingReviewTitle = rawTitle,
                pendingReviewText = rawText,
                dedupeFingerprint = dedupeFingerprint,
                notificationKeyHash = notificationKeyHash,
                mode = mode
            )
            RawStorageMode.STORE_REDACTED -> NotificationPersistencePayload(
                rawNotificationTitle = "[REDACTED]",
                rawNotificationText = "[REDACTED]",
                rawNotificationBigText = "[REDACTED]",
                rawNotificationSubText = "[REDACTED]",
                rawNotificationExtrasJson = """{"redacted":true}""",
                pendingReviewTitle = "[REDACTED]",
                pendingReviewText = "[REDACTED]",
                dedupeFingerprint = dedupeFingerprint,
                notificationKeyHash = notificationKeyHash,
                mode = mode
            )
            RawStorageMode.STORE_METADATA_ONLY -> NotificationPersistencePayload(
                rawNotificationTitle = null,
                rawNotificationText = null,
                rawNotificationBigText = null,
                rawNotificationSubText = null,
                rawNotificationExtrasJson = null,
                pendingReviewTitle = null,
                pendingReviewText = null,
                dedupeFingerprint = dedupeFingerprint,
                notificationKeyHash = notificationKeyHash,
                mode = mode
            )
            RawStorageMode.DO_NOT_STORE -> NotificationPersistencePayload(
                rawNotificationTitle = null,
                rawNotificationText = null,
                rawNotificationBigText = null,
                rawNotificationSubText = null,
                rawNotificationExtrasJson = null,
                pendingReviewTitle = null,
                pendingReviewText = null,
                dedupeFingerprint = dedupeFingerprint,
                notificationKeyHash = notificationKeyHash,
                mode = mode
            )
        }
    }
}
