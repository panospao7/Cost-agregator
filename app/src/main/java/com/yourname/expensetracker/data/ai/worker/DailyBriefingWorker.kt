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
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.workers.WorkerExecutionGuard
import com.yourname.expensetracker.domain.workers.WorkerGuardRequest
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
    private val executionGuard: WorkerExecutionGuard
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Timber.d("DailyBriefingWorker: starting.")
        var shouldScheduleNext = true

        val guardResult = executionGuard.runGuarded(
            WorkerGuardRequest(
                workerName = "ai_daily_briefing",
                requiredCapabilities = listOf(PrivacyCapability.CLOUD_AI_DAILY_BRIEFING),
                allowDuringBackupExport = false
            )
        ) {
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
                    Timber.d("DailyBriefingWorker: fresh artifact found, skipping generation.")
                    return@runGuarded
                }
            }

            val notificationId = NotificationIdGenerator.forGeneral(dateKey.hashCode().toLong())
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
            }
            shouldScheduleNext = false
            Timber.d("DailyBriefingWorker: completed successfully.")
            aiWorkScheduler.scheduleDailyBriefing()
        }

        if (shouldScheduleNext) {
            runCatching { aiWorkScheduler.scheduleDailyBriefing() }
        }

        return guardResult.toWorkerResult()
    }

    companion object {
        const val TAG = "DailyBriefingWorker"
        private const val BRIEFING_PIPELINE_TIMEOUT_MS =
            AppConfig.Ai.DASHBOARD_BRIEFING_TIMEOUT_SECONDS * 1000L
    }
}
