package com.yourname.expensetracker.service.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yourname.expensetracker.data.database.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import timber.log.Timber

/**
 * BroadcastReceiver that dismisses a reminder delivery.
 *
 * Triggered by the "Dismiss" action button on a bill reminder notification.
 * Updates the delivery's status to "DISMISSED" and records the dismissal
 * timestamp so the reminder is not shown again.
 */
class DismissReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val deliveryId = intent.getLongExtra("deliveryId", -1L)
        if (deliveryId == -1L) {
            Timber.w("DismissReminderReceiver: missing deliveryId extra")
            return
        }

        Timber.d("Dismissing reminder delivery %d", deliveryId)

        runBlocking(Dispatchers.IO) {
            try {
                val db = AppDatabase.fileBuilder(context).build()
                try {
                    val delivery = db.recurringReminderDeliveryDao().getById(deliveryId)
                    if (delivery == null) {
                        Timber.w("DismissReminderReceiver: delivery %d not found", deliveryId)
                        return@runBlocking
                    }

                    val now = System.currentTimeMillis()

                    db.recurringReminderDeliveryDao().update(
                        delivery.copy(
                            status = "DISMISSED",
                            dismissedAt = now
                        )
                    )

                    Timber.d("Reminder delivery %d dismissed at %d", deliveryId, now)
                } finally {
                    db.close()
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to dismiss reminder delivery %d", deliveryId)
            }
        }
    }
}
