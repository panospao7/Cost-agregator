package com.yourname.expensetracker.domain.ai.model

import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.core.money.MoneyAggregateBuilder
import com.yourname.expensetracker.domain.core.money.MoneyAggregateResult
import com.yourname.expensetracker.domain.core.money.SourceCurrencyAllocation
import com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableException
import com.yourname.expensetracker.domain.core.money.MoneyAmount
import com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableReasonCode
import com.yourname.expensetracker.domain.core.money.RateBasis
import java.math.BigDecimal

object ReceiptTaxAllocator {
    /**
     * Allocates tax over source-currency item bases.
     *
     * Item bases may contain finite signed discount/refund adjustments only
     * when the source item explicitly marks them as adjustments. Negative shares and signed
     * unallocated tax are therefore valid. A positive tax request still fails
     * closed when the net item basis is empty, zero, or negative.
     */
    fun allocate(
        totalTax: Double?,
        items: List<CategorizedReceiptItem>,
        currency: String
    ): ReceiptTaxAllocation {
        if (totalTax == null) return ReceiptTaxAllocation.NotRequested
        val taxAggregate = when (val result = MoneyAggregateBuilder.fromSourceCurrencyAmounts(listOf(totalTax), currency)) {
            is MoneyAggregateResult.Available -> result.aggregate
            is MoneyAggregateResult.Unavailable -> return ReceiptTaxAllocation.Unavailable(result)
        }
        if (totalTax < 0) return unavailableTaxAllocation("RECEIPT_TAX_NEGATIVE")
        val itemAggregate = when (val result = MoneyAggregateBuilder.fromSourceCurrencyAmounts(
            items.asSequence().map { it.amount }.asIterable(), currency
        )) {
            is MoneyAggregateResult.Available -> result.aggregate
            is MoneyAggregateResult.Unavailable -> return ReceiptTaxAllocation.Unavailable(result)
        }
        if (totalTax > 0 && itemAggregate.displayAmount <= 0) {
            return unavailableTaxAllocation("RECEIPT_ITEM_TOTAL_NON_POSITIVE")
        }
        if (items.any { it.amount < 0.0 && !it.isAdjustment }) {
            return unavailableTaxAllocation("RECEIPT_ITEM_ADJUSTMENT_UNCONFIRMED")
        }

        val categoryAggregates = linkedMapOf<Long, MoneyAggregate>()
        for ((categoryId, categoryItems) in items.groupBy { it.suggestedCategory?.categoryId }) {
            if (categoryId == null || categoryId <= 0) continue
            val categoryAggregate = when (val result = MoneyAggregateBuilder.fromSourceCurrencyAmounts(
                categoryItems.asSequence().map { it.amount }.asIterable(), currency
            )) {
                is MoneyAggregateResult.Available -> result.aggregate
                is MoneyAggregateResult.Unavailable -> return ReceiptTaxAllocation.Unavailable(result)
            }
            categoryAggregates[categoryId] = categoryAggregate
        }

        val allocation = try {
            SourceCurrencyAllocation.allocate(
                MoneyAmount(totalTax, taxAggregate.displayCurrency), items.map { it.amount }
            )
        } catch (failure: MoneyDisplayUnavailableException) {
            return unavailableTaxAllocation(failure.reasonCode.name)
        }
        if (totalTax == 0.0) {
            return ReceiptTaxAllocation.Available(
                itemAggregate = itemAggregate,
                categoryAggregates = categoryAggregates,
                requestedTax = allocation.requested,
                categoryAmounts = emptyMap(),
                unallocatedTax = MoneyAmount(0.0, taxAggregate.displayCurrency),
                itemAmounts = allocation.shares
            )
        }
        val categoryTotals = linkedMapOf<Long, BigDecimal>()
        var unallocated = BigDecimal.ZERO
        for ((index, item) in items.withIndex()) {
            val share = allocation.shares[index]
            val categoryId = item.suggestedCategory?.categoryId?.takeIf { it > 0 }
            if (categoryId == null) {
                unallocated = unallocated.add(BigDecimal.valueOf(share.amount))
            } else {
                categoryTotals[categoryId] = (categoryTotals[categoryId] ?: BigDecimal.ZERO)
                    .add(BigDecimal.valueOf(share.amount))
            }
        }
        val categoryAmounts = categoryTotals.mapValues { (_, total) ->
            MoneyAmount(total.toDouble(), taxAggregate.displayCurrency)
        }
        if (categoryAmounts.values.any { !it.amount.isFinite() } || !unallocated.toDouble().isFinite()) {
            return unavailableTaxAllocation(MoneyDisplayUnavailableReasonCode.INVALID_AMOUNT.name)
        }
        val categoryTotal = categoryAmounts.values.fold(BigDecimal.ZERO) { total, share ->
            total.add(BigDecimal.valueOf(share.amount))
        }
        val requestedTotal = BigDecimal.valueOf(allocation.requested.amount)
        val itemTotal = allocation.shares.fold(BigDecimal.ZERO) { total, share ->
            total.add(BigDecimal.valueOf(share.amount))
        }
        if (itemTotal.compareTo(requestedTotal) != 0) {
            return unavailableTaxAllocation("RECEIPT_TAX_ITEM_NOT_CONSERVED")
        }
        val projectedTotal = categoryTotal.add(BigDecimal.valueOf(unallocated.toDouble()))
        if (projectedTotal.compareTo(requestedTotal) != 0) {
            return unavailableTaxAllocation("RECEIPT_TAX_PROJECTION_NOT_CONSERVED")
        }

        return ReceiptTaxAllocation.Available(
            itemAggregate = itemAggregate,
            categoryAggregates = categoryAggregates,
            requestedTax = allocation.requested,
            categoryAmounts = categoryAmounts,
            unallocatedTax = MoneyAmount(unallocated.toDouble(), taxAggregate.displayCurrency),
            itemAmounts = allocation.shares
        )
    }

    private fun unavailableTaxAllocation(reason: String): ReceiptTaxAllocation.Unavailable {
        return ReceiptTaxAllocation.Unavailable(
            MoneyAggregateResult.Unavailable(reason = reason, requestedRateBasis = RateBasis.IDENTITY)
        )
    }
}
