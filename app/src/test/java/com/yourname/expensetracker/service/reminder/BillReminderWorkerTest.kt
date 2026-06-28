package com.yourname.expensetracker.service.reminder

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.yourname.expensetracker.data.database.entity.RecurringOccurrence
import com.yourname.expensetracker.data.database.entity.RecurringReminderDelivery
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import com.yourname.expensetracker.domain.reminder.BillReminderSettings
import com.yourname.expensetracker.domain.reminder.BillReminderSettingsRepository
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.workers.WorkerExecutionGuard
import com.yourname.expensetracker.domain.workers.WorkerGuardResult
import com.yourname.expensetracker.domain.workers.WorkerRunContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PR6D — BillReminder notification permission fix tests.
 *
 * Covers:
 * 1. Guard blocks execution when notification permission is denied at precheck time.
 * 2. Permission revocation after claim calls cancelClaimedReminderDelivery (NOT markReminderFailed).
 * 3. Delivery is not permanently failed on permission revocation.
 * 4. Normal (non-permission) notification failure still records the delivery failure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BillReminderWorkerTest {

    private lateinit var context: Context
    private lateinit var coordinator: RecurringLifecycleCoordinator
    private lateinit var executionGuard: WorkerExecutionGuard
    private lateinit var diagnosticEventWriter: DiagnosticEventWriter
    private lateinit var reminderSettingsRepository: BillReminderSettingsRepository
    private lateinit var timeProvider: FakeTimeProvider

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        coordinator = mockk(relaxed = true)
        executionGuard = mockk(relaxed = true)
        diagnosticEventWriter = mockk(relaxed = true)
        reminderSettingsRepository = mockk()
        timeProvider = FakeTimeProvider(0L)

        // Default guard setup: runs the block (can be overridden per test)
        coEvery { executionGuard.runGuardedWithContext(any(), any<suspend (WorkerRunContext) -> Any>()) } coAnswers {
            val block = secondArg<suspend (WorkerRunContext) -> Any>()
            val ctx = mockk<WorkerRunContext>(relaxed = true)
            WorkerGuardResult.Success(block.invoke(ctx))
        }

        // Default settings: reminders enabled, no quiet hours
        coEvery { reminderSettingsRepository.getSnapshot() } returns BillReminderSettings(
            billRemindersEnabled = true,
            quietHoursEnabled = false
        )
    }

    @After
    fun tearDown() {
        // Clean up any lingering static mocks
        unmockkStatic(NotificationManagerCompat::class)
    }

    private fun buildWorker(): BillReminderWorker {
        return TestListenableWorkerBuilder<BillReminderWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ): BillReminderWorker {
                    return BillReminderWorker(
                        appContext,
                        workerParameters,
                        coordinator,
                        executionGuard,
                        diagnosticEventWriter,
                        reminderSettingsRepository,
                        timeProvider
                    )
                }
            })
            .build()
    }

    /**
     * Creates a minimal [RecurringReminderDelivery] for test use.
     */
    private fun testReminder(
        id: Long = 1L,
        occurrenceId: Long = 100L,
        status: String = "SCHEDULED"
    ) = RecurringReminderDelivery(
        id = id,
        occurrenceId = occurrenceId,
        reminderWindow = "DUE_DAY",
        scheduledAt = timeProvider.now(),
        status = status
    )

    /**
     * Creates a minimal [RecurringOccurrence] for test use.
     */
    private fun testOccurrence(
        id: Long = 100L,
        status: String = "PLANNED"
    ) = RecurringOccurrence(
        id = id,
        sourceType = "RECURRING_RULE",
        sourceId = 10L,
        occurrenceKey = "RECURRING_RULE|10|1000|MONTHLY",
        dueDate = timeProvider.now(),
        status = status,
        expectedAmount = 50.0,
        expectedCurrency = "USD",
        frequency = "MONTHLY",
        merchant = "Netflix"
    )

    /**
     * Creates a [RecurringLifecycleCoordinator.ReminderDispatchSnapshot] with test data.
     */
    private fun testSnapshot(
        deliveryId: Long = 1L,
        occurrenceId: Long = 100L
    ) = RecurringLifecycleCoordinator.ReminderDispatchSnapshot(
        delivery = testReminder(id = deliveryId, occurrenceId = occurrenceId),
        occurrence = testOccurrence(id = occurrenceId)
    )

    /**
     * Sets up the common mocks needed for a notification dispatch path:
     * - Returns one due reminder
     * - Claim succeeds
     * - Snapshot is valid
     */
    private fun setupNotificationDispatchPath(reminder: RecurringReminderDelivery) {
        coEvery { coordinator.getDueReminders() } returns listOf(reminder)
        coEvery { coordinator.claimReminderDelivery(reminder.id) } returns true
        coEvery { coordinator.getDispatchableClaimedReminder(reminder.id) } returns testSnapshot(
            deliveryId = reminder.id, occurrenceId = reminder.occurrenceId
        )
    }

    // ─── Test 1: Guard blocks before getDueReminders ───

    @Test
    fun `permission_denied_guard_blocks_before_get_due_reminders`() = runTest {
        // Override guard to return Skipped(NOTIFICATION_PERMISSION_DENIED)
        coEvery { executionGuard.runGuardedWithContext(any(), any<suspend (WorkerRunContext) -> Any>()) } returns
            WorkerGuardResult.Skipped(DiagnosticReasonCode.NOTIFICATION_PERMISSION_DENIED.name)

        val result = buildWorker().doWork()

        // Skipped returns success (periodic worker should not retry)
        assertEquals(Result.success(), result)

        // Crucially: coordinator.getDueReminders() should NEVER be called
        coVerify(exactly = 0) { coordinator.getDueReminders() }
    }

    // ─── Test 2: Permission revoked after claim unclaims delivery ───

    @Test
    fun `permission_revoked_after_claim_unclaims_delivery`() = runTest {
        val reminder = testReminder(id = 1L)
        setupNotificationDispatchPath(reminder)

        // Mock NotificationManagerCompat.notify() to throw SecurityException
        mockkStatic(NotificationManagerCompat::class)
        val mockNm = mockk<NotificationManagerCompat>(relaxed = true)
        every { NotificationManagerCompat.from(any<Context>()) } returns mockNm
        every { mockNm.notify(any(), any()) } throws SecurityException("Missing notification permission")

        coEvery { coordinator.cancelClaimedReminderDelivery(any(), any()) } returns true

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)

        // Should call cancelClaimedReminderDelivery (unclaim), NOT markReminderFailed
        coVerify(exactly = 1) { coordinator.cancelClaimedReminderDelivery(1L, "notification_permission_revoked") }
        coVerify(exactly = 0) { coordinator.markReminderFailed(any(), any()) }
    }

    // ─── Test 3: Permission revoked does not mark failed final ───

    @Test
    fun `permission_revoked_after_claim_does_not_mark_failed_final`() = runTest {
        val reminder = testReminder(id = 2L)
        setupNotificationDispatchPath(reminder)

        // Mock NotificationManagerCompat.notify() to throw SecurityException
        mockkStatic(NotificationManagerCompat::class)
        val mockNm = mockk<NotificationManagerCompat>(relaxed = true)
        every { NotificationManagerCompat.from(any<Context>()) } returns mockNm
        every { mockNm.notify(any(), any()) } throws SecurityException("Missing notification permission")

        coEvery { coordinator.cancelClaimedReminderDelivery(any(), any()) } returns true

        val result = buildWorker().doWork()

        // Periodic worker returns success — delivery is unclaimed, not failed
        assertEquals(Result.success(), result)

        // Delivery should NOT be permanently failed
        coVerify(exactly = 0) { coordinator.markReminderFailed(any(), any()) }
    }

    // ─── Test 4: Normal notification failure records delivery failure ───

    @Test
    fun `normal_notification_failure_still_records_delivery_failure`() = runTest {
        val reminder = testReminder(id = 3L)
        setupNotificationDispatchPath(reminder)

        // Mock NotificationManagerCompat.notify() to throw generic RuntimeException (not SecurityException)
        mockkStatic(NotificationManagerCompat::class)
        val mockNm = mockk<NotificationManagerCompat>(relaxed = true)
        every { NotificationManagerCompat.from(any<Context>()) } returns mockNm
        every { mockNm.notify(any(), any()) } throws RuntimeException("Notification channel no longer exists")

        coEvery { coordinator.markReminderFailed(any(), any()) } returns true

        val result = buildWorker().doWork()

        // Non-permission failure still returns success (periodic retry is handled by WorkManager)
        assertEquals(Result.success(), result)

        // Should mark the delivery as failed (not unclaimed)
        coVerify(exactly = 1) { coordinator.markReminderFailed(3L, any()) }
        coVerify(exactly = 0) { coordinator.cancelClaimedReminderDelivery(any(), any()) }
    }
}
