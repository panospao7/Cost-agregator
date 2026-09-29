package com.yourname.expensetracker.domain.groups

import java.math.BigDecimal

/** Read-only balance input; deliberately excludes notes and unused persistence fields. */
data class SharedGroupSettlement(
    val groupId: Long,
    val fromMemberId: Long,
    val toMemberId: Long,
    val amount: Double,
    val currency: String,
    val status: String
)

/** One settlement eligibility/sign contract for single-member and displayed balances. */
object GroupSettlementBalancePolicy {
    data class Totals(
        val paid: BigDecimal = BigDecimal.ZERO,
        val received: BigDecimal = BigDecimal.ZERO
    ) {
        fun applyTo(netBalance: Double): Double =
            BigDecimal.valueOf(netBalance).add(paid).subtract(received).toDouble()
    }

    fun totals(
        settlements: List<SharedGroupSettlement>,
        groupId: Long,
        currency: String
    ): Map<Long, Totals> {
        val result = mutableMapOf<Long, Totals>()
        for (settlement in settlements) {
            if (settlement.groupId != groupId || settlement.currency != currency ||
                settlement.status !in setOf("RECORDED", "COMPLETED")) continue
            // Non-finite money fails rather than producing an expense-only balance.
            val amount = BigDecimal.valueOf(settlement.amount)
            val sender = result[settlement.fromMemberId] ?: Totals()
            result[settlement.fromMemberId] = sender.copy(paid = sender.paid.add(amount))
            val recipient = result[settlement.toMemberId] ?: Totals()
            result[settlement.toMemberId] = recipient.copy(received = recipient.received.add(amount))
        }
        return result
    }

    fun applyToBalances(
        expenseBalances: Map<Long, Double>,
        settlements: List<SharedGroupSettlement>,
        groupId: Long,
        currency: String
    ): Map<Long, Double> {
        val totals = totals(settlements, groupId, currency)
        return expenseBalances.mapValues { (memberId, balance) ->
            totals[memberId]?.applyTo(balance) ?: balance
        }
    }
}
