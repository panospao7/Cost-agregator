package com.yourname.expensetracker.service.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.dao.RecurringLifecycleEventDao
import com.yourname.expensetracker.data.database.dao.RecurringReminderDeliveryDao
import com.yourname.expensetracker.data.database.entity.RecurringLifecycleEvent
import com.yourname.expensetracker.domain.util.TimeProvider
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import javax.inject.Inject

/**
 * BroadcastReceiver that dismisses a reminder delivery.
 *
 * Triggered by the "Dismiss" action button on a bill reminder notification.
 * Updates the delivery's status to "DISMISSED" and records the dismissal
 * timestamp so the reminder is not shown again.
 *
 * TODO (P4-PR4): This receiver currently writes directly to the DAO. It should
 * instead delegate to [RecurringLifecycleCoordinator.dismissReminder] so that
 * all lifecycle rules (PlannedExpense re-evaluation, event audit) are enforced
 * consistently. The direct DAO writes are retained for now to preserve
 * backward-compatible semantics.
 */
@AndroidEntryPoint
class DismissReminderReceiver : BroadcastReceiver() {

    @Inject lateinit var reminderDeliveryDao: RecurringReminderDeliveryDao
    @Inject lateinit var timeProvider: TimeProvider
    @Inject lateinit var writeBarrier: DatabaseWriteBarrier
    @Inject lateinit var lifecycleEventDao: RecurringLifecycleEventDao

    override fun onReceive(context: Context, intent: Intent) {
        val deliveryId = intent.getLongExtra("deliveryId", -1L)
        if (deliveryId == -1L) {
            Timber.w("DismissReminderReceiver: missing deliveryId extra")
            return
        }

        try {
            writeBarrier.checkWritesAllowed("DismissReminderReceiver")
        } catch (e: Exception) {
            Timber.w("DismissReminderReceiver: writes blocked — %s", e.message)
            return
        }

        Timber.d("Dismissing reminder delivery %d", deliveryId)

        runBlocking(Dispatchers.IO) {
            try {
                val delivery = reminderDeliveryDao.getById(deliveryId)
                if (delivery == null) {
                    Timber.w("DismissReminderReceiver: delivery %d not found", deliveryId)
                    return@runBlocking
                }

                val now = timeProvider.now()

                reminderDeliveryDao.update(
                    delivery.copy(
                        status = "DISMISSED",
                        dismissedAt = now
                    )
                )

                // Write lifecycle event
                try {
                    lifecycleEventDao.insert(
                        RecurringLifecycleEvent(
                            occurrenceId = delivery.occurrenceId,
                            eventType = "REMINDER_DISMISSED",
                            occurredAt = now,
                            oldStatus = null,
                            newStatus = null,
                            metadata = """{"deliveryId":$deliveryId}"""
                        )
                    )
                } catch (e: Exception) {
                    Timber.w(e, "Non-critical: failed to write REMINDER_DISMISSED event")
                }

                Timber.d("Reminder delivery %d dismissed at %d", deliveryId, now)
            } catch (e: Exception) {
                Timber.e(e, "Failed to dismiss reminder delivery %d", deliveryId)
            }
        }
    }
}
