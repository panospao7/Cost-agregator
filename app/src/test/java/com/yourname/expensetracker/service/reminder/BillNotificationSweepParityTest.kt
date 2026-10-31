package com.yourname.expensetracker.service.reminder

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.yourname.expensetracker.data.database.entity.RecurringOccurrence
import com.yourname.expensetracker.data.database.entity.RecurringReminderDelivery
import com.yourname.expensetracker.data.service.AndroidNotificationService
import com.yourname.expensetracker.data.service.NotificationPresentationApplier
import com.yourname.expensetracker.data.service.NotificationPresentationTexts
import com.yourname.expensetracker.data.service.PresentationFixtures.ScriptedSettingsRepository
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter
import com.yourname.expensetracker.domain.notification.presentation.NotificationPresentationResolver
import com.yourname.expensetracker.domain.privacy.applock.AppLockPrivacySettings
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsLoadState
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticsEmitter
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import com.yourname.expensetracker.domain.reminder.BillReminderManager
import com.yourname.expensetracker.domain.reminder.BillReminderSettings
import com.yourname.expensetracker.domain.reminder.BillReminderSettingsRepository
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.util.NotificationId
import com.yourname.expensetracker.domain.workers.WorkerExecutionGuard
import com.yourname.expensetracker.domain.workers.WorkerGuardResult
import com.yourname.expensetracker.domain.workers.WorkerRunContext
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.seconds

/**
 * S9b review fix 3: the BILL notification the worker builds and the one the discreet sweep rebuilds must have the
 * same shape. The sweep keeps its own copy of the BILL small icon, priority and the Snooze and Dismiss
 * icon and title templates (the worker is the second builder owner), so this test pins the two together: a change
 * to one builder without the other fails here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BillNotificationSweepParityTest {

    private lateinit var context: Context
    private lateinit var scope: CoroutineScope

    private data class Shape(
        val smallIconRes: Int,
        val priority: Int,
        val actionIconRes: List<Int>,
        val actionTitles: List<String>
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        scope = CoroutineScope(Job())
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun manager(): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** The committed settings the worker and the sweep both read; a test can flip it between the two. */
    private var settingsState: AppLockSettingsLoadState =
        AppLockSettingsLoadState.Loaded(AppLockPrivacySettings(discreetNotifications = true))

    private fun settingsRepo() = ScriptedSettingsRepository { settingsState }

    private fun resolver() = NotificationPresentationResolver(settingsRepo(), mockk(relaxed = true), scope)

    private fun runWorkerOnce(): StatusBarNotification {
        val texts = NotificationPresentationTexts(context)
        val applier = NotificationPresentationApplier(context, texts)
        val coordinator = mockk<RecurringLifecycleCoordinator>(relaxed = true)
        val guard = mockk<WorkerExecutionGuard>(relaxed = true)
        val runContext = mockk<WorkerRunContext>(relaxed = true)
        val settings = mockk<BillReminderSettingsRepository>()
        coEvery { guard.runGuardedWithContext(any(), any<suspend (WorkerRunContext) -> Any>()) } coAnswers {
            val block = secondArg<suspend (WorkerRunContext) -> Any>()
            WorkerGuardResult.Success(block.invoke(runContext))
        }
        coEvery { settings.getSnapshot() } returns BillReminderSettings(
            billRemindersEnabled = true,
            quietHoursEnabled = false
        )
        val reminder = RecurringReminderDelivery(
            id = DELIVERY_ID,
            occurrenceId = 100L,
            reminderWindow = "DUE_DAY",
            scheduledAt = 0L,
            status = "SCHEDULED"
        )
        coEvery { coordinator.recoverAndGetDueReminders() } returns listOf(reminder)
        coEvery { coordinator.claimReminderDelivery(DELIVERY_ID) } returns true
        coEvery { coordinator.getDispatchableClaimedReminder(DELIVERY_ID) } returns
            RecurringLifecycleCoordinator.ReminderDispatchSnapshot(
                delivery = reminder,
                occurrence = RecurringOccurrence(
                    id = reminder.occurrenceId,
                    sourceType = "RECURRING_RULE",
                    sourceId = 10L,
                    occurrenceKey = "RECURRING_RULE|10|100|MONTHLY",
                    dueDate = 0L,
                    status = "PLANNED",
                    expectedAmount = 10.0,
                    expectedCurrency = "USD",
                    frequency = "MONTHLY",
                    merchant = "Zorbas"
                )
            )
        coEvery { coordinator.markReminderSent(DELIVERY_ID, any()) } returns true

        val resolver = resolver()
        // mec merge: installment reminders ride the same worker; the parity shape is the recurring BILL post.
        val billReminderManager = mockk<BillReminderManager>(relaxed = true)
        coEvery { billReminderManager.getDueInstallmentReminders() } returns emptyList()
        val worker = TestListenableWorkerBuilder<BillReminderWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ): BillReminderWorker = BillReminderWorker(
                    appContext,
                    workerParameters,
                    coordinator,
                    guard,
                    mockk<DiagnosticEventWriter>(relaxed = true),
                    settings,
                    FakeTimeProvider(0L),
                    resolver,
                    applier,
                    billReminderManager
                )
            })
            .build()
        runTest(timeout = 10.seconds) { assertEquals(Result.success(), worker.doWork()) }
        return active()
    }

    private fun active(): StatusBarNotification {
        val id = NotificationId.forBill(DELIVERY_ID).value
        return manager().activeNotifications.single { it.id == id }
    }

    @Suppress("DEPRECATION")
    private fun shapeOf(n: Notification): Shape {
        val actions = n.actions
        assertNotNull(actions)
        return Shape(
            smallIconRes = n.smallIcon.resId,
            priority = n.priority,
            actionIconRes = actions.map { it.getIcon().resId },
            actionTitles = actions.map { it.title.toString() }
        )
    }

    @Test
    fun `the worker built BILL notification and the sweep rebuilt one have the same icon priority and actions`() {
        val workerShape = shapeOf(runWorkerOnce().notification)

        val texts = NotificationPresentationTexts(context)
        val service = AndroidNotificationService(
            context,
            resolver(),
            NotificationPresentationApplier(context, texts),
            mockk<PrivacyDiagnosticsEmitter>(relaxed = true)
        )
        var result: com.yourname.expensetracker.domain.service.NotificationService.RepostResult? = null
        runTest(timeout = 10.seconds) { result = service.repostActiveAsDiscreet() }
        assertEquals(1, result!!.reposted)

        val sweepShape = shapeOf(active().notification)

        assertEquals(workerShape.smallIconRes, sweepShape.smallIconRes)
        assertEquals(workerShape.priority, sweepShape.priority)
        assertEquals(2, workerShape.actionIconRes.size)
        assertEquals(workerShape.actionIconRes, sweepShape.actionIconRes)
        assertEquals(workerShape.actionTitles, sweepShape.actionTitles)
    }

    private fun sweepOnce(): com.yourname.expensetracker.domain.service.NotificationService.RepostResult {
        val service = AndroidNotificationService(
            context,
            resolver(),
            NotificationPresentationApplier(context, NotificationPresentationTexts(context)),
            mockk<PrivacyDiagnosticsEmitter>(relaxed = true)
        )
        var result: com.yourname.expensetracker.domain.service.NotificationService.RepostResult? = null
        runTest(timeout = 10.seconds) { result = service.repostActiveAsDiscreet() }
        return result!!
    }

    private fun lockOn() = AppLockSettingsLoadState.Loaded(
        AppLockPrivacySettings(discreetNotifications = false, appLockEnabled = true)
    )

    /** S18-ii-b (F3): at API 30 with the lock on, the sweep rebuild drops the BILL actions like the post path. */
    @Test
    @Config(sdk = [30])
    fun `api 30 with the lock on the sweep rebuilt BILL has no actions and matches the worker built one`() {
        // Control: lock off, the worker's BILL carries both actions (so the original below is not vacuous).
        val unlockedWorker = runWorkerOnce().notification
        assertEquals(2, unlockedWorker.actions.size)

        // The lock is enabled after the BILL was posted; the sweep must drop the actions it would re-apply.
        settingsState = lockOn()
        assertEquals(1, sweepOnce().reposted)
        val sweepLocked = active().notification
        assertEquals(0, sweepLocked.actions?.size ?: 0)

        // Parity: a BILL the worker itself builds under the same lock-on state has the same shape and no actions.
        manager().cancelAll()
        val workerLocked = runWorkerOnce().notification
        assertEquals(0, workerLocked.actions?.size ?: 0)
        assertEquals(workerLocked.smallIcon.resId, sweepLocked.smallIcon.resId)
        assertEquals(workerLocked.priority, sweepLocked.priority)
    }

    /** Fail-closed: unreadable settings count as lock on, so the sweep rebuild drops the actions at API 30. */
    @Test
    @Config(sdk = [30])
    fun `api 30 with unreadable settings the sweep rebuilt BILL has no actions`() {
        assertEquals(2, runWorkerOnce().notification.actions.size)

        settingsState = AppLockSettingsLoadState.Unreadable(
            com.yourname.expensetracker.domain.privacy.applock.SettingsUnreadableCause.IO_ERROR
        )
        assertEquals(1, sweepOnce().reposted)

        assertEquals(0, active().notification.actions?.size ?: 0)
    }

    private companion object {
        const val DELIVERY_ID = 7L
    }
}
