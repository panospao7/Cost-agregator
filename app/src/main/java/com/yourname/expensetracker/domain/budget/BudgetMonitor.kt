package com.yourname.expensetracker.domain.budget

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.yourname.expensetracker.data.database.dao.BudgetDao
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.domain.util.TimeProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BudgetMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val budgetDao: BudgetDao,
    private val budgetRepository: BudgetRepository,
    private val timeProvider: TimeProvider,
    @com.yourname.expensetracker.di.IoDispatcher private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher
) {
    private val serviceScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun checkBudgets() {
        serviceScope.launch {
            try {
                // Use Repository to get calculated statuses (includes rollover logic)
                val budgetStatuses = budgetRepository.getBudgetStatuses().first()
                if (budgetStatuses.isEmpty()) return@launch

                val now = timeProvider.now()

                for (status in budgetStatuses) {
                    processBudgetStatus(status, now)
                }
            } catch (e: Exception) {
                android.util.Log.e("BudgetMonitor", "Error in checkBudgets: ${e.message}", e)
            }
        }
    }

    private suspend fun processBudgetStatus(
        status: BudgetStatus, 
        now: Long
    ) {
        val budget = status.budget
        val spent = status.spentAmount
        val categoryName = status.category?.name ?: "Overall"
        val periodStart = status.periodStart

        if (spent <= 0 || budget.amount <= 0) return

        val percent = status.percentUsed

        when {
            percent >= 1.0f -> {
                if (shouldNotify(budget.lastExceededNotifiedAt, now, periodStart)) {
                    sendNotificationDirect(budget, spent, "Budget Exceeded!", categoryName)
                    budgetDao.updateExceededNotification(budget.id, now)
                }
            }
            percent >= budget.notifyAtCritical -> {
                if (shouldNotify(budget.lastCriticalNotifiedAt, now, periodStart)) {
                    sendNotificationDirect(budget, spent, "Critical Budget Warning", categoryName)
                    budgetDao.updateCriticalNotification(budget.id, now)
                }
            }
            percent >= budget.notifyAtWarning -> {
                if (shouldNotify(budget.lastWarningNotifiedAt, now, periodStart)) {
                    sendNotificationDirect(budget, spent, "Budget Warning", categoryName)
                    budgetDao.updateWarningNotification(budget.id, now)
                }
            }
        }
    }

    private fun shouldNotify(lastNotified: Long?, now: Long, periodStart: Long): Boolean {
        if (lastNotified == null) return true
        
        // Reset cooldown if we entered a new period (BUG-7 Fix)
        if (lastNotified < periodStart) return true
        
        // Cooldown: only notify once every 12 hours for the same budget level
        val cooldown = 12 * 60 * 60 * 1000L
        return now - lastNotified > cooldown
    }

    private fun sendNotificationDirect(budget: Budget, spent: Double, title: String, categoryName: String) {
        val percent = (spent / budget.amount * 100).toInt()
        val content = "You've spent €${String.format(java.util.Locale.US, "%.2f", spent)} ($percent%) of your $categoryName budget."

        val builder = NotificationCompat.Builder(context, "budget_alerts")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        notificationManager.notify(budget.id.toInt(), builder.build())
    }
}
