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

    // Non-numeric leading-dash text is NOT a plain number, so under the
    // NUMERIC-ONLY exception it is treated as potentially dangerous and
    // neutralized (CSV/DDE formula injection guard). A merchant like
    // "-Pizza Place" gets a leading "'" rather than being passed raw.
    @Test
    fun `merchant name starting with dash is neutralized as potentially dangerous`() {
        assertEquals("'-Pizza Place", CsvCellSanitizer.sanitize("-Pizza Place"))
    }

    // NEW-P12-003: dangerous dash vector with a DIGIT as char 2 must be
    // neutralized, not passed raw. "-2+3+cmd|'/C calc'!A0" is a real
    // Excel/LibreOffice DDE injection payload.
    @Test
    fun `dangerous dash formula starting with digit is neutralized`() {
        assertEquals(
            "'-2+3+cmd|'/C calc'!A0",
            CsvCellSanitizer.sanitize("-2+3+cmd|'/C calc'!A0")
        )
    }

    // NEW-P12-003: dangerous dash vector with a LETTER as char 2 must be
    // neutralized, not passed raw.
    @Test
    fun `dangerous dash formula starting with letter is neutralized`() {
        assertEquals(
            "'-cmd|'/C calc'!A0",
            CsvCellSanitizer.sanitize("-cmd|'/C calc'!A0")
        )
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

    // NEW-P12-007: non-numeric leading-dash text is NOT a plain number, so it
    // is treated as potentially dangerous and neutralized in IIF export too.
    @Test
    fun `sanitizeIif neutralizes merchant with leading dash`() {
        assertEquals("'-Burger Joint", CsvCellSanitizer.sanitizeIif("-Burger Joint"))
    }

    // NEW-P12-007: dangerous dash vectors must be neutralized in IIF export.
    @Test
    fun `sanitizeIif neutralizes dangerous dash formula starting with digit`() {
        assertEquals(
            "'-2+3+cmd|'/C calc'!A0",
            CsvCellSanitizer.sanitizeIif("-2+3+cmd|'/C calc'!A0")
        )
    }

    @Test
    fun `sanitizeIif neutralizes dangerous dash formula starting with letter`() {
        assertEquals(
            "'-cmd|'/C calc'!A0",
            CsvCellSanitizer.sanitizeIif("-cmd|'/C calc'!A0")
        )
    }

    @Test
    fun `sanitizeIif blocks formula with equals`() {
        assertEquals("'=IMPORTDATA(url)", CsvCellSanitizer.sanitizeIif("=IMPORTDATA(url)"))
    }
}
