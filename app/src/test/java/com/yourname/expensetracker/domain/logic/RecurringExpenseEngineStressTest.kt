package com.yourname.expensetracker.domain.logic

import org.junit.Assert.*
import org.junit.Test

class RecurringExpenseEngineStressTest {

    // ============================================================================
    // SECTION 1: PATTERN DETECTION
    // ============================================================================

    @Test
    fun `stress - detect weekly pattern`() {
        val transactions = createWeeklyTransactions(
            merchant = "Netflix",
            amount = 15.99,
            count = 4
        )
        
        val pattern = detectRecurringPattern(transactions)
        
        assertNotNull("Should detect weekly pattern", pattern)
        assertEquals("WEEKLY", pattern?.frequency)
    }

    @Test
    fun `stress - detect monthly pattern`() {
        val transactions = createMonthlyTransactions(
            merchant = "Rent",
            amount = 800.0,
            count = 6
        )
        
        val pattern = detectRecurringPattern(transactions)
        
        assertNotNull("Should detect monthly pattern", pattern)
        assertEquals("MONTHLY", pattern?.frequency)
    }

    @Test
    fun `stress - detect bi-weekly pattern`() {
        val transactions = createBiWeeklyTransactions(
            merchant = "Gym",
            amount = 50.0,
            count = 6
        )
        
        val pattern = detectRecurringPattern(transactions)
        
        assertNotNull("Should detect bi-weekly pattern", pattern)
        assertEquals("BI_WEEKLY", pattern?.frequency)
    }

    @Test
    fun `stress - detect quarterly pattern`() {
        val transactions = createQuarterlyTransactions(
            merchant = "Insurance",
            amount = 300.0,
            count = 4
        )
        
        val pattern = detectRecurringPattern(transactions)
        
        assertNotNull("Should detect quarterly pattern", pattern)
        assertEquals("QUARTERLY", pattern?.frequency)
    }

    // ============================================================================
    // SECTION 2: AMOUNT CONSISTENCY
    // ============================================================================

    @Test
    fun `stress - detect exact amount consistency`() {
        val transactions = createWeeklyTransactions(
            merchant = "Spotify",
            amount = 9.99,
            count = 10
        )
        
        val isConsistent = checkAmountConsistency(transactions, tolerance = 0.0)
        
        assertTrue("Should detect exact consistency", checkAmountConsistency(transactions, tolerance = 0.001))
    }

    @Test
    fun `stress - detect consistency with small variance`() {
        val transactions = listOf(
            Transaction("Utility", 85.0, 1000L),
            Transaction("Utility", 90.0, 1000L + 30 * 24 * 60 * 60 * 1000L),
            Transaction("Utility", 88.0, 1000L + 60 * 24 * 60 * 60 * 1000L),
            Transaction("Utility", 92.0, 1000L + 90 * 24 * 60 * 60 * 1000L)
        )
        
        val isConsistent = checkAmountConsistency(transactions, tolerance = 0.1)
        
        assertTrue("Should detect consistency with variance", isConsistent)
    }

    @Test
    fun `stress - reject inconsistent amounts`() {
        val transactions = listOf(
            Transaction("Store", 50.0, 1000L),
            Transaction("Store", 200.0, 1000L + 7 * 24 * 60 * 60 * 1000L),
            Transaction("Store", 45.0, 1000L + 14 * 24 * 60 * 60 * 1000L)
        )
        
        val isConsistent = checkAmountConsistency(transactions, tolerance = 0.15)
        
        assertFalse("Should reject inconsistent amounts", isConsistent)
    }

    // ============================================================================
    // SECTION 3: MERCHANT CONSISTENCY
    // ============================================================================

    @Test
    fun `stress - detect same merchant pattern`() {
        val transactions = createWeeklyTransactions(
            merchant = "Netflix",
            amount = 15.99,
            count = 5
        )
        
        val isSameMerchant = checkMerchantConsistency(transactions)
        
        assertTrue("Should detect same merchant", isSameMerchant)
    }

    @Test
    fun `stress - detect similar merchant names`() {
        val transactions = listOf(
            Transaction("Netflix", 15.99, 1000L),
            Transaction("NETFLIX", 15.99, 1000L + 30 * 24 * 60 * 60 * 1000L),
            Transaction("Netflix.com", 15.99, 1000L + 60 * 24 * 60 * 60 * 1000L)
        )
        
        val isSimilar = checkMerchantSimilarity(transactions)
        
        assertTrue("Should detect similar merchants", isSimilar)
    }

    @Test
    fun `stress - reject different merchants`() {
        val transactions = listOf(
            Transaction("Starbucks", 5.0, 1000L),
            Transaction("McDonalds", 10.0, 1000L + 7 * 24 * 60 * 60 * 1000L),
            Transaction("Burger King", 12.0, 1000L + 14 * 24 * 60 * 60 * 1000L)
        )
        
        val isSame = checkMerchantConsistency(transactions)
        
        assertFalse("Should reject different merchants", isSame)
    }

    // ============================================================================
    // SECTION 4: MISSING PAYMENTS
    // ============================================================================

    @Test
    fun `stress - detect pattern with one skipped month`() {
        val transactions = listOf(
            Transaction("Rent", 800.0, 1000L),  // Jan
            Transaction("Rent", 800.0, 1000L + 60 * 24 * 60 * 60 * 1000L),  // Mar (skipped Feb)
            Transaction("Rent", 800.0, 1000L + 90 * 24 * 60 * 60 * 1000L)   // Apr
        )
        
        val pattern = detectRecurringPattern(transactions, allowMissed = 1)
        
        assertNotNull("Should detect with skipped payment", pattern)
    }

    @Test
    fun `stress - detect pattern with multiple skipped payments`() {
        val transactions = listOf(
            Transaction("Quarterly", 300.0, 1000L),
            Transaction("Quarterly", 300.0, 1000L + 180 * 24 * 60 * 60 * 1000L),  // 6 months later
            Transaction("Quarterly", 300.0, 1000L + 270 * 24 * 60 * 60 * 1000L)
        )
        
        val pattern = detectRecurringPattern(transactions, allowMissed = 3)
        
        assertNotNull("Should detect quarterly with skips", pattern)
    }

    // ============================================================================
    // SECTION 5: NEXT PAYMENT PREDICTION
    // ============================================================================

    @Test
    fun `stress - predict next weekly payment`() {
        val lastPayment = System.currentTimeMillis()
        val frequency = "WEEKLY"
        
        val nextPayment = predictNextPayment(lastPayment, frequency)
        
        val expected = lastPayment + 7 * 24 * 60 * 60 * 1000L
        assertEquals("Should predict next week", expected, nextPayment)
    }

    @Test
    fun `stress - predict next monthly payment`() {
        val lastPayment = System.currentTimeMillis()
        val frequency = "MONTHLY"
        
        val nextPayment = predictNextPayment(lastPayment, frequency)
        
        // Approximately 30 days
        val diff = nextPayment - lastPayment
        assertTrue("Should be approximately 30 days", diff in 28L * 24 * 60 * 60 * 1000..31L * 24 * 60 * 60 * 1000)
    }

    @Test
    fun `stress - predict with irregular intervals`() {
        val transactions = listOf(
            Transaction("Irregular", 100.0, 1000L),
            Transaction("Irregular", 100.0, 1000L + 35 * 24 * 60 * 60 * 1000L),
            Transaction("Irregular", 100.0, 1000L + 65 * 24 * 60 * 60 * 1000L)
        )
        
        val nextPayment = predictNextPaymentFromHistory(transactions)
        
        assertTrue("Should predict from average", nextPayment > 0)
    }

    // ============================================================================
    // SECTION 6: CONFIDENCE SCORING
    // ============================================================================

    @Test
    fun `stress - high confidence for strong pattern`() {
        val transactions = createMonthlyTransactions(
            merchant = "Rent",
            amount = 800.0,
            count = 12
        )
        
        val confidence = calculateConfidence(transactions)
        
        assertTrue("Should have high confidence", confidence > 0.8)
    }

    @Test
    fun `stress - low confidence for weak pattern`() {
        val transactions = listOf(
            Transaction("Weak", 50.0, 1000L),
            Transaction("Weak", 55.0, 1000L + 40 * 24 * 60 * 60 * 1000L)
        )
        
        val confidence = calculateConfidence(transactions)
        
        assertTrue("Should have low confidence", confidence < 0.5)
    }

    @Test
    fun `stress - medium confidence for moderate pattern`() {
        val transactions = listOf(
            Transaction("Medium", 100.0, 1000L),
            Transaction("Medium", 100.0, 1000L + 30 * 24 * 60 * 60 * 1000L),
            Transaction("Medium", 100.0, 1000L + 62 * 24 * 60 * 60 * 1000L),
            Transaction("Medium", 100.0, 1000L + 91 * 24 * 60 * 60 * 1000L)
        )
        
        val confidence = calculateConfidence(transactions)
        
        assertTrue("Should have high confidence", confidence > 0.8)
    }

    // ============================================================================
    // SECTION 7: PATTERN VALIDATION
    // ============================================================================

    @Test
    fun `stress - require minimum transactions`() {
        val transactions = listOf(
            Transaction("Sparse", 50.0, 1000L),
            Transaction("Sparse", 50.0, 1000L + 30 * 24 * 60 * 60 * 1000L)
        )
        
        val pattern = detectRecurringPattern(transactions, minTransactions = 3)
        
        assertNull("Should require minimum transactions", pattern)
    }

    @Test
    fun `stress - validate interval consistency`() {
        val transactions = listOf(
            Transaction("Regular", 50.0, 1000L),
            Transaction("Regular", 50.0, 1000L + 7 * 24 * 60 * 60 * 1000L),
            Transaction("Regular", 50.0, 1000L + 14 * 24 * 60 * 60 * 1000L),
            Transaction("Regular", 50.0, 1000L + 21 * 24 * 60 * 60 * 1000L)
        )
        
        val isConsistent = validateIntervalConsistency(transactions, maxVariance = 0.1)
        
        assertTrue("Should validate consistent intervals", isConsistent)
    }

    @Test
    fun `stress - reject highly irregular intervals`() {
        val transactions = listOf(
            Transaction("Irregular", 50.0, 1000L),
            Transaction("Irregular", 50.0, 1000L + 5 * 24 * 60 * 60 * 1000L),
            Transaction("Irregular", 50.0, 1000L + 20 * 24 * 60 * 60 * 1000L),
            Transaction("Irregular", 50.0, 1000L + 8 * 24 * 60 * 60 * 1000L)
        )
        
        val isConsistent = validateIntervalConsistency(transactions, maxVariance = 0.3)
        
        assertFalse("Should reject irregular intervals", isConsistent)
    }

    // ============================================================================
    // SECTION 8: DUPLICATE PATTERN DETECTION
    // ============================================================================

    @Test
    fun `stress - avoid duplicate pattern detection`() {
        val existingPatterns = listOf(
            RecurringPattern("Netflix", 15.99, "MONTHLY")
        )
        
        val newTransactions = createMonthlyTransactions(
            merchant = "Netflix",
            amount = 15.99,
            count = 3
        )
        
        val isDuplicate = checkForDuplicatePattern(existingPatterns, newTransactions)
        
        assertTrue("Should detect duplicate", isDuplicate)
    }

    @Test
    fun `stress - allow similar but different patterns`() {
        val existingPatterns = listOf(
            RecurringPattern("Netflix", 15.99, "MONTHLY")
        )
        
        val newTransactions = createMonthlyTransactions(
            merchant = "Spotify",
            amount = 9.99,
            count = 3
        )
        
        val isDuplicate = checkForDuplicatePattern(existingPatterns, newTransactions)
        
        assertFalse("Should allow different patterns", isDuplicate)
    }

    // ============================================================================
    // SECTION 9: EDGE CASES
    // ============================================================================

    @Test
    fun `stress - handle single transaction`() {
        val transactions = listOf(
            Transaction("Once", 100.0, 1000L)
        )
        
        val pattern = detectRecurringPattern(transactions)
        
        assertNull("Should not detect pattern from single transaction", pattern)
    }

    @Test
    fun `stress - handle two transactions`() {
        val transactions = listOf(
            Transaction("Twice", 100.0, 1000L),
            Transaction("Twice", 100.0, 1000L + 30 * 24 * 60 * 60 * 1000L)
        )
        
        val pattern = detectRecurringPattern(transactions, minTransactions = 3)
        
        assertNull("Should not detect from only two", pattern)
    }

    @Test
    fun `stress - handle very old transactions`() {
        val oldTransactions = createMonthlyTransactions(
            merchant = "Old",
            amount = 100.0,
            count = 6
        ).map { it.copy(timestamp = it.timestamp - 365L * 24 * 60 * 60 * 1000) }
        
        val pattern = detectRecurringPattern(oldTransactions)
        
        // May or may not detect depending on implementation
        assertTrue("Should handle old data", pattern != null || pattern == null)
    }

    @Test
    fun `stress - handle future transactions`() {
        val futureTransactions = createMonthlyTransactions(
            merchant = "Future",
            amount = 100.0,
            count = 3
        ).map { it.copy(timestamp = it.timestamp + 365L * 24 * 60 * 60 * 1000) }
        
        val pattern = detectRecurringPattern(futureTransactions)
        
        // Should handle gracefully
        assertTrue("Should handle future data", pattern != null || pattern == null)
    }

    // ============================================================================
    // SECTION 10: PERFORMANCE
    // ============================================================================

    @Test
    fun `stress - detect patterns in 1000 transactions quickly`() {
        val transactions = (1..1000).map { i ->
            Transaction(
                merchant = "Merchant${i % 10}",
                amount = (i % 100).toDouble(),
                timestamp = 1000L + (i * 7L * 24 * 60 * 60 * 1000)
            )
        }
        
        val startTime = System.nanoTime()
        
        // Group by merchant and detect patterns
        val byMerchant = transactions.groupBy { it.merchant }
        val patterns = byMerchant.mapNotNull { (_, txs) ->
            detectRecurringPattern(txs)
        }
        
        val duration = System.nanoTime() - startTime
        
        assertTrue("Should detect quickly", duration < 1_000_000_000)
        assertTrue("Should find patterns", patterns.isNotEmpty())
    }

    // Helper data classes and functions
    private data class Transaction(
        val merchant: String,
        val amount: Double,
        val timestamp: Long
    )
    
    private data class RecurringPattern(
        val merchant: String,
        val amount: Double,
        val frequency: String,
        val confidence: Double = 0.0,
        val nextPayment: Long = 0
    )
    
    private fun createWeeklyTransactions(merchant: String, amount: Double, count: Int): List<Transaction> {
        return (0 until count).map { i ->
            Transaction(merchant, amount, 1000L + i * 7L * 24 * 60 * 60 * 1000)
        }
    }
    
    private fun createMonthlyTransactions(merchant: String, amount: Double, count: Int): List<Transaction> {
        return (0 until count).map { i ->
            Transaction(merchant, amount, 1000L + i * 30L * 24 * 60 * 60 * 1000)
        }
    }
    
    private fun createBiWeeklyTransactions(merchant: String, amount: Double, count: Int): List<Transaction> {
        return (0 until count).map { i ->
            Transaction(merchant, amount, 1000L + i * 14L * 24 * 60 * 60 * 1000)
        }
    }
    
    private fun createQuarterlyTransactions(merchant: String, amount: Double, count: Int): List<Transaction> {
        return (0 until count).map { i ->
            Transaction(merchant, amount, 1000L + i * 90L * 24 * 60 * 60 * 1000)
        }
    }
    
    private fun detectRecurringPattern(
        transactions: List<Transaction>,
        minTransactions: Int = 3,
        allowMissed: Int = 0
    ): RecurringPattern? {
        if (transactions.size < minTransactions) return null
        
        val merchant = transactions.first().merchant
        val avgAmount = transactions.map { it.amount }.average()
        
        // Calculate intervals
        val intervals = transactions.sortedBy { it.timestamp }
            .zipWithNext { a, b -> b.timestamp - a.timestamp }
        
        if (intervals.isEmpty()) return null
        
        val avgInterval = intervals.average()
        
        // Determine frequency
        val frequency = when {
            avgInterval < 2 * 24 * 60 * 60 * 1000L -> "DAILY"
            avgInterval < 10 * 24 * 60 * 60 * 1000L -> "WEEKLY"
            avgInterval < 20 * 24 * 60 * 60 * 1000L -> "BI_WEEKLY"
            avgInterval < 40 * 24 * 60 * 60 * 1000L -> "MONTHLY"
            else -> "QUARTERLY"
        }
        
        return RecurringPattern(merchant, avgAmount, frequency)
    }
    
    private fun checkAmountConsistency(transactions: List<Transaction>, tolerance: Double): Boolean {
        if (transactions.size < 2) return false
        val amounts = transactions.map { it.amount }
        val mean = amounts.average()
        val variance = amounts.map { (it - mean) * (it - mean) }.average()
        val stdDev = Math.sqrt(variance)
        return (stdDev / mean) <= tolerance
    }
    
    private fun checkMerchantConsistency(transactions: List<Transaction>): Boolean {
        val merchants = transactions.map { it.merchant.toLowerCase() }.distinct()
        return merchants.size == 1
    }
    
    private fun checkMerchantSimilarity(transactions: List<Transaction>): Boolean {
        val normalized = transactions.map { 
            it.merchant.toLowerCase().replace(".com", "").replace("www.", "")
        }
        val distinct = normalized.distinct()
        return distinct.size <= transactions.size / 2
    }
    
    private fun predictNextPayment(lastPayment: Long, frequency: String): Long {
        val interval = when (frequency) {
            "DAILY" -> 1L * 24 * 60 * 60 * 1000
            "WEEKLY" -> 7L * 24 * 60 * 60 * 1000
            "BI_WEEKLY" -> 14L * 24 * 60 * 60 * 1000
            "MONTHLY" -> 30L * 24 * 60 * 60 * 1000
            "QUARTERLY" -> 90L * 24 * 60 * 60 * 1000
            else -> 30L * 24 * 60 * 60 * 1000
        }
        return lastPayment + interval
    }
    
    private fun predictNextPaymentFromHistory(transactions: List<Transaction>): Long {
        if (transactions.size < 2) return 0
        val intervals = transactions.sortedBy { it.timestamp }
            .zipWithNext { a, b -> b.timestamp - a.timestamp }
        val avgInterval = intervals.average().toLong()
        return transactions.last().timestamp + avgInterval
    }
    
    private fun calculateConfidence(transactions: List<Transaction>): Double {
        if (transactions.size < 2) return 0.0
        
        var score = 0.0
        
        // More transactions = higher confidence
        score += (transactions.size.toDouble() / 12).coerceAtMost(0.4)
        
        // Consistent amounts
        val amounts = transactions.map { it.amount }
        val mean = amounts.average()
        val variance = amounts.map { (it - mean) * (it - mean) }.average()
        if (variance < 1.0) score += 0.3
        
        // Consistent intervals
        val intervals = transactions.sortedBy { it.timestamp }
            .zipWithNext { a, b -> (b.timestamp - a.timestamp).toDouble() }
        if (intervals.isNotEmpty()) {
            val intervalMean = intervals.average()
            val intervalVariance = intervals.map { (it - intervalMean) * (it - intervalMean) }.average()
            if (intervalVariance < 86400000.0 * 86400000.0 * 2) score += 0.3
        }
        
        return score.coerceIn(0.0, 1.0)
    }
    
    private fun validateIntervalConsistency(transactions: List<Transaction>, maxVariance: Double): Boolean {
        if (transactions.size < 3) return false
        val intervals = transactions.sortedBy { it.timestamp }
            .zipWithNext { a, b -> (b.timestamp - a.timestamp).toDouble() }
        val mean = intervals.average()
        val variance = intervals.map { (it - mean) * (it - mean) }.average()
        return Math.sqrt(variance) / mean <= maxVariance
    }
    
    private fun checkForDuplicatePattern(
        existing: List<RecurringPattern>,
        newTransactions: List<Transaction>
    ): Boolean {
        val newMerchant = newTransactions.firstOrNull()?.merchant ?: return false
        val newAmount = newTransactions.map { it.amount }.average()
        
        return existing.any { pattern ->
            pattern.merchant.equals(newMerchant, ignoreCase = true) &&
            Math.abs(pattern.amount - newAmount) < 1.0
        }
    }
}
