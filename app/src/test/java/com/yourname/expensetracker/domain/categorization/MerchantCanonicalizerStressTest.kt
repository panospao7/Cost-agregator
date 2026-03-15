package com.yourname.expensetracker.domain.categorization

import org.junit.Assert.*
import org.junit.Test

class MerchantCanonicalizerStressTest {
    private val canonicalizer = MerchantCanonicalizer()

    @Test
    fun `removes greeklish corporate suffixes`() {
        val result = canonicalizer.canonicalize("papadopoulos ike")
        assertEquals("papadopoulos", result.canonicalName)
        assertTrue(result.strippedParts.contains("ike"))
    }

    @Test
    fun `removes latin corporate suffixes case-insensitively`() {
        val result = canonicalizer.canonicalize("Company LtD")
        assertEquals("company", result.canonicalName)
    }

    @Test
    fun `removes greek prefixes and suffixes iteratively`() {
        val result = canonicalizer.canonicalize("afoi bakery ike")
        assertEquals("bakery", result.canonicalName)
        assertTrue(result.strippedParts.contains("afoi"))
        assertTrue(result.strippedParts.contains("ike"))
    }

    @Test
    fun `removes greek corporate suffixes in greek script`() {
        val result = canonicalizer.canonicalize("Παπαδόπουλος ΙΚΕ")
        assertEquals("παπαδόπουλος", result.canonicalName)
        assertTrue(result.strippedParts.contains("ικε"))
    }

    @Test
    fun `keeps merchant text when no suffix exists`() {
        val result = canonicalizer.canonicalize("Starbucks")
        assertEquals("starbucks", result.canonicalName)
        assertTrue(result.strippedParts.isEmpty())
    }

    @Test
    fun `normalizes punctuation and whitespace`() {
        val result = canonicalizer.canonicalize("  Foo---Bar,   LTD ")
        assertEquals("foo bar", result.canonicalName)
    }

    @Test
    fun `is deterministic across repeated calls`() {
        val a = canonicalizer.canonicalize("Company IKE LTD")
        val b = canonicalizer.canonicalize("Company IKE LTD")
        assertEquals(a, b)
    }

    @Test
    fun `confidence penalty grows with stripped parts`() {
        val none = canonicalizer.canonicalize("starbucks")
        val one = canonicalizer.canonicalize("starbucks ltd")
        val many = canonicalizer.canonicalize("afoi starbucks ltd ike")
        assertTrue(one.confidencePenalty >= none.confidencePenalty)
        assertTrue(many.confidencePenalty >= one.confidencePenalty)
    }
}
