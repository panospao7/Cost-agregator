package com.yourname.expensetracker.domain.transaction

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [RefundLikelihoodDetector] (DR-05 stopgap).
 *
 * Pure domain tests: no Room, no Android framework, no side effects.
 * Relies on [com.yourname.expensetracker.domain.intelligence.DuplicateDetectionPolicy.AMOUNT_TOLERANCE]
 * (0.01) for amount matching and [RefundLikelihoodDetector.WINDOW_DAYS] (90 days) for the lookback window.
 */
class RefundLikelihoodDetectorTest {

    companion object {
        private const val ONE_DAY_MS: Long = 24L * 60L * 60L * 1000L
        // Base epoch timestamp representing Day 1
        private const val DAY_1_MS: Long = 1_710_000_000_000L
    }

    private fun expense(
        id: Long = 1L,
        amount: Double = 200.0,
        currency: String = "EUR",
        merchant: String = "Zara",
        merchantKey: String? = null,
        transactionType: TransactionType = TransactionType.PURCHASE,
        date: Long = DAY_1_MS
    ): Expense = Expense(
        id = id,
        amount = amount,
        currency = currency,
        merchant = merchant,
        merchantKey = merchantKey,
        transactionType = transactionType,
        date = date
    )

    @Test
    fun `positive match when DEPOSIT matches prior PURCHASE by amount currency and merchant within window`() {
        val purchase = expense(
            id = 1L,
            amount = 200.0,
            currency = "EUR",
            merchant = "Zara",
            transactionType = TransactionType.PURCHASE,
            date = DAY_1_MS
        )
        val deposit = expense(
            id = 2L,
            amount = 200.0,
            currency = "EUR",
            merchant = "ZARA",
            transactionType = TransactionType.DEPOSIT,
            date = DAY_1_MS + 9L * ONE_DAY_MS // Day 10 (+9 days offset)
        )

        assertTrue(RefundLikelihoodDetector.isLikelyRefund(deposit, listOf(purchase)))
    }

    @Test
    fun `amount mismatch respects 0_01 DuplicateDetectionPolicy tolerance boundaries`() {
        val purchase = expense(
            id = 1L,
            amount = 200.0,
            currency = "EUR",
            merchant = "Zara",
            transactionType = TransactionType.PURCHASE,
            date = DAY_1_MS
        )
        val depositDate = DAY_1_MS + 9L * ONE_DAY_MS

        // Relies on 0.01 tolerance (DuplicateDetectionPolicy.areAmountsEqual):
        // |199.50 - 200.0| = 0.50 > 0.01 -> FALSE
        val mismatch19950 = expense(
            id = 2L,
            amount = 199.5,
            currency = "EUR",
            merchant = "ZARA",
            transactionType = TransactionType.DEPOSIT,
            date = depositDate
        )
        assertFalse(RefundLikelihoodDetector.isLikelyRefund(mismatch19950, listOf(purchase)))

        // |200.004 - 200.0| = 0.004 <= 0.01 -> TRUE (within tolerance boundary)
        val withinTolerance = expense(
            id = 3L,
            amount = 200.004,
            currency = "EUR",
            merchant = "ZARA",
            transactionType = TransactionType.DEPOSIT,
            date = depositDate
        )
        assertTrue(RefundLikelihoodDetector.isLikelyRefund(withinTolerance, listOf(purchase)))

        // |200.02 - 200.0| = 0.02 > 0.01 -> FALSE (outside tolerance boundary)
        val outsideTolerance = expense(
            id = 4L,
            amount = 200.02,
            currency = "EUR",
            merchant = "ZARA",
            transactionType = TransactionType.DEPOSIT,
            date = depositDate
        )
        assertFalse(RefundLikelihoodDetector.isLikelyRefund(outsideTolerance, listOf(purchase)))
    }

    @Test
    fun `merchant mismatch returns false when canonical keys differ`() {
        val purchase = expense(
            id = 1L,
            amount = 200.0,
            currency = "EUR",
            merchant = "Zara",
            transactionType = TransactionType.PURCHASE,
            date = DAY_1_MS
        )
        val deposit = expense(
            id = 2L,
            amount = 200.0,
            currency = "EUR",
            merchant = "H&M",
            transactionType = TransactionType.DEPOSIT,
            date = DAY_1_MS + 9L * ONE_DAY_MS
        )

        assertFalse(RefundLikelihoodDetector.isLikelyRefund(deposit, listOf(purchase)))
    }

    @Test
    fun `window boundary returns true at exactly 90 days and false at 91 days`() {
        val purchase = expense(
            id = 1L,
            amount = 200.0,
            currency = "EUR",
            merchant = "Zara",
            transactionType = TransactionType.PURCHASE,
            date = DAY_1_MS
        )

        // Exactly 90 days after purchase (delta == WINDOW_MS) -> TRUE
        val depositAt90Days = expense(
            id = 2L,
            amount = 200.0,
            currency = "EUR",
            merchant = "ZARA",
            transactionType = TransactionType.DEPOSIT,
            date = DAY_1_MS + 90L * ONE_DAY_MS
        )
        assertTrue(RefundLikelihoodDetector.isLikelyRefund(depositAt90Days, listOf(purchase)))

        // 91 days after purchase (delta > WINDOW_MS) -> FALSE
        val depositAt91Days = expense(
            id = 3L,
            amount = 200.0,
            currency = "EUR",
            merchant = "ZARA",
            transactionType = TransactionType.DEPOSIT,
            date = DAY_1_MS + 91L * ONE_DAY_MS
        )
        assertFalse(RefundLikelihoodDetector.isLikelyRefund(depositAt91Days, listOf(purchase)))
    }

    @Test
    fun `deposit before purchase with negative delta returns false`() {
        val purchase = expense(
            id = 1L,
            amount = 200.0,
            currency = "EUR",
            merchant = "Zara",
            transactionType = TransactionType.PURCHASE,
            date = DAY_1_MS + 10L * ONE_DAY_MS
        )
        val earlierDeposit = expense(
            id = 2L,
            amount = 200.0,
            currency = "EUR",
            merchant = "ZARA",
            transactionType = TransactionType.DEPOSIT,
            date = DAY_1_MS // 10 days before the purchase (negative delta)
        )

        assertFalse(RefundLikelihoodDetector.isLikelyRefund(earlierDeposit, listOf(purchase)))
    }

    @Test
    fun `wrong deposit type returns false when second row is also a PURCHASE`() {
        val firstPurchase = expense(
            id = 1L,
            amount = 200.0,
            currency = "EUR",
            merchant = "Zara",
            transactionType = TransactionType.PURCHASE,
            date = DAY_1_MS
        )
        val secondPurchase = expense(
            id = 2L,
            amount = 200.0,
            currency = "EUR",
            merchant = "ZARA",
            transactionType = TransactionType.PURCHASE,
            date = DAY_1_MS + 9L * ONE_DAY_MS
        )

        assertFalse(RefundLikelihoodDetector.isLikelyRefund(secondPurchase, listOf(firstPurchase)))
    }

    @Test
    fun `currency mismatch returns false when USD deposit is compared against EUR purchase`() {
        val eurPurchase = expense(
            id = 1L,
            amount = 200.0,
            currency = "EUR",
            merchant = "Zara",
            transactionType = TransactionType.PURCHASE,
            date = DAY_1_MS
        )
        val usdDeposit = expense(
            id = 2L,
            amount = 200.0,
            currency = "USD",
            merchant = "ZARA",
            transactionType = TransactionType.DEPOSIT,
            date = DAY_1_MS + 9L * ONE_DAY_MS
        )

        assertFalse(RefundLikelihoodDetector.isLikelyRefund(usdDeposit, listOf(eurPurchase)))
    }

    @Test
    fun `empty candidates and blank or null merchant key return false without crash`() {
        val validDeposit = expense(
            id = 1L,
            amount = 200.0,
            currency = "EUR",
            merchant = "ZARA",
            transactionType = TransactionType.DEPOSIT,
            date = DAY_1_MS
        )
        assertFalse(RefundLikelihoodDetector.isLikelyRefund(validDeposit, emptyList()))

        // Empty candidate list with blank merchant and null merchantKey (guards empty/null merchant path)
        val blankMerchantDeposit = expense(
            id = 2L,
            amount = 200.0,
            currency = "EUR",
            merchant = "",
            merchantKey = null,
            transactionType = TransactionType.DEPOSIT,
            date = DAY_1_MS
        )
        assertFalse(RefundLikelihoodDetector.isLikelyRefund(blankMerchantDeposit, emptyList()))
        assertFalse(
            RefundLikelihoodDetector.isLikelyRefund(
                depositAmount = 200.0,
                depositMerchant = "   ",
                depositDateMs = DAY_1_MS,
                depositCurrency = "EUR",
                depositMerchantKey = null,
                candidates = emptyList()
            )
        )
    }

    @Test
    fun `purity check ensures candidate list and elements are not mutated`() {
        val candidate1 = expense(
            id = 1L,
            amount = 200.0,
            currency = "EUR",
            merchant = "Zara",
            transactionType = TransactionType.PURCHASE,
            date = DAY_1_MS
        )
        val candidate2 = expense(
            id = 2L,
            amount = 50.0,
            currency = "EUR",
            merchant = "Cafe",
            transactionType = TransactionType.PURCHASE,
            date = DAY_1_MS + 2L * ONE_DAY_MS
        )
        val candidates = listOf(candidate1, candidate2)
        val snapshotBefore = candidates.map { it.copy() }

        val deposit = expense(
            id = 3L,
            amount = 200.0,
            currency = "EUR",
            merchant = "ZARA",
            transactionType = TransactionType.DEPOSIT,
            date = DAY_1_MS + 9L * ONE_DAY_MS
        )

        val result = RefundLikelihoodDetector.isLikelyRefund(deposit, candidates)

        assertTrue(result)
        assertEquals(snapshotBefore, candidates)
    }

    // ---- Refund netting INV-18 -------------------------------------------------------------

    private fun matchingPair(
        depositNetted: Double = 0.0,
        purchaseNetted: Double = 0.0,
        purchaseAmount: Double = 200.0
    ): Pair<Expense, Expense> {
        val purchase = expense(
            id = 1L,
            amount = purchaseAmount,
            merchant = "Zara",
            transactionType = TransactionType.PURCHASE,
            date = DAY_1_MS
        ).copy(refundNettedAmount = purchaseNetted)
        val deposit = expense(
            id = 2L,
            amount = 200.0,
            merchant = "ZARA",
            transactionType = TransactionType.DEPOSIT,
            date = DAY_1_MS + 9L * ONE_DAY_MS
        ).copy(refundNettedAmount = depositNetted)
        return deposit to purchase
    }

    @Test
    fun `INV-18 control - unlinked deposit and unrefunded purchase still raise the notice`() {
        val (deposit, purchase) = matchingPair()
        assertTrue(RefundLikelihoodDetector.isLikelyRefund(deposit, listOf(purchase)))
    }

    @Test
    fun `INV-18 deposit already netted raises no notice`() {
        val (deposit, purchase) = matchingPair(depositNetted = 40.0)
        assertFalse(RefundLikelihoodDetector.isLikelyRefund(deposit, listOf(purchase)))
    }

    @Test
    fun `INV-18 deposit netted by one minor unit raises no notice`() {
        val (deposit, purchase) = matchingPair(depositNetted = 0.01)
        assertFalse(RefundLikelihoodDetector.isLikelyRefund(deposit, listOf(purchase)))
    }

    @Test
    fun `INV-18 fully refunded purchase is skipped`() {
        val (deposit, purchase) = matchingPair(purchaseNetted = 200.0)
        assertFalse(RefundLikelihoodDetector.isLikelyRefund(deposit, listOf(purchase)))
    }

    @Test
    fun `INV-18 purchase netted above its amount is skipped`() {
        val (deposit, purchase) = matchingPair(purchaseNetted = 200.01)
        assertFalse(RefundLikelihoodDetector.isLikelyRefund(deposit, listOf(purchase)))
    }

    @Test
    fun `INV-18 partially refunded purchase is still a candidate`() {
        val (deposit, purchase) = matchingPair(purchaseNetted = 199.99)
        assertTrue(RefundLikelihoodDetector.isLikelyRefund(deposit, listOf(purchase)))
    }

    @Test
    fun `INV-18 a fully refunded purchase is skipped but another matching purchase still raises the notice`() {
        val (deposit, refunded) = matchingPair(purchaseNetted = 200.0)
        val fresh = refunded.copy(id = 3L, refundNettedAmount = 0.0)
        assertTrue(RefundLikelihoodDetector.isLikelyRefund(deposit, listOf(refunded, fresh)))
    }

    @Test
    fun `INV-18 field level overload also skips a fully refunded purchase`() {
        val (deposit, purchase) = matchingPair(purchaseNetted = 200.0)
        assertFalse(
            RefundLikelihoodDetector.isLikelyRefund(
                depositAmount = deposit.amount,
                depositMerchant = deposit.merchant,
                depositDateMs = deposit.date,
                depositCurrency = deposit.currency,
                depositMerchantKey = null,
                candidates = listOf(purchase)
            )
        )
    }
}
