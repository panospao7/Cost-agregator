package com.yourname.expensetracker

import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import java.util.regex.Pattern
import java.util.regex.MatchResult

/**
 * OCR & Receipt Parser Test Suite
 * 
 * This test class validates:
 * 1. Greek OCR character recognition
 * 2. Keyword normalization patterns
 * 3. Decimal number parsing
 * 4. Date extraction
 * 5. Total amount extraction
 * 
 * Run with: ./gradlew test --tests "com.yourname.expensetracker.OcrParserTest"
 */
class OcrParserTest {

    // ============================================
    // TEST DATA FROM ACTUAL RECEIPTS
    // ============================================
    
    /**
     * Real OCR output from 16 scanned receipts
     * Format: Pair<Raw OCR Text, Expected Parsed Values>
     */
    private val realReceiptData = listOf(
        ReceiptTestData(
            id = 36,
            rawOcr = """
                PORTOBELLO'S
                KOTPRTEIOE EYArrEAOE ABEE
                IYN. noZOTHTA : 0,00
                METPHTA
                114.90
                80,43
            """.trimIndent(),
            expectedTotal = 80.43,
            expectedMerchant = "PORTOBELLO",
            description = "Portobello receipt - Greek keywords garbled"
        ),
        ReceiptTestData(
            id = 35,
            rawOcr = """
                cardlink
                PORTOBELLOS
                AGIA PARASKEYH
                nozo/AMOUNT: €80,43
                EYXAPIETOYME - THANK YOU
            """.trimIndent(),
            expectedTotal = 80.43,
            expectedMerchant = "PORTOBELLOS",
            description = "Portobello card receipt - cardlink format"
        ),
        ReceiptTestData(
            id = 34,
            rawOcr = """
                PINTERSPORT
                1 X 48.88
                ZYNOAO NPO OPOY : 35.09
                35.09 €
            """.trimIndent(),
            expectedTotal = 35.09,
            expectedMerchant = "PINTERSPORT",
            description = "Pintersport - Decimal already in US format"
        ),
        ReceiptTestData(
            id = 33,
            rawOcr = """
                eOPONOr IRH ANOOEIEH ENAPEH
                OHMOKPATIAE 20
                EYNONO
                E 5,00
            """.trimIndent(),
            expectedTotal = 5.00,
            expectedMerchant = "OHMOKPATIAE",
            description = "Oikonomika - ΣΥΝΟΛΟ → EYNONO"
        ),
        ReceiptTestData(
            id = 32,
            rawOcr = """
                CRYSTAI AND DESIGN HOUSE
                THESSAI ONIKH
                nozo/AMOUNT: €35,00
            """.trimIndent(),
            expectedTotal = 35.00,
            expectedMerchant = "CRYSTAI",
            description = "Crystal card receipt"
        ),
        ReceiptTestData(
            id = 31,
            rawOcr = """
                IRANNIAOY 2 NANOPAMA
                AIAMANTHE MAZOYTHE A.E.
                ZYNOAO : 4,70 EUR
                4,70
            """.trimIndent(),
            expectedTotal = 4.70,
            expectedMerchant = "AIAMANTHE",
            description = "Diamantis supermarket"
        ),
        ReceiptTestData(
            id = 30,
            rawOcr = """
                STEPSPORT
                KAPTA AAAATHE
                18.90
            """.trimIndent(),
            expectedTotal = 18.90,
            expectedMerchant = "STEPSPORT",
            description = "Stepsport - Decimal US format"
        ),
        ReceiptTestData(
            id = 29,
            rawOcr = """
                TAMEIO 2 /304
                AIAMANTHE MAZ OYTHE A.E
                ZYNOAO
                METPHTA
                25,74 EUR
            """.trimIndent(),
            expectedTotal = 25.74,
            expectedMerchant = "AIAMANTHE",
            description = "Diamantis - Correct parsing"
        ),
        ReceiptTestData(
            id = 28,
            rawOcr = """
                EYNONO
                METPHTA
                3.90 13.00
                3.90 13.00%
            """.trimIndent(),
            expectedTotal = 7.80,
            expectedMerchant = null,
            description = "Hobbs coffee - VAT percentages present"
        ),
        ReceiptTestData(
            id = 26,
            rawOcr = """
                NEPOYT202T0P10
                AM 1248063626OY MHAOY
                45.50
            """.trimIndent(),
            expectedTotal = 45.50,
            expectedMerchant = "NEPOYT",
            description = "Katien restaurant - US decimal"
        ),
        ReceiptTestData(
            id = 25,
            rawOcr = """
                O EPMHE
                TIMH MONAS0E : 1.574 EYPQ/AT
                ZYNOIO : 20,01 EYPQ
            """.trimIndent(),
            expectedTotal = 20.01,
            expectedMerchant = "EPMHE",
            description = "Shell fuel - Unit price present"
        ),
        ReceiptTestData(
            id = 24,
            rawOcr = """
                0OPOnOr IKH ANOOEIEH -ENAPEH
                ZYNOAO
                METPHTA
                182,00€
            """.trimIndent(),
            expectedTotal = 182.00,
            expectedMerchant = null,
            description = "Veterinary - Correct parsing"
        ),
        ReceiptTestData(
            id = 23,
            rawOcr = """
                XPHETOE &BAEIAEIOS KAPAKOZTAZ U
                ZYNOAO €
                113 80
            """.trimIndent(),
            expectedTotal = 113.80,
            expectedMerchant = "KAPAKOZTAZ",
            description = "Karakaostas - Spaced number"
        ),
        ReceiptTestData(
            id = 22,
            rawOcr = """
                TIPERNI
                2YNONO
                METPHIA
                9.80 24x
                10.80 24%
                44.20
            """.trimIndent(),
            expectedTotal = 44.20,
            expectedMerchant = "TIPERNI",
            description = "To Xani - Multiple amounts with VAT"
        ),
        ReceiptTestData(
            id = 21,
            rawOcr = """
                TIMH NONASOE : 1.947 EYPO/AT
                ZYNOAIKH AEIA: 50,00 EYP0
            """.trimIndent(),
            expectedTotal = 50.00,
            expectedMerchant = null,
            description = "Fuel receipt - Unit price and total"
        )
    )

    // ============================================
    // GREEK KEYWORD OCR VARIATIONS
    // ============================================
    
    private val totalKeywordVariations = mapOf(
        "ΣΥΝΟΛΟ" to true,      // Correct Greek
        "EYNONO" to true,      // Σ→E
        "ZYNOAO" to true,      // Σ→Z, Λ→A
        "2YNONO" to true,      // Σ→2
        "ZYNOIO" to true,      // Λ→I
        "2YNOAO" to true,      // Σ→2, Λ→A
        "ZYNOAO" to true,      // Mixed
        "SYNOLON" to true,     // Latin transliteration
        "SYNOLO" to true,      // Latin short
        "TOTAL" to true,       // English
        "AMOUNT" to true,      // English
        "EYNONO" to true,      // With trailing space
        "METPHTA" to false,    // This is CASH, not TOTAL
        "EYPΩ" to false        // This is EUR, not TOTAL
    )

    private val cashKeywordVariations = mapOf(
        "ΜΕΤΡΗΤΑ" to true,
        "METPHTA" to true,
        "METPHIA" to true,
        "METPH TA" to true,
        "CASH" to true,
        "METPHTA" to true,
        "ZYNOAO" to false      // Not cash
    )

    private val euroKeywordVariations = mapOf(
        "ΕΥΡΩ" to true,
        "EYPΩ" to true,
        "EYP9" to true,
        "EYP0" to true,
        "EYPQ" to true,
        "EYP O" to true,
        "EUR" to true,
        "€" to true,
        "METPHTA" to false
    )

    // ============================================
    // DECIMAL NUMBER TEST CASES
    // ============================================
    
    private val decimalTestCases = listOf(
        // European format (comma as decimal)
        DecimalTestCase("45,50", 45.50, "European decimal"),
        DecimalTestCase("100,00", 100.00, "European whole"),
        DecimalTestCase("7,80", 7.80, "European small"),
        DecimalTestCase("182,00", 182.00, "European large"),
        DecimalTestCase("113,80", 113.80, "European medium"),
        
        // US format (dot as decimal) - THIS IS THE BUG!
        DecimalTestCase("45.50", 45.50, "US decimal - CURRENTLY FAILS"),
        DecimalTestCase("44.20", 44.20, "US decimal - CURRENTLY FAILS"),
        DecimalTestCase("18.90", 18.90, "US decimal - CURRENTLY FAILS"),
        DecimalTestCase("7.80", 7.80, "US decimal"),
        DecimalTestCase("35.09", 35.09, "US decimal"),
        
        // European with thousand separator
        DecimalTestCase("1.250,50", 1250.50, "European with thousand sep"),
        DecimalTestCase("2.500,00", 2500.00, "European with thousand sep"),
        DecimalTestCase("12.345,67", 12345.67, "European large"),
        
        // US with thousand separator
        DecimalTestCase("1,250.50", 1250.50, "US with thousand sep"),
        DecimalTestCase("2,500.00", 2500.00, "US with thousand sep"),
        
        // OCR spacing issues
        DecimalTestCase("45, 50", 45.50, "Space after comma"),
        DecimalTestCase("45 ,50", 45.50, "Space before comma"),
        DecimalTestCase("45 .50", 45.50, "Space before dot"),
        DecimalTestCase("45. 50", 45.50, "Space after dot"),
        
        // Edge cases
        DecimalTestCase("0,00", 0.00, "Zero amount"),
        DecimalTestCase("0.00", 0.00, "Zero amount US"),
        DecimalTestCase("5,0", 5.0, "Single decimal"),
        DecimalTestCase("5.0", 5.0, "Single decimal US")
    )

    // ============================================
    // DATE EXTRACTION TEST CASES
    // ============================================
    
    private val dateTestCases = listOf(
        DateTestCase("30/01/2026", 2026, 1, 30, "European slash"),
        DateTestCase("01/10/2015", 2015, 10, 1, "European slash"),
        DateTestCase("29/11/2016", 2016, 11, 29, "European slash"),
        DateTestCase("16/04/2017", 2017, 4, 16, "European slash"),
        DateTestCase("18/06/2019", 2019, 6, 18, "European slash"),
        DateTestCase("14/03/2020", 2020, 3, 14, "European slash"),
        DateTestCase("07/10/2024", 2024, 10, 7, "European slash"),
        
        // Short year
        DateTestCase("30/01/26", 2026, 1, 30, "Short year"),
        DateTestCase("01/10/15", 2015, 10, 1, "Short year"),
        
        // Dash separator
        DateTestCase("30-01-2026", 2026, 1, 30, "Dash separator"),
        DateTestCase("01-10-2015", 2015, 10, 1, "Dash separator"),
        
        // Dot separator
        DateTestCase("30.01.2026", 2026, 1, 30, "Dot separator"),
        
        // With spaces
        DateTestCase("30 / 01 / 2026", 2026, 1, 30, "With spaces"),
        
        // OCR errors
        DateTestCase("16-D4-2017", 2017, 4, 16, "OCR D instead of 0"),
        DateTestCase("16-O4-2017", 2017, 4, 16, "OCR O instead of 0")
    )

    // ============================================
    // PARSER CLASS (SIMPLIFIED FOR TESTING)
    // ============================================
    
    private lateinit var parser: TestableReceiptParser

    @Before
    fun setup() {
        parser = TestableReceiptParser()
    }

    // ============================================
    // TEST 1: DECIMAL PARSING
    // ============================================
    
    @Test
    fun testDecimalParsing() {
        println("\n" + "=".repeat(60))
        println("DECIMAL PARSING TESTS")
        println("=".repeat(60))
        
        var passed = 0
        var failed = 0
        
        decimalTestCases.forEach { testCase ->
            val result = parser.parseAmount(testCase.input)
            val success = kotlin.math.abs(result - testCase.expected) < 0.001
            
            val status = if (success) "✅ PASS" else "❌ FAIL"
            println("$status: ${testCase.input} → $result (expected: ${testCase.expected}) - ${testCase.description}")
            
            if (success) passed++ else failed++
        }
        
        println("\nResults: $passed passed, $failed failed")
        println("Success Rate: ${(passed * 100.0 / decimalTestCases.size).toInt()}%")
        
        // Assert at least 80% pass rate
        assertTrue("Decimal parsing success rate too low: ${(passed * 100.0 / decimalTestCases.size).toInt()}%", 
            passed * 100.0 / decimalTestCases.size >= 80.0)
    }

    // ============================================
    // TEST 2: GREEK KEYWORD NORMALIZATION
    // ============================================
    
    @Test
    fun testGreekKeywordNormalization() {
        println("\n" + "=".repeat(60))
        println("GREEK KEYWORD NORMALIZATION TESTS")
        println("=".repeat(60))
        
        // Test TOTAL keyword variations
        println("\n--- TOTAL Keyword Variations ---")
        var totalPassed = 0
        totalKeywordVariations.forEach { (input, shouldMatch) ->
            val normalized = parser.normalizeGreekOcr(input)
            val found = normalized.contains("TOTAL_KEY")
            val success = found == shouldMatch
            
            val status = if (success) "✅ PASS" else "❌ FAIL"
            println("$status: '$input' → ${if (found) "MATCHED" else "NOT MATCHED"} (expected: ${if (shouldMatch) "MATCH" else "NO MATCH"})")
            
            if (success) totalPassed++
        }
        println("TOTAL Keywords: $totalPassed/${totalKeywordVariations.size} passed")
        
        // Test CASH keyword variations
        println("\n--- CASH Keyword Variations ---")
        var cashPassed = 0
        cashKeywordVariations.forEach { (input, shouldMatch) ->
            val normalized = parser.normalizeGreekOcr(input)
            val found = normalized.contains("CASH_KEY")
            val success = found == shouldMatch
            
            val status = if (success) "✅ PASS" else "❌ FAIL"
            println("$status: '$input' → ${if (found) "MATCHED" else "NOT MATCHED"} (expected: ${if (shouldMatch) "MATCH" else "NO MATCH"})")
            
            if (success) cashPassed++
        }
        println("CASH Keywords: $cashPassed/${cashKeywordVariations.size} passed")
        
        // Test EUR keyword variations
        println("\n--- EUR Keyword Variations ---")
        var eurPassed = 0
        euroKeywordVariations.forEach { (input, shouldMatch) ->
            val normalized = parser.normalizeGreekOcr(input)
            val found = normalized.contains("EUR")
            val success = found == shouldMatch
            
            val status = if (success) "✅ PASS" else "❌ FAIL"
            println("$status: '$input' → ${if (found) "MATCHED" else "NOT MATCHED"} (expected: ${if (shouldMatch) "MATCH" else "NO MATCH"})")
            
            if (success) eurPassed++
        }
        println("EUR Keywords: $eurPassed/${euroKeywordVariations.size} passed")
        
        val totalTests = totalKeywordVariations.size + cashKeywordVariations.size + euroKeywordVariations.size
        val totalPassed = totalPassed + cashPassed + eurPassed
        
        println("\nOverall: $totalPassed/$totalTests passed (${(totalPassed * 100.0 / totalTests).toInt()}%)")
        
        assertTrue("Keyword normalization success rate too low", 
            totalPassed * 100.0 / totalTests >= 80.0)
    }

    // ============================================
    // TEST 3: DATE EXTRACTION
    // ============================================
    
    @Test
    fun testDateExtraction() {
        println("\n" + "=".repeat(60))
        println("DATE EXTRACTION TESTS")
        println("=".repeat(60))
        
        var passed = 0
        
        dateTestCases.forEach { testCase ->
            val result = parser.extractDate(testCase.input)
            
            val success = if (result != null) {
                val calendar = java.util.Calendar.getInstance()
                calendar.timeInMillis = result
                val year = calendar.get(java.util.Calendar.YEAR)
                val month = calendar.get(java.util.Calendar.MONTH) + 1
                val day = calendar.get(java.util.Calendar.DAY_OF_MONTH)
                
                year == testCase.expectedYear && 
                month == testCase.expectedMonth && 
                day == testCase.expectedDay
            } else {
                false
            }
            
            val status = if (success) "✅ PASS" else "❌ FAIL"
            println("$status: '${testCase.input}' → $result (${testCase.description})")
            
            if (success) passed++
        }
        
        println("\nResults: $passed/${dateTestCases.size} passed")
        println("Success Rate: ${(passed * 100.0 / dateTestCases.size).toInt()}%")
        
        assertTrue("Date extraction success rate too low", 
            passed * 100.0 / dateTestCases.size >= 80.0)
    }

    // ============================================
    // TEST 4: TOTAL EXTRACTION FROM REAL RECEIPTS
    // ============================================
    
    @Test
    fun testTotalExtractionFromRealReceipts() {
        println("\n" + "=".repeat(60))
        println("TOTAL EXTRACTION FROM REAL RECEIPTS")
        println("=".repeat(60))
        
        var passed = 0
        var failed = 0
        val results = mutableListOf<String>()
        
        realReceiptData.forEach { receipt ->
            val normalized = parser.normalizeGreekOcr(receipt.rawOcr)
            val extractedTotal = parser.extractTotal(normalized.lines())
            
            val success = extractedTotal != null && 
                kotlin.math.abs(extractedTotal - receipt.expectedTotal) < 0.01
            
            val status = if (success) "✅ PASS" else "❌ FAIL"
            val diff = if (extractedTotal != null) {
                String.format("%.2f", kotlin.math.abs(extractedTotal - receipt.expectedTotal))
            } else {
                "N/A"
            }
            
            val line = "$status: Receipt #${receipt.id} | Expected: ${receipt.expectedTotal} | Got: $extractedTotal | Diff: $diff"
            results.add(line)
            println(line)
            println("   Description: ${receipt.description}")
            println("   Raw OCR snippet: ${receipt.rawOcr.take(50)}...")
            println()
            
            if (success) passed++ else failed++
        }
        
        println("\n" + "=".repeat(60))
        println("SUMMARY: $passed/${realReceiptData.size} passed (${(passed * 100.0 / realReceiptData.size).toInt()}%)")
        println("=".repeat(60))
        
        // We want at least 70% success rate after fixes
        assertTrue("Total extraction success rate too low: ${(passed * 100.0 / realReceiptData.size).toInt()}%", 
            passed * 100.0 / realReceiptData.size >= 70.0)
    }

    // ============================================
    // TEST 5: FULL PARSING PIPELINE
    // ============================================
    
    @Test
    fun testFullParsingPipeline() {
        println("\n" + "=".repeat(60))
        println("FULL PARSING PIPELINE TEST")
        println("=".repeat(60))
        
        var totalPassed = 0
        var totalTests = 0
        
        realReceiptData.forEach { receipt ->
            println("\n--- Receipt #${receipt.id}: ${receipt.description} ---")
            
            // Step 1: Normalize
            val normalized = parser.normalizeGreekOcr(receipt.rawOcr)
            println("Normalized text preview: ${normalized.take(100)}...")
            
            // Step 2: Extract total
            val total = parser.extractTotal(normalized.lines())
            val totalSuccess = total != null && 
                kotlin.math.abs(total - receipt.expectedTotal) < 0.01
            println("Total: $total (expected: ${receipt.expectedTotal}) ${if (totalSuccess) "✅" else "❌"}")
            
            // Step 3: Extract date
            val date = parser.extractDate(normalized)
            println("Date: $date ${if (date != null) "✅" else "❌"}")
            
            totalTests += 2
            if (totalSuccess) totalPassed++
            if (date != null) totalPassed++
        }
        
        println("\nOverall Pipeline Success: $totalPassed/$totalTests (${(totalPassed * 100.0 / totalTests).toInt()}%)")
    }

    // ============================================
    // TEST 6: BUG REGRESSION TESTS
    // ============================================
    
    @Test
    fun testBugRegressions() {
        println("\n" + "=".repeat(60))
        println("BUG REGRESSION TESTS")
        println("=".repeat(60))
        
        // BUG #1: 45.50 → 4550.0 (Decimal parsing)
        println("\n--- BUG #1: Decimal Parsing ---")
        val decimalBug = parser.parseAmount("45.50")
        println("Input: '45.50' → Output: $decimalBug")
        assertEquals("Decimal parsing bug: 45.50 should be 45.50, not 4550.0", 
            45.50, decimalBug, 0.001)
        println("✅ PASS: Decimal bug fixed")
        
        // BUG #2: EYNONO not matching ΣΥΝΟΛΟ
        println("\n--- BUG #2: EYNONO Not Matching ---")
        val eynonoNormalized = parser.normalizeGreekOcr("EYNONO")
        assertTrue("EYNONO should normalize to TOTAL_KEY", 
            eynonoNormalized.contains("TOTAL_KEY"))
        println("Input: 'EYNONO' → Output: $eynonoNormalized")
        println("✅ PASS: EYNONO now matches")
        
        // BUG #3: Date 2015-2019 rejected
        println("\n--- BUG #3: Date Range Too Narrow ---")
        val oldDate = parser.extractDate("01/10/2015")
        assertNotNull("Date 01/10/2015 should be parsed (was rejected before)", oldDate)
        println("Input: '01/10/2015' → Output: $oldDate")
        println("✅ PASS: Old dates now accepted")
        
        // BUG #4: VAT percentage matched as total
        println("\n--- BUG #4: VAT Matched as Total ---")
        val vatText = """
            ΣΥΝΟΛΟ
            13.00%
            44.20
        """.trimIndent()
        val vatNormalized = parser.normalizeGreekOcr(vatText)
        val vatTotal = parser.extractTotal(vatNormalized.lines())
        println("Text with VAT: $vatText")
        println("Extracted total: $vatTotal")
        assertTrue("Total should be 44.20, not 13.00", 
            vatTotal != null && kotlin.math.abs(vatTotal - 44.20) < 0.01)
        println("✅ PASS: VAT percentage correctly ignored")
        
        println("\n" + "=".repeat(60))
        println("ALL REGRESSION TESTS PASSED!")
        println("=".repeat(60))
    }

    // ============================================
    // TEST 7: CHARACTER CONFUSION MAP
    // ============================================
    
    @Test
    fun testCharacterConfusionMap() {
        println("\n" + "=".repeat(60))
        println("CHARACTER CONFUSION MAP")
        println("=".repeat(60))
        
        val greekLetters = mapOf(
            'Σ' to listOf('E', 'Z', '2', '5', 'S'),
            'Υ' to listOf('Y', 'V', 'U'),
            'Ο' to listOf('O', '0'),
            'Λ' to listOf('A', 'Λ'),
            'Ω' to listOf('O', 'Ω', '0'),
            'Η' to listOf('H', 'N'),
            'Ι' to listOf('I', '1', 'l'),
            'Α' to listOf('A'),
            'Μ' to listOf('M'),
            'Ε' to listOf('E')
        )
        
        println("\nGreek Letter → Common OCR Outputs:")
        greekLetters.forEach { (greek, variations) ->
            val varList = variations.joinToString(", ")
            println("  $greek → $varList")
        }
        
        // Test pattern covers all variations
        println("\nPattern Coverage Test:")
        val testWords = mapOf(
            "ΣΥΝΟΛΟ" to listOf("EYNONO", "ZYNOAO", "2YNONO", "SYNOLO"),
            "ΜΕΤΡΗΤΑ" to listOf("METPHTA", "METPHIA"),
            "ΕΥΡΩ" to listOf("EYPΩ", "EYP9", "EYP0")
        )
        
        testWords.forEach { (greek, variations) ->
            println("\n  Testing '$greek':")
            variations.forEach { variation ->
                val normalized = parser.normalizeGreekOcr(variation)
                val matched = normalized.contains("TOTAL_KEY") || 
                    normalized.contains("CASH_KEY") || 
                    normalized.contains("EUR")
                println("    '$variation' → ${if (matched) "✅ MATCHED" else "❌ NOT MATCHED"}")
            }
        }
    }

    // ============================================
    // HELPER CLASSES
    // ============================================
    
    data class ReceiptTestData(
        val id: Int,
        val rawOcr: String,
        val expectedTotal: Double,
        val expectedMerchant: String?,
        val description: String
    )

    data class DecimalTestCase(
        val input: String,
        val expected: Double,
        val description: String
    )

    data class DateTestCase(
        val input: String,
        val expectedYear: Int,
        val expectedMonth: Int,
        val expectedDay: Int,
        val description: String
    )

    // ============================================
    // TESTABLE PARSER IMPLEMENTATION
    // ============================================
    
    /**
     * Testable implementation of ReceiptParser with all fixes applied.
     * This can be compared against your actual parser to verify fixes.
     */
    class TestableReceiptParser {

        // ============ NORMALIZATION ============
        
        fun normalizeGreekOcr(text: String): String {
            var normalized = text.uppercase()
            
            // Phase 1: Fix number spacing
            normalized = normalized.replace(Regex("""(\d+)[.,]\s+(\d{2})"""), "$1.$2")
            normalized = normalized.replace(Regex("""(\d+)\s+[.,](\d{2})"""), "$1.$2")
            normalized = normalized.replace(Regex("""(\d)\s+(\d)"""), "$1$2")
            
            // Phase 2: Normalize Greek keywords
            // ΣΥΝΟΛΟ variants
            normalized = normalized.replace(
                Regex("""\b[E2Z5SZ][YVIU][NO][OA0Ω][NA0ΩΛ][OA0Ω]?\b"""), 
                "TOTAL_KEY"
            )
            normalized = normalized.replace(
                Regex("""\b[YVIU][NO][OA0Ω][NA0ΩΛ][OA0Ω]?\b"""), 
                "TOTAL_KEY"
            )
            normalized = normalized.replace("ΣΥΝΟΛΟ", "TOTAL_KEY")
            normalized = normalized.replace("SYNOLO", "TOTAL_KEY")
            normalized = normalized.replace("SYNOLON", "TOTAL_KEY")
            
            // ΜΕΤΡΗΤΑ variants
            normalized = normalized.replace(
                Regex("""\bM[EA]TPH[TI][A0]\b"""), 
                "CASH_KEY"
            )
            normalized = normalized.replace("ΜΕΤΡΗΤΑ", "CASH_KEY")
            
            // ΕΥΡΩ variants
            normalized = normalized.replace(Regex("""\bEYP[ΩO09Q]\b"""), "EUR")
            normalized = normalized.replace("ΕΥΡΩ", "EUR")
            
            // ΠΛΗΡΩΤΕΟ variants
            normalized = normalized.replace(
                Regex("""\b[NΠ][AΛ][HP][ΩO0]TE[OA]\b"""), 
                "TOTAL_KEY"
            )
            normalized = normalized.replace("ΠΛΗΡΩΤΕΟ", "TOTAL_KEY")
            
            // ΠΟΣΟ variants
            normalized = normalized.replace(Regex("""\b[NΠ][OA]S[OA]\b"""), "TOTAL_KEY")
            normalized = normalized.replace(Regex("""\bnozo\b""", RegexOption.IGNORE_CASE), "TOTAL_KEY")
            normalized = normalized.replace("ΠΟΣΟ", "TOTAL_KEY")
            
            // English keywords
            normalized = normalized.replace("TOTAL", "TOTAL_KEY")
            normalized = normalized.replace("AMOUNT", "TOTAL_KEY")
            normalized = normalized.replace("SUBTOTAL", "SUBTOTAL_KEY")
            normalized = normalized.replace("CASH", "CASH_KEY")
            
            // Phase 3: Fix date OCR errors
            normalized = normalized.replace(Regex("""(\d{2})-D(\d)-(\d{4})"""), "$1-0$2-$3")
            normalized = normalized.replace(Regex("""(\d{2})-O(\d)-(\d{4})"""), "$1-0$2-$3")
            
            return normalized
        }

        // ============ DECIMAL PARSING (FIXED) ============
        
        fun parseAmount(rawAmount: String): Double {
            val trimmed = rawAmount.trim()
            
            val dots = trimmed.count { it == '.' }
            val commas = trimmed.count { it == ',' }
            
            return when {
                // European: 1.250,50 or 45,50
                commas == 1 && dots <= 1 -> {
                    trimmed.replace(".", "").replace(",", ".").toDoubleOrNull() ?: 0.0
                }
                // US: 1,250.50 or 45.50
                dots == 1 && commas <= 1 -> {
                    trimmed.replace(",", "").toDoubleOrNull() ?: 0.0
                }
                // Thousand separators only
                dots == 1 && commas == 0 && trimmed.indexOf('.') < trimmed.length - 3 -> {
                    trimmed.replace(".", "").toDoubleOrNull() ?: 0.0
                }
                commas == 1 && dots == 0 && trimmed.indexOf(',') < trimmed.length - 3 -> {
                    trimmed.replace(",", "").toDoubleOrNull() ?: 0.0
                }
                else -> trimmed.toDoubleOrNull() ?: 0.0
            }
        }

        // ============ TOTAL EXTRACTION ============
        
        fun extractTotal(lines: List<String>): Double? {
            val amountRegex = Regex("""(\d{1,3}(?:[.,]\d{3})*[.,]\d{2})""")
            
            // Strategy 1: Look for TOTAL_KEY
            val totalLineIndex = lines.indexOfLast { it.contains("TOTAL_KEY") }
            if (totalLineIndex != -1) {
                val amountInLine = extractAmountFromLine(lines[totalLineIndex], amountRegex)
                if (amountInLine != null && isValidTotal(amountInLine, lines[totalLineIndex])) {
                    return amountInLine
                }
                if (totalLineIndex + 1 < lines.size) {
                    val amountNext = extractAmountFromLine(lines[totalLineIndex + 1], amountRegex)
                    if (amountNext != null && isValidTotal(amountNext, lines[totalLineIndex + 1])) {
                        return amountNext
                    }
                }
            }
            
            // Strategy 2: Card receipt patterns
            for (i in lines.indices) {
                val line = lines[i]
                if (line.contains("AMOUNT", ignoreCase = true) || 
                    line.contains("nozo", ignoreCase = true)) {
                    val amount = extractAmountFromLine(line, amountRegex)
                        ?: if (i + 1 < lines.size) extractAmountFromLine(lines[i + 1], amountRegex) else null
                    if (amount != null && amount > 0) return amount
                }
            }
            
            // Strategy 3: Bottom fallback
            var maxAmount = 0.0
            val searchStart = (lines.size * 0.3).toInt()
            
            for (i in searchStart until lines.size) {
                val line = lines[i]
                if (line.contains("%")) continue
                if (line.contains("CASH_KEY") || line.contains("METPHTA")) continue
                if (line.contains("MONAS") || line.contains("ΜΟΝΑΔΟΣ")) continue
                if (line.contains("/AT") || line.contains("/ΛΤ")) continue
                
                val matches = amountRegex.findAll(line)
                for (match in matches) {
                    val rawVal = match.groupValues[1]
                    val amount = parseAmount(rawVal)
                    if (isValidTotal(amount, line) && amount > maxAmount) {
                        maxAmount = amount
                    }
                }
            }
            
            return if (maxAmount > 0.0) maxAmount else null
        }

        private fun extractAmountFromLine(line: String, regex: Regex): Double? {
            val matches = regex.findAll(line)
            return matches.lastOrNull()?.groupValues?.get(1)?.let { parseAmount(it) }
        }

        private fun isValidTotal(amount: Double, line: String): Boolean {
            if (amount > 5000) return false
            if (amount <= 0.0) return false
            if (amount >= 2020 && amount <= 2035 && amount % 1 == 0.0) return false
            return true
        }

        // ============ DATE EXTRACTION ============
        
        fun extractDate(text: String): Long? {
            val patterns = listOf(
                Regex("""(\d{1,2})\s?[/.-]\s?(\d{1,2})\s?[/.-]\s?(20\d{2})"""),
                Regex("""(\d{1,2})\s?[/.-]\s?(\d{1,2})\s?[/.-]\s?(\d{2})""")
            )
            
            val sdf = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.US)
            sdf.isLenient = false
            
            for (pattern in patterns) {
                pattern.find(text)?.let { match ->
                    val (d, m, y) = match.destructured
                    val year = if (y.length == 2) "20$y" else y
                    val yearInt = year.toIntOrNull() ?: 0
                    
                    // Extended range: 2015-2035
                    if (yearInt in 2015..2035) {
                        try {
                            return sdf.parse("$d/$m/$year")?.time
                        } catch (e: Exception) { }
                    }
                }
            }
            return null
        }
    }
}
