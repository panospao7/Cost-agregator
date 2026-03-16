package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.domain.ai.model.ReceiptAssistInput
import org.junit.Assert.assertNull
import org.junit.Test

class CloudReceiptAssistServiceTest {

    @Test
    fun `suggest returns null safely when api key is absent or request unsupported`() {
        val service = CloudReceiptAssistService()

        val result = kotlinx.coroutines.runBlocking {
            service.suggest(
                ReceiptAssistInput(
                    receiptId = 1L,
                    rawOcrText = "LIDL HELLAS\nTOTAL 12.34\nDATE 2026-03-01",
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
}
