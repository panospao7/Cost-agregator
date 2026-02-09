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
                processBudget(budget)
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

        if (spent <= 0) return

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
        val cal = Calendar.getInstance()
        val now = System.currentTimeMillis()
        cal.timeInMillis = now

        // Set to start of current day
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
                // Set to current week's Monday
                cal.firstDayOfWeek = Calendar.MONDAY
                cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                val start = cal.timeInMillis
                cal.add(Calendar.WEEK_OF_YEAR, 1)
                Pair(start, cal.timeInMillis)
            }
            BudgetPeriod.MONTHLY -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                val start = cal.timeInMillis
                cal.add(Calendar.MONTH, 1)
                Pair(start, cal.timeInMillis)
            }
            BudgetPeriod.YEARLY -> {
                cal.set(Calendar.DAY_OF_YEAR, 1)
                val start = cal.timeInMillis
                cal.add(Calendar.YEAR, 1)
                Pair(start, cal.timeInMillis)
            }
        }
    }
}
