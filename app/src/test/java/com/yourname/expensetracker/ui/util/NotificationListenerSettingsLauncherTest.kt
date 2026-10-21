package com.yourname.expensetracker.ui.util

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class NotificationListenerSettingsLauncherTest {

    private val packageName = "com.yourname.expensetracker"
    private val componentFlat = "com.yourname.expensetracker/com.yourname.expensetracker.service.NotificationCaptureService"

    private fun launch(
        sdkInt: Int,
        failActions: Set<String> = emptySet(),
        attempted: MutableList<Intent> = mutableListOf()
    ): Boolean = NotificationListenerSettingsLauncher.open(
        packageName = packageName,
        listenerComponentFlat = componentFlat,
        sdkInt = sdkInt,
        startActivity = { intent ->
            attempted.add(intent)
            if (intent.action in failActions) throw ActivityNotFoundException("simulated")
        }
    )

    @Test
    @Config(sdk = [26])
    fun `sdk 26 skips detail screen and opens the listener list first`() {
        val attempted = mutableListOf<Intent>()

        assertTrue(launch(sdkInt = 26, attempted = attempted))

        assertEquals(1, attempted.size)
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, attempted[0].action)
    }

    @Test
    @Config(sdk = [29])
    fun `sdk 29 skips detail screen and opens the listener list first`() {
        val attempted = mutableListOf<Intent>()

        assertTrue(launch(sdkInt = 29, attempted = attempted))

        assertEquals(listOf(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), attempted.map { it.action })
    }

    @Test
    @Config(sdk = [30])
    fun `sdk 30 opens the listener detail screen with the service component`() {
        val attempted = mutableListOf<Intent>()

        assertTrue(launch(sdkInt = 30, attempted = attempted))

        assertEquals(1, attempted.size)
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS, attempted[0].action)
        assertEquals(
            componentFlat,
            attempted[0].getStringExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME)
        )
    }

    @Test
    @Config(sdk = [34])
    fun `sdk 34 falls back in order when screens are missing`() {
        val attempted = mutableListOf<Intent>()

        val opened = launch(
            sdkInt = 34,
            failActions = setOf(
                Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS,
                Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
            ),
            attempted = attempted
        )

        assertTrue(opened)
        assertEquals(
            listOf(
                Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS,
                Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            ),
            attempted.map { it.action }
        )
        assertEquals("package:$packageName", attempted.last().data.toString())
    }

    @Test
    @Config(sdk = [34])
    fun `sdk 34 falls back to the listener list when only the detail screen is missing`() {
        val attempted = mutableListOf<Intent>()

        val opened = launch(
            sdkInt = 34,
            failActions = setOf(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS),
            attempted = attempted
        )

        assertTrue(opened)
        assertEquals(
            listOf(
                Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS,
                Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
            ),
            attempted.map { it.action }
        )
    }

    @Test
    @Config(sdk = [34])
    fun `sdk 34 falls back to the next intent when the first start throws SecurityException`() {
        val attempted = mutableListOf<Intent>()

        val opened = NotificationListenerSettingsLauncher.open(
            packageName = packageName,
            listenerComponentFlat = componentFlat,
            sdkInt = 34,
            startActivity = { intent ->
                attempted.add(intent)
                if (attempted.size == 1) throw SecurityException("simulated")
            }
        )

        assertTrue(opened)
        assertEquals(
            listOf(
                Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS,
                Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
            ),
            attempted.map { it.action }
        )
    }

    @Test
    @Config(sdk = [34])
    fun `returns false when every screen is missing`() {
        val attempted = mutableListOf<Intent>()

        val opened = launch(
            sdkInt = 34,
            failActions = setOf(
                Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS,
                Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            ),
            attempted = attempted
        )

        assertFalse(opened)
        assertEquals(3, attempted.size)
    }

    @Test
    @Config(sdk = [26])
    fun `returns false on sdk 26 when both remaining screens are missing`() {
        val attempted = mutableListOf<Intent>()

        val opened = launch(
            sdkInt = 26,
            failActions = setOf(
                Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            ),
            attempted = attempted
        )

        assertFalse(opened)
        assertEquals(2, attempted.size)
    }
}
