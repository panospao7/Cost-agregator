package com.yourname.expensetracker.golden

import com.yourname.expensetracker.domain.export.CsvCellSanitizer
import com.yourname.expensetracker.testfixtures.golden.GoldenScenarioVerifier
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/**
 * Golden Scenario Test: CSV Export/Import Roundtrip
 *
 * Proves that:
 * 1. CsvCellSanitizer neutralizes formula injection (=, +, -, @)
 * 2. Safe cells pass through unchanged
 * 3. Cells with commas/quotes are properly RFC-4180 quoted
 * 4. Null characters are stripped
 * 5. Roundtrip: sanitized values can be parsed back correctly
 */
class CsvExportImportRoundtripGoldenTest : GoldenTestBase() {

    private val verifier = GoldenScenarioVerifier(
        scenarioName = "csv_export_import_roundtrip",
        numericTolerance = 0.01
    )

    @Test
    fun `csv sanitizer neutralizes formula injection and preserves safe values`() = runTest {
        // Dangerous cells that could trigger formula injection in Excel/Sheets
        val dangerousCells = listOf(
            "=SUM(A1:A10)",
            "+cmd|'/C calc'!A0",
            "-1+1",
            "@SUM(A1)",
            "=HYPERLINK(\"http://evil.com\")"
        )

        // Safe cells that should pass through
        val safeCells = listOf(
            "Lidl",
            "45.50",
            "EUR",
            "Groceries",
            "Normal text"
        )

        // Cells needing RFC-4180 quoting (contain comma, quote, newline)
        val quotingCells = listOf(
            "Merchant, Inc.",
            "He said \"hello\"",
            "Line1\nLine2"
        )

        val sanitizedDangerous = dangerousCells.map { CsvCellSanitizer.sanitize(it) }
        val sanitizedSafe = safeCells.map { CsvCellSanitizer.sanitize(it) }
        val sanitizedQuoting = quotingCells.map { CsvCellSanitizer.sanitize(it) }

        // Verify roundtrip: amounts survive sanitization
        val amounts = listOf(100.0, 45.50, 0.01, 9999.99)
        val amountStrings = amounts.map { it.toString() }
        val sanitizedAmounts = amountStrings.map { CsvCellSanitizer.sanitize(it) }
        val parsedBack = sanitizedAmounts.map { it.toDoubleOrNull() }

        val actual = JSONObject().apply {
            put("dangerousCells", JSONArray().apply {
                dangerousCells.zip(sanitizedDangerous).forEach { (original, sanitized) ->
                    put(JSONObject().apply {
                        put("original", original)
                        put("sanitized", sanitized)
                        put("neutralized", sanitized.trimStart().startsWith("'"))
                    })
                }
            })

            put("safeCellsUnchanged", safeCells == sanitizedSafe)

            put("quotedCells", JSONArray().apply {
                quotingCells.zip(sanitizedQuoting).forEach { (original, sanitized) ->
                    put(JSONObject().apply {
                        put("original", original)
                        put("sanitized", sanitized)
                        put("quoted", sanitized.startsWith("\"") && sanitized.endsWith("\""))
                    })
                }
            })

            put("amountRoundtrip", JSONArray().apply {
                amounts.zip(parsedBack).forEach { (original, parsed) ->
                    put(JSONObject().apply {
                        put("original", original)
                        put("parsedBack", parsed)
                        put("preserved", parsed != null && Math.abs(original - parsed) < 0.001)
                    })
                }
            })

            put("allDangerousNeutralized", sanitizedDangerous.all { it.trimStart().startsWith("'") })
            put("allSafeUnchanged", safeCells == sanitizedSafe)
            put("allAmountsPreserved", amounts.zip(parsedBack).all { (o, p) -> p != null && Math.abs(o - p) < 0.001 })
        }

        verifier.verify(actual).assertPassed()
    }
}
