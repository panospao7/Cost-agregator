package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.service.AiWorkScheduler
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class SyncProactiveBriefingWorkUseCase @Inject constructor(
    private val aiSettingsRepository: AiSettingsRepository,
    private val aiWorkScheduler: AiWorkScheduler
) {

    suspend operator fun invoke(settingsOverride: AiSettings? = null) {
        val settings = settingsOverride ?: aiSettingsRepository.settings().first()
        // INV-25: armed iff aiEnabled and briefing and (allowCloudAi or proactive).
        // Cloud-route users generate the daily artifact regardless of proactive posting;
        // allowCloudAi=false users keep today's behavior (proactive arms the fallback path).
        // INV-26: this predicate never reads or writes privacy settings.
        val shouldSchedule = settings.aiEnabled &&
            settings.dashboardBriefingEnabled &&
            (settings.allowCloudAi || settings.proactiveBriefingsEnabled)

        if (shouldSchedule) {
            aiWorkScheduler.scheduleDailyBriefing()
        } else {
            aiWorkScheduler.cancelDailyBriefing()
        }
    }
}
