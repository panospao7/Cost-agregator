package com.yourname.expensetracker.domain.util

import java.util.Calendar

object CalendarUtils {
    fun resetToStartOfDay(cal: Calendar): Calendar {
        return cal.apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }

    fun resetToEndOfDay(cal: Calendar): Calendar {
        return cal.apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }
    }

    fun getStartOfDay(timestamp: Long): Long {
        return Calendar.getInstance().apply { timeInMillis = timestamp }
            .let { resetToStartOfDay(it) }
            .timeInMillis
    }

    fun getEndOfDay(timestamp: Long): Long {
        return Calendar.getInstance().apply { timeInMillis = timestamp }
            .let { resetToEndOfDay(it) }
            .timeInMillis
    }

    /**
     * Reset calendar and set a new time in one operation.
     * Useful in loops to avoid creating multiple Calendar instances.
     */
    fun resetAndSetTime(cal: Calendar, timestamp: Long): Calendar {
        cal.timeInMillis = timestamp
        return cal
    }
}
