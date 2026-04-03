package com.yourname.expensetracker.domain.alerts

import com.yourname.expensetracker.data.database.dao.AnomalyAlertDao
import com.yourname.expensetracker.data.database.entity.AnomalyAlert
import com.yourname.expensetracker.data.database.model.ExpenseWithCategory
import com.yourname.expensetracker.domain.analytics.AnomalyDetector
import com.yourname.expensetracker.domain.analytics.AnomalyMethod
import com.yourname.expensetracker.domain.analytics.AnomalyTransaction
import com.yourname.expensetracker.domain.analytics.MonthPeriod
import com.yourname.expensetracker.domain.service.NotificationService
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

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
    private val anomalyAlertDao: AnomalyAlertDao,
    private val timeProvider: TimeProvider,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + ioDispatcher)

    companion object {
        // Cooldown periods
        private const val MERCHANT_COOLDOWN_MS = 24 * 60 * 60 * 1000L // 24 hours
        private const val CATEGORY_COOLDOWN_MS = 12 * 60 * 60 * 1000L // 12 hours

        // Severity threshold - only alert on HIGH confidence
        private const val MIN_SEVERITY = "HIGH"
        private const val MIN_DEVIATION_MULTIPLIER = 4.0f

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

        serviceScope.launch {
            try {
                // Get all expenses for the current month for context
                val now = timeProvider.now()
                val currentMonth = getMonthPeriod(now)

                // Build a minimal list for detection - just this expense plus any needed history
                val monthExpenses = listOf(expense.expense)
                val categoryMap = expense.category?.let { mapOf(it.id to it) } ?: emptyMap()

                // Run anomaly detection
                val anomalies = anomalyDetector.detect(
                    monthPeriod = currentMonth,
                    categoryMap = categoryMap,
                    allExpenses = monthExpenses
                )

                // Check if this expense was flagged
                val expenseAnomalies = anomalies.filter { it.expense.id == expense.expense.id }

                if (expenseAnomalies.isEmpty()) {
                    Timber.d("No anomalies detected for expense ${expense.expense.id}")
                    return@launch
                }

                // Check if we should alert
                if (!shouldAlert(expense, expenseAnomalies)) {
                    Timber.d("Alert suppressed for expense ${expense.expense.id} due to cooldown/deduplication")
                    return@launch
                }

                // Determine severity
                val severity = determineSeverity(expenseAnomalies)

                // Only alert on HIGH severity
                if (severity != MIN_SEVERITY) {
                    Timber.d("Alert suppressed for expense ${expense.expense.id} - severity $severity below threshold")
                    return@launch
                }

                // Build and send notification
                val message = buildNotificationMessage(expense, expenseAnomalies)
                val notificationId = ANOMALY_NOTIFICATION_BASE_ID + (expense.expense.id % 100000).toInt()

                // Record the alert in database
                val alert = AnomalyAlert(
                    expenseId = expense.expense.id,
                    merchant = expense.expense.merchant,
                    category = expense.category?.name,
                    amount = expense.expense.effectiveAmount,
                    anomalyReason = buildAnomalyReason(expenseAnomalies),
                    severity = severity,
                    alertedAt = now
                )

                val alertId = anomalyAlertDao.insert(alert)
                Timber.d("Created anomaly alert $alertId for expense ${expense.expense.id}")

                // Send the notification
                notificationService.sendAnomalyAlert(
                    notificationId = notificationId,
                    title = "Unusual Charge Detected",
                    message = message,
                    expenseId = expense.expense.id
                )

                Timber.i("Anomaly alert sent for ${expense.expense.merchant}: €${expense.expense.effectiveAmount}")

            } catch (e: Exception) {
                Timber.e(e, "Error checking anomaly alert for expense ${expense.expense.id}")
            }
        }.join()
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
        val lastAlertForExpense = anomalyAlertDao.getLastAlertForExpense(expense.expense.id)
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
        val looksNormalCount = anomalyAlertDao.getLooksNormalCountForMerchant(merchant)
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
        val lastAlert = anomalyAlertDao.getLastAlertForMerchant(merchant, sinceMs)
        return lastAlert != null
    }

    /**
     * Check if category-level cooldown is active (12 hours).
     */
    private suspend fun isCategoryCooldownActive(category: String, now: Long): Boolean {
        val sinceMs = now - CATEGORY_COOLDOWN_MS
        val lastAlert = anomalyAlertDao.getLastAlertForCategory(category, sinceMs)
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
     * Format: "Unusual charge: €{amount} at {merchant} ({reason}). Tap to review."
     */
    private fun buildNotificationMessage(
        expense: ExpenseWithCategory,
        anomalies: List<AnomalyTransaction>
    ): String {
        val amount = String.format(Locale.US, "%.2f", expense.expense.effectiveAmount)
        val merchant = expense.expense.merchant
        val reason = buildAnomalyReason(anomalies)

        return "Unusual charge: €$amount at $merchant ($reason). Tap to review."
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
     * Helper to get the current month period.
     */
    private fun getMonthPeriod(timeMs: Long): MonthPeriod {
        val calendar = java.util.Calendar.getInstance().apply {
            timeInMillis = timeMs
        }
        val year = calendar.get(java.util.Calendar.YEAR)
        val month = calendar.get(java.util.Calendar.MONTH)

        // Set to first day of month
        calendar.set(java.util.Calendar.DAY_OF_MONTH, 1)
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        val startMs = calendar.timeInMillis

        // Set to last day of month
        calendar.add(java.util.Calendar.MONTH, 1)
        val endMs = calendar.timeInMillis

        return MonthPeriod(year, month, startMs, endMs)
    }

    /**
     * Cleanup method to clear the service scope.
     */
    fun cleanup() {
        serviceJob.cancel()
    }
}
