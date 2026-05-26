package com.yourname.expensetracker.domain.reminder

import com.yourname.expensetracker.data.repository.RecurringExpenseRepository
import com.yourname.expensetracker.domain.logic.RecurrenceCalculator
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

data class BillReminder(
    val recurringExpenseId: Long,
    val merchant: String,
    val amount: Double,
    val currency: String,
    val dueDate: Long,
    val daysUntilDue: Int,
    val isOverdue: Boolean,
    val urgency: ReminderUrgency
)

enum class ReminderUrgency {
    INFO,      // Due > 7 days
    WARNING,   // Due in 3-7 days
    URGENT,    // Due in 1-2 days
    CRITICAL   // Due today or overdue
}

/**
 * Manages bill reminders based on recurring expense rules.
 *
 * ## Integration with RecurringLifecycleCoordinator
 *
 * This class's [getNotificationsDue] method filters upcoming reminders by urgency.
 * However, the preferred scheduling path going forward is:
 * 1. Use [RecurringLifecycleCoordinator.getDueReminders] which queries the
 *    `recurring_reminder_deliveries` table for SCHEDULED deliveries whose
 *    `scheduledAt` has passed.
 * 2. A [ReminderDispatchWorker] (WorkManager — to be created in a future PR)
 *    should run periodic checks and call [RecurringLifecycleCoordinator.getDueReminders]
 *    to dispatch notifications.
 *
 * Until that worker exists, [getNotificationsDue] remains active for backward
 * compatibility with the legacy reminder path.
 */
@Singleton
class BillReminderManager @Inject constructor(
    private val recurringExpenseRepository: RecurringExpenseRepository,
    private val timeProvider: TimeProvider,
    private val lifecycleCoordinator: dagger.Lazy<com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator>
) {
    companion object {
        const val DEFAULT_REMINDER_DAYS = 3 // Days before due date

        /**
         * Scheduling note: future reminder scheduling should use
         * [WorkerSpecScheduler] (e.g. [WorkerSpecScheduler.scheduleFromSpec]
         * or [WorkerSpecScheduler.scheduleAtMidnight]) to register
         * recurring [WorkManager] workers for periodic dispatch.
         * The existing [getNotificationsDue] is a pull-based fallback and
         * does NOT self-schedule.
         */
    }
    
    /**
     * Get all upcoming bill reminders.
     */
    suspend fun getUpcomingReminders(daysAhead: Int = 14): List<BillReminder> = withContext(Dispatchers.IO) {
        val now = timeProvider.now()
        val cutoff = TimePeriodUtils.addDays(now, daysAhead)
        
        val recurring = recurringExpenseRepository.getAll()
        val reminders = mutableListOf<BillReminder>()
        
        for (expense in recurring) {
            if (!expense.isActive) continue
            
            val nextDate = expense.nextDate
            if (nextDate > cutoff) continue
            
            val daysUntil = TimePeriodUtils.daysBetween(now, nextDate)
            
            val urgency = when {
                nextDate <= now -> ReminderUrgency.CRITICAL
                daysUntil <= 2 -> ReminderUrgency.URGENT
                daysUntil <= 7 -> ReminderUrgency.WARNING
                else -> ReminderUrgency.INFO
            }
            
            reminders.add(
                BillReminder(
                    recurringExpenseId = expense.id,
                    merchant = expense.merchant,
                    amount = expense.amount,
                    currency = expense.currency,
                    dueDate = nextDate,
                    daysUntilDue = daysUntil,
                    isOverdue = nextDate < now,
                    urgency = urgency
                )
            )
        }
        
        reminders.sortedBy { it.dueDate }
    }
    
    /**
     * Get reminders that need notification now.
     *
     * ## Coordinator path (preferred)
     *
     * Use [com.yourname.expensetracker.domain.recurring.RecurringLifecycleCoordinator.getDueReminders]
     * which queries the `recurring_reminder_deliveries` table for SCHEDULED deliveries whose
     * `scheduledAt` has passed, and dispatch via a [BillReminderWorker] (WorkManager).
     */
    @Deprecated(
        message = "Use RecurringLifecycleCoordinator.getDueReminders() and BillReminderWorker",
        replaceWith = ReplaceWith(
            "RecurringLifecycleCoordinator.getDueReminders()",
            "com.yourname.expensetracker.domain.recurring.RecurringLifecycleCoordinator"
        )
    )
    suspend fun getNotificationsDue(): List<BillReminder> = withContext(Dispatchers.IO) {
        val reminders = getUpcomingReminders(7)
        
        reminders.filter { reminder ->
            when (reminder.urgency) {
                ReminderUrgency.CRITICAL -> true
                ReminderUrgency.URGENT -> reminder.daysUntilDue in 1..2
                ReminderUrgency.WARNING -> reminder.daysUntilDue == DEFAULT_REMINDER_DAYS
                ReminderUrgency.INFO -> false
            }
        }
    }
    
    /**
     * Mark a bill as paid and update next due date.
     *
     * ## REC-3: Pay-through-today for overdue bills
     * If the computed next due date is still in the past (overdue), the date is
     * advanced one interval at a time until it reaches today. This mirrors the
     * `rollNextExpectedDateForward()` pattern in [RecurringExpenseEngine].
     * IRREGULAR frequency is skipped because it has no predictable interval.
     *
     * @deprecated Use [markRuleBillAsPaid] which delegates to the lifecycle coordinator
     *   for proper occurrence/reminder/planned-expense management.
     */
    @Deprecated(
        message = "Use markRuleBillAsPaid(recurringExpenseId) for full lifecycle tracking",
        level = DeprecationLevel.WARNING
    )
    suspend fun markBillPaid(recurringExpenseId: Long) {
        lifecycleCoordinator.get().markRuleBillAsPaid(recurringExpenseId)
    }

    /**
     * Marks a recurring bill as paid via the lifecycle coordinator.
     * Finds the current PLANNED occurrence, marks it PAID, suppresses reminders,
     * fulfills the planned expense, and advances the next due date.
     *
     * This is the replacement for the legacy [markBillPaid] path.
     */
    suspend fun markRuleBillAsPaid(recurringExpenseId: Long) {
        lifecycleCoordinator.get().markRuleBillAsPaid(recurringExpenseId)
    }
    
    /**
     * Get total expected bills for current month.
     */
    suspend fun getMonthlyBillsTotal(): Double = withContext(Dispatchers.IO) {
        val recurring = recurringExpenseRepository.getAll()
        var total = 0.0
        
        for (expense in recurring) {
            if (!expense.isActive) continue

            total += RecurrenceCalculator.toMonthlyAmount(expense.amount, expense.frequency)
        }

        total
    }
}
