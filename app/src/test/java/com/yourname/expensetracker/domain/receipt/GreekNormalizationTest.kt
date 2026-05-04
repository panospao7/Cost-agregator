package com.yourname.expensetracker.domain.receipt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method

import com.yourname.expensetracker.data.repository.MerchantRulesRepository

class GreekNormalizationTest {

    private val parser = ReceiptParser(MerchantRulesRepository(), timeProvider = mock())
    private val normalizeMethod: Method = ReceiptParser::class.java.getDeclaredMethod("normalizeGreekOcr", String::class.java)

    init {
        normalizeMethod.isAccessible = true
    }

    private fun normalize(text: String): String {
        return normalizeMethod.invoke(parser, text) as String
    }

    @Test
    fun `test number fixes`() {
        assertEquals("45.50", normalize("4 5. 5 0"))
        assertEquals("45,00", normalize("45 , 00"))
        assertEquals("123.45", normalize("123 . 45"))
    }

    @Test
    fun `test total keywords variants`() {
        // E -> Σ, etc.
        assertTrue(normalize("EYNONO 50.00").contains("TOTAL_KEY"))
        assertTrue(normalize("ZYNOAO 50.00").contains("TOTAL_KEY"))
        assertTrue(normalize("2YNONO 50.00").contains("TOTAL_KEY"))
        assertTrue(normalize("ZYNOIO 50.00").contains("TOTAL_KEY"))
        assertTrue(normalize("NAHPQTEO 50.00").contains("TOTAL_KEY")) // Payable
        
        // New variants
        assertTrue(normalize("ZYNOAΩ 50.00").contains("TOTAL_KEY"))
        assertTrue(normalize("EYNONO 50.00").contains("TOTAL_KEY"))
        assertTrue(normalize("TEAIKO 50.00").contains("TOTAL_KEY"))
        assertTrue(normalize("TEΛIKO 50.00").contains("TOTAL_KEY"))
    }

    @Test
    fun `test amount keywords`() {
        assertTrue(normalize("ΠΟΣΟ 10.00").contains("AMOUNT_KEY"))
        assertTrue(normalize("nozo 10.00").contains("AMOUNT_KEY"))
    }

    @Test
    fun `test compound keywords`() {
        assertTrue(normalize("ΣΥΝΟΛΙΚΗ ΑΞΙΑ 100").contains("TOTAL_KEY"))
        assertTrue(normalize("ΚΑΘΑΡΗ ΑΞΙΑ 80").contains("SUBTOTAL_KEY"))
    }

    @Test
    fun `test currency normalization`() {
        assertTrue(normalize("50,00 ΕΥΡΩ").contains("EUR"))
        assertTrue(normalize("50,00 ΕΥΡΑ").contains("EUR"))
    }

}