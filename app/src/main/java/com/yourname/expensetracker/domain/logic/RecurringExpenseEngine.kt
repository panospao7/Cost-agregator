package com.yourname.expensetracker.domain.logic

import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.RecurringExpenseDao
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.model.RecurringPattern
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

@Singleton
class RecurringExpenseEngine @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val recurringExpenseDao: RecurringExpenseDao
) {

    /**
     * Analyze all expenses to find recurring patterns and merge with manual overrides.
     * Returns a list of patterns sorted by confidence (Manual = 1.0).
     */
    suspend fun getPatterns(): List<RecurringPattern> {
        // 1. Fetch Manual Overrides
        val manualExpenses = recurringExpenseDao.getAll()
        val manualMap = manualExpenses.associateBy { it.merchant.lowercase() }

        // 2. Fetch all expenses for detection
        val allExpenses = expenseDao.getAll()
        
        // Group by normalized merchant name
        val grouped = allExpenses.groupBy { it.merchant }

        val detectedPatterns = mutableListOf<RecurringPattern>()

        for ((merchant, expenses) in grouped) {
            // If we already have a manual rule for this merchant, skip detection
            if (manualMap.containsKey(merchant.lowercase())) continue

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

            // If amount varies by more than 20%, likely not a fixed subscription/bill
            if (amountVariance > 0.20) continue 

            // 2. Interval Analysis
            val dates = sorted.map { it.date }
            val intervals = calculateIntervals(dates)
            
            val (frequency, confidence, varianceDays) = determineFrequency(intervals)

            // Thresholds: Must be a known frequency and have > 60% confidence (LOG-013 Relaxed from 65%)
            if (frequency != RecurrenceFrequency.IRREGULAR && confidence > 0.60) {
                
                // Predict next date
                // Predict next date (LOG-021 Fix: Use Calendar for proper Month/Year addition)
                val cal = java.util.Calendar.getInstance()
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
                        merchantName = merchant,
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
        if (values.size < 2) return 0.0
        val mean = values.average()
        val sumSq = values.sumOf { (it - mean) * (it - mean) }
        return sqrt(sumSq / (values.size - 1))
    }

    private fun determineFrequency(intervalsMs: List<Long>): Triple<RecurrenceFrequency, Double, Int> {
        if (intervalsMs.isEmpty()) return Triple(RecurrenceFrequency.IRREGULAR, 0.0, 0)
        
        // Convert ms to days (round to nearest integer day)
        val intervalsDays = intervalsMs.map { (it / 86_400_000.0).roundToInt() }
        
        // Find Mode (most common interval)
        val frequencyMap = intervalsDays.groupingBy { it }.eachCount()
        val modeEntry = frequencyMap.maxByOrNull { it.value } 
            ?: return Triple(RecurrenceFrequency.IRREGULAR, 0.0, 0)
            
        val mode = modeEntry.key
        
        // Map mode to known frequencies with tolerance
        val frequency = when (mode) {
             in 6..8 -> RecurrenceFrequency.WEEKLY
             in 13..15 -> RecurrenceFrequency.BIWEEKLY
             in 28..31 -> RecurrenceFrequency.MONTHLY // Covers 28, 30, 31 day months
             in 85..95 -> RecurrenceFrequency.QUARTERLY
             in 175..190 -> RecurrenceFrequency.SEMI_ANNUALLY
             in 360..370 -> RecurrenceFrequency.ANNUALLY
             else -> RecurrenceFrequency.IRREGULAR
        }

        if (frequency == RecurrenceFrequency.IRREGULAR) {
            return Triple(RecurrenceFrequency.IRREGULAR, 0.0, 0)
        }

        // Calculate Confidence
        // Score based on how many intervals are "close" to the mode (within ±10% or ±1 day)
        val tolerance = (mode * 0.1).coerceAtLeast(1.0)
        val matchingIntervals = intervalsDays.count { abs(it - mode) <= tolerance }
        val consistencyScore = matchingIntervals.toDouble() / intervalsDays.size

        // Calculate Average Deviation (days)
        val deviations = intervalsDays.map { abs(it - mode) }
        val avgDeviation = deviations.average()

        return Triple(frequency, consistencyScore, avgDeviation.roundToInt())
    }
}
