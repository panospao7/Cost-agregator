package com.yourname.expensetracker.domain.util

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Calendar

/**
 * Utility to standardize date range calculations across the app.
 * Replaces manual Calendar manipulation to prevent timezone/boundary bugs.
 */
object TimePeriodUtils {

    const val DAY_IN_MILLIS: Long = 24L * 60L * 60L * 1000L

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
     * getEndOfDay - Returns the start of the next day (exclusive upper bound).
     */
    fun getEndOfDay(timestamp: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = getStartOfDay(timestamp)
        cal.add(Calendar.DAY_OF_MONTH, 1)
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
        
        cal2.add(Calendar.DAY_OF_MONTH, -daysFromMonday)
        return cal2.timeInMillis
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
     * getEndOfMonth - Returns the start of the next month (exclusive upper bound).
     */
    fun getEndOfMonth(timestamp: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = getStartOfMonth(timestamp)
        cal.add(Calendar.MONTH, 1)
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
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        cal.add(Calendar.DAY_OF_MONTH, -days)
        val start = getStartOfDay(cal.timeInMillis)
        return start to now
    }
    /**
     * getWeekRange - Returns a pair of (start, end) timestamps for the current calendar week.
     * Week starts on Monday 00:00:00.000 and ends at next Monday 00:00:00.000 (exclusive).
     * @param timestamp Reference time to determine which week
     * @param weekOffset 0 for current week, -1 for previous week, etc.
     */
    fun getWeekRange(timestamp: Long, weekOffset: Int = 0): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp
        cal.firstDayOfWeek = Calendar.MONDAY
        
        // Apply week offset
        cal.add(Calendar.DAY_OF_MONTH, weekOffset * 7)
        
        // Calculate Monday of this week
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val daysFromMonday = if (dayOfWeek == Calendar.SUNDAY) 6 else dayOfWeek - Calendar.MONDAY
        cal.add(Calendar.DAY_OF_MONTH, -daysFromMonday)
        
        // Start of week: Monday 00:00:00.000
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val startMs = cal.timeInMillis
        
        // End of week: next Monday 00:00:00.000 (exclusive)
        cal.add(Calendar.DAY_OF_MONTH, 7)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val endMs = cal.timeInMillis
        
        return startMs to endMs
    }

    /**
     * getStartOfQuarter - Returns the start of the quarter containing the given timestamp.
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
     * getEndOfQuarter - Returns the start of the next quarter (exclusive upper bound).
     */
    fun getEndOfQuarter(timestamp: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = getStartOfQuarter(timestamp)
        cal.add(Calendar.MONTH, 3)
        return cal.timeInMillis
    }

    /**
     * getQuarterRange - Returns a pair of (start, end) timestamps for a quarter relative to current time.
     * @param timestamp Reference time
     * @param quarterOffset 0 for current quarter, -1 for previous quarter, etc.
     */
    fun getQuarterRange(timestamp: Long, quarterOffset: Int = 0): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp
        cal.add(Calendar.MONTH, quarterOffset * 3)
        
        val start = getStartOfQuarter(cal.timeInMillis)
        val end = getEndOfQuarter(cal.timeInMillis)
        return start to end
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
     * getEndOfYear - Returns the start of the next year (exclusive upper bound).
     */
    fun getEndOfYear(timestamp: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = getStartOfYear(timestamp)
        cal.add(Calendar.YEAR, 1)
        return cal.timeInMillis
    }

    /**
     * getYearRange - Returns a pair of (start, end) timestamps for a year relative to current time.
     * @param timestamp Reference time
     * @param yearOffset 0 for current year, -1 for previous year, etc.
     */
    fun getYearRange(timestamp: Long, yearOffset: Int = 0): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp
        cal.add(Calendar.YEAR, yearOffset)
        
        val start = getStartOfYear(cal.timeInMillis)
        val end = getEndOfYear(cal.timeInMillis)
        return start to end
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

    /**
     * getDayOfMonth - Returns the day of month (1-31) for a given timestamp.
     */
    fun getDayOfMonth(timestamp: Long): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        return cal.get(Calendar.DAY_OF_MONTH)
    }

    /**
     * getDaysInMonth - Returns the number of days in the month for a given timestamp.
     */
    fun getDaysInMonth(timestamp: Long): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        return cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    }

    /**
     * getDayIndexFromMonthStart - Returns the day index from start of month (0-based).
     */
    fun getDayIndexFromMonthStart(timestamp: Long): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        return (cal.get(Calendar.DAY_OF_MONTH) - 1).coerceAtLeast(0)
    }

    /**
     * isSameMonth - Checks if two timestamps are in the same month.
     */
    fun isSameMonth(timestamp1: Long, timestamp2: Long): Boolean {
        val cal1 = Calendar.getInstance().apply { timeInMillis = timestamp1 }
        val cal2 = Calendar.getInstance().apply { timeInMillis = timestamp2 }
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
               cal1.get(Calendar.MONTH) == cal2.get(Calendar.MONTH)
    }

    /**
     * addMonths - Adds specified months to a timestamp.
     */
    fun addMonths(timestamp: Long, months: Int): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        cal.add(Calendar.MONTH, months)
        return cal.timeInMillis
    }

    /**
     * addDays - Adds specified days to a timestamp.
     */
    fun addDays(timestamp: Long, days: Int): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        cal.add(Calendar.DAY_OF_MONTH, days)
        return cal.timeInMillis
    }

    /**
     * addYears - Adds specified years to a timestamp.
     */
    fun addYears(timestamp: Long, years: Int): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        cal.add(Calendar.YEAR, years)
        return cal.timeInMillis
    }

    /**
     * getYear - Returns calendar year for a timestamp.
     */
    fun getYear(timestamp: Long): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        return cal.get(Calendar.YEAR)
    }

    /**
     * getMonth - Returns calendar month for a timestamp (0=Jan, 11=Dec).
     */
    fun getMonth(timestamp: Long): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        return cal.get(Calendar.MONTH)
    }

    /**
     * getWeekOfYear - Returns week of year for a timestamp.
     */
    fun getWeekOfYear(timestamp: Long): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        return cal.get(Calendar.WEEK_OF_YEAR)
    }

    /**
     * getDayOfWeek - Returns day of week (Calendar constants: SUNDAY=1..SATURDAY=7).
     */
    fun getDayOfWeek(timestamp: Long): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        return cal.get(Calendar.DAY_OF_WEEK)
    }

    /**
     * getHourOfDay - Returns hour of day (0-23) for a timestamp.
     */
    fun getHourOfDay(timestamp: Long): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        return cal.get(Calendar.HOUR_OF_DAY)
    }

    /**
     * daysBetween - Calendar-day difference between two timestamps.
     * Time-of-day and DST differences are ignored by converting to LocalDate.
     */
    fun daysBetween(startTimestamp: Long, endTimestamp: Long): Int {
        val zone = ZoneId.systemDefault()
        val startDate = Instant.ofEpochMilli(startTimestamp).atZone(zone).toLocalDate()
        val endDate = Instant.ofEpochMilli(endTimestamp).atZone(zone).toLocalDate()
        return ChronoUnit.DAYS.between(startDate, endDate).toInt()
    }
}
