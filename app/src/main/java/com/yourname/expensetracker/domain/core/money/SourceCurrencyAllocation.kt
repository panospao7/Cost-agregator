package com.yourname.expensetracker.domain.core.money

import java.math.BigDecimal
import java.math.RoundingMode

object SourceCurrencyAllocation {
    data class Result(val requested: MoneyAmount, val shares: List<MoneyAmount>)

    /**
     * Allocates a requested source-currency amount across signed weights.
     * Rounds the request with HALF_EVEN, then assigns remaining minor units by
     * largest fractional remainder with source item order breaking ties.
     *
     * Negative weights are supported only for explicit discount/refund
     * adjustments. The net basis must remain positive for a positive request;
     * arbitrary negative product prices must be rejected by the caller before
     * reaching this compatibility allocator.
     */
    fun allocate(amount: MoneyAmount, weights: List<Double>): Result {
        if (amount.currency !in CurrencyCode.ALL_SUPPORTED) {
            throw MoneyDisplayUnavailableException(MoneyDisplayUnavailableReasonCode.INVALID_SOURCE_CURRENCY)
        }
        if (!amount.amount.isFinite() || amount.amount < 0 ||
            weights.any { !it.isFinite() }
        ) {
            throw MoneyDisplayUnavailableException(MoneyDisplayUnavailableReasonCode.INVALID_AMOUNT)
        }
        val digits = CurrencyFractionDigits.of(amount.currency)
            ?: throw MoneyDisplayUnavailableException(MoneyDisplayUnavailableReasonCode.INVALID_SOURCE_CURRENCY)
        val requested = BigDecimal.valueOf(amount.amount).setScale(digits, RoundingMode.HALF_EVEN)
        val totalWeight = weights.fold(BigDecimal.ZERO) { total, weight -> total.add(BigDecimal.valueOf(weight)) }
        if (requested.signum() > 0 && totalWeight.signum() <= 0) {
            throw MoneyDisplayUnavailableException(MoneyDisplayUnavailableReasonCode.INVALID_AMOUNT)
        }
        val units = requested.movePointRight(digits)
        val numerators = weights.map { units.multiply(BigDecimal.valueOf(it)) }
        val allocated = numerators.map { numerator ->
            if (requested.signum() == 0) {
                BigDecimal.ZERO
            } else {
                val quotient = numerator.divideToIntegralValue(totalWeight)
                if (numerator.remainder(totalWeight).signum() < 0) quotient.subtract(BigDecimal.ONE) else quotient
            }
        }.toMutableList()
        val remainders = numerators.mapIndexed { index, numerator ->
            numerator.subtract(allocated[index].multiply(totalWeight))
        }
        val remaining = units.subtract(allocated.fold(BigDecimal.ZERO, BigDecimal::add)).intValueExact()
        val order = numerators.indices.sortedWith(
            compareByDescending<Int> { remainders[it] }.thenBy { it }
        )
        repeat(remaining) { index ->
            val target = order[index]
            allocated[target] = allocated[target].add(BigDecimal.ONE)
        }
        val shares = allocated.map {
            val share = it.movePointLeft(digits).toDouble()
            if (!share.isFinite()) {
                throw MoneyDisplayUnavailableException(MoneyDisplayUnavailableReasonCode.INVALID_AMOUNT)
            }
            MoneyAmount(share, amount.currency)
        }
        val persistedTotal = shares.fold(BigDecimal.ZERO) { total, share ->
            total.add(BigDecimal.valueOf(share.amount))
        }
        if (persistedTotal.compareTo(requested) != 0) {
            throw MoneyDisplayUnavailableException(MoneyDisplayUnavailableReasonCode.INVALID_AMOUNT)
        }
        return Result(MoneyAmount(requested.toDouble(), amount.currency), shares)
    }
}
