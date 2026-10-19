package com.yourname.expensetracker.domain.receipt

import com.yourname.expensetracker.data.repository.MerchantRulesRepository
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

/**
 * OCR pattern coverage for [ReceiptParser], expressed as row tables.
 *
 * Every row keeps the exact input of the original per-case test. Amounts are compared exactly
 * (zero delta). Each table is size-guarded so it cannot silently pass empty or lose a row.
 */
class ReceiptParserOcrPatternsTest {

    private lateinit var parser: ReceiptParser
    private lateinit var timeProvider: TimeProvider

    @Before
    fun setup() {
        timeProvider = mockk()
        every { timeProvider.now() } returns 1776297600000L // April 2026
        parser = ReceiptParser(MerchantRulesRepository(), timeProvider = timeProvider)
    }

    // ------------------------------------------------------------------
    // Table 1: total amount + currency provenance
    // ------------------------------------------------------------------

    private data class TotalRow(val name: String, val input: String, val total: Double)

    private val totalRows = listOf(
        // Greek / transliterated keywords
        TotalRow("Greek TOTAL keyword ΣΥΝΟΛΟ", "MARKET STORE\nΑΦΜ: 123456789\nΣΥΝΟΛΟ € 50,00", 50.00),
        TotalRow("Greek FINAL keyword ΤΕΛΙΚΟ", "CAFE\nΤΕΛΙΚΟ 12,50 €", 12.50),
        TotalRow("Greek PAYABLE keyword ΠΛΗΡΩΤΕΟ", "SUPERMARKET\nΠΛΗΡΩΤΕΟ 35,00 €", 35.00),
        TotalRow("Greek AMOUNT keyword ΠΟΣΟ", "SHOP\nΠΟΣΟ: €80,43", 80.43),
        // Original assertNotNull (weak): the only monetary token is 25,74, so the total is exactly 25.74.
        TotalRow("Greek CASH keyword ΜΕΤΡΗΤΑ is still parsed as the only amount", "STORE\nΜΕΤΡΗΤΑ € 25,74", 25.74),
        TotalRow("ΠΛΗΡΩΤΕΟ OCR variant NAHPΩTEO", "NAHPΩTEO: 45,50 €", 45.50),
        TotalRow("ΣΥΝΟΛΙΚΗ ΑΞΙΑ OCR variant YNUAIKH AEIA", "YNUAIKH AEIA: 50,00 EYRL\nCURRENCY: EUR", 50.00),
        TotalRow("compound keyword ΣΥΝΟΛΙΚΗ ΑΞΙΑ", "STORE\nΣΥΝΟΛΙΚΗ ΑΞΙΑ: 20,01 ΕΥΡΩ", 20.01),
        TotalRow("compound keyword ΓΕΝΙΚΟ ΣΥΝΟΛΟ", "SUPERMARKET\nΓΕΝΙΚΟ ΣΥΝΟΛΟ: 100,00 €", 100.00),
        // Spacing damage inside numbers
        TotalRow("space after comma '45, 50'", "STORE\nTOTAL 45, 50 €", 45.50),
        TotalRow("space before dot '12 .50'", "STORE\nTOTAL 12 .50 €", 12.50),
        TotalRow("space as thousands separator '1 250,50'", "STORE\nTOTAL 1 250,50 €", 1250.50),
        TotalRow("severely mangled '4 5 . 5 0'", "TOTAL 4 5 . 5 0 EUR", 45.50),
        TotalRow("severely mangled '1.2 5 0, 5 0'", "STORE\nTOTAL 1.2 5 0, 5 0 €", 1250.50),
        // Tax vs total confusion
        TotalRow(
            "amount before percent sign beats E0,13 (Receipt 3)",
            "SONICK EU E.E\nΣΥΝΟΛΟ\n20,13 24,00%\nE0,13\nCURRENCY: EUR",
            20.13
        ),
        TotalRow(
            "tax-only lines skipped (Receipt 1)",
            "TRREPN\nΦΠΑ 24%: 9.80 €\nΦΠΑ 13%: 4.20 €\nΣΥΝΟΛΟ: 44.20 €",
            44.20
        ),
        // Receipt-number interference
        TotalRow("serial number ZEIPA skipped", "MARKET\nZEIPA: Y204\nAP: 1926788\nTOTAL: 50.00\nCURRENCY: EUR", 50.00),
        TotalRow(
            "receipt number ΑΡΙΘΜΟΣ skipped",
            "STORE\nΑΡΙΘΜΟΣ ΑΠΌΔΕΙΞΗΣ: 123456\nΣΥΝΟΛΟ: 15.20\nCURRENCY: EUR",
            15.20
        ),
        // Currency symbol / code placement
        TotalRow("currency before amount €50,00", "STORE\nTOTAL €50,00", 50.00),
        TotalRow("currency after amount 50,00 €", "STORE\nTOTAL 50,00 €", 50.00),
        TotalRow("EUR text format", "STORE\nTOTAL EUR 100,00", 100.00),
        // Unit price, VAT percentage, year, phone and tax-id distractors (originally weak < / != checks)
        TotalRow("unit price 1,574 €/ΛΤ not the total", "GAS STATION\nFUEL 1,574 €/ΛΤ\nTOTAL 45,50 €", 45.50),
        TotalRow("VAT percentage 24,00% not the total", "STORE\nΦΠΑ 24,00%\nTOTAL 50,00 €", 50.00),
        TotalRow("whole year 2026 not the total", "STORE\nDATE: 30/01/2026\nTOTAL 50,00 €", 50.00),
        TotalRow("phone number not the total", "STORE\nTEL: 2310 476821\nTOTAL 50,00 €", 50.00),
        TotalRow("tax id ΑΦΜ not the total", "STORE\nΑΦΜ: 094206641\nTOTAL 50,00 €", 50.00),
        TotalRow("year-like amount with decimals 2020,50", "STORE\nTOTAL 2020,50 €", 2020.50),
        // Complete one-line receipts
        TotalRow("complete line 'ΣΥΝΟΛΟ € 50,00'", "ΣΥΝΟΛΟ € 50,00", 50.00),
        TotalRow("complete line 'ΣΥΝΟΛΟ: 80,43 €'", "ΣΥΝΟΛΟ: 80,43 €", 80.43),
        TotalRow("complete line 'ΠΟΣΟ/AMOUNT: €80,43'", "ΠΟΣΟ/AMOUNT: €80,43", 80.43),
        // Simulated OCR errors
        TotalRow("OCR error EYNONO for ΣΥΝΟΛΟ", "MARKET\nEYNONO € 5,00", 5.00),
        // Original assertNotNull (weak): only monetary token is 25,74.
        TotalRow("OCR error METPHTA for ΜΕΤΡΗΤΑ", "CAFE\nMETPHTA 25,74 ΕΥΡΩ", 25.74),
        TotalRow("OCR error EYPΩ for ΕΥΡΩ", "STORE\nTOTAL 50,00 EYPΩ", 50.00),
        TotalRow("OCR error EYP9 for ΕΥΡΩ", "STORE\nTOTAL 50,00 EYP9", 50.00),
        // Actual OCR output
        TotalRow("actual OCR ZYNOAO IONTAN", "STORE\nZYNOAO IONTAN\n182,00 €", 182.00),
        TotalRow("actual OCR NAHPQTEO for ΠΛΗΡΩΤΕΟ", "STORE\nNAHPQTEO 10,00 €", 10.00),
        // Line-item receipts (line items asserted separately)
        TotalRow(
            "total next to line items",
            "CAFE\nΚΑΦΕΣ           3,90 €\nΦΑΓΗΤΟ          16,50 €\nΣΑΛΑΤΕΣ         13,20 €\nΣΥΝΟΛΟ 33,60 €",
            33.60
        ),
        // Card / bilingual receipts
        TotalRow(
            "card receipt POSO/AMOUNT",
            "cardlink\nΑΓΟΡΑ-SALE\n5356 71** **** 6523\nANEIIAQH/CONTACTLESS\nNOsO/AMOUNT: €35,00",
            35.00
        ),
        TotalRow("total before bilingual thank-you", "STORE\nTOTAL 50,00 €\nEYXAPISTOYME - THANK YOU", 50.00),
        TotalRow("bilingual 'TOTAL / ΣΥΝΟΛΟ'", "TOTAL / ΣΥΝΟΛΟ: €45.50", 45.50),
        // Original assertNotNull (weak): only monetary token is 50.00.
        TotalRow("bilingual 'CASH / ΜΕΤΡΗΤΑ'", "CASH / ΜΕΤΡΗΤΑ: €50.00", 50.00)
    )

    @Test
    fun `total amount and EUR currency provenance across keyword, number-format and distractor rows`() {
        assertEquals("totalRows size guard", 41, totalRows.size)
        assertEquals("row names must be unique", totalRows.size, totalRows.map { it.name }.toSet().size)
        for (row in totalRows) {
            val result = parser.parse(row.input)
            val total = result.total
            assertNotNull("[${row.name}] total must be present", total)
            assertEquals("[${row.name}] total amount", row.total, total!!.amount, MONEY_DELTA)
            // Total can only exist when a source currency was resolved; all inputs carry EUR evidence.
            assertEquals("[${row.name}] result currency", "EUR", result.currency)
            assertEquals("[${row.name}] total currency", "EUR", total.currency.code)
        }
    }

    // ------------------------------------------------------------------
    // Table 2: dates
    // ------------------------------------------------------------------

    private data class DateRow(val name: String, val input: String, val ymd: Triple<Int, Int, Int>?)

    private val dateRows = listOf(
        DateRow("DD/MM/YYYY", "STORE\nDATE: 30/01/2026\nTOTAL 10,00 €", Triple(2026, Calendar.JANUARY, 30)),
        DateRow("dashes", "STORE\nDATE: 30-01-2026\nTOTAL 10,00 €", Triple(2026, Calendar.JANUARY, 30)),
        DateRow("dots", "STORE\nDATE: 30.01.2026\nTOTAL 10,00 €", Triple(2026, Calendar.JANUARY, 30)),
        DateRow("short year DD/MM/YY expands to 2026", "STORE\nDATE: 30/01/26\nTOTAL 10,00 €", Triple(2026, Calendar.JANUARY, 30)),
        DateRow("OCR label HM/NIA", "STORE\nHM/NIA: 30/01/2026\nTOTAL 10,00 €", Triple(2026, Calendar.JANUARY, 30)),
        // Original was assertNotNull(result) (vacuous). pattern 1 allows optional spaces around separators.
        DateRow("spaced slashes '30 / 01 / 2026'", "STORE\nDATE: 30 / 01 / 2026\nTOTAL 10,00 €", Triple(2026, Calendar.JANUARY, 30)),
        DateRow("year 2058 rejected as too far in the future", "TOTAL: 50.00\nDATE: 31-1-2058", null)
    )

    @Test
    fun `dates are parsed to exact day month year or rejected`() {
        assertEquals("dateRows size guard", 7, dateRows.size)
        assertEquals("row names must be unique", dateRows.size, dateRows.map { it.name }.toSet().size)
        for (row in dateRows) {
            val date = parser.parse(row.input).date
            val expected = row.ymd
            if (expected == null) {
                assertNull("[${row.name}] date must be rejected", date)
            } else {
                assertNotNull("[${row.name}] date must be parsed", date)
                val cal = Calendar.getInstance()
                cal.timeInMillis = date!!
                assertEquals("[${row.name}] year", expected.first, cal.get(Calendar.YEAR))
                assertEquals("[${row.name}] month", expected.second, cal.get(Calendar.MONTH))
                assertEquals("[${row.name}] day", expected.third, cal.get(Calendar.DAY_OF_MONTH))
            }
        }
    }

    // ------------------------------------------------------------------
    // Table 3: tax / subtotal
    // ------------------------------------------------------------------

    private data class TaxRow(
        val name: String,
        val input: String,
        val total: Double?,
        val subtotal: Double?,
        val tax: Double?
    )

    private val taxRows = listOf(
        TaxRow("VAT with Greek label", "STORE\nSUBTOTAL 100,00 €\nΦΠΑ 24,00%: 24,00 €\nTOTAL 124,00 €", null, null, 24.00),
        TaxRow(
            "bilingual VAT",
            "STORE\nSUBTOTAL: €40.00\nVAT / ΦΠΑ: €2.76\nTOTAL: €42.76",
            42.76, null, 2.76
        ),
        // Originally presence-only: extractSubtotal captures 17,25 from the raw line.
        TaxRow("ΚΑΘΑΡΗ ΑΞΙΑ is the net value (subtotal)", "STORE\nΚΑΘΑΡΗ ΑΞΙΑ: 17,25 ΕΥΡΩ", null, 17.25, null)
    )

    @Test
    fun `tax and subtotal amounts are extracted exactly`() {
        assertEquals("taxRows size guard", 3, taxRows.size)
        assertEquals("row names must be unique", taxRows.size, taxRows.map { it.name }.toSet().size)
        for (row in taxRows) {
            val result = parser.parse(row.input)
            row.total?.let {
                assertNotNull("[${row.name}] total present", result.total)
                assertEquals("[${row.name}] total", it, result.total!!.amount, MONEY_DELTA)
            }
            row.subtotal?.let {
                assertNotNull("[${row.name}] subtotal present", result.subtotal)
                assertEquals("[${row.name}] subtotal", it, result.subtotal!!.amount, MONEY_DELTA)
            }
            row.tax?.let {
                assertNotNull("[${row.name}] tax present", result.tax)
                assertEquals("[${row.name}] tax", it, result.tax!!.amount, MONEY_DELTA)
                assertEquals("[${row.name}] tax currency", "EUR", result.tax!!.currency.code)
            }
        }
    }

    /**
     * Original `test VAT with dots in label` only asserted presence. Static reading of ReceiptParser
     * shows the dotted label is normalised to VAT_KEY and extractTax pattern 1 then captures the first
     * decimal token after it, which is the rate "24,00" rather than the amount "9,68".
     */
    @Test
    fun `KNOWN DEFECT - dotted VAT label captures the rate as the tax amount`() {
        // Characterizes current behaviour; correct value is 9.68; see test-suite-audit handoff release
        // follow-up. Update when production is fixed.
        val result = parser.parse("STORE\nΦ.Π.Α. 24,00%: 9,68 €\nTOTAL 50,00 €")
        assertNotNull("tax must be present", result.tax)
        val tax = result.tax!!
        assertEquals("tax amount (current behaviour captures the 24,00 rate; correct is 9.68)", 24.00, tax.amount, 0.0)
        assertEquals("tax currency", "EUR", tax.currency.code)
        assertEquals("total stays the TOTAL line", 50.00, result.total!!.amount, MONEY_DELTA)
    }

    // ------------------------------------------------------------------
    // Table 4: merchants, line items, confidence, graceful degradation
    // ------------------------------------------------------------------

    private data class MerchantRow(val name: String, val input: String, val check: (String?) -> Boolean)

    private val merchantRows = listOf(
        MerchantRow(
            "Greek merchant ΣΚΛΑΒΕΝΙΤΗΣ exact",
            "ΣΚΛΑΒΕΝΙΤΗΣ\nΑΦΜ: 094206641\nTOTAL 50,00 €"
        ) { it == "ΣΚΛΑΒΕΝΙΤΗΣ" },
        MerchantRow("Greek merchant ΛΙΔΛ", "ΛΙΔΛ\nΑΘΗΝΑ\nTOTAL 35,00 €") {
            it?.contains("ΛΙΔΛ") == true || it?.contains("LIDL") == true
        },
        MerchantRow("merchant with Greeklish ΔΙΑΜΑΝΤΗΣ ΜΑΖΟΥΘΗΣ Α.Ε. is present", "ΔΙΑΜΑΝΤΗΣ ΜΑΖΟΥΘΗΣ Α.Ε.\nΘΕΣΣΑΛΟΝΙΚΗ\nTOTAL 100,00 €") {
            it != null
        }
    )

    @Test
    fun `merchant names are extracted`() {
        assertEquals("merchantRows size guard", 3, merchantRows.size)
        for (row in merchantRows) {
            val merchant = parser.parse(row.input).merchantName
            assertTrue("[${row.name}] unexpected merchant: $merchant", row.check(merchant))
        }
    }

    @Test
    fun `line items are extracted from plain and quantity rows`() {
        val inputs = listOf(
            "plain item rows" to "CAFE\nΚΑΦΕΣ           3,90 €\nΦΑΓΗΤΟ          16,50 €\nΣΑΛΑΤΕΣ         13,20 €\nΣΥΝΟΛΟ 33,60 €",
            "quantity row '2 x'" to "STORE\n2 x ΚΡΑΣΙ ΧΥΜΑ   7,60 €\nTOTAL 7,60 €"
        )
        assertEquals("line item rows size guard", 2, inputs.size)
        for ((name, input) in inputs) {
            assertTrue("[$name] should extract line items", parser.parse(input).lineItems.isNotEmpty())
        }
    }

    private data class ConfidenceRow(val name: String, val input: String, val minInclusive: Float?, val maxExclusive: Float?)

    private val confidenceRows = listOf(
        ConfidenceRow(
            "good data is high confidence",
            "ΣΚΛΑΒΕΝΙΤΗΣ\nΑΦΜ: 094206641\nΗΜΕΡΟΜΗΝΙΑ: 30/01/2026\nΚΑΦΕΣ           3,90 €\nΦΑΓΗΤΟ          16,50 €\nΣΥΝΟΛΟ 20,40 €\nΦΠΑ 24%: 4,89 €",
            0.7f, null
        ),
        ConfidenceRow("minimal data is low confidence", "50,00 €", null, 0.7f)
    )

    @Test
    fun `confidence reflects data quality`() {
        assertEquals("confidenceRows size guard", 2, confidenceRows.size)
        for (row in confidenceRows) {
            val confidence = parser.parse(row.input).confidence
            row.minInclusive?.let { assertTrue("[${row.name}] confidence $confidence >= $it", confidence >= it) }
            row.maxExclusive?.let { assertTrue("[${row.name}] confidence $confidence < $it", confidence < it) }
        }
    }

    /**
     * Replaces the original vacuous assertNotNull(result) on the garbled receipt (parse() returns a
     * non-null type): the real guarantee is that parsing does not throw and fabricates no date.
     */
    @Test
    fun `severely garbled OCR input does not throw and never invents a date`() {
        val garbled = parser.parse("STORE\nIYN. noZOTHTA\n50,00 €")
        assertNull("garbled receipt has no date", garbled.date)
    }

    private companion object {
        /** Amounts are parsed straight from decimal text (String.toDouble), so equality is exact. */
        const val MONEY_DELTA = 0.0
    }
}
