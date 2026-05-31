package com.yourname.expensetracker.domain.export

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P12-PR1: Verifies CsvCellSanitizer does not corrupt negative amounts or
 * merchant names starting with dash, while still blocking formula injection.
 */
class CsvCellSanitizerNegativeAmountTest {

    @Test
    fun `negative number is not corrupted`() {
        assertEquals("-15.50", CsvCellSanitizer.sanitize("-15.50"))
    }

    @Test
    fun `negative integer is not corrupted`() {
        assertEquals("-100", CsvCellSanitizer.sanitize("-100"))
    }

    @Test
    fun `merchant name starting with dash is not corrupted`() {
        assertEquals("-Pizza Place", CsvCellSanitizer.sanitize("-Pizza Place"))
    }

    @Test
    fun `formula injection with equals is still blocked`() {
        assertEquals("'=SUM(A1:A10)", CsvCellSanitizer.sanitize("=SUM(A1:A10)"))
    }

    @Test
    fun `formula injection with plus is still blocked`() {
        assertEquals("'+cmd|' /C calc'!A0", CsvCellSanitizer.sanitize("+cmd|' /C calc'!A0"))
    }

    @Test
    fun `formula injection with at is still blocked`() {
        assertEquals("'@SUM(A1)", CsvCellSanitizer.sanitize("@SUM(A1)"))
    }

    @Test
    fun `sanitizeIif preserves negative amount`() {
        assertEquals("-25.00", CsvCellSanitizer.sanitizeIif("-25.00"))
    }

    @Test
    fun `sanitizeIif preserves merchant with leading dash`() {
        assertEquals("-Burger Joint", CsvCellSanitizer.sanitizeIif("-Burger Joint"))
    }

    @Test
    fun `sanitizeIif blocks formula with equals`() {
        assertEquals("'=IMPORTDATA(url)", CsvCellSanitizer.sanitizeIif("=IMPORTDATA(url)"))
    }
}
