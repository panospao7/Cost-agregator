package com.yourname.expensetracker.service.reminder

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.entity.RecurringReminderDelivery
import com.yourname.expensetracker.data.service.NotificationPresentationApplier
import com.yourname.expensetracker.data.service.NotificationPresentationTexts
import com.yourname.expensetracker.data.service.PresentationFixtures
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter
import com.yourname.expensetracker.domain.notification.presentation.NotificationActionPolicy
import com.yourname.expensetracker.domain.notification.presentation.NotificationKind
import com.yourname.expensetracker.domain.notification.presentation.NotificationPresentationDecision
import com.yourname.expensetracker.domain.notification.presentation.NotificationPresentationMode
import com.yourname.expensetracker.domain.notification.presentation.NotificationPresentationResolver
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import com.yourname.expensetracker.domain.reminder.BillReminderManager
import com.yourname.expensetracker.domain.reminder.BillReminderSettings
import com.yourname.expensetracker.domain.reminder.BillReminderSettingsRepository
import com.yourname.expensetracker.domain.reminder.InstallmentReminder
import com.yourname.expensetracker.domain.reminder.InstallmentReminderText
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.workers.WorkerCheckpointBlockedException
import com.yourname.expensetracker.domain.workers.WorkerExecutionGuard
import com.yourname.expensetracker.domain.workers.WorkerGuardResult
import com.yourname.expensetracker.domain.workers.WorkerRunContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertFailsWith

/**
 * S7b privacy-review condition C1: worker-level coverage of the installment reminder path.
 *
 * Pins (worker wiring only; the manager's claim/revalidate rules live in
 * BillReminderManagerInstallmentTest):
 * 1. CancellationException from the installment read propagates (never turned into a Result).
 * 2. A non-cancellation failure of the installment read does not block the recurring dispatch and
 *    logs the class name only (no plan data).
 * 3. A failed post releases the claim for exactly that candidate; a successful post does not
 *    release it and posts a notification.
 * 4. Disabled reminders / quiet hours skip installment dispatch before any claim.
 *
 * All fixtures are synthetic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BillReminderWorkerInstallmentTest {

    private lateinit var context: Context
    private lateinit var coordinator: RecurringLifecycleCoordinator
    private lateinit var executionGuard: WorkerExecutionGuard
    private lateinit var diagnosticEventWriter: DiagnosticEventWriter
    private lateinit var reminderSettingsRepository: BillReminderSettingsRepository
    private lateinit var timeProvider: FakeTimeProvider
    private lateinit var billReminderManager: BillReminderManager
    private lateinit var presentationResolver: NotificationPresentationResolver

    private fun decisionFor(mode: NotificationPresentationMode) = NotificationPresentationDecision(
        kind = NotificationKind.BILL,
        mode = mode,
        expandedStyleAllowed = false,
        actionPolicy = NotificationActionPolicy.UNCHANGED
    )

    private val candidate = InstallmentReminder(
        planUuid = "plan-a",
        seq = 2,
        recordGeneration = 1,
        entryId = 501L,
        displayName = "Demo Electronics",
        currencyCode = "USD",
        amountMinor = 12_500L,
        dueEpochDay = 20_000L,
        daysUntilDue = 2
    )

    // Equal to the candidate, so a stubbed claim and the release verification use concrete values.
    private val claimed = candidate.copy()

    private val reminderText = InstallmentReminderText(
        title = "Bill due",
        body = "Demo Electronics due: 125.00 USD"
    )

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        billReminderManager = mockk(relaxed = true)
        coordinator = mockk(relaxed = true)
        executionGuard = mockk(relaxed = true)
        diagnosticEventWriter = mockk(relaxed = true)
        reminderSettingsRepository = mockk()
        timeProvider = FakeTimeProvider(1_700_000_000_000L)
        // The legacy installment tests run in FULL mode (today's visible text); the DISCREET case below
        // pins the D-M1 A contract (installment reminders use the BILL presentation).
        presentationResolver = mockk()
        coEvery { presentationResolver.resolve(any(), any()) } returns decisionFor(NotificationPresentationMode.FULL)

        coEvery { executionGuard.runGuardedWithContext(any(), any<suspend (WorkerRunContext) -> Any>()) } coAnswers {
            val block = secondArg<suspend (WorkerRunContext) -> Any>()
            val ctx = mockk<WorkerRunContext>(relaxed = true)
            WorkerGuardResult.Success(block.invoke(ctx))
        }
        coEvery { reminderSettingsRepository.getSnapshot() } returns BillReminderSettings(
            billRemindersEnabled = true,
            quietHoursEnabled = false
        )

        // One stubbed installment candidate whose claim succeeds.
        coEvery { billReminderManager.getDueInstallmentReminders() } returns listOf(candidate)
        coEvery { billReminderManager.claimInstallmentReminder(candidate) } returns claimed
        every { billReminderManager.buildInstallmentReminderText(claimed) } returns reminderText
        coEvery { billReminderManager.markInstallmentReminderSent(claimed) } returns true
    }

    /**
     * Guard stand-in that models the real classification of a blocked checkpoint: a
     * [WorkerCheckpointBlockedException] reaching the guard becomes BlockedRetry (BlockedPolicy.RETRY,
     * which this worker requests) and then Result.retry(). Cancellation passes through; any other
     * exception is a Failed result.
     */
    private fun installBlockedAwareGuard(blockedLabels: Set<String>) {
        coEvery { executionGuard.runGuardedWithContext(any(), any<suspend (WorkerRunContext) -> Any>()) } coAnswers {
            val block = secondArg<suspend (WorkerRunContext) -> Any>()
            val ctx = mockk<WorkerRunContext>(relaxed = true)
            coEvery { ctx.checkpoint(any()) } coAnswers {
                val label = firstArg<String>()
                if (label in blockedLabels) {
                    throw WorkerCheckpointBlockedException(
                        reasonCode = "WORKER_WRITE_BARRIER_DENIED",
                        message = "Worker checkpoint blocked at '$label'"
                    )
                }
            }
            try {
                WorkerGuardResult.Success(block.invoke(ctx))
            } catch (e: CancellationException) {
                throw e
            } catch (e: WorkerCheckpointBlockedException) {
                WorkerGuardResult.BlockedRetry(e.reasonCode, e.reasonCode)
            } catch (e: Exception) {
                WorkerGuardResult.Failed("WORKER_UNHANDLED_EXCEPTION", e)
            }
        }
    }

    /** A worker whose application context cannot hand out the notification service (channel creation fails). */
    private fun buildWorkerWithFailingChannelCreation(): BillReminderWorker {
        val failing = object : android.content.ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSystemService(name: String): Any? {
                if (name == Context.NOTIFICATION_SERVICE) throw IllegalStateException("Demo Electronics plan-a")
                return super.getSystemService(name)
            }
        }
        val params = TestListenableWorkerBuilder<BillReminderWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ): BillReminderWorker = BillReminderWorker(
                    failing,
                    workerParameters,
                    coordinator,
                    executionGuard,
                    diagnosticEventWriter,
                    reminderSettingsRepository,
                    timeProvider,
                    presentationResolver,
                    NotificationPresentationApplier(context, NotificationPresentationTexts(context)),
                    billReminderManager
                )
            })
            .build()
        return params
    }

    @After
    fun tearDown() {
        unmockkStatic(NotificationManagerCompat::class)
    }

    private fun buildWorker(): BillReminderWorker =
        TestListenableWorkerBuilder<BillReminderWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ): BillReminderWorker = BillReminderWorker(
                    appContext,
                    workerParameters,
                    coordinator,
                    executionGuard,
                    diagnosticEventWriter,
                    reminderSettingsRepository,
                    timeProvider,
                    presentationResolver,
                    NotificationPresentationApplier(context, NotificationPresentationTexts(context)),
                    billReminderManager
                )
            })
            .build()

    private fun mockNotificationManagerCompat(): NotificationManagerCompat {
        mockkStatic(NotificationManagerCompat::class)
        val nm = mockk<NotificationManagerCompat>(relaxed = true)
        every { NotificationManagerCompat.from(any<Context>()) } returns nm
        return nm
    }

    private fun workerLogsSince(before: Int) =
        ShadowLog.getLogs().drop(before).filter { it.tag == "BillReminderWorker" }

    private fun assertNoPlanDataInLogs(before: Int) {
        val logs = workerLogsSince(before)
        assertTrue(
            logs.all {
                it.throwable == null &&
                    !it.msg.contains("Demo Electronics") &&
                    !it.msg.contains("plan-a") &&
                    !it.msg.contains("125.00")
            }
        )
    }

    private fun testReminder(id: Long) = RecurringReminderDelivery(
        id = id,
        occurrenceId = 100L,
        reminderWindow = "DUE_DAY",
        scheduledAt = timeProvider.now(),
        status = "SCHEDULED"
    )

    // --- 1. Cancellation propagates ---

    @Test
    fun `installment read cancellation propagates out of doWork and does not run recurring dispatch`() = runTest {
        coEvery { billReminderManager.getDueInstallmentReminders() } throws CancellationException("cancelled")
        val before = ShadowLog.getLogs().size

        assertFailsWith<CancellationException> { buildWorker().doWork() }

        coVerify(exactly = 0) { coordinator.recoverAndGetDueReminders() }
        coVerify(exactly = 0) { billReminderManager.claimInstallmentReminder(any()) }
        assertTrue(workerLogsSince(before).none { it.type >= android.util.Log.WARN })
    }

    // --- 2. Non-cancellation failure is isolated ---

    @Test
    fun `installment read failure does not block recurring dispatch and logs class only`() = runTest {
        coEvery { billReminderManager.getDueInstallmentReminders() } throws
            RuntimeException("Demo Electronics plan-a 125.00")
        val reminder = testReminder(id = 31L)
        coEvery { coordinator.recoverAndGetDueReminders() } returns listOf(reminder)
        coEvery { coordinator.claimReminderDelivery(reminder.id) } returns true
        coEvery { coordinator.getDispatchableClaimedReminder(reminder.id) } returns
            RecurringLifecycleCoordinator.ReminderDispatchSnapshot(
                delivery = reminder,
                occurrence = com.yourname.expensetracker.data.database.entity.RecurringOccurrence(
                    id = 100L,
                    sourceType = "RECURRING_RULE",
                    sourceId = 10L,
                    occurrenceKey = "RECURRING_RULE|10|1000|MONTHLY",
                    dueDate = timeProvider.now(),
                    status = "PLANNED",
                    expectedAmount = 50.0,
                    expectedCurrency = "USD",
                    frequency = "MONTHLY",
                    merchant = "Demo Streaming"
                )
            )
        coEvery { coordinator.markReminderSent(reminder.id, any()) } returns true
        val nm = mockNotificationManagerCompat()
        every { nm.notify(any(), any()) } returns Unit
        val before = ShadowLog.getLogs().size

        assertEquals(Result.success(), buildWorker().doWork())

        coVerify(exactly = 1) { coordinator.recoverAndGetDueReminders() }
        coVerify(exactly = 1) { coordinator.markReminderSent(31L, any()) }
        coVerify(exactly = 0) { billReminderManager.claimInstallmentReminder(any()) }
        val logs = workerLogsSince(before)
        assertTrue(logs.any { it.msg.contains("Installment reminder dispatch failed (RuntimeException)") })
        assertNoPlanDataInLogs(before)
    }

    // --- 3. Post failure releases the claim; success does not ---

    @Test
    fun `permission denied post releases the installment claim for that candidate`() = runTest {
        val nm = mockNotificationManagerCompat()
        every { nm.notify(any(), any()) } throws SecurityException("Demo Electronics plan-a")
        val before = ShadowLog.getLogs().size

        assertEquals(Result.success(), buildWorker().doWork())

        coVerify(exactly = 1) { billReminderManager.releaseInstallmentReminder(claimed) }
        coVerify(exactly = 0) { billReminderManager.markInstallmentReminderSent(any()) }
        assertTrue(workerLogsSince(before).any { it.msg.contains("INSTALLMENT_REMINDER_NOT_SENT reason=permission_denied") })
        assertNoPlanDataInLogs(before)
    }

    @Test
    fun `generic post failure releases the installment claim for that candidate`() = runTest {
        val nm = mockNotificationManagerCompat()
        every { nm.notify(any(), any()) } throws RuntimeException("Demo Electronics plan-a")
        val before = ShadowLog.getLogs().size

        assertEquals(Result.success(), buildWorker().doWork())

        coVerify(exactly = 1) { billReminderManager.releaseInstallmentReminder(claimed) }
        coVerify(exactly = 0) { billReminderManager.markInstallmentReminderSent(any()) }
        assertTrue(workerLogsSince(before).any { it.msg.contains("INSTALLMENT_REMINDER_NOT_SENT reason=notification_error") })
        assertNoPlanDataInLogs(before)
    }

    @Test
    fun `post cancellation after the claim releases the claim and propagates without a failure log`() = runTest {
        val nm = mockNotificationManagerCompat()
        every { nm.notify(any(), any()) } throws CancellationException("cancelled")
        val before = ShadowLog.getLogs().size

        assertFailsWith<CancellationException> { buildWorker().doWork() }

        // A claim held by a cancelled run is given back (the next run may remind) and never finalized.
        coVerify(exactly = 1) { billReminderManager.releaseInstallmentReminder(claimed) }
        coVerify(exactly = 0) { billReminderManager.markInstallmentReminderSent(any()) }
        coVerify(exactly = 0) { coordinator.recoverAndGetDueReminders() }
        assertTrue(workerLogsSince(before).none { it.type >= android.util.Log.WARN })
    }

    @Test
    fun `text build cancellation after the claim releases the claim and propagates`() = runTest {
        every { billReminderManager.buildInstallmentReminderText(claimed) } throws CancellationException("cancelled")
        val nm = mockNotificationManagerCompat()
        every { nm.notify(any(), any()) } returns Unit

        assertFailsWith<CancellationException> { buildWorker().doWork() }

        coVerify(exactly = 1) { billReminderManager.releaseInstallmentReminder(claimed) }
        coVerify(exactly = 0) { nm.notify(any(), any()) }
        coVerify(exactly = 0) { billReminderManager.markInstallmentReminderSent(any()) }
    }

    @Test
    fun `presentation resolve cancellation after the claim releases the claim and propagates`() = runTest {
        // Resolution happens inside the claim -> post try (D-M1 A): a cancelled resolve must give the
        // claim back, post nothing, finalize nothing, and rethrow. NotificationKind is an enum and the
        // sdk parameter is a plain Int, so any() is safe for both.
        coEvery { presentationResolver.resolve(any(), any()) } throws CancellationException("cancelled")
        val nm = mockNotificationManagerCompat()
        every { nm.notify(any(), any()) } returns Unit
        val before = ShadowLog.getLogs().size

        assertFailsWith<CancellationException> { buildWorker().doWork() }

        coVerify(exactly = 1) { billReminderManager.releaseInstallmentReminder(claimed) }
        coVerify(exactly = 0) { nm.notify(any(), any()) }
        coVerify(exactly = 0) { billReminderManager.markInstallmentReminderSent(any()) }
        coVerify(exactly = 0) { coordinator.recoverAndGetDueReminders() }
        assertTrue(workerLogsSince(before).none { it.type >= android.util.Log.WARN })
    }

    @Test
    fun `text build failure after the claim releases the claim and does not block the recurring dispatch`() = runTest {
        every { billReminderManager.buildInstallmentReminderText(claimed) } throws
            RuntimeException("Demo Electronics plan-a 125.00")
        val nm = mockNotificationManagerCompat()
        every { nm.notify(any(), any()) } returns Unit
        val before = ShadowLog.getLogs().size

        assertEquals(Result.success(), buildWorker().doWork())

        coVerify(exactly = 1) { billReminderManager.releaseInstallmentReminder(claimed) }
        coVerify(exactly = 0) { billReminderManager.markInstallmentReminderSent(any()) }
        coVerify(exactly = 0) { nm.notify(any(), any()) }
        // The installment failure is isolated: the recurring path still runs.
        coVerify(exactly = 1) { coordinator.recoverAndGetDueReminders() }
        assertTrue(workerLogsSince(before).any { it.msg.contains("Installment reminder dispatch failed (RuntimeException)") })
        assertNoPlanDataInLogs(before)
    }

    @Test
    fun `channel creation failure after the claim becomes a not-sent result and releases the claim`() = runTest {
        val nm = mockNotificationManagerCompat()
        every { nm.notify(any(), any()) } returns Unit
        val before = ShadowLog.getLogs().size

        assertEquals(Result.success(), buildWorkerWithFailingChannelCreation().doWork())

        coVerify(exactly = 1) { billReminderManager.releaseInstallmentReminder(claimed) }
        coVerify(exactly = 0) { billReminderManager.markInstallmentReminderSent(any()) }
        coVerify(exactly = 0) { nm.notify(any(), any()) }
        assertTrue(workerLogsSince(before).any { it.msg.contains("INSTALLMENT_REMINDER_NOT_SENT reason=notification_error") })
        assertNoPlanDataInLogs(before)
    }

    // --- 3b. Checkpoint blocked: the guard sees it (RETRY); nothing is claimed (ISSUE-1) ---

    @Test
    fun `blocked installment checkpoint yields the guard retry result and reaches neither claim nor recurring dispatch`() = runTest {
        installBlockedAwareGuard(setOf("installment_reminder"))
        val nm = mockNotificationManagerCompat()
        every { nm.notify(any(), any()) } returns Unit
        val before = ShadowLog.getLogs().size

        val result = buildWorker().doWork()

        assertEquals(Result.retry(), result)
        coVerify(exactly = 0) { billReminderManager.claimInstallmentReminder(any()) }
        coVerify(exactly = 0) { billReminderManager.releaseInstallmentReminder(any()) }
        coVerify(exactly = 0) { billReminderManager.markInstallmentReminderSent(any()) }
        coVerify(exactly = 0) { coordinator.recoverAndGetDueReminders() }
        coVerify(exactly = 0) { nm.notify(any(), any()) }
        // Not swallowed into the generic "dispatch failed" warning.
        assertTrue(workerLogsSince(before).none { it.msg.contains("Installment reminder dispatch failed") })
    }

    @Test
    fun `unblocked installment checkpoint under the blocked-aware guard still dispatches normally`() = runTest {
        installBlockedAwareGuard(emptySet())
        val nm = mockNotificationManagerCompat()
        every { nm.notify(any(), any()) } returns Unit

        assertEquals(Result.success(), buildWorker().doWork())

        coVerify(exactly = 1) { billReminderManager.claimInstallmentReminder(candidate) }
        coVerify(exactly = 1) { coordinator.recoverAndGetDueReminders() }
    }

    @Test
    fun `mark sent failure after a posted notification is logged without releasing the claim`() = runTest {
        coEvery { billReminderManager.markInstallmentReminderSent(claimed) } returns false
        val nm = mockNotificationManagerCompat()
        every { nm.notify(any(), any()) } returns Unit
        val before = ShadowLog.getLogs().size

        assertEquals(Result.success(), buildWorker().doWork())

        coVerify(exactly = 1) { nm.notify(any(), any()) }
        coVerify(exactly = 1) { billReminderManager.markInstallmentReminderSent(claimed) }
        coVerify(exactly = 0) { billReminderManager.releaseInstallmentReminder(any()) }
        assertTrue(workerLogsSince(before).any { it.msg.contains("INSTALLMENT_REMINDER_MARK_SENT_FAILED") })
        assertNoPlanDataInLogs(before)
    }

    @Test
    fun `successful post keeps the installment claim and posts one notification in the bill id range`() = runTest {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadow = shadowOf(manager)
        val notificationsBefore = shadow.allNotifications.size

        assertEquals(Result.success(), buildWorker().doWork())

        coVerify(exactly = 0) { billReminderManager.releaseInstallmentReminder(any()) }
        coVerify(exactly = 1) { billReminderManager.markInstallmentReminderSent(claimed) }
        val posted = shadow.allNotifications.drop(notificationsBefore)
        assertEquals(1, posted.size)
        assertEquals("Bill due", posted.single().extras.getString(Notification.EXTRA_TITLE))
        assertEquals(
            "Demo Electronics due: 125.00 USD",
            posted.single().extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        )
        val postedIds = manager.activeNotifications.map { it.id }
        assertEquals(1, postedIds.size)
        assertTrue(postedIds.single() in 30000..39999)
        // No Snooze/Dismiss actions: those receivers act on recurring delivery rows only.
        assertTrue(posted.single().actions == null || posted.single().actions.isEmpty())
    }

    // D-M1 A (owner-approvals 79a): installment reminders use the BILL presentation, so DISCREET
    // hides the plan name and the amount everywhere the notification carries text.
    @Test
    fun `DISCREET installment reminder carries no plan name or amount in title text public version or extras`() = runTest {
        coEvery { presentationResolver.resolve(any(), any()) } returns decisionFor(NotificationPresentationMode.DISCREET)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadow = shadowOf(manager)
        val notificationsBefore = shadow.allNotifications.size

        assertEquals(Result.success(), buildWorker().doWork())

        coVerify(exactly = 1) { presentationResolver.resolve(NotificationKind.BILL, any()) }
        coVerify(exactly = 1) { billReminderManager.markInstallmentReminderSent(claimed) }
        coVerify(exactly = 0) { billReminderManager.releaseInstallmentReminder(any()) }
        val posted = shadow.allNotifications.drop(notificationsBefore).single()
        assertEquals(
            context.getString(R.string.notif_discreet_bill_title),
            posted.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        )
        assertEquals(
            context.getString(R.string.notif_discreet_bill_text),
            posted.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        )
        val everyString = PresentationFixtures.everyString(posted)
        assertTrue(everyString.isNotEmpty())
        listOf("Demo Electronics", "125.00", "12500", "USD", "plan-a").forEach { sentinel ->
            everyString.forEach { s ->
                assertTrue("'$sentinel' leaked into a DISCREET installment notification", !s.contains(sentinel))
            }
        }
        assertEquals(Notification.VISIBILITY_PRIVATE, posted.visibility)
        // No Snooze/Dismiss actions: those receivers act on recurring delivery rows only.
        assertTrue(posted.actions == null || posted.actions.isEmpty())
    }

    @Test
    fun `claim denied installment is neither posted nor released`() = runTest {
        coEvery { billReminderManager.claimInstallmentReminder(candidate) } returns null
        val nm = mockNotificationManagerCompat()
        every { nm.notify(any(), any()) } returns Unit

        assertEquals(Result.success(), buildWorker().doWork())

        coVerify(exactly = 0) { nm.notify(any(), any()) }
        coVerify(exactly = 0) { billReminderManager.releaseInstallmentReminder(any()) }
        coVerify(exactly = 0) { billReminderManager.markInstallmentReminderSent(any()) }
    }

    // --- 4. Settings gate: no installment dispatch before any claim ---

    @Test
    fun `reminders disabled skips installment dispatch and never claims`() = runTest {
        coEvery { reminderSettingsRepository.getSnapshot() } returns BillReminderSettings(
            billRemindersEnabled = false,
            quietHoursEnabled = false
        )

        assertEquals(Result.success(), buildWorker().doWork())

        coVerify(exactly = 0) { billReminderManager.getDueInstallmentReminders() }
        coVerify(exactly = 0) { billReminderManager.claimInstallmentReminder(any()) }
        coVerify(exactly = 0) { billReminderManager.releaseInstallmentReminder(any()) }
    }

    @Test
    fun `quiet hours skip installment dispatch and never claim`() = runTest {
        // Whole-day window: within quiet hours in any time zone.
        coEvery { reminderSettingsRepository.getSnapshot() } returns BillReminderSettings(
            billRemindersEnabled = true,
            quietHoursEnabled = true,
            quietHoursStartMinuteOfDay = 0,
            quietHoursEndMinuteOfDay = 24 * 60 - 1
        )
        val nm = mockNotificationManagerCompat()
        every { nm.notify(any(), any()) } returns Unit

        assertEquals(Result.success(), buildWorker().doWork())

        coVerify(exactly = 0) { billReminderManager.getDueInstallmentReminders() }
        coVerify(exactly = 0) { billReminderManager.claimInstallmentReminder(any()) }
        coVerify(exactly = 0) { nm.notify(any(), any()) }
    }

    @Test
    fun `quiet hours enabled but current minute outside the window still dispatches installments`() = runTest {
        val zoned = Instant.ofEpochMilli(timeProvider.now()).atZone(ZoneId.systemDefault())
        val currentMinute = zoned.hour * 60 + zoned.minute
        // A one-minute window an hour away from "now" (never contains the current minute).
        val windowMinute = (currentMinute + 60) % (24 * 60)
        coEvery { reminderSettingsRepository.getSnapshot() } returns BillReminderSettings(
            billRemindersEnabled = true,
            quietHoursEnabled = true,
            quietHoursStartMinuteOfDay = windowMinute,
            quietHoursEndMinuteOfDay = windowMinute
        )
        val nm = mockNotificationManagerCompat()
        every { nm.notify(any(), any()) } returns Unit

        assertEquals(Result.success(), buildWorker().doWork())

        coVerify(exactly = 1) { billReminderManager.claimInstallmentReminder(candidate) }
        coVerify(exactly = 1) { nm.notify(any(), any()) }
    }

    @Test
    fun `reminders enabled outside quiet hours claims the installment candidate`() = runTest {
        val nm = mockNotificationManagerCompat()
        every { nm.notify(any(), any()) } returns Unit

        assertEquals(Result.success(), buildWorker().doWork())

        coVerify(exactly = 1) { billReminderManager.claimInstallmentReminder(candidate) }
        coVerify(exactly = 1) { nm.notify(any(), any()) }
        coVerify(exactly = 0) { billReminderManager.releaseInstallmentReminder(any()) }
    }
}
