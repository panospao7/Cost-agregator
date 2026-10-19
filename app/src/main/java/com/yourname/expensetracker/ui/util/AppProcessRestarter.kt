package com.yourname.expensetracker.ui.util

import android.content.Context
import android.content.Intent

/**
 * R27-S3: relaunches the launcher activity in a fresh task and then terminates the
 * current process. Used after a successful restore so the old Room singleton can
 * never silently reopen at the live database path.
 *
 * No permission, manifest entry or AlarmManager is involved. If no launch intent
 * can be resolved the process is still exited (the user reopens manually), which
 * matches the previous "Restart Now" behaviour.
 *
 * [exitProcess] is injectable for tests only; production uses the default.
 */
fun restartApp(
    context: Context,
    exitProcess: () -> Unit = { Runtime.getRuntime().exit(0) }
) {
    val launchComponent = context.packageManager
        .getLaunchIntentForPackage(context.packageName)
        ?.component
    if (launchComponent != null) {
        val restartIntent = Intent.makeRestartActivityTask(launchComponent)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(restartIntent)
    }
    exitProcess()
}
