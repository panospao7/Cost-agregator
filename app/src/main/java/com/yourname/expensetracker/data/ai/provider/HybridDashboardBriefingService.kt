package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiServiceResult
import com.yourname.expensetracker.domain.ai.HybridRouter
import com.yourname.expensetracker.domain.ai.model.DashboardBriefing
import com.yourname.expensetracker.domain.ai.model.DashboardBriefingInput
import com.yourname.expensetracker.domain.ai.service.AiCapabilityRouter
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.service.DashboardBriefingService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HybridDashboardBriefingService @Inject constructor(
    private val aiSettingsRepository: AiSettingsRepository,
    private val router: AiCapabilityRouter,
    private val cloudDashboardBriefingService: CloudDashboardBriefingService,
    private val onDeviceDashboardBriefingService: OnDeviceDashboardBriefingService,
    private val noOpDashboardBriefingService: NoOpDashboardBriefingService
) : DashboardBriefingService {

    private val hybridRouter = HybridRouter<DashboardBriefingInput, AiServiceResult<DashboardBriefing>>(
        aiSettingsRepository = aiSettingsRepository,
        router = router,
        capability = AiCapability.DASHBOARD_BRIEFING,
        cloudFn = { cloudDashboardBriefingService.generate(it) },
        onDeviceFn = { onDeviceDashboardBriefingService.generate(it) },
        fallbackFn = { noOpDashboardBriefingService.generate(it) }
    )

    override suspend fun generate(input: DashboardBriefingInput): AiServiceResult<DashboardBriefing> =
        hybridRouter.execute(input)
}
