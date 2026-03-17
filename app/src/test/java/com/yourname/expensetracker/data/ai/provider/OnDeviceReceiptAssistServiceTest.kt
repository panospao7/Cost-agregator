package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.domain.ai.model.ReceiptAssistInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceReceiptAssistServiceTest {

    private val service = OnDeviceReceiptAssistService()

    private val sampleInput = ReceiptAssistInput(
        receiptId = 7L,
        rawOcrText = "LIDL HELLAS\nTOTAL 12.34\nDATE 2026-03-01",
        imagePath = null,
        imageMimeType = null,
        parsedMerchant = null,
        parsedTotal = null,
        parsedDate = null,
        parsedTaxAmount = null,
        currency = "EUR",
        lineItemsJson = null,
        currentTimeMs = 1_000L
    )

    @Test
    fun `buildPrompt includes OCR and parsed values`() {
        val prompt = service.buildPrompt(sampleInput)

        assertTrue(prompt.contains("LIDL HELLAS"))
        assertTrue(prompt.contains("Parsed merchant: none"))
        assertTrue(prompt.contains("Parsed total: none"))
        assertTrue(prompt.contains("Currency: EUR"))
    }

    @Test
    fun `buildPrompt includes JSON schema`() {
        val prompt = service.buildPrompt(sampleInput)

        assertTrue(prompt.contains("merchant"))
        assertTrue(prompt.contains("total"))
        assertTrue(prompt.contains("date"))
        assertTrue(prompt.contains("taxAmount"))
        assertTrue(prompt.contains("notes"))
    }

    @Test
    fun `parseResponse handles clean JSON`() {
        val result = service.parseResponse(
            """
            {"merchant":{"value":"Lidl","confidence":0.9,"rationale":"merchant line"},"total":{"value":12.34,"confidence":0.8,"rationale":"total line"},"date":{"value":1709251200000,"confidence":0.7,"rationale":"date line"},"taxAmount":null,"notes":["receipt is short"]}
            """.trimIndent()
        )

        assertNotNull(result)
        assertEquals("Lidl", result!!.merchant?.value)
        assertEquals(12.34, result.total?.value ?: 0.0, 0.001)
        assertEquals(1709251200000L, result.date?.value)
        assertEquals(listOf("receipt is short"), result.notes)
    }

    @Test
    fun `parseResponse handles markdown fenced JSON`() {
        val result = service.parseResponse(
            """
            ```json
            {"merchant":{"value":"Lidl"},"total":null,"date":null,"taxAmount":null,"notes":[]}
            ```
            """.trimIndent()
        )

        assertNotNull(result)
        assertEquals("Lidl", result!!.merchant?.value)
    }

    @Test
    fun `parseResponse returns null for invalid text`() {
        assertNull(service.parseResponse("not json"))
    }

    @Test
    fun `parseResponse keeps missing values as null`() {
        val result = service.parseResponse(
            """{"merchant":null,"total":null,"date":null,"taxAmount":null,"notes":[]}"""
        )

        assertNotNull(result)
        assertNull(result!!.merchant)
        assertNull(result.total)
        assertNull(result.date)
        assertNull(result.taxAmount)
    }
}
