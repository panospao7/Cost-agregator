package com.yourname.expensetracker.ui.permissions

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * R24b: tiny persisted "already asked" flag for the notification-permission prompt.
 *
 * Follows the repo's existing SharedPreferences pattern for small UI/runtime flags (for example
 * BillReminderSettingsRepositoryImpl); no new DataStore file and no schema change.
 *
 * The flag is set when the in-app prompt is first SHOWN (not when the system result arrives), so
 * a process death or dismissal mid-prompt still counts as "asked once" and can never loop.
 */
@Singleton
class NotificationPromptStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasPrompted(): Boolean = prefs.getBoolean(KEY_PROMPTED, false)

    fun markPrompted() {
        prefs.edit().putBoolean(KEY_PROMPTED, true).apply()
    }

    companion object {
        const val PREFS_NAME = "notification_permission_prompt"
        const val KEY_PROMPTED = "notification_permission_prompted"
    }
}
