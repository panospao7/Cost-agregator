package com.yourname.expensetracker.data.ai.provider

import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.GenerativeModel
import com.yourname.expensetracker.domain.ai.model.AiServiceError
import com.yourname.expensetracker.domain.ai.model.AiServiceResult
import com.yourname.expensetracker.domain.ai.model.AiTargetType
import com.yourname.expensetracker.domain.ai.model.DedupeCandidateSummary
import com.yourname.expensetracker.domain.ai.model.DedupeJudgeInput
import com.yourname.expensetracker.domain.ai.model.DuplicateVerdict
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import timber.log.Timber

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

    // ---- R6 (HD-3 / RG02B-1 / RG02B-4): bounded codes, no leaked text, CancellationException propagates ----

    private inline fun <T> withPlantedLogs(block: (MutableList<Pair<Throwable?, String>>) -> T): T {
        val logs = mutableListOf<Pair<Throwable?, String>>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                logs += t to message
            }
        }
        Timber.plant(tree)
        try {
            return block(logs)
        } finally {
            Timber.uproot(tree)
        }
    }

    /** Seeds the production model cache (no production change; avoids Generation.getClient()). */
    private fun serviceWithModel(model: GenerativeModel): OnDeviceDedupeJudgeService {
        val target = OnDeviceDedupeJudgeService()
        val field = OnDeviceDedupeJudgeService::class.java.getDeclaredField("cachedModel")
        field.isAccessible = true
        field.set(target, model)
        return target
    }

    @Test
    fun `parseResponse does not log throwable or sentinel on JSON failure`() {
        withPlantedLogs { logs ->
            val result = service.parseResponse("{ R6SENTINEL_9f2c }")

            assertNull(result)
            assertTrue(logs.any { it.second.contains("PARSER_FAILED") })
            assertTrue(logs.all { it.first == null && !it.second.contains("R6SENTINEL_9f2c") })
        }
    }

    @Test
    fun `judge maps unexpected exception to bounded UNKNOWN_ERROR without leaking sentinel`() {
        val model = mockk<GenerativeModel>()
        coEvery { model.generateContent(any<GenerateContentRequest>()) } throws
            IllegalStateException("R6SENTINEL_9f2c")

        withPlantedLogs { logs ->
            val result = runBlocking { serviceWithModel(model).judge(sampleInput) }

            assertEquals(
                AiServiceResult.Failure(AiServiceError.Unknown("UNKNOWN_ERROR")),
                result
            )
            assertFalse(result.toString().contains("R6SENTINEL_9f2c"))
            assertTrue(logs.all { it.first == null && !it.second.contains("R6SENTINEL_9f2c") })
        }
    }

    @Test
    fun `judge propagates CancellationException instead of mapping to Unknown`() {
        val model = mockk<GenerativeModel>()
        coEvery { model.generateContent(any<GenerateContentRequest>()) } throws
            CancellationException("R6SENTINEL_9f2c")

        withPlantedLogs { logs ->
            assertThrows(CancellationException::class.java) {
                runBlocking { serviceWithModel(model).judge(sampleInput) }
            }
            assertTrue(logs.none { it.second.contains("UNKNOWN_ERROR") })
        }
    }
}
