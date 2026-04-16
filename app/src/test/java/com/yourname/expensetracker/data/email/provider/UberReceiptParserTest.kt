package com.yourname.expensetracker.data.email.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class UberReceiptParserTest {

    private val parser = UberReceiptParser()

    @Test
    fun `parse uses timestamped ride date subgroup instead of am pm token`() {
        val receipt = parser.parse(
            emailBody = """
                Uber trip receipt
                Total ${'$'}23.45
                Your driver: Alex
                9:15 PM · March 07
                Trip ID: ride-123
            """.trimIndent(),
            receivedAt = 42L
        )

        assertNotNull(receipt)
        assertEquals(23.45, receipt!!.amount, 0.001)
        assertEquals(expectedCurrentYearMillis("MMMM dd", "March 07"), receipt.date)
    }

    @Test
    fun `parse handles localized ride total and labeled date`() {
        val receipt = parser.parse(
            emailBody = """
                Uber trip receipt
                Total €12,34
                Trip date: 15 mars 2026
                Trip ID: ride-456
            """.trimIndent(),
            receivedAt = 0L
        )

        assertNotNull(receipt)
        assertEquals(12.34, receipt!!.amount, 0.001)
        assertEquals(expectedLocalDateMillis("2026-03-15"), receipt.date)
    }

    @Test
    fun `parse handles localized eats total and order date`() {
        val receipt = parser.parse(
            emailBody = """
                Uber Eats receipt
                Restaurant: Burger Place
                Order Total 18,90 €
                Order date: 15 mars 2026
                Order ID: eats-789
            """.trimIndent(),
            receivedAt = 0L
        )

        assertNotNull(receipt)
        assertEquals(18.90, receipt!!.amount, 0.001)
        assertEquals(expectedLocalDateMillis("2026-03-15"), receipt.date)
        assertEquals("Uber Eats - Burger Place", receipt.merchant)
    }

    private fun expectedLocalDateMillis(value: String): Long {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(value)!!.time
    }

    private fun expectedCurrentYearMillis(pattern: String, value: String): Long {
        val parsed = SimpleDateFormat(pattern, Locale.US).parse(value)!!
        return Calendar.getInstance().apply {
            time = parsed
            set(Calendar.YEAR, Calendar.getInstance().get(Calendar.YEAR))
        }.timeInMillis
    }
}
