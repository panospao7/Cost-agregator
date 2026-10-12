package com.yourname.expensetracker.domain.ai.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency

class ReceiptTaxAllocatorTest {

    @Test
    fun `item shares and category projection conserve rounded tax at each supported precision`() {
        for ((currency, tax) in listOf("EUR" to 0.015, "JPY" to 1.5, "KRW" to 1.0)) {
            val items = listOf(item(0.0, 1L), item(3.0, 1L), item(7.0, null))
            val allocation = ReceiptTaxAllocator.allocate(tax, items, currency) as ReceiptTaxAllocation.Available
            val requested = BigDecimal.valueOf(allocation.requestedTax.amount)
            val rounded = BigDecimal.valueOf(tax).setScale(Currency.getInstance(currency).defaultFractionDigits, RoundingMode.HALF_EVEN)
            val itemTotal = allocation.itemAmounts.fold(BigDecimal.ZERO) { total, share ->
                total.add(BigDecimal.valueOf(share.amount))
            }
            val categoryTotal = allocation.categoryAmounts.values.fold(BigDecimal.ZERO) { total, share ->
                total.add(BigDecimal.valueOf(share.amount))
            }.add(BigDecimal.valueOf(allocation.unallocatedTax.amount))

            assertThat(requested.compareTo(rounded)).isEqualTo(0)
            assertThat(itemTotal.compareTo(requested)).isEqualTo(0)
            assertThat(categoryTotal.compareTo(requested)).isEqualTo(0)
            assertThat(allocation.itemAmounts.first().amount).isEqualTo(0.0)
            assertThat(allocation.itemAmounts.map { it.currency }).containsExactly(
                allocation.requestedTax.currency, allocation.requestedTax.currency, allocation.requestedTax.currency
            )
        }
    }

    @Test
    fun `unconfirmed negative prices fail closed despite a positive net basis`() {
        val result = ReceiptTaxAllocator.allocate(8.0, listOf(item(100.0, 1L), item(-20.0, 1L)), "EUR")

        assertThat((result as ReceiptTaxAllocation.Unavailable).failure.reason)
            .isEqualTo("RECEIPT_ITEM_ADJUSTMENT_UNCONFIRMED")
    }

    @Test
    fun `confirmed unclassified adjustment retains signed unallocated tax`() {
        val items = listOf(item(100.0, 1L), item(-20.0, null).copy(isAdjustment = true))
        val result = ReceiptTaxAllocator.allocate(8.0, items, "EUR") as ReceiptTaxAllocation.Available

        assertThat(result.itemAmounts.map { it.amount }).containsExactly(10.0, -2.0).inOrder()
        assertThat(result.categoryAmounts.getValue(1L).amount).isEqualTo(10.0)
        assertThat(result.unallocatedTax.amount).isEqualTo(-2.0)
    }

    @Test
    fun `confirmed adjustments cannot make a nonpositive basis actionable`() {
        val items = listOf(item(100.0, 1L), item(-101.0, null).copy(isAdjustment = true))
        val result = ReceiptTaxAllocator.allocate(8.0, items, "EUR")

        assertThat((result as ReceiptTaxAllocation.Unavailable).failure.reason)
            .isEqualTo("RECEIPT_ITEM_TOTAL_NON_POSITIVE")
    }

    private fun item(amount: Double, categoryId: Long?) = CategorizedReceiptItem(
        itemDescription = "item", amount = amount,
        suggestedCategory = categoryId?.let { CategorySuggestion(it, "category", 0.9f) },
        confidence = 0.9f, rationale = "matched", alternatives = emptyList(), needsReview = false
    )
}
