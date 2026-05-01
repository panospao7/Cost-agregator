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
) {
    init {
        require(merchantName.isNotBlank()) { "merchantName cannot be blank" }
        require(averageAmount.isFinite() && averageAmount > 0.0) { "averageAmount must be a positive finite number" }
        require(currency.isNotBlank()) { "currency cannot be blank" }
        require(periodVarianceDays >= 0) { "periodVarianceDays cannot be negative" }
        require(amountVariancePercent.isFinite() && amountVariancePercent >= 0.0) {
            "amountVariancePercent must be a non-negative finite number"
        }
        require(nextExpectedDate >= 0L) { "nextExpectedDate cannot be negative" }
        require(confidence.isFinite() && confidence in 0f..1f) { "confidence must be between 0 and 1" }
        require(previousDates.all { it >= 0L }) { "previousDates cannot contain negative values" }
    }
}

enum class RecurrenceFrequency {
    WEEKLY,
    BIWEEKLY,
    MONTHLY,
    QUARTERLY,
    SEMI_ANNUALLY,
    ANNUALLY,
    IRREGULAR;

    /**
     * Sentinel day count for backward compatibility only.
     * Prefer [fixedIntervalDays] or [calendarMonths].
     * The value 0 for [IRREGULAR] and 30 for [MONTHLY] are approximate
     * and should not be used for calendar arithmetic.
     */
    @Deprecated(
        message = "Use fixedIntervalDays/calendarMonths/isIrregular helpers instead of sentinel day counts.",
        replaceWith = ReplaceWith("fixedIntervalDays ?: 0")
    )
    val days: Int
        get() = when (this) {
            WEEKLY -> 7
            BIWEEKLY -> 14
            MONTHLY -> 30
            QUARTERLY -> 90
            SEMI_ANNUALLY -> 180
            ANNUALLY -> 365
            IRREGULAR -> 0
        }

    val fixedIntervalDays: Int?
        get() = when (this) {
            WEEKLY -> 7
            BIWEEKLY -> 14
            else -> null
        }

    val calendarMonths: Int?
        get() = when (this) {
            MONTHLY -> 1
            QUARTERLY -> 3
            SEMI_ANNUALLY -> 6
            ANNUALLY -> 12
            else -> null
        }

    val isIrregular: Boolean
        get() = this == IRREGULAR

    @Deprecated(
        message = "Use fixedIntervalDays/calendarMonths/isIrregular and RecurrenceCalculator helpers.",
        replaceWith = ReplaceWith("fixedIntervalDays?.toLong()?.times(86_400_000L)")
    )
    val intervalInMs: Long?
        get() = fixedIntervalDays?.toLong()?.times(86_400_000L)
}
