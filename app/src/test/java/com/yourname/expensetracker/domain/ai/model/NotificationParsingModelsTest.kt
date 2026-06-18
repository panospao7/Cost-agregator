package com.yourname.expensetracker.domain.ai.model

import com.yourname.expensetracker.domain.parser.ParsedTransactionType
import org.junit.Assert.assertThrows
import org.junit.Test

class NotificationParsingModelsTest {

    @Test
    fun `NotificationParseResult rejects non-positive amount`() {
        assertThrows(IllegalArgumentException::class.java) {
            NotificationParseResult(
                amount = 0.0,
                currency = "EUR",
                merchant = "Store",
                transactionType = ParsedTransactionType.PURCHASE,
                direction = null,
                confidence = 0.8f,
                reasoning = null
            )
        }
    }

    @Test
    fun `NotificationParseResult rejects out-of-range confidence`() {
        assertThrows(IllegalArgumentException::class.java) {
            NotificationParseResult(
                amount = 10.0,
                currency = "EUR",
                merchant = "Store",
                transactionType = ParsedTransactionType.PURCHASE,
                direction = null,
                confidence = 1.2f,
                reasoning = null
            )
        }
    }
}
