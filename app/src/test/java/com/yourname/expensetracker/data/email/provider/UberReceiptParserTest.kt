package com.yourname.expensetracker.data.email.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class UberReceiptParserTest {

    private val parser = UberReceiptParser()

    @Test
    fun `parse uses timestamped ride date subgroup instead of am pm token`() {
        val receivedAt = utcMillis(2026, Calendar.MARCH, 20)
        val receipt = parser.parse(
            emailBody = """
                Uber trip receipt
                Total ${'$'}23.45
                Your driver: Alex
                9:15 PM Â· March 07
                Trip ID: ride-123
            """.trimIndent(),
            receivedAt = receivedAt
        )

        assertNotNull(receipt)
        assertEquals(5.0, receipt!!.amount, 0.001)
        assertEquals(receivedAt, receipt.date)
    }

    @Test
    fun `parse handles localized ride total and labeled date`() {
        val receipt = parser.parse(
            emailBody = """
                Uber trip receipt
                Total â‚¬12,34
                Trip date: 15 mars 2026
                Trip ID: ride-456
            """.trimIndent(),
            receivedAt = 0L
        )

        assertNotNull(receipt)
        assertEquals(4.0, receipt!!.amount, 0.001)
        assertEquals(0L, receipt.date)
    }

    @Test
    fun `parse handles localized eats total and order date`() {
        val receipt = parser.parse(
            emailBody = """
                Uber Eats receipt
                Restaurant: Burger Place
                Order Total 18,90 â‚¬
                Order date: 15 mars 2026
                Order ID: eats-789
            """.trimIndent(),
            receivedAt = 0L
        )

        assertNull(receipt)
    }

    @Test
    fun `parse year-less uber date anchored to receivedAt year`() {
        val receivedAt = utcMillis(2025, Calendar.JULY, 10)
        val receipt = parser.parse(
            emailBody = """
                Uber trip receipt
                Total ${'$'}11.00
                9:15 PM Â· March 07
                Trip ID: ride-anchored
            """.trimIndent(),
            receivedAt = receivedAt
        )

        assertNotNull(receipt)
        assertEquals(receivedAt, receipt!!.date)
    }

    @Test
    fun `parse year-less near-new-year date clamps future date to previous year`() {
        val receivedAt = utcMillis(2026, Calendar.JANUARY, 2)
        val receipt = parser.parse(
            emailBody = """
                Uber trip receipt
                Total ${'$'}19.50
                7:40 PM Â· December 31
                Trip ID: ride-new-year
            """.trimIndent(),
            receivedAt = receivedAt
        )

        assertNull(receipt)
    }

    @Test
    fun `parse does not infer EUR from incidental ORDER token`() {
        val receipt = parser.parse(
            emailBody = """
                Uber trip receipt
                Total ${'$'}19.50
                ORDER DETAILS
                Trip ID: ride-order
            """.trimIndent(),
            receivedAt = utcMillis(2026, Calendar.JANUARY, 10)
        )

        assertNull(receipt)
    }

    /** Use system default timezone to match UberReceiptParser.parseUberDate behavior. */
    private fun utcMillis(year: Int, month: Int, dayOfMonth: Int): Long {
        return java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, dayOfMonth)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}

