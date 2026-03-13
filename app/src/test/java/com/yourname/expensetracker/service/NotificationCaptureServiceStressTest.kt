package com.yourname.expensetracker.service

import android.os.Build
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stress tests for NotificationCaptureService.
 * Uses Robolectric for Android framework and Hilt for dependency injection.
 * Verifies crash-free behavior. Full StatusBarNotification tests require
 * instrumented tests due to constructor API differences across SDK levels.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P])
class NotificationCaptureServiceStressTest {

    @get:Rule(order = 0)
    var hiltRule = HiltAndroidRule(this)

    @Before
    fun setup() {
        hiltRule.inject()
    }

    @Test
    fun `stress - null sbn does not crash`() = runTest {
        val controller = Robolectric.buildService(NotificationCaptureService::class.java)
        val service = controller.create().get()
        service.onNotificationPosted(null)
    }

    @Test
    fun `stress - service creates without crash`() = runTest {
        val controller = Robolectric.buildService(NotificationCaptureService::class.java)
        val service = controller.create().get()
        assert(service != null)
    }
}
