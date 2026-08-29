package com.yourname.expensetracker.domain.debug

import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiRouteDecision
import com.yourname.expensetracker.domain.util.TimeProvider
import javax.inject.Inject
import javax.inject.Singleton

data class AiRuntimeEvent(
    val timestamp: Long,
    val type: String,
    val message: String
)

@Singleton
class AiRuntimeDiagnostics @Inject constructor(
    private val timeProvider: TimeProvider
) {

    private val lock = Any()
    private val events = ArrayDeque<AiRuntimeEvent>()

    fun recordRouteDecision(capability: AiCapability, decision: AiRouteDecision, now: Long = timeProvider.now()) {
        record(
            AiRuntimeEvent(
                timestamp = now,
                type = "route",
                message = "${capability.name}: ${decision.route.name} (${decision.providerName ?: "none"}/${decision.modelName ?: "none"}) - ${decision.reason}"
            )
        )
    }

    fun recordRuntimeRefresh(message: String, now: Long = timeProvider.now()) {
        record(AiRuntimeEvent(timestamp = now, type = "runtime", message = message))
    }

    fun recordInteraction(type: String, message: String, now: Long = timeProvider.now()) {
        record(AiRuntimeEvent(timestamp = now, type = type, message = message))
    }

    fun getRecentEvents(limit: Int = 20): List<AiRuntimeEvent> = synchronized(lock) {
        events.takeLast(limit).toList().asReversed()
    }

    fun clear() = synchronized(lock) {
        events.clear()
    }

    private fun record(event: AiRuntimeEvent) = synchronized(lock) {
        events.addLast(event)
        while (events.size > 100) {
            events.removeFirst()
        }
    }
}
