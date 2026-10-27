package com.yourname.expensetracker.service.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import com.yourname.expensetracker.domain.reminder.BillReminderManager
import com.yourname.expensetracker.domain.util.NotificationId
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.workers.BlockedPolicy
import com.yourname.expensetracker.domain.workers.WorkerCheckpointBlockedException
import com.yourname.expensetracker.domain.workers.WorkerExecutionGuard
import com.yourname.expensetracker.domain.workers.WorkerGuardRequest
import com.yourname.expensetracker.domain.workers.WorkerSpec
import com.yourname.expensetracker.domain.workers.WorkerSpecScheduler
import com.yourname.expensetracker.domain.workers.toWorkerResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Periodic WorkManager worker that checks for due reminder deliveries
 * and dispatches Android notifications for bills that are due or overdue.
 *
 * The worker is scheduled every [PERIOD_INTERVAL_HOURS] hours and processes
 * all pending deliveries returned by [RecurringLifecycleCoordinator.getDueReminders].
 */
@HiltWorker
class BillReminderWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val coordinator: RecurringLifecycleCoordinator,
    private val executionGuard: WorkerExecutionGuard,
    private val diagnosticEventWriter: com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter,
    private val reminderSettingsRepository: com.yourname.expensetracker.domain.reminder.BillReminderSettingsRepository,
    private val timeProvider: TimeProvider,
    private val billReminderManager: BillReminderManager
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "BillReminderWorker started — checking for due reminders")

        val guardResult = executionGuard.runGuardedWithContext(
            WorkerGuardRequest(
                workerName = "bill_reminder_periodic",
                allowDuringBackupExport = false,
                blockedPolicy = BlockedPolicy.RETRY,
                requiresNotificationPermission = true,
                workId = id.toString(),
                runAttemptCount = runAttemptCount,
                specVersion = WorkerSpec.DEFAULTS["bill_reminder_periodic"]?.version
            )
        ) { ctx ->
            // P9-PR1 (NEW-P9-002): Settings/quiet-hours check moved INSIDE guard
            // so the run is properly logged even when skipped.
            val settings = reminderSettingsRepository.getSnapshot()
            if (!settings.billRemindersEnabled) {
                Log.d(TAG, "Bill reminders disabled by runtime settings — skipping")
                return@runGuardedWithContext
            }
            val now = timeProvider.now()
            if (settings.isWithinQuietHours(now)) {
                Log.d(TAG, "Bill reminders in quiet hours — skipping")
                return@runGuardedWithContext
            }

            // R21 S4: after a long gap (device off / worker starved) several missed
            // occurrences of the same rule can be due at once. Collapse stale due
            // deliveries (older than the latest per rule) BEFORE recovery/dispatch so
            // a rule yields at most one notification instead of a burst. Isolated in its own
            // try: a persistent collapse failure must not block reminder dispatch.
            try {
                coordinator.collapseStaleDueDeliveries(now)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Stale reminder collapse failed (${e::class.simpleName}); dispatching without collapse")
            }

            // S7b: installment reminders ride the same worker, settings and quiet hours. Isolated in
            // its own try so an installment failure never blocks the recurring dispatch below.
            try {
                dispatchInstallmentReminders(ctx)
            } catch (e: CancellationException) {
                throw e
            } catch (e: WorkerCheckpointBlockedException) {
                // The guard must see a blocked checkpoint (RETRY); never swallow it.
                throw e
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Installment reminder dispatch failed (${e::class.simpleName}); continuing")
            }

            try {
                val dueReminders = coordinator.recoverAndGetDueReminders()
                if (dueReminders.isEmpty()) {
                    Log.d(TAG, "No due reminders found")
                    return@runGuardedWithContext
                }

                for (reminder in dueReminders) {
                    if (isStopped) break

                    // RP-16 16-D: every due reminder examined is scanned work.
                    ctx.addRowsScanned()

                    ctx.checkpoint("bill_reminder")

                    if (!coordinator.claimReminderDelivery(reminder.id)) {
                        Log.d(TAG, "Reminder ${reminder.id} already claimed by another worker, skipping")
                        ctx.addRowsSkipped()
                        continue
                    }

                    // P4-NEW-03 / P4-P0-02: Revalidate occurrence after claim.
                    // If the occurrence is no longer PLANNED (e.g. user paid between
                    // claim and notification), cancel the claimed delivery and skip.
                    val snapshot = coordinator.getDispatchableClaimedReminder(reminder.id)
                    if (snapshot == null) {
                        coordinator.cancelClaimedReminderDelivery(
                            deliveryId = reminder.id,
                            reason = "not_dispatchable_after_claim"
                        )
                        ctx.addRowsSkipped()
                        continue
                    }

                    val title = "Bill due"
                    val body = buildNotificationBody(snapshot)
                    val result = sendNotification(reminder, title, body)

                    when (result) {
                        is NotificationSendResult.Sent -> {
                            val marked = coordinator.markReminderSent(reminder.id, result.notificationId.value)
                            if (marked) {
                                ctx.addNotificationsSent()
                                try {
                                    diagnosticEventWriter.emit(com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
                                        pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.RECURRING,
                                        stage = "dispatch",
                                        outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.COMPLETED,
                                        entityType = "RecurringReminderDelivery",
                                        entityId = reminder.id,
                                        metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                                            .put("delivered", true)
                                            .put("notificationId", result.notificationId.value)
                                            .build()
                                    ))
                                } catch (e: Exception) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    Log.w(TAG, "SIDE_EFFECT_EXCEPTION class=${e::class.java.simpleName}")
                                }
                            } else {
                                Log.w(TAG, "Reminder ${reminder.id} was sent but could not be marked SENT (no longer CLAIMED)")
                                ctx.addRowsSkipped()
                            }
                        }
                        is NotificationSendResult.Failed -> {
                            val failureCode = if (result.reason == "permission_denied") {
                                "WORKER_NOTIFICATION_PERMISSION_DENIED"
                            } else {
                                "WORKER_UNHANDLED_EXCEPTION"
                            }
                            Log.w(TAG, "$failureCode reminderId=${reminder.id}")
                            if (result.reason == "permission_denied") {
                                coordinator.cancelClaimedReminderDelivery(
                                    deliveryId = reminder.id,
                                    reason = "notification_permission_revoked"
                                )
                                try {
                                    diagnosticEventWriter.emit(com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
                                        pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.RECURRING,
                                        stage = "dispatch",
                                        outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.SKIPPED,
                                        entityType = "RecurringReminderDelivery",
                                        entityId = reminder.id,
                                        metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                                            .put("delivered", false)
                                            .put("reason", "notification_permission_revoked")
                                            .build()
                                    ))
                                } catch (e: Exception) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    Log.w(TAG, "SIDE_EFFECT_EXCEPTION class=${e::class.java.simpleName}")
                                }
                            } else {
                                coordinator.markReminderFailed(reminder.id, result.reason)
                            }
                            ctx.addRowsSkipped()
                        }
                    }
                }

                Log.d(TAG, "BillReminderWorker completed — sent ${ctx.notificationsSent} reminders")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "WORKER_UNHANDLED_EXCEPTION class=${e::class.java.simpleName}")
                throw e
            }
        }

        return guardResult.toWorkerResult()
    }

    /**
     * S7b: delivers installment reminders. Each reminder is claimed in the dedupe ledger
     * (`planUuid:seq:recordGeneration`) and its entry state is revalidated after the claim; only
     * then is it posted. A reminder that was not delivered gives its claim back so the next run
     * can retry. Logs carry no plan data. No Snooze/Dismiss actions are attached because those
     * receivers act on recurring delivery rows only.
     */
    private suspend fun dispatchInstallmentReminders(ctx: com.yourname.expensetracker.domain.workers.WorkerRunContext) {
        val candidates = billReminderManager.getDueInstallmentReminders()
        for (candidate in candidates) {
            if (isStopped) break
            ctx.addRowsScanned()
            ctx.checkpoint("installment_reminder")

            // Prompt cancellation can discard a claim that was already stored and revalidated (the
            // result never reaches us): give it back (release only removes an unsent record).
            val claimed = try {
                billReminderManager.claimInstallmentReminder(candidate)
            } catch (e: CancellationException) {
                withContext(NonCancellable) { billReminderManager.releaseInstallmentReminder(candidate) }
                throw e
            }
            if (claimed == null) {
                ctx.addRowsSkipped()
                continue
            }

            // Any throw between the claim and the post (including cancellation) gives the claim back
            // under NonCancellable and rethrows, so the reminder is not suppressed. markSent runs
            // only after the post and outside this try: a post must never be un-claimed.
            val result = try {
                val text = billReminderManager.buildInstallmentReminderText(claimed)
                val notificationId = NotificationId.forBill(claimed.dedupeKey.hashCode().toLong())
                sendInstallmentNotification(notificationId, text.title, text.body)
            } catch (t: Throwable) {
                // A failing release must not hide the original failure (or a cancellation).
                try {
                    withContext(NonCancellable) { billReminderManager.releaseInstallmentReminder(claimed) }
                } catch (r: CancellationException) {
                    t.addSuppressed(r)
                } catch (r: Exception) {
                    t.addSuppressed(r)
                }
                throw t
            }
            when (result) {
                is NotificationSendResult.Sent -> {
                    ctx.addNotificationsSent()
                    if (!billReminderManager.markInstallmentReminderSent(claimed)) {
                        Log.w(TAG, "INSTALLMENT_REMINDER_MARK_SENT_FAILED")
                    }
                }
                is NotificationSendResult.Failed -> {
                    billReminderManager.releaseInstallmentReminder(claimed)
                    Log.w(TAG, "INSTALLMENT_REMINDER_NOT_SENT reason=${result.reason}")
                    ctx.addRowsSkipped()
                }
            }
        }
    }

    private fun sendInstallmentNotification(
        notificationId: NotificationId,
        title: String,
        body: String
    ): NotificationSendResult {
        return try {
            ensureChannelExists()
            val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(applicationContext).notify(notificationId.value, notification)
            NotificationSendResult.Sent(notificationId)
        } catch (e: SecurityException) {
            Log.w(TAG, "WORKER_NOTIFICATION_PERMISSION_DENIED class=${e::class.java.simpleName}")
            NotificationSendResult.Failed("permission_denied")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "WORKER_UNHANDLED_EXCEPTION class=${e::class.java.simpleName}")
            NotificationSendResult.Failed("notification_error")
        }
    }

    /**
     * Builds a notification body using the dispatch snapshot's occurrence details.
     */
    private fun buildNotificationBody(
        snapshot: com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator.ReminderDispatchSnapshot
    ): String {
        val occurrence = snapshot.occurrence
        val amount = "%.2f".format(occurrence.expectedAmount)
        val currency = occurrence.expectedCurrency
        val merchant = occurrence.merchant ?: "Bill"
        return "$merchant due: $amount $currency"
    }

    /**
     * Result of attempting to send a notification.
     */
    private sealed interface NotificationSendResult {
        data class Sent(val notificationId: NotificationId) : NotificationSendResult
        data class Failed(val reason: String) : NotificationSendResult
    }

    /**
     * Sends an Android notification using [NotificationManagerCompat].
     * Creates the notification channel on first invocation if needed.
     * Adds Snooze (24h) and Dismiss action buttons via [SnoozeReminderReceiver]
     * and [DismissReminderReceiver] broadcast receivers.
     *
     * RP-16 16-A: the notification ID comes exclusively from the typed
     * allocation boundary ([NotificationId.forBill] → NotificationIdGenerator),
     * never from raw `(delivery.id % Int.MAX_VALUE)` arithmetic.
     *
     * @return [NotificationSendResult.Sent] with the notificationId on success,
     *         or [NotificationSendResult.Failed] with a reason on failure.
     */
    private fun sendNotification(
        delivery: com.yourname.expensetracker.data.database.entity.RecurringReminderDelivery,
        title: String,
        body: String
    ): NotificationSendResult {
        ensureChannelExists()

        // RP-16 16-A: typed ID from the reserved bill range (30000-39999).
        val notificationId = NotificationId.forBill(delivery.id)

        // Snooze action — marks delivery SNOOZED for 24h
        val snoozeIntent = Intent(applicationContext, SnoozeReminderReceiver::class.java).apply {
            putExtra("deliveryId", delivery.id)
        }
        // P4-NEW-005/006: Use the generator-derived notification ID (stable across
        // restarts) as the PendingIntent request code so it is deterministic.
        val snoozeRequestCode = notificationId.value
        val snoozePendingIntent = PendingIntent.getBroadcast(
            applicationContext,
            snoozeRequestCode,
            snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Dismiss action — marks delivery DISMISSED
        // Uses snoozeRequestCode xor'd with a flag to guarantee uniqueness from snooze.
        val dismissIntent = Intent(applicationContext, DismissReminderReceiver::class.java).apply {
            putExtra("deliveryId", delivery.id)
        }
        val dismissRequestCode = snoozeRequestCode xor 0x40000000
        val dismissPendingIntent = PendingIntent.getBroadcast(
            applicationContext,
            dismissRequestCode,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .addAction(R.drawable.ic_snooze, "Snooze", snoozePendingIntent)
            .addAction(R.drawable.ic_dismiss, "Dismiss", dismissPendingIntent)
            .build()

        return try {
            NotificationManagerCompat.from(applicationContext).notify(notificationId.value, notification)
            NotificationSendResult.Sent(notificationId)
        } catch (e: SecurityException) {
            Log.w(TAG, "WORKER_NOTIFICATION_PERMISSION_DENIED class=${e::class.java.simpleName}")
            NotificationSendResult.Failed("permission_denied")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "WORKER_UNHANDLED_EXCEPTION class=${e::class.java.simpleName}")
            NotificationSendResult.Failed("notification_error")
        }
    }

    /**
     * Ensures the [CHANNEL_ID] notification channel exists (Android 8+).
     */
    private fun ensureChannelExists() {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = CHANNEL_DESCRIPTION
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val TAG = "BillReminderWorker"
        const val WORK_NAME = "bill_reminder_periodic"

        private const val CHANNEL_ID = "bill_reminders"
        private const val CHANNEL_NAME = "Bill Reminders"
        private const val CHANNEL_DESCRIPTION = "Notifications for due bill payments"

        /**
         * Schedules the periodic bill-reminder worker.
         * Reads interval, flex, and constraints from [WorkerSpec.DEFAULTS] for the canonical config.
         * Uses [ExistingPeriodicWorkPolicy.KEEP] so only one schedule is active.
         */
        fun schedule(context: Context) {
            WorkerSpecScheduler.scheduleFromSpec(context, WORK_NAME, BillReminderWorker::class.java)
        }
    }
}
