package com.yourname.expensetracker.domain.quickadd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Currency fail-closed rules of the quick-add parser (owner-approvals item 58, spec clarification
 * 2026-10-28), kept apart from the 132-case golden table so its section 12 counts stay intact.
 *
 * - (a) a token with a Unicode currency-symbol code point that is not exactly a home marker is foreign;
 * - (b) dollar compounds outside the catalog (US$, CA$, AU$, $US) are foreign;
 * - (c) a leading minus does not hide a prefix marker ("-$5"), and foreign wins over R0;
 * - (d) alphabetic catalog symbols (kr, zl with stroke, ...) block when adjacent to the number, and a
 *   home marker never blocks;
 * - (e) the embedded non-catalog ISO list covers all ISO 4217 alphabetic codes minus the catalog.
 *
 * Also pins the superseded AE-12 line "20 abc" (OD-3) and the category-removal fallback (spec 4.11).
 * Invisible and non-ASCII characters are written as unicode escapes.
 */
class QuickAddParserForeignCurrencyTest {

    private val parser = QuickAddParser()

    // U+2212 minus sign, built from its code point so no look-alike of the hyphen sits raw in the source.
    private val minusSign = 0x2212.toChar()
    private val baht ="฿"
    private val shekel = "₪"
    private val dong = "₫"
    private val euro = "€"
    private val zloty = "zł"

    private fun parse(line: String, home: String = "EUR"): QuickAddParseResult =
        parser.parse(QuickAddParserGoldenFixtures.CTX_A.copy(home = home).input(line))

    private fun assertForeign(line: String, merchant: String? = null, home: String = "EUR") {
        val result = parse(line, home)
        assertEquals(
            "amount of ${printable(line)} (home $home)",
            FieldParse.Flagged(FlagReason.AMOUNT_FOREIGN_CURRENCY),
            result.amount
        )
        assertTrue(
            "hint of ${printable(line)} (home $home)",
            LineHint.FOREIGN_CURRENCY_NOT_SUPPORTED in result.lineHints
        )
        if (merchant != null) {
            assertEquals("merchant of ${printable(line)}", FieldParse.Value(MerchantText(merchant)), result.merchant)
        }
    }

    private fun assertValue(line: String, amount: String, merchant: String?, home: String = "EUR") {
        val result = parse(line, home)
        assertEquals("amount of ${printable(line)} (home $home)", FieldParse.Value(AmountText(amount)), result.amount)
        assertTrue(
            "no foreign hint for ${printable(line)} (home $home)",
            LineHint.FOREIGN_CURRENCY_NOT_SUPPORTED !in result.lineHints
        )
        val expectedMerchant: FieldParse<MerchantText> =
            if (merchant == null) FieldParse.Absent else FieldParse.Value(MerchantText(merchant))
        assertEquals("merchant of ${printable(line)}", expectedMerchant, result.merchant)
    }

    // ------------------------------------------------------------------ (a) currency symbols

    @Test
    fun detachedUnknownCurrencySymbolsAreForeign() {
        assertForeign("taxi 200 $baht", merchant = "taxi")
        assertForeign("dinner 50 $shekel", merchant = "dinner")
        assertForeign("pho 90000 $dong", merchant = "pho")
        assertForeign("$baht 200 taxi", merchant = "taxi")
    }

    @Test
    fun attachedUnknownCurrencySymbolsAreForeign() {
        assertForeign("taxi 200$baht")
        assertForeign("taxi ${shekel}50")
        assertForeign("pho 90000$dong")
    }

    @Test
    fun unknownCurrencySymbolsAreForeignEvenWhenNotAdjacent() {
        assertForeign("$baht taxi 200")
        assertForeign("taxi $baht dinner 200")
    }

    // ------------------------------------------------------------------ (b) dollar compounds

    @Test
    fun dollarCompoundsOutsideTheCatalogAreForeign() {
        assertForeign("dinner 20 US\$", merchant = "dinner")
        assertForeign("taxi 15 CA\$", merchant = "taxi")
        assertForeign("dinner 20 AU\$", merchant = "dinner")
        assertForeign("dinner \$US 20", merchant = "dinner")
        assertForeign("dinner 20US\$")
        assertForeign("taxi CA\$15")
    }

    // ------------------------------------------------------------------ (c) leading minus

    @Test
    fun leadingMinusDoesNotHideTheMarkerAndForeignWinsOverNotPositive() {
        assertForeign("taxi -\$5", merchant = "taxi")
        assertForeign("taxi ${minusSign}\$5", merchant = "taxi")
        assertForeign("taxi -£5", merchant = "taxi")
    }

    @Test
    fun leadingMinusWithTheHomeMarkerStaysNotPositive() {
        val eur = parse("taxi -${euro}5")
        assertEquals(FieldParse.Flagged(FlagReason.AMOUNT_NOT_POSITIVE), eur.amount)
        assertTrue(LineHint.FOREIGN_CURRENCY_NOT_SUPPORTED !in eur.lineHints)

        val usd = parse("taxi -\$5", home = "USD")
        assertEquals(FieldParse.Flagged(FlagReason.AMOUNT_NOT_POSITIVE), usd.amount)
        assertTrue(LineHint.FOREIGN_CURRENCY_NOT_SUPPORTED !in usd.lineHints)
        assertEquals(FieldParse.Value(MerchantText("taxi")), usd.merchant)
    }

    // ------------------------------------------------------------------ (d) alphabetic symbols

    @Test
    fun alphabeticCatalogSymbolsBlockWhenAdjacentOrAttached() {
        assertForeign("dinner 100 kr", merchant = "dinner")
        assertForeign("kr 100 dinner", merchant = "dinner")
        assertForeign("dinner 100kr")
        assertForeign("obiad 50 $zloty", merchant = "obiad")
        assertForeign("obiad 50$zloty")
        assertForeign("lunch 50 lei", merchant = "lunch")
        assertForeign("lunch 80 Ft", merchant = "lunch")
    }

    @Test
    fun kronaSymbolIsNeverHomeEvenForSwedishHome() {
        assertForeign("dinner 100 kr", merchant = "dinner", home = "SEK")
    }

    @Test
    fun alphabeticSymbolsThatAreTheHomeCurrencyNeverBlock() {
        assertValue("obiad 50 $zloty", "50.00", "obiad", home = "PLN")
        assertValue("50 $zloty obiad", "50.00", "obiad", home = "PLN")
        assertValue("lunch 50 lei", "50.00", "lunch", home = "RON")
        assertValue("lunch 80 Ft", "80.00", "lunch", home = "HUF")
    }

    @Test
    fun alphabeticSymbolsAwayFromTheNumberStayMerchantText() {
        assertValue("kr dinner 100", "100.00", "kr dinner")
        assertValue("R and R dinner 100", "100.00", "R and R dinner")
    }

    // ------------------------------------------------------------------ (e) ISO code list

    private val addedIsoCodes = listOf(
        "VED", "ZWG", "XCG", "SVC", "UYW", "BOV", "CHE", "CHW", "CLF", "COU", "MXV", "USN", "UYI",
        "XAU", "XAG", "XDR", "XPD", "XPT", "XSU", "XUA", "XBA", "XBB", "XBC", "XBD", "XTS", "XXX"
    )

    @Test
    fun adjacentNonCatalogIsoCodesAreForeignIncludingNewAndFundCodes() {
        assertForeign("dinner 20 ved", merchant = "dinner")
        for (code in addedIsoCodes + listOf("THB", "ILS", "VND")) {
            assertForeign("dinner 20 ${code.lowercase(Locale.ROOT)}", merchant = "dinner")
            assertForeign("$code 20 dinner", merchant = "dinner")
            assertForeign("dinner 20${code.lowercase(Locale.ROOT)}")
        }
    }

    @Test
    fun nonCatalogIsoCodesAwayFromTheNumberStayMerchantText() {
        assertValue("ved dinner 20", "20.00", "ved dinner")
    }

    @Test
    fun theBlockListHoldsTheNewCodesAndNeverACatalogOrHomeCode() {
        for (code in addedIsoCodes) {
            assertTrue("$code must be in the non-catalog list", code.lowercase(Locale.ROOT) in QuickAddVocabulary.NON_CATALOG_CODES)
        }
        for (code in QuickAddVocabulary.CATALOG_CODES) {
            assertTrue(
                "catalog code $code must never be in the block list",
                code.lowercase(Locale.ROOT) !in QuickAddVocabulary.NON_CATALOG_CODES
            )
        }
    }

    @Test
    fun theHomeCurrencyCodeNeverBlocksItself() {
        for (code in QuickAddVocabulary.CATALOG_CODES) {
            val lower = code.lowercase(Locale.ROOT)
            assertValue("dinner 20 $lower", "20.00", "dinner", home = code)
            assertValue("dinner 20$lower", "20.00", "dinner", home = code)
            assertValue("$code 20 dinner", "20.00", "dinner", home = code)
        }
    }

    // ------------------------------------------------------------------ home controls

    @Test
    fun homeMarkersStayValues() {
        assertValue("taxi 15 $euro", "15.00", "taxi")
        assertValue("taxi 15 eur", "15.00", "taxi")
        assertValue("taxi 15$euro", "15.00", "taxi")
        assertValue("taxi ${euro}15", "15.00", "taxi")
        assertValue("taxi 15 EUR", "15.00", "taxi")
        assertValue("taxi 15 \$", "15.00", "taxi", home = "USD")
        assertValue("taxi \$15", "15.00", "taxi", home = "USD")
    }

    @Test
    fun aHomeMarkerDoesNotMakeAForeignOneHome() {
        assertForeign("taxi 15 $euro 20 usd")
        assertForeign("taxi 15 $euro $baht")
    }

    // ------------------------------------------------------------------ OD-3 and category fallback

    @Test
    fun leadingBareIntegerBeforeAWordIsAQuantity_OD3() {
        val result = parse("20 abc")
        assertEquals(FieldParse.Flagged(FlagReason.AMOUNT_QUANTITY), result.amount)
        assertEquals(FieldParse.Value(MerchantText("abc")), result.merchant)
        assertTrue(result.lineHints.isEmpty())
    }

    @Test
    fun categoryTokensStayInTheMerchantWhenOnlyASymbolWouldRemain() {
        // Removing "groceries" would leave only an emoji, which is not a merchant (no letter or digit).
        val result = parse("groceries 🛒 42.30")
        assertEquals(FieldParse.Value(AmountText("42.30")), result.amount)
        assertEquals(FieldParse.Value(CategoryMatch(1L, CategorySource.EXACT_NAME)), result.category)
        assertEquals(FieldParse.Value(MerchantText("groceries 🛒")), result.merchant)
    }

    @Test
    fun categoryTokensAreStillRemovedWhenALetterRemains() {
        val result = parse("groceries lidl 42.30")
        assertEquals(FieldParse.Value(CategoryMatch(1L, CategorySource.EXACT_NAME)), result.category)
        assertEquals(FieldParse.Value(MerchantText("lidl")), result.merchant)
    }

    private fun printable(s: String): String =
        s.map { c ->
            if (c.code in 32..126) c.toString() else "\\u" + Integer.toHexString(c.code).uppercase().padStart(4, '0')
        }.joinToString("")
}
