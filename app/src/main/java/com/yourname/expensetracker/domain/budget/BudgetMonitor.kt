package com.yourname.expensetracker.domain.budget

import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.domain.notification.presentation.NotificationKind
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
    private val diagnosticEventWriter: com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter,
    private val writeBarrier: com.yourname.expensetracker.data.backup.DatabaseWriteBarrier,
    private val diagnosticSink: com.yourname.expensetracker.data.backup.MaintenanceSafeDiagnosticSink
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
            serviceScope.launch(ioDispatcher) {
                try {
                    writeBarrier.checkWritesAllowed("BudgetMonitor.checkBudgets.diagnostic")
                    diagnosticEventWriter.emit(com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
                        pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.BUDGET,
                        stage = "CHECK_FAILED",
                        outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.FAILED_RETRYABLE,
                        severity = com.yourname.expensetracker.domain.diagnostics.EventSeverity.ERROR,
                        exception = lastException
                    ))
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
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
        withContext(ioDispatcher) {
            try {
                writeBarrier.checkWritesAllowed("BudgetMonitor.diagnostic")
                diagnosticEventWriter.emit(com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
                    pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.BUDGET,
                    stage = "CHECK_STARTED",
                    outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.ATTEMPTED,
                    entityType = "Budget",
                    entityId = budget.id
                ))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (e is com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException) {
                    diagnosticSink.recordBlockedOperation("BudgetMonitor.diagnostic", e.mode, "P6")
                } else {
                    Timber.w(e, "BudgetMonitor: skipping diagnostic insert")
                }
            }
        }

        // P6-P1-2: Use adjustedSpendBreakdown.effectiveSpend (shared-expense offset).
        // BudgetStatus.spentAmount is gross spend and does not account for shared-expense
        // reimbursements, which can trigger false threshold alerts; it never drives an alert
        // (money-aggregates INV-18: a null breakdown skips with EFFECTIVE_SPEND_UNAVAILABLE).
        //
        // P6-CURRENT-002 (RESOLVED): adjustedSpendBreakdown is now populated by
        // BudgetRepository.createBudgetStatus(), so the monitor and UI share the same
        // effective spend.
        //
        // INV-18 (money-aggregates-sweep): a null breakdown means effective spend is
        // unavailable (offset engine failed in the repo). Gross spentAmount never reaches an
        // alert: the budget is skipped below, so `spent` is always the effective spend.
        val adjustedBreakdown = status.adjustedSpendBreakdown
        // P6-CURRENT-027(b): record when the shared-expense offset breakdown was unavailable
        // (offset engine failed in the repo).
        val grossFallback = adjustedBreakdown == null
        val categoryName = status.category?.name ?: "Overall"
        val periodStart = status.periodStart

        // RP-09 (P6-005): percent-gated alerting. When the budget limit could not be
        // converted to home currency (percentKnown == false), no home-currency percent
        // exists: BudgetStatus.percentUsed is a 0f placeholder that must never enter a
        // threshold comparison or an alert. Record a durable SKIPPED diagnostic with a
        // controlled reason code and move on — other budgets are unaffected.
        if (!status.percentKnown) {
            withContext(ioDispatcher) {
                try {
                    writeBarrier.checkWritesAllowed("BudgetMonitor.diagnostic")
                    diagnosticEventWriter.emit(com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
                        pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.BUDGET,
                        stage = "STATUS_SKIPPED",
                        outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.SKIPPED,
                        entityType = "BudgetStatus",
                        entityId = budget.id,
                        metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                            .put("grossFallback", grossFallback)
                            .put("reason", "PERCENT_UNKNOWN")
                            .build()
                    ))
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    if (e is com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException) {
                        diagnosticSink.recordBlockedOperation("BudgetMonitor.diagnostic", e.mode, "P6")
                    } else {
                        Timber.w(e, "BudgetMonitor: skipping diagnostic insert")
                    }
                }
            }
            return
        }

        // INV-18: effective spend unavailable (breakdown null). Run no threshold comparison, send
        // no notification and update no notified-at timestamp; nothing is persisted, so the next
        // cycle re-evaluates. Emit one durable SKIPPED diagnostic with a bounded reason and no
        // amount keys. Check order: PERCENT_UNKNOWN, this skip, NO_SPEND_OR_LIMIT.
        if (adjustedBreakdown == null) {
            withContext(ioDispatcher) {
                try {
                    writeBarrier.checkWritesAllowed("BudgetMonitor.diagnostic")
                    diagnosticEventWriter.emit(com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
                        pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.BUDGET,
                        stage = "STATUS_SKIPPED",
                        outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.SKIPPED,
                        entityType = "BudgetStatus",
                        entityId = budget.id,
                        metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                            .put("grossFallback", grossFallback)
                            .put("reason", "EFFECTIVE_SPEND_UNAVAILABLE")
                            .build()
                    ))
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    if (e is com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException) {
                        diagnosticSink.recordBlockedOperation("BudgetMonitor.diagnostic", e.mode, "P6")
                    } else {
                        Timber.w(e, "BudgetMonitor: skipping diagnostic insert")
                    }
                }
            }
            return
        }
        val spent = adjustedBreakdown.effectiveSpend

        // P6-CURRENT-027(a): the early no-op return previously emitted nothing, leaving a gap
        // in the budget-check audit trail. Emit a durable SKIPPED diagnostic so a non-positive
        // spend/limit no-op is observable. Best-effort, write-barrier-guarded, exception-tolerant.
        if (spent <= 0 || budget.amount <= 0) {
            withContext(ioDispatcher) {
                try {
                    writeBarrier.checkWritesAllowed("BudgetMonitor.diagnostic")
                    diagnosticEventWriter.emit(com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
                        pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.BUDGET,
                        stage = "STATUS_SKIPPED",
                        outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.SKIPPED,
                        entityType = "BudgetStatus",
                        entityId = budget.id,
                        metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                            .put("spent", spent)
                            .put("limit", budget.amount)
                            .put("grossFallback", grossFallback)
                            .put("reason", "NO_SPEND_OR_LIMIT")
                            .build()
                    ))
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    if (e is com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException) {
                        diagnosticSink.recordBlockedOperation("BudgetMonitor.diagnostic", e.mode, "P6")
                    } else {
                        Timber.w(e, "BudgetMonitor: skipping diagnostic insert")
                    }
                }
            }
            return
        }

        // Recompute percent from adjusted spent to avoid triggering alerts on gross spend
        val effectiveLimit = status.effectiveLimit
        val adjustedPercent = if (effectiveLimit > 0) (spent / effectiveLimit).toFloat() else 0f

        // P2-20: Write a diagnostic event recording the budget check attempt.
        // Writes on ioDispatcher to avoid blocking the monitor coroutine.
        withContext(ioDispatcher) {
            try {
                writeBarrier.checkWritesAllowed("BudgetMonitor.diagnostic")
                diagnosticEventWriter.emit(com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
                    pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.BUDGET,
                    stage = "STATUS_COMPUTED",
                    outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.COMPLETED,
                    entityType = "BudgetStatus",
                    entityId = budget.id,
                    metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                        .put("spent", spent)
                        .put("limit", effectiveLimit)
                        .put("percent", adjustedPercent)
                        .put("partial", status.isPartial)
                        .put("partialData", status.isPartial)
                        .put("grossFallback", grossFallback)
                        .build()
                ))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (e is com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException) {
                    diagnosticSink.recordBlockedOperation("BudgetMonitor.diagnostic", e.mode, "P6")
                } else {
                    Timber.w(e, "BudgetMonitor: skipping diagnostic insert")
                }
            }
        }

        // BUD-3: Only update notification timestamp if the notification was
        // actually delivered (e.g. user has notifications disabled).

        when {
            adjustedPercent >= 1.0f -> {
                if (shouldNotify(budget.lastExceededNotifiedAt, now, periodStart, budget.period)) {
                    val delivered = sendNotification(budget.id.toInt(), budget, spent, effectiveLimit, "Budget Exceeded!", categoryName, adjustedPercent, status.currency, partialData = status.isPartial)
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
                    val delivered = sendNotification(budget.id.toInt(), budget, spent, effectiveLimit, "Critical Budget Warning", categoryName, adjustedPercent, status.currency, partialData = status.isPartial)
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
                    val delivered = sendNotification(budget.id.toInt(), budget, spent, effectiveLimit, "Budget Warning", categoryName, adjustedPercent, status.currency, partialData = status.isPartial)
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
        serviceScope.launch(ioDispatcher) {
            try {
                writeBarrier.checkWritesAllowed("BudgetMonitor.writeAlertDiagnostic")
                diagnosticEventWriter.emit(com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
                    pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.BUDGET,
                    stage = stage,
                    outcome = if (delivered) com.yourname.expensetracker.domain.diagnostics.EventOutcome.COMPLETED
                              else com.yourname.expensetracker.domain.diagnostics.EventOutcome.SKIPPED,
                    entityType = "Budget",
                    entityId = budgetId,
                    metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                        .put("percentUsed", percentUsed)
                        .build()
                ))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
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
    private suspend fun sendNotification(
        notificationId: Int,
        budget: Budget,
        spent: Double,
        effectiveLimit: Double,
        title: String,
        categoryName: String,
        percentUsed: Float,
        displayCurrency: String = budget.currency,
        partialData: Boolean = false
    ): Boolean {
        // Always use effectiveLimit (rollover-aware) — never fall back
        // to budget.amount which omits rollover adjustments.
        val limit = effectiveLimit.coerceAtLeast(0.0)
        // Use the pre-computed percentUsed from BudgetStatus, which correctly
        // accounts for rollover via effectiveLimit, rather than recomputing from
        // raw spent/limit which could diverge.
        val percent = (percentUsed * 100).toInt().coerceAtLeast(0)  // allow overspend >100%
        // NOTE: spent/effectiveLimit are denominated in displayCurrency.
        // P6-CURRENT-003: callers pass BudgetStatus.currency (home currency after
        // repository normalization), so the formatted symbol matches the amounts.
        val currencySymbol = com.yourname.expensetracker.domain.currency.SupportedCurrency
            .fromCode(displayCurrency)?.symbol ?: displayCurrency
        // RP-09 (P6-005): bounded qualifier when some transactions were excluded from
        // the spend aggregate (missing exchange rates). Constant text only — no
        // amounts, paths, or exception details are ever appended here.
        val partialQualifier = if (partialData) {
            " Some transactions were excluded (missing exchange rates)."
        } else ""
        val content = String.format(
            Locale.US,
            "You've spent %s%.2f (%d%%) of your %s budget (%s%.2f).%s",
            currencySymbol,
            spent,
            percent,
            categoryName,
            currencySymbol,
            limit,
            partialQualifier
        )

        // S6 (INV-4, INV-12): declare the BUDGET kind; the id stays the raw budget id (TECH-12 (a)).
        // Only DELIVERED advances the cooldown write; NOT_DELIVERED and PERMISSION_DENIED (TECH-9)
        // both leave the cooldown untouched, exactly like the legacy NOT_DELIVERED path.
        return notificationService.sendBudgetAlertForKind(
            kind = NotificationKind.BUDGET,
            notificationId = notificationId,
            title = title,
            message = content
        ) == com.yourname.expensetracker.domain.service.NotificationService.DeliveryResult.DELIVERED
    }
}
