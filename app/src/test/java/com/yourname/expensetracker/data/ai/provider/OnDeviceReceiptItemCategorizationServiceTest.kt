package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.domain.ai.model.ReceiptItemCategorizationInput
import com.yourname.expensetracker.domain.dto.CategoryRef
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class OnDeviceReceiptItemCategorizationServiceTest {

    private val service = OnDeviceReceiptItemCategorizationService()

    @Test
    fun `categorizeItems uses keyword fallback for zero-overlap items`() = runTest {
        val input = ReceiptItemCategorizationInput(
            receiptId = 1L,
            merchant = "Cafe",
            lineItems = listOf(
                ReceiptParser.LineItem(
                    description = "coffee beans",
                    quantity = 1.0,
                    unitPrice = 4.5,
                    totalPrice = 4.5
                )
            ),
            userCategories = listOf(
                CategoryRef(id = 1L, name = "Misc"),
                CategoryRef(id = 2L, name = "Dining")
            ),
            totalTax = null,
            currency = "EUR"
        )

        val result = service.categorizeItems(input)

        assertNotNull(result)
        val item = result!!.items.single()
        assertEquals("Dining", item.suggestedCategory?.categoryName)
        assertEquals(0.6f, item.confidence)
    }
}
