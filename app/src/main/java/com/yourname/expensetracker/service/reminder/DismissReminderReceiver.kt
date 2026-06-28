package com.yourname.expensetracker.service.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class DismissReminderReceiver : BroadcastReceiver() {

    @Inject lateinit var coordinator: RecurringLifecycleCoordinator

    override fun onReceive(context: Context, intent: Intent) {
        val deliveryId = intent.getLongExtra("deliveryId", -1L)
        if (deliveryId == -1L) {
            Timber.w("DismissReminderReceiver: missing deliveryId extra")
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                coordinator.dismissReminderDelivery(deliveryId)
                Timber.d("DismissReminderReceiver: delivery %d dismissed", deliveryId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "DismissReminderReceiver: failed to dismiss delivery %d", deliveryId)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
