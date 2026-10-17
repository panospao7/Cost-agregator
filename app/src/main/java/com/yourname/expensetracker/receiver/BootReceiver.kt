package com.yourname.expensetracker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.yourname.expensetracker.data.database.DatabaseOpenGate
import com.yourname.expensetracker.service.DataSyncFgsTimeoutGuard
import com.yourname.expensetracker.service.NotificationCaptureService
import timber.log.Timber

/**
 * Broadcast receiver that starts [NotificationCaptureService] after device
 * boot or package replacement.
 *
 * ## Privacy gate
 * This receiver does **not** perform a privacy gate check itself. The
 * [NotificationCaptureService] checks the [com.yourname.expensetracker.domain.privacy.PrivacyGate]
 * at runtime before processing any notification data. If the gate denies
 * capture, the service silently drops the notification without persisting
 * anything. This design avoids a blocking gate check during the broadcast
 * receiver's short execution window.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // R25: never start the capture service while the database open gate blocks the live DB.
        val gateReason = DatabaseOpenGate.blockedReasonOrNull(context)
        if (gateReason != null) {
            Timber.w("BootReceiver skipped: open gate blocked reason=%s", gateReason)
            return
        }
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            // Try to start the service
            // Note: On Android 8+, this may fail silently if the app hasn't been opened yet
            // The system will bind to NotificationListenerService when the user grants permission
            // and opens any app from the developer
            try {
                val guard = DataSyncFgsTimeoutGuard.from(context)
                // A fresh boot restarts the elapsed-realtime clock; drop the stale timestamp
                // so it cannot re-block once the new uptime passes it. Not done on
                // package replacement, which keeps the same boot. Also done when the start
                // is skipped below, so a stale timestamp cannot block a later foreground start.
                if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
                    guard.clear()
                }
                // R18c (V-I-6): Android 15 (API 35) forbids starting a dataSync foreground
                // service from BOOT_COMPLETED; attempting it throws, so skip it deliberately.
                if (!shouldStartOnBoot(intent.action, Build.VERSION.SDK_INT)) {
                    Timber.i("BOOT_FGS_START_SKIPPED_API35")
                    return
                }
                // RT-C1: skip promotion while a recorded dataSync FGS timeout is active.
                if (guard.isPromotionBlocked()) {
                    Timber.i("FGS_PROMOTION_SKIPPED")
                    return
                }
                // Logged only once the start is actually attempted (not on the skip paths above).
                Timber.d("Boot completed - attempting to start NotificationCaptureService")
                val serviceIntent = Intent(context, NotificationCaptureService::class.java)
                context.startForegroundService(serviceIntent)
            } catch (e: Exception) {
                // Bounded diagnostic only: class name, never the exception object or message.
                Timber.w("BOOT_FGS_START_FAILED class=%s", e::class.java.simpleName)
            }
        }
    }

    internal companion object {
        /** First API level that forbids starting a dataSync FGS from BOOT_COMPLETED (Android 15). */
        const val BOOT_FGS_RESTRICTED_FROM_SDK = 35

        /**
         * False only for BOOT_COMPLETED on API 35+. MY_PACKAGE_REPLACED is not restricted, and
         * lower API levels still start the service as before.
         */
        internal fun shouldStartOnBoot(action: String?, sdkInt: Int): Boolean =
            !(action == Intent.ACTION_BOOT_COMPLETED && sdkInt >= BOOT_FGS_RESTRICTED_FROM_SDK)
    }
}
