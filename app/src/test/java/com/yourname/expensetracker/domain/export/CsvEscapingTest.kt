package com.yourname.expensetracker.domain.export

import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import org.junit.Test
import java.util.Date

/**
 * CRITICAL TEST (CRITICAL-4): CSV/IIF Field Escaping
 * 
 * Tests proper escaping of special characters in export formats
 * to prevent injection attacks and format corruption.
 */
class CsvEscapingTest {

    // ==================== CSV ESCAPING TESTS ====================

    @Test
    fun `csv field without special characters is not escaped`() {
        val exporter = XeroCSVExporter()
        val field = "Normal merchant name"
        
        val result = exporter.export(
            listOf(createExpense(merchant = field)),
            mapOf()
        )
        
        assertThat(result).contains("Normal merchant name")
        assertThat(result).doesNotContain("\"Normal merchant name\"")
    }

    @Test
    fun `csv field with comma is wrapped in quotes`() {
        val exporter = XeroCSVExporter()
        val field = "Merchant, Inc."
        
        val result = exporter.export(
            listOf(createExpense(merchant = field)),
            mapOf()
        )
        
        assertThat(result).contains("\"Merchant, Inc.\"")
    }

    @Test
    fun `csv field with quotes has quotes doubled`() {
        val exporter = XeroCSVExporter()
        val field = "Merchant \"The Best\""
        
        val result = exporter.export(
            listOf(createExpense(merchant = field)),
            mapOf()
        )
        
        assertThat(result).contains("\"Merchant \"\"The Best\"\"\"")
    }

    @Test
    fun `csv field with newline is wrapped in quotes`() {
        val exporter = XeroCSVExporter()
        val field = "Merchant\nWith Newline"
        
        val result = exporter.export(
            listOf(createExpense(merchant = field)),
            mapOf()
        )
        
        assertThat(result).contains("\"Merchant\nWith Newline\"")
    }

    @Test
    fun `csv field with carriage return is wrapped in quotes`() {
        val exporter = XeroCSVExporter()
        val field = "Merchant\rWith CR"
        
        val result = exporter.export(
            listOf(createExpense(merchant = field)),
            mapOf()
        )
        
        assertThat(result).contains("\"Merchant\rWith CR\"")
    }

    @Test
    fun `csv field with multiple special characters is properly escaped`() {
        val exporter = XeroCSVExporter()
        val field = """Merchant "Big", Inc.""" + "\nLine 2"
        
        val result = exporter.export(
            listOf(createExpense(merchant = field)),
            mapOf()
        )
        
        // Should be wrapped in quotes and internal quotes doubled
        assertThat(result).containsMatch("\"Merchant \"\"Big\"\", Inc.\\nLine 2\"")
    }

    @Test
    fun `csv export handles empty string`() {
        val exporter = XeroCSVExporter()
        
        val result = exporter.export(
            listOf(createExpense(merchant = "")),
            mapOf()
        )
        
        assertThat(result).contains(",,99.99,Uncategorized,1")
    }

    @Test
    fun `csv export handles very long field`() {
        val exporter = XeroCSVExporter()
        val longField = "A".repeat(1000)
        
        val result = exporter.export(
            listOf(createExpense(merchant = longField)),
            mapOf()
        )
        
        assertThat(result).contains(longField)
    }

    // ==================== IIF ESCAPING TESTS ====================

    @Test
    fun `iif field without special characters is unchanged`() {
        val exporter = QuickBooksIIFExporter()
        val field = "Normal notes text"
        
        val result = exporter.export(
            listOf(createExpense(notes = field)),
            mapOf()
        )
        
        assertThat(result).contains("Normal notes text")
    }

    @Test
    fun `iif field with tab is replaced with space`() {
        val exporter = QuickBooksIIFExporter()
        val field = "Note\twith\ttabs"
        
        val result = exporter.export(
            listOf(createExpense(notes = field)),
            mapOf()
        )
        
        assertThat(result).contains("Note with tabs")
        assertThat(result).doesNotContain("\t")
    }

    @Test
    fun `iif field with newline is replaced with space`() {
        val exporter = QuickBooksIIFExporter()
        val field = "Note\nwith\nnewlines"
        
        val result = exporter.export(
            listOf(createExpense(notes = field)),
            mapOf()
        )
        
        assertThat(result).contains("Note with newlines")
    }

    @Test
    fun `iif field with carriage return is removed`() {
        val exporter = QuickBooksIIFExporter()
        val field = "Note\rwith\rCR"
        
        val result = exporter.export(
            listOf(createExpense(notes = field)),
            mapOf()
        )
        
        assertThat(result).contains("NotewithCR")
    }

    @Test
    fun `iif field with all special chars is properly escaped`() {
        val exporter = QuickBooksIIFExporter()
        val field = "Note\t\n\rwith\t\n\rall"
        
        val result = exporter.export(
            listOf(createExpense(notes = field)),
            mapOf()
        )
        
        assertThat(result).contains("Note   with   all")
    }

    @Test
    fun `iif export handles empty string`() {
        val exporter = QuickBooksIIFExporter()
        
        val result = exporter.export(
            listOf(createExpense(notes = "")),
            mapOf()
        )
        
        assertThat(result).contains("TRNS\t")
    }

    @Test
    fun `iif export preserves spaces at edges after trim`() {
        val exporter = QuickBooksIIFExporter()
        val field = "  Note with spaces  "
        
        val result = exporter.export(
            listOf(createExpense(notes = field)),
            mapOf()
        )
        
        // Trim removes leading/trailing spaces
        assertThat(result).contains("Note with spaces")
    }

    // ==================== EXPORT FORMAT TESTS ====================

    @Test
    fun `xero csv has correct header format`() {
        val exporter = XeroCSVExporter()
        
        val result = exporter.export(emptyList(), emptyMap())
        
        assertThat(result).startsWith("Date,Description,Amount,Account,Reference\n")
    }

    @Test
    fun `quickbooks iif has correct header format`() {
        val exporter = QuickBooksIIFExporter()
        
        val result = exporter.export(emptyList(), emptyMap())
        
        assertThat(result).startsWith("!TRNS\tDATE\tACCNT\tAMOUNT\tMEMO\tNAME\tCLASS\n")
        assertThat(result).contains("!SPL\tDATE\tACCNT\tAMOUNT\tMEMO\tNAME\tCLASS\n")
        assertThat(result).contains("!ENDTRNS\n")
    }

    @Test
    fun `freshbooks csv has correct header format`() {
        val exporter = FreshBooksExporter()
        
        val result = exporter.export(emptyList(), emptyMap())
        
        assertThat(result).startsWith("date,description,amount,category,vendor\n")
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
        val fields = dataLine.split(",")
        
        // Should have exactly 5 fields, not 8
        assertThat(fields.size).isEqualTo(5)
        assertThat(dataLine).contains("\"Normal,Evil,More,Fields\"")
    }

    @Test
    fun `iif escaping prevents delimiter injection attack`() {
        val exporter = QuickBooksIIFExporter()
        // Malicious input trying to inject extra IIF fields with tabs
        val malicious = "Note\tExtra\tFields"
        
        val result = exporter.export(
            listOf(createExpense(notes = malicious)),
            mapOf()
        )
        
        // Count actual tab delimiters in a TRNS line
        val lines = result.lines()
        val trnsLine = lines.find { it.startsWith("TRNS\t") }!!
        val tabCount = trnsLine.count { it == '\t' }
        
        // Should have exactly 6 tabs (7 fields)
        assertThat(tabCount).isEqualTo(6)
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
    fun `category name with comma is escaped`() {
        val exporter = XeroCSVExporter()
        val categoryWithComma = "Food, Dining"
        
        val result = exporter.export(
            listOf(createExpense()),
            mapOf(1L to categoryWithComma)
        )
        
        assertThat(result).contains("\"Food, Dining\"")
    }

    @Test
    fun `uncategorized fallback works correctly`() {
        val exporter = XeroCSVExporter()
        
        val result = exporter.export(
            listOf(createExpense()),
            emptyMap() // No category mapping
        )
        
        assertThat(result).contains("Uncategorized")
    }

    // ==================== HELPER METHODS ====================

    private fun createExpense(
        id: Long = 1,
        merchant: String = "Test Merchant",
        amount: Double = 99.99,
        notes: String? = null,
        categoryId: Long = 1,
        date: Long = Date().time
    ): Expense {
        return Expense(
            id = id,
            merchant = merchant,
            amount = amount,
            date = date,
            categoryId = categoryId,
            notes = notes,
            transactionType = TransactionType.PURCHASE
        )
    }
}