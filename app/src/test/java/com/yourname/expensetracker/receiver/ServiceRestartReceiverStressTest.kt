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
class ServiceRestartReceiverStressTest {

    private val receiver = ServiceRestartReceiver()

    @Test
    fun `restarts capture service on restart action`() {
        val context = mockk<Context>(relaxed = true)
        every { context.startForegroundService(any()) } returns mockk<ComponentName>(relaxed = true)

        receiver.onReceive(context, Intent(NotificationCaptureService.ACTION_RESTART_SERVICE))

        verify(exactly = 1) {
            context.startForegroundService(
                match { it.component?.className == NotificationCaptureService::class.java.name }
            )
        }
    }

    @Test
    fun `ignores unrelated action`() {
        val context = mockk<Context>(relaxed = true)

        receiver.onReceive(context, Intent(Intent.ACTION_SCREEN_ON))

        verify(exactly = 0) { context.startForegroundService(any()) }
    }

    @Test
    fun `does not crash when restart startForegroundService fails`() {
        val context = mockk<Context>(relaxed = true)
        every { context.startForegroundService(any()) } throws RuntimeException("boom")

        receiver.onReceive(context, Intent(NotificationCaptureService.ACTION_RESTART_SERVICE))
    }
}
