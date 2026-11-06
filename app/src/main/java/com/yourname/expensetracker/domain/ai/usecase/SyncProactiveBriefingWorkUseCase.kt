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

    /**
     * @param previousSettings settings before the change that triggered this sync, when known.
     *   When supplied and the dashboard briefing toggle went from off to on while the work is
     *   armed, one immediate briefing run is enqueued (J-09-1). Null never triggers it, so
     *   startup and other syncs behave exactly as before.
     */
    suspend operator fun invoke(
        settingsOverride: AiSettings? = null,
        previousSettings: AiSettings? = null
    ) {
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
            if (previousSettings != null &&
                !previousSettings.dashboardBriefingEnabled &&
                settings.dashboardBriefingEnabled
            ) {
                aiWorkScheduler.enqueueBriefingNow()
            }
        } else {
            aiWorkScheduler.cancelDailyBriefing()
        }
    }
}
