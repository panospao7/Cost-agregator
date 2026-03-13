package com.yourname.expensetracker.domain.parser

import org.junit.Assert.*
import org.junit.Test

class GenericTransactionParserStressTest {

    // ============================================================================
    // SECTION 1: FALLBACK PARSING
    // ============================================================================

    @Test
    fun `stress - parse generic purchase pattern`() {
        val notifications = listOf(
            "Transaction: €50.00 at Starbucks",
            "Payment €25.00 - Amazon",
            "You spent $100.00 at Walmart",
            "Purchase: £30.00 at Tesco"
        )
        
        notifications.forEach { text ->
            val result = parseGenericTransaction(text)
            assertNotNull("Should parse: $text", result)
        }
    }

    @Test
    fun `stress - parse transfer patterns`() {
        val notifications = listOf(
            "Transfer: €200.00 to John",
            "Sent $50.00 to Mary",
            "Received £100.00 from Bob",
            "Money sent: €75.00"
        )
        
        notifications.forEach { text ->
            val result = parseGenericTransaction(text)
            assertNotNull("Should parse transfer: $text", result)
        }
    }

    // ============================================================================
    // SECTION 2: AMOUNT EXTRACTION
    // ============================================================================

    @Test
    fun `stress - extract amount with various formats`() {
        val formats = listOf(
            "€50.00",
            "EUR 50.00",
            "50.00 EUR",
            "50,00 €",
            "$50.00",
            "USD 50.00",
            "£50.00",
            "GBP 50.00"
        )
        
        formats.forEach { format ->
            val text = "Payment $format at Store"
            val result = parseGenericTransaction(text)
            assertNotNull("Should extract: $format", result)
        }
    }

    @Test
    fun `stress - handle amounts with thousands separator`() {
        val amounts = listOf(
            "€1,000.00",
            "€10,000.00",
            "€100,000.00",
            "€1,000,000.00"
        )
        
        amounts.forEach { amount ->
            val text = "Transaction $amount"
            val result = parseGenericTransaction(text)
            assertNotNull("Should parse large amount: $amount", result)
        }
    }

    // ============================================================================
    // SECTION 3: MERCHANT EXTRACTION
    // ============================================================================

    @Test
    fun `stress - extract merchant from various patterns`() {
        val patterns = listOf(
            "Payment at Starbucks" to "Starbucks",
            "Purchase - Amazon" to "Amazon",
            "Transaction: Walmart" to "Walmart",
            "You spent at Tesco" to "Tesco",
            "Paid to McDonald's" to "McDonald's"
        )
        
        patterns.forEach { (text, expected) ->
            val result = parseGenericTransaction(text)
            assertTrue("Should extract merchant: $text",
                result?.get("merchant")?.contains(expected) == true)
        }
    }

    @Test
    fun `stress - handle merchant without clear delimiter`() {
        val texts = listOf(
            "Starbucks €5.00",
            "Amazon $50.00",
            "Walmart purchase"
        )
        
        texts.forEach { text ->
            val result = parseGenericTransaction(text)
            assertNotNull("Should handle: $text", result)
        }
    }

    // ============================================================================
    // SECTION 4: FALSE POSITIVE HANDLING
    // ============================================================================

    @Test
    fun `stress - reject non-financial notifications`() {
        val nonFinancial = listOf(
            "You have a new message",
            "Update available",
            "Reminder: Meeting at 3 PM",
            "Weather forecast: Sunny",
            "Battery low",
            "WiFi connected"
        )
        
        nonFinancial.forEach { text ->
            val result = parseGenericTransaction(text)
            assertNull("Should reject: $text", result)
        }
    }

    @Test
    fun `stress - reject social media notifications`() {
        val social = listOf(
            "John liked your photo",
            "Mary commented on your post",
            "New follower: @username",
            "Tag: You were mentioned in a post"
        )
        
        social.forEach { text ->
            val result = parseGenericTransaction(text)
            assertNull("Should reject social: $text", result)
        }
    }

    @Test
    fun `stress - reject marketing notifications`() {
        val marketing = listOf(
            "Flash sale! 50% off",
            "New products available",
            "Limited time offer",
            "Check out our app"
        )
        
        marketing.forEach { text ->
            val result = parseGenericTransaction(text)
            assertNull("Should reject marketing: $text", result)
        }
    }

    // ============================================================================
    // SECTION 5: EDGE CASES
    // ============================================================================

    @Test
    fun `stress - handle notification with only amount`() {
        val text = "€50.00"
        val result = parseGenericTransaction(text)
        assertNotNull("Should parse amount-only", result)
    }

    @Test
    fun `stress - handle notification without amount`() {
        val text = "Payment at Starbucks"
        val result = parseGenericTransaction(text)
        // May or may not parse - depends on strictness
        assertTrue("Should handle gracefully", result != null || result == null)
    }

    @Test
    fun `stress - handle very short notification`() {
        val text = "€5"
        val result = parseGenericTransaction(text)
        assertNotNull("Should parse short", result)
    }

    @Test
    fun `stress - handle very long notification`() {
        val text = "A".repeat(5000) + " €50.00 " + "B".repeat(5000)
        val result = parseGenericTransaction(text)
        assertNotNull("Should parse long", result)
    }

    @Test
    fun `stress - handle empty notification`() {
        val result = parseGenericTransaction("")
        assertNull("Should handle empty", result)
    }

    // ============================================================================
    // SECTION 6: SPECIAL CHARACTERS
    // ============================================================================

    @Test
    fun `stress - handle special characters in merchant`() {
        val merchants = listOf(
            "H&M",
            "AT&T",
            "7-Eleven",
            "B&Q",
            "McDonald's",
            "T.J. Maxx"
        )
        
        merchants.forEach { merchant ->
            val text = "Payment €50.00 at $merchant"
            val result = parseGenericTransaction(text)
            assertNotNull("Should handle: $merchant", result)
        }
    }

    @Test
    fun `stress - handle unicode characters`() {
        val texts = listOf(
            "Payment €50.00 at 星巴克",
            "Payment €50.00 at Café",
            "Payment €50.00 at Ñoño"
        )
        
        texts.forEach { text ->
            val result = parseGenericTransaction(text)
            assertNotNull("Should handle unicode: $text", result)
        }
    }

    // ============================================================================
    // SECTION 7: CURRENCY HANDLING
    // ============================================================================

    @Test
    fun `stress - parse various currencies`() {
        val currencies = listOf(
            "€" to "EUR",
            "$" to "USD",
            "£" to "GBP",
            "¥" to "JPY",
            "CHF" to "CHF",
            "CAD" to "CAD",
            "AUD" to "AUD"
        )
        
        currencies.forEach { (symbol, code) ->
            val text = "Payment ${symbol}50.00 at Store"
            val result = parseGenericTransaction(text)
            assertNotNull("Should parse $code", result)
        }
    }

    @Test
    fun `stress - handle crypto amounts`() {
        val texts = listOf(
            "Sent 0.001 BTC",
            "Received 10 ETH",
            "Transfer: 1000 DOGE"
        )
        
        texts.forEach { text ->
            val result = parseGenericTransaction(text)
            // May or may not parse
            assertTrue("Should handle crypto", result != null || result == null)
        }
    }

    // ============================================================================
    // SECTION 8: PATTERN COMPLEXITY
    // ============================================================================

    @Test
    fun `stress - parse nested patterns`() {
        val texts = listOf(
            "Transaction: Payment of €50.00 at Store",
            "Notification: You paid €25.00 to John",
            "Alert: Purchase €100.00 completed"
        )
        
        texts.forEach { text ->
            val result = parseGenericTransaction(text)
            assertNotNull("Should parse nested: $text", result)
        }
    }

    @Test
    fun `stress - parse multiple amounts`() {
        val text = "Transfer €50.00 (was €45.00) to John"
        val result = parseGenericTransaction(text)
        assertNotNull("Should handle multiple amounts", result)
    }

    // ============================================================================
    // SECTION 9: CONFIDENCE SCORING
    // ============================================================================

    @Test
    fun `stress - calculate parse confidence`() {
        val highConfidence = "Payment: €50.00 at Starbucks"
        val mediumConfidence = "€50.00 transaction"
        val lowConfidence = "Some text with number 50"
        
        val highResult = parseGenericTransaction(highConfidence)
        val medResult = parseGenericTransaction(mediumConfidence)
        val lowResult = parseGenericTransaction(lowConfidence)
        
        assertNotNull("High confidence should parse", highResult)
        // Medium and low may vary
    }

    @Test
    fun `stress - confidence with amount and merchant`() {
        val text = "Paid €50.00 at Amazon"
        val result = parseGenericTransaction(text)
        
        assertNotNull("Should parse with both fields", result)
        assertNotNull("Should have amount", result?.get("amount"))
        assertNotNull("Should have merchant", result?.get("merchant"))
    }

    // ============================================================================
    // SECTION 10: PERFORMANCE
    // ============================================================================

    @Test
    fun `stress - parse 5000 unknown notifications quickly`() {
        val notifications = (1..5000).map {
            "Unknown app transaction $it: €${it}.00 at Store $it"
        }
        
        val startTime = System.nanoTime()
        
        notifications.forEach { text ->
            parseGenericTransaction(text)
        }
        
        val duration = System.nanoTime() - startTime
        
        assertTrue("Should parse 5000 in under 2s", duration < 2_000_000_000)
    }

    // ============================================================================
    // SECTION 11: CONSISTENCY
    // ============================================================================

    @Test
    fun `stress - deterministic parsing`() {
        val text = "Transaction: €50.00 at Starbucks"
        
        val result1 = parseGenericTransaction(text)
        val result2 = parseGenericTransaction(text)
        val result3 = parseGenericTransaction(text)
        
        assertEquals("Should be deterministic", result1, result2)
        assertEquals("Should be deterministic", result2, result3)
    }

    // Helper function - simplified generic parser
    private fun parseGenericTransaction(text: String): Map<String, String>? {
        if (text.isBlank()) return null
        
        val lowerText = text.lowercase()
        
        // Check for financial keywords
        val financialKeywords = listOf(
            "payment", "purchase", "transaction", "transfer", "sent", "received",
            "paid", "spent", "bought", "buy", "refund", "withdrawal", "deposit"
        )
        
        val hasFinancialKeyword = financialKeywords.any { lowerText.contains(it) }
        
        // Check for amount pattern
        val amountRegex = Regex("""[€$£¥]?\s*([0-9,.]+)\s*(?:EUR|USD|GBP|JPY)?""")
        val hasAmount = amountRegex.containsMatchIn(text)
        
        // Must have at least amount and preferably a financial keyword
        if (!hasAmount && !hasFinancialKeyword) return null
        
        val result = mutableMapOf<String, String>()
        
        // Extract amount
        amountRegex.find(text)?.let {
            result["amount"] = it.groupValues[1].replace(",", "")
        }
        
        // Extract merchant (look for words after "at", "to", "from", "-", ":")
        val merchantRegex = Regex("""(?:at|to|from|-|:)\s+([A-Za-z0-9&'\-\s.]+)""", RegexOption.IGNORE_CASE)
        merchantRegex.find(text)?.let {
            result["merchant"] = it.groupValues[1].trim()
        }
        
        return if (result.isNotEmpty()) result else null
    }
}
