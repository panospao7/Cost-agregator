package com.yourname.expensetracker.ui.permissions

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** R24b: unwraps ContextWrapper chains to the hosting Activity, or null. */
tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}

/** R24b: opens this app's notification settings; falls back to app details. No payload is logged. */
fun openAppNotificationSettings(context: Context) {
    val primary = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(primary)
    } catch (_: ActivityNotFoundException) {
        openAppDetailsFallback(context)
    } catch (_: SecurityException) {
        // Treat a blocked intent the same as a missing handler.
        openAppDetailsFallback(context)
    }
}

private fun openAppDetailsFallback(context: Context) {
    val fallback = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(fallback)
    } catch (_: ActivityNotFoundException) {
        // No settings UI available; nothing further to do.
    } catch (_: SecurityException) {
        // Blocked intent; nothing further to do.
    }
}
