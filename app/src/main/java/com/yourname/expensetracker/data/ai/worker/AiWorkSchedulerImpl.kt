package com.yourname.expensetracker.data.ai.worker

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.yourname.expensetracker.domain.ai.service.AiWorkScheduler
import com.yourname.expensetracker.domain.config.AppConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WorkManager-based implementation of [AiWorkScheduler].
 *
 * ## Constraints gap
 * Currently schedules [DailyBriefingWorker] with no custom constraints
 * (no network, battery, or charging requirements). The intended constraints
 * are defined in [WorkerSpec.DEFAULTS] under the key `"ai_daily_briefing"`
 * (`NetworkType.UNMETERED` + `requiresBatteryNotLow` + `requiresCharging`).
 * These constraints should be applied to the [PeriodicWorkRequestBuilder]
 * to prevent the briefing worker from running on metered networks or when
 * the device is low on battery.
 *
 * @see WorkerSpec.DEFAULTS for the full target configuration.
 */
@Singleton
class AiWorkSchedulerImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : AiWorkScheduler {

    override fun scheduleDailyBriefing() {
        val request = PeriodicWorkRequestBuilder<DailyBriefingWorker>(
            repeatInterval = 24,
            repeatIntervalTimeUnit = TimeUnit.HOURS
        ).build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            AppConfig.Ai.WORK_NAME_DAILY_BRIEFING,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
        Log.d(TAG, "Daily briefing worker scheduled")
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
