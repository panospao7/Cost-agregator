package com.yourname.expensetracker.data.service

import android.content.Context
import android.content.ContextWrapper
import android.content.ContentResolver
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidNotificationListenerAccessCheckerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun setEnabledListeners(value: String?) {
        Settings.Secure.putString(context.contentResolver, ENABLED_LISTENERS_KEY, value)
    }

    @Test
    fun `returns true when our package is an enabled listener`() {
        setEnabledListeners(
            "com.other.app/com.other.app.Service:" +
                "${context.packageName}/com.yourname.expensetracker.service.NotificationCaptureService"
        )

        assertTrue(AndroidNotificationListenerAccessChecker(context).isListenerAccessGranted())
    }

    @Test
    fun `returns false when only other packages are enabled listeners`() {
        setEnabledListeners("com.other.app/com.other.app.Service")

        assertFalse(AndroidNotificationListenerAccessChecker(context).isListenerAccessGranted())
    }

    @Test
    fun `returns false when no listeners are enabled`() {
        setEnabledListeners(null)

        assertFalse(AndroidNotificationListenerAccessChecker(context).isListenerAccessGranted())
    }

    @Test
    fun `a revoked grant is reported as revoked on the next check`() {
        val checker = AndroidNotificationListenerAccessChecker(context)
        setEnabledListeners("${context.packageName}/com.yourname.expensetracker.service.NotificationCaptureService")
        assertTrue(checker.isListenerAccessGranted())

        setEnabledListeners("")
        assertFalse(checker.isListenerAccessGranted())

        setEnabledListeners(null)
        assertFalse(checker.isListenerAccessGranted())
    }

    @Test
    fun `fails closed when the platform lookup throws`() {
        val throwingContext = object : ContextWrapper(context) {
            override fun getContentResolver(): ContentResolver =
                throw IllegalStateException("simulated platform failure")
        }

        assertFalse(AndroidNotificationListenerAccessChecker(throwingContext).isListenerAccessGranted())
    }

    private companion object {
        const val ENABLED_LISTENERS_KEY = "enabled_notification_listeners"
    }
}
