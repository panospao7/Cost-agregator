package com.yourname.expensetracker.domain.receipt

import com.yourname.expensetracker.assertApproxEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OcrLanguageProcessorTest {

    private val processor = OcrLanguageProcessor()

    @Test
    fun `detect language recognizes greek latin cyrillic and unknown text`() {
        assertEquals(DetectedLanguage.GREEK, processor.detectLanguage("ΣΥΝΟΛΟ 25,50"))
        assertEquals(DetectedLanguage.LATIN, processor.detectLanguage("TOTAL 25.50 EUR"))
        assertEquals(DetectedLanguage.CYRILLIC, processor.detectLanguage("ИТОГО 25,50"))
        assertEquals(DetectedLanguage.UNKNOWN, processor.detectLanguage("12345 !!!"))
    }

    @Test
    fun `normalize greek text uppercases strips accents and trims spacing`() {
        val normalized = processor.normalizeGreekText("  Καφές Σάββατο άέήίόύώ  ")

        assertEquals("ΚΑΦΕΣ ΣΑΒΒΑΤΟ ΑΕΗΙΟΥΩ", normalized)
    }

    @Test
    fun `normalize latin text keeps alphanumeric content and collapses whitespace`() {
        val normalized = processor.normalizeLatinText("  Total: €1,250.50 @Store!  ")

        assertEquals("TOTAL 1 250 50 STORE", normalized)
    }

    @Test
    fun `auto normalize returns detected language normalized text and confidence`() {
        val result = processor.autoNormalize("Σύνολο: 25,50")

        assertEquals(DetectedLanguage.GREEK, result.detectedLanguage)
        assertEquals("ΣΥΝΟΛΟ: 25,50", result.normalizedText)
        assertApproxEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `extract amount handles greek and latin formats`() {
        val greekAmount = processor.extractAmount("ΣΥΝΟΛΟ 25,50", DetectedLanguage.GREEK)
        val latinAmount = processor.extractAmount("TOTAL: $250.50", DetectedLanguage.LATIN)
        val cyrillicFallbackAmount = processor.extractAmount("TOTAL 99.99", DetectedLanguage.CYRILLIC)
        val noAmount = processor.extractAmount("NO TOTAL PRESENT", DetectedLanguage.LATIN)

        assertApproxEquals(25.50, greekAmount!!, 0.01)
        assertApproxEquals(250.50, latinAmount!!, 0.01)
        assertApproxEquals(99.99, cyrillicFallbackAmount!!, 0.01)
        assertNull(noAmount)
    }

    @Test
    fun `extract amount supports eur and usd suffix patterns`() {
        val eur = processor.extractAmount("Amount 45.70 EUR", DetectedLanguage.LATIN)
        val usd = processor.extractAmount("Amount 12.34 USD", DetectedLanguage.LATIN)

        assertApproxEquals(45.70, eur!!, 0.01)
        assertApproxEquals(12.34, usd!!, 0.01)
    }
}
