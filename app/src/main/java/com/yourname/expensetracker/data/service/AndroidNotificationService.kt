package com.yourname.expensetracker.data.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.notification.presentation.NotificationContentPolicy
import com.yourname.expensetracker.domain.notification.presentation.NotificationKind
import com.yourname.expensetracker.domain.notification.presentation.NotificationPresentationResolver
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticCode
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticKey
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticMetadata
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticsEmitter
import com.yourname.expensetracker.domain.service.NotificationService
import com.yourname.expensetracker.ui.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

@Singleton
class AndroidNotificationService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val presentationResolver: NotificationPresentationResolver,
    private val presentationApplier: NotificationPresentationApplier,
    private val privacyDiagnostics: PrivacyDiagnosticsEmitter
) : NotificationService {

    companion object {
        private const val TAG = "AndroidNotificationService"

        // The three channel ids are read by NotificationKindClassifier (S9b); one literal each.
        internal const val BUDGET_CHANNEL_ID = "budget_alerts"
        private const val BUDGET_CHANNEL_NAME = "Budget Alerts"
        private const val BUDGET_CHANNEL_DESC = "Notifications when budget thresholds are exceeded"
        internal const val AI_CHANNEL_ID = "ai_briefings"
        private const val AI_CHANNEL_NAME = "AI Briefings"
        private const val AI_CHANNEL_DESC = "Read-only notifications for proactive AI finance briefings"
        internal const val ANOMALY_CHANNEL_ID = "anomaly_alerts"
        private const val ANOMALY_CHANNEL_NAME = "Unusual Activity Alerts"
        private const val ANOMALY_CHANNEL_DESC = "Real-time alerts for anomalous or suspicious charges"

        // Per-channel builder shape, shared by the kind-aware posts and the discreet re-post sweep so the two
        // cannot drift. The bill shape mirrors the one BillReminderWorker builds (the second builder owner).
        private val BUDGET_SHAPE = PostShape(
            android.R.drawable.ic_dialog_alert, NotificationCompat.PRIORITY_HIGH, NotificationCompat.CATEGORY_REMINDER
        )
        private val AI_SHAPE = PostShape(
            android.R.drawable.ic_dialog_info, NotificationCompat.PRIORITY_DEFAULT, NotificationCompat.CATEGORY_REMINDER
        )
        private val ANOMALY_SHAPE = PostShape(
            android.R.drawable.ic_dialog_alert, NotificationCompat.PRIORITY_HIGH, NotificationCompat.CATEGORY_ALARM
        )
        private val BILL_SHAPE = PostShape(
            android.R.drawable.ic_dialog_info, NotificationCompat.PRIORITY_DEFAULT, category = null
        )

        /** BILL action templates in the order the worker adds them: Snooze, then Dismiss. */
        private val BILL_ACTION_TEMPLATES = listOf(
            R.drawable.ic_snooze to R.string.notif_action_snooze,
            R.drawable.ic_dismiss to R.string.action_dismiss
        )
    }

    private class PostShape(
        val smallIcon: Int,
        val priority: Int,
        val category: String?
    )

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

    // ── Kind-aware methods (S5a: TECH-2 option a, TECH-9, TECH-12) ────────────────────────────────
    // resolve(kind) -> build -> NotificationPresentationApplier -> notify. These are the only posting
    // methods (S9a removed the kind-less legacy forms). A SecurityException from notify() is returned as
    // PERMISSION_DENIED; every other exception, including CancellationException, propagates to the caller.

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
            shape = BUDGET_SHAPE,
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
            shape = AI_SHAPE,
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
            shape = ANOMALY_SHAPE,
            title = title,
            message = message,
            contentIntent = { anomalyTapIntent(notificationId, expenseId) }
        )

    /**
     * The one post path for every kind-aware method. The enabled check comes first, so a disabled app
     * never reads the settings. The decision is resolved once per post
     * (bounded, fail-closed to DISCREET inside the resolver) and applied by the shared applier. The log
     * line carries the kind token and exception class only, never notification text.
     */
    private suspend fun postForKind(
        kind: NotificationKind,
        notificationId: Int,
        channelId: String,
        shape: PostShape,
        title: String,
        message: String,
        contentIntent: () -> PendingIntent
    ): NotificationService.DeliveryResult {
        if (!notificationManager.areNotificationsEnabled()) {
            return NotificationService.DeliveryResult.NOT_DELIVERED
        }

        val decision = presentationResolver.resolve(kind, Build.VERSION.SDK_INT)

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(shape.smallIcon)
            .setPriority(shape.priority)
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
        shape.category?.let { builder.setCategory(it) }
        val notification = presentationApplier.apply(
            builder = builder,
            decision = decision,
            channelId = channelId,
            smallIcon = shape.smallIcon,
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

    // ── Discreet re-post sweep (S9b, INV-13, AMD-7, AMD-21, AMD-22, spec 4.6) ────────────────────────
    // Reads the active notifications once, never recovers a builder from a posted notification, and
    // rebuilds each one from the DISCREET decision for its detected kind. From the original it reuses
    // only the id, tag, channel, content intent, action PendingIntents (action policy re-applied) and the
    // post time. Titles come from resources per kind. It copies no text, extra or style. The marker is
    // read only to classify, and the rebuilt notification gets a fresh marker from the applier.

    override suspend fun repostActiveAsDiscreet(): NotificationService.RepostResult {
        val active: Array<StatusBarNotification> = try {
            notificationManager.activeNotifications
        } catch (e: SecurityException) {
            emitRepostFailed(e)
            return NotificationService.RepostResult(reposted = 0, failed = 1)
        }

        var reposted = 0
        var failed = 0
        for (original in active) {
            // Totality: one notification's failure (marker read, classify, rebuild or notify) never stops the
            // rest of the sweep. Cancellation is rethrown; the log and the diagnostic carry the class only.
            try {
                // The system may post its own group summary (ranker_group) on one of our channels. The app never
                // posts summaries, so one is never ours to rebuild: skipped, neither reposted nor failed.
                if ((original.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) continue

                val kind = NotificationKindClassifier.classify(
                    channelId = original.notification.channelId,
                    notificationId = original.id,
                    rawMarker = NotificationKindMarker.read(original.notification)
                ) ?: continue

                notificationManager.notify(original.tag, original.id, rebuildDiscreet(original, kind))
                reposted++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
                Log.w(TAG, "NOTIFICATION_REPOST_FAILED class=${e::class.java.simpleName}")
                emitRepostFailed(e)
            }
        }

        if (reposted > 0) {
            privacyDiagnostics.emit(
                PrivacyDiagnosticCode.NOTIF_REPOSTED_DISCREET,
                PrivacyDiagnosticMetadata.builder().put(PrivacyDiagnosticKey.COUNT, reposted).build()
            )
        }
        return NotificationService.RepostResult(reposted = reposted, failed = failed)
    }

    private fun rebuildDiscreet(original: StatusBarNotification, kind: NotificationKind): Notification {
        val old = original.notification
        val channelId = old.channelId
        val shape = shapeFor(kind)
        val decision = NotificationContentPolicy.decideDiscreet(kind, Build.VERSION.SDK_INT)

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(shape.smallIcon)
            .setPriority(shape.priority)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setWhen(old.`when`)
        shape.category?.let { builder.setCategory(it) }
        old.contentIntent?.let { builder.setContentIntent(it) }

        return presentationApplier.apply(
            builder = builder,
            decision = decision,
            channelId = channelId,
            smallIcon = shape.smallIcon,
            // Never read in DISCREET; empty on purpose so no detailed content can reach the rebuild.
            detailedTitle = "",
            detailedText = "",
            actions = if (kind == NotificationKind.BILL) billActionSpecs(old) else emptyList()
        ).build()
    }

    /** Reuses only the PendingIntents of the original actions, mapped by position to the resource-titled templates. */
    private fun billActionSpecs(old: Notification): List<NotificationActionSpec> {
        val originalActions = old.actions ?: return emptyList()
        return BILL_ACTION_TEMPLATES.mapIndexedNotNull { index, (iconRes, titleRes) ->
            originalActions.getOrNull(index)?.actionIntent?.let { NotificationActionSpec(iconRes, titleRes, it) }
        }
    }

    private fun shapeFor(kind: NotificationKind): PostShape =
        when (kind) {
            NotificationKind.BUDGET, NotificationKind.WARRANTY, NotificationKind.RECEIPT_MATCH -> BUDGET_SHAPE
            NotificationKind.ANOMALY -> ANOMALY_SHAPE
            NotificationKind.BRIEFING -> AI_SHAPE
            NotificationKind.BILL -> BILL_SHAPE
        }

    private suspend fun emitRepostFailed(e: Exception) {
        privacyDiagnostics.emit(
            PrivacyDiagnosticCode.NOTIF_REPOST_FAILED,
            PrivacyDiagnosticMetadata.builder().exceptionClass(e).build()
        )
    }

    // Explicit-component tap targets (TECH-6): one builder per target, same data URI, flags, request
    // codes and FLAG_IMMUTABLE as before; ACTION_VIEW kept so DeepLinkPolicy accepts the tap.

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
