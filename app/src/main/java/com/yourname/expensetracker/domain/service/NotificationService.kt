package com.yourname.expensetracker.domain.service

interface NotificationService {
    fun sendBudgetAlert(
        notificationId: Int,
        title: String,
        message: String
    )
}
