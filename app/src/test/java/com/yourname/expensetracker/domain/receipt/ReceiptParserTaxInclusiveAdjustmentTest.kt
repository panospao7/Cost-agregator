package com.yourname.expensetracker.domain.receipt

import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAmount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DR-09 N2 pin: the tax-inclusive check sums the line items INCLUDING a negative discount
 * adjustment, so items 10 + 5 + (-3) = 12 reconcile with a receipt total of 12 (inside the
 * strict 5 percent tolerance, 0.60), while the same items without the discount (sum 15, a
 * difference of 3.00) do not. Synthetic data only.
 */
class ReceiptParserTaxInclusiveAdjustmentTest {

    private fun line(description: String, price: Double, isAdjustment: Boolean = false) =
        ReceiptParser.LineItem(
            description = description,
            quantity = null,
            unitPrice = null,
            totalPrice = price,
            isAdjustment = isAdjustment
        )

    @Test
    fun `items 10 and 5 plus a minus 3 adjustment reconcile with total 12 as tax inclusive`() {
        val items = listOf(line("Item ten", 10.0), line("Item five", 5.0), line("Discount", -3.0, isAdjustment = true))

        assertTrue(ReceiptParser.isTaxInclusive(12.0, 1.0, items, "EUR"))
    }

    @Test
    fun `items 10 and 5 without the adjustment do not reconcile with total 12`() {
        val items = listOf(line("Item ten", 10.0), line("Item five", 5.0))

        assertFalse(ReceiptParser.isTaxInclusive(12.0, 1.0, items, "EUR"))
    }

    private fun receipt(
        total: Double,
        tax: Double?,
        discount: Double?,
        taxInclusive: Boolean = false
    ) = ReceiptParser.ParsedReceipt(
        merchantName = null,
        total = MoneyAmount(total, CurrencyCode.EUR),
        subtotal = null,
        tax = tax?.let { MoneyAmount(it, CurrencyCode.EUR) },
        date = null,
        currency = "EUR",
        lineItems = listOf(line("Item ten", 10.0), line("Item five", 5.0)),
        confidence = 1.0f,
        taxInclusive = taxInclusive,
        discount = discount?.let { MoneyAmount(it, CurrencyCode.EUR) }
    )

    @Test
    fun `withDiscountAdjustment sets taxInclusive consistent with recompute over adjusted items`() {
        val adjusted = receipt(total = 12.0, tax = 1.0, discount = 3.0).withDiscountAdjustment()

        assertEquals(3, adjusted.lineItems.size)
        assertTrue(adjusted.lineItems.last().isAdjustment)
        assertTrue(adjusted.taxInclusive)
        assertEquals(
            ReceiptParser.isTaxInclusive(12.0, 1.0, adjusted.lineItems, "EUR"),
            adjusted.taxInclusive
        )
    }

    @Test
    fun `withDiscountAdjustment without tax amount stays not tax inclusive and matches recompute`() {
        val adjusted = receipt(total = 12.0, tax = null, discount = 3.0).withDiscountAdjustment()

        assertEquals(3, adjusted.lineItems.size)
        assertFalse(adjusted.taxInclusive)
        assertEquals(
            ReceiptParser.isTaxInclusive(12.0, null, adjusted.lineItems, "EUR"),
            adjusted.taxInclusive
        )
    }

    @Test
    fun `non reconciling discount returns receipt unchanged and keeps original taxInclusive`() {
        // 10 + 5 - 3 = 12 differs from total 20, so the discount is ignored.
        val notInclusive = receipt(total = 20.0, tax = 1.0, discount = 3.0, taxInclusive = false)
        val unchangedFalse = notInclusive.withDiscountAdjustment()
        assertEquals(notInclusive, unchangedFalse)
        assertEquals(2, unchangedFalse.lineItems.size)
        assertFalse(unchangedFalse.taxInclusive)

        val inclusive = receipt(total = 20.0, tax = 1.0, discount = 3.0, taxInclusive = true)
        val unchangedTrue = inclusive.withDiscountAdjustment()
        assertEquals(inclusive, unchangedTrue)
        assertTrue(unchangedTrue.taxInclusive)
    }

    @Test
    fun `items 100 plus tax 5 equal total 105 is tax exclusive`() {
        assertFalse(ReceiptParser.isTaxInclusive(105.0, 5.0, listOf(line("Goods", 100.0)), "EUR"))
    }

    @Test
    fun `items summing to the total 105 with tax 5 is tax inclusive`() {
        assertTrue(ReceiptParser.isTaxInclusive(105.0, 5.0, listOf(line("Goods", 105.0)), "EUR"))
    }

    @Test
    fun `items 104_9 against total 105 with tax 20 is tax inclusive despite an OCR rounding gap`() {
        assertTrue(ReceiptParser.isTaxInclusive(105.0, 20.0, listOf(line("Goods", 104.9)), "EUR"))
    }

    @Test
    fun `adjustment case still needs a separate tax amount`() {
        val items = listOf(line("Item ten", 10.0), line("Item five", 5.0), line("Discount", -3.0, isAdjustment = true))

        assertFalse(ReceiptParser.isTaxInclusive(12.0, null, items, "EUR"))
    }
}
