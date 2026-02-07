package com.yourname.expensetracker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.yourname.expensetracker.service.NotificationCaptureService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            // We can't start the service directly from background on Android 8+
            // But we can try to request a rebind if the component is enabled.
            // However, NotificationListenerService is special. The system binds to it.
            // This receiver mainly serves to ensure our process is woken up.
            
            // On some aggressive OSes, starting a foreground service or just 'being' alive
            // helps the system re-bind the listener.
            
            // For now, we'll just log/noop, as the critical piece is
            // android:enabled="true" in manifest and user toggle.
            // Extending this: we could schedule a WorkManager job here.
            Log.d("BootReceiver", "Boot completed - Service should be restarted by system or user interaction.")
        }
    }
}
