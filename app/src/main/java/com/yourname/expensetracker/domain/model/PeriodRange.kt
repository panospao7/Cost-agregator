package com.yourname.expensetracker.domain.model

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
