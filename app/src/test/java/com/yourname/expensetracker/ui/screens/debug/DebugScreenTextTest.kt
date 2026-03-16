package com.yourname.expensetracker.ui.screens.debug

import com.yourname.expensetracker.domain.ai.model.AiCapability
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
}
