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

    // R18c (V-I-6): Android 15 forbids starting a dataSync FGS from BOOT_COMPLETED.
    // Robolectric 4.11.1 cannot run sdk 35, so the API 35 decision is a pure table test.
    @Test
    fun `shouldStartOnBoot is false only for boot completed on api 35 and above`() {
        val boot = Intent.ACTION_BOOT_COMPLETED
        val replaced = Intent.ACTION_MY_PACKAGE_REPLACED
        assertTrue(BootReceiver.shouldStartOnBoot(boot, 28))
        assertTrue(BootReceiver.shouldStartOnBoot(boot, 34))
        assertFalse(BootReceiver.shouldStartOnBoot(boot, 35))
        assertFalse(BootReceiver.shouldStartOnBoot(boot, 36))
        assertTrue(BootReceiver.shouldStartOnBoot(replaced, 34))
        assertTrue(BootReceiver.shouldStartOnBoot(replaced, 35))
        assertTrue(BootReceiver.shouldStartOnBoot(replaced, 36))
        assertTrue(BootReceiver.shouldStartOnBoot(null, 35))
        assertTrue(BootReceiver.shouldStartOnBoot(Intent.ACTION_TIME_CHANGED, 35))
    }

    @Test
    @Config(sdk = [34])
    fun `api 34 boot completed starts the capture service exactly once`() {
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
