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
