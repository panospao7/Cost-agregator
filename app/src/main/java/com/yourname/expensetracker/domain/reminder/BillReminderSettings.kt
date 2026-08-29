package com.yourname.expensetracker.domain.reminder

import java.time.Instant
import java.time.ZoneId

/**
 * Runtime settings controlling bill reminder dispatch.
 * Workers read this before querying or claiming reminder deliveries.
 */
data class BillReminderSettings(
    val billRemindersEnabled: Boolean = true,
    val defaultReminderWindows: List<String> = listOf("3_DAYS_BEFORE", "DUE_DAY", "OVERDUE"),
    val quietHoursEnabled: Boolean = false,
    val quietHoursStartMinuteOfDay: Int = 22 * 60, // 22:00
    val quietHoursEndMinuteOfDay: Int = 8 * 60     // 08:00
) {
    fun isWithinQuietHours(epochMillis: Long): Boolean {
        if (!quietHoursEnabled) return false
        // G-TIME-01: pure derivation from the [epochMillis] parameter (java.time,
        // system default timezone — same minute-of-day the Calendar produced).
        val zoned = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
        val minuteOfDay = zoned.hour * 60 + zoned.minute
        return if (quietHoursStartMinuteOfDay <= quietHoursEndMinuteOfDay) {
            minuteOfDay in quietHoursStartMinuteOfDay..quietHoursEndMinuteOfDay
        } else {
            // Overnight quiet hours (e.g., 22:00–08:00)
            minuteOfDay >= quietHoursStartMinuteOfDay || minuteOfDay <= quietHoursEndMinuteOfDay
        }
    }
}
