package com.yourname.expensetracker.domain.service

interface NotificationService {
    fun sendBudgetAlert(
        notificationId: Int,
        title: String,
        message: String
    )

    fun sendAiBriefingReady(
        notificationId: Int,
        title: String,
        message: String,
        targetKey: String
    )

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
}
