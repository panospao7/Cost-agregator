package com.yourname.expensetracker.ui.components.ai

import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.ai.model.AssistantUnsupportedReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * INV-C6: one mapping function from the bounded code to a localized message resource.
 */
class AssistantUnsupportedReasonTextTest {

    @Test(timeout = 10_000)
    fun `INV-C6 every reason maps to a non-zero resource id`() {
        AssistantUnsupportedReason.values().forEach { reason ->
            val resId = assistantUnsupportedReasonMessageRes(reason)
            assertNotEquals("reason $reason must map to a real resource id", 0, resId)
        }
    }

    @Test(timeout = 10_000)
    fun `INV-C6 every reason maps to a distinct resource id`() {
        val ids = AssistantUnsupportedReason.values().map { assistantUnsupportedReasonMessageRes(it) }
        assertEquals("each code needs its own message", ids.size, ids.toSet().size)
    }

    @Test(timeout = 10_000)
    fun `INV-C6 mapping covers all seven bounded codes`() {
        assertEquals(7, AssistantUnsupportedReason.values().size)
        assertEquals(
            setOf(
                "INTERPRETATION_DISABLED",
                "INTERPRETATION_FAILED",
                "PROVIDER_UNAVAILABLE",
                "PROVIDER_RESPONSE_INVALID",
                "MODEL_DECLINED",
                "METRIC_NOT_SUPPORTED",
                "NO_MATCHING_TRANSACTIONS"
            ),
            AssistantUnsupportedReason.values().map { it.name }.toSet()
        )
    }

    @Test(timeout = 10_000)
    fun `INV-C6 every mapped id is a declared R string`() {
        val declared = R.string::class.java.fields
            .filter { it.type == Int::class.javaPrimitiveType }
            .associate { it.getInt(null) to it.name }

        AssistantUnsupportedReason.values().forEach { reason ->
            val resId = assistantUnsupportedReasonMessageRes(reason)
            val name = declared[resId]
            assertNotNull("reason $reason maps to an id absent from R.string", name)
            assertTrue(
                "reason $reason must use an assistant_unsupported_* message key, was $name",
                name!!.startsWith("assistant_unsupported_")
            )
        }
    }

    @Test(timeout = 10_000)
    fun `INV-C6 specific codes map to their dedicated keys`() {
        assertEquals(
            R.string.assistant_unsupported_no_matching_transactions,
            assistantUnsupportedReasonMessageRes(AssistantUnsupportedReason.NO_MATCHING_TRANSACTIONS)
        )
        assertEquals(
            R.string.assistant_unsupported_model_declined,
            assistantUnsupportedReasonMessageRes(AssistantUnsupportedReason.MODEL_DECLINED)
        )
        assertEquals(
            R.string.assistant_unsupported_interpretation_failed,
            assistantUnsupportedReasonMessageRes(AssistantUnsupportedReason.INTERPRETATION_FAILED)
        )
        assertEquals(
            R.string.assistant_unsupported_interpretation_disabled,
            assistantUnsupportedReasonMessageRes(AssistantUnsupportedReason.INTERPRETATION_DISABLED)
        )
        assertEquals(
            R.string.assistant_unsupported_provider_unavailable,
            assistantUnsupportedReasonMessageRes(AssistantUnsupportedReason.PROVIDER_UNAVAILABLE)
        )
        assertEquals(
            R.string.assistant_unsupported_response_invalid,
            assistantUnsupportedReasonMessageRes(AssistantUnsupportedReason.PROVIDER_RESPONSE_INVALID)
        )
        assertEquals(
            R.string.assistant_unsupported_metric_not_supported,
            assistantUnsupportedReasonMessageRes(AssistantUnsupportedReason.METRIC_NOT_SUPPORTED)
        )
    }
}
