package com.yourname.expensetracker.domain.ai.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiRuntimeStatusModelsTest {

    @Test
    fun `routeDisplayText includes provider and model when present`() {
        val status = AiCapabilityRuntimeStatus(
            capability = AiCapability.DASHBOARD_BRIEFING,
            status = OnDeviceModelStatus.UNAVAILABLE,
            message = null,
            actionLabel = null,
            route = AiRoute.CLOUD,
            providerName = "google-ai-studio",
            modelName = "gemini-2.5-flash"
        )

        assertEquals("Cloud - google-ai-studio - gemini-2.5-flash", status.routeDisplayText())
    }

    @Test
    fun `routeDisplayText returns null when route missing`() {
        val status = AiCapabilityRuntimeStatus(
            capability = AiCapability.QUERY_INTERPRETATION,
            status = OnDeviceModelStatus.AVAILABLE,
            message = null,
            actionLabel = null
        )

        assertNull(status.routeDisplayText())
    }
}
