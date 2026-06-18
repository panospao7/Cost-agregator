package com.yourname.expensetracker.data.service

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.yourname.expensetracker.domain.workers.NotificationPermissionChecker
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android-backed [NotificationPermissionChecker] that delegates to
 * [NotificationManagerCompat.areNotificationsEnabled].
 */
@Singleton
class AndroidNotificationPermissionChecker @Inject constructor(
    @ApplicationContext private val context: Context
) : NotificationPermissionChecker {
    override fun areNotificationsEnabled(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()
}
