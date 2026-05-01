package com.yourname.expensetracker.domain.logic

import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.RecurringExpenseRepository
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.TransferDirection
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.DomainTransferDirection
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.model.RecurringPattern
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

@Singleton
class RecurringExpenseEngine @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val recurringExpenseRepository: RecurringExpenseRepository,
    private val timeProvider: TimeProvider
) {

    /**
     * Analyze all expenses to find recurring patterns and merge with manual overrides.
     * Returns a list of patterns sorted by confidence (Manual = 1.0).
     */
    suspend fun getPatterns(): List<RecurringPattern> {
        // Limit to last 12 months for performance - INS-009
        val now = timeProvider.now()
        val twelveMonthsAgo = TimePeriodUtils.addMonths(now, -12)
        val allExpenses = expenseRepository.getExpenseSnapshotsSince(twelveMonthsAgo)
        return getPatternsFromSnapshots(allExpenses)
    }

/**
 * Overload for when we already have the list of expenses (e.g. from Analytics).
 */
    suspend fun getPatternsFromSnapshots(allExpenses: List<ExpenseSnapshot>): List<RecurringPattern> {
        val now = timeProvider.now()
        // 1. Fetch Manual Overrides
        val manualExpenses = recurringExpenseRepository.getAll()

        // 3. Convert Manual Entries to RecurringPattern
        val manualPatterns = manualExpenses.map { manual ->
            RecurringPattern(
                merchantName = manual.merchant,
                averageAmount = manual.amount,
                currency = manual.currency,
                frequency = manual.frequency,
                periodVarianceDays = 0,
                amountVariancePercent = 0.0,
                nextExpectedDate = rollNextExpectedDateForward(manual.nextDate, manual.frequency, now),
                confidence = 1.0f, // Manual is 100% confident
                previousDates = emptyList(), // No history needed for display
                categoryId = null, // Manual entries don't have categoryId yet
                id = manual.id // Use DB ID
            )
        }

        val manualMerchantKeys = manualExpenses
            .map { canonicalMerchantKey(it.merchant, null) }
            .toSet()

        val detectedPatterns = detectPatternsFromSnapshots(
            allExpenses = allExpenses,
            excludedMerchantKeys = manualMerchantKeys
        )

        return (manualPatterns + detectedPatterns).sortedByDescending { it.confidence }
    }

    suspend fun getPatterns(allExpenses: List<Expense>): List<RecurringPattern> {
        return getPatternsFromSnapshots(allExpenses.map { it.toExpenseSnapshot() })
    }

    fun detectPatternsFromSnapshots(
        allExpenses: List<ExpenseSnapshot>,
        excludedMerchantKeys: Set<String> = emptySet()
    ): List<RecurringPattern> {
        val now = timeProvider.now()

        // Filter to PURCHASE only — deposits, transfers, and withdrawals are not recurring obligations
        val purchaseExpenses = allExpenses.filter {
            it.transactionType == DomainTransactionType.PURCHASE
        }

        // Group by canonical merchant key so aliases/casing/spacing variants collapse together.
        val grouped = purchaseExpenses.groupBy { canonicalMerchantKey(it.merchant, it.merchantKey) }

        val detectedPatterns = mutableListOf<RecurringPattern>()

        for ((merchantKey, expenses) in grouped) {
            // Use the most frequent original merchant name or the first one
            val actualMerchant = expenses.groupBy { it.merchant }
                .maxByOrNull { it.value.size }?.key ?: merchantKey

            if (merchantKey in excludedMerchantKeys) continue

            // Requirement: At least 3 occurrences to form a pattern
            if (expenses.size < 3) continue

            // Sort by date ascending to calculate intervals
            val sorted = expenses.sortedBy { it.date }

            // 1. Amount Stability Check
            val amounts = sorted.map { it.effectiveAmount }
            val avgAmount = amounts.average()
            if (avgAmount < 0.01) continue
            val stdDevAmount = calculateStdDev(amounts)
            // Coefficient of variation: stdDev / mean
            val amountVariance = if (avgAmount > 0) stdDevAmount / avgAmount else 0.0

            // If amount varies by more than 40%, likely not a fixed subscription/bill (LOW: relax from 35% to group similar amounts)
            if (amountVariance > 0.40) continue

            // 2. Interval Analysis
            val dates = sorted.map { it.date }
            val intervals = calculateIntervals(dates)

            val (frequency, confidence, varianceDays) = determineFrequency(intervals, dates)

            // Thresholds: Must be a known frequency and have >=50% confidence.
            // Using >= preserves valid monthly patterns with day-length drift
            // (e.g., Jan->Feb->Mar gives two intervals where one still matches).
            if (frequency != RecurrenceFrequency.IRREGULAR && confidence >= 0.50) {

                // Staleness check: drop patterns whose last occurrence is >6 months ago.
                // This prevents cancelled/dormant subscriptions from appearing as active recurring items.
                val sixMonthsAgo = TimePeriodUtils.addMonths(timeProvider.now(), -6)
                if (dates.isEmpty() || dates.last() < sixMonthsAgo) continue

                // Predict next date
                val baseDate = dates.last()
                val nextDate = RecurrenceCalculator.addFrequencyInterval(baseDate, frequency)

                detectedPatterns.add(
                    RecurringPattern(
                        merchantName = actualMerchant,
                        averageAmount = avgAmount,
                        currency = sorted.first().currency,
                        frequency = frequency,
                        periodVarianceDays = varianceDays,
                        amountVariancePercent = amountVariance,
                        nextExpectedDate = rollNextExpectedDateForward(nextDate, frequency, now),
                        confidence = confidence.toFloat(),
                        previousDates = dates.takeLast(5),
                        categoryId = sorted.first().categoryId
                    )
                )
            }
        }

        return detectedPatterns
    }

    private fun canonicalMerchantKey(merchant: String, merchantKey: String?): String {
        return merchantKey
            ?.takeIf { it.isNotBlank() }
            ?: MerchantKeyGenerator.generate(merchant).takeIf { it.isNotBlank() }
            ?: merchant.lowercase().trim()
    }

    private fun calculateIntervals(dates: List<Long>): List<Long> {
        if (dates.size < 2) return emptyList()
        val intervals = mutableListOf<Long>()
        for (i in 0 until dates.size - 1) {
            val diff = dates[i+1] - dates[i]
            intervals.add(diff)
        }
        return intervals
    }

    private fun calculateStdDev(values: List<Double>): Double {
        return com.yourname.expensetracker.domain.util.StatisticsUtils.calculateStdDev(values)
    }

    private fun rollNextExpectedDateForward(
        nextExpectedDate: Long,
        frequency: RecurrenceFrequency,
        now: Long
    ): Long {
        if (frequency == RecurrenceFrequency.IRREGULAR) return nextExpectedDate

        val todayStart = TimePeriodUtils.getStartOfDay(now)
        var rolledDate = nextExpectedDate
        while (rolledDate < todayStart) {
            val candidate = RecurrenceCalculator.addFrequencyInterval(rolledDate, frequency)

            if (candidate == rolledDate) break
            rolledDate = candidate
        }

        return rolledDate
    }

    private fun determineFrequency(intervalsMs: List<Long>, dates: List<Long>): Triple<RecurrenceFrequency, Double, Int> {
        if (intervalsMs.isEmpty()) return Triple(RecurrenceFrequency.IRREGULAR, 0.0, 0)
        
        // Fix (BUG-003): Use calendar-day difference helper across DST boundaries
        val intervalsDays = mutableListOf<Int>()

        for (i in 0 until dates.size - 1) {
            val diffDays = TimePeriodUtils.daysBetween(dates[i], dates[i + 1])
            intervalsDays.add(diffDays)
        }
        
        // Find Mode (most common interval)
        val frequencyMap = intervalsDays.groupingBy { it }.eachCount()
        val modeEntry = frequencyMap.maxByOrNull { it.value } 
            ?: return Triple(RecurrenceFrequency.IRREGULAR, 0.0, 0)
            
        val mode = modeEntry.key
        
        // NOTE: Frequency detection uses approximate day ranges as a heuristic.
        // Detected frequencies should be advanced using RecurrenceCalculator
        // (calendar-aware), not raw day multiplication, for correct behavior
        // across month boundaries and leap years.

        // Map mode to known frequencies with non-overlapping ranges
        // Uses midpoints to avoid gaps between categories
        val frequency = when (mode) {
             in 3..11 -> RecurrenceFrequency.WEEKLY          // ~7 days (weekly)
             in 12..22 -> RecurrenceFrequency.BIWEEKLY      // ~14 days (bi-weekly)
             in 23..45 -> RecurrenceFrequency.MONTHLY       // ~30 days (monthly)
             in 46..135 -> RecurrenceFrequency.QUARTERLY    // ~90 days (quarterly)
             in 136..270 -> RecurrenceFrequency.SEMI_ANNUALLY // ~180 days (semi-annually)
             in 271..400 -> RecurrenceFrequency.ANNUALLY     // ~365 days
             else -> RecurrenceFrequency.IRREGULAR
        }

        if (frequency == RecurrenceFrequency.IRREGULAR) {
            return Triple(RecurrenceFrequency.IRREGULAR, 0.0, 0)
        }

        // Calculate Confidence: intervals "close" to mode. For monthly (28-31), use ±3 days (LOW: date boundary fix)
        val tolerance = when {
            mode in 28..31 -> 3.0  // Month-length variation (28-31 days)
            else -> (mode * 0.2).coerceAtLeast(1.0)
        }
        val matchingIntervals = intervalsDays.count { abs(it - mode) <= tolerance }
        val consistencyScore = matchingIntervals.toDouble() / intervalsDays.size

        // Calculate Average Deviation (days)
        val deviations = intervalsDays.map { abs(it - mode) }
        val avgDeviation = deviations.average()

        return Triple(frequency, consistencyScore, avgDeviation.roundToInt())
    }

    private fun Expense.toExpenseSnapshot(): ExpenseSnapshot {
        return ExpenseSnapshot(
            id = id,
            amount = amount,
            effectiveAmount = effectiveAmount,
            currency = currency,
            merchant = merchant,
            merchantKey = merchantKey,
            transactionType = when (transactionType) {
                TransactionType.PURCHASE -> DomainTransactionType.PURCHASE
                TransactionType.WITHDRAWAL -> DomainTransactionType.WITHDRAWAL
                TransactionType.TRANSFER -> DomainTransactionType.TRANSFER
                TransactionType.DEPOSIT -> DomainTransactionType.DEPOSIT
                TransactionType.UNKNOWN -> DomainTransactionType.UNKNOWN
            },
            date = date,
            categoryId = categoryId,
            isNotMine = isNotMine,
            transferDirection = when (transferDirection) {
                TransferDirection.INCOMING -> DomainTransferDirection.INCOMING
                TransferDirection.OUTGOING -> DomainTransferDirection.OUTGOING
                null -> null
            },
            notes = notes
        )
    }

}
