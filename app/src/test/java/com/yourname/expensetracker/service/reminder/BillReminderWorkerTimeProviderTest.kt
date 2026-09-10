package com.yourname.expensetracker.service.reminder

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import com.yourname.expensetracker.domain.reminder.BillReminderSettings
import com.yourname.expensetracker.domain.reminder.BillReminderSettingsRepository
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.workers.WorkerExecutionGuard
import com.yourname.expensetracker.domain.workers.WorkerGuardResult
import com.yourname.expensetracker.domain.workers.WorkerRunContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * U-TIME-01: Verifies BillReminderWorker uses injected TimeProvider for quiet hours
 * check instead of System.currentTimeMillis().
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BillReminderWorkerTimeProviderTest {

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

        coEvery { executionGuard.runGuardedWithContext(any(), any<suspend (WorkerRunContext) -> Any>()) } coAnswers {
            val block = secondArg<suspend (WorkerRunContext) -> Any>()
            val ctx = mockk<WorkerRunContext>(relaxed = true)
            WorkerGuardResult.Success(block.invoke(ctx))
        }
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

    @Test
    fun `quiet hours check uses injected TimeProvider timestamp`() = runTest {
        // Set TimeProvider to 23:00 (within default quiet hours 22:00-08:00)
        timeProvider.setTime(FakeTimeProvider.forDate(2026, 3, 15, 23, 0).now())
        coEvery { reminderSettingsRepository.getSnapshot() } returns BillReminderSettings(
            billRemindersEnabled = true,
            quietHoursEnabled = true,
            quietHoursStartMinuteOfDay = 22 * 60,
            quietHoursEndMinuteOfDay = 8 * 60
        )

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        // Guard IS invoked (settings check moved inside guard via P9-PR1),
        // but getDueReminders() should NOT be called because quiet hours short-circuit
        coVerify(exactly = 0) { coordinator.getDueReminders() }
    }

    @Test
    fun `outside quiet hours proceeds to guard block`() = runTest {
        // Set TimeProvider to 14:00 (outside quiet hours)
        timeProvider.setTime(FakeTimeProvider.forDate(2026, 3, 15, 14, 0).now())
        coEvery { reminderSettingsRepository.getSnapshot() } returns BillReminderSettings(
            billRemindersEnabled = true,
            quietHoursEnabled = true,
            quietHoursStartMinuteOfDay = 22 * 60,
            quietHoursEndMinuteOfDay = 8 * 60
        )
        coEvery { coordinator.getDueReminders() } returns emptyList()

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        // Guard block IS invoked because we're outside quiet hours
        coVerify(exactly = 1) { executionGuard.runGuardedWithContext(any(), any<suspend (WorkerRunContext) -> Any>()) }
    }

    @Test
    fun `disabled reminders short-circuits before time check`() = runTest {
        timeProvider.setTime(FakeTimeProvider.forDate(2026, 3, 15, 14, 0).now())
        coEvery { reminderSettingsRepository.getSnapshot() } returns BillReminderSettings(
            billRemindersEnabled = false
        )

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        // Guard IS invoked (settings check moved inside guard via P9-PR1),
        // but getDueReminders() should NOT be called because reminders are disabled
        coVerify(exactly = 0) { coordinator.getDueReminders() }
    }
}
