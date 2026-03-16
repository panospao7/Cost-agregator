package com.yourname.expensetracker.domain.ai.policy

import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiSettings
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiPolicyImpl @Inject constructor() : AiPolicy {

    override fun canUseCloud(settings: AiSettings): Boolean =
        settings.aiEnabled && settings.allowCloudAi

    override fun shouldRedact(settings: AiSettings, capability: AiCapability): Boolean =
        settings.redactBeforeCloud
}
