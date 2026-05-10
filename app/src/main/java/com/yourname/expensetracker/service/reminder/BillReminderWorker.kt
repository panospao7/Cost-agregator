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
import com.yourname.expensetracker.data.database.dao.PipelineDiagnosticEventDao
import com.yourname.expensetracker.data.database.entity.PipelineDiagnosticEvent
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import com.yourname.expensetracker.domain.workers.WorkerExecutionGuard
import com.yourname.expensetracker.domain.workers.WorkerGuardRequest
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
    private val diagnosticEventDao: PipelineDiagnosticEventDao
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "BillReminderWorker started — checking for due reminders")

        val guardResult = executionGuard.runGuarded(
            WorkerGuardRequest(
                workerName = "bill_reminder_periodic",
                allowDuringBackupExport = false
            )
        ) {
            try {
                val dueReminders = coordinator.getDueReminders()
                if (dueReminders.isEmpty()) {
                    Log.d(TAG, "No due reminders found")
                    return@runGuarded
                }

                var sentCount = 0
                for (reminder in dueReminders) {
                    if (isStopped) break

                    // Atomic claim: only one worker can claim each delivery
                    if (!coordinator.claimReminderDelivery(reminder.id)) {
                        Log.d(TAG, "Reminder ${reminder.id} already claimed by another worker, skipping")
                        continue
                    }

                    val title = "Bill due"
                    val body = buildNotificationBody(reminder)
                    val delivered = sendNotification(reminder, title, body)

                    if (delivered) {
                        coordinator.markReminderSent(reminder.id)
                        sentCount++
                        try {
                            diagnosticEventDao.insert(
                                PipelineDiagnosticEvent(
                                    pipeline = "bill_reminder",
                                    stage = "dispatch",
                                    outcome = "SENT",
                                    entityType = "RecurringReminderDelivery",
                                    entityId = reminder.id,
                                    message = "Reminder successfully delivered",
                                    timestamp = System.currentTimeMillis()
                                )
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to write reminder diagnostic event", e)
                        }
                    } else {
                        Log.w(TAG, "Notification delivery failed for reminder ${reminder.id}")
                        coordinator.markReminderFailed(reminder.id, "permission_denied")
                    }
                }

                Log.d(TAG, "BillReminderWorker completed — sent $sentCount reminders")
            } catch (e: Exception) {
                Log.e(TAG, "BillReminderWorker failed", e)
                throw e
            }
        }

        return guardResult.toWorkerResult()
    }

    /**
     * Builds a notification body using actual occurrence details (merchant/amount/currency).
     * Falls back to a generic message if the occurrence cannot be loaded.
     */
    private suspend fun buildNotificationBody(
        reminder: com.yourname.expensetracker.data.database.entity.RecurringReminderDelivery
    ): String {
        val occurrence = coordinator.getOccurrenceById(reminder.occurrenceId)
        return if (occurrence != null) {
            val amount = "%.2f".format(occurrence.expectedAmount)
            val currency = occurrence.expectedCurrency
            val merchant = occurrence.merchant ?: "Bill"
            "$merchant due: $amount $currency"
        } else {
            "Bill reminder (details unavailable)"
        }
    }

    /**
     * Sends an Android notification using [NotificationManagerCompat].
     * Creates the notification channel on first invocation if needed.
     * Adds Snooze (24h) and Dismiss action buttons via [SnoozeReminderReceiver]
     * and [DismissReminderReceiver] broadcast receivers.
     */
    private fun sendNotification(
        delivery: com.yourname.expensetracker.data.database.entity.RecurringReminderDelivery,
        title: String,
        body: String
    ): Boolean {
        ensureChannelExists()

        // Snooze action — marks delivery SNOOZED for 24h
        val snoozeIntent = Intent(applicationContext, SnoozeReminderReceiver::class.java).apply {
            putExtra("deliveryId", delivery.id)
        }
        val snoozePendingIntent = PendingIntent.getBroadcast(
            applicationContext,
            delivery.id.toInt(),
            snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Dismiss action — marks delivery DISMISSED
        val dismissIntent = Intent(applicationContext, DismissReminderReceiver::class.java).apply {
            putExtra("deliveryId", delivery.id)
        }
        val dismissPendingIntent = PendingIntent.getBroadcast(
            applicationContext,
            (delivery.id + 10000).toInt(),
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
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Missing notification permission — cannot send notification", e)
            false
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
