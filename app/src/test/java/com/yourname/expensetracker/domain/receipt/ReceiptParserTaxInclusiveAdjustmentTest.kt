package com.yourname.expensetracker.domain.receipt

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

    @Test
    fun `adjustment case still needs a separate tax amount`() {
        val items = listOf(line("Item ten", 10.0), line("Item five", 5.0), line("Discount", -3.0, isAdjustment = true))

        assertFalse(ReceiptParser.isTaxInclusive(12.0, null, items, "EUR"))
    }
}
