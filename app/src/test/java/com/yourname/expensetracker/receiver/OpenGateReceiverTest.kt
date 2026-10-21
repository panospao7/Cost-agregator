package com.yourname.expensetracker.receiver

import android.content.Context
import android.content.Intent
import com.yourname.expensetracker.data.database.DatabaseOpenGate
import com.yourname.expensetracker.service.NotificationCaptureService
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R25 slice B1: receivers must not start the capture service while the database open gate blocks
 * the live DB.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OpenGateReceiverTest {

    @Before
    fun setUp() {
        DatabaseOpenGate.resetForTests()
        DatabaseOpenGate.overrideForTests(DatabaseOpenGate.REASON_SCHEMA_TOO_NEW)
    }

    @After
    fun tearDown() {
        DatabaseOpenGate.resetForTests()
    }

    @Test
    fun `boot receiver does not start the service when the gate is blocked`() {
        val context = mockk<Context>(relaxed = true)

        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        BootReceiver().onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))

        verify(exactly = 0) { context.startForegroundService(any()) }
        verify(exactly = 0) { context.getSharedPreferences(any(), any()) }
    }

    @Test
    fun `service restart receiver does not restart the service when the gate is blocked`() {
        val context = mockk<Context>(relaxed = true)

        ServiceRestartReceiver().onReceive(
            context,
            Intent(NotificationCaptureService.ACTION_RESTART_SERVICE)
        )

        verify(exactly = 0) { context.startForegroundService(any()) }
        verify(exactly = 0) { context.getSharedPreferences(any(), any()) }
    }
}
