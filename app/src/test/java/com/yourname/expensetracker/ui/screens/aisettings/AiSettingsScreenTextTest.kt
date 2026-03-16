package com.yourname.expensetracker.ui.screens.aisettings

import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiCapabilityRuntimeStatus
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.OnDeviceModelStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiSettingsScreenTextTest {

    @Test
    fun `runtime guidance mentions cloud fallback when cloud is allowed`() {
        val text = runtimeGuidanceText(
            settings = AiSettings(aiEnabled = true, allowCloudAi = true),
            highestPriorityMessage = "On-device AI is unavailable."
        )

        assertEquals(
            "On-device AI is unavailable. Cloud routing can still handle advisory features when your mode and privacy settings allow it.",
            text
        )
    }

    @Test
    fun `runtime guidance falls back to status message when cloud is disabled`() {
        val text = runtimeGuidanceText(
            settings = AiSettings(aiEnabled = true, allowCloudAi = false),
            highestPriorityMessage = "Check device support"
        )

        assertEquals("Check device support", text)
    }

    @Test
    fun `cloud fallback hint shown when capability needs attention and cloud is allowed`() {
        val hint = cloudFallbackHint(
            enabled = true,
            runtime = AiCapabilityRuntimeStatus(
                capability = AiCapability.DASHBOARD_BRIEFING,
                status = OnDeviceModelStatus.UNAVAILABLE,
                message = "On-device briefing is unavailable.",
                actionLabel = "Check device support"
            ),
            cloudFallbackAvailable = true
        )

        assertEquals("Cloud fallback available", hint)
    }

    @Test
    fun `cloud fallback hint hidden when runtime is ready`() {
        val hint = cloudFallbackHint(
            enabled = true,
            runtime = AiCapabilityRuntimeStatus(
                capability = AiCapability.DASHBOARD_BRIEFING,
                status = OnDeviceModelStatus.AVAILABLE,
                message = null,
                actionLabel = null
            ),
            cloudFallbackAvailable = true
        )

        assertNull(hint)
    }
}
