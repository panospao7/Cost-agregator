package com.yourname.expensetracker.domain.logic

import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.RecurringExpenseRepository
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.model.RecurringPattern
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
        val twelveMonthsAgo = timeProvider.now() - (365L * 24 * 60 * 60 * 1000)
        val allExpenses = expenseRepository.getExpensesSince(twelveMonthsAgo)
        return getPatterns(allExpenses)
    }

    /**
     * Overload for when we already have the list of expenses (e.g. from Analytics).
     */
    suspend fun getPatterns(allExpenses: List<com.yourname.expensetracker.data.database.entity.Expense>): List<RecurringPattern> {
        // 1. Fetch Manual Overrides
        val manualExpenses = recurringExpenseRepository.getAll()
        val manualMap = manualExpenses.associateBy { it.merchant.lowercase() }
        

        // Group by normalized merchant name
        val grouped = allExpenses.groupBy { it.merchant.lowercase().trim() }

        val detectedPatterns = mutableListOf<RecurringPattern>()

        for ((normalizedMerchant, expenses) in grouped) {
            // Use the most frequent original merchant name or the first one
            val actualMerchant = expenses.groupBy { it.merchant }
                .maxByOrNull { it.value.size }?.key ?: normalizedMerchant

            // If we already have a manual rule for this merchant, skip detection
            if (manualMap.containsKey(normalizedMerchant)) continue

            // Requirement: At least 3 occurrences to form a pattern
            if (expenses.size < 3) continue 

            // Sort by date ascending to calculate intervals
            val sorted = expenses.sortedBy { it.date }
            
            // 1. Amount Stability Check
            val amounts = sorted.map { it.amount }
            val avgAmount = amounts.average()
            val stdDevAmount = calculateStdDev(amounts)
            // Coefficient of variation: stdDev / mean
            val amountVariance = if (avgAmount > 0) stdDevAmount / avgAmount else 0.0

            // If amount varies by more than 35%, likely not a fixed subscription/bill
            if (amountVariance > 0.35) continue 

            // 2. Interval Analysis
            val dates = sorted.map { it.date }
            val intervals = calculateIntervals(dates)
            
            val (frequency, confidence, varianceDays) = determineFrequency(intervals, dates)

            // Thresholds: Must be a known frequency and have > 50% confidence (LOG-013 Relaxed further to catch varying bills)
            if (frequency != RecurrenceFrequency.IRREGULAR && confidence > 0.50) {
                
                // Predict next date
                // Predict next date (LOG-021 Fix: Use Calendar for proper Month/Year addition)
                val cal = java.util.Calendar.getInstance().apply { timeInMillis = timeProvider.now() }
                cal.timeInMillis = dates.last()
                when (frequency) {
                    RecurrenceFrequency.MONTHLY -> cal.add(java.util.Calendar.MONTH, 1)
                    RecurrenceFrequency.QUARTERLY -> cal.add(java.util.Calendar.MONTH, 3)
                    RecurrenceFrequency.SEMI_ANNUALLY -> cal.add(java.util.Calendar.MONTH, 6)
                    RecurrenceFrequency.ANNUALLY -> cal.add(java.util.Calendar.YEAR, 1)
                    else -> cal.add(java.util.Calendar.DAY_OF_YEAR, frequency.days)
                }
                val nextDate = cal.timeInMillis

                detectedPatterns.add(
                    RecurringPattern(
                        merchantName = actualMerchant,
                        averageAmount = avgAmount,
                        currency = sorted.first().currency,
                        frequency = frequency,
                        periodVarianceDays = varianceDays,
                        amountVariancePercent = amountVariance,
                        nextExpectedDate = nextDate,
                        confidence = confidence.toFloat(),
                        previousDates = dates.takeLast(5),
                        categoryId = sorted.first().categoryId
                    )
                )
            }
        }
        
        // 3. Convert Manual Entries to RecurringPattern
        val manualPatterns = manualExpenses.map { manual ->
            RecurringPattern(
                merchantName = manual.merchant,
                averageAmount = manual.amount,
                currency = manual.currency,
                frequency = manual.frequency,
                periodVarianceDays = 0,
                amountVariancePercent = 0.0,
                nextExpectedDate = manual.nextDate,
                confidence = 1.0f, // Manual is 100% confident
                previousDates = emptyList(), // No history needed for display
                categoryId = null, // Manual entries don't have categoryId yet
                id = manual.id // Use DB ID
            )
        }

        return (manualPatterns + detectedPatterns).sortedByDescending { it.confidence }
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

    private fun determineFrequency(intervalsMs: List<Long>, dates: List<Long>): Triple<RecurrenceFrequency, Double, Int> {
        if (intervalsMs.isEmpty()) return Triple(RecurrenceFrequency.IRREGULAR, 0.0, 0)
        
        // Fix (BUG-003): Use Calendar for proper day interval calculation across DST
        val intervalsDays = mutableListOf<Int>()
        val cal1 = java.util.Calendar.getInstance().apply { timeInMillis = timeProvider.now() }
        val cal2 = java.util.Calendar.getInstance().apply { timeInMillis = timeProvider.now() }
        
        for (i in 0 until dates.size - 1) {
            cal1.timeInMillis = dates[i]
            cal2.timeInMillis = dates[i + 1]
            
            // Clear time fields for accurate day calculation (handles DST edge cases)
            cal1.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal1.set(java.util.Calendar.MINUTE, 0)
            cal1.set(java.util.Calendar.SECOND, 0)
            cal1.set(java.util.Calendar.MILLISECOND, 0)
            
            cal2.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal2.set(java.util.Calendar.MINUTE, 0)
            cal2.set(java.util.Calendar.SECOND, 0)
            cal2.set(java.util.Calendar.MILLISECOND, 0)
            
            val diffDays = ((cal2.timeInMillis - cal1.timeInMillis) / 86400000.0).roundToInt()
            intervalsDays.add(diffDays)
        }
        
        // Find Mode (most common interval)
        val frequencyMap = intervalsDays.groupingBy { it }.eachCount()
        val modeEntry = frequencyMap.maxByOrNull { it.value } 
            ?: return Triple(RecurrenceFrequency.IRREGULAR, 0.0, 0)
            
        val mode = modeEntry.key
        
        // Map mode to known frequencies with non-overlapping ranges
        // Uses midpoints to avoid gaps between categories
        val frequency = when (mode) {
             in 3..11 -> RecurrenceFrequency.WEEKLY          // ~7 days (weekly)
             in 12..22 -> RecurrenceFrequency.BIWEEKLY      // ~14 days (bi-weekly)
             in 23..45 -> RecurrenceFrequency.MONTHLY       // ~30 days (monthly)
             in 46..75 -> RecurrenceFrequency.QUARTERLY      // ~90 days (quarterly)
             in 76..120 -> RecurrenceFrequency.QUARTERLY     // Extended quarterly
             in 121..270 -> RecurrenceFrequency.SEMI_ANNUALLY // ~180 days
             in 271..400 -> RecurrenceFrequency.ANNUALLY     // ~365 days
             else -> RecurrenceFrequency.IRREGULAR
        }

        if (frequency == RecurrenceFrequency.IRREGULAR) {
            return Triple(RecurrenceFrequency.IRREGULAR, 0.0, 0)
        }

        // Calculate Confidence
        // Score based on how many intervals are "close" to the mode (within ±20% or ±1 day)
        val tolerance = (mode * 0.2).coerceAtLeast(1.0)
        val matchingIntervals = intervalsDays.count { abs(it - mode) <= tolerance }
        val consistencyScore = matchingIntervals.toDouble() / intervalsDays.size

        // Calculate Average Deviation (days)
        val deviations = intervalsDays.map { abs(it - mode) }
        val avgDeviation = deviations.average()

        return Triple(frequency, consistencyScore, avgDeviation.roundToInt())
    }
}
