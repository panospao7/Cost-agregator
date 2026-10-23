package com.yourname.expensetracker.data.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.yourname.expensetracker.domain.notification.presentation.NotificationKind
import com.yourname.expensetracker.domain.notification.presentation.NotificationPresentationResolver
import com.yourname.expensetracker.domain.service.NotificationService
import com.yourname.expensetracker.ui.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidNotificationService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val presentationResolver: NotificationPresentationResolver,
    private val presentationApplier: NotificationPresentationApplier
) : NotificationService {

    companion object {
        private const val TAG = "AndroidNotificationService"
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
    ): NotificationService.DeliveryResult {
        if (!notificationManager.areNotificationsEnabled()) {
            return NotificationService.DeliveryResult.NOT_DELIVERED
        }

        val builder = NotificationCompat.Builder(context, BUDGET_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(homeTapIntent())
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
        val notification = presentationApplier.hardenLegacy(
            builder = builder,
            channelId = BUDGET_CHANNEL_ID,
            smallIcon = android.R.drawable.ic_dialog_alert
        ).build()

        notificationManager.notify(notificationId, notification)
        return NotificationService.DeliveryResult.DELIVERED
    }

    override fun sendAiBriefingReady(
        notificationId: Int,
        title: String,
        message: String,
        targetKey: String
    ) {
        sendAiBriefingReadyWithResult(
            notificationId = notificationId,
            title = title,
            message = message,
            targetKey = targetKey
        )
    }

    override fun sendAiBriefingReadyWithResult(
        notificationId: Int,
        title: String,
        message: String,
        targetKey: String
    ): NotificationService.DeliveryResult {
        if (!notificationManager.areNotificationsEnabled()) {
            return NotificationService.DeliveryResult.NOT_DELIVERED
        }

        val builder = NotificationCompat.Builder(context, AI_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(briefingTapIntent(notificationId, targetKey))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
        val notification = presentationApplier.hardenLegacy(
            builder = builder,
            channelId = AI_CHANNEL_ID,
            smallIcon = android.R.drawable.ic_dialog_info
        ).build()

        notificationManager.notify(notificationId, notification)
        return NotificationService.DeliveryResult.DELIVERED
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

        val builder = NotificationCompat.Builder(context, ANOMALY_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(anomalyTapIntent(notificationId, expenseId))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
        val notification = presentationApplier.hardenLegacy(
            builder = builder,
            channelId = ANOMALY_CHANNEL_ID,
            smallIcon = android.R.drawable.ic_dialog_alert
        ).build()

        notificationManager.notify(notificationId, notification)
    }

    // ── Kind-aware methods (S5a: TECH-2 option a, TECH-9, TECH-12) ────────────────────────────────
    // resolve(kind) -> build -> NotificationPresentationApplier -> notify. The legacy methods above keep
    // their visible text and behaviour; S5b only hardens them (private visibility, generic public version,
    // no marker) and moves them onto the same tap-intent builders below. A SecurityException from
    // notify() is returned as PERMISSION_DENIED; every other exception, including
    // CancellationException, propagates to the caller.

    override suspend fun sendBudgetAlertForKind(
        kind: NotificationKind,
        notificationId: Int,
        title: String,
        message: String
    ): NotificationService.DeliveryResult =
        postForKind(
            kind = kind,
            notificationId = notificationId,
            channelId = BUDGET_CHANNEL_ID,
            smallIcon = android.R.drawable.ic_dialog_alert,
            priority = NotificationCompat.PRIORITY_HIGH,
            category = NotificationCompat.CATEGORY_REMINDER,
            title = title,
            message = message,
            contentIntent = { homeTapIntent() }
        )

    override suspend fun sendAiBriefingReadyForKind(
        kind: NotificationKind,
        notificationId: Int,
        title: String,
        message: String,
        targetKey: String
    ): NotificationService.DeliveryResult =
        postForKind(
            kind = kind,
            notificationId = notificationId,
            channelId = AI_CHANNEL_ID,
            smallIcon = android.R.drawable.ic_dialog_info,
            priority = NotificationCompat.PRIORITY_DEFAULT,
            category = NotificationCompat.CATEGORY_REMINDER,
            title = title,
            message = message,
            contentIntent = { briefingTapIntent(notificationId, targetKey) }
        )

    override suspend fun sendAnomalyAlertForKind(
        kind: NotificationKind,
        notificationId: Int,
        title: String,
        message: String,
        expenseId: Long
    ): NotificationService.DeliveryResult =
        postForKind(
            kind = kind,
            notificationId = notificationId,
            channelId = ANOMALY_CHANNEL_ID,
            smallIcon = android.R.drawable.ic_dialog_alert,
            priority = NotificationCompat.PRIORITY_HIGH,
            category = NotificationCompat.CATEGORY_ALARM,
            title = title,
            message = message,
            contentIntent = { anomalyTapIntent(notificationId, expenseId) }
        )

    /**
     * The one post path for every kind-aware method. The enabled check comes first, as in the legacy
     * methods, so a disabled app never reads the settings. The decision is resolved once per post
     * (bounded, fail-closed to DISCREET inside the resolver) and applied by the shared applier. The log
     * line carries the kind token and exception class only, never notification text.
     */
    private suspend fun postForKind(
        kind: NotificationKind,
        notificationId: Int,
        channelId: String,
        smallIcon: Int,
        priority: Int,
        category: String,
        title: String,
        message: String,
        contentIntent: () -> PendingIntent
    ): NotificationService.DeliveryResult {
        if (!notificationManager.areNotificationsEnabled()) {
            return NotificationService.DeliveryResult.NOT_DELIVERED
        }

        val decision = presentationResolver.resolve(kind, Build.VERSION.SDK_INT)

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(smallIcon)
            .setPriority(priority)
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .setCategory(category)
        val notification = presentationApplier.apply(
            builder = builder,
            decision = decision,
            channelId = channelId,
            smallIcon = smallIcon,
            detailedTitle = title,
            detailedText = message
        ).build()

        return try {
            notificationManager.notify(notificationId, notification)
            NotificationService.DeliveryResult.DELIVERED
        } catch (e: SecurityException) {
            Log.w(TAG, "NOTIFICATION_PERMISSION_DENIED kind=${kind.stableToken} class=${e::class.java.simpleName}")
            NotificationService.DeliveryResult.PERMISSION_DENIED
        }
    }

    // Explicit-component tap targets (TECH-6), shared by the legacy and kind-aware paths: one builder
    // per target, same data URI, flags, request codes and FLAG_IMMUTABLE as before; ACTION_VIEW kept so
    // DeepLinkPolicy accepts the tap.

    private fun homeTapIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = android.net.Uri.parse("expensetracker://home")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun briefingTapIntent(notificationId: Int, targetKey: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = briefingUri(targetKey)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun anomalyTapIntent(notificationId: Int, expenseId: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = android.net.Uri.parse("expensetracker://activity?expenseId=$expenseId")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // `expensetracker://dashboard?briefingKey=<key>`, with the key percent-encoded by the Uri builder
    // (":" becomes "%3A"). DeepLinkPolicy reads it with getQueryParameter, which decodes it.
    private fun briefingUri(targetKey: String): android.net.Uri =
        android.net.Uri.Builder()
            .scheme("expensetracker")
            .authority("dashboard")
            .appendQueryParameter("briefingKey", targetKey)
            .build()
}
