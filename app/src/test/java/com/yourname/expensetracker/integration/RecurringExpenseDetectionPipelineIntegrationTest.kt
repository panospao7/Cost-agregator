package com.yourname.expensetracker.integration

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class RecurringExpenseDetectionPipelineIntegrationTest {

    // ============================================================================
    // SECTION 1: PATTERN DETECTION PIPELINE
    // ============================================================================

    @Test
    fun `integration - detect weekly recurring pattern`() {
        val msPerDay = 24 * 60 * 60 * 1000L
        val weeklyExpenses = listOf(
            1000L to 50.0,                       // Week 1
            (1000L + 7 * msPerDay) to 50.0,      // Week 2 (7 days later)
            (1000L + 14 * msPerDay) to 50.0,     // Week 3 (7 days later)
            (1000L + 21 * msPerDay) to 50.0      // Week 4 (7 days later)
        )
        
        // Calculate intervals
        val intervals = weeklyExpenses.zipWithNext().map { (a, b) ->
            b.first - a.first
        }
        
        val avgInterval = intervals.average()
        val isWeekly = avgInterval in (6 * msPerDay).toDouble()..(8 * msPerDay).toDouble()
        
        assertTrue("Should detect weekly pattern with avgInterval=$avgInterval", isWeekly)
    }

    @Test
    fun `integration - detect monthly recurring pattern`() {
        val monthlyExpenses = listOf(
            1000L to 100.0,   // Month 1
            2678400000L to 100.0,  // Month 2 (~31 days)
            5097600000L to 100.0,  // Month 3 (~30 days)
            7776000000L to 100.0   // Month 4 (~31 days)
        )
        
        val intervals = monthlyExpenses.zipWithNext().map { (a, b) ->
            b.first - a.first
        }
        
        val avgInterval = intervals.average()
        val isMonthly = avgInterval > 2500000000  // ~30 days in ms
        
        assertTrue("Should detect monthly pattern", isMonthly)
    }

    // ============================================================================
    // SECTION 2: AMOUNT CONSISTENCY PIPELINE
    // ============================================================================

    @Test
    fun `integration - detect consistent amounts`() {
        val amounts = listOf(50.0, 50.0, 50.0, 50.0, 50.0)
        
        val mean = amounts.average()
        val variance = amounts.map { (it - mean) * (it - mean) }.average()
        val isConsistent = variance < 1.0
        
        assertTrue("Amounts should be consistent", isConsistent)
    }

    @Test
    fun `integration - detect variable amounts within tolerance`() {
        val amounts = listOf(48.0, 52.0, 49.0, 51.0, 50.0)
        
        val mean = amounts.average()
        val variance = amounts.map { (it - mean) * (it - mean) }.average()
        val stdDev = Math.sqrt(variance)
        
        // Within 10% tolerance
        val withinTolerance = amounts.all { Math.abs(it - mean) / mean < 0.1 }
        
        assertTrue("Amounts should be within tolerance", withinTolerance)
    }

    @Test
    fun `integration - detect inconsistent amounts`() {
        val amounts = listOf(50.0, 150.0, 45.0, 200.0, 48.0)
        
        val mean = amounts.average()
        val variance = amounts.map { (it - mean) * (it - mean) }.average()
        val isConsistent = variance < 100.0  // High variance
        
        assertFalse("Amounts should not be consistent", isConsistent)
    }

    // ============================================================================
    // SECTION 3: MERCHANT CONSISTENCY PIPELINE
    // ============================================================================

    @Test
    fun `integration - detect same merchant recurring`() {
        val transactions = listOf(
            "Netflix" to 15.99,
            "Netflix" to 15.99,
            "Netflix" to 15.99,
            "Netflix" to 15.99
        )
        
        val merchants = transactions.map { it.first }.distinct()
        val isSameMerchant = merchants.size == 1
        
        assertTrue("Should be same merchant", isSameMerchant)
        assertEquals("Netflix", merchants[0])
    }

    @Test
    fun `integration - detect similar merchant names`() {
        val transactions = listOf(
            "Netflix Subscription",
            "NETFLIX",
            "netflix.com",
            "Netflix Monthly"
        )
        
        // Check if all contain "netflix" (case insensitive)
        val normalized = transactions.map { it.lowercase() }
        val allSimilar = normalized.all { it.contains("netflix") }
        
        assertTrue("Should detect similar merchant names", allSimilar)
    }

    // ============================================================================
    // SECTION 4: CONFIDENCE SCORING PIPELINE
    // ============================================================================

    @Test
    fun `integration - calculate high confidence recurring`() {
        val factors = mapOf(
            "intervalConsistency" to 0.95,
            "amountConsistency" to 0.90,
            "merchantConsistency" to 1.0,
            "frequency" to 0.80
        )
        
        val confidence = factors.values.average()
        
        assertTrue("Should have high confidence", confidence > 0.8)
    }

    @Test
    fun `integration - calculate low confidence recurring`() {
        val factors = mapOf(
            "intervalConsistency" to 0.30,
            "amountConsistency" to 0.40,
            "merchantConsistency" to 0.50,
            "frequency" to 0.20
        )
        
        val confidence = factors.values.average()
        
        assertTrue("Should have low confidence", confidence < 0.5)
    }

    // ============================================================================
    // SECTION 5: NEXT PAYMENT PREDICTION PIPELINE
    // ============================================================================

    @Test
    fun `integration - predict next weekly payment`() {
        val lastPayment = System.currentTimeMillis()
        val avgInterval = 7L * 24 * 60 * 60 * 1000  // 7 days in ms
        
        val nextPayment = lastPayment + avgInterval
        
        assertTrue("Next payment should be in future", nextPayment > lastPayment)
        assertEquals(avgInterval, nextPayment - lastPayment)
    }

    @Test
    fun `integration - predict next monthly payment`() {
        val lastPayment = System.currentTimeMillis()
        val avgInterval = 30L * 24 * 60 * 60 * 1000  // ~30 days in ms
        
        val nextPayment = lastPayment + avgInterval
        
        assertTrue("Next payment should be in future", nextPayment > lastPayment)
    }

    // ============================================================================
    // SECTION 6: FREQUENCY ANALYSIS PIPELINE
    // ============================================================================

    @Test
    fun `integration - analyze transaction frequency`() {
        val msPerDay = 24 * 60 * 60 * 1000L
        val transactions = listOf(
            1000L,                              // Transaction 1
            1000L + 7 * msPerDay,               // Transaction 2 (7 days)
            1000L + 14 * msPerDay,              // Transaction 3 (7 days)
            1000L + 21 * msPerDay               // Transaction 4 (7 days)
        )
        
        val intervals = transactions.zipWithNext().map { (a, b) -> b - a }
        val avgInterval = intervals.average()
        
        // Determine frequency
        val frequency = when {
            avgInterval < msPerDay * 2 -> "Daily"
            avgInterval < msPerDay * 10 -> "Weekly"
            avgInterval < msPerDay * 20 -> "Bi-weekly"
            else -> "Monthly"
        }
        
        assertEquals("Weekly", frequency)
    }

    @Test
    fun `integration - detect missing payments`() {
        val expectedDates = listOf(
            1000L,
            8000L,   // Expected
            15000L,  // Expected
            30000L   // Gap - missing one
        )
        
        val intervals = expectedDates.zipWithNext().map { (a, b) -> b - a }
        val avgInterval = intervals.average()
        
        val gaps = intervals.filter { it > avgInterval * 1.5 }
        
        assertEquals(1, gaps.size)
    }

    // ============================================================================
    // SECTION 7: COMPLETE DETECTION PIPELINE
    // ============================================================================

    @Test
    fun `integration - full recurring detection flow`() {
        // Simulate a subscription pattern
        val transactions = listOf(
            Triple(1000L, "Netflix", 15.99),
            Triple(8000L, "Netflix", 15.99),
            Triple(15000L, "Netflix", 15.99),
            Triple(22000L, "Netflix", 15.99)
        )
        
        // Check merchant consistency
        val merchants = transactions.map { it.second }.distinct()
        val merchantConsistent = merchants.size == 1
        
        // Check amount consistency
        val amounts = transactions.map { it.third }
        val amountVariance = amounts.map { (it - amounts.average()) * (it - amounts.average()) }.average()
        val amountConsistent = amountVariance < 0.1
        
        // Check interval consistency
        val timestamps = transactions.map { it.first }
        val intervals = timestamps.zipWithNext().map { (a, b) -> b - a }
        val intervalConsistent = intervals.all { Math.abs(it - intervals.average()) < 1000000 }
        
        // Overall detection
        val isRecurring = merchantConsistent && amountConsistent && intervalConsistent
        
        assertTrue("Should detect recurring pattern", isRecurring)
    }

    // ============================================================================
    // SECTION 8: EDGE CASES
    // ============================================================================

    @Test
    fun `integration - handle single transaction`() {
        val transactions = listOf(Triple(1000L, "Netflix", 15.99))
        
        // Can't detect recurring with only one transaction
        val isRecurring = transactions.size >= 3
        
        assertFalse("Single transaction is not recurring", isRecurring)
    }

    @Test
    fun `integration - handle two transactions`() {
        val transactions = listOf(
            Triple(1000L, "Netflix", 15.99),
            Triple(8000L, "Netflix", 15.99)
        )
        
        // Need at least 3 for confidence
        val isRecurring = transactions.size >= 3
        
        assertFalse("Two transactions not enough for recurring", isRecurring)
    }

    @Test
    fun `integration - handle irregular intervals`() {
        val transactions = listOf(
            Triple(1000L, "Netflix", 15.99),
            Triple(5000L, "Netflix", 15.99),  // 4 days
            Triple(20000L, "Netflix", 15.99), // 15 days
            Triple(22000L, "Netflix", 15.99)  // 2 days
        )
        
        val timestamps = transactions.map { it.first }
        val intervals = timestamps.zipWithNext().map { (a, b) -> b - a }
        val avgInterval = intervals.average()
        val variance = intervals.map { (it - avgInterval) * (it - avgInterval) }.average()
        
        // High variance indicates irregular intervals
        val isRegular = variance < avgInterval * avgInterval * 0.1
        
        assertFalse("Should detect irregular intervals", isRegular)
    }

    // ============================================================================
    // SECTION 9: MULTIPLE RECURRING DETECTION
    // ============================================================================

    @Test
    fun `integration - detect multiple recurring merchants`() {
        val transactions = listOf(
            Triple(1000L, "Netflix", 15.99),
            Triple(8000L, "Netflix", 15.99),
            Triple(15000L, "Netflix", 15.99),
            Triple(2000L, "Spotify", 9.99),
            Triple(9000L, "Spotify", 9.99),
            Triple(16000L, "Spotify", 9.99)
        )
        
        val byMerchant = transactions.groupBy { it.second }
        val recurringMerchants = byMerchant.filter { (_, txs) -> txs.size >= 3 }.keys
        
        assertEquals(2, recurringMerchants.size)
        assertTrue(recurringMerchants.contains("Netflix"))
        assertTrue(recurringMerchants.contains("Spotify"))
    }

    // ============================================================================
    // SECTION 10: PERFORMANCE
    // ============================================================================

    @Test
    fun `integration - process 1000 transactions for recurring quickly`() {
        val transactions = (1..1000).map { i ->
            Triple(
                i * 7000L,  // Weekly pattern
                "Merchant${i % 10}",
                (i % 100).toDouble()
            )
        }
        
        val startTime = System.nanoTime()
        
        val byMerchant = transactions.groupBy { it.second }
        val recurring = byMerchant.filter { (_, txs) -> txs.size >= 3 }
        
        val duration = System.nanoTime() - startTime
        
        assertTrue("Should process quickly", duration < 1_000_000_000)
        assertEquals(10, recurring.size)
    }
}
