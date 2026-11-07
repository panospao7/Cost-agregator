package com.yourname.expensetracker.domain.core.money

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * refund-netting S2a, INV-7 (home value nets by the factor of the row's OWN snapshot), INV-11 (the
 * list stays gross) and AE-10. Pure JVM; expected values are hand computed from spec 1.4.
 * The existing ComparableHomeValue*ParityTest classes stay untouched and cover r = 0.
 */
class ComparableHomeValueRefundNettingTest {

    private fun expense(
        amount: Double,
        refund: Double,
        baseAmount: Double,
        rate: Double,
        currency: String = "USD",
        baseCurrency: String = "EUR",
        type: TransactionType = TransactionType.PURCHASE,
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
        baseAmount = baseAmount,
        baseCurrency = baseCurrency,
        exchangeRateUsed = rate,
        spendingExclusionReason = reason,
        refundNettedAmount = refund
    )

    // ── r = 0 unchanged ──────────────────────────────────────────

    @Test
    fun `INV7 a row without a refund returns the unchanged snapshot value`() {
        assertEquals(92.0, ComparableHomeValue.snapshotValue(expense(100.0, 0.0, 92.0, 0.92))!!, 0.0)
        // percentage: baseAmount * 50 / 100 ; fixed: myShareAmount * rate
        assertEquals(46.0, ComparableHomeValue.snapshotValue(expense(100.0, 0.0, 92.0, 0.92, shared = true, pct = 50))!!, 0.0)
        assertEquals(27.6, ComparableHomeValue.snapshotValue(expense(100.0, 0.0, 92.0, 0.92, shared = true, fixed = 30.0))!!, 1e-9)
    }

    @Test
    fun `INV7 a negative or NaN refund is treated as no refund`() {
        listOf(-5.0, Double.NaN).forEach { r ->
            assertEquals("r=$r", 92.0, ComparableHomeValue.snapshotValue(expense(100.0, r, 92.0, 0.92))!!, 0.0)
        }
    }

    // ── partial refund: proportional on the row's own snapshot ───

    @Test
    fun `AE10 INV7 a partial refund scales the purchase snapshot by net over gross`() {
        // P7: 100 USD, base 92 EUR at 0.92, refund 40 -> 92 * 60 / 100 = 55.2
        val p7 = expense(100.0, 40.0, 92.0, 0.92)
        assertEquals(55.2, ComparableHomeValue.snapshotValue(p7)!!, 1e-9)
        assertEquals(55.2, p7.normalizedEffectiveAmount!!, 1e-9)
        assertEquals(60.0, p7.effectiveAmount, 0.0)

        // D9: 40 USD deposit, base 36 at 0.90, applied 40 -> fully covered -> 0.0
        val d9 = expense(40.0, 40.0, 36.0, 0.90, type = TransactionType.DEPOSIT)
        assertEquals(0.0, ComparableHomeValue.snapshotValue(d9)!!, 0.0)
        assertEquals(0.0, d9.normalizedEffectiveAmount!!, 0.0)

        // Home net: income - spend was 36 - 92 = -56.00 and becomes 0 - 55.2 = -55.20.
        val before = 36.0 - 92.0
        val after = d9.normalizedEffectiveAmount!! - p7.normalizedEffectiveAmount!!
        assertEquals(-56.0, before, 0.0)
        assertEquals(-55.2, after, 1e-9)
    }

    @Test
    fun `INV7 the refund is valued at the purchase snapshot rate never the refund rate`() {
        // The purchase row carries only its own snapshot (rate 0.92); the refund deposit's rate 0.90
        // is not an input. 55.2 = 92 * 60 / 100, whereas converting the applied 40 at the refund
        // rate (36) and subtracting would give 92 - 36 = 56.
        val value = ComparableHomeValue.snapshotValue(expense(100.0, 40.0, 92.0, 0.92))!!
        assertEquals(55.2, value, 1e-9)
        assertTrue("must differ from the refund-rate valuation 56.0: $value", Math.abs(value - 56.0) > 0.5)
    }

    @Test
    fun `INV7 ownership shares scale by the same net over gross factor`() {
        // percentage: snapshotAmount 92 * 50 / 100 = 46 ; 46 * 60 / 100 = 27.6
        assertEquals(
            27.6,
            ComparableHomeValue.snapshotValue(expense(100.0, 40.0, 92.0, 0.92, shared = true, pct = 50))!!,
            1e-9
        )
        // fixed: myShareAmount 30 * rate 0.92 = 27.6 ; 27.6 * 60 / 100 = 16.56
        assertEquals(
            16.56,
            ComparableHomeValue.snapshotValue(expense(100.0, 40.0, 92.0, 0.92, shared = true, fixed = 30.0))!!,
            1e-9
        )
    }

    // ── r >= amount: zero, no divide by zero ─────────────────────

    @Test
    fun `INV7 a refund that covers the whole amount gives exactly positive zero`() {
        listOf(100.0, 100.01, 150.0).forEach { r ->
            val value = ComparableHomeValue.snapshotValue(expense(100.0, r, 92.0, 0.92))!!
            assertEquals("r=$r", 0L, value.toRawBits())
        }
        // Boundary control: one cent short of the amount is still positive.
        assertTrue(ComparableHomeValue.snapshotValue(expense(100.0, 99.99, 92.0, 0.92))!! > 0.0)
    }

    @Test
    fun `INV7 a zero amount with a refund never divides by zero`() {
        val value = ComparableHomeValue.snapshotValue(expense(0.0, 0.01, 92.0, 0.92))!!
        assertEquals(0L, value.toRawBits())
        assertFalse(value.isNaN())
        // Zero amount and no refund takes the unchanged path and stays finite.
        val neutral = ComparableHomeValue.snapshotValue(expense(0.0, 0.0, 92.0, 0.92))!!
        assertEquals(92.0, neutral, 0.0)
    }

    // ── unchanged branches ───────────────────────────────────────

    @Test
    fun `INV7 neutralised not-mine and no-snapshot branches ignore the refund`() {
        assertEquals(0.0, ComparableHomeValue.snapshotValue(expense(100.0, 40.0, 92.0, 0.92, reason = "INSTALLMENT_PURCHASE"))!!, 0.0)
        assertEquals(0.0, ComparableHomeValue.snapshotValue(expense(100.0, 40.0, 92.0, 0.92, notMine = true))!!, 0.0)
        assertNull(ComparableHomeValue.snapshotValue(expense(100.0, 40.0, 0.0, 0.92)))
        assertNull(ComparableHomeValue.snapshotValue(expense(100.0, 40.0, 92.0, 0.0)))
        assertNull(ComparableHomeValue.snapshotValue(expense(100.0, 100.0, 0.0, 0.0)))
    }

    // ── INV-11: the list stays gross ─────────────────────────────

    @Test
    fun `INV11 comparable value and list amount of a netted row equal the same row without a refund`() {
        val home = CurrencyCode("EUR")
        listOf(
            expense(100.0, 0.0, 92.0, 0.92, currency = "EUR"),
            expense(100.0, 0.0, 92.0, 0.92, currency = "EUR", shared = true, pct = 50),
            expense(100.0, 0.0, 92.0, 0.92, currency = "EUR", shared = true, fixed = 30.0),
            expense(100.0, 0.0, 92.0, 0.92, currency = "USD"),
            expense(100.0, 0.0, 92.0, 0.92, currency = "EUR", notMine = true)
        ).forEach { plain ->
            listOf(0.01, 40.0, 100.0, 150.0).forEach { r ->
                val netted = plain.copy(refundNettedAmount = r)
                assertEquals(
                    "r=$r ${plain.currency} shared=${plain.isSharedExpense}",
                    ComparableHomeValue.comparableValue(plain, home),
                    ComparableHomeValue.comparableValue(netted, home)
                )
                assertEquals(ComparableHomeValue.listAmount(plain), ComparableHomeValue.listAmount(netted), 0.0)
            }
        }
        // Concrete control: a netted home-currency row still sorts and filters on its gross 100.
        val netted = expense(100.0, 40.0, 100.0, 1.0, currency = "EUR")
        assertEquals(100.0, ComparableHomeValue.listAmount(netted), 0.0)
        assertEquals(ComparableValue.Value(100.0), ComparableHomeValue.comparableValue(netted, home))
        assertNotNull(ComparableHomeValue.comparableValue(netted, home))
    }
}
