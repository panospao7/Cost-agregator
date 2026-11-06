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
import com.yourname.expensetracker.data.service.NotificationPresentationApplier
import com.yourname.expensetracker.data.service.NotificationPresentationTexts
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
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.workers.NotificationPermissionChecker
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFailsWith

/**
 * P-04-1: a `permission_denied` post result releases the CLAIMED delivery for a bounded retry when
 * notifications are enabled and the coordinator accepts the release (attempt budget of 3 not spent).
 * Otherwise (notifications disabled, release refused) the delivery is cancelled as before.
 * A `CancellationException` from the release propagates.
 *
 * The always-cancel pins live in BillReminderWorkerTest, which stubs the checker as disabled.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BillReminderWorkerPermissionRetryTest {

    private lateinit var context: Context
    private lateinit var coordinator: RecurringLifecycleCoordinator
    private lateinit var executionGuard: WorkerExecutionGuard
    private lateinit var diagnosticEventWriter: DiagnosticEventWriter
    private lateinit var reminderSettingsRepository: BillReminderSettingsRepository
    private lateinit var timeProvider: FakeTimeProvider
    private lateinit var billReminderManager: BillReminderManager
    private lateinit var presentationResolver: NotificationPresentationResolver
    private lateinit var permissionChecker: NotificationPermissionChecker

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        billReminderManager = mockk(relaxed = true)
        coordinator = mockk(relaxed = true)
        executionGuard = mockk(relaxed = true)
        diagnosticEventWriter = mockk(relaxed = true)
        reminderSettingsRepository = mockk()
        permissionChecker = mockk()
        timeProvider = FakeTimeProvider(0L)
        presentationResolver = mockk()
        coEvery { presentationResolver.resolve(any(), any()) } returns NotificationPresentationDecision(
            kind = NotificationKind.BILL,
            mode = NotificationPresentationMode.FULL,
            expandedStyleAllowed = false,
            actionPolicy = NotificationActionPolicy.UNCHANGED
        )
        coEvery { executionGuard.runGuardedWithContext(any(), any<suspend (WorkerRunContext) -> Any>()) } coAnswers {
            val block = secondArg<suspend (WorkerRunContext) -> Any>()
            val ctx = mockk<WorkerRunContext>(relaxed = true)
            WorkerGuardResult.Success(block.invoke(ctx))
        }
        coEvery { reminderSettingsRepository.getSnapshot() } returns BillReminderSettings(
            billRemindersEnabled = true,
            quietHoursEnabled = false
        )
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
                    billReminderManager,
                    permissionChecker
                )
            })
            .build()

    private fun reminder(id: Long = 1L) = RecurringReminderDelivery(
        id = id,
        occurrenceId = 100L,
        reminderWindow = "DUE_DAY",
        scheduledAt = timeProvider.now(),
        status = "SCHEDULED"
    )

    private fun snapshot(deliveryId: Long) = RecurringLifecycleCoordinator.ReminderDispatchSnapshot(
        delivery = reminder(deliveryId),
        occurrence = RecurringOccurrence(
            id = 100L,
            sourceType = "RECURRING_RULE",
            sourceId = 10L,
            occurrenceKey = "RECURRING_RULE|10|1000|MONTHLY",
            dueDate = timeProvider.now(),
            status = "PLANNED",
            expectedAmount = 50.0,
            expectedCurrency = "USD",
            frequency = "MONTHLY",
            merchant = "Netflix"
        )
    )

    private fun dispatchPath(delivery: RecurringReminderDelivery) {
        coEvery { coordinator.recoverAndGetDueReminders() } returns listOf(delivery)
        coEvery { coordinator.claimReminderDelivery(delivery.id) } returns true
        coEvery { coordinator.getDispatchableClaimedReminder(delivery.id) } returns snapshot(delivery.id)
    }

    private fun postThrows(throwable: Throwable) {
        mockkStatic(NotificationManagerCompat::class)
        val manager = mockk<NotificationManagerCompat>(relaxed = true)
        every { NotificationManagerCompat.from(any<Context>()) } returns manager
        every { manager.notify(any(), any()) } throws throwable
    }

    @Test
    fun `P-04-1 permission denied with notifications enabled releases the claim and does not cancel`() =
        runTest {
            val delivery = reminder(1L)
            dispatchPath(delivery)
            postThrows(SecurityException("synthetic denial"))
            every { permissionChecker.areNotificationsEnabled() } returns true
            coEvery { coordinator.releaseClaimedReminderDelivery(any(), any()) } returns true

            val result = buildWorker().doWork()

            assertEquals(Result.success(), result)
            coVerify(exactly = 1) { coordinator.releaseClaimedReminderDelivery(1L, 3) }
            coVerify(exactly = 0) { coordinator.cancelClaimedReminderDelivery(any(), any()) }
            coVerify(exactly = 0) { coordinator.markReminderFailed(any(), any()) }
        }

    @Test
    fun `P-04-1 permission denied with notifications disabled cancels and never releases`() = runTest {
        val delivery = reminder(2L)
        dispatchPath(delivery)
        postThrows(SecurityException("synthetic denial"))
        every { permissionChecker.areNotificationsEnabled() } returns false
        coEvery { coordinator.releaseClaimedReminderDelivery(any(), any()) } returns true
        coEvery { coordinator.cancelClaimedReminderDelivery(any(), any()) } returns true

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        coVerify(exactly = 1) { coordinator.cancelClaimedReminderDelivery(2L, "notification_permission_revoked") }
        coVerify(exactly = 0) { coordinator.releaseClaimedReminderDelivery(any(), any()) }
        coVerify(exactly = 0) { coordinator.markReminderFailed(any(), any()) }
    }

    @Test
    fun `P-04-1 release refused at the attempt cap falls back to cancel`() = runTest {
        val delivery = reminder(3L)
        dispatchPath(delivery)
        postThrows(SecurityException("synthetic denial"))
        every { permissionChecker.areNotificationsEnabled() } returns true
        coEvery { coordinator.releaseClaimedReminderDelivery(any(), any()) } returns false
        coEvery { coordinator.cancelClaimedReminderDelivery(any(), any()) } returns true

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        coVerify(exactly = 1) { coordinator.releaseClaimedReminderDelivery(3L, 3) }
        coVerify(exactly = 1) { coordinator.cancelClaimedReminderDelivery(3L, "notification_permission_revoked") }
        coVerify(exactly = 0) { coordinator.markReminderFailed(any(), any()) }
    }

    @Test
    fun `P-04-1 cancellation from the release propagates and nothing is cancelled`() = runTest {
        val delivery = reminder(4L)
        dispatchPath(delivery)
        postThrows(SecurityException("synthetic denial"))
        every { permissionChecker.areNotificationsEnabled() } returns true
        coEvery { coordinator.releaseClaimedReminderDelivery(any(), any()) } throws
            CancellationException("synthetic cancellation")

        assertFailsWith<CancellationException> { buildWorker().doWork() }

        coVerify(exactly = 0) { coordinator.cancelClaimedReminderDelivery(any(), any()) }
        coVerify(exactly = 0) { coordinator.markReminderFailed(any(), any()) }
    }

    @Test
    fun `P-04-1 a non-permission failure never consults the permission retry path`() = runTest {
        val delivery = reminder(5L)
        dispatchPath(delivery)
        postThrows(RuntimeException("synthetic failure"))
        every { permissionChecker.areNotificationsEnabled() } returns true
        coEvery { coordinator.releaseClaimedReminderDelivery(any(), any()) } returns true
        coEvery { coordinator.markReminderFailed(any(), any()) } returns true

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        coVerify(exactly = 1) { coordinator.markReminderFailed(5L, any()) }
        coVerify(exactly = 0) { coordinator.releaseClaimedReminderDelivery(any(), any()) }
        coVerify(exactly = 0) { coordinator.cancelClaimedReminderDelivery(any(), any()) }
    }
}
