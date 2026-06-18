package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiServiceResult
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.model.DashboardBriefing
import com.yourname.expensetracker.domain.ai.model.DashboardBriefingInput
import com.yourname.expensetracker.domain.ai.service.AiCapabilityRouter
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.service.DashboardBriefingService
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

// AID-4: This service can be simplified by using HybridRouter:
// val router = HybridRouter(aiSettingsRepository, router, AiCapability.DASHBOARD_BRIEFING,
//     cloudFn = { cloudDashboardBriefingService.generate(it) },
//     onDeviceFn = { onDeviceDashboardBriefingService.generate(it) },
//     fallbackFn = { noOpDashboardBriefingService.generate(it) }
// )
// override suspend fun generate(input: DashboardBriefingInput): AiServiceResult<DashboardBriefing> = router.execute(input)
@Singleton
class HybridDashboardBriefingService @Inject constructor(
    private val aiSettingsRepository: AiSettingsRepository,
    private val router: AiCapabilityRouter,
    private val cloudDashboardBriefingService: CloudDashboardBriefingService,
    private val onDeviceDashboardBriefingService: OnDeviceDashboardBriefingService,
    private val noOpDashboardBriefingService: NoOpDashboardBriefingService
) : DashboardBriefingService {

    override suspend fun generate(input: DashboardBriefingInput): AiServiceResult<DashboardBriefing> {
        val settings = aiSettingsRepository.settings().first()
        return when (router.decide(AiCapability.DASHBOARD_BRIEFING, settings).route) {
            AiRoute.CLOUD -> cloudDashboardBriefingService.generate(input)
            AiRoute.ON_DEVICE -> onDeviceDashboardBriefingService.generate(input)
            AiRoute.DETERMINISTIC_FALLBACK,
            AiRoute.DISABLED -> noOpDashboardBriefingService.generate(input)
        }
    }
}
