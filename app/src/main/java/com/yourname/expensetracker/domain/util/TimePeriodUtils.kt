package com.yourname.expensetracker.domain.util

import java.util.Calendar
import java.util.TimeZone

/**
 * Utility to standardize date range calculations across the app.
 * Replaces manual Calendar manipulation to prevent timezone/boundary bugs.
 */
object TimePeriodUtils {

    /**
     * getStartOfDay - Returns the start of the day (00:00:00.000) for a given timestamp.
     * Uses system default timezone.
     */
    fun getStartOfDay(timestamp: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /**
     * getEndOfDay - Returns the end of the day (23:59:59.999) for a given timestamp.
     */
    fun getEndOfDay(timestamp: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        return cal.timeInMillis
    }

    /**
     * getStartOfWeek - Returns the start of the week (Monday 00:00:00.000) for a given timestamp.
     * Adjusts if current day is Sunday (treats Sunday as last day of week).
     */
    fun getStartOfWeek(timestamp: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp
        cal.firstDayOfWeek = Calendar.MONDAY
        
        // Reset to start of day first
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        // Set to Monday of current week
        cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        
        // If we are currently Sunday, Calendar might jump to next Monday depending on locale settings.
        // But with standard US locale, MONDAY is day 2. SUNDAY is day 1. 
        // If today is Sunday (1), setting DAY_OF_WEEK to Monday (2) might jump forward.
        // Safer approach: calculate delta
        
        // Re-do with delta logic which is robust
        val cal2 = Calendar.getInstance()
        cal2.timeInMillis = timestamp
        cal2.set(Calendar.HOUR_OF_DAY, 0)
        cal2.set(Calendar.MINUTE, 0)
        cal2.set(Calendar.SECOND, 0)
        cal2.set(Calendar.MILLISECOND, 0)
        
        val dayOfWeek = cal2.get(Calendar.DAY_OF_WEEK) // Sun=1, Mon=2...
        val daysFromMonday = if (dayOfWeek == Calendar.SUNDAY) 6 else dayOfWeek - Calendar.MONDAY
        
        return cal2.timeInMillis - (daysFromMonday * 86400000L)
    }

    /**
     * getStartOfMonth - Returns the start of the month (1st, 00:00:00.000) for a given timestamp.
     */
    fun getStartOfMonth(timestamp: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /**
     * getEndOfMonth - Returns the end of the month (Last Day, 23:59:59.999) for a given timestamp.
     */
    fun getEndOfMonth(timestamp: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp
        cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        return cal.timeInMillis
    }

    /**
     * getMonthRange - Returns a pair of (start, end) timestamps for a month relative to current time.
     * @param monthOffset 0 for current month, -1 for previous month, etc.
     */
    /**
     * getMonthRange - Returns a pair of (start, end) timestamps for a month relative to current time.
     * @param timestamp Reference time
     * @param monthOffset 0 for current month, -1 for previous month, etc.
     */
    fun getMonthRange(timestamp: Long, monthOffset: Int = 0): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp
        cal.add(Calendar.MONTH, monthOffset)
        
        val start = getStartOfMonth(cal.timeInMillis)
        val end = getEndOfMonth(cal.timeInMillis)
        return start to end
    }

    /**
     * getLastNDaysRange - Returns a pair of (start, end) timestamps for the last N days.
     * @param now Current timestamp (pass timeProvider.now())
     * @param days Number of days to look back
     */
    fun getLastNDaysRange(now: Long, days: Int): Pair<Long, Long> {
        val start = getStartOfDay(now - (days * 86400000L))
        return start to now
    }
    /**
     * getStartOfQuarter - Returns the start of the quarter (1st of Jan, Apr, Jul, Oct)
     */
    fun getStartOfQuarter(timestamp: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp
        val month = cal.get(Calendar.MONTH)
        val quarterStartMonth = (month / 3) * 3
        cal.set(Calendar.MONTH, quarterStartMonth)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /**
     * getEndOfQuarter - Returns the end of the quarter
     */
    fun getEndOfQuarter(timestamp: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = getStartOfQuarter(timestamp)
        cal.add(Calendar.MONTH, 3)
        cal.add(Calendar.MILLISECOND, -1)
        return cal.timeInMillis
    }

    /**
     * getStartOfYear - Returns the start of the year (Jan 1st)
     */
    fun getStartOfYear(timestamp: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp
        cal.set(Calendar.MONTH, Calendar.JANUARY)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /**
     * getEndOfYear - Returns the end of the year (Dec 31st)
     */
    fun getEndOfYear(timestamp: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp
        cal.set(Calendar.MONTH, Calendar.DECEMBER)
        cal.set(Calendar.DAY_OF_MONTH, 31)
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        return cal.timeInMillis
    }

    /**
     * getDaysRemainingInMonth - Returns the number of days remaining in the current month.
     */
    fun getDaysRemainingInMonth(timestamp: Long): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val dayOfMonth = cal.get(Calendar.DAY_OF_MONTH)
        return daysInMonth - dayOfMonth
    }
}
