package com.yourname.expensetracker.data.database.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * refund-netting S2a, Kotlin half of INV-6 / INV-8 / INV-10 (spec 1.4 and the acceptance examples
 * AE-1, 2, 4, 7, 8, 9, 13, 20). Pure JVM; every expected number is hand computed from the spec,
 * never derived by re-running the production formula. The SQL half lives in
 * SpendingInclusionParityTest and RefundNettingDaoSpendingTotalsTest.
 */
class ExpenseRefundNettingEffectiveAmountTest {

    private fun expense(
        amount: Double,
        refund: Double = 0.0,
        type: TransactionType = TransactionType.PURCHASE,
        currency: String = "EUR",
        notMine: Boolean = false,
        shared: Boolean = false,
        pct: Int? = null,
        fixed: Double? = null,
        reason: String? = null
    ) = Expense(
        amount = amount,
        currency = currency,
        merchant = "Synthetic Merchant",
        transactionType = type,
        date = 1_700_000_000_000L,
        isNotMine = notMine,
        isSharedExpense = shared,
        mySharePercentage = pct,
        myShareAmount = fixed,
        spendingExclusionReason = reason,
        refundNettedAmount = refund
    )

    private fun deposit(amount: Double, refund: Double = 0.0) =
        expense(amount, refund, type = TransactionType.DEPOSIT)

    // ── AE-1, 2, 4, 13 ───────────────────────────────────────────

    @Test
    fun `AE1 INV6 a partial refund nets the purchase and zeroes the applied deposit`() {
        assertEquals(60.0, expense(100.0, refund = 40.0).effectiveAmount, 0.0)
        assertEquals(0.0, deposit(40.0, refund = 40.0).effectiveAmount, 0.0)
    }

    @Test
    fun `AE13 INV6 unlink restores the gross values`() {
        assertEquals(100.0, expense(100.0, refund = 0.0).effectiveAmount, 0.0)
        assertEquals(40.0, deposit(40.0, refund = 0.0).effectiveAmount, 0.0)
    }

    @Test
    fun `AE2 INV6 a full refund takes the purchase to exactly zero and never below`() {
        assertEquals(0.0, expense(100.0, refund = 100.0).effectiveAmount, 0.0)
        assertEquals(0.0, deposit(60.0, refund = 60.0).effectiveAmount, 0.0)
        // Boundary: one cent short is not zero; one cent over is clamped to zero.
        assertEquals(0.01, expense(100.0, refund = 99.99).effectiveAmount, 1e-9)
        assertEquals(0.0, expense(100.0, refund = 100.01).effectiveAmount, 0.0)
        assertEquals(0.0, expense(100.0, refund = 101.0).effectiveAmount, 0.0)
    }

    @Test
    fun `AE4 INV6 INV8 a refund larger than the purchase leaves the rest of the deposit as income`() {
        assertEquals(0.0, expense(30.0, refund = 30.0).effectiveAmount, 0.0)
        assertEquals(5.0, deposit(35.0, refund = 30.0).effectiveAmount, 0.0)
    }

    // ── AE-7, 8, 9: ownership ────────────────────────────────────

    @Test
    fun `AE7 INV6 a percentage share is taken from the net gross`() {
        assertEquals(50.0, expense(100.0, shared = true, pct = 50).effectiveAmount, 0.0)
        // (100 - 40) * 50 / 100
        assertEquals(30.0, expense(100.0, refund = 40.0, shared = true, pct = 50).effectiveAmount, 0.0)
    }

    @Test
    fun `AE8 INV6 a fixed share is scaled by net over gross`() {
        assertEquals(30.0, expense(100.0, shared = true, fixed = 30.0).effectiveAmount, 0.0)
        // 30 * (100 - 40) / 100
        assertEquals(18.0, expense(100.0, refund = 40.0, shared = true, fixed = 30.0).effectiveAmount, 0.0)
    }

    @Test
    fun `AE9 INV6 INV8 a not-mine purchase stays zero and its refund deposit leaves income`() {
        assertEquals(0.0, expense(80.0, refund = 80.0, notMine = true).effectiveAmount, 0.0)
        assertEquals(0.0, expense(80.0, refund = 0.0, notMine = true).effectiveAmount, 0.0)
        // D8: 80 deposit fully applied -> 0.0, so income falls by 80.
        assertEquals(80.0, deposit(80.0).effectiveAmount, 0.0)
        assertEquals(0.0, deposit(80.0, refund = 80.0).effectiveAmount, 0.0)
    }

    // ── Branch order and neutrality ──────────────────────────────

    @Test
    fun `INV6 a spending exclusion reason beats the netting branch`() {
        assertEquals(0.0, expense(100.0, refund = 40.0, reason = "INSTALLMENT_PURCHASE").effectiveAmount, 0.0)
        assertEquals(0.0, expense(100.0, refund = 0.0, reason = "INSTALLMENT_REVERSED").effectiveAmount, 0.0)
        assertEquals(0.0, deposit(40.0, refund = 0.0).copy(spendingExclusionReason = "INSTALLMENT_PURCHASE").effectiveAmount, 0.0)
    }

    @Test
    fun `INV6 neutrality a row without a refund keeps every pre-feature value`() {
        assertEquals(100.0, expense(100.0).effectiveAmount, 0.0)
        assertEquals(0.0, expense(100.0, notMine = true).effectiveAmount, 0.0)
        assertEquals(25.0, expense(100.0, shared = true, pct = 25).effectiveAmount, 0.0)
        assertEquals(30.0, expense(100.0, shared = true, fixed = 30.0).effectiveAmount, 0.0)
        assertEquals(30.0, expense(100.0, shared = true, pct = 50, fixed = 30.0).effectiveAmount, 0.0) // fixed wins
        assertEquals(100.0, expense(100.0, shared = true).effectiveAmount, 0.0)                         // no share given
        assertEquals(0.0, expense(100.0, shared = true, pct = 0).effectiveAmount, 0.0)
    }

    @Test
    fun `INV6 a zero negative or NaN refund takes the unchanged expression`() {
        listOf(0.0, -5.0, -0.0, Double.NaN).forEach { r ->
            assertEquals("r=$r", 100.0, expense(100.0, refund = r).effectiveAmount, 0.0)
            assertEquals("r=$r", 25.0, expense(100.0, refund = r, shared = true, pct = 25).effectiveAmount, 0.0)
            assertEquals("r=$r", 30.0, expense(100.0, refund = r, shared = true, fixed = 30.0).effectiveAmount, 0.0)
        }
    }

    @Test
    fun `INV6 there is no division by a zero amount`() {
        // amount 0 with a positive refund: the covers-everything branch wins before any division.
        val zeroFixed = expense(0.0, refund = 0.01, shared = true, fixed = 30.0).effectiveAmount
        assertEquals(0.0, zeroFixed, 0.0)
        assertFalse(zeroFixed.isNaN())
        assertEquals(0.0, expense(0.0, refund = 0.01, shared = true, pct = 50).effectiveAmount, 0.0)
        assertEquals(0.0, expense(0.0, refund = 0.01).effectiveAmount, 0.0)
        // amount 0 and no refund: finite, unchanged.
        val neutral = expense(0.0, refund = 0.0, shared = true, fixed = 30.0).effectiveAmount
        assertEquals(30.0, neutral, 0.0)
    }

    @Test
    fun `INV6 the result is never negative and a larger refund never increases it`() {
        val shapes: List<(Double) -> Expense> = listOf(
            { r -> expense(100.0, refund = r) },
            { r -> expense(100.0, refund = r, shared = true, pct = 33) },
            { r -> expense(100.0, refund = r, shared = true, fixed = 30.0) },
            { r -> expense(100.0, refund = r, notMine = true) },
            { r -> deposit(100.0, refund = r) }
        )
        val refunds = listOf(0.01, 1.0, 33.33, 66.66, 99.99, 100.0, 150.0)
        shapes.forEach { shape ->
            var previous = shape(0.0).effectiveAmount
            refunds.forEach { r ->
                val value = shape(r).effectiveAmount
                assertTrue("r=$r value=$value", value >= 0.0)
                assertTrue("r=$r value=$value previous=$previous", value <= previous)
                previous = value
            }
        }
    }

    // ── AE-20: rounding conservation ─────────────────────────────

    @Test
    fun `AE20 JPY 1000 less 333 is exactly 667`() {
        assertEquals(667.0, expense(1000.0, refund = 333.0, currency = "JPY").effectiveAmount, 0.0)
    }

    @Test
    fun `AE20 BHD 10125 less 2005 is 8120 within 1e-9`() {
        assertEquals(8.12, expense(10.125, refund = 2.005, currency = "BHD").effectiveAmount, 1e-9)
    }

    @Test
    fun `AE20 an imported 10005 purchase with a capped 10 refund is about half a cent and the deposit keeps 10`() {
        val purchase = expense(10.005, refund = 10.0)
        assertEquals(0.005, purchase.effectiveAmount, 1e-9)
        assertTrue(purchase.effectiveAmount >= 0.0)
        assertEquals(10.0, deposit(20.0, refund = 10.0).effectiveAmount, 0.0)
    }

    @Test
    fun `AE20 three refunds that sum to exactly 100 leave an effective amount of exactly zero`() {
        // The stored refundNettedAmount is the exact BigDecimal sum (100.00), written as 100.0.
        val netted = expense(100.0, refund = 100.0)
        assertEquals(0.0, netted.effectiveAmount, 0.0)
        assertEquals(0L, netted.effectiveAmount.toRawBits())
    }

    // ── INV-8 and INV-10 ─────────────────────────────────────────

    @Test
    fun `INV8 income minus spend over both sides of a link is unchanged by the link and the unlink`() {
        val gross = deposit(150.0).effectiveAmount - expense(100.0).effectiveAmount
        assertEquals(50.0, gross, 0.0)
        listOf(0.01, 40.0, 99.99, 100.0).forEach { applied ->
            val net = deposit(150.0, refund = applied).effectiveAmount -
                expense(100.0, refund = applied).effectiveAmount
            assertEquals("applied=$applied", gross, net, 1e-9)
        }
    }

    @Test
    fun `INV10 netting is row-local so date category and merchant do not change the effective amount`() {
        val p1 = expense(100.0, refund = 40.0).copy(merchant = "Zara", categoryId = null, date = 1_700_000_000_000L)
        val moved = p1.copy(merchant = "Other", date = 1_700_000_000_000L + 40L * 86_400_000L)
        assertEquals(60.0, p1.effectiveAmount, 0.0)
        assertEquals(p1.effectiveAmount, moved.effectiveAmount, 0.0)
    }

    @Test
    fun `INV9 D9 the spending predicate and countsAsSpending ignore the refund`() {
        assertTrue(expense(100.0, refund = 100.0).countsAsSpending)
        assertFalse(expense(100.0, refund = 100.0, notMine = true).countsAsSpending)
        assertFalse(expense(100.0, refund = 40.0, reason = "INSTALLMENT_PURCHASE").countsAsSpending)
    }
}
