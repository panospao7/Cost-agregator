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
        val shouldSchedule = settings.aiEnabled &&
            settings.dashboardBriefingEnabled &&
            settings.proactiveBriefingsEnabled

        if (shouldSchedule) {
            aiWorkScheduler.scheduleDailyBriefing()
        } else {
            aiWorkScheduler.cancelDailyBriefing()
        }
    }
}
