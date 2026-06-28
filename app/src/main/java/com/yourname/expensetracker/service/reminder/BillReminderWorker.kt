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
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.workers.BlockedPolicy
import com.yourname.expensetracker.domain.workers.WorkerExecutionGuard
import com.yourname.expensetracker.domain.workers.WorkerGuardRequest
import com.yourname.expensetracker.domain.workers.WorkerSpec
import com.yourname.expensetracker.domain.workers.WorkerSpecScheduler
import com.yourname.expensetracker.domain.workers.toWorkerResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

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
    private val timeProvider: TimeProvider
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

            try {
                val dueReminders = coordinator.getDueReminders()
                if (dueReminders.isEmpty()) {
                    Log.d(TAG, "No due reminders found")
                    return@runGuardedWithContext
                }

                for (reminder in dueReminders) {
                    if (isStopped) break

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
                            val marked = coordinator.markReminderSent(reminder.id, result.notificationId)
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
                                            .put("notificationId", result.notificationId)
                                            .build()
                                    ))
                                } catch (e: Exception) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    Log.w(TAG, "Failed to write reminder diagnostic event", e)
                                }
                            } else {
                                Log.w(TAG, "Reminder ${reminder.id} was sent but could not be marked SENT (no longer CLAIMED)")
                                ctx.addRowsSkipped()
                            }
                        }
                        is NotificationSendResult.Failed -> {
                            Log.w(TAG, "Notification delivery failed for reminder ${reminder.id}: ${result.reason}")
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
                                    Log.w(TAG, "Failed to write permission-revoked diagnostic", e)
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
                Log.e(TAG, "BillReminderWorker failed", e)
                throw e
            }
        }

        return guardResult.toWorkerResult()
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
        data class Sent(val notificationId: Int) : NotificationSendResult
        data class Failed(val reason: String) : NotificationSendResult
    }

    /**
     * Sends an Android notification using [NotificationManagerCompat].
     * Creates the notification channel on first invocation if needed.
     * Adds Snooze (24h) and Dismiss action buttons via [SnoozeReminderReceiver]
     * and [DismissReminderReceiver] broadcast receivers.
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

        // Snooze action — marks delivery SNOOZED for 24h
        val snoozeIntent = Intent(applicationContext, SnoozeReminderReceiver::class.java).apply {
            putExtra("deliveryId", delivery.id)
        }
        // P4-NEW-005/006: Use stable ID derived from delivery.id (not hashCode)
        // to ensure PendingIntent request codes are deterministic across restarts.
        val snoozeRequestCode = (delivery.id % Int.MAX_VALUE).toInt()
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

        val notificationId = (delivery.id % Int.MAX_VALUE).toInt()
        return try {
            NotificationManagerCompat.from(applicationContext).notify(notificationId, notification)
            NotificationSendResult.Sent(notificationId)
        } catch (e: SecurityException) {
            Log.w(TAG, "Missing notification permission — cannot send notification", e)
            NotificationSendResult.Failed("permission_denied")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "Notification delivery failed", e)
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
