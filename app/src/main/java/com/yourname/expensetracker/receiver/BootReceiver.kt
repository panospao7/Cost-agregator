package com.yourname.expensetracker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Timber.d("Boot completed - attempting to start NotificationCaptureService")
            
            // Try to start the service
            // Note: On Android 8+, this may fail silently if the app hasn't been opened yet
            // The system will bind to NotificationListenerService when the user grants permission
            // and opens any app from the developer
            try {
                val serviceIntent = Intent(context, NotificationCaptureService::class.java)
                context.startForegroundService(serviceIntent)
            } catch (e: Exception) {
                Timber.w(e, "Could not start service on boot - may need user interaction first")
            }
        }
    }
}
