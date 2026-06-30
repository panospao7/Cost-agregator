package com.yourname.expensetracker.data.ai.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiTargetType
import com.yourname.expensetracker.domain.ai.service.AiArtifactRepository
import com.yourname.expensetracker.domain.ai.service.AiWorkScheduler
import com.yourname.expensetracker.domain.ai.usecase.DeliverProactiveBriefingNotificationUseCase
import com.yourname.expensetracker.domain.ai.usecase.GenerateDashboardBriefingUseCase
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.workers.BlockedPolicy
import com.yourname.expensetracker.domain.workers.WorkerExecutionGuard
import com.yourname.expensetracker.domain.workers.WorkerGuardRequest
import com.yourname.expensetracker.domain.workers.WorkerGuardResult
import com.yourname.expensetracker.domain.workers.WorkerSpec
import com.yourname.expensetracker.domain.workers.WorkerSpecScheduler
import com.yourname.expensetracker.domain.workers.toWorkerResult
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardAnalyticsRepository
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardDataProvider
import com.yourname.expensetracker.domain.util.NotificationIdGenerator
import com.yourname.expensetracker.domain.util.TimeProvider
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Periodic WorkManager worker that proactively generates a daily AI dashboard briefing.
 *
 * ## Settings gate
 * At runtime, checks [PrivacyCapability.CLOUD_AI_DAILY_BRIEFING] via [PrivacyGate].
 * If the gate denies, the worker exits early with [Result.success] (skipped, not retried).
 *
 * Design:
 *  - Runs once every 24 hours ([AiWorkSchedulerImpl.scheduleDailyBriefing]).
 *  - Fetches a single [ProcessedDashboardData] snapshot and delegates to
 *    [GenerateDashboardBriefingUseCase], which handles the AI opt-in gate,
 *    cache freshness, generation, and artifact persistence.
 *  - Bounds the end-to-end pipeline with [BRIEFING_PIPELINE_TIMEOUT_MS] so a
 *    stalled data/generation/delivery path cannot hang forever.
 */
@HiltWorker
class DailyBriefingWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val generateDashboardBriefingUseCase: GenerateDashboardBriefingUseCase,
    private val dashboardDataProvider: DashboardDataProvider,
    private val analyticsRepository: DashboardAnalyticsRepository,
    private val deliverProactiveBriefingNotificationUseCase: DeliverProactiveBriefingNotificationUseCase,
    private val timeProvider: TimeProvider,
    private val aiArtifactRepository: AiArtifactRepository,
    private val aiWorkScheduler: AiWorkScheduler,
    private val executionGuard: WorkerExecutionGuard,
    private val diagnosticEventWriter: DiagnosticEventWriter
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Timber.d("DailyBriefingWorker: starting.")

        val guardResult = executionGuard.runGuardedWithContext(
            WorkerGuardRequest(
                workerName = "ai_daily_briefing",
                requiredCapabilities = listOf(PrivacyCapability.CLOUD_AI_DAILY_BRIEFING),
                allowDuringBackupExport = false,
                blockedPolicy = BlockedPolicy.SKIP_SUCCESS,
                workId = id.toString(),
                runAttemptCount = runAttemptCount,
                specVersion = WorkerSpec.DEFAULTS["ai_daily_briefing"]?.version
            )
        ) { ctx ->
            val startedAt = timeProvider.now()
            val dateKey = java.time.Instant.ofEpochMilli(startedAt)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .toString()
            val targetKey = "dashboard_home:$dateKey"

            val existing = aiArtifactRepository.getLatest(targetKey, AiCapability.DASHBOARD_BRIEFING)
            if (existing != null && existing.status == com.yourname.expensetracker.domain.ai.model.AiArtifactStatus.READY) {
                val now = timeProvider.now()
                if (existing.expiresAt == null || now < existing.expiresAt) {
                    // No work to do this run, but the block returns normally so the
                    // guard surfaces Success — the midnight chain stays armed below.
                    Timber.d("DailyBriefingWorker: fresh artifact found, skipping generation.")
                    return@runGuardedWithContext
                }
            }

            val notificationId = NotificationIdGenerator.forGeneral(dateKey.hashCode().toLong())
            try {
                withTimeout(BRIEFING_PIPELINE_TIMEOUT_MS) {
                    val processedData = dashboardDataProvider
                        .getProcessedDataFlow(analyticsRepository)
                        .first()
                    generateDashboardBriefingUseCase(processedData, startedAt)
                    deliverProactiveBriefingNotificationUseCase(
                        dateKey = dateKey,
                        startedAt = startedAt,
                        notificationId = notificationId
                    )
                    // P9-S4 (NEW-03): record the proactive briefing delivery so the run
                    // surfaces a non-zero notificationsSent in BackgroundJobRun. This is
                    // best-effort at the worker boundary: the delivery use case completed
                    // without throwing or timing out. (The use case may still internally
                    // no-op on settings/dedupe; surfacing that would require a delivery
                    // return value, which is out of scope for this counts-only slice.)
                    ctx.addNotificationsSent()
                }
            } catch (e: TimeoutCancellationException) {
                Timber.w(e, "DailyBriefingWorker: pipeline timed out after ${BRIEFING_PIPELINE_TIMEOUT_MS}ms — retrying")
                throw com.yourname.expensetracker.domain.workers.RetryableWorkerException("PIPELINE_TIMEOUT", e)
            }
            Timber.d("DailyBriefingWorker: completed successfully.")
        }

        if (shouldRescheduleNextMidnight(guardResult)) {
            val result = WorkerSpecScheduler.scheduleAtMidnight(
                applicationContext,
                AppConfig.Ai.WORK_NAME_DAILY_BRIEFING,
                DailyBriefingWorker::class.java,
                diagnosticEventWriter
            )
            if (!result.scheduled) {
                Timber.w("DailyBriefingWorker: midnight reschedule failed — ${result.error}")
            }
        }

        return guardResult.toWorkerResult()
    }

    /**
     * P9-P1-04 / PR3: rescheduling is driven by the guard RESULT, not by whether
     * the inner block did real work. Keep the one-shot midnight chain alive on
     * every terminal outcome EXCEPT an explicit disable.
     *
     * Reschedule when the run is [WorkerGuardResult.Success] or an incidental
     * [WorkerGuardResult.Skipped] (fresh artifact / no work, privacy-denied,
     * privacy fail-closed, restore-blocked, write-barrier-denied). Previously the
     * chain only re-armed after full generation+delivery success, so any of those
     * incidental skips silently broke it forever until an app restart re-seeded it.
     *
     * Do NOT reschedule when the worker is explicitly disabled by spec/runtime
     * ([DISABLED_BY_SPEC_REASON]): slice S2's [com.yourname.expensetracker.domain.workers.WorkerSpecScheduler.scheduleAtMidnight]
     * cancels the unique work when the spec is disabled, and the disable path owns
     * (re)arming if the worker is re-enabled — re-arming here would fight that.
     *
     * Retry/Failure are not rescheduled here: WorkManager owns retry backoff, and
     * the next midnight is re-seeded on the following terminal Success/Skip.
     *
     * Privacy-denied still reschedules because there is no tight loop to fear: the
     * next run is bounded by the midnight initial delay, and if the capability is
     * also toggled off at the spec level, scheduleAtMidnight cancels instead of
     * re-arming. So the worst case is one bounded, idle run per day.
     */
    private fun shouldRescheduleNextMidnight(result: WorkerGuardResult<Unit>): Boolean = when (result) {
        is WorkerGuardResult.Success -> true
        is WorkerGuardResult.Skipped -> result.reason != DISABLED_BY_SPEC_REASON
        is WorkerGuardResult.BlockedRetry -> false
        is WorkerGuardResult.Retry -> false
        is WorkerGuardResult.Failed -> false
    }

    companion object {
        const val TAG = "DailyBriefingWorker"
        private const val BRIEFING_PIPELINE_TIMEOUT_MS =
            AppConfig.Ai.DASHBOARD_BRIEFING_TIMEOUT_SECONDS * 1000L

        /**
         * Exact [WorkerGuardResult.Skipped.reason] the guard emits when the worker
         * is disabled by spec/runtime (see [com.yourname.expensetracker.domain.workers.WorkerExecutionGuard]).
         * The guard durably logs this run as [DiagnosticReasonCode.PROVIDER_DISABLED]
         * but surfaces this human-readable reason on the result. This is the ONLY
         * skip reason that must NOT keep the midnight chain alive.
         */
        const val DISABLED_BY_SPEC_REASON = "Worker disabled by spec"
    }
}
