package com.yourname.expensetracker.domain.budget

import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.domain.service.NotificationService
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BudgetMonitor @Inject constructor(
    private val budgetRepository: BudgetRepository,
    private val timeProvider: TimeProvider,
    private val notificationService: NotificationService,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + ioDispatcher)

    // Single synchronization owner for all shared monitor state.
    // lastCheckTime, cachedStatuses, and cacheTimestamp must always be
    // observed and mutated together so throttle decisions and cache
    // freshness checks are never inconsistent.
    private val stateLock = Any()
    private var lastCheckTime = 0L
    private var cachedStatuses: List<BudgetStatus>? = null
    private var cacheTimestamp: Long = 0L
    private val cacheValidityMs = 30_000L // 30 seconds cache

    /**
     * Non-destructive lifecycle callback for routine app backgrounding.
     *
     * Cancels any in-flight monitor work and clears transient throttle/cache state,
     * but keeps the parent scope alive so future foreground checks can still run.
     */
    fun onBackground() {
        serviceJob.cancelChildren()
        synchronized(stateLock) {
            lastCheckTime = 0L
            cachedStatuses = null
            cacheTimestamp = 0L
        }
    }

    /**
     * Permanently cancels this monitor's coroutine scope.
     *
     * After this call, no further checks can be launched.
     * Use only for true disposal (for example in tests/process teardown).
     */
    fun destroy() {
        onBackground()
        serviceJob.cancel()
    }

    @Deprecated(
        message = "Use onBackground() for routine backgrounding or destroy() for permanent disposal",
        replaceWith = ReplaceWith("onBackground()")
    )
    fun cleanup() {
        destroy()
    }

    companion object {
        private const val MIN_CHECK_INTERVAL_MS = 60_000L // 1 minute
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 1000L
        
        // Period-aware notification cooldowns
        private const val DAILY_COOLDOWN_MS = 6 * 60 * 60 * 1000L      // 6 hours
        private const val WEEKLY_COOLDOWN_MS = 24 * 60 * 60 * 1000L    // 24 hours
        private const val MONTHLY_COOLDOWN_MS = 48 * 60 * 60 * 1000L   // 48 hours
        private const val YEARLY_COOLDOWN_MS = 72 * 60 * 60 * 1000L    // 72 hours
        
        private fun getCooldownForPeriod(period: BudgetPeriod): Long {
            return when (period) {
                BudgetPeriod.DAILY -> DAILY_COOLDOWN_MS
                BudgetPeriod.WEEKLY -> WEEKLY_COOLDOWN_MS
                BudgetPeriod.MONTHLY -> MONTHLY_COOLDOWN_MS
                BudgetPeriod.YEARLY -> YEARLY_COOLDOWN_MS
            }
        }
    }

    fun checkBudgets() {
        val now = timeProvider.now()
        synchronized(stateLock) {
            if (now - lastCheckTime < MIN_CHECK_INTERVAL_MS) {
                Timber.d("Budget check skipped - too soon (last check: ${now - lastCheckTime}ms ago)")
                return
            }
            lastCheckTime = now
        }
        
        serviceScope.launch {
            var lastException: Exception? = null
            repeat(MAX_RETRIES) { attempt ->
                try {
                    val budgetStatuses = getCachedBudgetStatuses(now)
                    if (budgetStatuses.isEmpty()) return@launch

                    for (status in budgetStatuses) {
                        processBudgetStatus(status, now)
                    }
                    return@launch // Success - exit
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastException = e
                    if (isTransientError(e)) {
                        Timber.w(e, "checkBudgets transient error, attempt ${attempt + 1}")
                        if (attempt < MAX_RETRIES - 1) {
                            kotlinx.coroutines.delay(RETRY_DELAY_MS)
                        }
                    } else {
                        Timber.e(e, "checkBudgets non-transient error, not retrying")
                        return@launch
                    }
                }
            }
            Timber.e(lastException, "checkBudgets failed after $MAX_RETRIES attempts")
        }
    }

    private fun isTransientError(e: Exception): Boolean {
        return when (e) {
            is IOException -> true
            is SocketTimeoutException -> true
            is CancellationException -> false
            else -> e.message?.contains("timeout") == true || 
                   e.message?.contains("network") == true ||
                   e.message?.contains("database") == true
        }
    }

    private suspend fun getCachedBudgetStatuses(now: Long): List<BudgetStatus> {
        synchronized(stateLock) {
            val cached = cachedStatuses
            if (cached != null && now - cacheTimestamp < cacheValidityMs) {
                Timber.d("Using cached budget statuses (${cached.size} budgets)")
                return cached
            }
        }

        val statuses = budgetRepository.getBudgetStatuses().first()
        currentCoroutineContext().ensureActive()
        synchronized(stateLock) {
            cachedStatuses = statuses
            cacheTimestamp = now
        }
        Timber.d("Fetched fresh budget statuses (${statuses.size} budgets)")
        return statuses
    }

    private suspend fun processBudgetStatus(
        status: BudgetStatus, 
        now: Long
    ) {
        currentCoroutineContext().ensureActive()
        val budget = status.budget
        val spent = status.spentAmount
        val categoryName = status.category?.name ?: "Overall"
        val periodStart = status.periodStart

        if (spent <= 0 || budget.amount <= 0) return

        val percent = status.percentUsed

        when {
            percent >= 1.0f -> {
                if (shouldNotify(budget.lastExceededNotifiedAt, now, periodStart, budget.period)) {
                    sendNotification(budget.id.toInt(), budget, spent, "Budget Exceeded!", categoryName)
                    budgetRepository.updateExceededNotification(budget.id, now)
                }
            }
            percent >= budget.notifyAtCritical && percent < 1.0f -> {
                if (shouldNotify(budget.lastCriticalNotifiedAt, now, periodStart, budget.period)) {
                    sendNotification(budget.id.toInt(), budget, spent, "Critical Budget Warning", categoryName)
                    budgetRepository.updateCriticalNotification(budget.id, now)
                }
            }
            percent >= budget.notifyAtWarning && percent < budget.notifyAtCritical -> {
                if (shouldNotify(budget.lastWarningNotifiedAt, now, periodStart, budget.period)) {
                    sendNotification(budget.id.toInt(), budget, spent, "Budget Warning", categoryName)
                    budgetRepository.updateWarningNotification(budget.id, now)
                }
            }
        }
    }

    private fun shouldNotify(lastNotified: Long?, now: Long, periodStart: Long, period: BudgetPeriod): Boolean {
        if (lastNotified == null) return true
        
        if (lastNotified < periodStart) return true
        
        val cooldown = getCooldownForPeriod(period)
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
        val currencySymbol = com.yourname.expensetracker.domain.currency.SupportedCurrency
            .fromCode(budget.currency)?.symbol ?: budget.currency
        val content = String.format(
            Locale.US,
            "You've spent %s%.2f (%d%%) of your %s budget.",
            currencySymbol,
            spent,
            percent,
            categoryName
        )

        notificationService.sendBudgetAlert(notificationId, title, content)
    }
}
