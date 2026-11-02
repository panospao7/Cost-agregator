package com.yourname.expensetracker.domain.transaction

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.intelligence.DuplicateDetectionPolicy
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator

/**
 * Pure heuristic signal for whether a [TransactionType.DEPOSIT] row looks like a refund
 * of a recent [TransactionType.PURCHASE] (DR-05 stopgap; schema-free).
 *
 * Refunds are recorded as [TransactionType.DEPOSIT] in 1.0.x, so category and budget
 * spending are not reversed automatically. This helper surfaces an informational notice
 * when a deposit matches a purchase within the last [WINDOW_DAYS] days by amount and
 * canonical merchant key.
 *
 * Pure function over caller-supplied data: no DAO query, no clock read, no side effects.
 */
object RefundLikelihoodDetector {

    const val WINDOW_DAYS: Int = 90
    const val WINDOW_MS: Long = WINDOW_DAYS * 24L * 60L * 60L * 1000L

    /**
     * Returns true when [deposit] is a [TransactionType.DEPOSIT] and at least one
     * [TransactionType.PURCHASE] in [candidates] occurred within the preceding 90 days
     * (`purchase.date <= deposit.date` and `deposit.date - purchase.date <= WINDOW_MS`),
     * has the same amount within [DuplicateDetectionPolicy.areAmountsEqual] (0.01 tolerance)
     * and the same currency, and shares the canonical merchant key from [MerchantKeyGenerator].
     */
    fun isLikelyRefund(deposit: Expense, candidates: Iterable<Expense>): Boolean {
        if (deposit.transactionType != TransactionType.DEPOSIT) return false
        return isLikelyRefund(
            depositAmount = deposit.amount,
            depositMerchant = deposit.merchant,
            depositDateMs = deposit.date,
            depositCurrency = deposit.currency,
            depositMerchantKey = deposit.merchantKey,
            candidates = candidates
        )
    }

    /**
     * Field-level overload for callers holding deposit fields alongside existing [Expense] rows.
     */
    fun isLikelyRefund(
        depositAmount: Double,
        depositMerchant: String,
        depositDateMs: Long,
        depositCurrency: String? = null,
        depositMerchantKey: String? = null,
        candidates: Iterable<Expense>
    ): Boolean {
        if (!depositAmount.isFinite() || depositAmount <= 0.0) return false
        val targetMerchantKey = canonicalMerchantKey(depositMerchant, depositMerchantKey)
            ?: return false
        val normalizedDepositCurrency = depositCurrency?.let {
            DuplicateDetectionPolicy.normalizeCurrency(it)
        }

        return candidates.any { candidate ->
            candidate.transactionType == TransactionType.PURCHASE &&
                isWithinPrecedingWindow(depositDateMs = depositDateMs, purchaseDateMs = candidate.date) &&
                DuplicateDetectionPolicy.areAmountsEqual(depositAmount, candidate.amount) &&
                (normalizedDepositCurrency == null ||
                    DuplicateDetectionPolicy.normalizeCurrency(candidate.currency) == normalizedDepositCurrency) &&
                canonicalMerchantKey(candidate.merchant, candidate.merchantKey) == targetMerchantKey
        }
    }

    private fun isWithinPrecedingWindow(depositDateMs: Long, purchaseDateMs: Long): Boolean {
        val deltaMs = depositDateMs - purchaseDateMs
        return deltaMs in 0L..WINDOW_MS
    }

    private fun canonicalMerchantKey(merchant: String, storedKey: String?): String? {
        return storedKey?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: MerchantKeyGenerator.generate(merchant).takeIf { it.isNotBlank() }
            ?: merchant.trim().lowercase().takeIf { it.isNotEmpty() }
    }
}
