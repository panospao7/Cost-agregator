package com.yourname.expensetracker.domain.core.money

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Helper-level tests for [roundSnapshot] (INV-16, AE-23, AE-24 helper case). */
class SnapshotRoundingTest {

    private fun code(value: String): CurrencyCode =
        requireNotNull(CurrencyCode.parse(value)) { "test currency code must parse" }

    private val eur = code("EUR")
    private val jpy = code("JPY")
    private val bhd = code("BHD")
    private val xxx = code("XXX")

    private fun assertRounded(expected: Double, value: Double, home: CurrencyCode) {
        assertEquals(
            "roundSnapshot($value, ${home.code})",
            SnapshotRounding.Rounded(expected),
            roundSnapshot(value, home)
        )
    }

    private fun assertUnavailable(
        reason: SnapshotRoundingUnavailableReason,
        value: Double,
        home: CurrencyCode
    ) {
        assertEquals(
            "roundSnapshot($value, ${home.code})",
            SnapshotRounding.Unavailable(reason),
            roundSnapshot(value, home)
        )
    }

    // ── HALF_EVEN on the decimal string of the double ───────────────────────

    @Test
    fun `1_025 rounds to 1_02 in EUR`() = assertRounded(1.02, 1.025, eur)

    @Test
    fun `0_135 rounds to 0_14 in EUR`() = assertRounded(0.14, 0.135, eur)

    @Test
    fun `10_125 rounds to 10_12 in EUR`() = assertRounded(10.12, 10.125, eur)

    @Test
    fun `2_03 times 0_5 rounds to 1_02 in EUR`() {
        // AE-23 / AE-24 helper case: the product prints 1.015, HALF_EVEN gives 1.02.
        assertRounded(1.02, 2.03 * 0.5, eur)
    }

    @Test
    fun `rate is applied unrounded and only the result is rounded`() {
        assertRounded(9.23, 10.0 * 0.923456, eur)
    }

    // ── Zero-digit currency (JPY) ───────────────────────────────────────────

    @Test
    fun `JPY 3024_74 rounds to 3025`() = assertRounded(3025.0, 3024.74, jpy)

    @Test
    fun `JPY tie 3024_5 rounds to even 3024`() = assertRounded(3024.0, 3024.5, jpy)

    @Test
    fun `JPY tie 3025_5 rounds to even 3026`() = assertRounded(3026.0, 3025.5, jpy)

    // ── One-minor-unit floor ────────────────────────────────────────────────

    @Test
    fun `0_0025 floors to one minor unit 0_01 in EUR`() = assertRounded(0.01, 0.0025, eur)

    @Test
    fun `exactly 0_005 floors to 0_01 in EUR`() = assertRounded(0.01, 0.005, eur)

    @Test
    fun `0_0061 rounds to 0_01 in EUR by ordinary rounding`() = assertRounded(0.01, 0.0061, eur)

    @Test
    fun `a tiny positive JPY value floors to one yen`() = assertRounded(1.0, 0.3, jpy)

    // ── Three-digit currency ────────────────────────────────────────────────

    @Test
    fun `BHD 1_2345 rounds to 1_234`() = assertRounded(1.234, 1.2345, bhd)

    @Test
    fun `BHD 0_0004 floors to 0_001`() = assertRounded(0.001, 0.0004, bhd)

    // ── Unavailable outcomes ────────────────────────────────────────────────

    @Test
    fun `NaN is unavailable as non-finite or non-positive`() =
        assertUnavailable(SnapshotRoundingUnavailableReason.NON_FINITE_OR_NON_POSITIVE, Double.NaN, eur)

    @Test
    fun `positive infinity is unavailable as non-finite or non-positive`() =
        assertUnavailable(SnapshotRoundingUnavailableReason.NON_FINITE_OR_NON_POSITIVE, Double.POSITIVE_INFINITY, eur)

    @Test
    fun `negative infinity is unavailable as non-finite or non-positive`() =
        assertUnavailable(SnapshotRoundingUnavailableReason.NON_FINITE_OR_NON_POSITIVE, Double.NEGATIVE_INFINITY, eur)

    @Test
    fun `zero is unavailable as non-finite or non-positive`() =
        assertUnavailable(SnapshotRoundingUnavailableReason.NON_FINITE_OR_NON_POSITIVE, 0.0, eur)

    @Test
    fun `negative zero is unavailable as non-finite or non-positive`() =
        assertUnavailable(SnapshotRoundingUnavailableReason.NON_FINITE_OR_NON_POSITIVE, -0.0, eur)

    @Test
    fun `negative value is unavailable as non-finite or non-positive`() =
        assertUnavailable(SnapshotRoundingUnavailableReason.NON_FINITE_OR_NON_POSITIVE, -1.0, eur)

    @Test
    fun `currency without digits is unavailable`() =
        assertUnavailable(SnapshotRoundingUnavailableReason.DIGITS_UNAVAILABLE, 1.0, xxx)

    @Test
    fun `value check precedes the digits check`() =
        assertUnavailable(SnapshotRoundingUnavailableReason.NON_FINITE_OR_NON_POSITIVE, 0.0, xxx)

    // ── Contract: result is positive and exactly representable ──────────────

    @Test
    fun `rounded results are positive with scale within the currency digits`() {
        val samples = listOf(0.0001, 0.004, 0.005, 1.025, 2.5, 10.125, 3024.5, 123456.789)
        for (home in listOf(eur, jpy, bhd)) {
            val digits = requireNotNull(CurrencyFractionDigits.of(home))
            for (value in samples) {
                val result = roundSnapshot(value, home)
                assertTrue("$value ${home.code} should be Rounded", result is SnapshotRounding.Rounded)
                val amount = (result as SnapshotRounding.Rounded).amount
                assertTrue("$value ${home.code} must be positive", amount > 0.0)
                assertTrue(
                    "$value ${home.code} must have scale <= $digits",
                    BigDecimal.valueOf(amount).stripTrailingZeros().scale() <= digits
                )
            }
        }
    }
}
