package com.yourname.expensetracker.domain.categorization

import org.junit.Assert.*
import org.junit.Test

class MerchantCanonicalizerStressTest {

    // ============================================================================
    // SECTION 1: GREEK CORPORATE SUFFIX REMOVAL
    // ============================================================================

    @Test
    fun `stress - remove Greek IKE suffix`() {
        val merchant = "Παπαδόπουλος ΙΚΕ"
        val canonicalized = canonicalizeGreekMerchant(merchant)
        
        assertFalse("Should remove ΙΚΕ", canonicalized.contains("ΙΚΕ"))
        assertTrue("Should keep name", canonicalized.contains("Παπαδόπουλος"))
    }

    @Test
    fun `stress - remove Greek EPE suffix`() {
        val merchant = "Εταιρία ΕΠΕ"
        val canonicalized = canonicalizeGreekMerchant(merchant)
        
        assertFalse("Should remove ΕΠΕ", canonicalized.contains("ΕΠΕ"))
    }

    @Test
    fun `stress - remove Greek ΑΕ suffix`() {
        val merchant = "Τράπεζα ΑΕ"
        val canonicalized = canonicalizeGreekMerchant(merchant)
        
        assertFalse("Should remove ΑΕ", canonicalized.contains("ΑΕ"))
    }

    @Test
    fun `stress - remove Greek ΟΕ suffix`() {
        val merchant = "Κατάστημα ΟΕ"
        val canonicalized = canonicalizeGreekMerchant(merchant)
        
        assertFalse("Should remove ΟΕ", canonicalized.contains("ΟΕ"))
    }

    @Test
    fun `stress - remove Greek ΕΕ suffix`() {
        val merchant = "Επιχείρηση ΕΕ"
        val canonicalized = canonicalizeGreekMerchant(merchant)
        
        assertFalse("Should remove ΕΕ", canonicalized.contains("ΕΕ"))
    }

    // ============================================================================
    // SECTION 2: LATIN CORPORATE SUFFIX REMOVAL
    // ============================================================================

    @Test
    fun `stress - remove Ltd suffix`() {
        val merchant = "Company Ltd"
        val canonicalized = canonicalizeLatinMerchant(merchant)
        
        assertFalse("Should remove Ltd", canonicalized.contains("Ltd"))
    }

    @Test
    fun `stress - remove Inc suffix`() {
        val merchant = "Corporation Inc"
        val canonicalized = canonicalizeLatinMerchant(merchant)
        
        assertFalse("Should remove Inc", canonicalized.contains("Inc"))
    }

    @Test
    fun `stress - remove LLC suffix`() {
        val merchant = "Business LLC"
        val canonicalized = canonicalizeLatinMerchant(merchant)
        
        assertFalse("Should remove LLC", canonicalized.contains("LLC"))
    }

    @Test
    fun `stress - remove Corp suffix`() {
        val merchant = "Enterprise Corp"
        val canonicalized = canonicalizeLatinMerchant(merchant)
        
        assertFalse("Should remove Corp", canonicalized.contains("Corp"))
    }

    @Test
    fun `stress - remove SA suffix`() {
        val merchant = "Societe SA"
        val canonicalized = canonicalizeLatinMerchant(merchant)
        
        assertFalse("Should remove SA", canonicalized.contains("SA"))
    }

    // ============================================================================
    // SECTION 3: MIXED GREEK-LATIN SUFFIXES
    // ============================================================================

    @Test
    fun `stress - handle mixed Greek-Latin merchant names`() {
        val merchant = "Coffee Shop IKE"
        val canonicalized = canonicalize(merchant)
        
        assertFalse("Should remove IKE", canonicalized.contains("IKE"))
    }

    @Test
    fun `stress - handle Greek name with Latin suffix`() {
        val merchant = "Σκλαβενίτης IKE"
        val canonicalized = canonicalize(merchant)
        
        assertFalse("Should remove IKE", canonicalized.contains("IKE"))
        assertTrue("Should keep Greek name", canonicalized.contains("Σκλαβενίτης"))
    }

    // ============================================================================
    // SECTION 4: MULTIPLE SUFFIXES
    // ============================================================================

    @Test
    fun `stress - remove multiple Greek suffixes`() {
        val merchant = "Εταιρία ΑΦΟΙ ΙΚΕ"
        val canonicalized = canonicalizeGreekMerchant(merchant)
        
        assertFalse("Should remove ΑΦΟΙ", canonicalized.contains("ΑΦΟΙ"))
        assertFalse("Should remove ΙΚΕ", canonicalized.contains("ΙΚΕ"))
    }

    @Test
    fun `stress - remove nested suffixes`() {
        val merchant = "Company Ltd Inc"
        val canonicalized = canonicalizeLatinMerchant(merchant)
        
        assertFalse("Should remove Ltd", canonicalized.contains("Ltd"))
        assertFalse("Should remove Inc", canonicalized.contains("Inc"))
    }

    // ============================================================================
    // SECTION 5: CASE VARIATIONS
    // ============================================================================

    @Test
    fun `stress - handle uppercase Greek suffixes`() {
        val merchant = "ΚΑΤΑΣΤΗΜΑ ΙΚΕ"
        val canonicalized = canonicalizeGreekMerchant(merchant)
        
        assertFalse("Should remove uppercase ΙΚΕ", canonicalized.contains("ΙΚΕ"))
    }

    @Test
    fun `stress - handle lowercase Greek suffixes`() {
        val merchant = "κατάστημα ικε"
        val canonicalized = canonicalizeGreekMerchant(merchant)
        
        assertFalse("Should remove lowercase ικε", canonicalized.contains("ικε"))
    }

    @Test
    fun `stress - handle mixed case Latin suffixes`() {
        val suffixes = listOf("ltd", "Ltd", "LTD", "LtD", "lTd")
        
        suffixes.forEach { suffix ->
            val merchant = "Company $suffix"
            val canonicalized = canonicalizeLatinMerchant(merchant)
            assertFalse("Should remove $suffix", canonicalized.contains(suffix, ignoreCase = true))
        }
    }

    // ============================================================================
    // SECTION 6: WHITESPACE HANDLING
    // ============================================================================

    @Test
    fun `stress - handle extra spaces before suffix`() {
        val merchant = "Company   Ltd"
        val canonicalized = canonicalizeLatinMerchant(merchant)
        
        assertFalse("Should remove Ltd", canonicalized.contains("Ltd"))
        assertFalse("Should not have multiple spaces", canonicalized.contains("  "))
    }

    @Test
    fun `stress - handle no space before suffix`() {
        val merchant = "CompanyLtd"
        val canonicalized = canonicalizeLatinMerchant(merchant)
        
        // Should not remove suffix without space
        assertTrue("Should keep CompanyLtd", canonicalized == "CompanyLtd")
    }

    @Test
    fun `stress - trim trailing whitespace`() {
        val merchant = "Company Ltd   "
        val canonicalized = canonicalizeLatinMerchant(merchant)
        
        assertFalse("Should not end with space", canonicalized.endsWith(" "))
    }

    // ============================================================================
    // SECTION 7: EDGE CASES
    // ============================================================================

    @Test
    fun `stress - handle merchant with only suffix`() {
        val merchant = "IKE"
        val canonicalized = canonicalizeGreekMerchant(merchant)
        
        assertTrue("Should handle suffix-only", canonicalized.isNotEmpty())
    }

    @Test
    fun `stress - handle empty merchant`() {
        val merchant = ""
        val canonicalized = canonicalize(merchant)
        
        assertEquals("Should return empty", "", canonicalized)
    }

    @Test
    fun `stress - handle null merchant`() {
        val merchant: String? = null
        val canonicalized = canonicalize(merchant ?: "")
        
        assertEquals("Should handle null", "", canonicalized)
    }

    @Test
    fun `stress - handle merchant without suffix`() {
        val merchant = "Starbucks"
        val canonicalized = canonicalize(merchant)
        
        assertEquals("Should return unchanged", "Starbucks", canonicalized)
    }

    // ============================================================================
    // SECTION 8: SPECIAL CHARACTERS
    // ============================================================================

    @Test
    fun `stress - handle merchant with special characters`() {
        val merchant = "McDonald's Ltd"
        val canonicalized = canonicalizeLatinMerchant(merchant)
        
        assertFalse("Should remove Ltd", canonicalized.contains("Ltd"))
        assertTrue("Should keep apostrophe", canonicalized.contains("'"))
    }

    @Test
    fun `stress - handle merchant with numbers`() {
        val merchant = "7-Eleven Inc"
        val canonicalized = canonicalizeLatinMerchant(merchant)
        
        assertFalse("Should remove Inc", canonicalized.contains("Inc"))
        assertTrue("Should keep 7-Eleven", canonicalized.contains("7-Eleven"))
    }

    // ============================================================================
    // SECTION 9: PERFORMANCE
    // ============================================================================

    @Test
    fun `stress - canonicalize 10000 merchants quickly`() {
        val merchants = (1..10000).map { "Merchant $it IKE Ltd" }
        
        val startTime = System.nanoTime()
        
        merchants.forEach { merchant ->
            canonicalize(merchant)
        }
        
        val duration = System.nanoTime() - startTime
        
        assertTrue("Should process 10000 merchants in under 1s", duration < 1_000_000_000)
    }

    // ============================================================================
    // SECTION 10: CONSISTENCY
    // ============================================================================

    @Test
    fun `stress - deterministic results`() {
        val merchant = "Company IKE Ltd"
        
        val result1 = canonicalize(merchant)
        val result2 = canonicalize(merchant)
        val result3 = canonicalize(merchant)
        
        assertEquals("Should be deterministic", result1, result2)
        assertEquals("Should be deterministic", result2, result3)
    }

    // Helper functions
    private fun canonicalize(merchant: String): String {
        return canonicalizeGreekMerchant(canonicalizeLatinMerchant(merchant))
    }

    private fun canonicalizeGreekMerchant(merchant: String): String {
        val suffixes = listOf("ΙΚΕ", "ΕΠΕ", "ΑΕ", "ΟΕ", "ΕΕ", "ΑΦΟΙ")
        var result = merchant.trim()
        
        suffixes.forEach { suffix ->
            val regex = Regex("\\s+$suffix\b", RegexOption.IGNORE_CASE)
            result = result.replace(regex, "")
        }
        
        return result.trim()
    }

    private fun canonicalizeLatinMerchant(merchant: String): String {
        val suffixes = listOf("Ltd", "Inc", "LLC", "Corp", "SA", "GmbH", "BV", "NV")
        var result = merchant.trim()
        
        suffixes.forEach { suffix ->
            val regex = Regex("\\s+$suffix\\.?$", RegexOption.IGNORE_CASE)
            result = result.replace(regex, "")
        }
        
        return result.trim()
    }
}
