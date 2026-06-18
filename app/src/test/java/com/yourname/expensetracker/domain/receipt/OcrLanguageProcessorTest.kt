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
    fun `normalize for language preserves non latin scripts`() {
        assertEquals("ИТОГО 25,50", processor.normalizeForLanguage("  Итого 25,50  ", DetectedLanguage.CYRILLIC))
        assertEquals("المجموع ١٬٢٣٤٫٥٦", processor.normalizeForLanguage("  المجموع ١٬٢٣٤٫٥٦  ", DetectedLanguage.ARABIC))
        assertEquals("合計 １２３４，５６", processor.normalizeForLanguage("  合計　１２３４，５６  ", DetectedLanguage.CJK))
        assertEquals("€١٬٢٣٤٫٥٦", processor.normalizeForLanguage("  €١٬٢٣٤٫٥٦  ", DetectedLanguage.UNKNOWN))
    }

    @Test
    fun `auto normalize returns detected language normalized text and confidence`() {
        val result = processor.autoNormalize("Σύνολο: 25,50")

        assertEquals(DetectedLanguage.GREEK, result.detectedLanguage)
        assertEquals("ΣΥΝΟΛΟ: 25,50", result.normalizedText)
        assertApproxEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `auto normalize keeps unknown amount only unicode text intact`() {
        val result = processor.autoNormalize("  €١٬٢٣٤٫٥٦  ")

        assertEquals(DetectedLanguage.UNKNOWN, result.detectedLanguage)
        assertEquals("€١٬٢٣٤٫٥٦", result.normalizedText)
        assertApproxEquals(0.0, result.confidence, 0.0001)
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

    @Test
    fun `extract amount supports grouped and locale aware greek and latin values`() {
        val greekGrouped = processor.extractAmount("ΣΥΝΟΛΟ 1.234,56", DetectedLanguage.GREEK)
        val latinCommaDecimal = processor.extractAmount("TOTAL 25,50 EUR", DetectedLanguage.LATIN)
        val latinGroupedDecimal = processor.extractAmount("€1,234.56", DetectedLanguage.LATIN)
        val unicodeSpaceGrouped = processor.extractAmount("€1 234,56", DetectedLanguage.LATIN)
        val noAmount = processor.extractAmount("TOTAL --", DetectedLanguage.LATIN)

        assertApproxEquals(1234.56, greekGrouped!!, 0.01)
        assertApproxEquals(25.50, latinCommaDecimal!!, 0.01)
        assertApproxEquals(1234.56, latinGroupedDecimal!!, 0.01)
        assertApproxEquals(1234.56, unicodeSpaceGrouped!!, 0.01)
        assertNull(noAmount)
    }

    @Test
    fun `extract amount preserves unknown latin fallback and arabic separators`() {
        val arabicAmount = processor.extractAmount("€١٬٢٣٤٫٥٦", DetectedLanguage.ARABIC)
        val unknownAmount = processor.extractAmount("Receipt €١٬٢٣٤٫٥٦", DetectedLanguage.UNKNOWN)
        val unknownTotalAmount = processor.extractAmount("TOTAL 99.99", DetectedLanguage.UNKNOWN)
        val unknownEurAmount = processor.extractAmount("Amount 45.70 EUR", DetectedLanguage.UNKNOWN)
        val unknownLowercaseCurrencyAmount = processor.extractAmount("Amount 12.34 usd", DetectedLanguage.UNKNOWN)

        assertApproxEquals(1234.56, arabicAmount!!, 0.01)
        assertApproxEquals(1234.56, unknownAmount!!, 0.01)
        assertApproxEquals(99.99, unknownTotalAmount!!, 0.01)
        assertApproxEquals(45.70, unknownEurAmount!!, 0.01)
        assertApproxEquals(12.34, unknownLowercaseCurrencyAmount!!, 0.01)
    }

    @Test
    fun `extract amount supports unknown labeled arabic digit inputs`() {
        val unknownTotalArabicDigits = processor.extractAmount("TOTAL ١٬٢٣٤٫٥٦", DetectedLanguage.UNKNOWN)
        val unknownAmountArabicDigitsWithCurrency = processor.extractAmount("AMOUNT ١٢٫٣٤ EUR", DetectedLanguage.UNKNOWN)

        assertApproxEquals(1234.56, unknownTotalArabicDigits!!, 0.01)
        assertApproxEquals(12.34, unknownAmountArabicDigitsWithCurrency!!, 0.01)
    }

    @Test
    fun `extract amount keeps non latin fallback parseable inputs working`() {
        val cyrillicFallbackAmount = processor.extractAmount("€١٬٢٣٤٫٥٦", DetectedLanguage.CYRILLIC)

        assertApproxEquals(1234.56, cyrillicFallbackAmount!!, 0.01)
    }

    @Test
    fun `auto normalize keeps full width cjk unknown amount text intact`() {
        val result = processor.autoNormalize("１２３４，５６")

        assertEquals(DetectedLanguage.UNKNOWN, result.detectedLanguage)
        assertEquals("１２３４，５６", result.normalizedText) // Should preserve full-width digits and comma
        assertApproxEquals(0.0, result.confidence, 0.0001) // No letters, so confidence should be 0
    }
}
