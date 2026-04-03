package com.yourname.expensetracker.data.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.yourname.expensetracker.domain.service.NotificationService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidNotificationService @Inject constructor(
    @ApplicationContext private val context: Context
) : NotificationService {

    companion object {
        private const val BUDGET_CHANNEL_ID = "budget_alerts"
        private const val BUDGET_CHANNEL_NAME = "Budget Alerts"
        private const val BUDGET_CHANNEL_DESC = "Notifications when budget thresholds are exceeded"
        private const val AI_CHANNEL_ID = "ai_briefings"
        private const val AI_CHANNEL_NAME = "AI Briefings"
        private const val AI_CHANNEL_DESC = "Read-only notifications for proactive AI finance briefings"
        private const val ANOMALY_CHANNEL_ID = "anomaly_alerts"
        private const val ANOMALY_CHANNEL_NAME = "Unusual Activity Alerts"
        private const val ANOMALY_CHANNEL_DESC = "Real-time alerts for anomalous or suspicious charges"
    }

    private val notificationManager: NotificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val budgetChannel = NotificationChannel(
            BUDGET_CHANNEL_ID,
            BUDGET_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = BUDGET_CHANNEL_DESC
        }
        val aiChannel = NotificationChannel(
            AI_CHANNEL_ID,
            AI_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = AI_CHANNEL_DESC
        }
        val anomalyChannel = NotificationChannel(
            ANOMALY_CHANNEL_ID,
            ANOMALY_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = ANOMALY_CHANNEL_DESC
        }
        notificationManager.createNotificationChannels(listOf(budgetChannel, aiChannel, anomalyChannel))
    }

    override fun sendBudgetAlert(
        notificationId: Int,
        title: String,
        message: String
    ) {
        if (!notificationManager.areNotificationsEnabled()) {
            return
        }

        val notification = NotificationCompat.Builder(context, BUDGET_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(mainActivityIntent())
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()

        notificationManager.notify(notificationId, notification)
    }

    override fun sendAiBriefingReady(
        notificationId: Int,
        title: String,
        message: String,
        targetKey: String
    ) {
        if (!notificationManager.areNotificationsEnabled()) {
            return
        }

        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("expensetracker://dashboard?briefingKey=$targetKey")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, AI_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()

        notificationManager.notify(notificationId, notification)
    }

    override fun sendAnomalyAlert(
        notificationId: Int,
        title: String,
        message: String,
        expenseId: Long
    ) {
        if (!notificationManager.areNotificationsEnabled()) {
            return
        }

        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("expensetracker://transaction/$expenseId")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, ANOMALY_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .build()

        notificationManager.notify(notificationId, notification)
    }

    private fun mainActivityIntent(): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("expensetracker://home")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
