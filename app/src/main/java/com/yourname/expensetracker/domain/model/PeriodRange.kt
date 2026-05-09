package com.yourname.expensetracker.domain.model

// M05 PARTIAL: Use domain.core.time.PeriodRange for new code.
// This type is deprecated and will be removed once all callers migrate.
@Deprecated(
    "Use com.yourname.expensetracker.domain.core.time.PeriodRange instead. " +
    "core.time.PeriodRange provides zone-aware, half-open [startInclusive, endExclusive) semantics.",
    ReplaceWith("com.yourname.expensetracker.domain.core.time.PeriodRange")
)
data class PeriodRange(
    val start: Long,
    val end: Long
) {
    init {
        require(end >= start) { "end must be greater than or equal to start" }
    }

    fun contains(date: Long): Boolean = date in start until end
    
    val duration: Long get() = end - start
}
