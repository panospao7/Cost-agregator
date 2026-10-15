package com.yourname.expensetracker.service

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock

/**
 * Remembers that the OS delivered a `dataSync` foreground-service timeout
 * (Android 15+, `Service.onTimeout(int, int)`) to [NotificationCaptureService].
 *
 * Re-promoting the service to a `dataSync` foreground service after the timeout
 * (for example from the 15 minute restart alarm) makes the platform throw
 * `ForegroundServiceDidNotStopInTimeException` and crash the process. While the
 * guard is active the service stays a plain bound NotificationListenerService
 * and no `startForegroundService` call is issued.
 *
 * The timestamp uses the elapsed-realtime clock, which restarts after a reboot.
 * A reboot only unblocks promotion implicitly until the new boot's uptime
 * reaches the stored value; after that the stale timestamp would block again.
 * Therefore [clear] must be called on a fresh boot (`BootReceiver` does this on
 * `ACTION_BOOT_COMPLETED`, not on package replacement, which keeps the same
 * boot). The window is [BLOCK_WINDOW_MS] (24 hours), matching the platform
 * dataSync budget reset.
 *
 * Only a timestamp is stored; no notification, OCR or receipt content.
 */
class DataSyncFgsTimeoutGuard(
    private val prefs: SharedPreferences,
    private val elapsedRealtimeMs: () -> Long = { SystemClock.elapsedRealtime() }
) {

    /** True when a dataSync timeout was recorded within the last 24 hours of this boot. */
    fun isPromotionBlocked(): Boolean {
        if (!prefs.contains(KEY_TIMEOUT_ELAPSED_MS)) return false
        val stored = prefs.getLong(KEY_TIMEOUT_ELAPSED_MS, 0L)
        val age = elapsedRealtimeMs() - stored
        return age in 0 until BLOCK_WINDOW_MS
    }

    /** Records that a dataSync timeout was just delivered. */
    fun recordTimeout() {
        // commit(): the process may be killed right after the platform timeout.
        prefs.edit().putLong(KEY_TIMEOUT_ELAPSED_MS, elapsedRealtimeMs()).commit()
    }

    /** Forgets a recorded timeout; called on a fresh boot when the elapsed-realtime clock restarted. */
    fun clear() {
        prefs.edit().remove(KEY_TIMEOUT_ELAPSED_MS).commit()
    }

    companion object {
        const val PREFS_NAME = "notification_capture_fgs_guard"
        const val KEY_TIMEOUT_ELAPSED_MS = "data_sync_timeout_elapsed_ms"
        const val BLOCK_WINDOW_MS = 24L * 60L * 60L * 1000L

        fun from(context: Context): DataSyncFgsTimeoutGuard =
            DataSyncFgsTimeoutGuard(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }
}
