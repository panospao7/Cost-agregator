package com.yourname.expensetracker.ui.util

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.yourname.expensetracker.service.NotificationCaptureService

/**
 * R17: opens the best available Settings screen for granting notification-listener access.
 *
 * Chain: (API 30+) listener detail screen for our service, then the listener list, then
 * the app-details screen. Returns false when no screen could be opened so the caller can
 * show a fallback message. It never changes privacy settings.
 */
object NotificationListenerSettingsLauncher {

    fun open(context: Context): Boolean {
        val appContext = context.applicationContext
        val component = ComponentName(appContext, NotificationCaptureService::class.java)
        return open(
            packageName = appContext.packageName,
            listenerComponentFlat = component.flattenToString(),
            sdkInt = Build.VERSION.SDK_INT,
            startActivity = { intent ->
                if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        )
    }

    @SuppressLint("InlinedApi")
    internal fun open(
        packageName: String,
        listenerComponentFlat: String,
        sdkInt: Int,
        startActivity: (Intent) -> Unit
    ): Boolean {
        val candidates = buildList {
            if (sdkInt >= Build.VERSION_CODES.R) {
                add(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                        Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        listenerComponentFlat
                    )
                )
            }
            add(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            add(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", packageName, null))
            )
        }
        for (intent in candidates) {
            try {
                startActivity(intent)
                return true
            } catch (_: ActivityNotFoundException) {
                // Try the next fallback.
            } catch (_: SecurityException) {
                // Treat a blocked intent the same as a missing handler.
            }
        }
        return false
    }
}
