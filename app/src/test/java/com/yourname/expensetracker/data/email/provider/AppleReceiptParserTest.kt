package com.yourname.expensetracker.data.email.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class AppleReceiptParserTest {

    private val parser = AppleReceiptParser()

    @Test
    fun `parse handles standalone localized date and comma decimal amount`() {
        val receipt = parser.parse(
            emailBody = """
                Apple Services Receipt
                Order ID: MT123456789
                Total €9,99
                15 mars 2026
                apple.com/bill
            """.trimIndent(),
            receivedAt = 0L
        )

        assertNotNull(receipt)
        assertEquals(9.99, receipt!!.amount, 0.001)
        assertEquals(expectedLocalDateMillis("2026-03-15"), receipt.date)
    }

    @Test
    fun `parse does not infer EUR from incidental substring tokens`() {
        val receipt = parser.parse(
            emailBody = """
                Apple Services Receipt
                Order ID: MT123456789
                Total ${'$'}9.99
                ORDER DETAILS FOR MUSIC SUBSCRIPTION
                apple.com/bill
            """.trimIndent(),
            receivedAt = 0L
        )

        assertNotNull(receipt)
        assertEquals("USD", receipt!!.currency)
        assertNotEquals("EUR", receipt.currency)
    }

    private fun expectedLocalDateMillis(value: String): Long {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(value)!!.time
    }
}
