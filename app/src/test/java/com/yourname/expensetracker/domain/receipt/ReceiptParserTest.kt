package com.yourname.expensetracker.domain.receipt

import com.yourname.expensetracker.data.repository.MerchantRulesRepository
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAmount
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    // ---- DR-09: receipt discounts -------------------------------------------------------

    private val eur = CurrencyCode("EUR")

    private fun eurReceipt(
        items: List<ReceiptParser.LineItem>,
        total: Double?,
        discount: Double?,
        tax: Double? = null
    ) = ReceiptParser.ParsedReceipt(
        merchantName = "STORE",
        total = total?.let { MoneyAmount(it, eur) },
        subtotal = null,
        tax = tax?.let { MoneyAmount(it, eur) },
        date = null,
        currency = "EUR",
        lineItems = items,
        confidence = 0.9f,
        discount = discount?.let { MoneyAmount(it, eur) }
    )

    private fun item(description: String, price: Double, index: Int) =
        ReceiptParser.LineItem(description, null, null, price, index)

    private val tenAndFive = listOf(item("COFFEE", 10.0, 0), item("BREAD", 5.0, 1))

    @Test
    fun `DR-09 Greek discount keyword line parses as a positive discount in the receipt currency`() {
        val parsed = parser.parse("ΜΑΡΚΕΤ\nΣΥΝΟΛΟ 12,00 €\nΕΚΠΤΩΣΗ 3,00")

        assertEquals(MoneyAmount(3.0, eur), parsed.discount)
    }

    @Test
    fun `DR-09 English DISCOUNT line with a minus sign parses as a positive magnitude`() {
        val parsed = parser.parse("MARKET\nTOTAL 12.00 €\nDISCOUNT -3.00")

        assertEquals(MoneyAmount(3.0, eur), parsed.discount)
    }

    @Test
    fun `DR-09 Unicode minus and en dash signs are ignored and the magnitude stays positive`() {
        val minus = parser.parse("MARKET\nTOTAL 12.00 €\nDISCOUNT −3.00")
        val enDash = parser.parse("MARKET\nTOTAL 12.00 €\nDISCOUNT –3.00")

        assertEquals(MoneyAmount(3.0, eur), minus.discount)
        assertEquals(MoneyAmount(3.0, eur), enDash.discount)
    }

    @Test
    fun `DR-09 two discount lines are summed`() {
        val parsed = parser.parse("MARKET\nTOTAL 12.00 €\nDISCOUNT 1.25\nDISCOUNT 0.75")

        assertEquals(MoneyAmount(2.0, eur), parsed.discount)
    }

    @Test
    fun `DR-09 percentage discount line is ignored`() {
        val onlyPercent = parser.parse("MARKET\nTOTAL 12.00 €\nDISCOUNT 10,00%")
        val percentAndAmount = parser.parse("MARKET\nTOTAL 12.00 €\nDISCOUNT 10,00%\nDISCOUNT 3.00")

        assertNull(onlyPercent.discount)
        assertEquals(MoneyAmount(3.0, eur), percentAndAmount.discount)
    }

    @Test
    fun `DR-09 receipt without a discount line has null discount`() {
        val parsed = parser.parse("MARKET\nCOFFEE    10,00\nTOTAL 10,00 €")

        assertNull(parsed.discount)
    }

    @Test
    fun `DR-09 discount is unavailable when the receipt currency is unresolved`() {
        val parsed = parser.parse("MARKET\nTOTAL 12.00\nDISCOUNT 3.00")

        assertNull(parsed.discount)
    }

    @Test
    fun `DR-09 parsed receipt with discount line reconciles to one negative adjustment item`() {
        val parsed = parser.parse(
            "MARKET\nCOFFEE    10,00\nBREAD    5,00\nDISCOUNT    3,00\nTOTAL 12,00 €"
        )

        assertEquals(12.0, requireNotNull(parsed.total).amount, 0.0)
        assertEquals(MoneyAmount(3.0, eur), parsed.discount)
        assertEquals(listOf(10.0, 5.0), parsed.lineItems.map { it.totalPrice })
        val adjusted = parsed.withDiscountAdjustment()
        assertEquals(listOf(10.0, 5.0, -3.0), adjusted.lineItems.map { it.totalPrice })
        assertEquals(listOf(false, false, true), adjusted.lineItems.map { it.isAdjustment })
        assertEquals(parsed.total, adjusted.total)
    }

    @Test
    fun `DR-09 reconciling discount adds exactly one negative adjustment and only recomputes taxInclusive`() {
        val receipt = eurReceipt(tenAndFive, total = 12.0, discount = 3.0, tax = 1.2)

        val adjusted = receipt.withDiscountAdjustment()

        assertEquals(3, adjusted.lineItems.size)
        assertEquals(tenAndFive, adjusted.lineItems.take(2))
        val adjustment = adjusted.lineItems.last()
        assertTrue(adjustment.isAdjustment)
        assertEquals(-3.0, adjustment.totalPrice, 0.0)
        assertEquals(1, adjusted.lineItems.count { it.isAdjustment })
        assertEquals(3, adjusted.lineItems.map { it.itemIndex }.toSet().size)
        assertEquals(MoneyAmount(12.0, eur), adjusted.total)
        assertEquals(MoneyAmount(1.2, eur), adjusted.tax)
        // Net items (10 + 5 - 3) now match the total, so taxInclusive is recomputed to true
        // (strict review 2026-11-06: the first scan must agree with a reopen).
        assertEquals(receipt.copy(taxInclusive = true), adjusted.copy(lineItems = tenAndFive))
    }

    @Test
    fun `DR-09 applying the adjustment twice does not double-apply the discount`() {
        val adjusted = eurReceipt(tenAndFive, total = 12.0, discount = 3.0).withDiscountAdjustment()

        assertEquals(adjusted, adjusted.withDiscountAdjustment())
    }

    @Test
    fun `DR-09 discount that does not reconcile with the total is ignored`() {
        val receipt = eurReceipt(tenAndFive, total = 14.0, discount = 3.0)

        assertEquals(receipt, receipt.withDiscountAdjustment())
        assertEquals(2, receipt.withDiscountAdjustment().lineItems.size)
    }

    @Test
    fun `DR-09 reconciliation tolerance is one cent inclusive`() {
        // items 15 - discount 3 = 12.00; totals within 0.01 reconcile, beyond do not.
        for (total in listOf(12.0, 12.005, 12.01, 11.99)) {
            val adjusted = eurReceipt(tenAndFive, total = total, discount = 3.0).withDiscountAdjustment()
            assertEquals("total $total should reconcile", 3, adjusted.lineItems.size)
        }
        for (total in listOf(12.02, 11.98, 12.5)) {
            val receipt = eurReceipt(tenAndFive, total = total, discount = 3.0)
            assertEquals("total $total should not reconcile", receipt, receipt.withDiscountAdjustment())
        }
    }

    @Test
    fun `DR-09 missing total or empty items or null or non-positive discount leave the receipt unchanged`() {
        val noTotal = eurReceipt(tenAndFive, total = null, discount = 3.0)
        val noItems = eurReceipt(emptyList(), total = 12.0, discount = 3.0)
        val noDiscount = eurReceipt(tenAndFive, total = 15.0, discount = null)
        val zeroDiscount = eurReceipt(tenAndFive, total = 15.0, discount = 0.0)
        val negativeDiscount = eurReceipt(tenAndFive, total = 18.0, discount = -3.0)

        for (receipt in listOf(noTotal, noItems, noDiscount, zeroDiscount, negativeDiscount)) {
            assertEquals(receipt, receipt.withDiscountAdjustment())
        }
    }

    @Test
    fun `DR-09 discount in a different currency than the receipt is rejected`() {
        try {
            ReceiptParser.ParsedReceipt(
                merchantName = null, total = null, subtotal = null, tax = null, date = null,
                currency = "EUR", lineItems = emptyList(), confidence = 0f,
                discount = MoneyAmount(3.0, CurrencyCode("USD"))
            )
            org.junit.Assert.fail("expected currency mismatch")
        } catch (expected: IllegalArgumentException) {
            assertEquals("RECEIPT_MONEY_CURRENCY_MISMATCH", expected.message)
        }
    }

}
