package com.yourname.expensetracker.data.service

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.ui.MainActivity
import com.yourname.expensetracker.domain.notification.presentation.NotificationKind
import com.yourname.expensetracker.domain.notification.presentation.NotificationPresentationResolver
import com.yourname.expensetracker.domain.privacy.applock.AppLockPrivacySettings
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingField
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsLoadState
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsRepository
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsWriteResult
import com.yourname.expensetracker.domain.privacy.applock.AutoLockTimeout
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticsEmitter
import com.yourname.expensetracker.domain.service.NotificationService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

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
    fun `sendAiBriefingReadyForKind returns not delivered when notifications are disabled`() = runTest {
        every { notificationManager.areNotificationsEnabled() } returns false

        val service = newService(testContext())

        val result = service.sendAiBriefingReadyForKind(
            kind = NotificationKind.BRIEFING,
            notificationId = 42,
            title = "Your AI briefing is ready",
            message = "Spending is calm today.",
            targetKey = "dashboard_home:2026-03-17"
        )

        assertEquals(NotificationService.DeliveryResult.NOT_DELIVERED, result)
        verify(exactly = 0) { notificationManager.notify(any(), any()) }
    }

    @Test
    fun `sendAiBriefingReadyForKind returns delivered after dispatch`() = runTest {
        every { notificationManager.areNotificationsEnabled() } returns true

        val service = newService(testContext())

        val result = service.sendAiBriefingReadyForKind(
            kind = NotificationKind.BRIEFING,
            notificationId = 42,
            title = "Your AI briefing is ready",
            message = "Spending is calm today.",
            targetKey = "dashboard_home:2026-03-17"
        )

        assertEquals(NotificationService.DeliveryResult.DELIVERED, result)
        verify(exactly = 1) { notificationManager.notify(42, any()) }
    }

    @Test
    fun `S9a sendAiBriefingReadyForKind hands the manager a private notification with a generic public version and the kind marker`() = runTest {
        every { notificationManager.areNotificationsEnabled() } returns true
        val captured = slot<Notification>()
        every { notificationManager.notify(42, capture(captured)) } returns Unit

        newService(testContext()).sendAiBriefingReadyForKind(
            kind = NotificationKind.BRIEFING,
            notificationId = 42,
            title = "Your AI briefing is ready",
            message = "Spending is calm today.",
            targetKey = "dashboard_home:2026-10-23"
        )

        val posted = captured.captured
        val generic = NotificationPresentationTexts(baseContext).genericPublic()
        assertEquals(Notification.VISIBILITY_PRIVATE, posted.visibility)
        assertEquals("Your AI briefing is ready", posted.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertEquals(generic.title, posted.publicVersion?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertEquals(generic.text, posted.publicVersion?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertEquals(NotificationKind.BRIEFING.stableToken, NotificationKindMarker.read(posted))
        assertNull(NotificationKindMarker.read(posted.publicVersion))
    }

    @Test
    fun `S9a sendBudgetAlertForKind hands the manager an explicit MainActivity tap target`() = runTest {
        every { notificationManager.areNotificationsEnabled() } returns true
        val captured = slot<Notification>()
        every { notificationManager.notify(7, capture(captured)) } returns Unit

        newService(testContext()).sendBudgetAlertForKind(NotificationKind.BUDGET, 7, "Budget title", "Budget message")

        val intent = shadowOf(captured.captured.contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("expensetracker://home", intent.dataString)
    }

    /** Fake settings store (same shape as the S4 resolver test fake): FULL mode, so the supplied text is shown. */
    private class FakeSettingsRepository : AppLockSettingsRepository {
        override suspend fun getLoadState(): AppLockSettingsLoadState =
            AppLockSettingsLoadState.Loaded(AppLockPrivacySettings(discreetNotifications = false))

        override fun observeLoadState(): Flow<AppLockSettingsLoadState> = emptyFlow()

        override suspend fun setDiscreetNotifications(enabled: Boolean): AppLockSettingsWriteResult =
            error("not used by the notification service")

        override suspend fun reviewSave(record: AppLockPrivacySettings): AppLockSettingsWriteResult =
            error("not used by the notification service")

        // S18 ii-f (finding 8): the write methods are abstract; this fake does not support them, so they fail closed.
        override suspend fun setAppLockEnabled(enabled: Boolean): AppLockSettingsWriteResult =
            AppLockSettingsWriteResult.Failed(AppLockSettingField.APP_LOCK_ENABLED)

        override suspend fun setAutoLockTimeout(timeout: AutoLockTimeout): AppLockSettingsWriteResult =
            AppLockSettingsWriteResult.Failed(AppLockSettingField.AUTO_LOCK_TIMEOUT)

        override suspend fun setAutoLockTimeoutIfNotWeakening(timeout: AutoLockTimeout): AppLockSettingsWriteResult =
            AppLockSettingsWriteResult.Failed(AppLockSettingField.AUTO_LOCK_TIMEOUT)

        override suspend fun setDiscreetNotificationsIfNotForced(enabled: Boolean): AppLockSettingsWriteResult =
            AppLockSettingsWriteResult.Failed(AppLockSettingField.DISCREET)

        override suspend fun setSecureScreenIfNotForced(enabled: Boolean): AppLockSettingsWriteResult =
            AppLockSettingsWriteResult.Failed(AppLockSettingField.SECURE_SCREEN)
    }

    private fun newService(context: Context): AndroidNotificationService {
        val resolver = NotificationPresentationResolver(
            FakeSettingsRepository(),
            mockk<PrivacyDiagnosticsEmitter>(relaxed = true),
            CoroutineScope(Job())
        )
        val applier = NotificationPresentationApplier(context, NotificationPresentationTexts(context))
        return AndroidNotificationService(context, resolver, applier, mockk<PrivacyDiagnosticsEmitter>(relaxed = true))
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
