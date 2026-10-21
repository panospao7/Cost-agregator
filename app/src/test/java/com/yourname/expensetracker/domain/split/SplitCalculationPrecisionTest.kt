package com.yourname.expensetracker.domain.split

import com.google.gson.Gson
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.SplitItemAssignmentDao
import com.yourname.expensetracker.data.database.dao.SplitTemplateDao
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

/**
 * HIGH-2: Split calculation precision, proven against the production
 * [EnhancedSplitManager] pure calculation functions.
 *
 * B3-4: this file previously asserted against test-local copies of the equal and
 * percentage split formulas (vacuous: the copy could drift from production
 * unnoticed). Every case now calls the production manager and asserts exact
 * integer cents, and shares are checked to sum to the total cents exactly.
 *
 * The manager's DAO/database collaborators are never touched by the pure
 * calculation functions; they are inert mocks (peripheral effects only).
 */
class SplitCalculationPrecisionTest {

    private val manager = EnhancedSplitManager(
        splitTemplateDao = mockk<SplitTemplateDao>(relaxed = true),
        splitItemAssignmentDao = mockk<SplitItemAssignmentDao>(relaxed = true),
        gson = Gson(),
        timeProvider = mockk<TimeProvider>(relaxed = true),
        database = mockk<AppDatabase>(relaxed = true),
        writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true)
    )

    // ==================== EQUAL SPLIT ====================

    @Test
    fun `equal split of 100 among 3 gives exact cents with remainder cent on first share`() {
        val shares = cents(manager.calculateEqualSplit(100.0, 3))

        assertEquals(listOf(3334L, 3333L, 3333L), shares)
        assertEquals(10_000L, shares.sum())
    }

    @Test
    fun `equal split of 10 among 3 gives exact cents with remainder cent on first share`() {
        val shares = cents(manager.calculateEqualSplit(10.0, 3))

        assertEquals(listOf(334L, 333L, 333L), shares)
        assertEquals(1_000L, shares.sum())
    }

    @Test
    fun `equal split with divisible amount has no remainder`() {
        val shares = cents(manager.calculateEqualSplit(100.0, 4))

        assertEquals(listOf(2500L, 2500L, 2500L, 2500L), shares)
    }

    @Test
    fun `equal split with single participant returns full amount`() {
        val shares = cents(manager.calculateEqualSplit(99.99, 1))

        assertEquals(listOf(9999L), shares)
    }

    @Test
    fun `equal split of large amount among 7 keeps exact cents and total`() {
        val shares = cents(manager.calculateEqualSplit(999_999.99, 7))

        // 999999.99 / 7 = 142857.1414...; base share 142857.14 x7 = 999999.98,
        // so the missing cent is added to the first share.
        assertEquals(
            listOf(14_285_715L, 14_285_714L, 14_285_714L, 14_285_714L, 14_285_714L, 14_285_714L, 14_285_714L),
            shares
        )
        assertEquals(99_999_999L, shares.sum())
    }

    @Test
    fun `equal split rounding up the base share removes a cent from first share`() {
        // 0.01 / 2 = 0.005 -> base share rounds HALF_UP to 0.01 (sum 0.02 > total),
        // so the remainder is negative and the first share absorbs it.
        val shares = cents(manager.calculateEqualSplit(0.01, 2))

        assertEquals(listOf(0L, 1L), shares)
        assertEquals(1L, shares.sum())
    }

    @Test
    fun `equal split of 99_99 among 2 conserves total with negative remainder`() {
        // 99.99 / 2 = 49.995 -> base 50.00 (HALF_UP); sum 100.00; first share -0.01.
        val shares = cents(manager.calculateEqualSplit(99.99, 2))

        assertEquals(listOf(4999L, 5000L), shares)
        assertEquals(9_999L, shares.sum())
    }

    @Test
    fun `equal split among 100 participants gives exact 10 each`() {
        val shares = cents(manager.calculateEqualSplit(1000.0, 100))

        assertEquals(100, shares.size)
        assertEquals(List(100) { 1_000L }, shares)
    }

    @Test
    fun `equal split of zero total returns zero shares`() {
        val shares = cents(manager.calculateEqualSplit(0.0, 3))

        assertEquals(listOf(0L, 0L, 0L), shares)
    }

    @Test
    fun `equal split of negative total divides exactly and conserves sum`() {
        val shares = cents(manager.calculateEqualSplit(-100.0, 2))

        assertEquals(listOf(-5000L, -5000L), shares)
        assertEquals(-10_000L, shares.sum())
    }

    @Test
    fun `equal split conserves total cents across a sequence of amounts`() {
        // (total, participants, exact expected cents)
        val table = listOf(
            Triple(100.0, 3, listOf(3334L, 3333L, 3333L)),
            Triple(33.33, 2, listOf(1666L, 1667L)),
            Triple(77.77, 4, listOf(1945L, 1944L, 1944L, 1944L)),
            Triple(50.0, 5, listOf(1000L, 1000L, 1000L, 1000L, 1000L)),
            Triple(99.99, 3, listOf(3333L, 3333L, 3333L))
        )

        table.forEach { (total, participants, expected) ->
            val shares = cents(manager.calculateEqualSplit(total, participants))

            assertEquals("shares for $total / $participants", expected, shares)
            assertEquals("sum for $total / $participants", totalCents(total), shares.sum())
        }
    }

    // ==================== PERCENTAGE SPLIT ====================

    @Test
    fun `percentage split with 50-50 sums to total`() {
        val shares = cents(manager.calculatePercentageSplit(100.0, listOf(50.0, 50.0)))

        assertEquals(listOf(5000L, 5000L), shares)
    }

    @Test
    fun `percentage split with uneven distribution is exact and conserves total`() {
        val shares = cents(manager.calculatePercentageSplit(100.0, listOf(70.0, 20.0, 10.0)))

        assertEquals(listOf(7000L, 2000L, 1000L), shares)
        assertEquals(10_000L, shares.sum())
    }

    @Test
    fun `percentage split with decimal percentages is exact and conserves total`() {
        val shares = cents(manager.calculatePercentageSplit(100.0, listOf(33.33, 33.33, 33.34)))

        assertEquals(listOf(3333L, 3333L, 3334L), shares)
        assertEquals(10_000L, shares.sum())
    }

    @Test
    fun `percentage split of 0_03 at 50-50 keeps sum at total not 0_04`() {
        // Independent per-share HALF_UP rounding would give 0.02 + 0.02 = 0.04.
        val shares = cents(manager.calculatePercentageSplit(0.03, listOf(50.0, 50.0)))

        assertEquals(listOf(2L, 1L), shares)
        assertEquals(3L, shares.sum())
    }

    @Test
    fun `percentage split allocates leftover cents by largest fractional part`() {
        // Naive HALF_UP per share would give 0.02 + 0.02 + 0.02 = 0.06.
        // Raw cents 1.6665 / 1.6665 / 1.667 -> floor 1/1/1, two leftover cents go to
        // the largest fractions: third share first, then first share (tie by order).
        val shares = cents(manager.calculatePercentageSplit(0.05, listOf(33.33, 33.33, 33.34)))

        assertEquals(listOf(2L, 1L, 2L), shares)
        assertEquals(5L, shares.sum())
    }

    @Test
    fun `percentage split of one cent at 50-50 gives the cent to the first share`() {
        val shares = cents(manager.calculatePercentageSplit(0.01, listOf(50.0, 50.0)))

        assertEquals(listOf(1L, 0L), shares)
    }

    @Test
    fun `percentage split with zero percent share gives that share nothing`() {
        val shares = cents(manager.calculatePercentageSplit(100.0, listOf(100.0, 0.0)))

        assertEquals(listOf(10_000L, 0L), shares)
    }

    @Test
    fun `percentage split with all zero percentages returns zero shares`() {
        val shares = cents(manager.calculatePercentageSplit(100.0, listOf(0.0, 0.0)))

        assertEquals(listOf(0L, 0L), shares)
    }

    @Test
    fun `percentage split with no percentages returns empty list`() {
        assertEquals(emptyList<Double>(), manager.calculatePercentageSplit(100.0, emptyList()))
    }

    // ==================== CUSTOM SPLIT ====================

    @Test
    fun `custom split sum matches expected total`() {
        assertEquals(100.0, manager.calculateCustomSplit(listOf(30.0, 20.0, 50.0)), 0.0)
    }

    @Test
    fun `custom split with decimal amounts maintains precision`() {
        assertEquals(100.0, manager.calculateCustomSplit(listOf(33.33, 33.33, 33.34)), 0.0)
    }

    @Test
    fun `custom split avoids binary floating point drift`() {
        // 0.1 + 0.2 == 0.30000000000000004 in raw Double arithmetic.
        assertEquals(0.3, manager.calculateCustomSplit(listOf(0.1, 0.2)), 0.0)
    }

    @Test
    fun `custom split with single amount returns that amount`() {
        assertEquals(99.99, manager.calculateCustomSplit(listOf(99.99)), 0.0)
    }

    @Test
    fun `custom split with empty list returns zero`() {
        assertEquals(0.0, manager.calculateCustomSplit(emptyList()), 0.0)
    }

    // ==================== HELPERS (unit conversion only, no split logic) ====================

    /** Converts display doubles to exact integer cents; fails if a value has sub-cent precision. */
    private fun cents(values: List<Double>): List<Long> =
        values.map { toExactCents(it) }

    private fun totalCents(total: Double): Long = toExactCents(total)

    private fun toExactCents(value: Double): Long =
        BigDecimal.valueOf(value).movePointRight(2).longValueExact()
}
