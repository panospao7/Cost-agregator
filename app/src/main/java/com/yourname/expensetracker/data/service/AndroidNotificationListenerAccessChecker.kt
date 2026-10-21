package com.yourname.expensetracker.data.service

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.yourname.expensetracker.domain.notification.capture.NotificationListenerAccessChecker
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * R17: Android-backed [NotificationListenerAccessChecker]. Reads the system
 * `enabled_notification_listeners` secure setting directly on every call (no caching, so a
 * revoked grant is never reported as still active) and checks whether one of the enabled
 * components belongs to this package. Fails closed (false) on a missing setting or any runtime
 * error; only a bounded reason code and the exception class name are logged.
 */
@Singleton
class AndroidNotificationListenerAccessChecker @Inject constructor(
    @ApplicationContext private val context: Context
) : NotificationListenerAccessChecker {

    override fun isListenerAccessGranted(): Boolean = try {
        val flattened = Settings.Secure.getString(context.contentResolver, ENABLED_LISTENERS_SETTING)
        if (flattened.isNullOrBlank()) {
            false
        } else {
            flattened.split(':').any { entry ->
                ComponentName.unflattenFromString(entry)?.packageName == context.packageName
            }
        }
    } catch (e: RuntimeException) {
        Timber.w("%s: %s", REASON_CHECK_FAILED, e.javaClass.simpleName)
        false
    }

    companion object {
        const val REASON_CHECK_FAILED = "NOTIFICATION_LISTENER_ACCESS_CHECK_FAILED"

        /** Same secure-settings key NotificationManagerCompat reads (stable since API 18). */
        private const val ENABLED_LISTENERS_SETTING = "enabled_notification_listeners"
    }
}
