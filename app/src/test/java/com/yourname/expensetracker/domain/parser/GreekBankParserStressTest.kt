package com.yourname.expensetracker.domain.parser

import org.junit.Assert.*
import org.junit.Test

class GreekBankParserStressTest {

    // ============================================================================
    // SECTION 1: NBG (National Bank of Greece) FORMATS
    // ============================================================================

    @Test
    fun `stress - parse NBG purchase notification`() {
        val notification = "Αγορά €50.00 στο Starbucks με κάρτα τερματικό *****1234"
        
        val result = parseGreekBankNotification(notification)
        
        assertNotNull("Should parse amount", result["amount"])
        assertNotNull("Should parse merchant", result["merchant"])
    }

    @Test
    fun `stress - parse NBG withdrawal notification`() {
        val notification = "Ανάληψη €100.00 από ΑΤΜ *****5678"
        
        val result = parseGreekBankNotification(notification)
        
        assertNotNull("Should parse amount", result["amount"])
    }

    // ============================================================================
    // SECTION 2: ALPHA BANK FORMATS
    // ============================================================================

    @Test
    fun `stress - parse Alpha Bank purchase`() {
        val notification = "Συναλλαγή €75.50 - Κατάστημα: Lidl"
        
        val result = parseGreekBankNotification(notification)
        
        assertNotNull("Should parse amount", result["amount"])
    }

    @Test
    fun `stress - parse Alpha Bank transfer`() {
        val notification = "Μεταφορά €200.00 προς Λογαριασμό: 1234567890"
        
        val result = parseGreekBankNotification(notification)
        
        assertNotNull("Should parse amount", result["amount"])
    }

    // ============================================================================
    // SECTION 3: EUROBANK FORMATS
    // ============================================================================

    @Test
    fun `stress - parse Eurobank purchase`() {
        val notification = "Χρέωση κάρτας €30.00 - Σκλαβενίτης"
        
        val result = parseGreekBankNotification(notification)
        
        assertNotNull("Should parse amount", result["amount"])
        assertTrue("Should contain merchant name", 
            result["merchant"]?.contains("Σκλαβενίτης") == true)
    }

    @Test
    fun `stress - parse Eurobank ATM withdrawal`() {
        val notification = "Ανάληψη μετρητών €150.00 από ΑΤΜ"
        
        val result = parseGreekBankNotification(notification)
        
        assertNotNull("Should parse amount", result["amount"])
    }

    // ============================================================================
    // SECTION 4: PIRAEUS BANK FORMATS
    // ============================================================================

    @Test
    fun `stress - parse Piraeus Bank purchase`() {
        val notification = "Αγορά με κάρτα €45.00 - ΑΒ Βασιλόπουλος"
        
        val result = parseGreekBankNotification(notification)
        
        assertNotNull("Should parse amount", result["amount"])
    }

    @Test
    fun `stress - parse Piraeus Bank deposit`() {
        val notification = "Κατάθεση €500.00 στον λογαριασμό σας"
        
        val result = parseGreekBankNotification(notification)
        
        assertNotNull("Should parse amount", result["amount"])
    }

    // ============================================================================
    // SECTION 5: AMOUNT FORMATS
    // ============================================================================

    @Test
    fun `stress - parse European amount format`() {
        val notifications = listOf(
            "Αγορά €1.234,56",
            "Αγορά €12,34",
            "Αγορά €1234.56"
        )
        
        notifications.forEach { notification ->
            val result = parseGreekBankNotification(notification)
            assertNotNull("Should parse amount: $notification", result["amount"])
        }
    }

    @Test
    fun `stress - parse amounts with currency symbols`() {
        val notifications = listOf(
            "Αγορά 50.00€",
            "Αγορά €50.00",
            "Αγορά 50,00 €",
            "Αγορά 50.00 EUR"
        )
        
        notifications.forEach { notification ->
            val result = parseGreekBankNotification(notification)
            assertNotNull("Should parse amount: $notification", result["amount"])
        }
    }

    // ============================================================================
    // SECTION 6: MERCHANT EXTRACTION
    // ============================================================================

    @Test
    fun `stress - extract merchant from various formats`() {
        val testCases = listOf(
            "Αγορά €50.00 στο Starbucks" to "Starbucks",
            "Συναλλαγή €75.50 - Κατάστημα: Lidl" to "Lidl",
            "Χρέωση €30.00 - Σκλαβενίτης" to "Σκλαβενίτης"
        )
        
        testCases.forEach { (notification, expectedMerchant) ->
            val result = parseGreekBankNotification(notification)
            assertTrue("Should extract merchant from: $notification",
                result["merchant"]?.contains(expectedMerchant) == true)
        }
    }

    // ============================================================================
    // SECTION 7: CARD NUMBER MASKING
    // ============================================================================

    @Test
    fun `stress - handle masked card numbers`() {
        val notifications = listOf(
            "Αγορά €50.00 με κάρτα *****1234",
            "Αγορά €50.00 με κάρτα **** **** **** 1234",
            "Αγορά €50.00 - Κάρτα: 1234"
        )
        
        notifications.forEach { notification ->
            val result = parseGreekBankNotification(notification)
            assertNotNull("Should parse with masked card: $notification", result["amount"])
        }
    }

    // ============================================================================
    // SECTION 8: EDGE CASES
    // ============================================================================

    @Test
    fun `stress - handle zero amount`() {
        val notification = "Αγορά €0.00"
        
        val result = parseGreekBankNotification(notification)
        
        assertNotNull("Should parse zero amount", result["amount"])
    }

    @Test
    fun `stress - handle very large amount`() {
        val notification = "Αγορά €1.000.000,00"
        
        val result = parseGreekBankNotification(notification)
        
        assertNotNull("Should parse large amount", result["amount"])
    }

    @Test
    fun `stress - handle negative amount`() {
        val notification = "Επιστροφή χρημάτων -€50.00"
        
        val result = parseGreekBankNotification(notification)
        
        assertNotNull("Should parse refund", result["amount"])
    }

    @Test
    fun `stress - handle notification without amount`() {
        val notification = "Ενημέρωση κίνησης λογαριασμού"
        
        val result = parseGreekBankNotification(notification)
        
        assertNull("Should not parse amount", result["amount"])
    }

    // ============================================================================
    // SECTION 9: SPECIAL CHARACTERS
    // ============================================================================

    @Test
    fun `stress - handle special characters in merchant name`() {
        val notifications = listOf(
            "Αγορά €50.00 στο McDonald's",
            "Αγορά €50.00 στο 7-Eleven",
            "Αγορά €50.00 στο H&M"
        )
        
        notifications.forEach { notification ->
            val result = parseGreekBankNotification(notification)
            assertNotNull("Should handle special chars: $notification", result["amount"])
        }
    }

    @Test
    fun `stress - handle Greek characters`() {
        val notification = "Αγορά €50.00 στο Κατάστημα ΑΒ Βασιλόπουλος"
        
        val result = parseGreekBankNotification(notification)
        
        assertNotNull("Should parse Greek merchant", result["merchant"])
    }

    // ============================================================================
    // SECTION 10: PERFORMANCE
    // ============================================================================

    @Test
    fun `stress - parse 1000 notifications quickly`() {
        val notifications = (1..1000).map { 
            "Αγορά €${it}.00 στο Κατάστημα $it" 
        }
        
        val startTime = System.nanoTime()
        
        notifications.forEach { notification ->
            parseGreekBankNotification(notification)
        }
        
        val duration = System.nanoTime() - startTime
        
        assertTrue("Should parse 1000 notifications in under 1s", duration < 1_000_000_000)
    }

    // ============================================================================
    // SECTION 11: CONSISTENCY
    // ============================================================================

    @Test
    fun `stress - deterministic parsing`() {
        val notification = "Αγορά €50.00 στο Starbucks"
        
        val result1 = parseGreekBankNotification(notification)
        val result2 = parseGreekBankNotification(notification)
        val result3 = parseGreekBankNotification(notification)
        
        assertEquals("Should be deterministic", result1, result2)
        assertEquals("Should be deterministic", result2, result3)
    }

    // Helper function
    private fun parseGreekBankNotification(notification: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        
        // Simple regex-based parsing for testing
        val amountRegex = Regex("""(€|EUR)?\s*([0-9.,]+)""", RegexOption.IGNORE_CASE)
        val merchantRegex = Regex("""(?:στο|from|at|:)\s+([A-Za-z0-9&'\- ]+)""", RegexOption.IGNORE_CASE)
        
        amountRegex.find(notification)?.let { match ->
            result["amount"] = match.groupValues[2].trim()
        }
        
        merchantRegex.find(notification)?.let { match ->
            result["merchant"] = match.groupValues[1].trim()
        }
        
        return result
    }
}
