package com.yourname.expensetracker.domain.model

import java.time.LocalDate

data class RecurringPattern(
    val merchantName: String,
    val averageAmount: Double,
    val currency: String,
    val frequency: RecurrenceFrequency,
    val periodVarianceDays: Int, // e.g. ±2 days
    val amountVariancePercent: Double, // e.g. 0.05 (5%)
    val nextExpectedDate: Long, // Epoch millis
    val confidence: Float, // 0.0 - 1.0
    val previousDates: List<Long>, // For debugging/UI visualization
    val categoryId: Long? = null,
    val id: Long? = null // ID of the underlying RecurringExpense rule, if any
)

enum class RecurrenceFrequency(val days: Int) {
    WEEKLY(7),
    BIWEEKLY(14),
    MONTHLY(30),
    QUARTERLY(90),
    SEMI_ANNUALLY(180),
    ANNUALLY(365),
    IRREGULAR(0);

    val intervalInMs: Long
        get() = days * 86_400_000L
}
