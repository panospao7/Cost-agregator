package com.yourname.expensetracker.ui.util

import com.yourname.expensetracker.domain.util.TimeProvider
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Time/date utilities for the UI layer.
 *
 * Rule: No composable should use Calendar.getInstance(), LocalDate.now(),
 * or System.currentTimeMillis() directly. Use these helpers with injected TimeProvider.
 *
 * This ensures:
 * 1. Tests can use fixed time
 * 2. All screens agree on "now"
 * 3. Timezone handling is explicit
 */
object UiTimeUtils {

    /**
     * Get start-of-day millis for a given timestamp.
     */
    fun startOfDay(millis: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        return Instant.ofEpochMilli(millis)
            .atZone(zone)
            .toLocalDate()
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
    }

    /**
     * Get end-of-day millis (start of next day) for a given timestamp.
     */
    fun endOfDay(millis: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        return Instant.ofEpochMilli(millis)
            .atZone(zone)
            .toLocalDate()
            .plusDays(1)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
    }

    /**
     * Get the year from a timestamp.
     */
    fun getYear(millis: Long, zone: ZoneId = ZoneId.systemDefault()): Int {
        return Instant.ofEpochMilli(millis).atZone(zone).year
    }

    /**
     * Get day range (startOfDay to startOfNextDay) for navigation filters.
     */
    fun dayRange(millis: Long, zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> {
        val start = startOfDay(millis, zone)
        val end = endOfDay(millis, zone)
        return start to end
    }
}
