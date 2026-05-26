package com.yourname.expensetracker.domain.reminder

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
        val calendar = java.util.Calendar.getInstance().apply { timeInMillis = epochMillis }
        val minuteOfDay = calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60 + calendar.get(java.util.Calendar.MINUTE)
        return if (quietHoursStartMinuteOfDay <= quietHoursEndMinuteOfDay) {
            minuteOfDay in quietHoursStartMinuteOfDay..quietHoursEndMinuteOfDay
        } else {
            // Overnight quiet hours (e.g., 22:00–08:00)
            minuteOfDay >= quietHoursStartMinuteOfDay || minuteOfDay <= quietHoursEndMinuteOfDay
        }
    }
}
