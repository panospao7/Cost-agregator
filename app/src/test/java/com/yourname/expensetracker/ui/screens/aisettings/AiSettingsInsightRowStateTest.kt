package com.yourname.expensetracker.ui.screens.aisettings

import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiCapabilityRuntimeStatus
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.OnDeviceModelStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ai-fixes B2e: UX 2.2 state table for the insight row (pure function, no Compose). */
class AiSettingsInsightRowStateTest {

    private val on = AiSettings(aiEnabled = true, allowOnDeviceAi = true, transactionInsightEnabled = true)

    private fun runtime(status: OnDeviceModelStatus, onDeviceMessage: String? = null) = AiCapabilityRuntimeStatus(
        capability = AiCapability.TRANSACTION_INSIGHT,
        status = status,
        message = "router reason that mentions Cloud AI",
        actionLabel = null,
        route = AiRoute.DETERMINISTIC_FALLBACK,
        routeReason = "router reason that mentions Cloud AI",
        onDeviceMessage = onDeviceMessage
    )

    @Test
    fun `AI off shows needs-AI caption and keeps the switch interactive`() {
        val state = transactionInsightRowState(on.copy(aiEnabled = false), runtime(OnDeviceModelStatus.AVAILABLE))
        assertEquals(TransactionInsightRowStatus.NEEDS_AI, state.status)
        assertTrue(state.switchEnabled)
    }

    @Test
    fun `on-device off shows needs-on-device caption and keeps the switch interactive`() {
        val state = transactionInsightRowState(on.copy(allowOnDeviceAi = false), runtime(OnDeviceModelStatus.AVAILABLE))
        assertEquals(TransactionInsightRowStatus.NEEDS_ON_DEVICE, state.status)
        assertTrue(state.switchEnabled)
    }

    @Test
    fun `insight off shows disabled status`() {
        val state = transactionInsightRowState(on.copy(transactionInsightEnabled = false), runtime(OnDeviceModelStatus.AVAILABLE))
        assertEquals(TransactionInsightRowStatus.DISABLED, state.status)
        assertTrue(state.switchEnabled)
    }

    @Test
    fun `missing runtime shows not-loaded and stays interactive`() {
        val state = transactionInsightRowState(on, null)
        assertEquals(TransactionInsightRowStatus.NOT_LOADED, state.status)
        assertTrue(state.switchEnabled)
    }

    @Test
    fun `available model shows ready`() {
        val state = transactionInsightRowState(on, runtime(OnDeviceModelStatus.AVAILABLE))
        assertEquals(TransactionInsightRowStatus.READY, state.status)
        assertNull(state.attentionMessage)
    }

    @Test
    fun `transient statuses show the on-device message not the router reason and stay interactive`() {
        listOf(
            OnDeviceModelStatus.NOT_INSTALLED,
            OnDeviceModelStatus.DOWNLOADING,
            OnDeviceModelStatus.UNAVAILABLE,
            OnDeviceModelStatus.UNKNOWN
        ).forEach { status ->
            val state = transactionInsightRowState(on, runtime(status, onDeviceMessage = "on-device text"))
            assertEquals(TransactionInsightRowStatus.NEEDS_ATTENTION, state.status)
            assertEquals("on-device text", state.attentionMessage)
            assertTrue(state.switchEnabled)
        }
    }

    @Test
    fun `permanent platform limits disable the switch and explain why even when settings are off`() {
        listOf(OnDeviceModelStatus.UNSUPPORTED_DEVICE, OnDeviceModelStatus.UNSUPPORTED_ANDROID_VERSION).forEach { status ->
            listOf(on, on.copy(aiEnabled = false), on.copy(transactionInsightEnabled = false)).forEach { settings ->
                val state = transactionInsightRowState(settings, runtime(status, onDeviceMessage = "platform text"))
                assertEquals(TransactionInsightRowStatus.NEEDS_ATTENTION, state.status)
                assertEquals("platform text", state.attentionMessage)
                assertFalse(state.switchEnabled)
            }
        }
    }
}
