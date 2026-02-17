package com.yourname.expensetracker.domain.model

data class PeriodRange(
    val start: Long,
    val end: Long
) {
    fun contains(date: Long): Boolean = date in start until end
    
    val duration: Long get() = end - start
}
