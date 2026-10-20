package com.yourname.expensetracker.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Tests for StatisticsUtils.calculateStdDev (sample standard deviation, Bessel's correction N-1).
 *
 * Every expected value below is derived by hand from the definition
 * sqrt(sum((x - mean)^2) / (n - 1)); the derivation is written next to each row.
 * Finite rows use a tight relative tolerance (1e-12) unless the row states why an
 * absolute delta is needed (inputs that are not exactly representable in binary).
 */
class StatisticsUtilsTest {

    private data class Row(
        val name: String,
        val values: List<Double>,
        val expected: Double,
        val delta: Double = abs(expected) * 1e-12
    )

    private fun assertRows(rows: List<Row>) {
        rows.forEach { row ->
            assertEquals(
                "StdDev for row '${row.name}'",
                row.expected,
                StatisticsUtils.calculateStdDev(row.values),
                row.delta
            )
        }
    }

    // ============================================================================
    // Fewer than two values and uniform values: exactly zero
    // ============================================================================

    @Test
    fun `zero stddev rows - fewer than two values and uniform values`() {
        // Uniform rows: the mean is exactly representable, so every deviation is exactly 0.0.
        val rows = listOf(
            Row("empty list", emptyList(), 0.0, 0.0),
            Row("single value", listOf(42.0), 0.0, 0.0),
            Row("100 x 50.0", List(100) { 50.0 }, 0.0, 0.0),
            Row("1000 x 1e6", List(1000) { 1000000.0 }, 0.0, 0.0),
            Row("1000 x 42.0", List(1000) { 42.0 }, 0.0, 0.0),
            Row("4 x 1.0", listOf(1.0, 1.0, 1.0, 1.0), 0.0, 0.0)
        )
        assertRows(rows)
    }

    // ============================================================================
    // Hand-derived small tables
    // ============================================================================

    @Test
    fun `sample stddev - small hand-derived rows`() {
        val rows = listOf(
            // mean 3; sum sq = 4+1+0+1+4 = 10; 10/4 = 2.5
            Row("1..5", listOf(1.0, 2.0, 3.0, 4.0, 5.0), sqrt(2.5)),
            // mean 15; sum sq = 25+25 = 50; 50/1
            Row("10,20", listOf(10.0, 20.0), sqrt(50.0)),
            // mean 5; sum sq = 50; 50/1
            Row("0,10", listOf(0.0, 10.0), sqrt(50.0)),
            // mean 0; sum sq = 25+25 = 50; 50/1
            Row("-5,5", listOf(-5.0, 5.0), sqrt(50.0)),
            // mean 1.5; sum sq = 0.25+0.25 = 0.5; 0.5/1 (N=2 is the minimum valid sample)
            Row("1,2 (minimum valid sample)", listOf(1.0, 2.0), sqrt(0.5)),
            // mean 5; deviations -3,-1,-1,-1,0,0,2,4; sum sq = 9+1+1+1+0+0+4+16 = 32; 32/7
            Row("2,4,4,4,5,5,7,9", listOf(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0), sqrt(32.0 / 7.0)),
            // mean 0; sum sq = 25+9+1+1+9+25 = 70; 70/5 = 14
            Row("negatives and positives", listOf(-5.0, -3.0, -1.0, 1.0, 3.0, 5.0), sqrt(14.0)),
            // mean 0; sum sq = 100+25+0+25+100 = 250; 250/4 = 62.5
            Row("-10,-5,0,5,10", listOf(-10.0, -5.0, 0.0, 5.0, 10.0), sqrt(62.5)),
            // mean 0; sum sq = 1e18 + 0 + 1e18 = 2e18 (exact in binary); 2e18/2 = 1e18; sqrt = 1e9
            Row("-1e9,0,1e9", listOf(-1e9, 0.0, 1e9), 1e9)
        )
        assertRows(rows)
    }

    @Test
    fun `sample stddev uses N minus 1 not N`() {
        val values = listOf(1.0, 2.0, 3.0)

        val stdDev = StatisticsUtils.calculateStdDev(values)

        // mean 2; sum sq = 1+0+1 = 2. Sample: sqrt(2/2) = 1.0. Population would be sqrt(2/3).
        assertEquals("Should use sample stddev (N-1)", 1.0, stdDev, 1e-12)
        assertNotEquals(
            "Must not equal population stddev sqrt(2/3)",
            sqrt(2.0 / 3.0),
            stdDev,
            1e-3
        )
    }

    // ============================================================================
    // Larger datasets with closed forms
    // ============================================================================

    @Test
    fun `sample stddev - larger datasets match closed forms`() {
        // For 1..n (step 1): sum sq = n(n^2-1)/12, so sample variance = n(n+1)/12.
        fun arithmeticStdDev(n: Int): Double = sqrt(n.toDouble() * (n + 1).toDouble() / 12.0)

        val rows = listOf(
            // mean 5; every deviation is +/-5 so each square is 25; 100*25 = 2500; 2500/99
            Row("100 x alternating 0/10", List(100) { if (it % 2 == 0) 0.0 else 10.0 }, sqrt(2500.0 / 99.0)),
            // shift of the row above by +10: 50x10 + 50x20, mean 15, 100*25 = 2500; 2500/99
            Row("50 x 10 + 50 x 20 (bimodal)", List(50) { 10.0 } + List(50) { 20.0 }, sqrt(2500.0 / 99.0)),
            // mean = (98*10 + 100 + 0)/100 = 10.8; 98*0.8^2 = 62.72; 89.2^2 = 7956.64; 10.8^2 = 116.64;
            // sum sq = 8136.0; 8136/99
            Row("98 x 10 + outliers 100 and 0", List(98) { 10.0 } + listOf(100.0, 0.0), sqrt(8136.0 / 99.0)),
            Row("1..100", (1..100).map { it.toDouble() }, arithmeticStdDev(100)),
            Row("1..1000", (1..1000).map { it.toDouble() }, arithmeticStdDev(1000)),
            Row("1..10000", (1..10000).map { it.toDouble() }, arithmeticStdDev(10000)),
            // scale by 1000 of 1..1000
            Row("1000, 2000 .. 1000000", (1..1000).map { it * 1000.0 }, 1000.0 * arithmeticStdDev(1000)),
            // values 2^0..2^10: sum = 2047, n = 11, sum of squares = (4^11-1)/3 = 1398101;
            // sum sq about mean = 1398101 - 2047^2/11 = (11*1398101 - 4190209)/11 = 11188902/11; divide by 10
            Row("geometric 2^0..2^10", (0..10).map { Math.pow(2.0, it.toDouble()) }, sqrt(11188902.0 / 110.0))
        )
        assertRows(rows)
    }

    // ============================================================================
    // Precision rows (inputs not exactly representable -> explicit absolute deltas)
    // ============================================================================

    @Test
    fun `sample stddev - precision rows with inexact decimal inputs`() {
        // Equally spaced values with step d and n points have sample stddev d * sqrt(n(n+1)/12).
        val rows = listOf(
            // d = 1.111, n = 5 -> 1.111 * sqrt(2.5); input rounding error ~1e-16
            Row(
                "1.111 .. 5.555",
                listOf(1.111, 2.222, 3.333, 4.444, 5.555),
                1.111 * sqrt(2.5),
                1e-12
            ),
            // d = 0.001, n = 4 -> 0.001 * sqrt(5/3). At 1e6 one ulp is ~1.2e-10, so each
            // input is off by up to ~6e-11; the resulting stddev error is far below 1e-9.
            Row(
                "1e6 + 0, 0.001, 0.002, 0.003",
                listOf(1000000.0, 1000000.0 + 0.001, 1000000.0 + 0.002, 1000000.0 + 0.003),
                0.001 * sqrt(5.0 / 3.0),
                1e-9
            ),
            // d = 1e-7, n = 4 -> 1e-7 * sqrt(5/3); input rounding error ~1e-16
            Row(
                "1 + 0, 1e-7, 2e-7, 3e-7",
                listOf(1.0, 1.0000001, 1.0000002, 1.0000003),
                1e-7 * sqrt(5.0 / 3.0),
                1e-14
            ),
            // deviations are +e, 0, -e in the limit: sum sq = 2e^2, /2 -> stddev e = 1e-10;
            // input rounding error ~1e-16 -> delta 1e-14 keeps a 1e-4 relative bound
            Row(
                "1, 1 + 1e-10, 1 - 1e-10",
                listOf(1.0, 1.0 + 1e-10, 1.0 - 1e-10),
                1e-10,
                1e-14
            )
        )
        assertRows(rows)
    }

    // ============================================================================
    // IEEE-754 edge cases (derived from: mean = sum / n; sumSq = sum((x-mean)^2); sqrt(sumSq / (n-1)))
    // ============================================================================

    @Test
    fun `max double values overflow to positive infinity`() {
        // sum = MAX + 0.9*MAX = 1.9*MAX overflows to +Infinity (and stays +Infinity), so mean = +Infinity.
        // Each deviation is x - Infinity = -Infinity; (-Infinity)*(-Infinity) = +Infinity.
        // sumSq = +Infinity; sqrt(+Infinity / 2) = +Infinity.
        val values = listOf(Double.MAX_VALUE, Double.MAX_VALUE * 0.9, Double.MAX_VALUE * 0.8)

        val stdDev = StatisticsUtils.calculateStdDev(values)

        assertEquals(
            "Overflowing sum must yield exactly +Infinity",
            Double.POSITIVE_INFINITY,
            stdDev,
            0.0
        )
    }

    @Test
    fun `min double values underflow to exactly zero`() {
        // Values are 1, 2, 3 times MIN_VALUE (subnormals, exactly representable): sum = 6*MIN, mean = 2*MIN
        // (exact). Deviations are -MIN, 0, +MIN; MIN*MIN underflows to 0.0, so sumSq = 0.0 and the
        // result is sqrt(0.0 / 2) = 0.0.
        val values = listOf(Double.MIN_VALUE, Double.MIN_VALUE * 2, Double.MIN_VALUE * 3)

        val stdDev = StatisticsUtils.calculateStdDev(values)

        assertEquals("Underflowing squares must yield exactly 0.0", 0.0, stdDev, 0.0)
    }

    @Test
    fun `infinity input yields NaN`() {
        // sum = +Infinity, mean = +Infinity. Deviations: 1 - Inf = -Inf (square +Inf);
        // Inf - Inf = NaN (square NaN); 3 - Inf = -Inf (square +Inf). sumSq = Inf + NaN + Inf = NaN;
        // sqrt(NaN / 2) = NaN.
        val values = listOf(1.0, Double.POSITIVE_INFINITY, 3.0)

        val stdDev = StatisticsUtils.calculateStdDev(values)

        assertTrue("Infinity input must propagate as NaN, was $stdDev", stdDev.isNaN())
    }

    @Test
    fun `NaN input yields NaN`() {
        // sum is NaN, so mean is NaN, every deviation is NaN, sumSq is NaN, and sqrt(NaN / 2) = NaN.
        val values = listOf(1.0, Double.NaN, 3.0)

        val stdDev = StatisticsUtils.calculateStdDev(values)

        assertTrue("NaN input must propagate as NaN, was $stdDev", stdDev.isNaN())
    }

    // ============================================================================
    // Invariants
    // ============================================================================

    @Test
    fun `stddev is invariant to shift`() {
        val values = listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        val shifted = values.map { it + 100.0 }

        // Both equal sqrt(2.5); the shifted values are exact so the deviations are exact too.
        assertEquals(
            "Unshifted stddev",
            sqrt(2.5),
            StatisticsUtils.calculateStdDev(values),
            sqrt(2.5) * 1e-12
        )
        assertEquals(
            "StdDev should be invariant to shift by +100",
            sqrt(2.5),
            StatisticsUtils.calculateStdDev(shifted),
            sqrt(2.5) * 1e-12
        )
    }

    @Test
    fun `stddev scales linearly`() {
        val values = listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        val doubled = values.map { it * 2.0 }

        // Doubled values: mean 6; sum sq = 16+4+0+4+16 = 40; 40/4 = 10 -> sqrt(10) = 2 * sqrt(2.5).
        assertEquals(
            "StdDev of doubled values should be sqrt(10)",
            sqrt(10.0),
            StatisticsUtils.calculateStdDev(doubled),
            sqrt(10.0) * 1e-12
        )
        assertEquals(
            "StdDev should scale linearly (x2)",
            2.0 * StatisticsUtils.calculateStdDev(values),
            StatisticsUtils.calculateStdDev(doubled),
            sqrt(10.0) * 1e-12
        )
    }

    @Test
    fun `stddev is order independent`() {
        // Fixed permutations (deterministic). mean = 86/5 = 17.2; deviations -16.2, -12.2, -7.2, 2.8, 32.8;
        // squares 262.44 + 148.84 + 51.84 + 7.84 + 1075.84 = 1546.8; 1546.8/4 = 386.7.
        val ascending = listOf(1.0, 5.0, 10.0, 20.0, 50.0)
        val permuted = listOf(20.0, 1.0, 50.0, 10.0, 5.0)
        val descending = ascending.reversed()
        val expected = sqrt(386.7)

        assertEquals("Ascending order", expected, StatisticsUtils.calculateStdDev(ascending), expected * 1e-12)
        assertEquals("Permuted order", expected, StatisticsUtils.calculateStdDev(permuted), expected * 1e-12)
        assertEquals("Descending order", expected, StatisticsUtils.calculateStdDev(descending), expected * 1e-12)
    }
}
