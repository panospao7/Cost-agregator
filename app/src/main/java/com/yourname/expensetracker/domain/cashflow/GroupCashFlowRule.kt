package com.yourname.expensetracker.domain.cashflow

import com.yourname.expensetracker.data.database.dao.CurrentUserSettlementRow
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType

/**
 * DR-01: the single pure rule for how group-linked personal rows and group settlements
 * enter cash flow. Shared by [CashFlowCalculator] (per-day cash flow) and the forecast start
 * balance (NetCashflowBalanceProvider) so both agree. No IO, no logging.
 */
object GroupCashFlowRule {

    enum class RowClass {
        /** Linked to a group expense someone else paid: the share is not a cash outflow. */
        NON_PAYER,

        /** Linked to a group expense the current user paid: counted at the full paid amount. */
        PAYER,

        /** Not group-linked (or classification unavailable): counted as stored. */
        REGULAR
    }

    /** Non-payer wins over payer, matching the calculator's historical order. */
    fun classify(expenseId: Long, nonPayerIds: Set<Long>, payerIds: Set<Long>): RowClass = when {
        expenseId in nonPayerIds -> RowClass.NON_PAYER
        expenseId in payerIds -> RowClass.PAYER
        else -> RowClass.REGULAR
    }

    /**
     * Cash-flow copy of a payer row at its full amount: share fields dropped. [Expense.effectiveAmount]
     * and the stored row are unchanged everywhere else. spendingExclusionReason is kept.
     */
    fun asFullAmountCopy(expense: Expense): Expense = expense.copy(
        isNotMine = false,
        isSharedExpense = false,
        mySharePercentage = null,
        myShareAmount = null
    )

    /** Ids of rows this window counts as cash movement (non-payer rows are not counted). */
    fun countedExpenseIds(expenses: List<Expense>, nonPayerIds: Set<Long>): Set<Long> =
        expenses
            .filter { it.id !in nonPayerIds }
            .filter {
                when (it.transactionType) {
                    TransactionType.DEPOSIT,
                    TransactionType.PURCHASE,
                    TransactionType.WITHDRAWAL -> true
                    else -> false
                }
            }
            .map { it.id }
            .toSet()

    /**
     * Defensive dedupe only: a settlement linked to an expense that this window already counts is
     * skipped so the same cash is not counted twice. Non-payer rows are not counted, so their
     * settlement stays. Today no write path sets linkedExpenseId.
     */
    fun dedupeSettlements(
        settlements: List<CurrentUserSettlementRow>,
        countedExpenseIds: Set<Long>
    ): List<CurrentUserSettlementRow> =
        settlements.filter { it.linkedExpenseId == null || it.linkedExpenseId !in countedExpenseIds }
}
