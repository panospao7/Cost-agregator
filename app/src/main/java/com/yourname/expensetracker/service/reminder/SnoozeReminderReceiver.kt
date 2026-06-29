package com.yourname.expensetracker.service.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

/**
 * BroadcastReceiver that handles the "Snooze" action on bill reminder notifications.
 *
 * Instead of performing database mutations directly (which would violate the
 * lease/barrier/run-ledger architecture), this receiver enqueues a one-shot
 * [SnoozeReminderActionWorker] via WorkManager. The worker executes the
 * actual snooze under [com.yourname.expensetracker.domain.workers.WorkerExecutionGuard].
 */
@AndroidEntryPoint
class SnoozeReminderReceiver : BroadcastReceiver() {

    @Inject lateinit var workManager: WorkManager

    override fun onReceive(context: Context, intent: Intent) {
        val deliveryId = intent.getLongExtra("deliveryId", -1L)
        if (deliveryId == -1L) {
            Timber.w("SnoozeReminderReceiver: missing deliveryId extra")
            return
        }

        val request = OneTimeWorkRequestBuilder<SnoozeReminderActionWorker>()
            .setInputData(workDataOf("deliveryId" to deliveryId))
            .build()

        workManager.enqueue(request)
        Timber.d("SnoozeReminderReceiver: enqueued snooze action for delivery %d", deliveryId)
    }
}
