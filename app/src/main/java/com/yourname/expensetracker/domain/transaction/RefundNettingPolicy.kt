package com.yourname.expensetracker.domain.transaction

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.ExpenseRefundLink
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.CurrencyFractionDigits
import com.yourname.expensetracker.domain.core.money.MoneyAmount
import com.yourname.expensetracker.domain.intelligence.DuplicateDetectionPolicy
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Refund netting (DR-05) pure eligibility, capacity and ranking rules (INV-3, INV-5).
 *
 * No DAO and no clock. The lifecycle coordinator evaluates [decideLink] inside its write
 * transaction on freshly read rows and link lists; the use case builds candidates with
 * [rankCandidates], so the eligibility rule has one owner. The amount-equality and merchant-key
 * rules come from [RefundLikelihoodDetector] helpers, so they are not duplicated.
 *
 * ## Money
 * - Scale `d` is the currency's fraction digits; an unavailable scale refuses with
 *   [RefundLinkRefusal.CURRENCY_DIGITS_UNAVAILABLE] (never a guessed 2).
 * - Rounding is HALF_EVEN, the same mode as `roundSnapshot`.
 * - `cap(row) = s(row.amount) - sum(BigDecimal.valueOf(applied))` over the row's links.
 * - Applied amount `A = min(capPurchase, capRefund)`; it must be at least one minor unit.
 *
 * Cross-currency links are refused (D-7); there is no FX anywhere in linking.
 */
object RefundNettingPolicy {

    /** At most this many candidates are returned for a refund. */
    const val MAX_CANDIDATES: Int = 50

    /** The refund may be dated at most this many calendar days before the purchase (posting lag). */
    const val REFUND_DATE_TOLERANCE_DAYS: Int = 1

    /** The candidate range read ends one day after the refund date (exclusive bound is added by the caller). */
    const val CANDIDATE_LOOKAHEAD_MS: Long = 24L * 60L * 60L * 1000L

    /** Outcome of evaluating one (refund, purchase) pair. */
    sealed interface LinkDecision {
        /** The pair is eligible; [applied] is at [scale] and at least one minor unit. */
        data class Apply(val applied: BigDecimal, val scale: Int) : LinkDecision

        /** The exact pair is already linked; nothing to write. */
        data class AlreadyLinked(val linkId: Long) : LinkDecision

        data class Refused(val code: RefundLinkRefusal) : LinkDecision
    }

    /**
     * Evaluates INV-3, INV-4 and INV-5 for a pair. [refundLinks] and [purchaseLinks] are the
     * current link rows of each row (either side), re-read by the caller in the same transaction.
     * [refundInstallmentLinked] and [purchaseInstallmentLinked] say that the row is an installment
     * plan purchase record or the linked expense of a plan entry (same-transaction reads).
     */
    fun decideLink(
        refund: Expense?,
        purchase: Expense?,
        refundLinks: List<ExpenseRefundLink>,
        purchaseLinks: List<ExpenseRefundLink>,
        refundGroupLinked: Boolean,
        purchaseGroupLinked: Boolean,
        refundInstallmentLinked: Boolean,
        purchaseInstallmentLinked: Boolean
    ): LinkDecision {
        if (refund == null) return refused(RefundLinkRefusal.REFUND_NOT_FOUND)
        if (purchase == null) return refused(RefundLinkRefusal.PURCHASE_NOT_FOUND)
        if (refund.id == purchase.id) return refused(RefundLinkRefusal.SAME_ROW)
        if (refund.transactionType != TransactionType.DEPOSIT) return refused(RefundLinkRefusal.NOT_A_DEPOSIT)
        if (purchase.transactionType != TransactionType.PURCHASE) return refused(RefundLinkRefusal.NOT_A_PURCHASE)

        // INV-4: an exact repeat is a no-op; a refund linked to a different purchase is refused.
        refundLinks.firstOrNull {
            it.refundExpenseId == refund.id && it.purchaseExpenseId == purchase.id
        }?.let { return LinkDecision.AlreadyLinked(it.id) }
        if (refundLinks.any { it.refundExpenseId == refund.id }) {
            return refused(RefundLinkRefusal.REFUND_LINKED_ELSEWHERE)
        }

        if (!hasUsableAmount(refund) || !hasUsableAmount(purchase)) {
            return refused(RefundLinkRefusal.INVALID_AMOUNT)
        }
        // INV-19, D-19: the installment plan and the group split own these rows. A row is
        // installment-managed when it carries the exclusion flag OR is a plan purchase record or the
        // payment of a plan entry (the caller reads those links in the same transaction). Installments
        // and refund links are mutually exclusive, so a refund never nets a row the plan also removes.
        if (refund.spendingExclusionReason != null || purchase.spendingExclusionReason != null ||
            refundInstallmentLinked || purchaseInstallmentLinked
        ) {
            return refused(RefundLinkRefusal.INSTALLMENT_MANAGED)
        }
        if (refundGroupLinked || purchaseGroupLinked) return refused(RefundLinkRefusal.GROUP_LINKED)

        val refundCurrency = DuplicateDetectionPolicy.normalizeCurrency(refund.currency)
        val purchaseCurrency = DuplicateDetectionPolicy.normalizeCurrency(purchase.currency)
        if (refundCurrency != purchaseCurrency) return refused(RefundLinkRefusal.CURRENCY_MISMATCH)
        val scale = scaleFor(purchaseCurrency) ?: return refused(RefundLinkRefusal.CURRENCY_DIGITS_UNAVAILABLE)

        if (TimePeriodUtils.daysBetween(purchase.date, refund.date) < -REFUND_DATE_TOLERANCE_DAYS) {
            return refused(RefundLinkRefusal.REFUND_BEFORE_PURCHASE)
        }

        val capPurchase = capacity(purchase.amount, purchaseLinks, scale)
        val capRefund = capacity(refund.amount, refundLinks, scale)
        val applied = capPurchase.min(capRefund)
        if (applied < minorUnit(scale)) return refused(RefundLinkRefusal.NOTHING_TO_APPLY)
        return LinkDecision.Apply(applied, scale)
    }

    /**
     * Ranked purchases a refund could be applied to (INV-3 pair rule, without the group check,
     * which needs a DAO read and stays with the caller). Order: exact amount first, then merchant
     * match first, then `|dayDelta|` ascending, then purchase id descending. Not capped; the
     * caller applies the group filter and [MAX_CANDIDATES]. Returns an empty list when the refund
     * itself is not eligible.
     */
    fun rankCandidates(refund: Expense, purchases: Iterable<Expense>): List<RefundCandidate> {
        if (refund.transactionType != TransactionType.DEPOSIT) return emptyList()
        if (!hasUsableAmount(refund) || refund.spendingExclusionReason != null) return emptyList()
        if (refund.refundNettedAmount > 0.0) return emptyList() // v1: one purchase per refund
        val currency = DuplicateDetectionPolicy.normalizeCurrency(refund.currency)
        val scale = scaleFor(currency) ?: return emptyList()
        val money = CurrencyCode.parse(currency) ?: return emptyList()
        if (remaining(refund, scale) < minorUnit(scale)) return emptyList()
        val refundMerchantKey = RefundLikelihoodDetector.canonicalMerchantKey(refund.merchant, refund.merchantKey)

        val ranked = purchases.mapNotNull { purchase ->
            if (purchase.id == refund.id) return@mapNotNull null
            if (purchase.transactionType != TransactionType.PURCHASE) return@mapNotNull null
            if (!hasUsableAmount(purchase) || purchase.spendingExclusionReason != null) return@mapNotNull null
            if (DuplicateDetectionPolicy.normalizeCurrency(purchase.currency) != currency) return@mapNotNull null
            val dayDelta = TimePeriodUtils.daysBetween(purchase.date, refund.date)
            if (dayDelta < -REFUND_DATE_TOLERANCE_DAYS) return@mapNotNull null
            val remainingPurchase = remaining(purchase, scale)
            if (remainingPurchase < minorUnit(scale)) return@mapNotNull null
            val purchaseMerchantKey =
                RefundLikelihoodDetector.canonicalMerchantKey(purchase.merchant, purchase.merchantKey)
            RefundCandidate(
                purchaseId = purchase.id,
                purchaseDate = purchase.date,
                amount = MoneyAmount(purchase.amount, money),
                remaining = MoneyAmount(remainingPurchase.toDouble(), money),
                exactAmountMatch = RefundLikelihoodDetector.amountsMatch(refund.amount, purchase.amount),
                merchantMatch = refundMerchantKey != null && refundMerchantKey == purchaseMerchantKey,
                dayDelta = dayDelta
            )
        }
        return ranked.sortedWith(
            compareBy<RefundCandidate>(
                { !it.exactAmountMatch },
                { !it.merchantMatch },
                { kotlin.math.abs(it.dayDelta) }
            ).thenByDescending { it.purchaseId }
        )
    }

    /** INV-1: the value stored in `refundNettedAmount`, recomputed from the rows (BigDecimal sum). */
    fun nettedAmountOf(links: List<ExpenseRefundLink>): Double = sumApplied(links).toDouble()

    /** Sum of `appliedAmount` as BigDecimal; never a running Double. */
    fun sumApplied(links: List<ExpenseRefundLink>): BigDecimal =
        links.fold(BigDecimal.ZERO) { acc, link -> acc.add(BigDecimal.valueOf(link.appliedAmount)) }

    /** Minor-unit digits of a normalised currency code, or null when unavailable. */
    fun scaleFor(normalizedCurrency: String): Int? {
        val code = CurrencyCode.parse(normalizedCurrency) ?: return null
        return CurrencyFractionDigits.of(code)
    }

    private fun refused(code: RefundLinkRefusal): LinkDecision = LinkDecision.Refused(code)

    private fun hasUsableAmount(row: Expense): Boolean = row.amount.isFinite() && row.amount > 0.0

    private fun scaled(value: Double, scale: Int): BigDecimal =
        BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_EVEN)

    private fun minorUnit(scale: Int): BigDecimal = BigDecimal.ONE.movePointLeft(scale)

    private fun capacity(amount: Double, links: List<ExpenseRefundLink>, scale: Int): BigDecimal =
        scaled(amount, scale).subtract(sumApplied(links))

    /** Remaining capacity of a row from its denormalised netted amount (INV-1 keeps it equal to the link sum). */
    private fun remaining(row: Expense, scale: Int): BigDecimal =
        scaled(row.amount, scale).subtract(BigDecimal.valueOf(row.refundNettedAmount))
}
