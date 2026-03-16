package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.domain.ai.model.DashboardBriefing
import com.yourname.expensetracker.domain.ai.model.DashboardBriefingInput
import com.yourname.expensetracker.domain.ai.service.DashboardBriefingService
import javax.inject.Inject

/**
 * No-op implementation of [DashboardBriefingService].
 *
 * Always returns `null`, signalling "nothing to generate". This is the default
 * binding for PR 2. A real on-device or cloud provider will replace this in a
 * future phase once the AI inference layer exists.
 *
 * Using a no-op provider (rather than a null binding) keeps the Hilt graph
 * satisfied and all call sites free of null checks on the service itself.
 */
class NoOpDashboardBriefingService @Inject constructor() : DashboardBriefingService {

    override suspend fun generate(input: DashboardBriefingInput): DashboardBriefing? = null
}
