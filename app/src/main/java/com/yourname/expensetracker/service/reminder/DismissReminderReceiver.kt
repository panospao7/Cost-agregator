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
 * BroadcastReceiver that handles the "Dismiss" action on bill reminder notifications.
 *
 * Instead of performing database mutations directly (which would violate the
 * lease/barrier/run-ledger architecture), this receiver enqueues a one-shot
 * [DismissReminderActionWorker] via WorkManager. The worker executes the
 * actual dismissal under [com.yourname.expensetracker.domain.workers.WorkerExecutionGuard].
 */
@AndroidEntryPoint
class DismissReminderReceiver : BroadcastReceiver() {

    @Inject lateinit var workManager: WorkManager

    override fun onReceive(context: Context, intent: Intent) {
        val deliveryId = intent.getLongExtra("deliveryId", -1L)
        if (deliveryId == -1L) {
            Timber.w("DismissReminderReceiver: missing deliveryId extra")
            return
        }

        val request = OneTimeWorkRequestBuilder<DismissReminderActionWorker>()
            .setInputData(workDataOf("deliveryId" to deliveryId))
            .build()

        workManager.enqueue(request)
        Timber.d("DismissReminderReceiver: enqueued dismiss action for delivery %d", deliveryId)
    }
}
