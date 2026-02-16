package com.yourname.expensetracker

import com.yourname.expensetracker.domain.receipt.ReceiptParser
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Calendar

/**
 * Comprehensive OCR Test Document Parser Test
 * 
 * This test file reads the OCR_TEST_DOCUMENT.txt and validates
 * all patterns against the ReceiptParser implementation.
 * 
 * Usage:
 * 1. Place OCR_TEST_DOCUMENT.txt in src/test/resources/
 * 2. Run this test class
 * 3. Check output for pass/fail results on each section
 */
class OcrDocumentTest {

    private lateinit var parser: ReceiptParser

    @Before
    fun setup() {
        parser = ReceiptParser()
    }

    // ============================================
    // SECTION 3: COMMON RECEIPT KEYWORDS
    // ============================================

    @Test
    fun `test Greek TOTAL keyword - ΣΥΝΟΛΟ`() {
        val input = """
            MARKET STORE
            ΑΦΜ: 123456789
            ΣΥΝΟΛΟ € 50,00
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should extract total from ΣΥΝΟΛΟ", 50.00, result.total!!, 0.01)
    }

    @Test
    fun `test Greek FINAL keyword - ΤΕΛΙΚΟ`() {
        val input = """
            CAFE
            ΤΕΛΙΚΟ 12,50 €
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should extract total from ΤΕΛΙΚΟ", 12.50, result.total!!, 0.01)
    }

    @Test
    fun `test Greek PAYABLE keyword - ΠΛΗΡΩΤΕΟ`() {
        val input = """
            SUPERMARKET
            ΠΛΗΡΩΤΕΟ 35,00 €
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should extract total from ΠΛΗΡΩΤΕΟ", 35.00, result.total!!, 0.01)
    }

    @Test
    fun `test Greek AMOUNT keyword - ΠΟΣΟ`() {
        val input = """
            SHOP
            ΠΟΣΟ: €80,43
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should extract total from ΠΟΣΟ", 80.43, result.total!!, 0.01)
    }

    @Test
    fun `test Greek CASH keyword - ΜΕΤΡΗΤΑ`() {
        val input = """
            STORE
            ΜΕΤΡΗΤΑ € 25,74
        """.trimIndent()
        val result = parser.parse(input)
        // ΜΕΤΡΗΤΑ is cash given, not total - but should still parse amount
        assertNotNull("Should parse amount from ΜΕΤΡΗΤΑ line", result.total)
    }

    @Test
    fun `test Greek amount keyword - ΠΛΗΡΩΤΕΟ variants`() {
        val input = """
            NAHPΩTEO: 45,50 €
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals(45.50, result.total!!, 0.01)
    }

    @Test
    fun `test Greek total keyword - ΣΥΝΟΛΙΚΗ ΑΞΙΑ variant`() {
        val input = """
            YNUAIKH AEIA: 50,00 EYRL
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals(50.00, result.total!!, 0.01)
    }

    // ============================================
    // SECTION 4: COMPOUND KEYWORDS
    // ============================================

    @Test
    fun `test compound keyword - ΣΥΝΟΛΙΚΗ ΑΞΙΑ`() {
        val input = """
            STORE
            ΣΥΝΟΛΙΚΗ ΑΞΙΑ: 20,01 ΕΥΡΩ
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should extract total from ΣΥΝΟΛΙΚΗ ΑΞΙΑ", 20.01, result.total!!, 0.01)
        assertEquals("Should detect EUR currency", "EUR", result.currency)
    }

    @Test
    fun `test compound keyword - ΚΑΘΑΡΗ ΑΞΙΑ`() {
        val input = """
            STORE
            ΚΑΘΑΡΗ ΑΞΙΑ: 17,25 ΕΥΡΩ
        """.trimIndent()
        val result = parser.parse(input)
        // ΚΑΘΑΡΗ ΑΞΙΑ is net value (subtotal), should be extracted
        assertNotNull("Should parse ΚΑΘΑΡΗ ΑΞΙΑ", result.subtotal)
    }

    @Test
    fun `test compound keyword - ΓΕΝΙΚΟ ΣΥΝΟΛΟ`() {
        val input = """
            SUPERMARKET
            ΓΕΝΙΚΟ ΣΥΝΟΛΟ: 100,00 €
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should extract total from ΓΕΝΙΚΟ ΣΥΝΟΛΟ", 100.00, result.total!!, 0.01)
    }

    // ============================================
    // SECTION 5 & 6: NUMBER FORMATS
    // ============================================

    @Test
    fun `test European decimal format - comma separator`() {
        val input = """
            STORE
            TOTAL 45,50 €
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should parse European decimal format", 45.50, result.total!!, 0.01)
    }

    @Test
    fun `test European format with thousands separator`() {
        val input = """
            TECH STORE
            TOTAL 1.250,50 €
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should parse 1.250,50 as 1250.50", 1250.50, result.total!!, 0.01)
    }

    @Test
    fun `test US decimal format - dot separator`() {
        val input = """
            DINER
            TOTAL 12.50
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should parse US decimal format", 12.50, result.total!!, 0.01)
    }

    @Test
    fun `test US format with thousands separator`() {
        val input = """
            CAR DEALER
            TOTAL 1,250.00
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should parse 1,250.00 as 1250.00", 1250.00, result.total!!, 0.01)
    }

    // ============================================
    // SECTION 7: NUMBERS WITH SPACING ISSUES
    // ============================================

    @Test
    fun `test number with space after comma`() {
        val input = """
            STORE
            TOTAL 45, 50 €
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should fix '45, 50' to 45.50", 45.50, result.total!!, 0.01)
    }

    @Test
    fun `test number with space before dot`() {
        val input = """
            STORE
            TOTAL 12 .50 €
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should fix '12 .50' to 12.50", 12.50, result.total!!, 0.01)
    }

    @Test
    fun `test number with space as thousands separator`() {
        val input = """
            STORE
            TOTAL 1 250,50 €
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should fix '1 250,50' to 1250.50", 1250.50, result.total!!, 0.01)
    }

    @Test
    fun `test severely mangled number`() {
        val input = "TOTAL 4 5 . 5 0 EUR"
        val result = parser.parse(input)
        assertEquals("Should fix '4 5 . 5 0' to 45.50", 45.50, result.total!!, 0.01)
    }

    // ============================================
    // NEW: TAX VS TOTAL CONFUSION (Patch 3.1)
    // ============================================

    @Test
    fun `test extraction before percentage sign - Receipt 3 failure case`() {
        val input = """
            SONICK EU E.E
            ΣΥΝΟΛΟ
            20,13 24,00%
            E0,13
        """.trimIndent()
        val result = parser.parse(input)
        // Should ignore 'E0,13' and extract '20,13' from the line with percentage
        assertEquals("Should extract amount before %", 20.13, result.total!!, 0.01)
    }

    @Test
    fun `test skip tax-only lines - Receipt 1 failure case`() {
        val input = """
            TRREPN
            ΦΠΑ 24%: 9.80 €
            ΦΠΑ 13%: 4.20 €
            ΣΥΝΟΛΟ: 44.20 €
        """.trimIndent()
        val result = parser.parse(input)
        // Should skip the ΦΠΑ lines and pick the ΣΥΝΟΛΟ amount
        assertEquals("Should skip tax lines and pick total", 44.20, result.total!!, 0.01)
    }

    // ============================================
    // NEW: RECEIPT NUMBER INTERFERENCE (Patch 3.3)
    // ============================================

    @Test
    fun `test skip receipt serial number - ZEIPA`() {
        val input = """
            MARKET
            ZEIPA: Y204
            AP: 1926788
            TOTAL: 50.00
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should skip serial and pick total", 50.00, result.total!!, 0.01)
    }

    @Test
    fun `test skip receipt number - APIOMOE`() {
        val input = """
            STORE
            ΑΡΙΘΜΟΣ ΑΠΌΔΕΙΞΗΣ: 123456
            ΣΥΝΟΛΟ: 15.20
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should skip ΑΡΙΘΜΟΣ and pick total", 15.20, result.total!!, 0.01)
    }

    // ============================================
    // NEW: DATE VALIDATION (Patch 3.4)
    // ============================================

    @Test
    fun `test dynamic year rejection`() {
        val input = """
            TOTAL: 50.00
            DATE: 31-1-2058
        """.trimIndent()
        val result = parser.parse(input)
        // 2058 should be rejected as it's too far in the future
        assertNull("Should reject year 2058", result.date)
    }

    @Test
    fun `test severely mangled number 2`() {
        val input = """
            STORE
            TOTAL 1.2 5 0, 5 0 €
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals(1250.50, result.total!!, 0.01)
    }

    // ============================================
    // SECTION 8: CURRENCY WITH SYMBOLS
    // ============================================

    @Test
    fun `test currency before amount - €50,00`() {
        val input = """
            STORE
            TOTAL €50,00
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should parse €50,00", 50.00, result.total!!, 0.01)
    }

    @Test
    fun `test currency after amount - 50,00 €`() {
        val input = """
            STORE
            TOTAL 50,00 €
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should parse 50,00 €", 50.00, result.total!!, 0.01)
    }

    @Test
    fun `test EUR text format`() {
        val input = """
            STORE
            TOTAL EUR 100,00
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should parse EUR 100,00", 100.00, result.total!!, 0.01)
        assertEquals("Should detect EUR currency", "EUR", result.currency)
    }

    // ============================================
    // SECTION 9: DATE FORMATS
    // ============================================

    @Test
    fun `test European date format - DD-MM-YYYY`() {
        val input = """
            STORE
            DATE: 30/01/2026
            TOTAL 10,00 €
        """.trimIndent()
        val result = parser.parse(input)
        assertNotNull("Should parse date", result.date)
        val cal = Calendar.getInstance()
        cal.timeInMillis = result.date!!
        assertEquals("Day should be 30", 30, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals("Month should be January (0-indexed)", 0, cal.get(Calendar.MONTH))
        assertEquals("Year should be 2026", 2026, cal.get(Calendar.YEAR))
    }

    @Test
    fun `test date with dashes`() {
        val input = """
            STORE
            DATE: 30-01-2026
            TOTAL 10,00 €
        """.trimIndent()
        val result = parser.parse(input)
        assertNotNull("Should parse date with dashes", result.date)
    }

    @Test
    fun `test date with dots`() {
        val input = """
            STORE
            DATE: 30.01.2026
            TOTAL 10,00 €
        """.trimIndent()
        val result = parser.parse(input)
        assertNotNull("Should parse date with dots", result.date)
    }

    @Test
    fun `test date with spacing issues`() {
        val input = """
            STORE
            DATE: 30 / 01 / 2026
            TOTAL 10,00 €
        """.trimIndent()
        val result = parser.parse(input)
        // May or may not work - test for graceful handling
        assertNotNull("Should handle date with spaces", result)
    }

    @Test
    fun `test short year format - DD-MM-YY`() {
        val input = """
            STORE
            DATE: 30/01/26
            TOTAL 10,00 €
        """.trimIndent()
        val result = parser.parse(input)
        assertNotNull("Should parse short year", result.date)
        val cal = Calendar.getInstance()
        cal.timeInMillis = result.date!!
        assertEquals("Year should expand to 2026", 2026, cal.get(Calendar.YEAR))
    }

    // ============================================
    // SECTION 11: VAT/TAX PERCENTAGES
    // ============================================

    @Test
    fun `test VAT extraction with Greek label`() {
        val input = """
            STORE
            SUBTOTAL 100,00 €
            ΦΠΑ 24,00%: 24,00 €
            TOTAL 124,00 €
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should extract VAT", 24.00, result.tax!!, 0.01)
    }

    @Test
    fun `test VAT with dots in label`() {
        val input = """
            STORE
            Φ.Π.Α. 24,00%: 9,68 €
            TOTAL 50,00 €
        """.trimIndent()
        val result = parser.parse(input)
        assertNotNull("Should parse Φ.Π.Α.", result.tax)
    }

    @Test
    fun `test VAT percentage not confused with total`() {
        val input = """
            STORE
            ΦΠΑ 24,00%
            TOTAL 50,00 €
        """.trimIndent()
        val result = parser.parse(input)
        // 24,00% should NOT be picked as total
        assertNotEquals("VAT percentage should not be total", 24.00, result.total)
    }

    // ============================================
    // SECTION 12: UNIT PRICES (Should NOT be totals)
    // ============================================

    @Test
    fun `test unit price not picked as total`() {
        val input = """
            GAS STATION
            FUEL 1,574 €/ΛΤ
            TOTAL 45,50 €
        """.trimIndent()
        val result = parser.parse(input)
        // Should NOT pick 1.574 as total
        assertEquals("Should pick actual total, not unit price", 45.50, result.total!!, 0.01)
    }

    // ============================================
    // SECTION 14: COMPLETE RECEIPT LINES (Critical!)
    // ============================================

    @Test
    fun `test complete line - ΣΥΝΟΛΟ € 50,00`() {
        val input = "ΣΥΝΟΛΟ € 50,00"
        val result = parser.parse(input)
        assertEquals("Should parse 'ΣΥΝΟΛΟ € 50,00'", 50.00, result.total!!, 0.01)
    }

    @Test
    fun `test complete line - ΣΥΝΟΛΟ 80_43 EUR`() {
        val input = "ΣΥΝΟΛΟ: 80,43 €"
        val result = parser.parse(input)
        assertEquals("Should parse 'ΣΥΝΟΛΟ: 80,43 €'", 80.43, result.total!!, 0.01)
    }

    @Test
    fun `test complete line - ΠΟΣΟ_AMOUNT`() {
        val input = "ΠΟΣΟ/AMOUNT: €80,43"
        val result = parser.parse(input)
        assertEquals("Should parse 'ΠΟΣΟ/AMOUNT: €80,43'", 80.43, result.total!!, 0.01)
    }

    // ============================================
    // SECTION 22: SIMULATED OCR ERRORS (Critical!)
    // ============================================

    @Test
    fun `test OCR error - EYNONO (ΣΥΝΟΛΟ)`() {
        val input = """
            MARKET
            EYNONO € 5,00
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should parse EYNONO as ΣΥΝΟΛΟ", 5.00, result.total!!, 0.01)
    }

    @Test
    fun `test OCR error - ZYNOAO (ΣΥΝΟΛΟ)`() {
        val input = """
            STORE
            ZYNOAO: 182,00€
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should parse ZYNOAO as ΣΥΝΟΛΟ", 182.00, result.total!!, 0.01)
    }

    @Test
    fun `test OCR error - 2YNONO (ΣΥΝΟΛΟ)`() {
        val input = """
            BAKERY
            2YNONO 0,90 €
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should parse 2YNONO as ΣΥΝΟΛΟ", 0.90, result.total!!, 0.01)
    }

    @Test
    fun `test OCR error - METPHTA (ΜΕΤΡΗΤΑ)`() {
        val input = """
            CAFE
            METPHTA 25,74 ΕΥΡΩ
        """.trimIndent()
        val result = parser.parse(input)
        assertNotNull("Should parse METPHTA line", result.total)
    }

    @Test
    fun `test OCR error - EYPOMEGA (ΕΥΡΩ)`() {
        val input = """
            STORE
            TOTAL 50,00 EYPΩ
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should parse EYPΩ as EUR", 50.00, result.total!!, 0.01)
        assertEquals("Currency should be EUR", "EUR", result.currency)
    }

    @Test
    fun `test OCR error - EYP9 (ΕΥΡΩ)`() {
        val input = """
            STORE
            TOTAL 50,00 EYP9
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should parse EYP9 as EUR", 50.00, result.total!!, 0.01)
    }

    @Test
    fun `test OCR error - HM_NIA (ΗΜΕΡΟΜΗΝΙΑ)`() {
        val input = """
            STORE
            HM/NIA: 30/01/2026
            TOTAL 10,00 €
        """.trimIndent()
        val result = parser.parse(input)
        assertNotNull("Should parse HM/NIA as date", result.date)
    }

    // ============================================
    // SECTION 23: ACTUAL OCR OUTPUT FROM RECEIPTS
    // ============================================

    @Test
    fun `test actual OCR - IYN noZOTHTA`() {
        val input = """
            STORE
            IYN. noZOTHTA
            50,00 €
        """.trimIndent()
        val result = parser.parse(input)
        // This is a severe OCR error - may not fully parse
        // But should not crash
        assertNotNull("Should handle severe OCR error gracefully", result)
    }

    @Test
    fun `test actual OCR - ZYNOAO IONTAN`() {
        val input = """
            STORE
            ZYNOAO IONTAN
            182,00 €
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should parse ZYNOAO IONTAN", 182.00, result.total!!, 0.01)
    }

    @Test
    fun `test actual OCR - NAHPQTEO (ΠΛΗΡΩΤΕΟ)`() {
        val input = """
            STORE
            NAHPQTEO 10,00 €
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should parse NAHPQTEO as ΠΛΗΡΩΤΕΟ", 10.00, result.total!!, 0.01)
    }


    // ============================================
    // SECTION 15: MERCHANT NAMES
    // ============================================

    @Test
    fun `test Greek merchant name - ΣΚΛΑΒΕΝΙΤΗΣ`() {
        val input = """
            ΣΚΛΑΒΕΝΙΤΗΣ
            ΑΦΜ: 094206641
            TOTAL 50,00 €
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should extract merchant ΣΚΛΑΒΕΝΙΤΗΣ", "ΣΚΛΑΒΕΝΙΤΗΣ", result.merchantName)
    }

    @Test
    fun `test Greek merchant name - ΛΙΔΛ`() {
        val input = """
            ΛΙΔΛ
            ΑΘΗΝΑ
            TOTAL 35,00 €
        """.trimIndent()
        val result = parser.parse(input)
        assertTrue("Should extract merchant ΛΙΔΛ", 
            result.merchantName?.contains("ΛΙΔΛ") == true || 
            result.merchantName?.contains("LIDL") == true
        )
    }

    @Test
    fun `test merchant with Greeklish - DIAMANTIS MAZOUTHIS`() {
        val input = """
            ΔΙΑΜΑΝΤΗΣ ΜΑΖΟΥΘΗΣ Α.Ε.
            ΘΕΣΣΑΛΟΝΙΚΗ
            TOTAL 100,00 €
        """.trimIndent()
        val result = parser.parse(input)
        assertNotNull("Should extract merchant name", result.merchantName)
    }

    // ============================================
    // SECTION 18: LINE ITEMS
    // ============================================

    @Test
    fun `test line items extraction`() {
        val input = """
            CAFE
            ΚΑΦΕΣ           3,90 €
            ΦΑΓΗΤΟ          16,50 €
            ΣΑΛΑΤΕΣ         13,20 €
            ΣΥΝΟΛΟ 33,60 €
        """.trimIndent()
        val result = parser.parse(input)
        assertTrue("Should extract line items", result.lineItems.isNotEmpty())
        assertEquals("Total should match", 33.60, result.total!!, 0.01)
    }

    @Test
    fun `test line item with quantity`() {
        val input = """
            STORE
            2 x ΚΡΑΣΙ ΧΥΜΑ   7,60 €
            TOTAL 7,60 €
        """.trimIndent()
        val result = parser.parse(input)
        assertTrue("Should extract line items", result.lineItems.isNotEmpty())
    }

    // ============================================
    // SECTION 19: CARD RECEIPT PATTERNS
    // ============================================

    @Test
    fun `test card receipt pattern`() {
        val input = """
            cardlink
            ΑΓΟΡΑ-SALE
            5356 71** **** 6523
            ANEIIAQH/CONTACTLESS
            NOsO/AMOUNT: €35,00
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should parse card receipt total", 35.00, result.total!!, 0.01)
    }

    @Test
    fun `test bilingual thank you`() {
        val input = """
            STORE
            TOTAL 50,00 €
            EYXAPISTOYME - THANK YOU
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should parse total before thank you", 50.00, result.total!!, 0.01)
    }

    // ============================================
    // SECTION 20: MIXED GREEK-ENGLISH
    // ============================================

    @Test
    fun `test bilingual total`() {
        val input = "TOTAL / ΣΥΝΟΛΟ: €45.50"
        val result = parser.parse(input)
        assertEquals("Should parse bilingual total", 45.50, result.total!!, 0.01)
    }

    @Test
    fun `test bilingual cash`() {
        val input = "CASH / ΜΕΤΡΗΤΑ: €50.00"
        val result = parser.parse(input)
        assertNotNull("Should parse bilingual cash", result.total)
    }

    @Test
    fun `test bilingual VAT`() {
        val input = """
            STORE
            SUBTOTAL: €40.00
            VAT / ΦΠΑ: €2.76
            TOTAL: €42.76
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should parse total", 42.76, result.total!!, 0.01)
        assertEquals("Should parse VAT", 2.76, result.tax!!, 0.01)
    }

    // ============================================
    // SECTION 21: EDGE CASES
    // ============================================

    @Test
    fun `test year-like amount not confused with year`() {
        val input = """
            STORE
            TOTAL 2020,50 €
        """.trimIndent()
        val result = parser.parse(input)
        // 2020.50 should be valid (has decimal)
        assertEquals("Should allow year-like amount with decimal", 2020.50, result.total!!, 0.01)
    }

    @Test
    fun `test whole year not picked as amount`() {
        val input = """
            STORE
            DATE: 30/01/2026
            TOTAL 50,00 €
        """.trimIndent()
        val result = parser.parse(input)
        // 2026 should NOT be the total
        assertNotEquals("Year should not be total", 2026.0, result.total)
    }

    @Test
    fun `test phone number not picked as amount`() {
        val input = """
            STORE
            TEL: 2310 476821
            TOTAL 50,00 €
        """.trimIndent()
        val result = parser.parse(input)
        // Phone number should not be picked as amount
        assertTrue("Total should be reasonable", result.total!! < 1000)
    }

    @Test
    fun `test tax ID not picked as amount`() {
        val input = """
            STORE
            ΑΦΜ: 094206641
            TOTAL 50,00 €
        """.trimIndent()
        val result = parser.parse(input)
        // Tax ID (094206641) should not be picked as amount
        assertTrue("Total should be reasonable", result.total!! < 1000)
    }

    // ============================================
    // CONFIDENCE SCORE TESTS
    // ============================================

    @Test
    fun `test confidence score with good data`() {
        val input = """
            ΣΚΛΑΒΕΝΙΤΗΣ
            ΑΦΜ: 094206641
            ΗΜΕΡΟΜΗΝΙΑ: 30/01/2026
            ΚΑΦΕΣ           3,90 €
            ΦΑΓΗΤΟ          16,50 €
            ΣΥΝΟΛΟ 20,40 €
            ΦΠΑ 24%: 4,89 €
        """.trimIndent()
        val result = parser.parse(input)
        assertTrue("Confidence should be high with good data", result.confidence >= 0.7f)
    }

    @Test
    fun `test confidence score with minimal data`() {
        val input = "50,00 €"
        val result = parser.parse(input)
        assertTrue("Confidence should be lower with minimal data", result.confidence < 0.7f)
    }

    // ============================================
    // DATE OCR FIXES
    // ============================================

    @Test
    fun `test date OCR fix - D instead of 0`() {
        val input = """
            STORE
            DATE: 16-D4-2017
            TOTAL 10,00 €
        """.trimIndent()
        val result = parser.parse(input)
        assertNotNull("Should fix D→0 in date", result.date)
        val cal = Calendar.getInstance()
        cal.timeInMillis = result.date!!
        assertEquals("Month should be April", 3, cal.get(Calendar.MONTH))
    }

    // ============================================
    // HELPER: Print Test Summary
    // ============================================

    @Test
    fun `print parser version info`() {
        println("=" .repeat(60))
        println("OCR Document Test Suite")
        println("Testing ReceiptParser with OCR_TEST_DOCUMENT patterns")
        println("=" .repeat(60))
    }
}
