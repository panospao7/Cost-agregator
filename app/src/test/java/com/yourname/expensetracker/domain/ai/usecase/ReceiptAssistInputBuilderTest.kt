package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.policy.AiPolicy
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReceiptAssistInputBuilderTest {

    private lateinit var aiPolicy: AiPolicy
    private lateinit var timeProvider: FakeTimeProvider
    private lateinit var builder: ReceiptAssistInputBuilder

    @Before
    fun setup() {
        aiPolicy = mockk()
        timeProvider = FakeTimeProvider(1_710_000_000_000L)
        builder = ReceiptAssistInputBuilder(aiPolicy, timeProvider)
    }

    @Test
    fun `build keeps contextual receipt fields when redaction off`() {
        every { aiPolicy.shouldRedact(any(), AiCapability.RECEIPT_EXTRACTION) } returns false
        val receipt = makeReceipt(
            rawOcrText = "LIDL HELLAS\nCARD 1111 2222 3333 4444\nTOTAL 12.34",
            parsedItems = "{\"items\":true}"
        )

        val result = builder.build(receipt, AiSettings(aiEnabled = true, receiptAssistEnabled = true))

        assertEquals(receipt.id, result.receiptId)
        assertEquals(receipt.rawOcrText, result.rawOcrText)
        assertEquals(receipt.imagePath, result.imagePath)
        assertEquals("image/jpeg", result.imageMimeType)
        assertEquals(receipt.parsedItems, result.lineItemsJson)
        assertFalse(result.redactBeforeCloud)
        assertEquals(timeProvider.now(), result.currentTimeMs)
    }

    @Test
    fun `build includes local image metadata when image cloud assist is enabled`() {
        every { aiPolicy.shouldRedact(any(), AiCapability.RECEIPT_EXTRACTION) } returns false
        val receipt = makeReceipt(rawOcrText = "ΑΒ ΒΑΣΙΛΟΠΟΥΛΟΣ")

        val result = builder.build(
            receipt.copy(imagePath = "receipt.jpg"),
            AiSettings(aiEnabled = true, receiptAssistEnabled = true, receiptImageCloudEnabled = true)
        )

        assertEquals("receipt.jpg", result.imagePath)
        assertEquals("image/jpeg", result.imageMimeType)
    }

    @Test
    fun `build redacts long sensitive numeric values when redaction on`() {
        every { aiPolicy.shouldRedact(any(), AiCapability.RECEIPT_EXTRACTION) } returns true
        val receipt = makeReceipt(
            rawOcrText = "CARD 4111 1111 1111 1111\nIBAN GR1601101250000000012300695\nPHONE 2101234567"
        )

        val result = builder.build(receipt, AiSettings(aiEnabled = true, receiptAssistEnabled = true))

        assertTrue(result.rawOcrText.contains("[REDACTED_CARD]"))
        assertTrue(result.rawOcrText.contains("[REDACTED_IBAN]"))
        assertFalse(result.rawOcrText.contains("2101234567"))
        assertEquals(receipt.imagePath, result.imagePath)
        assertEquals("image/jpeg", result.imageMimeType)
        assertEquals(null, result.lineItemsJson)
        assertTrue(result.redactBeforeCloud)
    }

    private fun makeReceipt(
        rawOcrText: String = "OCR TEXT",
        parsedItems: String? = null
    ) = ScannedReceipt(
        id = 7L,
        imagePath = "receipt.jpg",
        rawOcrText = rawOcrText,
        parsedTotal = 12.34,
        parsedMerchant = "Lidl",
        parsedDate = 1234L,
        parsedItems = parsedItems,
        parsedTaxAmount = 1.99,
        currency = "EUR",
        confidence = 0.45f
    )
}
