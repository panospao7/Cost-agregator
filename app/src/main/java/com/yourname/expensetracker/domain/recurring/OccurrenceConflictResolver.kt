package com.yourname.expensetracker.domain.recurring

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.recurring.RecurringOccurrenceExpander.OccurrenceCandidate
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import kotlin.math.abs
import javax.inject.Inject

/**
 * Resolves occurrence candidates against actual expenses to determine whether
 * each candidate has been paid, is still planned, or should be skipped.
 *
 * Pure domain logic — no DI needed. Instantiate as a plain class.
 */
class OccurrenceConflictResolver @Inject constructor() {

    /**
     * The resolved status of an occurrence candidate after matching against
     * actual expense data.
     *
     * @property candidate The original occurrence candidate.
     * @property status One of "PLANNED", "PAID", or "SKIPPED".
     * @property linkedExpenseId The ID of the matched expense, if status is PAID.
     * @property paidAmount The amount of the matched expense, if status is PAID.
     * @property paidCurrency The currency of the matched expense, if status is PAID.
     */
    data class ResolvedOccurrence(
        val candidate: OccurrenceCandidate,
        val status: String,
        val linkedExpenseId: Long? = null,
        val paidAmount: Double? = null,
        val paidCurrency: String? = null
    )

    /**
     * Resolves a list of occurrence candidates against a list of actual expenses.
     *
     * Matching rules (all must hold):
     * 1. Same calendar day (by start-of-day comparison).
     * 2. Merchant matches (case-insensitive, via [MerchantKeyGenerator]).
     * 3. Amount within ±10% tolerance of the expected amount.
     * 4. Same currency.
     *
     * Each actual expense may be matched to **at most one** occurrence
     * (first-matched wins).
     *
     * @param candidates The occurrence candidates produced by [RecurringOccurrenceExpander].
     * @param actualExpenses Actual expenses from the same date range (e.g. from an Expense DAO query).
     * @return A list of [ResolvedOccurrence] in the same order as [candidates].
     */
    suspend fun resolve(
        candidates: List<OccurrenceCandidate>,
        actualExpenses: List<Expense>
    ): List<ResolvedOccurrence> {
        val matchedExpenseIds = mutableSetOf<Long>()

        return candidates.map { candidate ->
            val match = actualExpenses.firstOrNull { expense ->
                if (matchedExpenseIds.contains(expense.id)) return@firstOrNull false

                if (expense.isNotMine) return@firstOrNull false
                if (expense.transactionType == TransactionType.TRANSFER ||
                    expense.transactionType == TransactionType.DEPOSIT ||
                    expense.transactionType == TransactionType.UNKNOWN) return@firstOrNull false

                if (!isSameCalendarDay(candidate.dueDate, expense.date)) return@firstOrNull false
                if (!merchantsMatch(candidate.merchant, expense)) return@firstOrNull false
                if (!amountMatches(candidate.expectedAmount, expense.amount)) return@firstOrNull false
                if (!candidate.expectedCurrency.equals(expense.currency, ignoreCase = true)) return@firstOrNull false

                true
            }

            if (match != null) {
                matchedExpenseIds.add(match.id)
                ResolvedOccurrence(
                    candidate = candidate,
                    status = "PAID",
                    linkedExpenseId = match.id,
                    paidAmount = match.amount,
                    paidCurrency = match.currency
                )
            } else {
                ResolvedOccurrence(
                    candidate = candidate,
                    status = "PLANNED"
                )
            }
        }
    }

    /**
     * Checks whether two timestamps fall on the same calendar day by comparing
     * their start-of-day values.
     */
    private fun isSameCalendarDay(timestamp1: Long, timestamp2: Long): Boolean {
        return TimePeriodUtils.getStartOfDay(timestamp1) == TimePeriodUtils.getStartOfDay(timestamp2)
    }

    /**
     * Checks whether [candidateMerchant] matches the merchant of [expense].
     *
     * Matching is case-insensitive using the canonical merchant key from
     * [MerchantKeyGenerator]. If the expense has a stored [merchantKey], that
     * is used as a shortcut; otherwise the raw merchant name is normalized on
     * the fly.
     */
    private fun merchantsMatch(candidateMerchant: String?, expense: Expense): Boolean {
        val candidateKey = MerchantKeyGenerator.generate(candidateMerchant.orEmpty())
        if (candidateKey.isBlank()) return false

        val expenseKey = expense.merchantKey?.takeIf { it.isNotBlank() }
            ?: MerchantKeyGenerator.generate(expense.merchant)

        return candidateKey == expenseKey
    }

    /**
     * Checks whether [actualAmount] is within ±10% of [expectedAmount].
     *
     * Tolerance is computed as `abs(expectedAmount * 0.10)`. Both values must
     * be finite; returns `false` for zero or negative expected amounts.
     */
    private fun amountMatches(expectedAmount: Double, actualAmount: Double): Boolean {
        if (!expectedAmount.isFinite() || !actualAmount.isFinite()) return false
        if (expectedAmount == 0.0) return false

        val tolerance = abs(expectedAmount * 0.10)
        val difference = abs(actualAmount - expectedAmount)
        return difference <= tolerance
    }
}
