package com.yourname.expensetracker.data.database.converter

import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.ai.model.AiCapability
import org.junit.Assert.*
import org.junit.Test

class ConvertersTest {
    private val converters = Converters()

    @Test
    fun `converts PURCHASE to string and back`() {
        val str = converters.fromTransactionType(TransactionType.PURCHASE)
        assertEquals("PURCHASE", str)
        assertEquals(TransactionType.PURCHASE, converters.toTransactionType(str))
    }

    @Test
    fun `converts all TransactionTypes roundtrip`() {
        assertTrue("TransactionType.entries must not be empty", TransactionType.entries.isNotEmpty())
        TransactionType.entries.forEach { type ->
            val str = converters.fromTransactionType(type)
            assertEquals(type, converters.toTransactionType(str))
        }
    }

    @Test
    fun `unparseable strings return UNKNOWN`() {
        val inputs = listOf("INVALID_TYPE", "")
        assertEquals("unparseable inputs size", 2, inputs.size)
        inputs.forEach { input ->
            assertEquals(
                "Input '$input' should map to UNKNOWN",
                TransactionType.UNKNOWN,
                converters.toTransactionType(input)
            )
        }
    }

    // ── AiCapability (ai-fixes B2a, R7) ─────────────────────────────────────────

    @Test
    fun `AiCapability persisted names round-trip for every entry including TRANSACTION_INSIGHT`() {
        assertTrue(AiCapability.TRANSACTION_INSIGHT in AiCapability.entries)
        AiCapability.entries.forEach { capability ->
            val str = converters.fromAiCapability(capability)
            assertEquals(capability.name, str)
            assertEquals(capability, converters.toAiCapability(str))
        }
        assertEquals("TRANSACTION_INSIGHT", converters.fromAiCapability(AiCapability.TRANSACTION_INSIGHT))
    }

    @Test
    fun `AiCapability enum is append-only with TRANSACTION_INSIGHT last and legacy names unchanged`() {
        val names = AiCapability.entries.map { it.name }
        assertEquals("TRANSACTION_INSIGHT", names.last())
        assertEquals(
            listOf(
                "DASHBOARD_BRIEFING",
                "REVIEW_EXPLANATION",
                "QUERY_INTERPRETATION",
                "RECEIPT_EXTRACTION",
                "WARRANTY_EXTRACTION",
                "CATEGORIZATION_FALLBACK",
                "DEDUPE_JUDGE",
                "LOCATION_SUMMARY",
                "NOTIFICATION_PARSE",
                "REVIEW_PRIORITIZATION",
                "SEMANTIC_DEDUPE",
                "RECEIPT_ITEM_CATEGORIZATION"
            ),
            names.dropLast(1)
        )
    }

    @Test
    fun `unknown AiCapability names still fall back to DASHBOARD_BRIEFING`() {
        val inputs = listOf("NOT_A_CAPABILITY", "", "transaction_insight")
        assertEquals("unknown capability inputs size", 3, inputs.size)
        inputs.forEach { input ->
            assertEquals(
                "Input '$input' should map to DASHBOARD_BRIEFING",
                AiCapability.DASHBOARD_BRIEFING,
                converters.toAiCapability(input)
            )
        }
    }
}
