package com.yourname.expensetracker.domain.groups

import com.yourname.expensetracker.data.database.entity.GroupExpense
import com.yourname.expensetracker.data.database.entity.GroupMember
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.SupportedCurrency
import com.yourname.expensetracker.domain.logic.SplitCalculator
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
                settlement.status !in CONSIDERED_STATUSES) continue
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

    /** Whether the displayed group figures are complete, partial (rows left out) or not shown at all. */
    enum class Availability { AVAILABLE, PARTIAL, UNAVAILABLE }

    /**
     * Read model for a group's displayed total and balances. Every amount is in [currency] major
     * units, unrounded. [includedTotal] is null and [memberBalances] is empty iff UNAVAILABLE.
     */
    data class DisplayedBalances(
        val availability: Availability,
        val currency: String,
        val includedTotal: Double?,
        val memberBalances: Map<Long, Double>,
        val excludedExpenseCount: Int,
        val excludedSettlementCount: Int
    ) {
        val settlementPlanAllowed: Boolean get() = availability == Availability.AVAILABLE
    }

    /**
     * Single owner of the displayed group total, member balances, availability and excluded counts.
     * Pure and read-only; no conversion. A row is included iff its currency equals [groupCurrency]
     * by exact string equality; excluded rows are counted without evaluating their amounts.
     */
    fun displayedBalances(
        groupId: Long,
        groupCurrency: String,
        expenses: List<GroupExpense>,
        members: List<GroupMember>,
        settlements: List<SharedGroupSettlement>
    ): DisplayedBalances {
        val consideredSettlements = settlements.filter {
            it.groupId == groupId && it.status in CONSIDERED_STATUSES
        }
        if (!isKnownCurrency(groupCurrency)) {
            return DisplayedBalances(
                availability = Availability.UNAVAILABLE,
                currency = groupCurrency,
                includedTotal = null,
                memberBalances = emptyMap(),
                excludedExpenseCount = expenses.size,
                excludedSettlementCount = consideredSettlements.size
            )
        }
        val includedExpenses = expenses.filter { it.currency == groupCurrency }
        val excludedExpenseCount = expenses.size - includedExpenses.size
        val excludedSettlementCount = consideredSettlements.count { it.currency != groupCurrency }
        val memberBalances = applyToBalances(
            SplitCalculator.calculateBalances(includedExpenses, members),
            settlements,
            groupId,
            groupCurrency
        )
        return DisplayedBalances(
            availability = if (excludedExpenseCount + excludedSettlementCount > 0) {
                Availability.PARTIAL
            } else {
                Availability.AVAILABLE
            },
            currency = groupCurrency,
            includedTotal = includedExpenses.sumOf { it.totalAmount },
            memberBalances = memberBalances,
            excludedExpenseCount = excludedExpenseCount,
            excludedSettlementCount = excludedSettlementCount
        )
    }

    private val CONSIDERED_STATUSES = setOf("RECORDED", "COMPLETED")

    /** Known iff the code is canonical (3 upper-case ASCII letters) and in the supported catalog. */
    private fun isKnownCurrency(code: String): Boolean =
        CurrencyCode.parse(code)?.code == code && SupportedCurrency.fromCode(code) != null
}
