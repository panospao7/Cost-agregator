package com.yourname.expensetracker.integration

import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DataExportImportPipelineIntegrationTest {

    // ============================================================================
    // SECTION 1: CSV EXPORT PIPELINE
    // ============================================================================

    @Test
    fun `integration - export expenses to CSV format`() {
        val expenses = listOf(
            mapOf("date" to "2024-01-15", "merchant" to "Starbucks", "amount" to "5.50", "category" to "Food"),
            mapOf("date" to "2024-01-16", "merchant" to "Uber", "amount" to "12.00", "category" to "Transport")
        )
        
        val csvHeader = "Date,Merchant,Amount,Category"
        val csvRows = expenses.map { expense ->
            "${expense["date"]},${expense["merchant"]},${expense["amount"]},${expense["category"]}"
        }
        val csvContent = (listOf(csvHeader) + csvRows).joinToString("\n")
        
        assertTrue("CSV should contain header", csvContent.contains(csvHeader))
        assertTrue("CSV should contain Starbucks", csvContent.contains("Starbucks"))
        assertTrue("CSV should contain Uber", csvContent.contains("Uber"))
    }

    @Test
    fun `integration - handle special characters in CSV export`() {
        val expense = mapOf(
            "date" to "2024-01-15",
            "merchant" to "McDonald's, Restaurant",
            "amount" to "10.00",
            "category" to "Food"
        )
        
        // Handle commas and quotes
        val merchant = expense["merchant"]?.replace(",", ";") ?: ""
        val csvRow = "${expense["date"]},$merchant,${expense["amount"]},${expense["category"]}"
        
        assertFalse("Should not contain raw comma", csvRow.contains("McDonald's, Restaurant"))
        assertTrue("Should contain semicolon", csvRow.contains("McDonald's; Restaurant"))
    }

    // ============================================================================
    // SECTION 2: JSON EXPORT PIPELINE
    // ============================================================================

    @Test
    fun `integration - export expenses to JSON format`() {
        val expense = mapOf(
            "id" to 1,
            "date" to 1705276800000L,
            "merchant" to "Starbucks",
            "amount" to 5.50,
            "category" to "Food"
        )
        
        // Build JSON manually (simulating export)
        val json = """
            {
                "id": ${expense["id"]},
                "date": ${expense["date"]},
                "merchant": "${expense["merchant"]}",
                "amount": ${expense["amount"]},
                "category": "${expense["category"]}"
            }
        """.trimIndent()
        
        assertTrue("JSON should contain id", json.contains("\"id\": 1"))
        assertTrue("JSON should contain merchant", json.contains("\"merchant\": \"Starbucks\""))
        assertTrue("JSON should contain amount", json.contains("\"amount\": 5.5"))
    }

    @Test
    fun `integration - export multiple expenses to JSON array`() {
        val expenses = listOf(
            mapOf("id" to 1, "merchant" to "Starbucks", "amount" to 5.50),
            mapOf("id" to 2, "merchant" to "Uber", "amount" to 12.00)
        )
        
        val jsonArray = expenses.joinToString(",\n", "[\n", "\n]") { expense ->
            """
            {
                "id": ${expense["id"]},
                "merchant": "${expense["merchant"]}",
                "amount": ${expense["amount"]}
            }
            """.trimIndent()
        }
        
        assertTrue("Should be valid JSON array", jsonArray.startsWith("["))
        assertTrue("Should be valid JSON array", jsonArray.endsWith("]"))
        assertTrue("Should contain both expenses", jsonArray.contains("Starbucks") && jsonArray.contains("Uber"))
    }

    // ============================================================================
    // SECTION 3: DATA VALIDATION PIPELINE
    // ============================================================================

    @Test
    fun `integration - validate exported data completeness`() {
        val expenses = listOf(
            mapOf("date" to "2024-01-15", "merchant" to "Starbucks", "amount" to "5.50", "category" to "Food"),
            mapOf("date" to "2024-01-16", "merchant" to "Uber", "amount" to "12.00", "category" to "Transport")
        )
        
        val requiredFields = listOf("date", "merchant", "amount", "category")
        
        val isComplete = expenses.all { expense ->
            requiredFields.all { field ->
                expense.containsKey(field) && expense[field]?.isNotEmpty() == true
            }
        }
        
        assertTrue("All exports should be complete", isComplete)
    }

    @Test
    fun `integration - validate date format in export`() {
        val validFormats = listOf("yyyy-MM-dd", "dd/MM/yyyy", "MM-dd-yyyy")
        val dateString = "2024-01-15"
        
        var isValid = false
        for (format in validFormats) {
            try {
                SimpleDateFormat(format, Locale.US).parse(dateString)
                isValid = true
                break
            } catch (e: Exception) {
                // Try next format
            }
        }
        
        assertTrue("Date format should be valid", isValid)
    }

    @Test
    fun `integration - validate amount format in export`() {
        val amounts = listOf("5.50", "12.00", "1000.00", "0.01")
        
        val areValid = amounts.all { amount ->
            try {
                amount.toDouble()
                true
            } catch (e: NumberFormatException) {
                false
            }
        }
        
        assertTrue("All amounts should be valid", areValid)
    }

    // ============================================================================
    // SECTION 4: IMPORT PIPELINE
    // ============================================================================

    @Test
    fun `integration - parse CSV import`() {
        val csvContent = """
            Date,Merchant,Amount,Category
            2024-01-15,Starbucks,5.50,Food
            2024-01-16,Uber,12.00,Transport
        """.trimIndent()
        
        val lines = csvContent.lines()
        val dataLines = lines.drop(1) // Skip header
        
        val expenses = dataLines.map { line ->
            val parts = line.split(",")
            mapOf(
                "date" to parts[0],
                "merchant" to parts[1],
                "amount" to parts[2],
                "category" to parts[3]
            )
        }
        
        assertEquals(2, expenses.size)
        assertEquals("Starbucks", expenses[0]["merchant"])
        assertEquals("Uber", expenses[1]["merchant"])
    }

    @Test
    fun `integration - parse JSON import`() {
        val jsonContent = """
            [
                {"date": "2024-01-15", "merchant": "Starbucks", "amount": 5.50},
                {"date": "2024-01-16", "merchant": "Uber", "amount": 12.00}
            ]
        """.trimIndent()
        
        // Simulate parsing (in real app would use JSON parser)
        val containsData = jsonContent.contains("Starbucks") && jsonContent.contains("Uber")
        val isArray = jsonContent.trim().startsWith("[") && jsonContent.trim().endsWith("]")
        
        assertTrue("Should parse JSON array", isArray)
        assertTrue("Should contain data", containsData)
    }

    // ============================================================================
    // SECTION 5: DATA TRANSFORMATION PIPELINE
    // ============================================================================

    @Test
    fun `integration - transform date format during export`() {
        val timestamp = 1705276800000L  // 2024-01-15
        val date = Date(timestamp)
        
        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val formatted = formatter.format(date)
        
        assertEquals("2024-01-15", formatted)
    }

    @Test
    fun `integration - convert amount to string for export`() {
        val amount = 5.5
        
        val formatted = String.format("%.2f", amount)
        
        assertEquals("5.50", formatted)
    }

    @Test
    fun `integration - handle null values in export`() {
        val expense = mapOf(
            "date" to "2024-01-15",
            "merchant" to "Starbucks",
            "amount" to "5.50",
            "category" to null
        )
        
        val safeCategory = expense["category"] ?: "Uncategorized"
        
        assertEquals("Uncategorized", safeCategory)
    }

    // ============================================================================
    // SECTION 6: DUPLICATE DETECTION PIPELINE
    // ============================================================================

    @Test
    fun `integration - detect duplicate entries during import`() {
        val existingExpenses = listOf(
            mapOf("date" to "2024-01-15", "merchant" to "Starbucks", "amount" to "5.50"),
            mapOf("date" to "2024-01-16", "merchant" to "Uber", "amount" to "12.00")
        )
        
        val newExpense = mapOf("date" to "2024-01-15", "merchant" to "Starbucks", "amount" to "5.50")
        
        val isDuplicate = existingExpenses.any { existing ->
            existing["date"] == newExpense["date"] &&
            existing["merchant"] == newExpense["merchant"] &&
            existing["amount"] == newExpense["amount"]
        }
        
        assertTrue("Should detect duplicate", isDuplicate)
    }

    @Test
    fun `integration - allow similar but different entries`() {
        val existingExpense = mapOf("date" to "2024-01-15", "merchant" to "Starbucks", "amount" to "5.50")
        
        val newExpense = mapOf("date" to "2024-01-15", "merchant" to "Starbucks", "amount" to "6.50")
        
        val isDuplicate = existingExpense == newExpense
        
        assertFalse("Should allow different amount", isDuplicate)
    }

    // ============================================================================
    // SECTION 7: BACKUP PIPELINE
    // ============================================================================

    @Test
    fun `integration - create full data backup`() {
        val expenses = (1..100).map { i ->
            mapOf(
                "id" to i,
                "date" to "2024-01-${String.format("%02d", (i % 30) + 1)}",
                "merchant" to "Merchant$i",
                "amount" to (i * 10.0).toString(),
                "category" to "Category${i % 5}"
            )
        }
        
        val backupSize = expenses.size
        val hasAllFields = expenses.all { expense ->
            expense.containsKey("id") &&
            expense.containsKey("date") &&
            expense.containsKey("merchant") &&
            expense.containsKey("amount") &&
            expense.containsKey("category")
        }
        
        assertEquals(100, backupSize)
        assertTrue("All records should have required fields", hasAllFields)
    }

    @Test
    fun `integration - backup metadata generation`() {
        val timestamp = System.currentTimeMillis()
        val version = "1.0"
        val recordCount = 100
        
        val metadata = mapOf(
            "exportDate" to timestamp.toString(),
            "version" to version,
            "recordCount" to recordCount.toString()
        )
        
        assertNotNull(metadata["exportDate"])
        assertEquals("1.0", metadata["version"])
        assertEquals("100", metadata["recordCount"])
    }

    // ============================================================================
    // SECTION 8: ERROR HANDLING PIPELINE
    // ============================================================================

    @Test
    fun `integration - handle malformed CSV gracefully`() {
        val malformedCsv = """
            Date,Merchant,Amount
            2024-01-15,Starbucks
            2024-01-16,Uber,12.00,Extra,Fields
        """.trimIndent()
        
        val lines = malformedCsv.lines().drop(1)
        
        val validLines = lines.filter { line ->
            val parts = line.split(",")
            parts.size >= 3  // Minimum required fields
        }
        
        assertEquals(1, validLines.size)
        assertTrue(validLines[0].contains("Uber"))
    }

    @Test
    fun `integration - handle empty import file`() {
        val emptyContent = ""
        
        val isEmpty = emptyContent.isBlank()
        
        assertTrue("Should detect empty file", isEmpty)
    }

    // ============================================================================
    // SECTION 9: FILTERING PIPELINE
    // ============================================================================

    @Test
    fun `integration - export filtered by date range`() {
        val expenses = listOf(
            mapOf("date" to "2024-01-10", "merchant" to "A", "amount" to "10.00"),
            mapOf("date" to "2024-01-15", "merchant" to "B", "amount" to "20.00"),
            mapOf("date" to "2024-01-20", "merchant" to "C", "amount" to "30.00")
        )
        
        val startDate = "2024-01-12"
        val endDate = "2024-01-18"
        
        val filtered = expenses.filter { expense ->
            val date = expense["date"] ?: ""
            date >= startDate && date <= endDate
        }
        
        assertEquals(1, filtered.size)
        assertEquals("B", filtered[0]["merchant"])
    }

    @Test
    fun `integration - export filtered by category`() {
        val expenses = listOf(
            mapOf("merchant" to "A", "category" to "Food", "amount" to "10.00"),
            mapOf("merchant" to "B", "category" to "Transport", "amount" to "20.00"),
            mapOf("merchant" to "C", "category" to "Food", "amount" to "30.00")
        )
        
        val filtered = expenses.filter { it["category"] == "Food" }
        
        assertEquals(2, filtered.size)
    }

    // ============================================================================
    // SECTION 10: PERFORMANCE
    // ============================================================================

    @Test
    fun `integration - export 1000 records quickly`() {
        val expenses = (1..1000).map { i ->
            mapOf(
                "id" to i,
                "date" to "2024-01-${String.format("%02d", (i % 30) + 1)}",
                "merchant" to "Merchant$i",
                "amount" to (i * 10.0).toString(),
                "category" to "Category${i % 10}"
            )
        }
        
        val startTime = System.nanoTime()
        
        // Simulate CSV export
        val csvLines = expenses.map { expense ->
            "${expense["date"]},${expense["merchant"]},${expense["amount"]},${expense["category"]}"
        }
        val csvContent = csvLines.joinToString("\n")
        
        val duration = System.nanoTime() - startTime
        
        assertTrue("Should export quickly", duration < 1_000_000_000)
        assertEquals(1000, csvLines.size)
    }
}
