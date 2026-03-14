package com.yourname.expensetracker.receiver

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.yourname.expensetracker.service.NotificationCaptureService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
}
