package com.yourname.expensetracker.domain.budget

import com.yourname.expensetracker.data.database.dao.PipelineDiagnosticEventDao
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.PipelineDiagnosticEvent
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.domain.service.NotificationService
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    /** P2-20: Durable diagnostic ledger for budget alert decisions. */
    private val diagnosticEventDao: PipelineDiagnosticEventDao
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
    /** BUD-16: Reduced from 30s to near-zero to minimize stale-alert risk.
     *  The monitor now re-fetches on every call, but still holds the cache
     *  briefly within the same clock-tick to avoid redundant DB queries
     *  from rapid successive calls in the same frame. */
    private val cacheValidityMs = 100L // 100ms — effectively forces re-fetch each check

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

    /**
     * BUD-16: Change-driven cache invalidation. Call this from the repository
     * whenever a budget is created, updated, or deleted so the next checkBudgets()
     * call fetches fresh data instead of serving stale cached statuses.
     */
    fun invalidateCache() {
        synchronized(stateLock) {
            cachedStatuses = null
            cacheTimestamp = 0L
            Timber.d("BudgetMonitor: cache invalidated by external change")
        }
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
            // P2-20: Durable record of failed budget check
            serviceScope.launch(Dispatchers.IO) {
                try {
                    diagnosticEventDao.insert(
                        PipelineDiagnosticEvent(
                            pipeline = "budget_monitor",
                            stage = "CHECK_FAILED",
                            outcome = "ERROR",
                            exceptionClass = lastException?.javaClass?.name,
                            exceptionMessage = lastException?.message,
                            timestamp = timeProvider.now()
                        )
                    )
                } catch (e: Exception) {
                    Timber.w(e, "Failed to write CHECK_FAILED diagnostic event")
                }
            }
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

        // P2-20: Durable diagnostic — record that a budget check started.
        withContext(Dispatchers.IO) {
            diagnosticEventDao.insert(
                PipelineDiagnosticEvent(
                    pipeline = "budget_monitor",
                    stage = "CHECK_STARTED",
                    outcome = "OK",
                    entityType = "Budget",
                    entityId = budget.id,
                    timestamp = now
                )
            )
        }

        // P6-P1-2: Use adjustedSpendBreakdown.effectiveSpend when available (shared-expense offset),
        // falling back to raw spentAmount.BudgetStatus.spentAmount is gross spend and does not
        // account for shared-expense reimbursements, which can trigger false threshold alerts.
        val spent = status.adjustedSpendBreakdown?.effectiveSpend ?: status.spentAmount
        val categoryName = status.category?.name ?: "Overall"
        val periodStart = status.periodStart

        if (spent <= 0 || budget.amount <= 0) return

        // Recompute percent from adjusted spent to avoid triggering alerts on gross spend
        val effectiveLimit = status.effectiveLimit
        val adjustedPercent = if (effectiveLimit > 0) (spent / effectiveLimit).toFloat() else 0f

        // P2-20: Write a diagnostic event recording the budget check attempt.
        // Writes on Dispatchers.IO to avoid blocking the monitor coroutine.
        withContext(Dispatchers.IO) {
            diagnosticEventDao.insert(
                PipelineDiagnosticEvent(
                    pipeline = "budget_monitor",
                    stage = "STATUS_COMPUTED",
                    outcome = "OK",
                    entityType = "BudgetStatus",
                    entityId = budget.id,
                    confidence = adjustedPercent.toDouble().coerceIn(0.0, 2.0).toFloat(),
                    message = "spent=%.2f limit=%.2f percent=%.2f partial=%b"
                        .format(spent, effectiveLimit, adjustedPercent, status.isPartial),
                    timestamp = now
                )
            )
        }

        // BUD-3: Only update notification timestamp if the notification was
        // actually delivered (e.g. user has notifications disabled).

        when {
            adjustedPercent >= 1.0f -> {
                if (shouldNotify(budget.lastExceededNotifiedAt, now, periodStart, budget.period)) {
                    val delivered = sendNotification(budget.id.toInt(), budget, spent, effectiveLimit, "Budget Exceeded!", categoryName, adjustedPercent)
                    if (delivered) {
                        budgetRepository.updateExceededNotification(budget.id, now)
                    }
                    writeAlertDiagnostic(budget.id, "EXCEEDED", adjustedPercent, delivered, now)
                } else {
                    writeAlertDiagnostic(budget.id, "EXCEEDED_THROTTLED", adjustedPercent, false, now)
                }
            }
            adjustedPercent >= budget.notifyAtCritical && adjustedPercent < 1.0f -> {
                if (shouldNotify(budget.lastCriticalNotifiedAt, now, periodStart, budget.period)) {
                    val delivered = sendNotification(budget.id.toInt(), budget, spent, effectiveLimit, "Critical Budget Warning", categoryName, adjustedPercent)
                    if (delivered) {
                        budgetRepository.updateCriticalNotification(budget.id, now)
                    }
                    writeAlertDiagnostic(budget.id, "CRITICAL", adjustedPercent, delivered, now)
                } else {
                    writeAlertDiagnostic(budget.id, "CRITICAL_THROTTLED", adjustedPercent, false, now)
                }
            }
            adjustedPercent >= budget.notifyAtWarning && adjustedPercent < budget.notifyAtCritical -> {
                if (shouldNotify(budget.lastWarningNotifiedAt, now, periodStart, budget.period)) {
                    val delivered = sendNotification(budget.id.toInt(), budget, spent, effectiveLimit, "Budget Warning", categoryName, adjustedPercent)
                    if (delivered) {
                        budgetRepository.updateWarningNotification(budget.id, now)
                    }
                    writeAlertDiagnostic(budget.id, "WARNING", adjustedPercent, delivered, now)
                } else {
                    writeAlertDiagnostic(budget.id, "WARNING_THROTTLED", adjustedPercent, false, now)
                }
            }
        }
    }

    /**
     * P2-20: Writes a durable diagnostic event for every alert decision
     * (sent, throttled, or failed) so budget-alert behaviour is auditable
     * without relying on ephemeral Timber logs.
     */
    private fun writeAlertDiagnostic(
        budgetId: Long,
        stage: String,
        percentUsed: Float,
        delivered: Boolean,
        now: Long
    ) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                diagnosticEventDao.insert(
                    PipelineDiagnosticEvent(
                        pipeline = "budget_monitor",
                        stage = stage,
                        outcome = if (delivered) "DELIVERED" else "NOT_DELIVERED",
                        entityType = "Budget",
                        entityId = budgetId,
                        confidence = percentUsed.toDouble().coerceIn(0.0, 2.0).toFloat(),
                        timestamp = now
                    )
                )
            } catch (e: Exception) {
                Timber.w(e, "Failed to write budget-monitor diagnostic event (stage=%s)", stage)
            }
        }
    }

    private fun shouldNotify(lastNotified: Long?, now: Long, periodStart: Long, period: BudgetPeriod): Boolean {
        if (lastNotified == null) return true
        
        if (lastNotified < periodStart) return true
        
        val cooldown = getCooldownForPeriod(period)
        return now - lastNotified > cooldown
    }

    /**
     * @return true if the notification was delivered, false otherwise.
     */
    private fun sendNotification(
        notificationId: Int,
        budget: Budget,
        spent: Double,
        effectiveLimit: Double,
        title: String,
        categoryName: String,
        percentUsed: Float
    ): Boolean {
        // Always use effectiveLimit (rollover-aware) — never fall back
        // to budget.amount which omits rollover adjustments.
        val limit = effectiveLimit.coerceAtLeast(0.0)
        // Use the pre-computed percentUsed from BudgetStatus, which correctly
        // accounts for rollover via effectiveLimit, rather than recomputing from
        // raw spent/limit which could diverge.
        val percent = (percentUsed * 100).toInt().coerceAtLeast(0)  // allow overspend >100%
        val currencySymbol = com.yourname.expensetracker.domain.currency.SupportedCurrency
            .fromCode(budget.currency)?.symbol ?: budget.currency
        val content = String.format(
            Locale.US,
            "You've spent %s%.2f (%d%%) of your %s budget (%s%.2f).",
            currencySymbol,
            spent,
            percent,
            categoryName,
            currencySymbol,
            limit
        )

        return notificationService.sendBudgetAlert(notificationId, title, content) ==
            com.yourname.expensetracker.domain.service.NotificationService.DeliveryResult.DELIVERED
    }
}
