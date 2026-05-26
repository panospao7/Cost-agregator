package com.yourname.expensetracker.domain.reminder

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for bill reminder runtime settings.
 * Backed by SharedPreferences for simplicity; migrate to DataStore later.
 */
interface BillReminderSettingsRepository {
    suspend fun getSnapshot(): BillReminderSettings
    suspend fun setBillRemindersEnabled(enabled: Boolean)
    suspend fun setQuietHoursEnabled(enabled: Boolean, startMinute: Int, endMinute: Int)
}

@Singleton
class BillReminderSettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : BillReminderSettingsRepository {
    private val prefs: SharedPreferences
        get() = context.getSharedPreferences("bill_reminder_settings", Context.MODE_PRIVATE)

    override suspend fun getSnapshot(): BillReminderSettings {
        val p = prefs
        return BillReminderSettings(
            billRemindersEnabled = p.getBoolean("billRemindersEnabled", true),
            quietHoursEnabled = p.getBoolean("quietHoursEnabled", false),
            quietHoursStartMinuteOfDay = p.getInt("quietHoursStartMinuteOfDay", 22 * 60),
            quietHoursEndMinuteOfDay = p.getInt("quietHoursEndMinuteOfDay", 8 * 60)
        )
    }

    override suspend fun setBillRemindersEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("billRemindersEnabled", enabled).apply()
    }

    override suspend fun setQuietHoursEnabled(enabled: Boolean, startMinute: Int, endMinute: Int) {
        prefs.edit()
            .putBoolean("quietHoursEnabled", enabled)
            .putInt("quietHoursStartMinuteOfDay", startMinute)
            .putInt("quietHoursEndMinuteOfDay", endMinute)
            .apply()
    }
}
