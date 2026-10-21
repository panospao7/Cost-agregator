package com.yourname.expensetracker.domain.receipt

import com.yourname.expensetracker.data.repository.MerchantRulesRepository
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

class ReceiptParserTest {

    private lateinit var parser: ReceiptParser

    @Before
    fun setup() {
        parser = ReceiptParser(
            MerchantRulesRepository(),
            timeProvider = FakeTimeProvider.forDate(2026, 6, 1)
        )
    }

    @Test
    fun `Greek total normalization preserves the merchant line`() {
        val parsed = parser.parse("Σκλαβενίτης\nΣΥΝΟΛΟ 12,40 EUR\n05.03.2026")

        assertEquals("ΣΚΛΑΒΕΝΊΤΗΣ", parsed.merchantName)
        assertEquals(12.40, requireNotNull(parsed.total).amount, 0.01)
        assertEquals("EUR", parsed.currency)
    }

    /**
     * Money-parsing table: OCR hallucinations, symbol/spacing noise and decimal-separator
     * conventions. Every row asserts the exact amount (zero delta) and the resolved currency.
     */
    private data class TotalRow(
        val name: String,
        val input: String,
        val total: Double,
        val currency: String,
        val lineItemsEmpty: Boolean = false
    )

    private val totalRows = listOf(
        // Exact hallucination map: ZYNOAO for ΣΥΝΟΛΟ, oIA for ΦΠΑ
        TotalRow(
            "exact hallucination map",
            "ΓΡΗΓΟΡΗΣ\nKAAAPH ABIA: 20,00\noIA 24%: 4,80\nZYNOAO 24,80\nCURRENCY: EUR",
            24.80, "EUR"
        ),
        // Latin S inside the Greek string: ΠΟSΟ/AMOUNT
        TotalRow("Latin intrusion strip", "PORTOBELLOS\nΠΟSΟ/AMOUNT: €80,43", 80.43, "EUR"),
        // Arrow ">" between ΣΥΝΟΛΟ and the amount
        TotalRow("geometric artifact strip", "ΓΕΛΑΣΤΟ ΚΡΕΜΜΥΔΙ\nΣΥΝΟΛΟ   >    € 6,80\nMETPHTA       € 6,80", 6.80, "EUR"),
        // Dynamic hallucination ZΥN0/\0 that is not explicitly in the map
        TotalRow("fuzzy matching fallback", "ΣΚΛΑΒΕΝΙΤΗΣ\nZΥN0/\\0 12,50\nMETEHTA 12,50\nCURRENCY: EUR", 12.50, "EUR"),
        TotalRow("decimal standard european", "MARKET\nITEMS 10,00\nTOTAL 45,50 €", 45.50, "EUR"),
        TotalRow("decimal european thousands separator", "TECH STORE\nLAPTOP 1.250,50\nTOTAL 1.250,50 €", 1250.50, "EUR"),
        TotalRow("decimal US standard", "DINER US\nBURGER 12.50\nTOTAL 12.50\nCURRENCY: USD", 12.50, "USD"),
        TotalRow("decimal US thousands separator", "CAR DEALER\nDOWNPAYMENT 1,250.00\nTOTAL 1,250.00\nCURRENCY: USD", 1250.00, "USD"),
        TotalRow("normalization Sigma error EYNONO", "SUPER MARKET\nGALA 1,20\nEYNONO 1,20\nCURRENCY: EUR", 1.20, "EUR"),
        TotalRow("normalization Z error ZYNOAO", "CAFE\nCOFFEE 2,50\nZYNOAO 2,50\nCURRENCY: EUR", 2.50, "EUR"),
        TotalRow("normalization 2 error 2YNONO", "BAKERY\nBREAD 0,90\n2YNONO 0,90\nCURRENCY: EUR", 0.90, "EUR"),
        TotalRow("normalization Lambda error IYNOAO", "STORE\nITEM 10.00\nIYNOAO 10.00\nCURRENCY: EUR", 10.00, "EUR"),
        TotalRow(
            "normalization cash keyword METPHTA yields no line items",
            "COFFEE SHOP\nCAPPUCCINO 3,50\nMETPHTA 3,50\nZYNOAO 3,50\nCURRENCY: EUR",
            3.50, "EUR", lineItemsEmpty = true
        ),
        TotalRow("normalization payable variant NAHPQTEO", "SUPERMARKET\nITEM 1 10.00\nNAHPQTEO 10.00\nCURRENCY: EUR", 10.00, "EUR"),
        TotalRow("complex OCR number fix 'TOTAL_KEY 4 5 , 5 0'", "STORE\nTOTAL_KEY 4 5 , 5 0\nCURRENCY: EUR", 45.50, "EUR")
    )

    @Test
    fun `OCR normalization and decimal parsing yield exact amount and currency per row`() {
        assertEquals("totalRows size guard", 15, totalRows.size)
        assertEquals("row names must be unique", totalRows.size, totalRows.map { it.name }.toSet().size)
        for (row in totalRows) {
            val parsed = parser.parse(row.input)
            val total = parsed.total
            assertNotNull("[${row.name}] total must be present", total)
            assertEquals("[${row.name}] total amount", row.total, total!!.amount, 0.0)
            assertEquals("[${row.name}] result currency", row.currency, parsed.currency)
            assertEquals("[${row.name}] total currency", row.currency, total.currency.code)
            if (row.lineItemsEmpty) {
                assertTrue("[${row.name}] line items must be empty", parsed.lineItems.isEmpty())
            }
        }
    }

    @Test
    fun `test year range expansion`() {
        val input = """
            HISTORY MUSEUM
            TICKET 5.00
            DATE 15/05/2016
            TOTAL 5.00
        """.trimIndent()
        val result = parser.parse(input)

        assertNotNull(result.date)
        val cal = Calendar.getInstance()
        cal.timeInMillis = result.date!!
        assertEquals(2016, cal.get(Calendar.YEAR))
        assertEquals(4, cal.get(Calendar.MONTH))
        assertEquals(15, cal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `test total extraction fallback`() {
        val input = """
            GAS STATION
            PUMP 1
            45,00 €

            THANK YOU
            COME AGAIN
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals(45.00, result.total!!.amount, 0.01)
    }

    @Test
    fun `test merchant extraction - skip noise`() {
        val input = """
            NOMIMH APODEIXI
            START
            MY SHOP NAME
            ADDRESS 123
            TEL 2101234567
            TOTAL 10.00
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("MY SHOP NAME", result.merchantName)
    }

    @Test
    fun `test date ocr fix - 16-D4-2017`() {
        val input = """
            MARKET
            16-D4-2017
            TOTAL 10.00
        """.trimIndent()
        val result = parser.parse(input)
        assertNotNull(result.date)
        val cal = Calendar.getInstance()
        cal.timeInMillis = result.date!!
        assertEquals(2017, cal.get(Calendar.YEAR))
        assertEquals(3, cal.get(Calendar.MONTH))
        assertEquals(16, cal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `quantity formatted line is not emitted twice when overlapping patterns match`() {
        val input = """
            STORE
            2 x ΚΡΑΣΙ ΧΥΜΑ   7,60 €
            TOTAL 7,60 €
        """.trimIndent()

        val result = parser.parse(input)

        assertEquals(1, result.lineItems.size)
        assertEquals("ΚΡΑΣΙ ΧΥΜΑ", result.lineItems.single().description)
        assertEquals(2.0, result.lineItems.single().quantity ?: 0.0, 0.0)
    }

}
