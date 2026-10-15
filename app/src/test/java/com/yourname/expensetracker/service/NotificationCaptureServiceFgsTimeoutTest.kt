package com.yourname.expensetracker.service

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * RT-C1: the OS delivers Service.onTimeout(int, int) once the dataSync foreground
 * service budget is exhausted (Android 15+). The service must demote itself,
 * remember the timeout, and never re-promote inside the guard window, otherwise the
 * platform raises ForegroundServiceDidNotStopInTimeException and kills the process.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class NotificationCaptureServiceFgsTimeoutTest {

    private lateinit var service: NotificationCaptureService

    @Before
    fun setUp() {
        RuntimeEnvironment.getApplication()
            .getSharedPreferences(DataSyncFgsTimeoutGuard.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        service = Robolectric.buildService(NotificationCaptureService::class.java).get()
    }

    private fun promote() {
        service.onStartCommand(Intent(), 0, 1)
    }

    @Test
    fun `promotes to foreground when no timeout was recorded`() {
        promote()

        assertNotNull(shadowOf(service).lastForegroundNotification)
    }

    @Test
    fun `onTimeout demotes removes notification records timeout and does not stop the service`() {
        promote()
        assertNotNull(shadowOf(service).lastForegroundNotification)

        service.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        val shadow = shadowOf(service)
        assertTrue(shadow.isForegroundStopped)
        assertTrue(shadow.notificationShouldRemoved)
        assertFalse("onTimeout must not stopSelf (listener stays bound)", shadow.isStoppedBySelf)
        assertTrue(DataSyncFgsTimeoutGuard.from(service).isPromotionBlocked())
    }

    @Test
    fun `no re-promotion while the timeout guard is active`() {
        service.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        promote()

        assertNull(shadowOf(service).lastForegroundNotification)
    }

    @Test
    fun `re-promotes after the 24 hour guard window`() {
        service.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        ShadowSystemClock.advanceBy(Duration.ofMillis(DataSyncFgsTimeoutGuard.BLOCK_WINDOW_MS + 1_000L))

        promote()

        assertFalse(DataSyncFgsTimeoutGuard.from(service).isPromotionBlocked())
        assertNotNull(shadowOf(service).lastForegroundNotification)
    }
}
