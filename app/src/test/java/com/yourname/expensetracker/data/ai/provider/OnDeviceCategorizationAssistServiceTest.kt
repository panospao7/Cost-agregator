package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.ai.model.AiTargetType
import com.yourname.expensetracker.domain.ai.model.CategorizationAssistInput
import com.yourname.expensetracker.domain.ai.model.CategoryOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceCategorizationAssistServiceTest {

    private val service = OnDeviceCategorizationAssistService()

    private val sampleInput = CategorizationAssistInput(
        targetType = AiTargetType.PENDING_REVIEW,
        targetId = 42L,
        merchant = "Lidl",
        amount = 24.5,
        currency = "EUR",
        transactionType = DomainTransactionType.PURCHASE,
        date = null,
        currentCategoryId = null,
        deterministicMatchType = "FALLBACK",
        deterministicExplanation = "weak deterministic match",
        candidateCategories = listOf(
            CategoryOption(1L, "Groceries"),
            CategoryOption(2L, "Transport"),
            CategoryOption(3L, "Entertainment")
        ),
        supportingText = null
    )

    private data class PromptRow(
        val name: String,
        val input: CategorizationAssistInput,
        val mustContain: List<String>,
        val mustNotContain: List<String>
    )

    /**
     * Success-path parse row. A null expectation means "not asserted for this row";
     * [expectNullConfidence] asserts the confidence field is absent.
     */
    private data class ParseRow(
        val name: String,
        val response: String,
        val categoryId: Long? = null,
        val categoryName: String? = null,
        val confidence: Float? = null,
        val expectNullConfidence: Boolean = false,
        val rationale: String? = null,
        val alternativeCategoryIds: List<Long>? = null
    )

    private data class RejectRow(val name: String, val response: String)

    // -----------------------------------------------------------------------
    // Prompt building
    // -----------------------------------------------------------------------

    @Test
    fun `buildPrompt table covers merchant amount categories schema and supporting text`() {
        val rows = listOf(
            PromptRow(
                name = "base prompt with null supportingText",
                input = sampleInput,
                mustContain = listOf(
                    "Lidl", "24.5", "EUR",
                    "1:Groceries", "2:Transport", "3:Entertainment",
                    "categoryId", "categoryName", "confidence", "rationale"
                ),
                mustNotContain = listOf("Context:")
            ),
            PromptRow(
                name = "supportingText present",
                input = sampleInput.copy(supportingText = "purchased weekly groceries"),
                mustContain = listOf("purchased weekly groceries"),
                mustNotContain = emptyList()
            )
        )
        assertEquals("prompt table size guard", 2, rows.size)

        rows.forEach { row ->
            val prompt = service.buildPrompt(row.input)
            row.mustContain.forEach {
                assertTrue("[${row.name}] prompt should contain '$it'", prompt.contains(it))
            }
            row.mustNotContain.forEach {
                assertTrue("[${row.name}] prompt should not contain '$it'", !prompt.contains(it))
            }
        }
    }

    // -----------------------------------------------------------------------
    // Response parsing — valid payloads
    // -----------------------------------------------------------------------

    @Test
    fun `parseResponse table accepts valid payloads and normalizes fields`() {
        val rows = listOf(
            ParseRow(
                name = "clean JSON",
                response = """{"categoryId":1,"categoryName":"Groceries","confidence":0.92,"rationale":"Lidl is a grocery store"}""",
                categoryId = 1L,
                categoryName = "Groceries",
                confidence = 0.92f,
                rationale = "Lidl is a grocery store"
            ),
            ParseRow(
                name = "markdown fences",
                response = "```json\n" +
                    """{"categoryId":2,"categoryName":"Transport","confidence":0.85,"rationale":"bus fare"}""" +
                    "\n```",
                categoryId = 2L,
                categoryName = "Transport"
            ),
            ParseRow(
                name = "leading text",
                response = "Based on the merchant name, I suggest:\n" +
                    """{"categoryId":1,"categoryName":"Groceries","confidence":0.9,"rationale":"supermarket"}""",
                categoryId = 1L
            ),
            ParseRow(
                name = "alternativeCategoryIds present",
                response = """{"categoryId":1,"categoryName":"Groceries","confidence":0.8,"rationale":"food","alternativeCategoryIds":[2,3]}""",
                alternativeCategoryIds = listOf(2L, 3L)
            ),
            ParseRow(
                name = "confidence field missing",
                response = """{"categoryId":1,"categoryName":"Groceries","rationale":"store"}""",
                expectNullConfidence = true
            ),
            ParseRow(
                name = "categoryName whitespace trimmed",
                response = """{"categoryId":1,"categoryName":"  Groceries  ","confidence":0.9,"rationale":"test"}""",
                categoryName = "Groceries"
            ),
            ParseRow(
                name = "alternativeCategoryIds absent gives empty list",
                response = """{"categoryId":1,"categoryName":"Groceries"}""",
                alternativeCategoryIds = emptyList()
            ),
            ParseRow(
                name = "invalid alternative ids filtered",
                response = """{"categoryId":1,"categoryName":"Groceries","alternativeCategoryIds":[2,0,-1,"abc",3]}""",
                alternativeCategoryIds = listOf(2L, 3L)
            )
        )
        assertEquals("valid parse table size guard", 8, rows.size)

        rows.forEach { row ->
            val result = service.parseResponse(row.response)
            assertNotNull("[${row.name}] expected a parsed suggestion", result)
            result!!
            row.categoryId?.let { assertEquals("[${row.name}] categoryId", it, result.categoryId) }
            row.categoryName?.let { assertEquals("[${row.name}] categoryName", it, result.categoryName) }
            row.confidence?.let {
                assertNotNull("[${row.name}] confidence should be present", result.confidence)
                assertEquals("[${row.name}] confidence", it, result.confidence!!, 0.01f)
            }
            if (row.expectNullConfidence) {
                assertNull("[${row.name}] confidence should be null", result.confidence)
            }
            row.rationale?.let { assertEquals("[${row.name}] rationale", it, result.rationale) }
            row.alternativeCategoryIds?.let {
                assertEquals("[${row.name}] alternativeCategoryIds", it, result.alternativeCategoryIds)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Response parsing — rejected payloads
    // -----------------------------------------------------------------------

    @Test
    fun `parseResponse table rejects invalid payloads with null`() {
        val rows = listOf(
            RejectRow("empty string", ""),
            RejectRow("non-JSON text", "I don't know how to categorize this"),
            RejectRow("categoryId missing", """{"categoryName":"Groceries","confidence":0.9}"""),
            RejectRow("categoryName blank", """{"categoryId":1,"categoryName":"","confidence":0.9}"""),
            RejectRow("categoryId zero", """{"categoryId":0,"categoryName":"Groceries","confidence":0.9}"""),
            RejectRow("confidence not finite", """{"categoryId":1,"categoryName":"Groceries","confidence":"NaN"}"""),
            RejectRow("confidence out of bounds", """{"categoryId":1,"categoryName":"Groceries","confidence":1.2}"""),
            RejectRow("malformed JSON", "{categoryId: 1, broken")
        )
        assertEquals("reject table size guard", 8, rows.size)

        rows.forEach { row ->
            assertNull("[${row.name}] must be rejected", service.parseResponse(row.response))
        }
    }
}
