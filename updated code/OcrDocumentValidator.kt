package com.yourname.expensetracker

import com.yourname.expensetracker.domain.receipt.ReceiptParser
import java.io.File

/**
 * Direct OCR Test Document Validator
 * 
 * This utility reads the OCR_TEST_DOCUMENT.txt file directly and runs
 * validation tests on each section, printing detailed results.
 * 
 * Usage:
 * 1. Place OCR_TEST_DOCUMENT.txt in a known location
 * 2. Run this main() function
 * 3. Review output for pass/fail status
 * 
 * Can also be used as a unit test by calling runAllTests()
 */
class OcrDocumentValidator {

    private val parser = ReceiptParser()
    private var passCount = 0
    private var failCount = 0
    private val failures = mutableListOf<String>()

    data class TestCase(
        val section: String,
        val input: String,
        val expectedTotal: Double? = null,
        val expectedMerchant: String? = null,
        val expectedCurrency: String? = null,
        val expectedHasDate: Boolean = false,
        val expectedHasTax: Boolean = false,
        val description: String = ""
    )

    /**
     * Load and parse the OCR test document file
     */
    fun loadTestDocument(filePath: String): String {
        return try {
            File(filePath).readText()
        } catch (e: Exception) {
            println("❌ ERROR: Could not load file: $filePath")
            println("   Make sure the file exists and is accessible")
            ""
        }
    }

    /**
     * Extract a section from the document between two markers
     */
    private fun extractSection(fullText: String, startMarker: String, endMarker: String): String {
        val startIndex = fullText.indexOf(startMarker)
        val endIndex = fullText.indexOf(endMarker)
        
        return when {
            startIndex < 0 -> ""
            endIndex < 0 -> fullText.substring(startIndex)
            else -> fullText.substring(startIndex, endIndex)
        }
    }

    /**
     * Run a single test case and print results
     */
    fun runTest(test: TestCase) {
        print("  ${test.input.take(40).padEnd(40)} → ")
        
        try {
            val result = parser.parse(test.input)
            var passed = true
            val issues = mutableListOf<String>()

            // Check total
            if (test.expectedTotal != null) {
                if (result.total == null) {
                    passed = false
                    issues.add("Expected total ${test.expectedTotal}, got null")
                } else if (kotlin.math.abs(result.total - test.expectedTotal) > 0.01) {
                    passed = false
                    issues.add("Expected total ${test.expectedTotal}, got ${result.total}")
                }
            }

            // Check merchant
            if (test.expectedMerchant != null) {
                if (result.merchantName == null) {
                    passed = false
                    issues.add("Expected merchant '${test.expectedMerchant}', got null")
                } else if (!result.merchantName.contains(test.expectedMerchant, ignoreCase = true)) {
                    passed = false
                    issues.add("Expected merchant '${test.expectedMerchant}', got '${result.merchantName}'")
                }
            }

            // Check currency
            if (test.expectedCurrency != null) {
                if (result.currency != test.expectedCurrency) {
                    passed = false
                    issues.add("Expected currency '${test.expectedCurrency}', got '${result.currency}'")
                }
            }

            // Check date presence
            if (test.expectedHasDate && result.date == null) {
                passed = false
                issues.add("Expected date to be present")
            }

            // Check tax presence
            if (test.expectedHasTax && result.tax == null) {
                passed = false
                issues.add("Expected tax to be present")
            }

            // Record result
            if (passed) {
                println("✅ PASS")
                passCount++
            } else {
                println("❌ FAIL")
                issues.forEach { println("      ⚠️ $it") }
                failCount++
                failures.add("[${test.section}] ${test.input.take(30)}: ${issues.joinToString(", ")}")
            }

        } catch (e: Exception) {
            println("💥 ERROR: ${e.message}")
            failCount++
            failures.add("[${test.section}] ${test.input.take(30)}: Exception - ${e.message}")
        }
    }

    /**
     * Run all predefined tests
     */
    fun runAllTests() {
        println()
        println("═".repeat(70))
        println("         OCR TEST DOCUMENT VALIDATOR")
        println("═".repeat(70))

        // SECTION 14: Complete Receipt Lines
        println()
        println("━".repeat(70))
        println("SECTION 14: COMPLETE RECEIPT LINES")
        println("━".repeat(70))
        
        listOf(
            TestCase("S14", "ΣΥΝΟΛΟ € 50,00", 50.00, description = "Greek TOTAL"),
            TestCase("S14", "ΣΥΝΟΛΟ: 80,43 €", 80.43, description = "Greek TOTAL with colon"),
            TestCase("S14", "ΜΕΤΡΗΤΑ € 80,43", 80.43, description = "Greek CASH"),
            TestCase("S14", "ΜΕΤΡΗΤΑ: 25,74 ΕΥΡΩ", 25.74, expectedCurrency = "EUR", description = "Greek CASH with EUR"),
            TestCase("S14", "ΠΟΣΟ/AMOUNT: €80,43", 80.43, description = "Bilingual AMOUNT"),
            TestCase("S14", "nozo/AMOUNT: €35,00", 35.00, description = "OCR error ΠΟΣΟ"),
            TestCase("S14", "ZYNOAO: 182,00€", 182.00, description = "OCR error ΣΥΝΟΛΟ"),
            TestCase("S14", "EYNONO € 5,00", 5.00, description = "OCR error ΣΥΝΟΛΟ variant"),
            TestCase("S14", "ΣΥΝΟΛΙΚΗ ΑΞΙΑ: 20,01 ΕΥΡΩ", 20.01, expectedCurrency = "EUR", description = "Compound keyword"),
            TestCase("S14", "ΚΑΘΑΡΗ ΑΞΙΑ: 17,25 ΕΥΡΩ", expectedHasTax = true, expectedCurrency = "EUR", description = "Net value"),
        ).forEach { runTest(it) }

        // SECTION 22: Simulated OCR Errors
        println()
        println("━".repeat(70))
        println("SECTION 22: SIMULATED OCR ERRORS")
        println("━".repeat(70))
        
        listOf(
            TestCase("S22", "EYNONO\nTOTAL 5,00 €", 5.00, description = "EYNONO → ΣΥΝΟΛΟ"),
            TestCase("S22", "ZYNOAO\nTOTAL 182,00€", 182.00, description = "ZYNOAO → ΣΥΝΟΛΟ"),
            TestCase("S22", "2YNONO\nTOTAL 0,90 €", 0.90, description = "2YNONO → ΣΥΝΟΛΟ"),
            TestCase("S22", "METPHTA 25,74", 25.74, description = "METPHTA → ΜΕΤΡΗΤΑ"),
            TestCase("S22", "TOTAL 50,00 EYPΩ", 50.00, expectedCurrency = "EUR", description = "EYPΩ → ΕΥΡΩ"),
            TestCase("S22", "TOTAL 50,00 EYP9", 50.00, description = "EYP9 → ΕΥΡΩ"),
            TestCase("S22", "HM/NIA: 30/01/2026\nTOTAL 10,00 €", 10.00, expectedHasDate = true, description = "HM/NIA → ΗΜΕΡΟΜΗΝΙΑ"),
        ).forEach { runTest(it) }

        // SECTION 23: Actual OCR Output
        println()
        println("━".repeat(70))
        println("SECTION 23: ACTUAL OCR OUTPUT FROM RECEIPTS")
        println("━".repeat(70))
        
        listOf(
            TestCase("S23", "IYN. noZOTHTA\n50,00 €", 50.00, description = "Severe OCR error"),
            TestCase("S23", "ZYNOAO IONTAN\n182,00 €", 182.00, description = "OCR with extra text"),
            TestCase("S23", "ZYNOIO\n50,00 €", 50.00, description = "ZYNOIO variant"),
            TestCase("S23", "NAHPQTEO 10,00 €", 10.00, description = "NAHPQTEO → ΠΛΗΡΩΤΕΟ"),
            TestCase("S23", "METPHTA\n25,74 €", 25.74, description = "METPHTA actual"),
            TestCase("S23", "AEIA onA\n10,00 €", 10.00, description = "AEIA → ΑΞΙΑ (partial)"),
            TestCase("S23", "AEIA EEOAQN\n10,00 €", 10.00, description = "AEIA variant"),
        ).forEach { runTest(it) }

        // SECTION 5-6: Number Formats
        println()
        println("━".repeat(70))
        println("SECTION 5-6: NUMBER FORMATS")
        println("━".repeat(70))
        
        listOf(
            TestCase("NUM", "TOTAL 12,50 €", 12.50, description = "European decimal"),
            TestCase("NUM", "TOTAL 1.250,50 €", 1250.50, description = "European with thousands"),
            TestCase("NUM", "TOTAL 12.50 €", 12.50, description = "US decimal"),
            TestCase("NUM", "TOTAL 1,250.50 €", 1250.50, description = "US with thousands"),
        ).forEach { runTest(it) }

        // SECTION 7: Spacing Issues
        println()
        println("━".repeat(70))
        println("SECTION 7: NUMBERS WITH SPACING ISSUES")
        println("━".repeat(70))
        
        listOf(
            TestCase("S7", "TOTAL 45, 50 €", 45.50, description = "Space after comma"),
            TestCase("S7", "TOTAL 12 .50 €", 12.50, description = "Space before dot"),
            TestCase("S7", "TOTAL 1 250,50 €", 1250.50, description = "Space as thousands"),
            TestCase("S7", "TOTAL 100, 00 €", 100.00, description = "Multiple spaces"),
        ).forEach { runTest(it) }

        // SECTION 9: Date Formats
        println()
        println("━".repeat(70))
        println("SECTION 9: DATE FORMATS")
        println("━".repeat(70))
        
        listOf(
            TestCase("S9", "DATE: 30/01/2026\nTOTAL 10,00 €", 10.00, expectedHasDate = true, description = "DD/MM/YYYY"),
            TestCase("S9", "DATE: 30-01-2026\nTOTAL 10,00 €", 10.00, expectedHasDate = true, description = "DD-MM-YYYY"),
            TestCase("S9", "DATE: 30.01.2026\nTOTAL 10,00 €", 10.00, expectedHasDate = true, description = "DD.MM.YYYY"),
            TestCase("S9", "DATE: 30/01/26\nTOTAL 10,00 €", 10.00, expectedHasDate = true, description = "Short year"),
        ).forEach { runTest(it) }

        // SECTION 15: Merchant Names
        println()
        println("━".repeat(70))
        println("SECTION 15: MERCHANT NAMES")
        println("━".repeat(70))
        
        listOf(
            TestCase("S15", "ΣΚΛΑΒΕΝΙΤΗΣ\nΑΦΜ: 094206641\nTOTAL 50,00 €", 50.00, expectedMerchant = "ΣΚΛΑΒΕΝΙΤΗΣ", description = "Greek merchant"),
            TestCase("S15", "ΛΙΔΛ\nΑΘΗΝΑ\nTOTAL 35,00 €", 35.00, expectedMerchant = "ΛΙΔΛ", description = "LIDL Greek"),
            TestCase("S15", "CARREFOUR\nTOTAL 100,00 €", 100.00, expectedMerchant = "CARREFOUR", description = "English merchant"),
        ).forEach { runTest(it) }

        // Print summary
        println()
        println("═".repeat(70))
        println("                         SUMMARY")
        println("═".repeat(70))
        println()
        println("  Total Tests:  ${passCount + failCount}")
        println("  ✅ Passed:    $passCount")
        println("  ❌ Failed:    $failCount")
        println("  Success Rate: ${if (passCount + failCount > 0) "%.1f".format(passCount * 100.0 / (passCount + failCount)) else "0"}%")
        println()

        if (failures.isNotEmpty()) {
            println("━".repeat(70))
            println("                     FAILURES DETAIL")
            println("━".repeat(70))
            failures.forEach { println("  • $it") }
        }
        println()

        return Pair(passCount, failCount)
    }

    /**
     * Test a custom input string
     */
    fun testCustomInput(input: String): ReceiptParser.ParsedReceipt {
        println()
        println("━".repeat(70))
        println("CUSTOM INPUT TEST")
        println("━".repeat(70))
        println()
        println("INPUT:")
        println(input)
        println()
        println("─".repeat(70))
        println("RESULT:")
        println()

        val result = parser.parse(input)

        println("  Merchant:    ${result.merchantName ?: "❌ NOT FOUND"}")
        println("  Total:       ${result.total?.let { "%.2f".format(it) + " " + result.currency } ?: "❌ NOT FOUND"}")
        println("  Subtotal:    ${result.subtotal?.let { "%.2f".format(it) } ?: "N/A"}")
        println("  Tax:         ${result.tax?.let { "%.2f".format(it) } ?: "N/A"}")
        println("  Date:        ${result.date?.let { java.text.SimpleDateFormat("dd/MM/yyyy").format(java.util.Date(it)) } ?: "❌ NOT FOUND"}")
        println("  Currency:    ${result.currency}")
        println("  Line Items:  ${result.lineItems.size}")
        println("  Confidence:  ${"%.0f".format(result.confidence * 100)}%")
        println()

        if (result.lineItems.isNotEmpty()) {
            println("─".repeat(70))
            println("LINE ITEMS:")
            result.lineItems.forEach { item ->
                println("  • ${item.description}: ${"%.2f".format(item.totalPrice)}")
            }
        }
        println()

        return result
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val validator = OcrDocumentValidator()
            
            // Check if a custom input file was provided
            if (args.isNotEmpty()) {
                val filePath = args[0]
                println("Loading custom test file: $filePath")
                val customText = validator.loadTestDocument(filePath)
                if (customText.isNotEmpty()) {
                    validator.testCustomInput(customText)
                }
            } else {
                // Run standard test suite
                validator.runAllTests()
            }
        }
    }
}
