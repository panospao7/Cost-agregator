package com.yourname.expensetracker.data.service

import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.domain.notification.presentation.NotificationPresentationResolver
import com.yourname.expensetracker.domain.privacy.applock.AppLockPrivacySettings
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsLoadState
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsRepository
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsWriteResult
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticsEmitter
import com.yourname.expensetracker.domain.service.NotificationService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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

        val service = newService(testContext())

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

        val service = newService(testContext())

        val result = service.sendAiBriefingReadyWithResult(
            notificationId = 42,
            title = "Your AI briefing is ready",
            message = "Spending is calm today.",
            targetKey = "dashboard_home:2026-03-17"
        )

        assertEquals(NotificationService.DeliveryResult.DELIVERED, result)
        verify(exactly = 1) { notificationManager.notify(42, any()) }
    }

    /** Fake settings store (same shape as the S4 resolver test fake); the legacy methods never read it. */
    private class FakeSettingsRepository : AppLockSettingsRepository {
        override suspend fun getLoadState(): AppLockSettingsLoadState =
            AppLockSettingsLoadState.Loaded(AppLockPrivacySettings(discreetNotifications = false))

        override fun observeLoadState(): Flow<AppLockSettingsLoadState> = emptyFlow()

        override suspend fun setDiscreetNotifications(enabled: Boolean): AppLockSettingsWriteResult =
            error("not used by the notification service")

        override suspend fun reviewSave(record: AppLockPrivacySettings): AppLockSettingsWriteResult =
            error("not used by the notification service")
    }

    private fun newService(context: Context): AndroidNotificationService {
        val resolver = NotificationPresentationResolver(
            FakeSettingsRepository(),
            mockk<PrivacyDiagnosticsEmitter>(relaxed = true),
            CoroutineScope(Job())
        )
        val applier = NotificationPresentationApplier(context, NotificationPresentationTexts(context))
        return AndroidNotificationService(context, resolver, applier)
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
