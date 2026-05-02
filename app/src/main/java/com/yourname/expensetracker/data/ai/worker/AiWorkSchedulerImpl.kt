package com.yourname.expensetracker.data.ai.worker

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.yourname.expensetracker.domain.ai.service.AiWorkScheduler
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.workers.WorkerSpec
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WorkManager-based implementation of [AiWorkScheduler].
 *
 * Reads interval and constraints from [WorkerSpec.DEFAULTS] under the key `"ai_daily_briefing"`
 * which defines `NetworkType.UNMETERED` + `requiresBatteryNotLow` + `requiresCharging`.
 * These constraints prevent the briefing worker from running on metered networks or when
 * the device is low on battery.
 *
 * @see WorkerSpec.DEFAULTS for the full target configuration.
 */
@Singleton
class AiWorkSchedulerImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : AiWorkScheduler {

    override fun scheduleDailyBriefing() {
        val spec = WorkerSpec.DEFAULTS["ai_daily_briefing"]
        val intervalHours = spec?.repeatIntervalHours ?: 24L
        val constraints = spec?.constraints

        val builder = PeriodicWorkRequestBuilder<DailyBriefingWorker>(
            repeatInterval = intervalHours,
            repeatIntervalTimeUnit = TimeUnit.HOURS
        )
        if (constraints != null) {
            builder.setConstraints(constraints)
        }

        val request = builder.build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            AppConfig.Ai.WORK_NAME_DAILY_BRIEFING,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
        Log.d(TAG, "Daily briefing worker scheduled (interval=${intervalHours}h)")
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
