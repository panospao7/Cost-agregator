package com.yourname.expensetracker.domain.quickadd

import com.yourname.expensetracker.domain.currency.SupportedCurrency
import com.yourname.expensetracker.domain.util.AmountUtils
import com.yourname.expensetracker.ui.util.AmountInputSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.Locale

/**
 * Golden suite for the quick-add parser (C1): 132 inline cases, taxonomy A-M of the domain spec
 * section 12. Expected values are taken from the spec and are never adjusted to match the code
 * (plan N11). The parser itself never calls `AmountUtils.parseAmount` or `AmountInputSanitizer`;
 * this test calls both only to assert INV-5 on every amount Value.
 *
 * The owner-approved currency fail-closed rules (item 58) are pinned separately in
 * [QuickAddParserForeignCurrencyTest] so the 132-case table and its section 12 counts stay intact.
 */
class QuickAddParserGoldenTest {

    private val parser = QuickAddParser()
    private val fixtures = QuickAddParserGoldenFixtures
    private val cases = fixtures.cases

    @Test
    fun taxonomyCountsMatchSpecSection12() {
        val actual: Map<Char, Int> = cases.groupingBy { it.group }.eachCount()
        assertEquals(fixtures.expectedCounts, actual)
        assertEquals(QuickAddParserGoldenFixtures.EXPECTED_TOTAL, cases.size)
        assertEquals("duplicate case ids", cases.size, cases.map { it.id }.toSet().size)
    }

    @Test
    fun everyGoldenCaseMatchesItsExpectedFields() {
        val failures = ArrayList<String>()
        for (case in cases) {
            val actual = fixtures.render(parser.parse(case.ctx.input(case.line)), case.ctx.zone)
            val expected = case.expectedRendering()
            if (expected != actual) {
                failures.add("${case.id} [${case.ctx.name}] line=${printable(case.line)}\n  expected: $expected\n  actual:   $actual")
            }
        }
        assertTrue("${failures.size} golden case(s) differ:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun amountValuesRoundTripSanitizerAndParseAmount_INV5() {
        val original = Locale.getDefault()
        try {
            var checked = 0
            for (locale in listOf(Locale.US, Locale.forLanguageTag("el-GR"), Locale.forLanguageTag("tr-TR"), Locale.GERMANY)) {
                Locale.setDefault(locale)
                for (case in cases) {
                    val result = parser.parse(case.ctx.input(case.line))
                    val amount = result.amount as? FieldParse.Value ?: continue
                    val text = amount.value.text
                    assertTrue("${case.id}: $text is not canonical", CANONICAL_AMOUNT.matches(text))
                    assertEquals("${case.id}: sanitize($text) under $locale", text, AmountInputSanitizer.sanitize(text))
                    assertEquals(
                        "${case.id}: parseAmount($text)",
                        BigDecimal(text).toDouble(),
                        AmountUtils.parseAmount(text) ?: Double.NaN,
                        0.0
                    )
                    assertEquals(
                        "${case.id}: AmountInputSanitizer.parseOrNull($text) under $locale",
                        BigDecimal(text).toDouble(),
                        AmountInputSanitizer.parseOrNull(text) ?: Double.NaN,
                        0.0
                    )
                    val value = BigDecimal(text)
                    assertTrue("${case.id}: $text outside [0.01, 1000000.00]", value >= BigDecimal("0.01") && value <= BigDecimal("1000000.00"))
                    if (locale == Locale.US) checked++
                }
            }
            assertTrue("expected amount Values in the golden table, found $checked", checked >= 60)
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun dateValuesStayOnTheirLocalDateAndNeverPassNow_INV6() {
        var checked = 0
        for (case in cases) {
            val result = parser.parse(case.ctx.input(case.line))
            val date = result.date as? FieldParse.Value ?: continue
            val resolved = date.value
            val atZone = Instant.ofEpochMilli(resolved.epochMs).atZone(case.ctx.zone)
            assertEquals("${case.id}: epochMs must be on localDate", resolved.localDate, atZone.toLocalDate())
            assertTrue("${case.id}: resolved date is after now", resolved.epochMs <= case.ctx.nowEpochMs)
            assertEquals("${case.id}: whole seconds", 0L, resolved.epochMs % 1000L)
            checked++
        }
        assertTrue("expected date Values in the golden table, found $checked", checked >= 30)
    }

    @Test
    fun parseResultDoesNotExposeTheLineOrMerchantInToString() {
        val line = "secretmerchant 12,50 χθες"
        val input = QuickAddParserGoldenFixtures.CTX_A.input(line)
        assertTrue(!input.toString().contains("secretmerchant"))
        assertTrue(!input.toString().contains(line))
        val result = parser.parse(input)
        assertTrue(result.merchant is FieldParse.Value)
        assertTrue(!result.merchant.toString().contains("secretmerchant"))
        val rendered = result.toString()
        assertTrue("result.toString() must not carry the merchant", !rendered.contains("secretmerchant"))
        assertTrue("result.toString() must not carry the line", !rendered.contains(line))
    }

    @Test
    fun requiredAcceptanceLinesAreInTheTable() {
        val lines = cases.map { it.line }.toSet()
        val required = listOf(
            // AE-1..AE-9 (parser level)
            "καφές 3,50 χθες", "groceries 42.30 lidl", "taxi 15€ σήμερα κάρτα", "kafes 3.50", "dinner 20 usd",
            "2 καφέδες 7", "lidl 1.500", "lidl 3,500", "taxi 12 αύριο", "καφές 3,50",
            // AE-10 (NBSP, narrow NBSP and thin space grouping, then ASCII-space grouping)
            "taxi 3,5", "tv 1.234,56", "taxi 1,234.56", "taxi 1${NBSP}234,56", "taxi 1${NARROW_NBSP}234,56",
            "taxi 1${THIN_SPACE}234,56", "taxi 1 234,56", "taxi 3.5055",
            "taxi 0,00", "taxi ${MINUS_SIGN}5", "taxi 1000000", "taxi 1000000,01", "taxi .50", "taxi 0150",
            // AE-11
            "lidl 12,40 21:30",
            // AE-12 ("abc 20" is pinned here; the superseded "20 abc" is pinned in QuickAddParserForeignCurrencyTest)
            "taxi 15 ευρώ", "taxi 15ε", "EUR 15", "evro 15", "€ 15", "\$20 cab", "100kr", "20 thb",
            "thb dinner 20", "¥500 cab",
            // AE-13
            "taxi 9 Δευτέρα", "taxi 9 τη Δευτέρα", "taxi 9 Πέμπτη", "taxi 9 Σάββατο", "taxi 9 Παρασκευή",
            "taxi 9 last monday", "taxi 9 χθες 15/10",
            // AE-14
            "taxi 5 28/12", "taxi 5 3/10", "taxi 5 2/10", "taxi 5 4/1", "taxi 5 31/12/26", "taxi 5 5/1/27",
            "taxi 5 31/4", "taxi 5 5.3",
            // AE-15
            "καφές 2 χθες", "χθες",
            // AE-16
            "taxi 5 με κάρτα", "taxi 5 ΚΑΡΤΑ", "taxi 5 metrita", "taxi 5 card cash",
            // AE-17
            "gifts kids 30", "legal & gov 30 fee", "GROCERIES 5", "kafes 3.50", "taxi home 12",
            // AE-18
            "", "!!! ;", "a1 b2"
        )
        val missing = required.filterNot { it in lines }
        assertTrue("acceptance lines missing from the golden table: ${missing.map { printable(it) }}", missing.isEmpty())
        assertTrue("a 201-char line is required", cases.any { it.line.length == 201 })
    }

    // ----------------------------------------------------------------- canonicaliser

    private data class CanonRow(val core: String, val expected: String, val parseAmountNull: Boolean = false)

    private fun v(text: String) = "V($text)"
    private fun f(reason: FlagReason) = "F(${reason.name})"

    private fun renderAmount(parse: FieldParse<AmountText>): String = when (parse) {
        is FieldParse.Value -> v(parse.value.text)
        is FieldParse.Flagged -> f(parse.reason)
        FieldParse.Absent -> "-"
    }

    @Test
    fun canonicalizeIsTheSingleOwnerOfTheAmountRule() {
        val rows = listOf(
            CanonRow("12,50", v("12.50")),
            CanonRow("12.50", v("12.50")),
            CanonRow("3,5", v("3.50")),
            CanonRow("5", v("5.00")),
            CanonRow("0,5", v("0.50")),
            CanonRow("1234,56", v("1234.56")),
            CanonRow("1.234,56", v("1234.56")),
            CanonRow("1,234.56", v("1234.56")),
            // Spec 4.5 R7: 1,234,567 is above the 1,000,000 limit, so it is flagged, never a Value.
            CanonRow("1,234,567", f(FlagReason.AMOUNT_OVER_LIMIT)),
            CanonRow("1,000,000", v("1000000.00")),
            CanonRow("1.234,5", v("1234.50")),
            CanonRow("1000000", v("1000000.00")),
            CanonRow("1000000,00", v("1000000.00")),
            CanonRow("", "-"),
            CanonRow("1.500", f(FlagReason.AMOUNT_AMBIGUOUS_SEPARATOR)),
            CanonRow("3,500", f(FlagReason.AMOUNT_AMBIGUOUS_SEPARATOR)),
            CanonRow("1,234", f(FlagReason.AMOUNT_AMBIGUOUS_SEPARATOR)),
            CanonRow("0,500", f(FlagReason.AMOUNT_AMBIGUOUS_SEPARATOR)),
            CanonRow("12.345", f(FlagReason.AMOUNT_AMBIGUOUS_SEPARATOR)),
            CanonRow("3.5055", f(FlagReason.AMOUNT_UNCLEAR)),
            CanonRow("1.234,567", f(FlagReason.AMOUNT_UNCLEAR)),
            CanonRow("1.234.567", f(FlagReason.AMOUNT_UNCLEAR), parseAmountNull = true),
            CanonRow("1,23.4", f(FlagReason.AMOUNT_UNCLEAR), parseAmountNull = true),
            CanonRow("12,34,567", f(FlagReason.AMOUNT_UNCLEAR), parseAmountNull = true),
            CanonRow("1.2.3", f(FlagReason.AMOUNT_UNCLEAR), parseAmountNull = true),
            CanonRow(".50", f(FlagReason.AMOUNT_UNCLEAR)),
            CanonRow("+15", f(FlagReason.AMOUNT_UNCLEAR)),
            CanonRow("15%", f(FlagReason.AMOUNT_UNCLEAR)),
            CanonRow("1:2", f(FlagReason.AMOUNT_UNCLEAR)),
            CanonRow("10-15", f(FlagReason.AMOUNT_UNCLEAR)),
            CanonRow("0150", f(FlagReason.AMOUNT_UNCLEAR)),
            CanonRow("00", f(FlagReason.AMOUNT_UNCLEAR)),
            // Arabic-Indic digits U+0661 U+0662 are not ASCII digits.
            CanonRow("$ARABIC_INDIC_ONE$ARABIC_INDIC_TWO", f(FlagReason.AMOUNT_UNCLEAR)),
            CanonRow("15💰", f(FlagReason.AMOUNT_UNCLEAR)),
            CanonRow("0", f(FlagReason.AMOUNT_NOT_POSITIVE)),
            CanonRow("0,00", f(FlagReason.AMOUNT_NOT_POSITIVE)),
            CanonRow("-5", f(FlagReason.AMOUNT_NOT_POSITIVE)),
            // U+2212 minus sign and U+2013 en dash are minus-class characters.
            CanonRow("${MINUS_SIGN}5", f(FlagReason.AMOUNT_NOT_POSITIVE)),
            CanonRow("${EN_DASH}15", f(FlagReason.AMOUNT_NOT_POSITIVE)),
            CanonRow("1000000,01", f(FlagReason.AMOUNT_OVER_LIMIT)),
            CanonRow("1000000.01", f(FlagReason.AMOUNT_OVER_LIMIT)),
            CanonRow("99999999999", f(FlagReason.AMOUNT_OVER_LIMIT))
        )
        val failures = ArrayList<String>()
        for (row in rows) {
            val parse = QuickAddAmountRules.canonicalize(row.core)
            val actual = renderAmount(parse)
            if (actual != row.expected) failures.add("canonicalize(${printable(row.core)}) expected ${row.expected} but was $actual")
            if (parse is FieldParse.Value) {
                val text = parse.value.text
                if (AmountInputSanitizer.sanitize(text) != text) failures.add("sanitize(${row.core}) changes $text")
                val lenient = AmountUtils.parseAmount(row.core)
                if (lenient == null || lenient != text.toDouble()) {
                    failures.add("parseAmount(${row.core}) = $lenient disagrees with $text")
                }
            }
            if (row.parseAmountNull) {
                assertNull("parseAmount(${row.core}) must reject what R4 flags", AmountUtils.parseAmount(row.core))
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    /**
     * Brute-force agreement of the canonicaliser with the app's lenient reader. The documented
     * relation is one-directional: a Value implies `parseAmount` agrees on the same number. The
     * converse is not required, because the canonicaliser deliberately flags shapes the lenient
     * reader accepts (ambiguous 3-digit groups, leading zeros, more than 2 fraction digits, values
     * over the limit). Covered: every string over [0-9 . ,] up to length 4 and over {0,1,2,5,.,,}
     * up to length 7, so grouping shapes with several groups are reached.
     */
    @Test
    fun canonicalizeValuesAlwaysAgreeWithParseAmountOnEveryShortString() {
        var total = 0
        var values = 0
        val failures = ArrayList<String>()

        fun check(s: String) {
            total++
            val parse = QuickAddAmountRules.canonicalize(s)
            when (parse) {
                is FieldParse.Value -> {
                    values++
                    val text = parse.value.text
                    if (!CANONICAL_AMOUNT.matches(text)) failures.add("canonicalize($s) = $text is not canonical")
                    val lenient = AmountUtils.parseAmount(s)
                    if (lenient == null || lenient != BigDecimal(text).toDouble()) {
                        failures.add("canonicalize($s) = $text but parseAmount($s) = $lenient")
                    }
                }
                is FieldParse.Flagged ->
                    if (parse.reason == FlagReason.AMOUNT_AMBIGUOUS_SEPARATOR) {
                        val separators = s.count { it == '.' || it == ',' }
                        val tail = s.length - s.indexOfFirst { it == '.' || it == ',' } - 1
                        if (separators != 1 || tail != 3) failures.add("canonicalize($s) is ambiguous but is not one separator plus 3 digits")
                    }
                FieldParse.Absent -> if (s.isNotEmpty()) failures.add("canonicalize($s) is Absent for a non-empty core")
            }
        }

        fun enumerate(alphabet: String, maxLength: Int) {
            fun walk(prefix: StringBuilder) {
                check(prefix.toString())
                if (prefix.length == maxLength) return
                for (c in alphabet) {
                    prefix.append(c)
                    walk(prefix)
                    prefix.setLength(prefix.length - 1)
                }
            }
            walk(StringBuilder())
        }

        enumerate("0123456789.,", 4)
        enumerate("0125.,", 7)

        assertTrue("canonicalize disagrees with parseAmount:\n" + failures.take(20).joinToString("\n"), failures.isEmpty())
        assertTrue("expected many canonical Values, found $values of $total", values > 1000)
    }

    @Test
    fun canonicalizeIsLocaleIndependent() {
        val original = Locale.getDefault()
        try {
            val baseline = listOf("12,50", "1.234,56", "1,234.56", "1.500", "3.5055", "0,00").map {
                renderAmount(QuickAddAmountRules.canonicalize(it))
            }
            for (tag in listOf("tr-TR", "el-GR", "de-DE", "en-US")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                val again = listOf("12,50", "1.234,56", "1,234.56", "1.500", "3.5055", "0,00").map {
                    renderAmount(QuickAddAmountRules.canonicalize(it))
                }
                assertEquals("canonicalize under $tag", baseline, again)
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    // --------------------------------------------------------------- vocabulary drift

    @Test
    fun embeddedCatalogCodesMatchTheAppCatalog() {
        assertEquals(28, QuickAddVocabulary.CATALOG_CODES.size)
        assertEquals(
            SupportedCurrency.catalog.map { it.code }.toSet(),
            QuickAddVocabulary.CATALOG_CODES.toSet()
        )
        for (currency in SupportedCurrency.catalog) {
            val key = QuickAddVocabulary.fold(currency.symbol)
            val marker = QuickAddVocabulary.MARKERS_LONGEST_FIRST.firstOrNull { it.key == key }
            assertTrue("no marker for symbol ${currency.symbol}", marker != null)
            assertTrue("symbol ${currency.symbol} must cover ${currency.code}", currency.code in marker!!.codes)
            val code = QuickAddVocabulary.MARKERS_LONGEST_FIRST.first { it.key == currency.code.lowercase(Locale.ROOT) }
            assertEquals(setOf(currency.code), code.codes)
            assertTrue("catalog ISO code ${currency.code} must be detachable", code.detachable)
        }
        assertTrue("non-catalog list must exclude catalog codes", QuickAddVocabulary.NON_CATALOG_CODES.none { it.uppercase(Locale.ROOT) in QuickAddVocabulary.CATALOG_CODES })
        assertTrue("thb" in QuickAddVocabulary.NON_CATALOG_CODES)
    }

    private fun printable(s: String): String =
        s.map { c ->
            if (c.code in 32..126) c.toString() else "\\u" + Integer.toHexString(c.code).uppercase().padStart(4, '0')
        }.joinToString("")

    private companion object {
        val CANONICAL_AMOUNT = Regex("^\\d{1,7}\\.\\d{2}$")

        // Invisible and look-alike characters are built from code points, so none sits raw in this source.
        val NBSP = 0x00A0.toChar()
        val NARROW_NBSP = 0x202F.toChar()
        val THIN_SPACE = 0x2009.toChar()
        val MINUS_SIGN = 0x2212.toChar()
        val EN_DASH = 0x2013.toChar()
        val ARABIC_INDIC_ONE = 0x0661.toChar()
        val ARABIC_INDIC_TWO = 0x0662.toChar()
    }
}
