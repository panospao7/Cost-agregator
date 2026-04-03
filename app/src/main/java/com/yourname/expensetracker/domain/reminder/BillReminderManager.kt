package com.yourname.expensetracker.domain.reminder

import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.data.repository.RecurringExpenseRepository
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Calendar
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

@Singleton
class BillReminderManager @Inject constructor(
    private val recurringExpenseRepository: RecurringExpenseRepository,
    private val timeProvider: TimeProvider
) {
    companion object {
        const val DEFAULT_REMINDER_DAYS = 3 // Days before due date
    }
    
    /**
     * Get all upcoming bill reminders.
     */
    suspend fun getUpcomingReminders(daysAhead: Int = 14): List<BillReminder> = withContext(Dispatchers.IO) {
        val now = timeProvider.now()
        val cutoff = now + (daysAhead * TimePeriodUtils.DAY_IN_MILLIS)
        
        val recurring = recurringExpenseRepository.getAll()
        val reminders = mutableListOf<BillReminder>()
        
        for (expense in recurring) {
            if (!expense.isActive) continue
            
            val nextDate = expense.nextDate
            if (nextDate > cutoff) continue
            
            val daysUntil = TimePeriodUtils.daysBetween(now, nextDate)
            
            val urgency = when {
                nextDate < now -> ReminderUrgency.CRITICAL
                daysUntil <= 1 -> ReminderUrgency.URGENT
                daysUntil <= 3 -> ReminderUrgency.WARNING
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
     */
    suspend fun getNotificationsDue(): List<BillReminder> = withContext(Dispatchers.IO) {
        val reminders = getUpcomingReminders(7)
        
        reminders.filter { reminder ->
            when (reminder.urgency) {
                ReminderUrgency.CRITICAL -> true
                ReminderUrgency.URGENT -> reminder.daysUntilDue in 0..1
                ReminderUrgency.WARNING -> reminder.daysUntilDue == DEFAULT_REMINDER_DAYS
                ReminderUrgency.INFO -> false
            }
        }
    }
    
    /**
     * Mark a bill as paid and update next due date.
     */
    suspend fun markBillPaid(recurringExpenseId: Long) = withContext(Dispatchers.IO) {
        val expense = recurringExpenseRepository.getById(recurringExpenseId) ?: return@withContext
        
        // Calculate next occurrence based on frequency
        val nextDate = calculateNextDate(expense.nextDate, expense.frequency.name)
        
        val updated = expense.copy(nextDate = nextDate)
        recurringExpenseRepository.update(updated)
        
        Timber.d("Marked bill paid for ${expense.merchant}, next due: $nextDate")
    }
    
    /**
     * Get total expected bills for current month.
     */
    suspend fun getMonthlyBillsTotal(): Double = withContext(Dispatchers.IO) {
        val recurring = recurringExpenseRepository.getAll()
        var total = 0.0
        
        for (expense in recurring) {
            if (!expense.isActive) continue
            
            // Convert to monthly equivalent
            val monthlyAmount = when (expense.frequency.name) {
                "WEEKLY" -> expense.amount * 4.33
                "BIWEEKLY" -> expense.amount * 2.17
                "MONTHLY" -> expense.amount
                "QUARTERLY" -> expense.amount / 3
                "YEARLY" -> expense.amount / 12
                else -> expense.amount
            }
            
            total += monthlyAmount
        }
        
        total
    }
    
    private fun calculateNextDate(currentDate: Long, frequency: String): Long {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = currentDate
        
        when (frequency) {
            "WEEKLY" -> return TimePeriodUtils.addDays(currentDate, 7)
            "BIWEEKLY" -> return TimePeriodUtils.addDays(currentDate, 14)
            "MONTHLY" -> calendar.add(Calendar.MONTH, 1)
            "QUARTERLY" -> calendar.add(Calendar.MONTH, 3)
            "YEARLY" -> calendar.add(Calendar.YEAR, 1)
            else -> calendar.add(Calendar.MONTH, 1)
        }
        
        return calendar.timeInMillis
    }
}
