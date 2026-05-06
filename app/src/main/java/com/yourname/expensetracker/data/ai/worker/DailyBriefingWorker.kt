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
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
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
    private val privacyGate: PrivacyGate,
    private val aiArtifactRepository: AiArtifactRepository,
    /** WRK-15: Used to re-schedule the next one-shot briefing at midnight. */
    private val aiWorkScheduler: AiWorkScheduler
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Timber.d("DailyBriefingWorker: starting.")

        // ── Runtime settings gate ────────────────────────────────────────
        val gateCheck = privacyGate.check(PrivacyCapability.CLOUD_AI_DAILY_BRIEFING)
        if (gateCheck is PrivacyDecision.Denied) {
            Timber.w("DailyBriefingWorker: blocked by privacy gate: ${gateCheck.reason}")
            return Result.success()
        }

        return try {
            val startedAt = timeProvider.now()
            val dateKey = java.time.Instant.ofEpochMilli(startedAt)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .toString()
            val targetKey = "dashboard_home:$dateKey"

            // WRK-6: Check cache before generating fresh to avoid unnecessary work.
            // If a fresh artifact already exists for today, skip the entire pipeline.
            val existing = aiArtifactRepository.getLatest(targetKey, AiCapability.DASHBOARD_BRIEFING)
            if (existing != null && existing.status == com.yourname.expensetracker.domain.ai.model.AiArtifactStatus.READY) {
                val now = timeProvider.now()
                if (existing.expiresAt == null || now < existing.expiresAt) {
                    Timber.d("DailyBriefingWorker: fresh artifact found, skipping generation.")
                    return Result.success()
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
            Timber.d("DailyBriefingWorker: completed successfully.")
            // WRK-15: Re-schedule at the next midnight boundary.
            aiWorkScheduler.scheduleDailyBriefing()
            Result.success()
        } catch (e: TimeoutCancellationException) {
            Timber.w(
                e,
                "DailyBriefingWorker: timed out after ${BRIEFING_PIPELINE_TIMEOUT_MS}ms. Retrying."
            )
            Result.retry()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // WRK-7: Classify error types — permanent exceptions should NOT be retried.
            if (isPermanentError(e)) {
                Timber.e(e, "DailyBriefingWorker: permanent failure, NOT retrying.")
                Result.failure()
            } else {
                Timber.e(e, "DailyBriefingWorker: transient failure, scheduling retry.")
                Result.retry()
            }
        }
    }

    /**
     * WRK-7: Classifies exceptions as permanent (non-retriable) or transient (retriable).
     * Permanent errors include coding bugs (IllegalArgument, NPE, State), configuration
     * errors, and privacy/security rejections. Transient errors include network failures,
     * timeouts, service unavailability, and database contention.
     */
    private fun isPermanentError(e: Exception): Boolean {
        return when (e) {
            is IllegalArgumentException,
            is IllegalStateException,
            is NullPointerException,
            is UnsupportedOperationException,
            is SecurityException -> true
            else -> {
                val message = e.message?.lowercase().orEmpty()
                message.contains("not found") ||
                message.contains("permission denied") ||
                message.contains("access denied") ||
                message.contains("invalid argument") ||
                message.contains("unsupported") ||
                message.contains("disabled")
            }
        }
    }

    companion object {
        const val TAG = "DailyBriefingWorker"
        private const val BRIEFING_PIPELINE_TIMEOUT_MS =
            AppConfig.Ai.DASHBOARD_BRIEFING_TIMEOUT_SECONDS * 1000L
    }
}
