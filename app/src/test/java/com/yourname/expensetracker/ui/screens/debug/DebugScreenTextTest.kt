package com.yourname.expensetracker.ui.screens.debug

import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiCapabilityRuntimeStatus
import com.yourname.expensetracker.domain.ai.model.AiEngagementState
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.OnDeviceModelStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DebugScreenTextTest {

    @Test
    fun `debug guidance mentions cloud fallback when runtime needs attention`() {
        val text = debugRuntimeGuidanceText(
            aiSettings = AiSettings(aiEnabled = true, allowCloudAi = true),
            hasRuntimeAttention = true
        )

        assertEquals(
            "Cloud AI is enabled, so advisory features can still run when on-device AI is unavailable.",
            text
        )
    }

    @Test
    fun `debug cloud fallback hint hidden for unsupported cloud capability`() {
        val hint = debugCloudFallbackHint(
            aiSettings = AiSettings(aiEnabled = true, allowCloudAi = true),
            capability = AiCapability.LOCATION_SUMMARY,
            status = OnDeviceModelStatus.UNAVAILABLE
        )

        assertNull(hint)
    }

    @Test
    fun `debug cloud fallback hint shown for advisory capability with runtime issue`() {
        val hint = debugCloudFallbackHint(
            aiSettings = AiSettings(aiEnabled = true, allowCloudAi = true),
            capability = AiCapability.REVIEW_EXPLANATION,
            status = OnDeviceModelStatus.UNAVAILABLE
        )

        assertEquals("Cloud fallback available for advisory AI", hint)
    }

    @Test
    fun `debug cloud fallback hint hidden when route already resolves to cloud`() {
        val hint = debugCloudFallbackHint(
            aiSettings = AiSettings(aiEnabled = true, allowCloudAi = true),
            capability = AiCapability.REVIEW_EXPLANATION,
            status = OnDeviceModelStatus.UNAVAILABLE,
            runtime = AiCapabilityRuntimeStatus(
                capability = AiCapability.REVIEW_EXPLANATION,
                status = OnDeviceModelStatus.UNAVAILABLE,
                message = null,
                actionLabel = null,
                route = AiRoute.CLOUD,
                providerName = "google-ai-studio",
                modelName = "gemini-2.5-flash"
            )
        )

        assertNull(hint)
    }

    @Test
    fun `phase 4a debug summary includes rollout toggles and engagement keys`() {
        val summary = buildPhase4aDebugSummary(
            aiSettings = AiSettings(
                aiEnabled = true,
                proactiveBriefingsEnabled = true,
                receiptQuickSaveEnabled = true,
                reviewQuickApproveEnabled = false
            ),
            engagementState = AiEngagementState(
                lastDeliveredDashboardBriefingKey = "dashboard_home:2026-03-17",
                lastOpenedDashboardBriefingKey = "dashboard_home:2026-03-16"
            )
        )

        assertEquals(
            "proactiveBriefingsEnabled=true\nreceiptQuickSaveEnabled=true\nreviewQuickApproveEnabled=false\nlastDeliveredBriefing=dashboard_home:2026-03-17\nlastOpenedBriefing=dashboard_home:2026-03-16",
            summary
        )
    }
}
