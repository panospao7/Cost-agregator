package com.yourname.expensetracker.domain.debug

import android.content.Context
import android.content.SharedPreferences
import com.yourname.expensetracker.domain.util.TimeProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServiceDiagnostics @Inject constructor(
    @ApplicationContext context: Context,
    private val timeProvider: TimeProvider
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Single synchronization owner for all counter writes and snapshot reads.
     * Prevents lost updates from concurrent read-modify-write cycles and
     * ensures [getStats] returns a consistent snapshot.
     */
    private val lock = Any()

    companion object {
        private const val PREFS_NAME = "service_diagnostics"
        private const val KEY_SERVICE_START_COUNT = "service_start_count"
        private const val KEY_SERVICE_KILLED_COUNT = "service_killed_count"
        private const val KEY_LISTENER_DISCONNECT_COUNT = "listener_disconnect_count"
        private const val KEY_LAST_RESTART_TIME = "last_restart_time"
        private const val KEY_LAST_KILL_TIME = "last_kill_time"
    }

    fun recordServiceStart() {
        synchronized(lock) {
            prefs.edit().apply {
                putInt(KEY_SERVICE_START_COUNT, prefs.getInt(KEY_SERVICE_START_COUNT, 0) + 1)
                putLong(KEY_LAST_RESTART_TIME, timeProvider.now())
            }.commit()
        }
    }

    fun recordServiceKilled() {
        synchronized(lock) {
            prefs.edit().apply {
                putInt(KEY_SERVICE_KILLED_COUNT, prefs.getInt(KEY_SERVICE_KILLED_COUNT, 0) + 1)
                putLong(KEY_LAST_KILL_TIME, timeProvider.now())
            }.commit()
        }
    }

    fun recordListenerDisconnected() {
        synchronized(lock) {
            prefs.edit().apply {
                putInt(KEY_LISTENER_DISCONNECT_COUNT, prefs.getInt(KEY_LISTENER_DISCONNECT_COUNT, 0) + 1)
            }.commit()
        }
    }

    fun getServiceStartCount(): Int = synchronized(lock) { prefs.getInt(KEY_SERVICE_START_COUNT, 0) }
    fun getServiceKilledCount(): Int = synchronized(lock) { prefs.getInt(KEY_SERVICE_KILLED_COUNT, 0) }
    fun getListenerDisconnectCount(): Int = synchronized(lock) { prefs.getInt(KEY_LISTENER_DISCONNECT_COUNT, 0) }
    fun getLastRestartTime(): Long = synchronized(lock) { prefs.getLong(KEY_LAST_RESTART_TIME, 0) }
    fun getLastKillTime(): Long = synchronized(lock) { prefs.getLong(KEY_LAST_KILL_TIME, 0) }

    fun resetStats() {
        synchronized(lock) {
            prefs.edit().clear().commit()
        }
    }

    data class Stats(
        val startCount: Int,
        val killedCount: Int,
        val disconnectCount: Int,
        val lastRestartTime: Long,
        val lastKillTime: Long
    )

    fun getStats(): Stats = synchronized(lock) {
        Stats(
            startCount = prefs.getInt(KEY_SERVICE_START_COUNT, 0),
            killedCount = prefs.getInt(KEY_SERVICE_KILLED_COUNT, 0),
            disconnectCount = prefs.getInt(KEY_LISTENER_DISCONNECT_COUNT, 0),
            lastRestartTime = prefs.getLong(KEY_LAST_RESTART_TIME, 0),
            lastKillTime = prefs.getLong(KEY_LAST_KILL_TIME, 0)
        )
    }
}
