package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.domain.ai.model.ReceiptAssistInput
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf

class CloudReceiptAssistServiceTest {

    @Test
    fun `suggest returns null safely when api key is absent or request unsupported`() {
        val settingsRepository = mockk<AiSettingsRepository>()
        every { settingsRepository.settings() } returns flowOf(AiSettings(aiEnabled = true, receiptAssistEnabled = true))
        val service = CloudReceiptAssistService(settingsRepository, "")

        val result = kotlinx.coroutines.runBlocking {
            service.suggest(
                ReceiptAssistInput(
                    receiptId = 1L,
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
            )
        }

        assertNull(result)
    }

    @Test
    fun `usedImageInput only reports true when image metadata exists`() {
        val settingsRepository = mockk<AiSettingsRepository>()
        every { settingsRepository.settings() } returns flowOf(AiSettings())
        val service = CloudReceiptAssistService(settingsRepository, "")

        assertFalse(
            service.usedImageInput(
                ReceiptAssistInput(
                    receiptId = 1L,
                    rawOcrText = "OCR",
                    imagePath = null,
                    imageMimeType = null,
                    parsedMerchant = null,
                    parsedTotal = null,
                    parsedDate = null,
                    parsedTaxAmount = null,
                    currency = "EUR",
                    lineItemsJson = null,
                    currentTimeMs = 1L
                )
            )
        )
    }

    @Test
    fun `buildRequestBodyForTest includes inline image data when allowed`() {
        val settingsRepository = mockk<AiSettingsRepository>()
        every { settingsRepository.settings() } returns flowOf(AiSettings())
        val service = CloudReceiptAssistService(settingsRepository, "")
        val imageFile = kotlin.io.path.createTempFile(suffix = ".jpg").toFile().apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }

        try {
            val requestBody = service.buildRequestBodyForTest(
                ReceiptAssistInput(
                    receiptId = 1L,
                    rawOcrText = "OCR",
                    imagePath = imageFile.absolutePath,
                    imageMimeType = "image/jpeg",
                    parsedMerchant = null,
                    parsedTotal = null,
                    parsedDate = null,
                    parsedTaxAmount = null,
                    currency = "EUR",
                    lineItemsJson = null,
                    currentTimeMs = 1L
                ),
                allowImage = true
            )

            assertTrue(requestBody.contains("inlineData"))
            assertTrue(requestBody.contains("image/jpeg"))
        } finally {
            imageFile.delete()
        }
    }
}
