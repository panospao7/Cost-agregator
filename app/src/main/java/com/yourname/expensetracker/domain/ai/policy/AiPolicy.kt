package com.yourname.expensetracker.domain.ai.policy

import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiSettings

/**
 * Encapsulates privacy and access decisions for the AI layer.
 *
 * Centralising these decisions here keeps policy logic out of ViewModels,
 * use cases, and workers.
 */
interface AiPolicy {
    /** Returns true if cloud AI calls are permitted under the current [settings]. */
    fun canUseCloud(settings: AiSettings): Boolean

    /**
     * Returns true if raw user data must be redacted before being sent to the
     * cloud for the given [capability].
     */
    fun shouldRedact(settings: AiSettings, capability: AiCapability): Boolean
}
