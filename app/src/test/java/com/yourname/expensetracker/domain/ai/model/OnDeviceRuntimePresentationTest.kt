package com.yourname.expensetracker.domain.ai.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnDeviceRuntimePresentationTest {

    @Test
    fun `available returns null`() {
        assertNull(OnDeviceModelStatus.AVAILABLE.toRuntimeStatusMessage("AI"))
    }

    @Test
    fun `not installed returns helpful message`() {
        assertEquals(
            "On-device AI is available but the model is not installed yet.",
            OnDeviceModelStatus.NOT_INSTALLED.toRuntimeStatusMessage("AI")
        )
    }

    @Test
    fun `unsupported android returns version message`() {
        assertEquals(
            "On-device briefing requires Android 14 or newer.",
            OnDeviceModelStatus.UNSUPPORTED_ANDROID_VERSION.toRuntimeStatusMessage("briefing")
        )
    }

    @Test
    fun `unavailable returns runtime guidance`() {
        assertEquals(
            "On-device AI is unavailable on this phone right now. This usually means Android AICore / Gemini Nano is missing, not provisioned yet, or unsupported by the device vendor.",
            OnDeviceModelStatus.UNAVAILABLE.toRuntimeStatusMessage("AI")
        )
    }
}
