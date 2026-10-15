package com.yourname.expensetracker.receiver

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.yourname.expensetracker.service.DataSyncFgsTimeoutGuard
import com.yourname.expensetracker.service.NotificationCaptureService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BootReceiverStressTest {

    private val receiver = BootReceiver()

    @Test
    fun `starts capture service on boot completed`() {
        val context = mockk<Context>(relaxed = true)
        every { context.startForegroundService(any()) } returns mockk<ComponentName>(relaxed = true)

        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        verify(exactly = 1) {
            context.startForegroundService(
                match { it.component?.className == NotificationCaptureService::class.java.name }
            )
        }
    }

    @Test
    fun `starts capture service on package replaced`() {
        val context = mockk<Context>(relaxed = true)
        every { context.startForegroundService(any()) } returns mockk<ComponentName>(relaxed = true)

        receiver.onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))

        verify(exactly = 1) { context.startForegroundService(any()) }
    }

    @Test
    fun `ignores unrelated broadcast action`() {
        val context = mockk<Context>(relaxed = true)

        receiver.onReceive(context, Intent(Intent.ACTION_TIME_CHANGED))

        verify(exactly = 0) { context.startForegroundService(any()) }
    }

    @Test
    fun `does not crash when service start throws`() {
        val context = mockk<Context>(relaxed = true)
        every { context.startForegroundService(any()) } throws RuntimeException("boom")

        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
    }

    @Test
    fun `skips startForegroundService on package replaced while dataSync fgs timeout guard is active`() {
        val realPrefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences(DataSyncFgsTimeoutGuard.PREFS_NAME, Context.MODE_PRIVATE)
        realPrefs.edit().clear().commit()
        DataSyncFgsTimeoutGuard(realPrefs).recordTimeout()
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(DataSyncFgsTimeoutGuard.PREFS_NAME, any()) } returns realPrefs

        // BOOT_COMPLETED clears the guard by design (R14 iteration 1), so the blocked path is exercised via
        // MY_PACKAGE_REPLACED only; boot clearing is covered by the dedicated boot-completed test.
        receiver.onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))

        verify(exactly = 0) { context.startForegroundService(any()) }
        realPrefs.edit().clear().commit()
    }

    @Test
    fun `boot completed clears recorded timeout and starts foreground service`() {
        val realPrefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences(DataSyncFgsTimeoutGuard.PREFS_NAME, Context.MODE_PRIVATE)
        realPrefs.edit().clear().commit()
        DataSyncFgsTimeoutGuard(realPrefs).recordTimeout()
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(DataSyncFgsTimeoutGuard.PREFS_NAME, any()) } returns realPrefs
        every { context.startForegroundService(any()) } returns mockk<ComponentName>(relaxed = true)

        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertFalse(realPrefs.contains(DataSyncFgsTimeoutGuard.KEY_TIMEOUT_ELAPSED_MS))
        verify(exactly = 1) { context.startForegroundService(any()) }
        realPrefs.edit().clear().commit()
    }

    @Test
    fun `package replaced keeps recorded timeout and stays blocked`() {
        val realPrefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences(DataSyncFgsTimeoutGuard.PREFS_NAME, Context.MODE_PRIVATE)
        realPrefs.edit().clear().commit()
        DataSyncFgsTimeoutGuard(realPrefs).recordTimeout()
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(DataSyncFgsTimeoutGuard.PREFS_NAME, any()) } returns realPrefs

        receiver.onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))

        assertTrue(realPrefs.contains(DataSyncFgsTimeoutGuard.KEY_TIMEOUT_ELAPSED_MS))
        verify(exactly = 0) { context.startForegroundService(any()) }
        realPrefs.edit().clear().commit()
    }
}
