package com.yourname.expensetracker.data.ai.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker.Result
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.ai.model.AiArtifactStatus
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiMode
import com.yourname.expensetracker.domain.ai.model.AiTargetType
import com.yourname.expensetracker.domain.ai.usecase.DeliverProactiveBriefingNotificationUseCase
import com.yourname.expensetracker.domain.ai.usecase.GenerateDashboardBriefingUseCase
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import com.yourname.expensetracker.domain.dto.AiArtifactRecord
import com.yourname.expensetracker.domain.model.dashboard.SpendingSummary
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardAnalyticsRepository
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardData
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardDataProvider
import com.yourname.expensetracker.domain.usecase.dashboard.ProcessedDashboardData
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.workers.WorkerGuardRequest
import com.yourname.expensetracker.domain.workers.WorkerGuardResult
import com.yourname.expensetracker.domain.workers.WorkerRunContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DailyBriefingWorkerTest {

    private lateinit var context: Context
    private lateinit var generateDashboardBriefingUseCase: GenerateDashboardBriefingUseCase
    private lateinit var dashboardDataProvider: DashboardDataProvider
    private lateinit var analyticsRepository: DashboardAnalyticsRepository
    private lateinit var deliverProactiveBriefingNotificationUseCase: DeliverProactiveBriefingNotificationUseCase
    private lateinit var executionGuard: com.yourname.expensetracker.domain.workers.WorkerExecutionGuard
    private val aiArtifactRepository = mockk<com.yourname.expensetracker.domain.ai.service.AiArtifactRepository>(relaxed = true)
    private val aiWorkScheduler = mockk<com.yourname.expensetracker.domain.ai.service.AiWorkScheduler>(relaxed = true)
    private val diagnosticEventWriter = mockk<DiagnosticEventWriter>(relaxed = true)
    private val workManager: WorkManager = mockk(relaxed = true)
    private val timeProvider: TimeProvider = object : TimeProvider { override fun now() = 1000L }

    // Relaxed run context so behavioral tests can both run the guarded block AND
    // coVerify the worker's counter calls (e.g. addNotificationsSent on delivery).
    private lateinit var ctx: WorkerRunContext

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        generateDashboardBriefingUseCase = mockk(relaxed = true)
        dashboardDataProvider = mockk(relaxed = true)
        analyticsRepository = mockk(relaxed = true)
        deliverProactiveBriefingNotificationUseCase = mockk(relaxed = true)
        executionGuard = mockk(relaxed = true)
        ctx = mockk(relaxed = true)

        // PR7: Mock WorkManager so the worker's reschedule call via
        // WorkerSpecScheduler.scheduleAtMidnight doesn't crash in the test
        // environment where WorkManager isn't initialized.
        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any()) } returns workManager

        // Mirror the real WorkerExecutionGuard.runGuardedWithContext exception handling:
        // - Non-cancellation exceptions → WorkerGuardResult.Retry (via classifyTransient)
        // - TimeoutCancellationException → WorkerGuardResult.Retry (P9-PR1 NEW-P9-001)
        // - CancellationException → re-thrown
        coEvery {
            executionGuard.runGuardedWithContext(
                any<WorkerGuardRequest>(),
                any<suspend (WorkerRunContext) -> Unit>()
            )
        } coAnswers {
            val block = secondArg<suspend (WorkerRunContext) -> Unit>()
            try {
                block.invoke(ctx)
                WorkerGuardResult.Success(Unit)
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                WorkerGuardResult.Retry("Timed out: ${e.message}", e)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                WorkerGuardResult.Retry(e.message ?: "Transient error", e)
            }
        }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun buildWorker(): DailyBriefingWorker {
        return TestListenableWorkerBuilder<DailyBriefingWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ): DailyBriefingWorker {
                    return DailyBriefingWorker(
                        appContext,
                        workerParameters,
                        generateDashboardBriefingUseCase,
                        dashboardDataProvider,
                        analyticsRepository,
                        deliverProactiveBriefingNotificationUseCase,
                        timeProvider,
                        aiArtifactRepository = aiArtifactRepository,
                        aiWorkScheduler = aiWorkScheduler,
                        executionGuard = executionGuard,
                        diagnosticEventWriter = diagnosticEventWriter
                    )
                }
            })
            .build()
    }

    @Test
    fun `briefing generated and stored`() = runTest {
        val processed = sampleProcessedData()
        coEvery { dashboardDataProvider.getProcessedDataFlow(analyticsRepository) } returns flowOf(processed)

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        coVerify(exactly = 1) { generateDashboardBriefingUseCase(processed, 1000L) }
        coVerify(exactly = 1) {
            deliverProactiveBriefingNotificationUseCase(dateKey = any(), startedAt = any(), notificationId = any())
        }
        // P9-S4 counts: a delivered briefing must surface a non-zero notificationsSent.
        verify(exactly = 1) { ctx.addNotificationsSent() }
        // PR7: Reschedule should have been called (WorkManager enqueueUniqueWork called).
        verify(atLeast = 1) {
            workManager.enqueueUniqueWork(any(), any(), any<androidx.work.OneTimeWorkRequest>())
        }
    }

    @Test
    fun `no data empty briefing stored`() = runTest {
        val emptyProcessed = sampleProcessedData()
        coEvery { dashboardDataProvider.getProcessedDataFlow(analyticsRepository) } returns flowOf(emptyProcessed)

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        coVerify(exactly = 1) { generateDashboardBriefingUseCase(emptyProcessed, 1000L) }
    }

    // TODO: Tautological mock test — consider adding real behavior assertion
    @Test
    fun `worker returns success`() = runTest {
        coEvery { dashboardDataProvider.getProcessedDataFlow(analyticsRepository) } returns flowOf(sampleProcessedData())

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
    }

    @Test
    fun `worker handles engine failure gracefully`() = runTest {
        val processed = sampleProcessedData()
        coEvery { dashboardDataProvider.getProcessedDataFlow(analyticsRepository) } returns flowOf(processed)
        coEvery { generateDashboardBriefingUseCase(processed, 1000L) } throws IllegalStateException("engine down")

        val result = buildWorker().doWork()

        assertEquals(Result.retry(), result)
        coVerify(exactly = 0) {
            deliverProactiveBriefingNotificationUseCase(dateKey = any(), startedAt = any(), notificationId = any())
        }
    }

    @Test
    fun `worker retries when delivery times out`() = runTest {
        val processed = sampleProcessedData()
        coEvery { dashboardDataProvider.getProcessedDataFlow(analyticsRepository) } returns flowOf(processed)
        coEvery { generateDashboardBriefingUseCase(processed, 1000L) } coAnswers {
            delay(12_100L)
        }

        val result = buildWorker().doWork()

        assertEquals(Result.retry(), result)
        coVerify(exactly = 0) {
            deliverProactiveBriefingNotificationUseCase(dateKey = any(), startedAt = any(), notificationId = any())
        }
    }

    @Test
    fun `worker propagates CancellationException instead of returning success`() = runTest {
        val processed = sampleProcessedData()
        coEvery { dashboardDataProvider.getProcessedDataFlow(analyticsRepository) } returns flowOf(processed)
        coEvery { generateDashboardBriefingUseCase(processed, 1000L) } throws CancellationException("cancelled")

        try {
            buildWorker().doWork()
            throw AssertionError("Expected CancellationException to propagate")
        } catch (_: CancellationException) {
            // expected — cancellation must not be swallowed
        }
    }

    // P9-P1-04 / PR3 — one-shot midnight chain must survive incidental skips.
    // These assert the real worker->scheduler path: reschedule on Success and on
    // incidental Skipped, but NOT when the guard reports the worker is disabled.

    @Test
    fun `fresh artifact still reschedules next run`() = runTest {
        // Guard runs the block; a fresh READY artifact short-circuits generation
        // via return@runGuarded, which surfaces as Success — the chain must re-arm.
        coEvery { aiArtifactRepository.getLatest(any(), any()) } returns freshBriefingArtifact()

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        coVerify(exactly = 0) { generateDashboardBriefingUseCase(any(), any()) }
        // PR7: Reschedule now goes through WorkerSpecScheduler → WorkManager.
        verify(atLeast = 1) {
            workManager.enqueueUniqueWork(any(), any(), any<androidx.work.OneTimeWorkRequest>())
        }
        // P9-S4 zero-count: a fresh artifact short-circuits before any delivery,
        // so no notification counter is incremented.
        verify(exactly = 0) { ctx.addNotificationsSent() }
    }

    @Test
    fun `privacy denied still reschedules next run`() = runTest {
        coEvery {
            executionGuard.runGuardedWithContext(
                any<WorkerGuardRequest>(),
                any<suspend (WorkerRunContext) -> Unit>()
            )
        } returns WorkerGuardResult.Skipped(DiagnosticReasonCode.PRIVACY_DENIED.name)

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        verify(atLeast = 1) {
            workManager.enqueueUniqueWork(any(), any(), any<androidx.work.OneTimeWorkRequest>())
        }
    }

    @Test
    fun `restore-skip still reschedules next run`() = runTest {
        coEvery {
            executionGuard.runGuardedWithContext(
                any<WorkerGuardRequest>(),
                any<suspend (WorkerRunContext) -> Unit>()
            )
        } returns WorkerGuardResult.Skipped(DiagnosticReasonCode.RESTORE_BLOCKED.name)

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        verify(atLeast = 1) {
            workManager.enqueueUniqueWork(any(), any(), any<androidx.work.OneTimeWorkRequest>())
        }
    }

    @Test
    fun `success reschedules next run`() = runTest {
        coEvery { dashboardDataProvider.getProcessedDataFlow(analyticsRepository) } returns flowOf(sampleProcessedData())

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        verify(atLeast = 1) {
            workManager.enqueueUniqueWork(any(), any(), any<androidx.work.OneTimeWorkRequest>())
        }
    }

    @Test
    fun `disabled does not reschedule`() = runTest {
        // The guard surfaces the spec/runtime-disabled skip with this exact reason
        // (mirrors WorkerExecutionGuard). S2's scheduleAtMidnight already cancels the
        // unique work when disabled, so the worker must NOT re-arm here.
        coEvery {
            executionGuard.runGuardedWithContext(
                any<WorkerGuardRequest>(),
                any<suspend (WorkerRunContext) -> Unit>()
            )
        } returns WorkerGuardResult.Skipped(DailyBriefingWorker.DISABLED_BY_SPEC_REASON)

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        // PR7: Disabled workers must NOT trigger a reschedule → no enqueueUniqueWork call.
        verify(exactly = 0) {
            workManager.enqueueUniqueWork(any(), any(), any<androidx.work.OneTimeWorkRequest>())
        }
    }

    // Drift guard: the `disabled does not reschedule` test above only suppresses
    // rescheduling when the guard's Skipped.reason equals DISABLED_BY_SPEC_REASON.
    // That contract is only meaningful if the constant matches the EXACT literal
    // WorkerExecutionGuard emits (runGuarded/runGuardedWithContext). Asserting
    // against the literal directly — not the constant on both sides — makes a
    // future change to either side fail CI instead of passing tautologically.
    @Test
    fun `DISABLED_BY_SPEC_REASON matches guard emitted literal`() {
        assertEquals("Worker disabled by spec", DailyBriefingWorker.DISABLED_BY_SPEC_REASON)
    }

    @Test
    fun `retry does not reschedule next run`() = runTest {
        // Retry is owned by WorkManager backoff; the midnight chain is re-seeded on
        // the next terminal Success/Skip, so the worker must NOT re-arm here.
        coEvery {
            executionGuard.runGuardedWithContext(
                any<WorkerGuardRequest>(),
                any<suspend (WorkerRunContext) -> Unit>()
            )
        } returns WorkerGuardResult.Retry("transient error")

        val result = buildWorker().doWork()

        assertEquals(Result.retry(), result)
        verify(exactly = 0) {
            workManager.enqueueUniqueWork(any(), any(), any<androidx.work.OneTimeWorkRequest>())
        }
    }

    @Test
    fun `failure does not reschedule next run`() = runTest {
        // A permanent failure must not re-arm the chain; the next terminal
        // Success/Skip re-seeds the midnight run.
        coEvery {
            executionGuard.runGuardedWithContext(
                any<WorkerGuardRequest>(),
                any<suspend (WorkerRunContext) -> Unit>()
            )
        } returns WorkerGuardResult.Failed("permanent error")

        val result = buildWorker().doWork()

        assertEquals(Result.failure(), result)
        verify(exactly = 0) {
            workManager.enqueueUniqueWork(any(), any(), any<androidx.work.OneTimeWorkRequest>())
        }
    }

    private fun freshBriefingArtifact(): AiArtifactRecord {
        return AiArtifactRecord(
            targetType = AiTargetType.DASHBOARD,
            targetKey = "dashboard_home:fresh",
            capability = AiCapability.DASHBOARD_BRIEFING,
            status = AiArtifactStatus.READY,
            mode = AiMode.CLOUD,
            promptVersion = "v1",
            sourceHash = "hash",
            createdAt = 0L,
            updatedAt = 0L,
            // null expiresAt => treated as still fresh by the worker's freshness check.
            expiresAt = null
        )
    }

    private fun sampleProcessedData(): ProcessedDashboardData {
        return ProcessedDashboardData(
            data = DashboardData(
                expenses = emptyList(),
                categories = emptyList(),
                budgetStatuses = emptyList(),
                pendingCount = 0,
                weather = com.yourname.expensetracker.domain.model.dashboard.FinancialWeather(
                    state = com.yourname.expensetracker.domain.model.dashboard.WeatherState.UNKNOWN,
                    headline = UiText.StringResource(R.string.domain_weather_headline_unavailable),
                    summary = UiText.StringResource(R.string.domain_weather_summary_unavailable),
                    icon = "",
                    riskLevel = 0,
                    totalCommitted = 0.0,
                    totalLikely = 0.0,
                    predictedDiscretionary = 0.0,
                    discretionaryBudget = 0.0
                ),
                recurringPatterns = emptyList(),
                plannedExpenses = emptyList(),
                goals = emptyList()
            ),
            summary = SpendingSummary(
                totalSpent = 0.0,
                previousTotalSpent = null,
                changePercent = null,
                dailyHistory = emptyList(),
                previousDailyHistory = emptyList(),
                transactionCount = 0
            ),
            categoryBreakdown = emptyList()
        )
    }
}
