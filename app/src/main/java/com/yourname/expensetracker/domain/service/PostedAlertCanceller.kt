package com.yourname.expensetracker.domain.service

import com.yourname.expensetracker.domain.util.NotificationId

interface PostedAlertCanceller {
    fun cancel(notificationId: NotificationId)
}
