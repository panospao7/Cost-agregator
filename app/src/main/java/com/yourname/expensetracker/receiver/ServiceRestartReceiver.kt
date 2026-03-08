package com.yourname.expensetracker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yourname.expensetracker.service.NotificationCaptureService
import timber.log.Timber

class ServiceRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == NotificationCaptureService.ACTION_RESTART_SERVICE) {
            Timber.d("Restart alarm triggered")
            
            val serviceIntent = Intent(context, NotificationCaptureService::class.java)
            
            try {
                context.startForegroundService(serviceIntent)
                Timber.d("Service restart triggered successfully")
            } catch (e: Exception) {
                Timber.e(e, "Failed to restart service")
            }
        }
    }
}
