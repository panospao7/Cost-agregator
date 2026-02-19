package com.yourname.expensetracker.domain.budget

import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.domain.service.NotificationService
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BudgetMonitor @Inject constructor(
    private val budgetRepository: BudgetRepository,
    private val timeProvider: TimeProvider,
    private val notificationService: NotificationService,
    @com.yourname.expensetracker.di.IoDispatcher private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher
) {
    private val serviceScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    companion object {
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 1000L
    }

    fun checkBudgets() {
        serviceScope.launch {
            var lastException: Exception? = null
            repeat(MAX_RETRIES) { attempt ->
                try {
                    val budgetStatuses = budgetRepository.getBudgetStatuses().first()
                    if (budgetStatuses.isEmpty()) return@launch

                    val now = timeProvider.now()

                    for (status in budgetStatuses) {
                        processBudgetStatus(status, now)
                    }
                    return@launch // Success - exit
                } catch (e: Exception) {
                    lastException = e
                    Timber.w(e, "checkBudgets attempt ${attempt + 1} failed")
                    if (attempt < MAX_RETRIES - 1) {
                        kotlinx.coroutines.delay(RETRY_DELAY_MS)
                    }
                }
            }
            Timber.e(lastException, "checkBudgets failed after $MAX_RETRIES attempts")
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
                    sendNotification(budget.id.toInt(), budget, spent, "Budget Exceeded!", categoryName)
                    budgetRepository.updateExceededNotification(budget.id, now)
                }
            }
            percent >= budget.notifyAtCritical && percent < 1.0f -> {
                if (shouldNotify(budget.lastCriticalNotifiedAt, now, periodStart)) {
                    sendNotification(budget.id.toInt(), budget, spent, "Critical Budget Warning", categoryName)
                    budgetRepository.updateCriticalNotification(budget.id, now)
                }
            }
            percent >= budget.notifyAtWarning && percent < budget.notifyAtCritical -> {
                if (shouldNotify(budget.lastWarningNotifiedAt, now, periodStart)) {
                    sendNotification(budget.id.toInt(), budget, spent, "Budget Warning", categoryName)
                    budgetRepository.updateWarningNotification(budget.id, now)
                }
            }
        }
    }

    private fun shouldNotify(lastNotified: Long?, now: Long, periodStart: Long): Boolean {
        if (lastNotified == null) return true
        
        if (lastNotified < periodStart) return true
        
        val cooldown = 12 * 60 * 60 * 1000L
        return now - lastNotified > cooldown
    }

    private fun sendNotification(
        notificationId: Int,
        budget: Budget,
        spent: Double,
        title: String,
        categoryName: String
    ) {
        val percent = (spent / budget.amount * 100).toInt()
        val content = String.format(
            Locale.US,
            "You've spent €%.2f (%d%%) of your %s budget.",
            spent,
            percent,
            categoryName
        )

        notificationService.sendBudgetAlert(notificationId, title, content)
    }
}
