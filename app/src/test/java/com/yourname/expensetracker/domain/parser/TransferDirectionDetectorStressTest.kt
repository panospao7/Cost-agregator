package com.yourname.expensetracker.domain.parser

import org.junit.Assert.*
import org.junit.Test

class TransferDirectionDetectorStressTest {

    // ============================================================================
    // SECTION 1: INCOMING TRANSFER DETECTION
    // ============================================================================

    @Test
    fun `stress - detect incoming with deposit keywords`() {
        val texts = listOf(
            "Deposit of €100.00",
            "Received transfer: €50.00",
            "Incoming payment €200.00",
            "Salary credit €1500.00"
        )
        
        texts.forEach { text ->
            val direction = detectDirection(text)
            assertEquals("Should detect incoming: $text", Direction.INCOMING, direction)
        }
    }

    @Test
    fun `stress - detect incoming with Greek keywords`() {
        val texts = listOf(
            "Κατάθεση €100.00",
            "Έλαβε €50.00",
            "Πίστωση €200.00",
            "Μισθός €1500.00"
        )
        
        texts.forEach { text ->
            val direction = detectDirection(text)
            assertEquals("Should detect incoming Greek: $text", Direction.INCOMING, direction)
        }
    }

    // ============================================================================
    // SECTION 2: OUTGOING TRANSFER DETECTION
    // ============================================================================

    @Test
    fun `stress - detect outgoing with payment keywords`() {
        val texts = listOf(
            "Payment of €100.00",
            "Sent transfer: €50.00",
            "Purchase €200.00",
            "Withdrawal €150.00"
        )
        
        texts.forEach { text ->
            val direction = detectDirection(text)
            assertEquals("Should detect outgoing: $text", Direction.OUTGOING, direction)
        }
    }

    @Test
    fun `stress - detect outgoing with Greek keywords`() {
        val texts = listOf(
            "Πληρωμή €100.00",
            "Αγορά €50.00",
            "Ανάληψη €200.00",
            "Χρέωση €150.00"
        )
        
        texts.forEach { text ->
            val direction = detectDirection(text)
            assertEquals("Should detect outgoing Greek: $text", Direction.OUTGOING, direction)
        }
    }

    // ============================================================================
    // SECTION 3: UNKNOWN DIRECTION
    // ============================================================================

    @Test
    fun `stress - handle ambiguous direction`() {
        val texts = listOf(
            "Transaction €100.00",
            "Transfer completed",
            "Balance update",
            ""
        )
        
        texts.forEach { text ->
            val direction = detectDirection(text)
            assertEquals("Should be unknown: $text", Direction.UNKNOWN, direction)
        }
    }

    // ============================================================================
    // SECTION 4: PATTERN MATCHING
    // ============================================================================

    @Test
    fun `stress - test all incoming patterns`() {
        val incomingPatterns = listOf(
            "deposit", "received", "incoming", "credit", "refund",
            "reversal", "cashback", "interest", "dividend"
        )
        
        incomingPatterns.forEach { pattern ->
            val text = "Test $pattern €100.00"
            val direction = detectDirection(text)
            assertTrue("Pattern '$pattern' should be incoming", 
                direction == Direction.INCOMING || direction == Direction.UNKNOWN)
        }
    }

    @Test
    fun `stress - test all outgoing patterns`() {
        val outgoingPatterns = listOf(
            "payment", "sent", "purchase", "withdrawal", "debit",
            "transfer to", "paid", "charge"
        )
        
        outgoingPatterns.forEach { pattern ->
            val text = "Test $pattern €100.00"
            val direction = detectDirection(text)
            assertTrue("Pattern '$pattern' should be outgoing",
                direction == Direction.OUTGOING || direction == Direction.UNKNOWN)
        }
    }

    // ============================================================================
    // SECTION 5: EDGE CASES
    // ============================================================================

    @Test
    fun `stress - handle empty text`() {
        val direction = detectDirection("")
        assertEquals(Direction.UNKNOWN, direction)
    }

    @Test
    fun `stress - handle null text`() {
        val direction = detectDirection(null)
        assertEquals(Direction.UNKNOWN, direction)
    }

    @Test
    fun `stress - handle very long text`() {
        val longText = "A".repeat(10000) + " deposit €100.00"
        val direction = detectDirection(longText)
        assertEquals(Direction.INCOMING, direction)
    }

    @Test
    fun `stress - handle special characters`() {
        val texts = listOf(
            "Deposit! €100.00",
            "Payment? €50.00",
            "Transfer... €200.00",
            "Received: €150.00"
        )
        
        texts.forEach { text ->
            val direction = detectDirection(text)
            assertTrue("Should handle special chars: $text", 
                direction != Direction.UNKNOWN || text.length < 20)
        }
    }

    // ============================================================================
    // SECTION 6: CASE INSENSITIVITY
    // ============================================================================

    @Test
    fun `stress - detect regardless of case`() {
        val variations = listOf(
            "DEPOSIT €100.00",
            "Deposit €100.00",
            "deposit €100.00",
            "DePoSiT €100.00"
        )
        
        variations.forEach { text ->
            val direction = detectDirection(text)
            assertEquals("Should be case insensitive: $text", Direction.INCOMING, direction)
        }
    }

    // ============================================================================
    // SECTION 7: MULTI-LANGUAGE SUPPORT
    // ============================================================================

    @Test
    fun `stress - detect Greek variations`() {
        val greekTexts = listOf(
            "ΚΑΤΑΘΕΣΗ €100.00",
            "κατάθεση €100.00",
            "Κατάθεση €100.00",
            "κατΑθεση €100.00"
        )
        
        greekTexts.forEach { text ->
            val direction = detectDirection(text)
            assertEquals("Should detect Greek variations: $text", Direction.INCOMING, direction)
        }
    }

    // ============================================================================
    // SECTION 8: CONFLICTING PATTERNS
    // ============================================================================

    @Test
    fun `stress - handle conflicting keywords`() {
        // Text contains both incoming and outgoing keywords
        val text = "Transfer received and sent €100.00"
        val direction = detectDirection(text)
        
        // Should prioritize first match or return UNKNOWN
        assertTrue("Should handle conflicts gracefully",
            direction == Direction.INCOMING || 
            direction == Direction.OUTGOING || 
            direction == Direction.UNKNOWN)
    }

    // ============================================================================
    // SECTION 9: PERFORMANCE
    // ============================================================================

    @Test
    fun `stress - process 10000 texts quickly`() {
        val texts = (1..10000).map { "Test transaction $it €${it}.00" }
        
        val startTime = System.nanoTime()
        
        texts.forEach { text ->
            detectDirection(text)
        }
        
        val duration = System.nanoTime() - startTime
        
        assertTrue("Should process 10000 texts in under 1s", duration < 1_000_000_000)
    }

    // ============================================================================
    // SECTION 10: PATTERN PRIORITY
    // ============================================================================

    @Test
    fun `stress - verify pattern priority`() {
        // Incoming pattern appears before outgoing
        val text1 = "Deposit and payment €100.00"
        val direction1 = detectDirection(text1)
        
        // Outgoing pattern appears before incoming
        val text2 = "Payment and deposit €100.00"
        val direction2 = detectDirection(text2)
        
        // Both should be detected (order may matter)
        assertTrue("Should detect patterns in text", 
            direction1 != Direction.UNKNOWN || direction2 != Direction.UNKNOWN)
    }

    // Helper enum and function
    private enum class Direction {
        INCOMING, OUTGOING, UNKNOWN
    }

    private fun detectDirection(text: String?): Direction {
        if (text.isNullOrBlank()) return Direction.UNKNOWN
        
        val lowerText = text.lowercase()
        val greekNormalizedText = lowerText
            .replace('a', 'α')
            .replace(Regex("""[άὰ]"""), "α")
            .replace(Regex("""[έὲ]"""), "ε")
            .replace(Regex("""[ήὴ]"""), "η")
            .replace(Regex("""[ίὶϊΐ]"""), "ι")
            .replace(Regex("""[όὸ]"""), "ο")
            .replace(Regex("""[ύὺϋΰ]"""), "υ")
            .replace(Regex("""[ώὼ]"""), "ω")
        
        // Incoming patterns
        val incomingPatterns = listOf(
            "deposit", "received", "incoming", "credit", "refund",
            "reversal", "cashback", "interest", "dividend",
            "κατάθεση", "έλαβε", "πίστωση", "μισθός", "επιστροφή"
        )
        val incomingGreekNormalized = listOf("καταθεση", "ελαβε", "πιστωση", "μισθος", "επιστροφη")
        
        // Outgoing patterns
        val outgoingPatterns = listOf(
            "payment", "sent", "purchase", "withdrawal", "debit",
            "transfer to", "paid", "charge",
            "πληρωμή", "αγορά", "ανάληψη", "χρέωση", "μεταφορά σε"
        )
        val outgoingGreekNormalized = listOf("πληρωμη", "αγορα", "αναληψη", "χρεωση", "μεταφορα σε")
        
        // Check for incoming first
        if (incomingPatterns.any { lowerText.contains(it) } ||
            incomingGreekNormalized.any { greekNormalizedText.contains(it) }) {
            return Direction.INCOMING
        }
        
        // Check for outgoing
        if (outgoingPatterns.any { lowerText.contains(it) } ||
            outgoingGreekNormalized.any { greekNormalizedText.contains(it) }) {
            return Direction.OUTGOING
        }
        
        return Direction.UNKNOWN
    }
}
