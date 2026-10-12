package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.domain.ai.model.ReceiptItemCategorizationInput
import com.yourname.expensetracker.domain.ai.model.ReceiptItemCategorizationResult
import com.yourname.expensetracker.domain.ai.model.ReceiptTaxAllocation
import com.yourname.expensetracker.domain.core.money.ConversionQuality
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregateBuilder
import com.yourname.expensetracker.domain.core.money.MoneyAggregateResult
import com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableReasonCode
import com.yourname.expensetracker.domain.core.money.RateBasis
import com.yourname.expensetracker.domain.dto.CategoryRef
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceReceiptItemCategorizationServiceTest {

    private val service = OnDeviceReceiptItemCategorizationService()

    @Test
    fun `categorizeItems uses keyword fallback for zero-overlap items`() = runTest {
        val input = ReceiptItemCategorizationInput(
            receiptId = 1L,
            merchant = "Cafe",
            lineItems = listOf(
                ReceiptParser.LineItem(
                    description = "coffee beans",
                    quantity = 1.0,
                    unitPrice = 4.5,
                    totalPrice = 4.5
                )
            ),
            userCategories = listOf(
                CategoryRef(id = 1L, name = "Misc"),
                CategoryRef(id = 2L, name = "Dining")
            ),
            cloudCategoryOptions = emptyList(),
            totalTax = null,
            currency = "EUR"
        )

        val result = service.categorizeItems(input)

        assertNotNull(result)
        val item = result!!.items.single()
        assertEquals("Dining", item.suggestedCategory?.categoryName)
        assertTrue(
            "Expected confidence ~0.6f but was ${item.confidence}",
            kotlin.math.abs(item.confidence - 0.6f) < 0.01f
        )
    }

    @Test
    fun `tax weights use complete source currency aggregates and preserve category counts`() = runTest {
        val result = categorize(
            listOf("Food" to 30.0, "Food" to 10.0, "Household" to 60.0),
            currency = " cad "
        )
        val allocation = result.taxAllocation as ReceiptTaxAllocation.Available

        assertEquals(CurrencyCode.CAD, allocation.itemAggregate.displayCurrency)
        assertEquals(100.0, allocation.itemAggregate.displayAmount, 0.0)
        assertEquals(3, allocation.itemAggregate.totalTransactionCount)
        assertEquals(3, allocation.itemAggregate.metadata.includedTransactionCount)
        assertEquals(RateBasis.IDENTITY, allocation.itemAggregate.requestedRateBasis)
        assertEquals(RateBasis.IDENTITY, allocation.itemAggregate.actualRateBasis)
        assertEquals(ConversionQuality.COMPLETE, allocation.itemAggregate.conversionQuality)
        assertFalse(allocation.itemAggregate.isPartial)
        assertEquals(40.0, allocation.categoryAggregates.getValue(1L).displayAmount, 0.0)
        assertEquals(2, allocation.categoryAggregates.getValue(1L).totalTransactionCount)
        assertEquals(60.0, allocation.categoryAggregates.getValue(2L).displayAmount, 0.0)
        assertEquals(1, allocation.categoryAggregates.getValue(2L).totalTransactionCount)
        assertEquals(10.0, allocation.requestedTax.amount, 0.0)
        assertEquals(CurrencyCode.CAD, allocation.requestedTax.currency)
        assertEquals(4.0, allocation.categoryAmounts.getValue(1L).amount, 0.0)
        assertEquals(6.0, allocation.categoryAmounts.getValue(2L).amount, 0.0)
        assertTrue(allocation.categoryAmounts.values.all { it.currency == CurrencyCode.CAD })
        assertEquals(0.0, allocation.unallocatedTax.amount, 0.0)
        assertEquals(CurrencyCode.CAD, allocation.unallocatedTax.currency)
        assertEquals(allocation.categoryAmounts.mapValues { it.value.amount }, result.taxDistribution)
        assertEquals(listOf(1L, 1L, 2L), result.items.map { it.suggestedCategory?.categoryId })
        assertFalse(result.needsReview)
    }

    @Test
    fun `tax allocation rounds requested tax to source minor units`() = runTest {
        val result = categorize(listOf("Food" to 0.004, "Household" to 0.004), totalTax = 0.001)
        val allocation = result.taxAllocation as ReceiptTaxAllocation.Available

        assertEquals(0.008, allocation.itemAggregate.displayAmount, 0.0)
        assertEquals(0.0, allocation.requestedTax.amount, 0.0)
        assertEquals(0.0, allocation.itemAmounts.sumOf { it.amount }, 0.0)
        assertEquals(0.0, allocation.categoryAmounts.getValue(1L).amount, 0.0)
        assertEquals(0.0, allocation.categoryAmounts.getValue(2L).amount, 0.0)
        assertEquals(0.0, allocation.unallocatedTax.amount, 0.0)
    }

    @Test
    fun `JPY tax shares use largest-remainder whole-unit allocation`() = runTest {
        val result = categorize(listOf("Food" to 1.0, "Household" to 2.0), totalTax = 1.0, currency = "JPY")
        val allocation = result.taxAllocation as ReceiptTaxAllocation.Available

        assertEquals(CurrencyCode.JPY, allocation.itemAggregate.displayCurrency)
        assertEquals(0.0, allocation.categoryAmounts.getValue(1L).amount, 0.0)
        assertEquals(1.0, allocation.categoryAmounts.getValue(2L).amount, 0.0)
        assertEquals(CurrencyCode.JPY, allocation.categoryAmounts.getValue(1L).currency)
    }

    @Test
    fun `unclassified item share stays unallocated instead of inflating classified tax`() = runTest {
        val result = categorize(listOf("Food" to 40.0, "mystery" to 60.0))
        val allocation = result.taxAllocation as ReceiptTaxAllocation.Available

        assertEquals(100.0, allocation.itemAggregate.displayAmount, 0.0)
        assertEquals(setOf(1L), allocation.categoryAmounts.keys)
        assertEquals(4.0, allocation.categoryAmounts.getValue(1L).amount, 0.0)
        assertEquals(6.0, allocation.unallocatedTax.amount, 0.0)
        assertTrue(result.needsReview)
    }

    @Test
    fun `non positive category ids remain in denominator but receive no published tax`() = runTest {
        val result = categorize(
            listOf("Food" to 40.0, "Zero" to 20.0, "Negative" to 40.0),
            categories = listOf(
                CategoryRef(id = 1L, name = "Food"),
                CategoryRef(id = 0L, name = "Zero"),
                CategoryRef(id = -2L, name = "Negative")
            )
        )
        val allocation = result.taxAllocation as ReceiptTaxAllocation.Available

        assertEquals(3, allocation.itemAggregate.totalTransactionCount)
        assertEquals(setOf(1L), allocation.categoryAggregates.keys)
        assertEquals(setOf(1L), result.taxDistribution.keys)
        assertEquals(4.0, allocation.categoryAmounts.getValue(1L).amount, 0.0)
        assertEquals(6.0, allocation.unallocatedTax.amount, 0.0)
    }

    @Test
    fun `all unclassified items retain the full tax as unallocated`() = runTest {
        val result = categorize(listOf("mystery" to 100.0))
        val allocation = result.taxAllocation as ReceiptTaxAllocation.Available

        assertTrue(allocation.categoryAggregates.isEmpty())
        assertTrue(allocation.categoryAmounts.isEmpty())
        assertTrue(result.taxDistribution.isEmpty())
        assertEquals(10.0, allocation.unallocatedTax.amount, 0.0)
        assertEquals(CurrencyCode.EUR, allocation.unallocatedTax.currency)
    }

    @Test
    fun `absent tax is not requested rather than an available zero tax allocation`() = runTest {
        val result = categorize(listOf("Food" to 100.0), totalTax = null, currency = "")

        assertSame(ReceiptTaxAllocation.NotRequested, result.taxAllocation)
        assertTrue(result.taxDistribution.isEmpty())
        assertFalse(result.needsReview)
    }

    @Test
    fun `zero tax has an available zero without dividing by an empty item total`() = runTest {
        for (amounts in listOf(listOf("Food" to 100.0), emptyList())) {
            val result = categorize(amounts, totalTax = 0.0)
            val allocation = result.taxAllocation as ReceiptTaxAllocation.Available

            assertEquals(0.0, allocation.requestedTax.amount, 0.0)
            assertEquals(0.0, allocation.unallocatedTax.amount, 0.0)
            assertEquals(CurrencyCode.EUR, allocation.requestedTax.currency)
            assertTrue(allocation.categoryAmounts.isEmpty())
            assertTrue(result.taxDistribution.isEmpty())
            assertTrue(result.totalConfidence.isFinite())
        }
    }

    @Test
    fun `empty categorization requires review and never publishes NaN confidence`() = runTest {
        val result = categorize(emptyList(), totalTax = null)

        assertEquals(0f, result.totalConfidence, 0f)
        assertTrue(result.needsReview)
        assertTrue(result.items.isEmpty())
        assertSame(ReceiptTaxAllocation.NotRequested, result.taxAllocation)
    }

    @Test
    fun `positive tax without a positive item denominator is explicitly unavailable`() = runTest {
        for (amounts in listOf(emptyList(), listOf("Food" to 0.0), listOf("Food" to -1.0))) {
            assertUnavailable(categorize(amounts), "RECEIPT_ITEM_TOTAL_NON_POSITIVE")
        }
    }

    @Test
    fun `invalid source currency cannot become a default currency even for zero tax`() = runTest {
        for (currency in listOf("", " ", "US", "123", "XXX", "ZZZ")) {
            for (totalTax in listOf(10.0, 0.0)) {
                val result = categorize(listOf("Food" to 100.0), totalTax = totalTax, currency = currency)
                assertUnavailable(result, MoneyDisplayUnavailableReasonCode.INVALID_SOURCE_CURRENCY.name)
                val failure = (result.taxAllocation as ReceiptTaxAllocation.Unavailable).failure
                assertEquals(1, failure.metadata.invalidCurrencyCount)
                assertEquals(1, failure.metadata.excludedTransactionCount)
            }
        }
    }

    @Test
    fun `invalid or negative tax produces a controlled unavailable allocation`() = runTest {
        for (totalTax in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertUnavailable(
                categorize(listOf("Food" to 100.0), totalTax = totalTax),
                MoneyDisplayUnavailableReasonCode.INVALID_AMOUNT.name
            )
        }
        assertUnavailable(categorize(listOf("Food" to 100.0), totalTax = -1.0), "RECEIPT_TAX_NEGATIVE")
    }

    @Test
    fun `non finite item amounts cannot publish a partial successful tax allocation`() = runTest {
        for (amount in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val result = categorize(listOf("Food" to 100.0, "Household" to amount))
            assertUnavailable(result, MoneyDisplayUnavailableReasonCode.INVALID_AMOUNT.name)
            val failure = (result.taxAllocation as ReceiptTaxAllocation.Unavailable).failure
            assertEquals(0, failure.metadata.includedTransactionCount)
            assertEquals(2, failure.metadata.excludedTransactionCount)
        }
    }

    @Test
    fun `overall item overflow is unavailable before computing tax weights`() = runTest {
        val result = categorize(listOf("Food" to Double.MAX_VALUE, "Household" to Double.MAX_VALUE))

        assertUnavailable(result, MoneyDisplayUnavailableReasonCode.INVALID_AMOUNT.name)
        assertEquals(2, (result.taxAllocation as ReceiptTaxAllocation.Unavailable).failure.metadata.excludedTransactionCount)
    }

    @Test
    fun `category overflow is unavailable even when the overall item total is finite`() = runTest {
        val result = categorize(
            listOf(
                "Food" to Double.MAX_VALUE,
                "Household" to -Double.MAX_VALUE,
                "Food" to Double.MAX_VALUE,
                "Household" to -Double.MAX_VALUE,
                "mystery" to 1.0
            ),
            adjustmentIndices = setOf(1, 3)
        )

        assertUnavailable(result, MoneyDisplayUnavailableReasonCode.INVALID_AMOUNT.name)
        assertEquals(2, (result.taxAllocation as ReceiptTaxAllocation.Unavailable).failure.metadata.excludedTransactionCount)
    }

    @Test
    fun `tax share overflow cannot escape as infinite money`() = runTest {
        val result = categorize(
            listOf("Food" to Double.MAX_VALUE, "Household" to -Double.MAX_VALUE, "mystery" to 1.0),
            totalTax = 2.0,
            adjustmentIndices = setOf(1)
        )

        assertUnavailable(result, MoneyDisplayUnavailableReasonCode.INVALID_AMOUNT.name)
    }

    @Test
    fun `finite signed item adjustments retain their existing proportional semantics`() = runTest {
        val result = categorize(
            listOf("Food" to 100.0, "Household" to -20.0), totalTax = 8.0,
            adjustmentIndices = setOf(1)
        )
        val allocation = result.taxAllocation as ReceiptTaxAllocation.Available

        assertEquals(80.0, allocation.itemAggregate.displayAmount, 0.0)
        assertEquals(listOf(10.0, -2.0), allocation.itemAmounts.map { it.amount })
        assertEquals(10.0, allocation.categoryAmounts.getValue(1L).amount, 0.0)
        assertEquals(-2.0, allocation.categoryAmounts.getValue(2L).amount, 0.0)
        assertEquals(0.0, allocation.unallocatedTax.amount, 0.0)
        assertFalse(result.needsReview)
    }

    @Test
    fun `unconfirmed negative prices do not become tax adjustments`() = runTest {
        val result = categorize(listOf("Food" to 100.0, "Household" to -20.0), totalTax = 8.0)

        assertUnavailable(result, "RECEIPT_ITEM_ADJUSTMENT_UNCONFIRMED")
    }

    @Test
    fun `unconfirmed negative prices still require review without requested tax`() = runTest {
        val result = categorize(listOf("Food" to 100.0, "Household" to -20.0), totalTax = null)

        assertSame(ReceiptTaxAllocation.NotRequested, result.taxAllocation)
        assertTrue(result.needsReview)
        assertTrue(result.items.last().needsReview)
    }

    @Test
    fun `signed adjustments with nonpositive net basis fail closed`() = runTest {
        val result = categorize(
            listOf("Food" to 100.0, "Household" to -101.0), totalTax = 8.0,
            adjustmentIndices = setOf(1)
        )

        assertUnavailable(result, "RECEIPT_ITEM_TOTAL_NON_POSITIVE")
    }

    @Test
    fun `minor unit residual uses stable item order and conserves tax`() = runTest {
        val result = categorize(
            listOf("Food" to 1.0, "Household" to 1.0, "Shopping" to 1.0),
            totalTax = 0.01,
            categories = listOf(
                CategoryRef(id = 1L, name = "Food"),
                CategoryRef(id = 2L, name = "Household"),
                CategoryRef(id = 3L, name = "Shopping")
            )
        )
        val allocation = result.taxAllocation as ReceiptTaxAllocation.Available
        assertEquals(listOf(0.01, 0.0, 0.0), allocation.itemAmounts.map { it.amount })
        assertEquals(0.01, allocation.categoryAmounts.getValue(1L).amount, 0.0)
        assertEquals(0.0, allocation.unallocatedTax.amount, 0.0)
        assertEquals(allocation.categoryAmounts.mapValues { it.value.amount }, result.taxDistribution)
    }

    @Test
    fun `same category receives item shares rather than a duplicated category total`() = runTest {
        val result = categorize(listOf("Food" to 3.0, "Food" to 7.0), totalTax = 1.0)
        val allocation = result.taxAllocation as ReceiptTaxAllocation.Available
        assertEquals(listOf(0.3, 0.7), allocation.itemAmounts.map { it.amount })
        assertEquals(1.0, allocation.categoryAmounts.getValue(1L).amount, 0.0)
    }

    private suspend fun categorize(
        amounts: List<Pair<String, Double>>,
        totalTax: Double? = 10.0,
        currency: String = "EUR",
        categories: List<CategoryRef> = listOf(
            CategoryRef(id = 1L, name = "Food"),
            CategoryRef(id = 2L, name = "Household")
        ),
        adjustmentIndices: Set<Int> = emptySet()
    ): ReceiptItemCategorizationResult = requireNotNull(
        service.categorizeItems(
            ReceiptItemCategorizationInput(
                receiptId = 1L,
                merchant = "Merchant",
                lineItems = amounts.mapIndexed { index, (description, amount) ->
                    ReceiptParser.LineItem(
                        description = description,
                        quantity = null,
                        unitPrice = null,
                        totalPrice = amount,
                        isAdjustment = index in adjustmentIndices
                    )
                },
                userCategories = categories,
                totalTax = totalTax,
                currency = currency
            )
        )
    )

    private fun assertUnavailable(result: ReceiptItemCategorizationResult, reason: String) {
        assertTrue(result.taxAllocation is ReceiptTaxAllocation.Unavailable)
        val failure = (result.taxAllocation as ReceiptTaxAllocation.Unavailable).failure
        assertEquals(reason, failure.reason)
        assertEquals(RateBasis.IDENTITY, failure.requestedRateBasis)
        assertTrue(result.taxDistribution.isEmpty())
        assertTrue(result.needsReview)
        assertTrue(result.totalConfidence.isFinite())
    }
}
