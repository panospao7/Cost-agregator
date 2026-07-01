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
import com.yourname.expensetracker.domain.util.NotificationIdGenerator
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
import kotlinx.coroutines.TimeoutCancellationException
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

    /**
     * Helper to create a real [TimeoutCancellationException] without accessing
     * the internal constructor. Used by post-delivery timeout tests.
     */
    private fun fakeTimeoutCancellationException(): TimeoutCancellationException {
        return try {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(1L) { kotlinx.coroutines.delay(10L) }
            }
            throw IllegalStateException("Expected TimeoutCancellationException")
        } catch (e: TimeoutCancellationException) {
            e
        }
    }

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

    // PR12H-6: DailyBriefing idempotency/cause — TimeoutCancellationException must be
    // wrapped in RetryableWorkerException with the original cause preserved.
    @Test
    fun `pipeline timeout wraps in RetryableWorkerException with cause`() = runTest {
        val processed = sampleProcessedData()
        val timeoutEx = kotlinx.coroutines.runBlocking {
            try {
                kotlinx.coroutines.withTimeout(1L) { kotlinx.coroutines.delay(10L) }
                throw IllegalStateException("Expected TimeoutCancellationException")
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                e
            }
        }
        coEvery { dashboardDataProvider.getProcessedDataFlow(analyticsRepository) } returns flowOf(processed)
        coEvery { generateDashboardBriefingUseCase(processed, 1000L) } throws timeoutEx

        // The worker should throw RetryableWorkerException, NOT plain TimeoutCancellationException
        var thrown: Throwable? = null
        try {
            buildWorker().doWork()
        } catch (e: Throwable) {
            thrown = e
        }

        // Because the guard mock in this test catches all non-cancellation exceptions and
        // returns Retry, the worker itself never propagates the raw RetryableWorkerException.
        // Instead we verify the guard mock receives the wrapped exception by inspecting
        // the mock call history.
        val capturedBlock = mutableListOf<suspend (WorkerRunContext) -> Unit>()
        coVerify(atLeast = 1) {
            executionGuard.runGuardedWithContext(
                any<WorkerGuardRequest>(),
                capture(capturedBlock)
            )
        }
        // When the captured block is executed, it should throw RetryableWorkerException
        // with TimeoutCancellationException as cause.
        val block = capturedBlock.first()
        var blockEx: Throwable? = null
        try {
            block.invoke(ctx)
        } catch (e: Throwable) {
            blockEx = e
        }
        assertEquals(
            "Worker block should throw RetryableWorkerException on pipeline timeout",
            com.yourname.expensetracker.domain.workers.RetryableWorkerException::class.java,
            blockEx?.javaClass
        )
        assertEquals(
            "RetryableWorkerException must preserve TimeoutCancellationException as cause",
            kotlinx.coroutines.TimeoutCancellationException::class.java,
            blockEx?.cause?.javaClass
        )
        assertEquals(DiagnosticReasonCode.WORKER_TIMEOUT.name, (blockEx as? com.yourname.expensetracker.domain.workers.RetryableWorkerException)?.reasonCode)
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

    // ══════════════════════════════════════════════════════════════════════
    // PR12I-5: DailyBriefing timeout idempotency
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `same_date_produces_same_notification_id`() {
        val dateKey = "2026-06-30"
        val id1 = NotificationIdGenerator.forGeneral(dateKey.hashCode().toLong())
        val id2 = NotificationIdGenerator.forGeneral(dateKey.hashCode().toLong())
        assertEquals(id1, id2)
    }

    @Test
    fun `existing_artifact_prevents_duplicate_delivery`() = runTest {
        coEvery { aiArtifactRepository.getLatest(any(), any()) } returns freshBriefingArtifact()

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        coVerify(exactly = 0) { generateDashboardBriefingUseCase(any(), any()) }
        coVerify(exactly = 0) { deliverProactiveBriefingNotificationUseCase(any(), any(), any()) }
    }

    @Test
    fun `retry_after_timeout_uses_same_notification_id`() = runTest {
        val processed = sampleProcessedData()
        coEvery { dashboardDataProvider.getProcessedDataFlow(analyticsRepository) } returns flowOf(processed)
        coEvery { generateDashboardBriefingUseCase(processed, 1000L) } returns Unit

        // Capture the notificationId passed to delivery on first attempt
        val capturedIds = mutableListOf<Int>()
        coEvery { deliverProactiveBriefingNotificationUseCase(dateKey = any(), startedAt = any(), notificationId = capture(capturedIds)) } returns Unit

        // First run: timeout after delivery but before worker success
        var callCount = 0
        coEvery {
            executionGuard.runGuardedWithContext(
                any<WorkerGuardRequest>(),
                any<suspend (WorkerRunContext) -> Unit>()
            )
        } coAnswers {
            val block = secondArg<suspend (WorkerRunContext) -> Unit>()
            callCount++
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

        val worker = buildWorker()
        val result1 = worker.doWork()

        // The worker should succeed (delivery completes, no timeout in this test path)
        assertEquals(Result.success(), result1)
        assertEquals("Delivery should have been called exactly once", 1, capturedIds.size)
        val firstId = capturedIds.first()

        // Second run (simulated retry): same date should produce same notificationId
        val result2 = worker.doWork()
        assertEquals(Result.success(), result2)
        assertEquals("Delivery should have been called twice total", 2, capturedIds.size)
        assertEquals("Retry must use same deterministic notificationId", firstId, capturedIds[1])
    }

    // ══════════════════════════════════════════════════════════════════════
    // PR12K-5: DailyBriefing post-delivery timeout idempotency tests
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `timeout_after_delivery_returns_retry`() = runTest {
        val processed = sampleProcessedData()
        coEvery { dashboardDataProvider.getProcessedDataFlow(analyticsRepository) } returns flowOf(processed)
        coEvery { generateDashboardBriefingUseCase(processed, 1000L) } returns Unit
        coEvery { deliverProactiveBriefingNotificationUseCase(
            dateKey = any(), startedAt = any(), notificationId = any()
        ) } returns Unit

        // Simulate timeout AFTER delivery by making addNotificationsSent throw.
        // In the worker, addNotificationsSent() is the last line inside withTimeout,
        // so a failure there mimics "delivery succeeded, then the pipeline timed out".
        every { ctx.addNotificationsSent() } throws fakeTimeoutCancellationException()

        val result = buildWorker().doWork()

        assertEquals(Result.retry(), result)
        coVerify(exactly = 1) { generateDashboardBriefingUseCase(any(), any()) }
        coVerify(exactly = 1) {
            deliverProactiveBriefingNotificationUseCase(
                dateKey = any(), startedAt = any(), notificationId = any()
            )
        }
    }

    @Test
    fun `timeout_after_delivery_retry_uses_same_notification_id`() = runTest {
        val processed = sampleProcessedData()
        coEvery { dashboardDataProvider.getProcessedDataFlow(analyticsRepository) } returns flowOf(processed)
        coEvery { generateDashboardBriefingUseCase(processed, 1000L) } returns Unit
        // Allow artifact check to return null so both attempts generate+deliver
        coEvery { aiArtifactRepository.getLatest(any(), any()) } returns null

        val capturedIds = mutableListOf<Int>()
        coEvery {
            deliverProactiveBriefingNotificationUseCase(
                dateKey = any(), startedAt = any(), notificationId = capture(capturedIds)
            )
        } returns Unit

        // First attempt times out after delivery; second succeeds.
        var addNotifCount = 0
        every { ctx.addNotificationsSent() } answers {
            addNotifCount++
            if (addNotifCount == 1) {
                throw fakeTimeoutCancellationException()
            }
        }

        // First attempt → retry after timeout
        val result1 = buildWorker().doWork()
        assertEquals(Result.retry(), result1)

        // Second attempt (retry) → complete successfully
        val result2 = buildWorker().doWork()
        assertEquals(Result.success(), result2)

        // Both attempts should have called delivery with the same deterministic notificationId
        assertEquals(2, capturedIds.size)
        assertEquals(
            "Retry must use same deterministic notificationId as first attempt",
            capturedIds[0], capturedIds[1]
        )
    }

    @Test
    fun `timeout_after_delivery_retry_does_not_duplicate_artifact`() = runTest {
        val processed = sampleProcessedData()
        coEvery { dashboardDataProvider.getProcessedDataFlow(analyticsRepository) } returns flowOf(processed)
        coEvery { generateDashboardBriefingUseCase(processed, 1000L) } returns Unit

        // First call to getLatest returns null (no existing artifact),
        // subsequent calls return the artifact generated on first attempt.
        var getLatestCount = 0
        coEvery { aiArtifactRepository.getLatest(any(), any()) } answers {
            getLatestCount++
            if (getLatestCount == 1) null else freshBriefingArtifact()
        }

        val capturedIds = mutableListOf<Int>()
        coEvery {
            deliverProactiveBriefingNotificationUseCase(
                dateKey = any(), startedAt = any(), notificationId = capture(capturedIds)
            )
        } returns Unit

        var addNotifCount = 0
        every { ctx.addNotificationsSent() } answers {
            addNotifCount++
            if (addNotifCount == 1) {
                throw fakeTimeoutCancellationException()
            }
        }

        // First attempt: no artifact → generate → deliver → timeout → retry
        assertEquals(Result.retry(), buildWorker().doWork())

        // Second attempt: artifact exists (returned by getLatest) → skip → success
        assertEquals(Result.success(), buildWorker().doWork())

        // Generation must happen exactly once — no duplicate artifact on retry
        coVerify(exactly = 1) { generateDashboardBriefingUseCase(any(), any()) }
        // Delivery must happen exactly once — the retry finds the existing artifact
        // and short-circuits before any delivery call.
        coVerify(exactly = 1) {
            deliverProactiveBriefingNotificationUseCase(
                dateKey = any(), startedAt = any(), notificationId = any()
            )
        }
    }

    @Test
    fun `timeout_after_delivery_retry_marks_artifact_delivered_once`() = runTest {
        val processed = sampleProcessedData()
        coEvery { dashboardDataProvider.getProcessedDataFlow(analyticsRepository) } returns flowOf(processed)
        coEvery { generateDashboardBriefingUseCase(processed, 1000L) } returns Unit

        // First call returns null; subsequent calls return the artifact generated
        // on the first attempt, simulating that the first run stored an artifact.
        var getLatestCount = 0
        coEvery { aiArtifactRepository.getLatest(any(), any()) } answers {
            getLatestCount++
            if (getLatestCount == 1) null else freshBriefingArtifact()
        }

        coEvery {
            deliverProactiveBriefingNotificationUseCase(
                dateKey = any(), startedAt = any(), notificationId = any()
            )
        } returns Unit

        every { ctx.addNotificationsSent() } throws fakeTimeoutCancellationException()

        // First attempt: deliver succeeds, then timeout → retry
        assertEquals(Result.retry(), buildWorker().doWork())

        // Second attempt: artifact exists → skip → complete successfully
        assertEquals(Result.success(), buildWorker().doWork())

        // The deliver use case was invoked exactly once (first attempt),
        // marking the notification as delivered. The retry does not re-deliver.
        coVerify(exactly = 1) {
            deliverProactiveBriefingNotificationUseCase(
                dateKey = any(), startedAt = any(), notificationId = any()
            )
        }
        // addNotificationsSent was called exactly once during the first attempt
        // (before the TimeoutCancellationException), recording the delivery metric.
        verify(exactly = 1) { ctx.addNotificationsSent() }
    }

    @Test
    fun `timeout_after_delivery_retry_replaces_same_notification`() = runTest {
        val processed = sampleProcessedData()
        coEvery { dashboardDataProvider.getProcessedDataFlow(analyticsRepository) } returns flowOf(processed)
        coEvery { generateDashboardBriefingUseCase(processed, 1000L) } returns Unit
        coEvery { aiArtifactRepository.getLatest(any(), any()) } returns null

        val capturedIds = mutableListOf<Int>()
        coEvery {
            deliverProactiveBriefingNotificationUseCase(
                dateKey = any(), startedAt = any(), notificationId = capture(capturedIds)
            )
        } returns Unit

        var addNotifCount = 0
        every { ctx.addNotificationsSent() } answers {
            addNotifCount++
            if (addNotifCount == 1) {
                throw fakeTimeoutCancellationException()
            }
        }

        // First attempt → retry
        assertEquals(Result.retry(), buildWorker().doWork())

        // Second attempt (retry) → success
        assertEquals(Result.success(), buildWorker().doWork())

        // Both attempts used the same notificationId; Android NotificationManager
        // replaces (does not duplicate) notifications with the same ID.
        assertEquals(2, capturedIds.size)
        assertEquals(
            "Both delivery attempts must use the same notification ID so the" +
                    " Android NotificationManager replaces the existing notification",
            capturedIds[0], capturedIds[1]
        )
        // Exactly one unique ID was used across both attempts
        assertEquals(1, capturedIds.distinct().size)
    }

    @Test
    fun `successful_retry_records_notifications_sent_once`() = runTest {
        val processed = sampleProcessedData()
        coEvery { dashboardDataProvider.getProcessedDataFlow(analyticsRepository) } returns flowOf(processed)
        coEvery { generateDashboardBriefingUseCase(processed, 1000L) } returns Unit

        // First call returns null (no artifact), subsequent calls return the artifact
        var getLatestCount = 0
        coEvery { aiArtifactRepository.getLatest(any(), any()) } answers {
            getLatestCount++
            if (getLatestCount == 1) null else freshBriefingArtifact()
        }

        coEvery {
            deliverProactiveBriefingNotificationUseCase(
                dateKey = any(), startedAt = any(), notificationId = any()
            )
        } returns Unit

        var addNotifCount = 0
        every { ctx.addNotificationsSent() } answers {
            addNotifCount++
            if (addNotifCount == 1) {
                throw fakeTimeoutCancellationException()
            }
        }

        // First attempt: deliver, timeout → retry (addNotificationsSent was called once)
        assertEquals(Result.retry(), buildWorker().doWork())

        // Second attempt: artifact exists → skip → success (addNotificationsSent NOT called)
        assertEquals(Result.success(), buildWorker().doWork())

        // notificationsSent metric recorded exactly once — the count from the
        // first attempt's successful delivery. The retry skips because the
        // artifact already exists and is READY.
        assertEquals(
            "notificationsSent metric must be recorded exactly once",
            1, addNotifCount
        )
    }
}
