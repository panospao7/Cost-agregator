package com.yourname.expensetracker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.yourname.expensetracker.service.NotificationCaptureService

class ServiceRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == NotificationCaptureService.ACTION_RESTART_SERVICE) {
            Log.d("ServiceRestart", "Restart alarm triggered")
            
            val serviceIntent = Intent(context, NotificationCaptureService::class.java)
            
            try {
                // Check if service is already running by trying to start it
                // START_STICKY will restart it if it was killed
                context.startForegroundService(serviceIntent)
                Log.d("ServiceRestart", "Service restart triggered successfully")
            } catch (e: Exception) {
                Log.e("ServiceRestart", "Failed to restart service", e)
            }
        }
    }
}
