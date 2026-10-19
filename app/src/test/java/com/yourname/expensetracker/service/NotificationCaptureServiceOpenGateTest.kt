package com.yourname.expensetracker.service

import android.app.Service
import android.content.Intent
import android.service.notification.StatusBarNotification
import com.yourname.expensetracker.data.database.DatabaseOpenGate
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * R25 slice B1: with the database open gate blocked the capture service must not promote to the
 * foreground, must stop itself without restart, and must ignore listener callbacks. Injected
 * fields are never initialised in these tests (no Hilt component); any access would throw.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class NotificationCaptureServiceOpenGateTest {

    private lateinit var service: NotificationCaptureService

    @Before
    fun setUp() {
        DatabaseOpenGate.resetForTests()
        DatabaseOpenGate.overrideForTests(DatabaseOpenGate.REASON_SCHEMA_TOO_OLD)
        service = Robolectric.buildService(NotificationCaptureService::class.java).get()
    }

    @After
    fun tearDown() {
        DatabaseOpenGate.resetForTests()
    }

    @Test
    fun `onStartCommand returns START_NOT_STICKY stops self and does not promote when blocked`() {
        val result = service.onStartCommand(Intent(), 0, 1)

        assertEquals(Service.START_NOT_STICKY, result)
        val shadow = shadowOf(service)
        assertTrue(shadow.isStoppedBySelf)
        assertNull(shadow.lastForegroundNotification)
    }

    @Test
    fun `refresh action is not honoured when blocked`() {
        val result = service.onStartCommand(
            Intent(NotificationCaptureService.ACTION_REFRESH_NOTIFICATIONS),
            0,
            1
        )

        assertEquals(Service.START_NOT_STICKY, result)
        assertNull(shadowOf(service).lastForegroundNotification)
    }

    @Test
    fun `listener callbacks return early without touching injected dependencies when blocked`() {
        // Uninitialised lateinit fields would throw if any callback went past the gate.
        service.onListenerConnected()
        service.onListenerDisconnected()
        service.onNotificationPosted(mockk<StatusBarNotification>(relaxed = true))
        service.onTimeout(1, 0)

        assertFalse(shadowOf(service).isForegroundStopped)
        assertNull(shadowOf(service).lastForegroundNotification)
    }
}
