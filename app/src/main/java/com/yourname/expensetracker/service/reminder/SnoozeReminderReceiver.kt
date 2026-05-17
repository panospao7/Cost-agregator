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
 * BroadcastReceiver that snoozes a reminder delivery for 24 hours.
 *
 * Triggered by the "Snooze" action button on a bill reminder notification.
 * Updates the delivery's status to "SNOOZED" and sets [snoozedUntil] to
 * 24 hours from now, so the next worker cycle will skip it.
 *
 * TODO (P4-PR4): This receiver currently writes directly to the DAO. It should
 * instead delegate to [RecurringLifecycleCoordinator.snoozeReminder] so that
 * all lifecycle rules (PlannedExpense re-opening, reminder regeneration, event
 * audit) are enforced consistently. The direct DAO writes are retained for now
 * to preserve backward-compatible semantics.
 */
@AndroidEntryPoint
class SnoozeReminderReceiver : BroadcastReceiver() {

    @Inject lateinit var reminderDeliveryDao: RecurringReminderDeliveryDao
    @Inject lateinit var timeProvider: TimeProvider
    @Inject lateinit var writeBarrier: DatabaseWriteBarrier
    @Inject lateinit var lifecycleEventDao: RecurringLifecycleEventDao

    override fun onReceive(context: Context, intent: Intent) {
        val deliveryId = intent.getLongExtra("deliveryId", -1L)
        if (deliveryId == -1L) {
            Timber.w("SnoozeReminderReceiver: missing deliveryId extra")
            return
        }

        try {
            writeBarrier.checkWritesAllowed("SnoozeReminderReceiver")
        } catch (e: Exception) {
            Timber.w("SnoozeReminderReceiver: writes blocked — %s", e.message)
            return
        }

        Timber.d("Snoozing reminder delivery %d for 24h", deliveryId)

        runBlocking(Dispatchers.IO) {
            try {
                val delivery = reminderDeliveryDao.getById(deliveryId)
                if (delivery == null) {
                    Timber.w("SnoozeReminderReceiver: delivery %d not found", deliveryId)
                    return@runBlocking
                }

                val now = timeProvider.now()
                val snoozedUntil = now + 24L * 60L * 60L * 1000L

                reminderDeliveryDao.update(
                    delivery.copy(
                        status = "SNOOZED",
                        snoozedUntil = snoozedUntil
                    )
                )

                // Write lifecycle event
                try {
                    lifecycleEventDao.insert(
                        RecurringLifecycleEvent(
                            occurrenceId = delivery.occurrenceId,
                            eventType = "REMINDER_SNOOZED",
                            occurredAt = now,
                            oldStatus = null,
                            newStatus = null,
                            metadata = """{"deliveryId":$deliveryId,"snoozedUntil":$snoozedUntil}"""
                        )
                    )
                } catch (e: Exception) {
                    Timber.w(e, "Non-critical: failed to write REMINDER_SNOOZED event")
                }

                Timber.d("Reminder delivery %d snoozed until %d", deliveryId, snoozedUntil)
            } catch (e: Exception) {
                Timber.e(e, "Failed to snooze reminder delivery %d", deliveryId)
            }
        }
    }
}
