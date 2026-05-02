package com.yourname.expensetracker.service.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import com.yourname.expensetracker.domain.workers.WorkerSpec
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

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
    private val restoreMaintenanceMode: RestoreMaintenanceMode
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "BillReminderWorker started — checking for due reminders")

        // Defense-in-depth: block writes during restore maintenance mode
        if (!restoreMaintenanceMode.isWritesAllowed()) {
            Log.w(TAG, "Writes blocked during restore mode, skipping")
            return Result.success()
        }

        // WorkerSpec gate: check if this worker is enabled
        val spec = WorkerSpec.DEFAULTS[WORK_NAME] ?: return Result.success()
        if (!spec.enabled) {
            Log.w(TAG, "Worker $WORK_NAME disabled by spec, skipping")
            return Result.success()
        }

        return try {
            val dueReminders = coordinator.getDueReminders()
            if (dueReminders.isEmpty()) {
                Log.d(TAG, "No due reminders found")
                return Result.success()
            }

            var sentCount = 0
            for (reminder in dueReminders) {
                if (isStopped) break

                val title = "Bill due"
                val body = buildNotificationBody(reminder)
                sendNotification(reminder.occurrenceId, title, body)

                // Update delivery status to SENT
                coordinator.markReminderSent(reminder.id)
                sentCount++
            }

            Log.d(TAG, "BillReminderWorker completed — sent $sentCount reminders")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "BillReminderWorker failed", e)
            Result.retry()
        }
    }

    /**
     * Builds a basic "Bill due: X EUR for Y" notification body.
     */
    private fun buildNotificationBody(
        reminder: com.yourname.expensetracker.data.database.entity.RecurringReminderDelivery
    ): String {
        val amount = "%.2f".format(reminder.occurrenceId)
        return "Bill due: $amount EUR"
    }

    /**
     * Sends an Android notification using [NotificationManagerCompat].
     * Creates the notification channel on first invocation if needed.
     */
    private fun sendNotification(occurrenceId: Long, title: String, body: String) {
        ensureChannelExists()

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        val notificationId = (occurrenceId % Int.MAX_VALUE).toInt()
        try {
            NotificationManagerCompat.from(applicationContext).notify(notificationId, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Missing notification permission — cannot send notification", e)
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
        private const val PERIOD_INTERVAL_HOURS = 6L

        private const val CHANNEL_ID = "bill_reminders"
        private const val CHANNEL_NAME = "Bill Reminders"
        private const val CHANNEL_DESCRIPTION = "Notifications for due bill payments"

        /**
         * Schedules the periodic bill-reminder worker.
         *
         * Runs every [PERIOD_INTERVAL_HOURS] hours, with a 15-minute flex interval.
         * Uses [ExistingPeriodicWorkPolicy.KEEP] so only one schedule is active.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BillReminderWorker>(
                PERIOD_INTERVAL_HOURS, TimeUnit.HOURS,
                15, TimeUnit.MINUTES
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.d(TAG, "BillReminderWorker scheduled every $PERIOD_INTERVAL_HOURS hours")
        }
    }
}
