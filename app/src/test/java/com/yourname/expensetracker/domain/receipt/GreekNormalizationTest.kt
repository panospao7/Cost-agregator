package com.yourname.expensetracker.domain.receipt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method

class GreekNormalizationTest {

    private val parser = ReceiptParser()
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
        assertEquals("45.00", normalize("45 , 00"))
        assertEquals("123.45", normalize("123 . 45"))
    }

    @Test
    fun `test total keywords variants`() {
        // E -> Σ, etc.
        assertTrue(normalize("EYNONO 50.00").contains("TOTAL_KEY"))
        assertTrue(normalize("ZYNOAO 50.00").contains("TOTAL_KEY"))
        assertTrue(normalize("2YNONO 50.00").contains("TOTAL_KEY"))
        assertTrue(normalize("IYNOAO 50.00").contains("TOTAL_KEY"))
        assertTrue(normalize("ZYNOIO 50.00").contains("TOTAL_KEY"))
        assertTrue(normalize("NAHPQTEO 50.00").contains("TOTAL_KEY")) // Payable
    }

    @Test
    fun `test amount keywords`() {
        assertTrue(normalize("ΠΟΣΟ 10.00").contains("AMOUNT_KEY"))
        assertTrue(normalize("POSO 10.00").contains("AMOUNT_KEY"))
        assertTrue(normalize("nozo 10.00").contains("AMOUNT_KEY"))
    }

    @Test
    fun `test compound keywords`() {
        assertTrue(normalize("ΣΥΝΟΛΙΚΗ ΑΞΙΑ 100").contains("TOTAL_KEY"))
        assertTrue(normalize("ΚΑΘΑΡΗ ΑΞΙΑ 80").contains("SUBTOTAL_KEY"))
    }

    @Test
    fun `test date fixes`() {
        assertEquals("16-04-2017", normalize("16-D4-2017"))
        assertEquals("16-04-2017", normalize("16/D4/2017"))
        assertEquals("16-04-2017", normalize("16-O4-2017"))
    }

    @Test
    fun `test currency cleanup`() {
        // EUR should be removed but replaced with empty string or space to allow number parsing
        // In the new implementation we replace EUR with "" at the end.
        val normalized = normalize("10.00 EUR")
        assertTrue(!normalized.contains("EUR"))
        assertTrue(normalized.trim() == "10.00")
    }
}
