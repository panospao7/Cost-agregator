package com.yourname.expensetracker.domain.util

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Locale
import kotlin.random.Random

/**
 * Exact-value tables for [AmountUtils]. Every parse row compares with delta 0.0 (the production
 * path is String.toDouble, so the expected literal is the identical double) and every table is
 * size-guarded so rows cannot be silently dropped. Invisible/unicode characters are written as
 * escapes (U+00A0 NBSP, U+202F NNBSP, U+2212 minus sign, U+2011 non-breaking hyphen,
 * U+FE0F + U+20E3 keycap sequences). Rows labelled KNOWN QUIRK pin suspected defects and are not
 * endorsed behaviour.
 */
class AmountUtilsTest {

    private lateinit var originalLocale: Locale

    @Before
    fun saveLocale() {
        originalLocale = Locale.getDefault()
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    private data class ParseRow(val input: String, val expected: Double?, val why: String)

    private data class ValidRow(val amount: Double, val max: Double?, val expected: Boolean, val why: String)

    private fun printable(s: String): String =
        s.map { c ->
            if (c.code in 32..126) c.toString()
            else "\\u" + Integer.toHexString(c.code).uppercase().padStart(4, '0')
        }.joinToString("")

    private fun assertParseTable(table: String, expectedSize: Int, rows: List<ParseRow>) {
        assertEquals("$table size guard", expectedSize, rows.size)
        for (row in rows) {
            val actual = AmountUtils.parseAmount(row.input)
            val label = "$table: parseAmount(\"${printable(row.input)}\") [${row.why}]"
            if (row.expected == null) {
                assertNull("$label expected null but was $actual", actual)
            } else {
                assertNotNull("$label expected ${row.expected} but was null", actual)
                assertEquals(label, row.expected, actual!!, 0.0)
            }
        }
    }

    @Test
    fun `malformed money diagnostics contain controlled codes not input`() {
        val messages = mutableListOf<String>()
        val errors = mutableListOf<Throwable?>()
        val tree = object : timber.log.Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                messages += message
                errors += t
            }
        }
        timber.log.Timber.plant(tree)
        try {
            assertNull(AmountUtils.parseAmount("SECRET_PAYLOAD €1,23,456.00"))
            assertTrue(messages.isNotEmpty())
            assertTrue(messages.all { it == "AMOUNT_GROUPING_INVALID" || it == "AMOUNT_FORMAT_INVALID" })
            assertTrue(errors.all { it == null })
        } finally {
            timber.log.Timber.uproot(tree)
        }
    }

    // ---------------------------------------------------------------------------------------
    // parseAmount tables
    // ---------------------------------------------------------------------------------------

    @Test
    fun `parseAmount table - separators and strict grouping`() {
        val rows = listOf(
            ParseRow("1,234.56", 1234.56, "US grouping"),
            ParseRow("1.234,56", 1234.56, "European grouping"),
            ParseRow("12.345,67", 12345.67, "European, two-digit leading group"),
            ParseRow("1,234,567", 1234567.0, "repeated comma grouping, integer"),
            ParseRow("1,234,567.89", 1234567.89, "US multi-group with decimals"),
            ParseRow("1.234.567,89", 1234567.89, "European multi-group with decimals"),
            ParseRow("1,50", 1.5, "comma + 2 digits is a decimal"),
            ParseRow("12,34", 12.34, "comma + 2 digits is a decimal"),
            ParseRow("0.99", 0.99, "plain decimal point"),
            ParseRow("0,99", 0.99, "plain decimal comma"),
            ParseRow("1,234", 1234.0, "comma + exactly 3 digits is grouping"),
            ParseRow("1.234", 1.234, "KNOWN QUIRK: lone dot + 3 digits is a decimal, not grouping"),
            ParseRow("1 234,56", 1234.56, "ASCII space as grouping"),
            ParseRow("1000000.00", 1_000_000.0, "plain one million"),
            ParseRow("1,23,456", null, "malformed grouping (2-digit group)"),
            ParseRow("1,0000", null, "4 digits after comma"),
            ParseRow("1,0000.00", null, "4-digit group before decimal"),
            ParseRow("12,34.56", null, "comma group of 2 before dot decimal"),
            ParseRow("1234,567.89", null, "4-digit leading group"),
            ParseRow("1.23.456,78", null, "malformed European grouping"),
            ParseRow("12.34,56", null, "dot group of 2 before comma decimal"),
            ParseRow("1.2345,67", null, "4-digit dot group")
        )
        assertParseTable("separators", 22, rows)
    }

    @Test
    fun `parseAmount table - sign handling`() {
        val rows = listOf(
            ParseRow("-50", -50.0, "ASCII hyphen-minus"),
            ParseRow("-50.00", -50.0, "hyphen-minus with decimals"),
            ParseRow("-1234.56", -1234.56, "hyphen-minus decimal"),
            ParseRow("\u221250", -50.0, "unicode minus sign U+2212"),
            ParseRow("\u201150", -50.0, "non-breaking hyphen U+2011"),
            ParseRow("€\u221250", -50.0, "currency prefix then unicode minus"),
            ParseRow("(50)", -50.0, "accounting parentheses"),
            ParseRow("(1234.56)", -1234.56, "accounting parentheses with decimals"),
            ParseRow("£-1,234.56", -1234.56, "prefix + sign + grouping"),
            ParseRow("-0", 0.0, "signed zero compares equal to zero")
        )
        assertParseTable("sign", 10, rows)
    }

    @Test
    fun `parseAmount table - currency symbols and codes`() {
        val rows = listOf(
            ParseRow("€50", 50.0, "euro prefix"),
            ParseRow("€ 50", 50.0, "euro prefix with space"),
            ParseRow("€50.00", 50.0, "euro prefix with decimals"),
            ParseRow("50€", 50.0, "euro suffix"),
            ParseRow("50 €", 50.0, "euro suffix with space"),
            ParseRow("\$50", 50.0, "dollar prefix"),
            ParseRow("50\$", 50.0, "dollar suffix"),
            ParseRow("£50", 50.0, "pound prefix"),
            ParseRow("50£", 50.0, "pound suffix"),
            ParseRow("¥50", 50.0, "yen prefix"),
            ParseRow("50¥", 50.0, "yen suffix"),
            ParseRow("EUR50", 50.0, "currency code prefix"),
            ParseRow("USD 50", 50.0, "currency code with space"),
            ParseRow("GBP50", 50.0, "currency code prefix"),
            ParseRow("E50", 50.0, "KNOWN QUIRK: capital E treated as euro"),
            ParseRow("e50", 50.0, "KNOWN QUIRK: lowercase e treated as euro"),
            ParseRow("€\$50", 50.0, "first symbol stripped, second dropped as non-numeric"),
            ParseRow("€1.234,56", 1234.56, "prefix + European grouping"),
            ParseRow("\$1,234.56", 1234.56, "prefix + US grouping"),
            ParseRow("€1,000,000.00", 1_000_000.0, "prefix + one million"),
            ParseRow("€1,23,456.00", null, "prefix does not excuse malformed grouping")
        )
        assertParseTable("currency", 21, rows)
    }

    @Test
    fun `parseAmount table - whitespace handling`() {
        val rows = listOf(
            ParseRow("  50", 50.0, "leading spaces"),
            ParseRow("\t50", 50.0, "leading tab"),
            ParseRow("50  ", 50.0, "trailing spaces"),
            ParseRow("50\t", 50.0, "trailing tab"),
            ParseRow("50\n", 50.0, "trailing LF"),
            ParseRow("50\r", 50.0, "trailing CR"),
            ParseRow("50\r\n", 50.0, "trailing CRLF"),
            ParseRow("50 0", 500.0, "internal space removed"),
            ParseRow("1\u00A0234\u00A0,56", 1234.56, "NBSP separators"),
            ParseRow("1\u202F234\u202F,56", 1234.56, "narrow NBSP separators")
        )
        assertParseTable("whitespace", 10, rows)
    }

    @Test
    fun `parseAmount table - malformed and non-numeric input`() {
        val rows = listOf(
            ParseRow("", null, "empty"),
            ParseRow("   ", null, "blank"),
            ParseRow("abc", null, "letters"),
            ParseRow("hello world", null, "words"),
            ParseRow("N/A", null, "slash rejected"),
            ParseRow("null", null, "the word null"),
            ParseRow("!!!", null, "punctuation only"),
            ParseRow("---", null, "dashes only"),
            ParseRow("...", null, "dots only"),
            ParseRow("a", null, "single letter a"),
            ParseRow("x", null, "single letter x"),
            ParseRow("①②③", null, "circled digits are not digits"),
            ParseRow("⅕", null, "vulgar fraction one fifth"),
            ParseRow("½", null, "vulgar fraction one half"),
            ParseRow("💰100", null, "emoji prefix (money bag)"),
            ParseRow("100💵", null, "emoji suffix (banknote)"),
            ParseRow("1/2", null, "slash fraction"),
            ParseRow("3/4", null, "slash fraction"),
            ParseRow("1\uFE0F\u20E30\uFE0F\u20E30\uFE0F\u20E3", 100.0, "keycap digits (combining marks) still parse")
        )
        assertParseTable("malformed", 19, rows)
    }

    @Test
    fun `parseAmount table - numeric edge cases`() {
        val rows = listOf(
            ParseRow("5", 5.0, "single digit"),
            ParseRow("0", 0.0, "zero"),
            ParseRow("0.0", 0.0, "zero one decimal"),
            ParseRow("0,0", 0.0, "zero comma decimal"),
            ParseRow("0.00", 0.0, "zero two decimals"),
            ParseRow("0,00", 0.0, "zero comma two decimals"),
            ParseRow("0001.00", 1.0, "leading zeros"),
            ParseRow("0000.00", 0.0, "all zeros"),
            ParseRow("0.01", 0.01, "one cent"),
            ParseRow("0.001", 0.001, "sub-cent"),
            ParseRow(".50", 0.5, "decimal point without integer part"),
            ParseRow(",50", 0.5, "decimal comma without integer part"),
            ParseRow("1e10", 110.0, "KNOWN QUIRK: exponent letter dropped, '1e10' parses as 110")
        )
        assertParseTable("numeric", 13, rows)
    }

    // KNOWN QUIRK: parseAmount returns Infinity; suspected defect (should return null) — update when fixed.
    @Test
    fun `parseAmount - KNOWN QUIRK overflowing digit string yields infinity that isValidAmount rejects`() {
        val parsed = AmountUtils.parseAmount("1" + "0".repeat(1000))

        assertEquals(Double.POSITIVE_INFINITY, parsed)
        assertFalse(AmountUtils.isValidAmount(parsed!!))
    }

    // ---------------------------------------------------------------------------------------
    // isValidAmount
    // ---------------------------------------------------------------------------------------

    @Test
    fun `isValidAmount table - lower bound, upper bound and custom max`() {
        val rows = listOf(
            ValidRow(100.0, null, true, "ordinary amount"),
            ValidRow(0.01, null, true, "one cent"),
            ValidRow(0.001, null, true, "sub-cent is positive"),
            ValidRow(0.0001, null, true, "tiny positive"),
            ValidRow(0.0, null, false, "zero rejected"),
            ValidRow(-0.01, null, false, "tiny negative rejected"),
            ValidRow(-10.0, null, false, "negative rejected"),
            ValidRow(-50.0, null, false, "negative rejected"),
            ValidRow(999_999.99, null, true, "just below default max"),
            ValidRow(1_000_000.0, null, true, "exactly the default max"),
            ValidRow(1_000_000.01, null, false, "one cent over default max"),
            ValidRow(1_000_001.0, null, false, "over default max"),
            ValidRow(2_000_000.0, null, false, "far over default max"),
            ValidRow(1e15, null, false, "huge"),
            ValidRow(Double.MAX_VALUE, null, false, "max double"),
            ValidRow(Double.POSITIVE_INFINITY, null, false, "infinity"),
            ValidRow(Double.NaN, null, false, "NaN"),
            ValidRow(500.0, 1000.0, true, "below custom max"),
            ValidRow(1000.0, 1000.0, true, "exactly custom max"),
            ValidRow(1500.0, 1000.0, false, "above custom max")
        )
        assertEquals("isValidAmount table size guard", 20, rows.size)
        for (row in rows) {
            val actual = if (row.max == null) AmountUtils.isValidAmount(row.amount)
            else AmountUtils.isValidAmount(row.amount, row.max)
            assertEquals("isValidAmount(${row.amount}, max=${row.max}) [${row.why}]", row.expected, actual)
        }
    }

    @Test
    fun `parseAmount then isValidAmount - parsing accepts what validation rejects`() {
        val rows = listOf(
            Triple("1000000.00", 1_000_000.0, true),
            Triple("1000000.01", 1_000_000.01, false),
            Triple("-50", -50.0, false),
            Triple("-50.00", -50.0, false)
        )
        assertEquals("parse-then-validate size guard", 4, rows.size)
        for ((input, parsedValue, valid) in rows) {
            val parsed = AmountUtils.parseAmount(input)
            assertNotNull("parseAmount(\"$input\") must not be null", parsed)
            assertEquals("parseAmount(\"$input\")", parsedValue, parsed!!, 0.0)
            assertEquals("isValidAmount(parseAmount(\"$input\"))", valid, AmountUtils.isValidAmount(parsed))
        }
    }

    // ---------------------------------------------------------------------------------------
    // Locale independence (Locale restored in @After)
    // ---------------------------------------------------------------------------------------

    @Test
    fun `parseAmount and formatAmount are independent of the default locale`() {
        val locales = listOf(
            Locale.US,
            Locale.GERMANY,
            Locale.forLanguageTag("el-GR"),
            Locale.FRENCH
        )
        assertEquals("locale list size guard", 4, locales.size)
        val rows = listOf(
            ParseRow("1,234.56", 1234.56, "US grouping"),
            ParseRow("1.234,56", 1234.56, "European grouping"),
            ParseRow("0,99", 0.99, "decimal comma"),
            ParseRow("0.99", 0.99, "decimal point"),
            ParseRow("1 234,56", 1234.56, "space grouping")
        )
        assertEquals("locale rows size guard", 5, rows.size)
        for (locale in locales) {
            Locale.setDefault(locale)
            for (row in rows) {
                val actual = AmountUtils.parseAmount(row.input)
                assertNotNull("locale=$locale parseAmount(\"${row.input}\") [${row.why}]", actual)
                assertEquals("locale=$locale parseAmount(\"${row.input}\") [${row.why}]", row.expected!!, actual!!, 0.0)
            }
            assertEquals("locale=$locale formatAmount default currency", "€1234.56", AmountUtils.formatAmount(1234.56))
            assertEquals("locale=$locale formatAmount custom currency", "\$0.50", AmountUtils.formatAmount(0.5, "\$"))
        }
    }

    // ---------------------------------------------------------------------------------------
    // Deterministic fuzz (fixed seed)
    // ---------------------------------------------------------------------------------------

    @Test
    fun `parseAmount fuzz - random strings never throw and never return NaN or infinity`() {
        val rnd = Random(20261018)
        val symbols = listOf('.', ',', ' ', '-', '€', '$')
        repeat(1000) { i ->
            val input = (1..20).joinToString("") {
                when (rnd.nextInt(4)) {
                    0 -> ('a'..'z').random(rnd).toString()
                    1 -> ('0'..'9').random(rnd).toString()
                    2 -> symbols.random(rnd).toString()
                    else -> ""
                }
            }
            val result = try {
                AmountUtils.parseAmount(input)
            } catch (e: Exception) {
                fail("iteration $i: parseAmount(\"${printable(input)}\") threw ${e.javaClass.simpleName}")
                null
            }
            assertTrue(
                "iteration $i: parseAmount(\"${printable(input)}\") returned non-finite $result",
                result == null || result.isFinite()
            )
        }
    }

    @Test
    fun `parseAmount fuzz - integer wrapped in currency symbols parses to the integer`() {
        val rnd = Random(20261019)
        val currencies = listOf("€", "$", "£", "¥", "₽", "₹", "₩")
        repeat(500) { i ->
            val prefix = if (rnd.nextBoolean()) currencies.random(rnd) else ""
            val number = (0..100000).random(rnd)
            val suffix = if (rnd.nextBoolean()) currencies.random(rnd) else ""
            val input = "$prefix$number$suffix"

            val actual = AmountUtils.parseAmount(input)

            assertNotNull("iteration $i: parseAmount(\"${printable(input)}\") was null", actual)
            assertEquals("iteration $i: parseAmount(\"${printable(input)}\")", number.toDouble(), actual!!, 0.0)
        }
    }
}
