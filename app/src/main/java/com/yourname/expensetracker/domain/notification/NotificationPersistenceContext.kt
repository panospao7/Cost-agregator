package com.yourname.expensetracker.domain.notification

import com.yourname.expensetracker.domain.privacy.RawStorageMode

/**
 * Captured privacy context for a notification being processed.
 * Ensures downstream persistence (PendingReview, audit events) uses the
 * storage mode active at capture time, not current settings.
 */
data class NotificationPersistenceContext(
    val rawStorageMode: RawStorageMode,
    val payloadMode: String?,
    val source: String
)
