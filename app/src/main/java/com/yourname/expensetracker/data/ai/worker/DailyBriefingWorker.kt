package com.yourname.expensetracker.data.ai.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.yourname.expensetracker.data.repository.AnalyticsRepository
import com.yourname.expensetracker.domain.ai.usecase.GenerateDashboardBriefingUseCase
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardDataProvider
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import timber.log.Timber

/**
 * Periodic WorkManager worker that proactively generates a daily AI dashboard briefing.
 *
 * Design:
 *  - Runs once every 24 hours ([AiWorkSchedulerImpl.scheduleDailyBriefing]).
 *  - Fetches a single [ProcessedDashboardData] snapshot and delegates to
 *    [GenerateDashboardBriefingUseCase], which handles the AI opt-in gate,
 *    cache freshness, generation, and artifact persistence.
 *  - Returns [Result.success] unconditionally — transient failures are logged
 *    and the use case stores a FAILED artifact; WorkManager will retry on the
 *    next scheduled window.
 */
@HiltWorker
class DailyBriefingWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val generateDashboardBriefingUseCase: GenerateDashboardBriefingUseCase,
    private val dashboardDataProvider: DashboardDataProvider,
    private val analyticsRepository: AnalyticsRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Timber.d("DailyBriefingWorker: starting.")
        return try {
            val processedData = dashboardDataProvider
                .getProcessedDataFlow(analyticsRepository)
                .first()
            generateDashboardBriefingUseCase(processedData)
            Timber.d("DailyBriefingWorker: completed successfully.")
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "DailyBriefingWorker: unexpected failure.")
            // Return success so WorkManager does not retry with exponential back-off;
            // the next 24-hour window will try again naturally.
            Result.success()
        }
    }

    companion object {
        const val TAG = "DailyBriefingWorker"
    }
}
