package com.yourname.expensetracker.domain.core.money

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.core.money.ComparableHomeValueFixture.EUR
import com.yourname.expensetracker.domain.core.money.ComparableHomeValueFixture.USD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kotlin-owner tests for [ComparableHomeValue] (INV-1..7). The SQL mirror is
 * tested against the same [ComparableHomeValueFixture] by ComparableHomeValueSqlParityTest.
 */
class ComparableHomeValueTest {

    private fun expense(
        id: Long = 1L,
        currency: String = "EUR",
        amount: Double = 10.0,
        date: Long = ComparableHomeValueFixture.BASE_DATE,
        isNotMine: Boolean = false,
        isShared: Boolean = false,
        myShareAmount: Double? = null,
        sharePercentage: Int? = null,
        baseAmount: Double = 0.0,
        baseCurrency: String = "",
        rate: Double = 0.0
    ) = Expense(
        id = id,
        amount = amount,
        currency = currency,
        merchant = "M$id",
        transactionType = TransactionType.PURCHASE,
        date = date,
        isNotMine = isNotMine,
        isSharedExpense = isShared,
        myShareAmount = myShareAmount,
        mySharePercentage = sharePercentage,
        baseAmount = baseAmount,
        baseCurrency = baseCurrency,
        exchangeRateUsed = rate
    )

    // ── Fixture rows ────────────────────────────────────────────────────────

    @Test
    fun `every fixture case matches the expected value or reason`() {
        for (case in ComparableHomeValueFixture.cases + ComparableHomeValueFixture.ae6BackToEur) {
            val actual = ComparableHomeValue.comparableValue(case.expense, case.home)
            if (case.expectedCv != null) {
                assertTrue("${case.label}: expected Value but was $actual", actual is ComparableValue.Value)
                assertEquals(case.label, case.expectedCv, (actual as ComparableValue.Value).amountInHome, 1e-12)
            } else {
                assertEquals(case.label, ComparableValue.None(case.expectedReason!!), actual)
            }
        }
    }

    @Test
    fun `fixture ids are unique across home currencies`() {
        val ids = ComparableHomeValueFixture.cases.map { it.expense.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    // ── Branch order ────────────────────────────────────────────────────────

    @Test
    fun `branch 1 not-mine wins over a matching currency and a valid snapshot`() {
        val e = expense(currency = "EUR", amount = 50.0, isNotMine = true, baseAmount = 50.0, baseCurrency = "EUR", rate = 1.0)
        assertEquals(ComparableValue.Value(0.0), ComparableHomeValue.comparableValue(e, EUR))
    }

    @Test
    fun `branch 2 ignores the snapshot`() {
        val e = expense(currency = "EUR", amount = 40.0, baseAmount = 99.0, baseCurrency = "USD", rate = 2.0)
        assertEquals(ComparableValue.Value(40.0), ComparableHomeValue.comparableValue(e, EUR))
    }

    @Test
    fun `branch 2 uses the ownership adjusted amount`() {
        val e = expense(currency = "EUR", amount = 80.0, isShared = true, sharePercentage = 25)
        assertEquals(ComparableValue.Value(20.0), ComparableHomeValue.comparableValue(e, EUR))
    }

    @Test
    fun `branch 3 needs a positive base amount, home base currency and positive rate`() {
        val valid = expense(currency = "USD", amount = 20.0, baseAmount = 18.4, baseCurrency = "EUR", rate = 0.92)
        assertEquals(ComparableValue.Value(18.4), ComparableHomeValue.comparableValue(valid, EUR))

        val zeroBase = valid.copy(baseAmount = 0.0)
        assertEquals(
            ComparableValue.None(NoComparableValueReason.NO_SNAPSHOT),
            ComparableHomeValue.comparableValue(zeroBase, EUR)
        )
        val otherBase = valid.copy(baseCurrency = "GBP")
        assertEquals(
            ComparableValue.None(NoComparableValueReason.SNAPSHOT_OTHER_CURRENCY),
            ComparableHomeValue.comparableValue(otherBase, EUR)
        )
        val badRate = valid.copy(exchangeRateUsed = 0.0)
        assertEquals(
            ComparableValue.None(NoComparableValueReason.SNAPSHOT_RATE_INVALID),
            ComparableHomeValue.comparableValue(badRate, EUR)
        )
    }

    @Test
    fun `branch 4 never falls back to the raw amount of a foreign row`() {
        val e = expense(currency = "USD", amount = 20.0)
        val cv = ComparableHomeValue.comparableValue(e, EUR)
        assertTrue(cv is ComparableValue.None)
    }

    @Test
    fun `a foreign row with a nan or missing snapshot has no comparable value`() {
        val nanBase = expense(currency = "USD", amount = 20.0, baseAmount = Double.NaN, baseCurrency = "EUR", rate = 0.9)
        assertEquals(
            ComparableValue.None(NoComparableValueReason.NO_SNAPSHOT),
            ComparableHomeValue.comparableValue(nanBase, EUR)
        )
    }

    // ── Reasons ─────────────────────────────────────────────────────────────

    @Test
    fun `reason precedence is no snapshot, other currency, invalid rate`() {
        // baseAmount <= 0 wins even when baseCurrency differs and the rate is invalid.
        val a = expense(currency = "USD", baseAmount = 0.0, baseCurrency = "GBP", rate = -1.0)
        assertEquals(ComparableValue.None(NoComparableValueReason.NO_SNAPSHOT), ComparableHomeValue.comparableValue(a, EUR))
        // baseAmount > 0 and other base currency wins over an invalid rate.
        val b = expense(currency = "USD", baseAmount = 5.0, baseCurrency = "GBP", rate = -1.0)
        assertEquals(
            ComparableValue.None(NoComparableValueReason.SNAPSHOT_OTHER_CURRENCY),
            ComparableHomeValue.comparableValue(b, EUR)
        )
    }

    // ── Zero normalisation ──────────────────────────────────────────────────

    @Test
    fun `negative zero is normalised to positive zero`() {
        val shared = expense(currency = "EUR", amount = 10.0, isShared = true, myShareAmount = -0.0)
        val value = (ComparableHomeValue.comparableValue(shared, EUR) as ComparableValue.Value).amountInHome
        assertEquals(0.0.toRawBits(), value.toRawBits())

        val rounded = expense(currency = "EUR", amount = -0.0)
        val value2 = (ComparableHomeValue.comparableValue(rounded, EUR) as ComparableValue.Value).amountInHome
        assertEquals(0.0.toRawBits(), value2.toRawBits())

        val snapshot = expense(
            currency = "USD", amount = 10.0, isShared = true, myShareAmount = -0.0,
            baseAmount = 5.0, baseCurrency = "EUR", rate = 0.5
        )
        val value3 = (ComparableHomeValue.comparableValue(snapshot, EUR) as ComparableValue.Value).amountInHome
        assertEquals(0.0.toRawBits(), value3.toRawBits())
        assertEquals(0.0.toRawBits(), (ComparableHomeValue.snapshotValue(snapshot))!!.toRawBits())
    }

    @Test
    fun `a negative zero value equals a positive zero value`() {
        assertEquals(
            ComparableValue.Value(0.0),
            ComparableHomeValue.comparableValue(expense(isShared = true, myShareAmount = -0.0), EUR)
        )
    }

    // ── Currency matching ───────────────────────────────────────────────────

    @Test
    fun `currency match is exact and case sensitive`() {
        val lower = expense(currency = "usd", amount = 10.0, baseAmount = 0.0, baseCurrency = "EUR", rate = 0.0)
        assertEquals(
            ComparableValue.None(NoComparableValueReason.NO_SNAPSHOT),
            ComparableHomeValue.comparableValue(lower, USD)
        )
        // A home parsed from lowercase input is the uppercase code.
        val parsedHome = CurrencyCode.parse("usd")
        assertNotNull(parsedHome)
        assertEquals(
            ComparableValue.None(NoComparableValueReason.NO_SNAPSHOT),
            ComparableHomeValue.comparableValue(lower, parsedHome!!)
        )
        // Lowercase row currency is not branch 2, but a valid USD snapshot still applies.
        val lowerWithSnapshot = lower.copy(baseAmount = 10.0, baseCurrency = "USD", exchangeRateUsed = 1.0)
        assertEquals(ComparableValue.Value(10.0), ComparableHomeValue.comparableValue(lowerWithSnapshot, USD))
        // Lowercase snapshot currency is not the home currency.
        val lowerBase = expense(currency = "EUR", baseAmount = 10.0, baseCurrency = "usd", rate = 1.0)
        assertEquals(
            ComparableValue.None(NoComparableValueReason.SNAPSHOT_OTHER_CURRENCY),
            ComparableHomeValue.comparableValue(lowerBase, USD)
        )
    }

    @Test
    fun `negative legacy home-currency amounts pass through and negative foreign snapshots are rejected`() {
        val home = expense(currency = "EUR", amount = -5.0)
        assertEquals(ComparableValue.Value(-5.0), ComparableHomeValue.comparableValue(home, EUR))
        val foreign = expense(currency = "USD", amount = -5.0, baseAmount = -4.6, baseCurrency = "EUR", rate = 0.92)
        assertEquals(
            ComparableValue.None(NoComparableValueReason.NO_SNAPSHOT),
            ComparableHomeValue.comparableValue(foreign, EUR)
        )
    }

    // ── AE-6: two home currencies over the same rows ───────────────────────

    @Test
    fun `AE-6 the same rows give different values under two home currencies`() {
        val a = expense(id = 1, currency = "EUR", amount = 100.0, baseAmount = 100.0, baseCurrency = "EUR", rate = 1.0)
        val b = expense(id = 2, currency = "USD", amount = 30.0, baseAmount = 27.78, baseCurrency = "EUR", rate = 0.926)
        val c = expense(id = 3, currency = "USD", amount = 95.0, baseAmount = 95.0, baseCurrency = "USD", rate = 1.0)

        val underUsd = listOf(a, b, c).sortedWith(ComparableHomeValue.comparator(USD, ComparableHomeValue.Direction.DESCENDING))
        assertEquals(listOf(3L, 2L, 1L), underUsd.map { it.id })
        assertEquals(
            ComparableValue.None(NoComparableValueReason.SNAPSHOT_OTHER_CURRENCY),
            ComparableHomeValue.comparableValue(a, USD)
        )

        val underEur = listOf(a, b, c).sortedWith(ComparableHomeValue.comparator(EUR, ComparableHomeValue.Direction.DESCENDING))
        assertEquals(listOf(1L, 2L, 3L), underEur.map { it.id })
        assertEquals(ComparableValue.Value(27.78), ComparableHomeValue.comparableValue(b, EUR))
        assertEquals(
            ComparableValue.None(NoComparableValueReason.SNAPSHOT_OTHER_CURRENCY),
            ComparableHomeValue.comparableValue(c, EUR)
        )
    }

    // ── snapshotValue ───────────────────────────────────────────────────────

    @Test
    fun `snapshotValue is branch 3 without the home currency condition`() {
        val plain = expense(currency = "USD", amount = 20.0, baseAmount = 18.4, baseCurrency = "GBP", rate = 0.92)
        assertEquals(18.4, ComparableHomeValue.snapshotValue(plain)!!, 0.0)

        val myShare = expense(
            currency = "USD", amount = 100.0, isShared = true, myShareAmount = 50.0,
            baseAmount = 50.0, baseCurrency = "GBP", rate = 0.5
        )
        assertEquals(25.0, ComparableHomeValue.snapshotValue(myShare)!!, 0.0)

        val pct = expense(
            currency = "USD", amount = 100.0, isShared = true, sharePercentage = 50,
            baseAmount = 92.0, baseCurrency = "GBP", rate = 0.92
        )
        assertEquals(46.0, ComparableHomeValue.snapshotValue(pct)!!, 0.0)
    }

    @Test
    fun `snapshotValue returns null without a usable snapshot and never the raw amount`() {
        assertNull(ComparableHomeValue.snapshotValue(expense(currency = "USD", amount = 20.0)))
        assertNull(
            ComparableHomeValue.snapshotValue(
                expense(currency = "USD", amount = 20.0, baseAmount = 18.4, baseCurrency = "EUR", rate = 0.0)
            )
        )
        assertNull(
            ComparableHomeValue.snapshotValue(
                expense(currency = "EUR", amount = 20.0, baseAmount = 0.0, baseCurrency = "EUR", rate = 0.0)
            )
        )
    }

    @Test
    fun `snapshotValue of a not-mine row is zero`() {
        assertEquals(0.0, ComparableHomeValue.snapshotValue(expense(isNotMine = true))!!, 0.0)
    }

    // ── Comparator (INV-5) ──────────────────────────────────────────────────

    @Test
    fun `descending order puts values high to low then none rows last by date then id descending`() {
        val sorted = ComparableHomeValueFixture.orderingRows
            .sortedWith(ComparableHomeValue.comparator(EUR, ComparableHomeValue.Direction.DESCENDING))
        // 104 (25), then 10.00 rows by date DESC / id DESC (103, 102 @ +20, 101 @ +10), then 105 (5),
        // then None rows: 108 (+40), 107 and 106 (+30, id DESC).
        assertEquals(listOf(104L, 103L, 102L, 101L, 105L, 108L, 107L, 106L), sorted.map { it.id })
    }

    @Test
    fun `ascending order keeps none rows last and ties still date then id descending`() {
        val sorted = ComparableHomeValueFixture.orderingRows
            .sortedWith(ComparableHomeValue.comparator(EUR, ComparableHomeValue.Direction.ASCENDING))
        assertEquals(listOf(105L, 103L, 102L, 101L, 104L, 108L, 107L, 106L), sorted.map { it.id })
    }

    @Test
    fun `comparator is a consistent total order independent of input order`() {
        val expected = ComparableHomeValueFixture.orderingRows
            .sortedWith(ComparableHomeValue.comparator(EUR, ComparableHomeValue.Direction.DESCENDING))
            .map { it.id }
        val reversed = ComparableHomeValueFixture.orderingRows.reversed()
            .sortedWith(ComparableHomeValue.comparator(EUR, ComparableHomeValue.Direction.DESCENDING))
            .map { it.id }
        assertEquals(expected, reversed)
    }

    @Test
    fun `AE-1 no-rate foreign row is last in both directions`() {
        val e1 = expense(id = 1, currency = "EUR", amount = 15.0, date = 1L, baseAmount = 15.0, baseCurrency = "EUR", rate = 1.0)
        val e2 = expense(id = 2, currency = "EUR", amount = 25.0, date = 2L, baseAmount = 25.0, baseCurrency = "EUR", rate = 1.0)
        val u1 = expense(id = 3, currency = "USD", amount = 20.0, date = 3L, baseAmount = 0.0, baseCurrency = "EUR", rate = 0.0)
        val rows = listOf(u1, e1, e2)
        assertEquals(
            listOf(2L, 1L, 3L),
            rows.sortedWith(ComparableHomeValue.comparator(EUR, ComparableHomeValue.Direction.DESCENDING)).map { it.id }
        )
        assertEquals(
            listOf(1L, 2L, 3L),
            rows.sortedWith(ComparableHomeValue.comparator(EUR, ComparableHomeValue.Direction.ASCENDING)).map { it.id }
        )
    }

    @Test
    fun `AE-9 not-mine row sorts as zero`() {
        val n1 = expense(id = 1, currency = "USD", amount = 50.0, isNotMine = true, date = 1L)
        val e1 = expense(id = 2, currency = "EUR", amount = 5.0, date = 2L)
        val sorted = listOf(n1, e1)
            .sortedWith(ComparableHomeValue.comparator(EUR, ComparableHomeValue.Direction.DESCENDING))
        assertEquals(listOf(2L, 1L), sorted.map { it.id })
    }

    @Test
    fun `AE-17 negative home-currency amount is first in ascending order`() {
        val q2 = expense(id = 1, currency = "EUR", amount = -5.0, date = 1L)
        val e = expense(id = 2, currency = "EUR", amount = 3.0, date = 2L)
        val sorted = listOf(e, q2)
            .sortedWith(ComparableHomeValue.comparator(EUR, ComparableHomeValue.Direction.ASCENDING))
        assertEquals(listOf(1L, 2L), sorted.map { it.id })
    }

    // ── withinBounds ────────────────────────────────────────────────────────

    @Test
    fun `bounds are inclusive on both ends`() {
        val v = ComparableValue.Value(25.0)
        assertTrue(ComparableHomeValue.withinBounds(v, 25.0, null))
        assertTrue(ComparableHomeValue.withinBounds(v, null, 25.0))
        assertTrue(ComparableHomeValue.withinBounds(v, 25.0, 25.0))
        assertFalse(ComparableHomeValue.withinBounds(v, 25.01, null))
        assertFalse(ComparableHomeValue.withinBounds(v, null, 24.99))
    }

    @Test
    fun `none rows are excluded once a bound is set and pass when no bound is set`() {
        val none = ComparableValue.None(NoComparableValueReason.NO_SNAPSHOT)
        assertTrue(ComparableHomeValue.withinBounds(none, null, null))
        assertFalse(ComparableHomeValue.withinBounds(none, 0.0, null))
        assertFalse(ComparableHomeValue.withinBounds(none, null, 1_000_000.0))
    }

    @Test
    fun `AE-9 min zero includes a not-mine row and min 0_01 excludes it`() {
        val n1 = expense(currency = "USD", amount = 50.0, isNotMine = true)
        assertTrue(ComparableHomeValue.withinBounds(n1, EUR, 0.0, null))
        assertFalse(ComparableHomeValue.withinBounds(n1, EUR, 0.01, null))
    }

    @Test
    fun `AE-26 unrounded legacy snapshot is excluded by max 18_40 and included by min 18_40`() {
        val legacy = expense(
            currency = "USD", amount = 20.0,
            baseAmount = 18.400000000000002, baseCurrency = "EUR", rate = 0.92
        )
        assertFalse(ComparableHomeValue.withinBounds(legacy, EUR, null, 18.4))
        assertTrue(ComparableHomeValue.withinBounds(legacy, EUR, 18.4, null))
    }

    @Test
    fun `AE-2 converted foreign row is judged by its snapshot not its raw amount`() {
        val c1 = expense(currency = "USD", amount = 20.0, baseAmount = 18.40, baseCurrency = "EUR", rate = 0.92)
        assertFalse(ComparableHomeValue.withinBounds(c1, EUR, 19.0, null))
        assertTrue(ComparableHomeValue.withinBounds(c1, EUR, null, 19.0))
    }

    @Test
    fun `bounds membership on the fixture follows the expected values`() {
        for ((min, max) in ComparableHomeValueFixture.bounds) {
            for (case in ComparableHomeValueFixture.cases) {
                val cv = case.expectedCv
                val expectedMember = when {
                    min == null && max == null -> true
                    cv == null -> false
                    else -> (min == null || cv >= min) && (max == null || cv <= max)
                }
                assertEquals(
                    "${case.label} min=$min max=$max",
                    expectedMember,
                    ComparableHomeValue.withinBounds(case.expense, case.home, min, max)
                )
            }
        }
    }
}
