package com.yourname.expensetracker.domain.receipt

import com.yourname.expensetracker.assertApproxEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

class WarrantyTextExtractorTest {

    private val extractor = WarrantyTextExtractor()

    @Test
    fun `extract returns structured warranty data for explicit warranty receipt text`() {
        val purchaseDateString = recentDateString()
        val ocrText = """
            Merchant: Tech Store
            Product: iPhone 15 Pro Max
            Date: $purchaseDateString
            2 Year Warranty
            Manufacturer Warranty
            Support: +1-800-123-4567
            Email: support@techstore.com
            Return Policy: 30 Days
        """.trimIndent()

        val result = extractor.extract(ocrText)

        assertEquals("IPHONE 15 PRO MAX", result.productName)
        assertEquals("TECH STORE", result.merchantName)
        assertNotNull(result.purchaseDate)
        assertEquals(24, result.warrantyDurationMonths)
        assertNotNull(result.warrantyEndDate)
        assertEquals("MANUFACTURER", result.warrantyType)
        assertEquals("+1-800-123-4567", result.supportPhone)
        assertEquals("support@techstore.com", result.supportEmail)
        assertEquals(30, result.returnWindowDays)
        assertApproxEquals(1.0, result.confidence, 0.01)

        // Half-open semantics: warrantyEndDate is exclusive (start of next day).
        // Use display date (endDate - 1 day) for day-level comparisons.
        val purchaseCal = Calendar.getInstance().apply { timeInMillis = result.purchaseDate!! }
        val displayEndMs = result.warrantyEndDate!! - com.yourname.expensetracker.domain.util.TimePeriodUtils.DAY_IN_MILLIS
        val displayEndCal = Calendar.getInstance().apply { timeInMillis = displayEndMs }
        assertEquals(purchaseCal.get(Calendar.YEAR) + 2, displayEndCal.get(Calendar.YEAR))
        assertEquals(purchaseCal.get(Calendar.MONTH), displayEndCal.get(Calendar.MONTH))
        assertEquals(purchaseCal.get(Calendar.DAY_OF_MONTH), displayEndCal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `extract applies merchant based default warranty and return window when explicit duration is missing`() {
        val ocrText = """
            APPLE STORE
            DATE: ${recentDateString()}
            ITEM: AIRPODS PRO
            Thank you for your purchase
        """.trimIndent()

        val result = extractor.extract(ocrText)

        assertEquals("AIRPODS PRO", result.productName)
        assertEquals("APPLE STORE", result.merchantName)
        assertEquals(12, result.warrantyDurationMonths)
        assertEquals(14, result.returnWindowDays)
        assertNull(result.warrantyType)
        assertTrue(result.confidence >= 0.80)
    }

    @Test
    fun `extract returns empty extraction data for non-warranty text`() {
        val ocrText = """
            RECEIPT
            TOTAL 20.00
            TAX 3.00
            PAYMENT CARD
        """.trimIndent()

        val result = extractor.extract(ocrText)

        assertNull(result.productName)
        assertEquals("PAYMENT CARD", result.merchantName)
        assertNull(result.purchaseDate)
        assertNull(result.warrantyDurationMonths)
        assertNull(result.warrantyEndDate)
        assertNull(result.warrantyType)
        assertNull(result.supportPhone)
        assertNull(result.supportEmail)
        assertNull(result.returnWindowDays)
        assertApproxEquals(0.15, result.confidence, 0.01)
    }

    // -------------------------------------------------------------------------
    // Regression tests: legacy OCR date-parsing contract (ISSUE-1 / A.8 Batch 1)
    // These inputs were accepted by the old SimpleDateFormat and must remain
    // parseable by the new immutable java.time formatter set.
    // -------------------------------------------------------------------------

    /**
     * 2-digit year in dd/MM/yy format.
     * The regex accepts \d{2,4} for the year segment, so "12/05/24" is a valid
     * OCR match and must round-trip through the formatter layer.
     */
    @Test
    fun `parseDate accepts 2-digit year in dd slash MM slash yy format`() {
        // Build a date string 5 days ago in dd/MM/yy format, which is within the
        // reasonable purchase-date window.
        val date = LocalDate.now().minusDays(5)
        val twoDigitYearFmt = DateTimeFormatter.ofPattern("dd/MM/yy", Locale.getDefault())
        val dateStr = twoDigitYearFmt.format(date)

        val ocrText = "Date: $dateStr\n2 Year Warranty"
        val result = extractor.extract(ocrText)

        assertNotNull("Expected purchaseDate to be parsed from 2-digit year dd/MM/yy input", result.purchaseDate)
    }

    /**
     * 2-digit year in MM/dd/yy format (US OCR variant).
     */
    @Test
    fun `parseDate accepts 2-digit year in MM slash dd slash yy format`() {
        val date = LocalDate.now().minusDays(5)
        val twoDigitYearFmt = DateTimeFormatter.ofPattern("MM/dd/yy", Locale.getDefault())
        val dateStr = twoDigitYearFmt.format(date)

        val ocrText = "Date: $dateStr\n2 Year Warranty"
        val result = extractor.extract(ocrText)

        assertNotNull("Expected purchaseDate to be parsed from 2-digit year MM/dd/yy input", result.purchaseDate)
    }

    /**
     * Full month name with comma: "September 12, 2024".
     * Old SimpleDateFormat("MMMM dd, yyyy") accepted this; the new MMMM formatter
     * must accept it too.
     */
    @Test
    fun `parseDate accepts full month name in MMMM dd comma yyyy format`() {
        val date = LocalDate.now().minusDays(5)
        val fullMonthFmt = DateTimeFormatter.ofPattern("MMMM dd, yyyy", Locale.US)
        val dateStr = fullMonthFmt.format(date)  // e.g. "April 04, 2026"

        val ocrText = "Purchase Date: $dateStr\n1 Year Warranty"
        val result = extractor.extract(ocrText)

        assertNotNull("Expected purchaseDate to be parsed from full month-name 'MMMM dd, yyyy' input '$dateStr'", result.purchaseDate)
    }

    /**
     * Full month name without comma (day-first): "12 September 2024".
     * Old SimpleDateFormat("dd MMMM yyyy") accepted this.
     */
    @Test
    fun `parseDate accepts full month name in dd MMMM yyyy format`() {
        val date = LocalDate.now().minusDays(5)
        val fullMonthFmt = DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.US)
        val dateStr = fullMonthFmt.format(date)  // e.g. "04 April 2026"

        val ocrText = "Date: $dateStr\n1 Year Warranty"
        val result = extractor.extract(ocrText)

        assertNotNull("Expected purchaseDate to be parsed from full month-name 'dd MMMM yyyy' input '$dateStr'", result.purchaseDate)
    }

    /**
     * Full month name in uppercase (as produced by normalizeText).
     * OCR text is uppercased internally, so "SEPTEMBER 12, 2024" must also parse.
     */
    @Test
    fun `parseDate accepts uppercased full month name as produced by internal OCR normalization`() {
        val date = LocalDate.now().minusDays(5)
        val fullMonthFmt = DateTimeFormatter.ofPattern("MMMM dd, yyyy", Locale.US)
        val mixedCaseDateStr = fullMonthFmt.format(date)  // e.g. "April 04, 2026"
        // Simulate what normalizeText does — uppercase the whole string
        val upperCaseOcrText = "PURCHASE DATE: ${mixedCaseDateStr.uppercase(Locale.getDefault())}\n2 YEAR WARRANTY"

        val result = extractor.extract(upperCaseOcrText)

        assertNotNull(
            "Expected purchaseDate to parse from uppercased full month-name OCR text (parseCaseInsensitive must be set)",
            result.purchaseDate
        )
    }

    @Test
    fun `extract accepts receipts older than one year when date is still plausible`() {
        val date = LocalDate.now().minusYears(3)
        val dateStr = DateTimeFormatter.ofPattern("MMMM dd, yyyy", Locale.US).format(date)

        val result = extractor.extract(
            """
                Merchant: Vintage Electronics
                Product: Stereo Receiver
                Purchase Date: $dateStr
                3 Year Warranty
            """.trimIndent()
        )

        assertNotNull(result.purchaseDate)
        assertEquals(36, result.warrantyDurationMonths)
        assertNotNull(result.warrantyEndDate)
    }

    @Test
    fun `extract uses calendar month semantics for warranty end date`() {
        val result = extractor.extract(
            """
                Merchant: Tech Store
                Product: Laptop
                Purchase Date: January 31, 2024
                1 Month Warranty
            """.trimIndent()
        )

        // Half-open semantics: getEndOfDay returns start of next day
        val endCal = Calendar.getInstance().apply { timeInMillis = result.warrantyEndDate!! }
        assertEquals(2024, endCal.get(Calendar.YEAR))
        assertEquals(Calendar.MARCH, endCal.get(Calendar.MONTH))
        assertEquals(1, endCal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `extract picks date at start of later line using multiline anchor`() {
        val ocrText = """
            WARRANTY RECEIPT
            SOME HEADER
            15/03/2026
            2 Year Warranty
        """.trimIndent()

        val result = extractor.extract(ocrText)

        assertNotNull(result.purchaseDate)
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private fun recentDateString(): String {
        val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.getDefault())
        val date = LocalDate.now().minusDays(5)
        return formatter.format(date)
    }
}
