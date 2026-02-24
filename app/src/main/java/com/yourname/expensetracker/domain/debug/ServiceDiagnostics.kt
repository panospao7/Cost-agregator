package com.yourname.expensetracker.domain.debug

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServiceDiagnostics @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "service_diagnostics"
        private const val KEY_SERVICE_START_COUNT = "service_start_count"
        private const val KEY_SERVICE_KILLED_COUNT = "service_killed_count"
        private const val KEY_LISTENER_DISCONNECT_COUNT = "listener_disconnect_count"
        private const val KEY_LAST_RESTART_TIME = "last_restart_time"
        private const val KEY_LAST_KILL_TIME = "last_kill_time"
    }

    fun recordServiceStart() {
        prefs.edit().apply {
            putInt(KEY_SERVICE_START_COUNT, getServiceStartCount() + 1)
            putLong(KEY_LAST_RESTART_TIME, System.currentTimeMillis())
            apply()
        }
    }

    fun recordServiceKilled() {
        prefs.edit().apply {
            putInt(KEY_SERVICE_KILLED_COUNT, getServiceKilledCount() + 1)
            putLong(KEY_LAST_KILL_TIME, System.currentTimeMillis())
            apply()
        }
    }

    fun recordListenerDisconnected() {
        prefs.edit().apply {
            putInt(KEY_LISTENER_DISCONNECT_COUNT, getListenerDisconnectCount() + 1)
            apply()
        }
    }

    fun getServiceStartCount(): Int = prefs.getInt(KEY_SERVICE_START_COUNT, 0)
    fun getServiceKilledCount(): Int = prefs.getInt(KEY_SERVICE_KILLED_COUNT, 0)
    fun getListenerDisconnectCount(): Int = prefs.getInt(KEY_LISTENER_DISCONNECT_COUNT, 0)
    fun getLastRestartTime(): Long = prefs.getLong(KEY_LAST_RESTART_TIME, 0)
    fun getLastKillTime(): Long = prefs.getLong(KEY_LAST_KILL_TIME, 0)

    fun resetStats() {
        prefs.edit().clear().apply()
    }

    data class Stats(
        val startCount: Int,
        val killedCount: Int,
        val disconnectCount: Int,
        val lastRestartTime: Long,
        val lastKillTime: Long
    )

    fun getStats(): Stats = Stats(
        startCount = getServiceStartCount(),
        killedCount = getServiceKilledCount(),
        disconnectCount = getListenerDisconnectCount(),
        lastRestartTime = getLastRestartTime(),
        lastKillTime = getLastKillTime()
    )
}
