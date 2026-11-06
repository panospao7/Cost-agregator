package com.yourname.expensetracker.data.ai.worker

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.WorkManager
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
        // Unique one-time work, no delay. Same unique name as the daily chain with REPLACE: the
        // superseded midnight request is re-armed by the worker on completion
        // (DailyBriefingWorker.shouldRescheduleNextMidnight), so the chain never doubles.
        // Constraints are the daily spec's minus requiresCharging, so "generate on enable" is not
        // deferred until the phone charges; the network-type and battery-not-low protections stay.
        // The daily midnight spec itself is unchanged.
        val specConstraints = WorkerSpec.DEFAULTS[AppConfig.Ai.WORK_NAME_DAILY_BRIEFING]?.constraints
        val onEnableConstraints = Constraints.Builder()
            .setRequiredNetworkType(specConstraints?.requiredNetworkType ?: NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(specConstraints?.requiresBatteryNotLow() ?: true)
            .build()
        WorkerSpecScheduler.scheduleFromSpec(
            context,
            AppConfig.Ai.WORK_NAME_DAILY_BRIEFING,
            DailyBriefingWorker::class.java,
            customConstraints = onEnableConstraints,
            oneShotPolicyOverride = ExistingWorkPolicy.REPLACE
        )
    }

    override fun cancelDailyBriefing() {
        WorkManager.getInstance(context)
            .cancelUniqueWork(AppConfig.Ai.WORK_NAME_DAILY_BRIEFING)
        Log.d(TAG, "Daily briefing worker cancelled")
    }

    private companion object {
        const val TAG = "AiWorkSchedulerImpl"
    }
}
