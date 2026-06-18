package com.yourname.expensetracker.data.service

import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.domain.service.NotificationService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AndroidNotificationServiceTest {

    private lateinit var baseContext: Context
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setup() {
        baseContext = ApplicationProvider.getApplicationContext()
        notificationManager = mockk(relaxed = true)
    }

    @Test
    fun `sendAiBriefingReady returns not delivered when notifications are disabled`() {
        every { notificationManager.areNotificationsEnabled() } returns false

        val service = AndroidNotificationService(testContext())

        val result = service.sendAiBriefingReadyWithResult(
            notificationId = 42,
            title = "Your AI briefing is ready",
            message = "Spending is calm today.",
            targetKey = "dashboard_home:2026-03-17"
        )

        assertEquals(NotificationService.DeliveryResult.NOT_DELIVERED, result)
        verify(exactly = 0) { notificationManager.notify(any(), any()) }
    }

    @Test
    fun `sendAiBriefingReady returns delivered after dispatch`() {
        every { notificationManager.areNotificationsEnabled() } returns true

        val service = AndroidNotificationService(testContext())

        val result = service.sendAiBriefingReadyWithResult(
            notificationId = 42,
            title = "Your AI briefing is ready",
            message = "Spending is calm today.",
            targetKey = "dashboard_home:2026-03-17"
        )

        assertEquals(NotificationService.DeliveryResult.DELIVERED, result)
        verify(exactly = 1) { notificationManager.notify(42, any()) }
    }

    private fun testContext(): Context {
        return object : ContextWrapper(baseContext) {
            override fun getSystemService(name: String): Any? {
                return if (name == Context.NOTIFICATION_SERVICE) {
                    notificationManager
                } else {
                    super.getSystemService(name)
                }
            }
        }
    }
}
