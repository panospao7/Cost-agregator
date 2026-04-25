package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.domain.ai.model.AiTargetType
import com.yourname.expensetracker.domain.ai.model.DedupeCandidateSummary
import com.yourname.expensetracker.domain.ai.model.DedupeJudgeInput
import com.yourname.expensetracker.domain.ai.model.DuplicateVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceDedupeJudgeServiceTest {

    private val service = OnDeviceDedupeJudgeService()

    private val sampleInput = DedupeJudgeInput(
        subject = DedupeCandidateSummary(
            targetType = AiTargetType.PENDING_REVIEW,
            targetId = 2L,
            merchant = "Lidl",
            amount = 10.0,
            currency = "EUR",
            date = 1_000L,
            sourceLabel = "pkg"
        ),
        candidates = listOf(
            DedupeCandidateSummary(
                targetType = AiTargetType.EXPENSE,
                targetId = 3L,
                merchant = "Lidl",
                amount = 10.0,
                currency = "EUR",
                date = 1_020L,
                sourceLabel = "expense"
            )
        )
    )

    @Test
    fun `buildPrompt includes subject and candidates`() {
        val prompt = service.buildPrompt(sampleInput)

        assertTrue(prompt.contains("Subject:"))
        assertTrue(prompt.contains("merchant=Lidl"))
        assertTrue(prompt.contains("Candidates:"))
        assertTrue(prompt.contains("targetId=3"))
    }

    @Test
    fun `parseResponse handles clean JSON`() {
        val result = service.parseResponse(
            """{"verdict":"LIKELY_DUPLICATE","matchedTargetType":"EXPENSE","matchedTargetId":3,"confidence":0.9,"rationale":"Amount and merchant are almost identical."}"""
        )

        assertNotNull(result)
        assertEquals(DuplicateVerdict.LIKELY_DUPLICATE, result!!.verdict)
        assertEquals(AiTargetType.EXPENSE, result.matchedTargetType)
        assertEquals(3L, result.matchedTargetId)
    }

    @Test
    fun `parseResponse handles markdown fenced JSON`() {
        val result = service.parseResponse(
            """
            ```json
            {"verdict":"UNCERTAIN","matchedTargetType":null,"matchedTargetId":null,"confidence":0.4,"rationale":"Signals are weak."}
            ```
            """.trimIndent()
        )

        assertNotNull(result)
        assertEquals(DuplicateVerdict.UNCERTAIN, result!!.verdict)
        assertNull(result.matchedTargetType)
        assertNull(result.matchedTargetId)
    }

    @Test
    fun `parseResponse returns null for invalid text`() {
        assertNull(service.parseResponse("not json"))
    }

    @Test
    fun `parseResponse returns null for unknown verdict`() {
        val result = service.parseResponse(
            """{"verdict":"MAYBE","matchedTargetType":"EXPENSE","matchedTargetId":3,"confidence":0.8}"""
        )

        assertNull(result)
    }

    @Test
    fun `parseResponse maps invalid matched target type to null`() {
        val result = service.parseResponse(
            """{"verdict":"UNCERTAIN","matchedTargetType":"OTHER","matchedTargetId":3,"confidence":0.4}"""
        )

        assertNotNull(result)
        assertNull(result!!.matchedTargetType)
    }

    @Test
    fun `parseResponse drops matchedTargetId when model emits zero`() {
        val result = service.parseResponse(
            """{"verdict":"LIKELY_DISTINCT","matchedTargetType":"EXPENSE","matchedTargetId":0,"confidence":0.7}"""
        )

        assertNotNull(result)
        assertNull(result!!.matchedTargetId)
    }

    @Test
    fun `parseResponse returns null for non-finite confidence`() {
        val result = service.parseResponse(
            """{"verdict":"LIKELY_DUPLICATE","matchedTargetType":"EXPENSE","matchedTargetId":3,"confidence":"NaN"}"""
        )

        assertNull(result)
    }
}
