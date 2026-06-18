package com.yourname.expensetracker.data.email.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class EmailReceiptParserTest {

    private val parser = TestEmailParser()

    @Test
    fun `cleanHtml preserves meaningful line breaks and decodes entities`() {
        val html = """
            <div>Order&nbsp;Total:&nbsp;&euro;12,34</div>
            <p>Line&#10;Two &amp; Three</p>
            <table><tr><td>Item</td><td>Qty</td></tr></table>
        """.trimIndent()

        val cleaned = parser.exposeCleanHtml(html)

        assertEquals("Order Total: €12,34\nLine\nTwo & Three\nItem Qty", cleaned)
    }

    @Test
    fun `parseLocalizedAmount handles comma decimal and grouped values`() {
        assertEquals(1234.56, parser.exposeParseLocalizedAmount("€1.234,56") ?: 0.0, 0.001)
        assertEquals(1234.56, parser.exposeParseLocalizedAmount("1 234,56 EUR") ?: 0.0, 0.001)
        assertEquals(1234.56, parser.exposeParseLocalizedAmount("1,234.56") ?: 0.0, 0.001)
    }

    @Test
    fun `parseLocalizedDate supports non english month names`() {
        val parsed = parser.exposeParseLocalizedDate("15 mars 2026")

        assertNotNull(parsed)
        assertEquals(expectedUtcMillis("yyyy-MM-dd", "2026-03-15"), parsed)
    }

    private fun expectedUtcMillis(pattern: String, value: String): Long {
        return LocalDate.parse(value, DateTimeFormatter.ofPattern(pattern, Locale.US))
            .atStartOfDay(ZoneId.of("UTC"))
            .toInstant()
            .toEpochMilli()
    }

    private class TestEmailParser : BaseEmailParser() {
        override fun canParse(sender: String, subject: String, body: String): Boolean = false

        override fun parse(emailBody: String, receivedAt: Long): ParsedEmailReceipt? = null

        fun exposeCleanHtml(text: String): String = cleanHtml(text)

        fun exposeParseLocalizedAmount(text: String): Double? = parseLocalizedAmount(text)

        fun exposeParseLocalizedDate(text: String): Long? = parseLocalizedDate(text)
    }
}
