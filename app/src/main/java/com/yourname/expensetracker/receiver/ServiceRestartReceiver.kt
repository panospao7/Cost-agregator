package com.yourname.expensetracker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yourname.expensetracker.data.database.DatabaseOpenGate
import com.yourname.expensetracker.service.DataSyncFgsTimeoutGuard
import com.yourname.expensetracker.service.NotificationCaptureService
import timber.log.Timber

class ServiceRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // R25: never restart the capture service while the database open gate blocks the live DB.
        val gateReason = DatabaseOpenGate.blockedReasonOrNull(context)
        if (gateReason != null) {
            Timber.w("ServiceRestartReceiver skipped: open gate blocked reason=%s", gateReason)
            return
        }
        if (intent.action == NotificationCaptureService.ACTION_RESTART_SERVICE) {
            Timber.d("Restart alarm triggered")
            
            val serviceIntent = Intent(context, NotificationCaptureService::class.java)
            
            try {
                // RT-C1: a dataSync FGS timeout was recorded; promoting again would
                // crash the process. The bound listener keeps capturing without it.
                if (DataSyncFgsTimeoutGuard.from(context).isPromotionBlocked()) {
                    Timber.i("FGS_PROMOTION_SKIPPED")
                    return
                }
                context.startForegroundService(serviceIntent)
                Timber.d("Service restart triggered successfully")
            } catch (e: Exception) {
                Timber.e(e, "Failed to restart service")
            }
        }
    }
}
