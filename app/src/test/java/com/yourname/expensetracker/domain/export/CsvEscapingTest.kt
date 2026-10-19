package com.yourname.expensetracker.domain.export

import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.data.database.entity.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/**
 * CRITICAL TEST (CRITICAL-4): CSV/IIF Field Escaping
 *
 * Tests proper escaping of special characters in export formats
 * to prevent injection attacks and format corruption.
 *
 * Near-duplicate cases are consolidated into row tables; each row keeps its own
 * name so a failure identifies the exact case.
 */
class CsvEscapingTest {

    private data class CsvRow(
        val name: String,
        val merchant: String,
        val expectedContained: String,
        val forbidden: String? = null
    )

    private data class IifMemoRow(val name: String, val notes: String, val expectedMemo: String)

    private data class IifFormulaRow(
        val name: String,
        val merchant: String?,
        val notes: String?,
        val expectedField: String,
        val rawField: String
    )

    private fun assertNoFailures(total: Int, failures: List<String>) {
        assertTrue(
            "${failures.size}/$total rows failed:\n" + failures.joinToString("\n"),
            failures.isEmpty()
        )
    }

    // ==================== CSV ESCAPING TESTS ====================

    @Test
    fun `csv merchant escaping table`() {
        val exporter = XeroCSVExporter()
        val long = "A".repeat(1000)
        val rows = listOf(
            CsvRow(
                "no special characters is not quoted",
                "Normal merchant name",
                "Normal merchant name",
                forbidden = "\"Normal merchant name\""
            ),
            CsvRow("comma is wrapped in quotes", "Merchant, Inc.", "\"Merchant, Inc.\""),
            CsvRow(
                "quotes are doubled",
                "Merchant \"The Best\"",
                "\"Merchant \"\"The Best\"\"\""
            ),
            CsvRow(
                "newline is wrapped in quotes",
                "Merchant\nWith Newline",
                "\"Merchant\nWith Newline\""
            ),
            CsvRow(
                "carriage return is wrapped in quotes",
                "Merchant\rWith CR",
                "\"Merchant\rWith CR\""
            ),
            CsvRow(
                "multiple special characters",
                """Merchant "Big", Inc.""" + "\nLine 2",
                "\"Merchant \"\"Big\"\", Inc.\nLine 2\""
            ),
            CsvRow("very long field is preserved", long, long)
        )
        assertEquals("csv merchant escaping table size", 7, rows.size)
        val failures = rows.mapNotNull { row ->
            val result = exporter.export(listOf(createExpense(merchant = row.merchant)), mapOf())
            when {
                !result.contains(row.expectedContained) ->
                    "row '${row.name}': expected export to contain <${row.expectedContained}>"
                row.forbidden != null && result.contains(row.forbidden) ->
                    "row '${row.name}': export must not contain <${row.forbidden}>"
                else -> null
            }
        }
        assertNoFailures(rows.size, failures)
    }

    @Test
    fun `csv export handles empty string`() {
        val exporter = XeroCSVExporter()

        val result = exporter.export(
            listOf(createExpense(merchant = "")),
            mapOf()
        )

        // Xero schema: Date,Description,Amount,Currency,Account,Reference,...
        // Empty merchant -> empty Description; amount 99.99; currency EUR; Uncategorized; id 1.
        assertThat(result).contains(",,99.99,EUR,Uncategorized,1")
    }

    // ==================== IIF ESCAPING TESTS ====================

    @Test
    fun `iif memo escaping table`() {
        val exporter = QuickBooksIIFExporter()
        val rows = listOf(
            IifMemoRow("no special characters unchanged", "Normal notes text", "Normal notes text"),
            IifMemoRow("tab replaced with space", "Note\twith\ttabs", "Note with tabs"),
            IifMemoRow("newline replaced with space", "Note\nwith\nnewlines", "Note with newlines"),
            IifMemoRow("carriage return removed", "Note\rwith\rCR", "NotewithCR"),
            IifMemoRow("all special chars", "Note\t\n\rwith\t\n\rall", "Note  with  all"),
            IifMemoRow("edge spaces trimmed", "  Note with spaces  ", "Note with spaces"),
            IifMemoRow("empty string", "", ""),
            // Delimiter injection: tabs must not add IIF columns.
            IifMemoRow("tab delimiter injection stays one field", "Note\tExtra\tFields", "Note Extra Fields")
        )
        assertEquals("iif memo escaping table size", 8, rows.size)
        val failures = rows.mapNotNull { row ->
            val result = exporter.export(listOf(createExpense(notes = row.notes)), mapOf())
            val trnsLine = result.lines().firstOrNull { it.startsWith("TRNS\t") }
            when {
                trnsLine == null -> "row '${row.name}': no TRNS line"
                !trnsLine.contains("\t${row.expectedMemo}\t") ->
                    "row '${row.name}': TRNS line missing memo field <${row.expectedMemo}>: <$trnsLine>"
                // TRNS schema is TRNS DATE ACCNT AMOUNT CURRENCY MEMO NAME CLASS -> 7 tabs (8 fields)
                trnsLine.count { it == '\t' } != 7 ->
                    "row '${row.name}': expected 7 tabs, got ${trnsLine.count { it == '\t' }}"
                else -> null
            }
        }
        assertNoFailures(rows.size, failures)
    }

    // ============= P12-CURRENT-015: IIF formula-injection neutralization =======

    @Test
    fun `quickbooks iif formula prefix neutralization table`() {
        val exporter = QuickBooksIIFExporter()
        val rows = listOf(
            IifFormulaRow(
                "equals in merchant",
                merchant = "=cmd|'/c calc'!A1",
                notes = null,
                expectedField = "'=cmd|'/c calc'!A1",
                rawField = "=cmd|'/c calc'!A1"
            ),
            IifFormulaRow("plus in memo", merchant = null, notes = "+1+1", expectedField = "'+1+1", rawField = "+1+1"),
            IifFormulaRow(
                "at sign in merchant",
                merchant = "@SUM(A1)",
                notes = null,
                expectedField = "'@SUM(A1)",
                rawField = "@SUM(A1)"
            ),
            IifFormulaRow("minus in merchant", merchant = "-2+3", notes = null, expectedField = "'-2+3", rawField = "-2+3")
        )
        assertEquals("iif formula neutralization table size", 4, rows.size)
        val failures = rows.mapNotNull { row ->
            val expense = if (row.merchant != null) {
                createExpense(merchant = row.merchant, notes = row.notes)
            } else {
                createExpense(notes = row.notes)
            }
            val result = exporter.export(listOf(expense), mapOf())
            when {
                !result.contains(row.expectedField) ->
                    "row '${row.name}': expected neutralized <${row.expectedField}>"
                // The raw value must never appear as a bare, tab-delimited field.
                result.contains("\t${row.rawField}\t") ->
                    "row '${row.name}': raw formula <${row.rawField}> appears as a bare field"
                else -> null
            }
        }
        assertNoFailures(rows.size, failures)
    }

    // ==================== EXPORT FORMAT TESTS ====================

    @Test
    fun `export headers match each format schema`() {
        val xero = XeroCSVExporter().export(emptyList(), emptyMap())
        assertThat(xero).startsWith(
            "Date,Description,Amount,Currency,Account,Reference," +
                "OriginalCurrency,HomeCurrency,ConversionRate,OriginalAmount\n"
        )

        val iif = QuickBooksIIFExporter().export(emptyList(), emptyMap())
        assertThat(iif).startsWith("!TRNS\tDATE\tACCNT\tAMOUNT\tCURRENCY\tMEMO\tNAME\tCLASS\n")
        assertThat(iif).contains("!SPL\tDATE\tACCNT\tAMOUNT\tCURRENCY\tMEMO\tNAME\tCLASS\n")
        assertThat(iif).contains("!ENDTRNS\n")

        val freshBooks = FreshBooksExporter().export(emptyList(), emptyMap())
        assertThat(freshBooks).startsWith(
            "date,description,amount,currency,category,vendor," +
                "originalCurrency,homeCurrency,conversionRate,originalAmount\n"
        )
    }

    @Test
    fun `xero csv contains all expense data`() {
        val exporter = XeroCSVExporter()
        val expense = createExpense(
            id = 42,
            merchant = "Test Store",
            amount = 123.45
        )

        val result = exporter.export(
            listOf(expense),
            mapOf(1L to "Food")
        )

        assertThat(result).contains("Test Store")
        assertThat(result).contains("123.45")
        assertThat(result).contains("42")
    }

    @Test
    fun `quickbooks iif contains transaction pairs`() {
        val exporter = QuickBooksIIFExporter()
        val expense = createExpense(
            merchant = "Test Store",
            amount = 100.00
        )

        val result = exporter.export(
            listOf(expense),
            mapOf(1L to "Expenses")
        )

        // Each expense creates TRNS and SPL entries
        assertThat(result).contains("TRNS\t")
        assertThat(result).contains("SPL\t")
        assertThat(result).contains("ENDTRNS")
        // SPL has negative amount
        assertThat(result).containsMatch("SPL\\t.*\\t-100.0*\\t")
    }

    @Test
    fun `csv escaping prevents delimiter injection attack`() {
        val exporter = XeroCSVExporter()
        // Malicious input trying to inject extra CSV fields
        val malicious = "Normal,Evil,More,Fields"

        val result = exporter.export(
            listOf(createExpense(merchant = malicious)),
            mapOf()
        )

        // Should be a single field, not multiple fields
        val lines = result.lines().filter { it.isNotEmpty() }
        val dataLine = lines[1] // First data line after header
        val fields = parseCsvLine(dataLine)

        // Xero schema has 10 columns; the malicious value must stay ONE quoted field.
        assertThat(fields.size).isEqualTo(10)
        assertThat(dataLine).contains("\"Normal,Evil,More,Fields\"")
    }

    @Test
    fun `multiple expenses export correctly`() {
        val exporter = XeroCSVExporter()
        val expenses = listOf(
            createExpense(id = 1, merchant = "Store A", amount = 10.00),
            createExpense(id = 2, merchant = "Store B", amount = 20.00),
            createExpense(id = 3, merchant = "Store C", amount = 30.00)
        )

        val result = exporter.export(expenses, mapOf(1L to "Category"))

        val lines = result.lines().filter { it.isNotEmpty() }
        assertThat(lines.size).isEqualTo(4) // 1 header + 3 data lines
        assertThat(result).contains("Store A")
        assertThat(result).contains("Store B")
        assertThat(result).contains("Store C")
    }

    @Test
    fun `category cell table comma quoting and uncategorized fallback`() {
        val exporter = XeroCSVExporter()

        val withComma = exporter.export(listOf(createExpense()), mapOf(1L to "Food, Dining"))
        assertThat(withComma).contains("\"Food, Dining\"")

        val unmapped = exporter.export(listOf(createExpense()), emptyMap())
        assertThat(unmapped).contains("Uncategorized")
    }

    // ==================== HELPER METHODS ====================

    private fun createExpense(
        id: Long = 1,
        merchant: String = "Test Merchant",
        amount: Double = 99.99,
        notes: String? = null,
        categoryId: Long = 1,
        date: Long = Date().time
    ): ExportTransaction {
        return ExportTransaction(
            id = id,
            merchant = merchant,
            amount = amount,
            date = date,
            categoryId = categoryId,
            notes = notes,
            currency = "EUR",
            transactionType = TransactionType.PURCHASE,
            sourceAccountName = "Cash"
        )
    }

    private fun parseCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0

        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"')
                    i++
                }

                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> {
                    fields.add(current.toString())
                    current.clear()
                }

                else -> current.append(ch)
            }
            i++
        }

        fields.add(current.toString())
        return fields
    }
}
