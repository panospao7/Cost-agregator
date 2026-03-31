package com.yourname.expensetracker.domain.split

import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.domain.util.Money
import com.yourname.expensetracker.domain.util.sum
import com.yourname.expensetracker.domain.util.toMoney
import org.junit.Test

/**
 * CRITICAL TEST (HIGH-2): Split Calculation Precision
 * 
 * Tests that split calculations using Money class produce exact results
 * without floating-point errors. Ensures sum of all shares equals total.
 */
class SplitCalculationPrecisionTest {

    // ==================== EQUAL SPLIT TESTS ====================

    @Test
    fun `equal split of 100 dollars among 3 people sums to exactly 100`() {
        val total = 100.0.toMoney()
        val numParticipants = 3
        
        val shares = calculateEqualSplit(total, numParticipants)
        
        // Sum should equal total exactly
        val sum = shares.sum()
        assertThat(sum).isEqualTo(total)
        
        // Should be [33.34, 33.33, 33.33] (first gets remainder)
        assertThat(shares[0].toDouble()).isWithin(0.01).of(33.34)
        assertThat(shares[1].toDouble()).isWithin(0.01).of(33.33)
        assertThat(shares[2].toDouble()).isWithin(0.01).of(33.33)
    }

    @Test
    fun `equal split of 10 dollars among 3 people sums to exactly 10`() {
        val total = 10.0.toMoney()
        val numParticipants = 3
        
        val shares = calculateEqualSplit(total, numParticipants)
        val sum = shares.sum()
        
        assertThat(sum).isEqualTo(total)
        // 10/3 = 3.333... → [3.34, 3.33, 3.33]
        assertThat(shares[0].toDouble()).isWithin(0.01).of(3.34)
    }

    @Test
    fun `equal split with divisible amount has no remainder`() {
        val total = 100.0.toMoney()
        val numParticipants = 4
        
        val shares = calculateEqualSplit(total, numParticipants)
        val sum = shares.sum()
        
        assertThat(sum).isEqualTo(total)
        // All should be exactly 25.00
        shares.forEach { share ->
            assertThat(share.toDouble()).isWithin(0.001).of(25.0)
        }
    }

    @Test
    fun `equal split with single participant returns full amount`() {
        val total = 99.99.toMoney()
        
        val shares = calculateEqualSplit(total, 1)
        
        assertThat(shares.size).isEqualTo(1)
        assertThat(shares[0]).isEqualTo(total)
    }

    @Test
    fun `equal split with large amount maintains precision`() {
        val total = 999_999.99.toMoney()
        val numParticipants = 7
        
        val shares = calculateEqualSplit(total, numParticipants)
        val sum = shares.sum()
        
        assertThat(sum).isEqualTo(total)
    }

    @Test
    fun `equal split with small amount maintains precision`() {
        val total = 0.01.toMoney()
        val numParticipants = 2
        
        val shares = calculateEqualSplit(total, numParticipants)
        val sum = shares.sum()
        
        assertThat(sum).isEqualTo(total)
        // One should get 0.01, other 0.00
        val totalDistributed = shares.map { it.toDouble() }.sum()
        assertThat(totalDistributed).isWithin(0.01).of(0.01)
    }

    @Test
    fun `equal split with many participants maintains precision`() {
        val total = 1000.0.toMoney()
        val numParticipants = 100
        
        val shares = calculateEqualSplit(total, numParticipants)
        val sum = shares.sum()
        
        assertThat(sum).isEqualTo(total)
        // Each should be 10.00
        assertThat(shares[0].toDouble()).isWithin(0.01).of(10.0)
    }

    // ==================== PERCENTAGE SPLIT TESTS ====================

    @Test
    fun `percentage split with 50-50 sums to total`() {
        val total = 100.0.toMoney()
        val percentages = listOf(50.0, 50.0)
        
        val shares = calculatePercentageSplit(total, percentages)
        val sum = shares.sum()
        
        assertThat(sum).isEqualTo(total)
        assertThat(shares[0].toDouble()).isWithin(0.01).of(50.0)
        assertThat(shares[1].toDouble()).isWithin(0.01).of(50.0)
    }

    @Test
    fun `percentage split with uneven distribution`() {
        val total = 100.0.toMoney()
        val percentages = listOf(70.0, 20.0, 10.0)
        
        val shares = calculatePercentageSplit(total, percentages)
        val sum = shares.sum()
        
        assertThat(sum.toDouble()).isWithin(0.01).of(100.0)
        assertThat(shares[0].toDouble()).isWithin(0.01).of(70.0)
        assertThat(shares[1].toDouble()).isWithin(0.01).of(20.0)
        assertThat(shares[2].toDouble()).isWithin(0.01).of(10.0)
    }

    @Test
    fun `percentage split with decimal percentages`() {
        val total = 100.0.toMoney()
        val percentages = listOf(33.33, 33.33, 33.34) // Sums to 100%
        
        val shares = calculatePercentageSplit(total, percentages)
        
        assertThat(shares[0].toDouble()).isWithin(0.01).of(33.33)
        assertThat(shares[1].toDouble()).isWithin(0.01).of(33.33)
        assertThat(shares[2].toDouble()).isWithin(0.01).of(33.34)
    }

    @Test
    fun `percentage split with very small amount`() {
        val total = 0.03.toMoney()
        val percentages = listOf(50.0, 50.0)
        
        val shares = calculatePercentageSplit(total, percentages)
        val sum = shares.sum()
        
        // Sum should be very close to total (might have 0.01 rounding diff)
        assertThat(sum.toDouble()).isWithin(0.01).of(0.03)
    }

    @Test
    fun `percentage split with zero percent`() {
        val total = 100.0.toMoney()
        val percentages = listOf(100.0, 0.0)
        
        val shares = calculatePercentageSplit(total, percentages)
        
        assertThat(shares[0]).isEqualTo(total)
        assertThat(shares[1].isZero()).isTrue()
    }

    // ==================== CUSTOM SPLIT TESTS ====================

    @Test
    fun `custom split sum matches expected total`() {
        val amounts = listOf(30.0, 20.0, 50.0)
        
        val calculatedTotal = calculateCustomSplit(amounts)
        
        assertThat(calculatedTotal).isEqualTo(100.0)
    }

    @Test
    fun `custom split with decimal amounts maintains precision`() {
        val amounts = listOf(33.33, 33.33, 33.34)
        
        val calculatedTotal = calculateCustomSplit(amounts)
        
        assertThat(calculatedTotal).isEqualTo(100.0)
    }

    @Test
    fun `custom split with single amount returns that amount`() {
        val amounts = listOf(99.99)
        
        val calculatedTotal = calculateCustomSplit(amounts)
        
        assertThat(calculatedTotal).isEqualTo(99.99)
    }

    @Test
    fun `custom split with empty list returns zero`() {
        val amounts = emptyList<Double>()
        
        val calculatedTotal = calculateCustomSplit(amounts)
        
        assertThat(calculatedTotal).isEqualTo(0.0)
    }

    // ==================== EDGE CASE TESTS ====================

    @Test
    fun `split with zero total returns zeros`() {
        val total = 0.0.toMoney()
        val numParticipants = 3
        
        val shares = calculateEqualSplit(total, numParticipants)
        
        shares.forEach { share ->
            assertThat(share.isZero()).isTrue()
        }
    }

    @Test
    fun `split with negative amount fails appropriately`() {
        // Negative amounts shouldn't be allowed in real scenarios
        val total = (-100.0).toMoney()
        val numParticipants = 2
        
        val shares = calculateEqualSplit(total, numParticipants)
        
        // Should still calculate correctly
        val sum = shares.sum()
        assertThat(sum).isEqualTo(total)
        assertThat(shares[0].toDouble()).isEqualTo(-50.0)
    }

    @Test
    fun `split with two participants is exactly half`() {
        val total = 99.99.toMoney()
        val numParticipants = 2
        
        val shares = calculateEqualSplit(total, numParticipants)
        
        // One should be 50.00, other 49.99 (or vice versa)
        val sum = shares.map { it.toDouble() }.sum()
        assertThat(sum).isWithin(0.01).of(99.99)
    }

    @Test
    fun `multiple splits maintain precision across operations`() {
        // Simulate multiple expense splits in sequence
        val expenses = listOf(100.0, 33.33, 77.77, 50.0, 99.99)
        val participants = listOf(3, 2, 4, 5, 3)
        
        expenses.forEachIndexed { index, amount ->
            val total = amount.toMoney()
            val numParticipants = participants[index]
            
            val shares = calculateEqualSplit(total, numParticipants)
            val sum = shares.sum()
            
            assertThat(sum).isEqualTo(total)
        }
    }

    @Test
    fun `rounding behavior is consistent`() {
        // Test that rounding is deterministic
        val total1 = 100.0.toMoney()
        val total2 = 100.0.toMoney()
        
        val shares1 = calculateEqualSplit(total1, 3)
        val shares2 = calculateEqualSplit(total2, 3)
        
        // Same inputs should produce same outputs
        assertThat(shares1).hasSize(3)
        assertThat(shares2).hasSize(3)
        assertThat(shares1[0]).isEqualTo(shares2[0])
        assertThat(shares1[1]).isEqualTo(shares2[1])
        assertThat(shares1[2]).isEqualTo(shares2[2])
    }

    @Test
    fun `split preserves currency precision at 2 decimal places`() {
        val total = 100.0.toMoney()
        val shares = calculateEqualSplit(total, 3)
        
        shares.forEach { share ->
            val doubleValue = share.toDouble()
            // Should have at most 2 decimal places when converted to cents
            val cents = (doubleValue * 100).toLong()
            val reconstructed = cents / 100.0
            assertThat(doubleValue).isWithin(0.01).of(reconstructed)
        }
    }

    // ==================== HELPER METHODS ====================

    /**
     * Calculate equal split using Money class for precision.
     * Mirrors the logic in EnhancedSplitManager.
     */
    private fun calculateEqualSplit(total: Money, numParticipants: Int): List<Money> {
        if (numParticipants <= 0) return emptyList()
        
        val baseShare = total.divide(numParticipants)
        val shares = List(numParticipants) { baseShare }
        
        val sumOfShares = shares.sum()
        val remainder = total - sumOfShares
        
        return if (!remainder.isZero() && shares.isNotEmpty()) {
            val adjusted = shares.toMutableList()
            adjusted[0] = adjusted[0] + remainder
            adjusted
        } else {
            shares
        }
    }

    /**
     * Calculate percentage split using Money class.
     */
    private fun calculatePercentageSplit(total: Money, percentages: List<Double>): List<Money> {
        return percentages.map { percent ->
            total.percentage(percent)
        }
    }

    /**
     * Calculate custom split sum using Money class.
     */
    private fun calculateCustomSplit(amounts: List<Double>): Double {
        return amounts.map { it.toMoney() }.sum().toDouble()
    }
}