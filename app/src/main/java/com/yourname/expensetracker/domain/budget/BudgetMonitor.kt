package com.yourname.expensetracker.domain.budget

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.yourname.expensetracker.data.database.dao.BudgetDao
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BudgetMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val budgetDao: BudgetDao,
    private val expenseDao: ExpenseDao,
    private val categoryDao: CategoryDao
) {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun checkBudgets() {
        serviceScope.launch {
            val activeBudgets = budgetDao.getActiveBudgets()
            for (budget in activeBudgets) {
                try {
                    processBudget(budget)
                } catch (e: Exception) {
                    android.util.Log.e("BudgetMonitor", "Error processing budget ${budget.id}: ${e.message}", e)
                }
            }
        }
    }

    private suspend fun processBudget(budget: Budget) {
        val window = calculatePeriodWindow(budget.period, budget.startDate)
        val spent = if (budget.categoryId != null) {
            expenseDao.getCategorySpentInPeriod(budget.categoryId, window.first, window.second)
        } else {
            expenseDao.getTotalForPeriod(window.first, window.second)
        }

        if (spent <= 0 || budget.amount <= 0) return

        val percent = (spent / budget.amount).toFloat()
        val now = System.currentTimeMillis()

        when {
            percent >= 1.0f -> {
                if (shouldNotify(budget.lastExceededNotifiedAt, now)) {
                    sendNotification(budget, spent, "Budget Exceeded!")
                    budgetDao.updateExceededNotification(budget.id, now)
                }
            }
            percent >= budget.notifyAtCritical -> {
                if (shouldNotify(budget.lastCriticalNotifiedAt, now)) {
                    sendNotification(budget, spent, "Critical Budget Warning")
                    budgetDao.updateCriticalNotification(budget.id, now)
                }
            }
            percent >= budget.notifyAtWarning -> {
                if (shouldNotify(budget.lastWarningNotifiedAt, now)) {
                    sendNotification(budget, spent, "Budget Warning")
                    budgetDao.updateWarningNotification(budget.id, now)
                }
            }
        }
    }

    private fun shouldNotify(lastNotified: Long?, now: Long): Boolean {
        if (lastNotified == null) return true
        // Cooldown: only notify once every 12 hours for the same budget level
        val cooldown = 12 * 60 * 60 * 1000L
        return now - lastNotified > cooldown
    }

    private fun sendNotification(budget: Budget, spent: Double, title: String) {
        val percent = (spent / budget.amount * 100).toInt()
        
        serviceScope.launch {
            val categoryName = if (budget.categoryId != null) {
                categoryDao.getById(budget.categoryId)?.name ?: "Category"
            } else {
                "Overall"
            }
            
            val content = "You've spent €${"%.2f".format(spent)} ($percent%) of your $categoryName budget."

            val builder = NotificationCompat.Builder(context, "budget_alerts")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(content)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)

            notificationManager.notify(budget.id.toInt(), builder.build())
        }
    }

    fun calculatePeriodWindow(period: BudgetPeriod, anchorDate: Long): Pair<Long, Long> {
        return calculatePeriodWindowForTime(period, anchorDate, System.currentTimeMillis())
    }

    fun getPreviousPeriodWindow(period: BudgetPeriod, anchorDate: Long): Pair<Long, Long> {
        val currentWindow = calculatePeriodWindow(period, anchorDate)
        // To get previous, we can just subtract a small amount from the start of current and recalculate
        // This is safer than date math which might miss (e.g. variable month lengths)
        // If current start is Nov 1. Nov 1 - 1ms = Oct 31.
        // Calculate window for Oct 31. It will be Oct 1 - Nov 1.
        return calculatePeriodWindowForTime(period, anchorDate, currentWindow.first - 1000)
    }

    private fun calculatePeriodWindowForTime(period: BudgetPeriod, anchorDate: Long, evaluationTime: Long): Pair<Long, Long> {
        val anchorCal = Calendar.getInstance()
        anchorCal.timeInMillis = anchorDate

        val cal = Calendar.getInstance()
        cal.timeInMillis = evaluationTime

        // Reset time components to start of day
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        return when (period) {
            BudgetPeriod.DAILY -> {
                val start = cal.timeInMillis
                cal.add(Calendar.DAY_OF_YEAR, 1)
                Pair(start, cal.timeInMillis)
            }
            BudgetPeriod.WEEKLY -> {
                // Find the most recent occurrence of the anchor weekday
                val anchorDayOfWeek = anchorCal.get(Calendar.DAY_OF_WEEK)
                while (cal.get(Calendar.DAY_OF_WEEK) != anchorDayOfWeek) {
                    cal.add(Calendar.DAY_OF_YEAR, -1)
                }
                val start = cal.timeInMillis
                cal.add(Calendar.WEEK_OF_YEAR, 1)
                Pair(start, cal.timeInMillis)
            }
            BudgetPeriod.MONTHLY -> {
                val anchorDay = anchorCal.get(Calendar.DAY_OF_MONTH)
                
                // Set to start of current month first
                cal.set(Calendar.DAY_OF_MONTH, 1)
                val currentMonthMax = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                cal.set(Calendar.DAY_OF_MONTH, anchorDay.coerceAtMost(currentMonthMax))
                
                if (evaluationTime < cal.timeInMillis) {
                    // If evaluation time is before the start of this month's cycle, the cycle started last month
                    cal.add(Calendar.MONTH, -1)
                    val prevMonthMax = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                    cal.set(Calendar.DAY_OF_MONTH, anchorDay.coerceAtMost(prevMonthMax))
                }

                val start = cal.timeInMillis
                
                // To find the end, go to the start of the next cycle
                cal.add(Calendar.MONTH, 1)
                val nextMonthMax = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                cal.set(Calendar.DAY_OF_MONTH, anchorDay.coerceAtMost(nextMonthMax))
                
                val end = cal.timeInMillis
                Pair(start, end)
            }
            BudgetPeriod.YEARLY -> {
                val anchorMonth = anchorCal.get(Calendar.MONTH)
                val anchorDay = anchorCal.get(Calendar.DAY_OF_MONTH)
                
                val currentMonth = cal.get(Calendar.MONTH)
                val currentDay = cal.get(Calendar.DAY_OF_MONTH)

                // Check if we passed the anniversary this year
                var passed = false
                if (currentMonth > anchorMonth) passed = true
                else if (currentMonth == anchorMonth && currentDay >= anchorDay) passed = true
                
                if (!passed) {
                    cal.add(Calendar.YEAR, -1)
                }
                
                cal.set(Calendar.MONTH, anchorMonth)
                cal.set(Calendar.DAY_OF_MONTH, anchorDay.coerceAtMost(cal.getActualMaximum(Calendar.DAY_OF_MONTH)))
                
                val start = cal.timeInMillis
                cal.add(Calendar.YEAR, 1)
                val end = cal.timeInMillis
                Pair(start, end)
            }
        }
    }
}
