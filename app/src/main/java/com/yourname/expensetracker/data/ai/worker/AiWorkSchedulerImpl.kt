package com.yourname.expensetracker.data.ai.worker

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.yourname.expensetracker.domain.ai.service.AiWorkScheduler
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.workers.WorkerSpec
import com.yourname.expensetracker.domain.workers.WorkerSpecScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WorkManager-based implementation of [AiWorkScheduler].
 *
 * Delegates all scheduling to [WorkerSpecScheduler] which handles
 * version-checking, midnight-boundary alignment, and constraint application
 * via [WorkerSpecScheduler.scheduleAtMidnight].
 *
 * @see WorkerSpecScheduler.scheduleAtMidnight
 */
@Singleton
class AiWorkSchedulerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val timeProvider: TimeProvider
) : AiWorkScheduler {

    override fun scheduleDailyBriefing() {
        WorkerSpecScheduler.scheduleAtMidnight(
            context,
            AppConfig.Ai.WORK_NAME_DAILY_BRIEFING,
            DailyBriefingWorker::class.java,
            timeProvider
        )
    }

    override fun enqueueBriefingNow() {
        // Unique one-time work, no delay, under its OWN unique name. The midnight chain stored
        // under WORK_NAME_DAILY_BRIEFING is never touched (REPLACE there would cancel the pending
        // midnight request, and a request appended from inside a failing run is failed with it).
        // KEY_NO_REARM makes this run skip the worker's midnight re-arm; midnight is armed
        // separately by scheduleDailyBriefing (SyncProactiveBriefingWorkUseCase).
        // Constraints are the daily spec's minus requiresCharging, so "generate on enable" is not
        // deferred until the phone charges; the network-type and battery-not-low protections stay.
        // The daily midnight spec itself is unchanged. Not a WorkerSpec.DEFAULTS entry: it is
        // on-demand only and must not be seeded at startup or by WorkerRegistry.
        val specConstraints = WorkerSpec.DEFAULTS[AppConfig.Ai.WORK_NAME_DAILY_BRIEFING]?.constraints
        val onEnableConstraints = Constraints.Builder()
            .setRequiredNetworkType(specConstraints?.requiredNetworkType ?: NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(specConstraints?.requiresBatteryNotLow() ?: true)
            .build()
        val request = OneTimeWorkRequest.Builder(DailyBriefingWorker::class.java)
            .setConstraints(onEnableConstraints)
            .setInputData(workDataOf(DailyBriefingWorker.KEY_NO_REARM to true))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            AppConfig.Ai.WORK_NAME_DAILY_BRIEFING_ON_ENABLE,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    override fun cancelDailyBriefing() {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(AppConfig.Ai.WORK_NAME_DAILY_BRIEFING)
        workManager.cancelUniqueWork(AppConfig.Ai.WORK_NAME_DAILY_BRIEFING_ON_ENABLE)
        Log.d(TAG, "Daily briefing worker cancelled")
    }

    private companion object {
        const val TAG = "AiWorkSchedulerImpl"
    }
}
