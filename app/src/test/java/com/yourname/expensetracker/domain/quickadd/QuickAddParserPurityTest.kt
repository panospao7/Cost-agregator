package com.yourname.expensetracker.domain.quickadd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/**
 * INV-19: `parse()` is a pure function of its input. The result must not depend on call order,
 * the device locale (tr-TR and el-GR change case-folding rules for 'I' and sigma) or the default
 * time zone (the zone is an argument). The default locale and time zone are restored in `finally`.
 */
class QuickAddParserPurityTest {

    private val parser = QuickAddParser()
    private val fixtures = QuickAddParserGoldenFixtures

    /** Extra upper-case lines: the Turkish dotless-i and Greek final-sigma hazards. */
    private val hazardLines = listOf(
        "TAXI 9 KYRIAKI",
        "TAXI 9 SIMERA",
        "TAXI 9 FRIDAY",
        "TAXI 5 CARD",
        "DINNER 20 USD",
        "TAXI 12 AVRIO",
        "KIDS 12",
        "FOOD 9 SUSHI",
        "ΙΣΤΟΡΙΑ 12 ΧΘΕΣ",
        "ΣΟΥΠΕΡ ΜΑΡΚΕΤ 4,50 ΚΑΡΤΑ"
    )

    private fun inputs(): List<QuickAddInput> {
        val golden = fixtures.cases.map { it.ctx.input(it.line) }
        val hazards = hazardLines.map { fixtures.CTX_A.input(it) }
        return golden + hazards
    }

    private fun parseAll(): List<QuickAddParseResult> = inputs().map { parser.parse(it) }

    @Test
    fun sameInputTwiceGivesEqualResults() {
        val inputs = inputs()
        val fresh = QuickAddParser()
        for (input in inputs) {
            val first = parser.parse(input)
            assertEquals(first, parser.parse(input))
            assertEquals(first, fresh.parse(input))
        }
        assertTrue(inputs.size >= fixtures.cases.size)
    }

    @Test
    fun resultsAreIdenticalUnderTurkishAndGreekDefaultLocales() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ROOT)
            val baseline = parseAll()
            for (tag in listOf("tr-TR", "el-GR")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                val actual = parseAll()
                assertEquals("results differ under $tag", baseline.size, actual.size)
                for (i in baseline.indices) {
                    assertEquals("input #$i differs under $tag", baseline[i], actual[i])
                }
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun resultsAreIdenticalWhenTheDefaultTimeZoneChanges() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val baseline = parseAll()
            for (id in listOf("Pacific/Kiritimati", "America/Los_Angeles", "Asia/Kolkata", "Europe/Athens")) {
                TimeZone.setDefault(TimeZone.getTimeZone(id))
                val actual = parseAll()
                for (i in baseline.indices) {
                    assertEquals("input #$i differs when the default zone is $id", baseline[i], actual[i])
                }
            }
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun hazardLinesAreRecognisedRegardlessOfLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val kyriaki = parser.parse(fixtures.CTX_A.input("TAXI 9 KYRIAKI"))
            assertTrue("KYRIAKI must be a date under tr-TR", kyriaki.date is FieldParse.Value)
            val friday = parser.parse(fixtures.CTX_A.input("TAXI 9 FRIDAY"))
            assertEquals(FieldParse.Flagged(FlagReason.DATE_AMBIGUOUS), friday.date)
            val card = parser.parse(fixtures.CTX_A.input("TAXI 5 CARD"))
            assertEquals(FieldParse.Value(QuickAddPayment.CARD), card.payment)
            val kids = parser.parse(fixtures.CTX_A.input("KIDS 12"))
            assertEquals(FieldParse.Value(CategoryMatch(16L, CategorySource.EXACT_NAME)), kids.category)
        } finally {
            Locale.setDefault(original)
        }
    }
}
