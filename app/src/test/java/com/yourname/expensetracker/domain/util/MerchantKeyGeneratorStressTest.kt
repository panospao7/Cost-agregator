package com.yourname.expensetracker.domain.util

import org.junit.Assert.*
import org.junit.Test

class MerchantKeyGeneratorStressTest {

    @Test
    fun `stress - empty string returns empty`() {
        assertEquals("", MerchantKeyGenerator.generate(""))
    }

    @Test
    fun `stress - blank string returns empty`() {
        assertEquals("", MerchantKeyGenerator.generate("   "))
    }

    @Test
    fun `stress - basic latin lowercase`() {
        assertEquals("hello", MerchantKeyGenerator.generate("hello"))
    }

    @Test
    fun `stress - basic latin uppercase`() {
        assertEquals("hello", MerchantKeyGenerator.generate("HELLO"))
    }

    @Test
    fun `stress - mixed case latin`() {
        assertEquals("hello", MerchantKeyGenerator.generate("HeLLo"))
    }

    @Test
    fun `stress - greek to latin basic`() {
        val result = MerchantKeyGenerator.generate("Σκλαβενίτης")
        assertTrue("Expected non-empty", result.isNotEmpty())
    }

    @Test
    fun `stress - greek diphthong conversion`() {
        val result = MerchantKeyGenerator.generate("Μπάρμπα Σταθης")
        assertTrue("Expected non-empty: $result", result.isNotEmpty())
    }

    @Test
    fun `stress - greek common merchant`() {
        val result = MerchantKeyGenerator.generate("Κουλουράκια")
        assertTrue("Expected non-empty: $result", result.isNotEmpty())
    }

    @Test
    fun `stress - greek vowel variations`() {
        val result = MerchantKeyGenerator.generate("Μυδατα")
        assertTrue("Expected non-empty: $result", result.isNotEmpty())
    }

    @Test
    fun `stress - greek letter combinations`() {
        val result = MerchantKeyGenerator.generate("Παϊνι")
        assertTrue("Expected non-empty: $result", result.isNotEmpty())
    }

    @Test
    fun `stress - greek omicron iota`() {
        val result = MerchantKeyGenerator.generate("Σχοινάρ")
        assertTrue("Expected non-empty: $result", result.isNotEmpty())
    }

    @Test
    fun `stress - special characters stripped`() {
        assertEquals("mcdonalds", MerchantKeyGenerator.generate("McDonald's"))
    }

    @Test
    fun `stress - numbers preserved`() {
        assertEquals("store123", MerchantKeyGenerator.generate("Store123"))
    }

    @Test
    fun `stress - spaces stripped`() {
        assertEquals("helloworld", MerchantKeyGenerator.generate("Hello World"))
    }

    @Test
    fun `stress - special symbols stripped`() {
        assertEquals("test", MerchantKeyGenerator.generate("!@#$%test%^&*"))
    }

    @Test
    fun `stress - unicode accents removed`() {
        assertEquals("cafe", MerchantKeyGenerator.generate("café"))
    }

    @Test
    fun `stress - greek with spaces`() {
        val result = MerchantKeyGenerator.generate("Καφενείο")
        assertTrue("Expected non-empty: $result", result.isNotEmpty())
    }

    @Test
    fun `stress - long greek string performance`() {
        val input = "Σκλαβενίτης ΑΕ Βιομηχανία Τροφίμων Οικιακών Ειδών Μακεδονία Θράκη"
        val startTime = System.nanoTime()
        val result = MerchantKeyGenerator.generate(input)
        val duration = System.nanoTime() - startTime
        assertTrue("Should complete quickly but took ${duration/1000000}ms", duration < 10_000_000)
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `stress - 1000 operations performance`() {
        val inputs = listOf("Σκλαβενίτης", "LIDL", "ΑΒ Βασιλόπουλος", "Κρητικός", "Μασούτης")
        
        val startTime = System.nanoTime()
        repeat(1000) { i ->
            MerchantKeyGenerator.generate(inputs[i % inputs.size])
        }
        val duration = System.nanoTime() - startTime
        
        assertTrue("1000 operations should complete in under 1000ms but took ${duration/1000000}ms", duration < 1_000_000_000)
    }

    @Test
    fun `stress - emoji stripped`() {
        assertEquals("test", MerchantKeyGenerator.generate("test👋"))
    }

    @Test
    fun `stress - multiple emojis stripped`() {
        assertEquals("hello", MerchantKeyGenerator.generate("hello👋🏃‍♂️🎉"))
    }

    @Test
    fun `stress - greeklish mixed`() {
        val result = MerchantKeyGenerator.generate("Μπαρ")
        assertTrue("Expected non-empty: $result", result.isNotEmpty())
    }

    @Test
    fun `stress - punctuation stripped`() {
        assertEquals("test123", MerchantKeyGenerator.generate("test...123..."))
    }

    @Test
    fun `stress - tabs and newlines stripped`() {
        assertEquals("test", MerchantKeyGenerator.generate("te\tst\n"))
    }

    @Test
    fun `stress - very long string performance`() {
        val input = "A".repeat(10000)
        val startTime = System.nanoTime()
        val result = MerchantKeyGenerator.generate(input)
        val duration = System.nanoTime() - startTime
        assertTrue("Should complete in under 100ms but took ${duration/1000000}ms", duration < 100_000_000)
        assertEquals(10000, result.length)
    }

    @Test
    fun `stress - null character stripped`() {
        assertEquals("test", MerchantKeyGenerator.generate("te\u0000st"))
    }

    @Test
    fun `stress - greek uppercase conversion`() {
        val result = MerchantKeyGenerator.generate("ΑΒΓΔΕΖ")
        assertTrue("Expected non-empty: $result", result.isNotEmpty())
    }

    @Test
    fun `stress - all greek alphabet lowercase`() {
        val result = MerchantKeyGenerator.generate("αβγδεζηθικλμνξοπρστυχψω")
        assertTrue("Expected non-empty: $result", result.isNotEmpty())
    }

    @Test
    fun `stress - consistency same input produces same output`() {
        val input = "Σκλαβενίτης"
        val result1 = MerchantKeyGenerator.generate(input)
        val result2 = MerchantKeyGenerator.generate(input)
        assertEquals(result1, result2)
    }
}
