package com.yourname.expensetracker.domain.service

import com.yourname.expensetracker.domain.util.NotificationId

interface NotificationService {
    enum class DeliveryResult {
        DELIVERED,
        NOT_DELIVERED
    }

    fun sendBudgetAlert(
        notificationId: Int,
        title: String,
        message: String
    ): DeliveryResult

    fun sendAiBriefingReady(
        notificationId: Int,
        title: String,
        message: String,
        targetKey: String
    )

    fun sendAiBriefingReadyWithResult(
        notificationId: Int,
        title: String,
        message: String,
        targetKey: String
    ): DeliveryResult {
        sendAiBriefingReady(
            notificationId = notificationId,
            title = title,
            message = message,
            targetKey = targetKey
        )
        return DeliveryResult.DELIVERED
    }

    /**
     * Send an anomaly alert notification for unusual charges.
     *
     * @param notificationId Unique ID for the notification
     * @param title Notification title
     * @param message Notification message describing the anomaly
     * @param expenseId The expense ID for deep linking to transaction detail
     */
    fun sendAnomalyAlert(
        notificationId: Int,
        title: String,
        message: String,
        expenseId: Long
    )

    // ── RP-16 16-A: typed allocation boundary ────────────────────────────
    // Kind-specific posting helpers: these accept [NotificationId], whose value
    // can only originate from NotificationIdGenerator, so raw Int IDs cannot be
    // posted through this path. Distinct names (post*) keep the mediated path
    // unambiguous against the legacy Int-based methods.

    fun postBudgetAlert(
        notificationId: NotificationId,
        title: String,
        message: String
    ): DeliveryResult = sendBudgetAlert(notificationId.value, title, message)

    fun postAiBriefingReady(
        notificationId: NotificationId,
        title: String,
        message: String,
        targetKey: String
    ) = sendAiBriefingReady(notificationId.value, title, message, targetKey)

    fun postAiBriefingReadyWithResult(
        notificationId: NotificationId,
        title: String,
        message: String,
        targetKey: String
    ): DeliveryResult = sendAiBriefingReadyWithResult(notificationId.value, title, message, targetKey)

    fun postAnomalyAlert(
        notificationId: NotificationId,
        title: String,
        message: String,
        expenseId: Long
    ) = sendAnomalyAlert(notificationId.value, title, message, expenseId)
}
