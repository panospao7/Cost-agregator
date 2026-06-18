package com.yourname.expensetracker.domain.alerts

import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.model.ExpenseWithCategory
import com.yourname.expensetracker.domain.analytics.AnomalyDetector
import com.yourname.expensetracker.domain.analytics.AnalyticsCategoryRef
import com.yourname.expensetracker.domain.analytics.AnalyticsConversionWarningType
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.analytics.AnomalyMethod
import com.yourname.expensetracker.domain.analytics.AnomalyTransaction
import com.yourname.expensetracker.domain.analytics.MonthPeriod
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.DomainTransferDirection
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
import com.yourname.expensetracker.domain.service.NotificationService
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Orchestrates real-time anomaly alerts for expenses.
 *
 * Features:
 * - Cooldown management (per merchant: 24h, per category: 12h)
 * - Severity-based filtering (only HIGH confidence alerts)
 * - User feedback tracking ("looks_normal" reduces future alert frequency)
 * - Deduplication per expense
 */
@Singleton
class AnomalyAlertOrchestrator @Inject constructor(
    private val anomalyDetector: AnomalyDetector,
    private val notificationService: NotificationService,
    private val expenseDao: ExpenseDao,
    private val anomalyAlertRepository: AnomalyAlertRepository,
    private val analyticsCurrencyNormalizer: AnalyticsCurrencyNormalizer,
    private val currencySettingsRepository: CurrencySettingsRepository,
    private val timeProvider: TimeProvider
) {
    private val inFlightExpenseIds = ConcurrentHashMap.newKeySet<Long>()

    companion object {
        // Cooldown periods
        private const val MERCHANT_COOLDOWN_MS = 24 * 60 * 60 * 1000L // 24 hours
        private const val CATEGORY_COOLDOWN_MS = 12 * 60 * 60 * 1000L // 12 hours
        private const val HISTORY_LOOKBACK_DAYS = 90

        // Severity threshold - only alert on HIGH confidence
        private const val MIN_SEVERITY = "HIGH"

        // User feedback threshold - if user marked "looks_normal" 2+ times, reduce alerts
        private const val LOOKS_NORMAL_THRESHOLD = 2

        // Notification ID base for anomaly alerts (avoid collision with budget alerts)
        private const val ANOMALY_NOTIFICATION_BASE_ID = 100000
    }

    /**
     * Check if an expense is anomalous and send an alert if appropriate.
     * This method is designed to be called after an expense is created.
     *
     * @param expense The newly created expense with its category
     */
    suspend fun checkAndAlert(expense: ExpenseWithCategory) {
        // Only check purchase transactions
        if (expense.expense.transactionType != com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE) {
            return
        }

        // Skip if marked as "not mine"
        if (expense.expense.isNotMine) {
            return
        }

        val expenseId = expense.expense.id
        if (!inFlightExpenseIds.add(expenseId)) {
            Timber.d("Anomaly alert already in-flight for expense $expenseId")
            return
        }

        try {
            try {
                // Build a 90-day detection window so detector has enough samples
                val now = timeProvider.now()
                val lookbackStart = TimePeriodUtils.addDays(now, -HISTORY_LOOKBACK_DAYS)
                val detectionPeriod = getDetectionPeriod(now, lookbackStart)

                // Provide detector with 90-day category history + current expense context
                val categoryId = expense.expense.categoryId
                val historicalExpenses = if (categoryId != null) {
                    expenseDao.getExpensesByCategory(categoryId, lookbackStart, now)
                        .filter { it.id != expenseId }
                } else {
                    emptyList()
                }
                val allExpenses = historicalExpenses + listOf(expense.expense)
                val homeCurrency = runCatching { currencySettingsRepository.homeCurrency().first() }.getOrDefault(expense.expense.currency)
                val normalizedHistory = analyticsCurrencyNormalizer.normalizeSnapshots(
                    allExpenses.map { it.toSnapshot() },
                    homeCurrency
                )
                if (normalizedHistory.warnings.any { warning ->
                        warning.type == AnalyticsConversionWarningType.INVALID_HOME_CURRENCY ||
                            warning.type == AnalyticsConversionWarningType.INVALID_TRANSACTION_CURRENCY
                    }) {
                    Timber.w("Skipping anomaly alert for expense %s due to invalid analytics currency configuration", expenseId)
                    return
                }

                val missingRateWarnings = normalizedHistory.warnings.filter { it.type == AnalyticsConversionWarningType.MISSING_EXCHANGE_RATE }
                if (missingRateWarnings.isNotEmpty()) {
                    val totalExcluded = missingRateWarnings.sumOf { it.affectedTransactionCount }
                    Timber.w("Anomaly alert for expense %s based on partial historical baseline — %d transaction(s) excluded due to missing exchange rates", expenseId, totalExcluded)
                }

                val excludedCurrentExpense = normalizedHistory.includedExpenses.none { it.id == expenseId }
                if (excludedCurrentExpense) {
                    Timber.w("Skipping anomaly alert for expense %s because current transaction could not be normalized", expenseId)
                    return
                }

                val expenseSnapshots = normalizedHistory.includedExpenses
                val categoryMap = expense.category
                    ?.let {
                        mapOf(
                            it.id to AnalyticsCategoryRef(
                                id = it.id,
                                name = it.name,
                                icon = it.icon,
                                color = it.color
                            )
                        )
                    }
                    ?: emptyMap()

                // Run anomaly detection
                val anomalies = anomalyDetector.detect(
                    monthPeriod = detectionPeriod,
                    categoryMap = categoryMap,
                    allExpenses = expenseSnapshots,
                    displayCurrency = homeCurrency
                )

                // Check if this expense was flagged
                val expenseAnomalies = anomalies.filter { it.expense.id == expenseId }

                if (expenseAnomalies.isEmpty()) {
                    Timber.d("No anomalies detected for expense $expenseId")
                    return
                }

                // Check if we should alert
                if (!shouldAlert(expense, expenseAnomalies)) {
                    Timber.d("Alert suppressed for expense $expenseId due to cooldown/deduplication")
                    return
                }

                // Determine severity
                val severity = determineSeverity(expenseAnomalies)

                // Only alert on HIGH severity
                if (severity != MIN_SEVERITY) {
                    Timber.d("Alert suppressed for expense $expenseId - severity $severity below threshold")
                    return
                }

                // Build and send notification
                val normalizedExpense = expenseSnapshots.firstOrNull { it.id == expenseId }
                val message = buildNotificationMessage(normalizedExpense ?: expense.expense.toSnapshot(), expenseAnomalies)
                val notificationId = ANOMALY_NOTIFICATION_BASE_ID + (expenseId % 100000).toInt()

                // Record the alert in database
                val alert = NewAnomalyAlert(
                    expenseId = expenseId,
                    merchant = expense.expense.merchant,
                    category = expense.category?.name,
                    amount = normalizedExpense?.effectiveAmount ?: expense.expense.effectiveAmount,
                    anomalyReason = buildAnomalyReason(expenseAnomalies),
                    severity = severity,
                    alertedAt = now
                )

                val alertId = anomalyAlertRepository.insert(alert)
                Timber.d("Created anomaly alert $alertId for expense $expenseId")

                // Send the notification
                notificationService.sendAnomalyAlert(
                    notificationId = notificationId,
                    title = "Unusual Charge Detected",
                    message = message,
                    expenseId = expenseId
                )

                Timber.i(
                    "Anomaly alert sent for %s: %s",
                    expense.expense.merchant,
                    CurrencyFormatter.format(
                        normalizedExpense?.effectiveAmount ?: expense.expense.effectiveAmount,
                        normalizedExpense?.currency ?: homeCurrency
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Error checking anomaly alert for expense $expenseId")
            }
        } catch (e: CancellationException) {
            throw e
        } finally {
            inFlightExpenseIds.remove(expenseId)
        }
    }

    /**
     * Determines if an alert should be sent based on cooldown and deduplication logic.
     */
    private suspend fun shouldAlert(
        expense: ExpenseWithCategory,
        anomalies: List<AnomalyTransaction>
    ): Boolean {
        val now = timeProvider.now()
        val merchant = expense.expense.merchant
        val category = expense.category?.name

        // Check if we've already alerted for this exact expense
        val lastAlertForExpense = anomalyAlertRepository.getLastAlertForExpense(expense.expense.id)
        if (lastAlertForExpense != null) {
            Timber.d("Already alerted for expense ${expense.expense.id}")
            return false
        }

        // Check merchant cooldown
        if (isMerchantCooldownActive(merchant, now)) {
            return false
        }

        // Check category cooldown
        if (category != null && isCategoryCooldownActive(category, now)) {
            return false
        }

        // Check if user has marked this merchant as "looks_normal" multiple times
        val looksNormalCount = anomalyAlertRepository.getLooksNormalCountForMerchant(merchant)
        if (looksNormalCount >= LOOKS_NORMAL_THRESHOLD) {
            // Only alert if deviation is very high (5x normal)
            val maxDeviation = anomalies.maxOf { it.deviationMultiple }
            if (maxDeviation < 5.0f) {
                Timber.d("Merchant $merchant marked as normal $looksNormalCount times, suppressing alert (deviation: $maxDeviation)")
                return false
            }
        }

        return true
    }

    /**
     * Check if merchant-level cooldown is active (24 hours).
     */
    private suspend fun isMerchantCooldownActive(merchant: String, now: Long): Boolean {
        val sinceMs = now - MERCHANT_COOLDOWN_MS
        val lastAlert = anomalyAlertRepository.getLastAlertForMerchant(merchant, sinceMs)
        return lastAlert != null
    }

    /**
     * Check if category-level cooldown is active (12 hours).
     */
    private suspend fun isCategoryCooldownActive(category: String, now: Long): Boolean {
        val sinceMs = now - CATEGORY_COOLDOWN_MS
        val lastAlert = anomalyAlertRepository.getLastAlertForCategory(category, sinceMs)
        return lastAlert != null
    }

    /**
     * Determine severity based on deviation and detection method.
     */
    private fun determineSeverity(anomalies: List<AnomalyTransaction>): String {
        val maxDeviation = anomalies.maxOf { it.deviationMultiple }
        val hasMadDetection = anomalies.any { it.detectionMethod == AnomalyMethod.MAD }
        val hasIqrDetection = anomalies.any { it.detectionMethod == AnomalyMethod.IQR }

        return when {
            maxDeviation >= 5.0f || hasMadDetection -> "HIGH"
            maxDeviation >= 3.0f || hasIqrDetection -> "MEDIUM"
            else -> "LOW"
        }
    }

    /**
     * Build the notification message.
     * Format: "Unusual charge: {amount} at {merchant} ({reason}). Tap to review."
     */
    private fun buildNotificationMessage(
        expense: ExpenseSnapshot,
        anomalies: List<AnomalyTransaction>
    ): String {
        val amount = CurrencyFormatter.format(expense.effectiveAmount, expense.currency)
        val merchant = expense.merchant
        val reason = buildAnomalyReason(anomalies)

        return "Unusual charge: $amount at $merchant ($reason). Tap to review."
    }

    /**
     * Build a human-readable anomaly reason from detection methods.
     */
    private fun buildAnomalyReason(anomalies: List<AnomalyTransaction>): String {
        val methods = anomalies.map { it.detectionMethod }.distinct()
        val contextualNote = anomalies.firstNotNullOfOrNull { it.contextualNote }

        val reason = when {
            methods.contains(AnomalyMethod.MAD) -> "unusually high amount"
            methods.contains(AnomalyMethod.IQR) -> "above normal range"
            methods.contains(AnomalyMethod.CONTEXTUAL) -> "unusual timing"
            methods.contains(AnomalyMethod.MULTIPLIER) -> "higher than typical"
            else -> "unusual pattern detected"
        }

        return contextualNote ?: reason
    }

    /**
     * Detection period that spans historical lookback through now.
     */
    private fun getDetectionPeriod(now: Long, startMs: Long): MonthPeriod {
        val calendar = java.util.Calendar.getInstance().apply {
            timeInMillis = now
        }
        val year = calendar.get(java.util.Calendar.YEAR)
        val month = calendar.get(java.util.Calendar.MONTH)
        return MonthPeriod(year, month, startMs, now + 1L)
    }

    private fun com.yourname.expensetracker.data.database.entity.Expense.toSnapshot(): ExpenseSnapshot {
        return ExpenseSnapshot(
            id = id,
            amount = amount,
            effectiveAmount = effectiveAmount,
            currency = currency,
            merchant = merchant,
            merchantKey = merchantKey,
            transactionType = when (transactionType) {
                com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE -> DomainTransactionType.PURCHASE
                com.yourname.expensetracker.data.database.entity.TransactionType.WITHDRAWAL -> DomainTransactionType.WITHDRAWAL
                com.yourname.expensetracker.data.database.entity.TransactionType.TRANSFER -> DomainTransactionType.TRANSFER
                com.yourname.expensetracker.data.database.entity.TransactionType.DEPOSIT -> DomainTransactionType.DEPOSIT
                com.yourname.expensetracker.data.database.entity.TransactionType.UNKNOWN -> DomainTransactionType.UNKNOWN
            },
            date = date,
            categoryId = categoryId,
            isNotMine = isNotMine,
            transferDirection = when (transferDirection) {
                com.yourname.expensetracker.data.database.entity.TransferDirection.INCOMING -> DomainTransferDirection.INCOMING
                com.yourname.expensetracker.data.database.entity.TransferDirection.OUTGOING -> DomainTransferDirection.OUTGOING
                null -> null
            },
            notes = notes
        )
    }

    /**
     * Cleanup method to clear the service scope.
     */
    fun cleanup() {
        // No-op: orchestration is now executed inline in caller coroutine.
    }

    /**
     * P2-07: Bulk invalidation called after bulk expense mutations.
     * In the current implementation anomaly detection runs per-expense,
     * so this is a best-effort signal. Future versions may clear internal
     * caches or trigger a background re-scan.
     */
    fun invalidateCache() {
        // Best-effort: anomaly alerts are checked per-expense via checkAndAlert()
    }
}
