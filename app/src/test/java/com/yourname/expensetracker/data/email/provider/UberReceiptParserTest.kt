package com.yourname.expensetracker.data.email.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
                9:15 PM · March 07
                Trip ID: ride-123
            """.trimIndent(),
            receivedAt = receivedAt
        )

        assertNotNull(receipt)
        assertEquals(23.45, receipt!!.amount, 0.001)
        assertEquals(systemZoneMillis(2026, Calendar.MARCH, 7), receipt.date)
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
        assertEquals(systemZoneMillis(2026, Calendar.MARCH, 15), receipt.date)
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
        assertEquals("EUR", receipt.currency)
        assertEquals(systemZoneMillis(2026, Calendar.MARCH, 15), receipt.date)
    }

    @Test
    fun `parse year-less uber date anchored to receivedAt year`() {
        val receivedAt = utcMillis(2025, Calendar.JULY, 10)
        val receipt = parser.parse(
            emailBody = """
                Uber trip receipt
                Total ${'$'}11.00
                9:15 PM · March 07
                Trip ID: ride-anchored
            """.trimIndent(),
            receivedAt = receivedAt
        )

        assertNotNull(receipt)
        assertEquals(systemZoneMillis(2025, Calendar.MARCH, 7), receipt!!.date)
    }

    @Test
    fun `parse year-less near-new-year date clamps future date to previous year`() {
        val receivedAt = utcMillis(2026, Calendar.JANUARY, 2)
        val receipt = parser.parse(
            emailBody = """
                Uber trip receipt
                Total ${'$'}19.50
                7:40 PM · December 31
                Trip ID: ride-new-year
            """.trimIndent(),
            receivedAt = receivedAt
        )

        assertNotNull(receipt)
        assertEquals(systemZoneMillis(2025, Calendar.DECEMBER, 31), receipt!!.date)
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

        assertNotNull(receipt)
        assertEquals("USD", receipt!!.currency)
    }

    // -------------------------------------------------------------------------
    // RP-18 18-A: label hierarchy, strict total grammar, currency resolution.
    // -------------------------------------------------------------------------

    @Test
    fun `parse prefers Order Total over bare Total for eats orders`() {
        val receipt = parser.parse(
            emailBody = """
                Uber Eats receipt
                Restaurant: Burger Place
                Total 5,00
                Order Total 18,90 €
                Order date: 15 mars 2026
                Order ID: eats-ordering
            """.trimIndent(),
            receivedAt = 0L
        )

        assertNotNull(receipt)
        assertEquals(
            "Specific 'Order Total' must win over a bare 'Total' line",
            18.90,
            receipt!!.amount,
            0.001
        )
        assertEquals("EUR", receipt.currency)
    }

    @Test
    fun `parse ignores vat annotation and unit price forms for rides`() {
        val receipt = parser.parse(
            emailBody = """
                Uber trip receipt
                2 x 5,00 €
                Total incl. 21% IVA: 12,10 €
                Trip ID: ride-vat
            """.trimIndent(),
            receivedAt = 0L
        )

        assertNotNull(receipt)
        assertEquals(
            "VAT percentage and unit-price forms must not be selected as the total",
            12.10,
            receipt!!.amount,
            0.001
        )
        assertEquals("EUR", receipt.currency)
    }

    @Test
    fun `parse skips with CURRENCY_UNRESOLVED when no trusted currency signal exists`() {
        val body = """
            Uber trip receipt
            Total 23,45
            Trip ID: ride-nocurrency
        """.trimIndent()

        val outcome = parser.parseWithOutcome(emailBody = body, receivedAt = 0L)

        assertTrue(
            "Expected CURRENCY_UNRESOLVED skip, got $outcome",
            outcome is EmailParseOutcome.Skipped &&
                outcome.reason == EmailParseSkipReason.CURRENCY_UNRESOLVED
        )
        assertNull(
            "Skipped parse must surface as null from parse()",
            parser.parse(emailBody = body, receivedAt = 0L)
        )
    }

    @Test
    fun `parse does not infer currency from everyday words it us de`() {
        val body = """
            Uber trip receipt
            Total 19,50
            Hope you enjoyed it. Rate us. de
            Trip ID: ride-words
        """.trimIndent()

        val outcome = parser.parseWithOutcome(emailBody = body, receivedAt = 0L)

        assertTrue(
            "Expected CURRENCY_UNRESOLVED skip, got $outcome",
            outcome is EmailParseOutcome.Skipped &&
                outcome.reason == EmailParseSkipReason.CURRENCY_UNRESOLVED
        )
        assertNull(
            "Skipped parse must surface as null from parse()",
            parser.parse(emailBody = body, receivedAt = 0L)
        )
    }

    @Test
    fun `delayed yearless receipt retains its ride date rather than the email date`() {
        val receipt = parser.parse(
            emailBody = """
                Uber trip receipt
                Total EUR 11.00
                March 07 at 9:15 PM
                Trip ID: delayed-ride
            """.trimIndent(),
            receivedAt = systemZoneMillis(2025, Calendar.JULY, 10)
        )

        assertNotNull(receipt)
        assertEquals(systemZoneMillis(2025, Calendar.MARCH, 7), receipt!!.date)
    }

    @Test
    fun `delayed new year receipt retains the previous year date`() {
        val receipt = parser.parse(
            emailBody = """
                Uber trip receipt
                Total EUR 11.00
                December 31 at 7:40 PM
                Trip ID: delayed-new-year
            """.trimIndent(),
            receivedAt = systemZoneMillis(2026, Calendar.FEBRUARY, 28)
        )

        assertNotNull(receipt)
        assertEquals(systemZoneMillis(2025, Calendar.DECEMBER, 31), receipt!!.date)
    }

    @Test
    fun `explicit ride year is preserved independently of delivery date`() {
        val receipt = parser.parse(
            emailBody = """
                Uber trip receipt
                Total EUR 11.00
                Trip date: March 07, 2024
                Trip ID: explicit-old-year
            """.trimIndent(),
            receivedAt = systemZoneMillis(2026, Calendar.JULY, 10)
        )

        assertNotNull(receipt)
        assertEquals(systemZoneMillis(2024, Calendar.MARCH, 7), receipt!!.date)
    }

    @Test
    fun `missing ride date still falls back to the received timestamp`() {
        val receivedAt = systemZoneMillis(2026, Calendar.JULY, 10)
        val receipt = parser.parse(
            emailBody = "Uber trip receipt\nTotal EUR 11.00\nTrip ID: undated-ride",
            receivedAt = receivedAt
        )

        assertNotNull(receipt)
        assertEquals(receivedAt, receipt!!.date)
    }

    /** UTC delivery-timestamp fixture; parsed ride dates use the system zone. */
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

    private fun systemZoneMillis(year: Int, month: Int, dayOfMonth: Int): Long {
        return java.util.Calendar.getInstance().apply {
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

