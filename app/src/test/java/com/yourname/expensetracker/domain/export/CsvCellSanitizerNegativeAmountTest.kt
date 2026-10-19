package com.yourname.expensetracker.domain.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P12-PR1: Verifies CsvCellSanitizer does not corrupt negative amounts while
 * still neutralizing every other leading formula-injection vector.
 *
 * Consolidated into two row tables (CSV and IIF). Every row is its own case:
 * a failure message names the row and shows input, expected and actual.
 */
class CsvCellSanitizerNegativeAmountTest {

    private data class Row(val name: String, val input: String, val expected: String)

    private fun assertRows(table: String, expectedSize: Int, rows: List<Row>, sanitize: (String) -> String) {
        assertEquals("$table size", expectedSize, rows.size)
        val failures = rows.mapNotNull { row ->
            val actual = sanitize(row.input)
            if (actual == row.expected) null
            else "row '${row.name}': input=<${row.input}> expected=<${row.expected}> actual=<$actual>"
        }
        assertTrue(
            "${failures.size}/${rows.size} rows failed:\n" + failures.joinToString("\n"),
            failures.isEmpty()
        )
    }

    // Non-numeric leading-dash text is NOT a plain number, so under the
    // NUMERIC-ONLY exception it is neutralized (CSV/DDE formula injection guard).
    // NEW-P12-003: dash vectors with a DIGIT or LETTER as char 2 are real
    // Excel/LibreOffice DDE payloads and must be neutralized, not passed raw.
    @Test
    fun `sanitize passes plain numbers and neutralizes every formula prefix`() {
        assertRows(
            "csv sanitize rows", 8,
            listOf(
                Row("negative decimal is not corrupted", "-15.50", "-15.50"),
                Row("negative integer is not corrupted", "-100", "-100"),
                Row("dash merchant name is neutralized", "-Pizza Place", "'-Pizza Place"),
                Row(
                    "dash formula starting with digit",
                    "-2+3+cmd|'/C calc'!A0",
                    "'-2+3+cmd|'/C calc'!A0"
                ),
                Row(
                    "dash formula starting with letter",
                    "-cmd|'/C calc'!A0",
                    "'-cmd|'/C calc'!A0"
                ),
                Row("equals prefix", "=SUM(A1:A10)", "'=SUM(A1:A10)"),
                Row("plus prefix", "+cmd|' /C calc'!A0", "'+cmd|' /C calc'!A0"),
                Row("at prefix", "@SUM(A1)", "'@SUM(A1)")
            )
        ) { CsvCellSanitizer.sanitize(it) }
    }

    // NEW-P12-007: same rules in the IIF (tab-delimited) sanitizer.
    @Test
    fun `sanitizeIif passes plain numbers and neutralizes every formula prefix`() {
        assertRows(
            "iif sanitize rows", 5,
            listOf(
                Row("negative amount is preserved", "-25.00", "-25.00"),
                Row("dash merchant name is neutralized", "-Burger Joint", "'-Burger Joint"),
                Row(
                    "dash formula starting with digit",
                    "-2+3+cmd|'/C calc'!A0",
                    "'-2+3+cmd|'/C calc'!A0"
                ),
                Row(
                    "dash formula starting with letter",
                    "-cmd|'/C calc'!A0",
                    "'-cmd|'/C calc'!A0"
                ),
                Row("equals prefix", "=IMPORTDATA(url)", "'=IMPORTDATA(url)")
            )
        ) { CsvCellSanitizer.sanitizeIif(it) }
    }
}
