package com.yourname.expensetracker.data.email.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class AmazonReceiptParserTest {

    private val parser = AmazonReceiptParser()

    @Test
    fun `parse handles standalone localized date and comma decimal amount`() {
        val receipt = parser.parse(
            emailBody = """
                <html>
                <body>
                <div>Amazon.de Bestellung</div>
                <div>Order Total: €12,34</div>
                <div>15 mars 2026</div>
                </body>
                </html>
            """.trimIndent(),
            receivedAt = 0L
        )

        assertNotNull(receipt)
        assertEquals(12.34, receipt!!.amount, 0.001)
        assertEquals(expectedLocalDateMillis("2026-03-15"), receipt.date)
    }

    private fun expectedLocalDateMillis(value: String): Long {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(value)!!.time
    }
}
