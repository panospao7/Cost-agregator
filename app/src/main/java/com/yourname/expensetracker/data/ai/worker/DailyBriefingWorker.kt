package com.yourname.expensetracker.data.ai.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    private val privacyGate: PrivacyGate
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
            val dateKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(startedAt))
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
            Timber.e(e, "DailyBriefingWorker: transient failure, scheduling retry.")
            Result.retry()
        }
    }

    companion object {
        const val TAG = "DailyBriefingWorker"
        private const val BRIEFING_PIPELINE_TIMEOUT_MS =
            AppConfig.Ai.DASHBOARD_BRIEFING_TIMEOUT_SECONDS * 1000L
    }
}
