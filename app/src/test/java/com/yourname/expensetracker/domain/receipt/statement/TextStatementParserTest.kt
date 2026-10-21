package com.yourname.expensetracker.domain.receipt.statement

import com.yourname.expensetracker.domain.util.MerchantCleaner
import com.yourname.expensetracker.domain.util.TimeProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TextStatementParserTest {
    private val parser = TextStatementParser(
        MerchantCleaner(),
        object : TimeProvider {
            override fun now(): Long = StatementTextFixtures.NOW_MS
        }
    )

    private val layouts: List<TextStatementLayout> = listOf(
        RevolutTextLayout,
        NbgAccountLayout,
        EurobankCardLayout,
        EurobankTransfersLayout
    )

    private val ctx = StatementTextFixtures.context()

    @Test
    fun `each layout is selected by its header`() {
        assertEquals(StatementLayoutId.REVOLUT_TEXT, parser.parse(StatementTextFixtures.REVOLUT_PAGES, "EUR").layout)
        assertEquals(StatementLayoutId.NBG_ACCOUNT, parser.parse(StatementTextFixtures.NBG_PAGES, "EUR").layout)
        assertEquals(StatementLayoutId.EUROBANK_CARD, parser.parse(StatementTextFixtures.EUROBANK_CARD_PAGES, "EUR").layout)
        assertEquals(
            StatementLayoutId.EUROBANK_TRANSFERS,
            parser.parse(StatementTextFixtures.EUROBANK_TRANSFERS_PAGES, "EUR").layout
        )
    }

    @Test
    fun `selected layout rows are returned`() {
        assertEquals(11, parser.parse(StatementTextFixtures.REVOLUT_PAGES, "EUR").parsedRows.size)
        assertEquals(12, parser.parse(StatementTextFixtures.NBG_PAGES, "EUR").parsedRows.size)
        assertEquals(7, parser.parse(StatementTextFixtures.EUROBANK_CARD_PAGES, "EUR").parsedRows.size)
        assertEquals(3, parser.parse(StatementTextFixtures.EUROBANK_TRANSFERS_PAGES, "EUR").parsedRows.size)
        assertFalse(parser.parse(StatementTextFixtures.REVOLUT_PAGES, "EUR").failed)
    }

    @Test
    fun `accounts overview page matches no layout so the caller falls back`() {
        val result = parser.parse(StatementTextFixtures.ACCOUNTS_OVERVIEW_PAGES, "EUR")
        assertNull(result.layout)
        assertTrue(result.rows.isEmpty())
        assertFalse(result.failed)
    }

    @Test
    fun `empty and unrelated input matches no layout`() {
        assertNull(parser.parse(emptyList(), "EUR").layout)
        assertNull(parser.parse(listOf(emptyList()), null).layout)
        assertNull(parser.parse(listOf(listOf("SOME SHOP -12,50", "10/05 OTHER SHOP 3,00")), "EUR").layout)
    }

    @Test
    fun `header and footer junk produces zero rows for every layout`() {
        val junk = listOf(
            StatementTextFixtures.REVOLUT_JUNK_PAGES,
            StatementTextFixtures.NBG_JUNK_PAGES,
            StatementTextFixtures.EUROBANK_CARD_JUNK_PAGES,
            StatementTextFixtures.EUROBANK_TRANSFERS_JUNK_PAGES
        )
        for (pages in junk) {
            val result = parser.parse(pages, "EUR")
            assertTrue("layout=${result.layout}", result.layout != null)
            assertTrue("layout=${result.layout}", result.rows.isEmpty())
        }
    }

    @Test
    fun `a matched layout that throws reports failed with its id and no rows`() {
        val throwing = object : TextStatementLayout {
            override val id: StatementLayoutId = StatementLayoutId.NBG_ACCOUNT

            override fun matches(pages: List<List<String>>): Boolean = true

            override fun parse(pages: List<List<String>>, ctx: StatementContext): TextStatementParseResult =
                throw IllegalStateException("simulated layout failure")
        }

        val result = parser.parseWith(listOf(throwing), StatementTextFixtures.NBG_PAGES, "EUR")

        assertEquals(StatementLayoutId.NBG_ACCOUNT, result.layout)
        assertTrue(result.failed)
        assertTrue(result.rows.isEmpty())
    }

    @Test
    fun `cancellation inside a layout is never swallowed`() {
        val cancelling = object : TextStatementLayout {
            override val id: StatementLayoutId = StatementLayoutId.NBG_ACCOUNT

            override fun matches(pages: List<List<String>>): Boolean = true

            override fun parse(pages: List<List<String>>, ctx: StatementContext): TextStatementParseResult =
                throw kotlinx.coroutines.CancellationException("cancelled")
        }

        try {
            parser.parseWith(listOf(cancelling), StatementTextFixtures.NBG_PAGES, "EUR")
            fail("CancellationException must propagate")
        } catch (expected: kotlinx.coroutines.CancellationException) {
            // propagated as required
        }
    }

    @Test
    fun `empty pages and huge lines never throw and yield no parsed rows for any layout`() {
        val huge = "X".repeat(5_000)
        val inputs: List<List<List<String>>> = listOf(
            emptyList(),
            listOf(emptyList()),
            listOf(listOf("", "   ", huge)),
            listOf(listOf("Ünïcödé ΩΩ שלום 日本語", "$huge €1.00 €2.00", "11/11/2031 $huge"))
        )
        for (layout in layouts) {
            for (pages in inputs) {
                val result = layout.parse(pages, ctx)
                assertEquals(layout.id, result.layout)
                assertFalse(result.failed)
                assertTrue("layout=${layout.id}", result.parsedRows.isEmpty())
            }
        }
    }

    @Test
    fun `malformed Revolut lines become skipped rows and never throw`() {
        val pages = listOf(
            listOf(
                "Date Description Money out Money in Balance",
                "Mar 99, 2031 €€€ €",
                "Mar 10, 2031",
                "Mar 10, 2031 Some Shop €",
                "To:",
                "Fee:",
                "Feb 31, 2031 Some Shop €2.00 €5.00",
                "Mar 10, 2031 Some Shop €2,000,000.00 €5.00",
                "Mar 10, 2031 Ünïcödé ΩΩ שלום €"
            )
        )
        val result = RevolutTextLayout.parse(pages, ctx)
        assertTrue(result.parsedRows.isEmpty())
        assertEquals(
            listOf(
                StatementSkipReason.INCOMPLETE_ROW,
                StatementSkipReason.INCOMPLETE_ROW,
                StatementSkipReason.NO_DATE,
                StatementSkipReason.AMOUNT_ABOVE_CEILING,
                StatementSkipReason.INCOMPLETE_ROW
            ),
            result.skippedRows.map { it.reason }
        )
    }

    @Test
    fun `a huge Revolut description is handled without throwing`() {
        val pages = listOf(
            listOf(
                "Date Description Money out Money in Balance",
                "Mar 10, 2031 ${"X".repeat(5_000)} €1.00 €2.00"
            )
        )
        val result = RevolutTextLayout.parse(pages, ctx)
        assertEquals(1, result.rows.size)
    }

    @Test
    fun `malformed NBG lines become skipped rows and never throw`() {
        val pages = listOf(
            listOf(
                "Ημερομηνία / Ώρα Valeur Κατάστημα Συν Περιγραφή Χ/Π Ποσό Λογιστικό Υπόλοιπο",
                "99/99/9999 99:99:99 99/99/9999 000 000 X Χ - -",
                "Π",
                "31/02/2031 10:00:00 01/03/2031 222 040 SOME SHOP Χ -2,00 5,00",
                "20/12/2031 10:00:00 20/12/2031 222 040 BIG SHOP Χ 2,000,000.00 5,00",
                "Ünïcödé ΩΩ שלום"
            )
        )
        val result = NbgAccountLayout.parse(pages, ctx)
        assertTrue(result.parsedRows.isEmpty())
        assertEquals(
            listOf(
                StatementSkipReason.INCOMPLETE_ROW,
                StatementSkipReason.NO_DATE,
                StatementSkipReason.AMOUNT_ABOVE_CEILING
            ),
            result.skippedRows.map { it.reason }
        )
    }

    @Test
    fun `malformed Eurobank card lines become skipped rows and never throw`() {
        val pages = listOf(
            listOf(
                "ΗΜ/ΝΙΑ",
                "ΣΥΝΑΛΛΑΓΗΣ",
                "ΗΜ/ΝΙΑ",
                "ΑΞΙΑΣ",
                "ΠΕΡΙΓΡΑΦΗ ΠΟΣΟ",
                "31/02/2031 01/03/2031 SOME SHOP -3,00",
                "11/11/2031 13/11/2031 SOME SHOP 2,000,000.00",
                "11/11/2031 13/11/2031 SOME SHOP",
                "99/99/9999 99/99/9999 Ünïcödé ΩΩ שלום -",
                "Ünïcödé ΩΩ שלום"
            )
        )
        val result = EurobankCardLayout.parse(pages, ctx)
        assertTrue(result.parsedRows.isEmpty())
        assertEquals(
            listOf(StatementSkipReason.NO_DATE, StatementSkipReason.AMOUNT_ABOVE_CEILING),
            result.skippedRows.map { it.reason }
        )
    }

    @Test
    fun `malformed Eurobank transfer rows become skipped rows and never throw`() {
        val own = StatementTextFixtures.OWN_IBAN
        val recipient = StatementTextFixtures.RECIPIENT_IBAN_1
        val pages = listOf(
            listOf(
                "Ιστορικό Μεταφορών σε άλλη τράπεζα",
                "07/12/2031 GR96 junk",
                "31/02/2031 $own $recipient SOME BANK SA 4,00 EUR Πιστώθηκε στον",
                "11/12/2031 $own $recipient SOME BANK SA 2,000,000.00 EUR Πιστώθηκε στον",
                "Ünïcödé ΩΩ שלום"
            )
        )
        val result = EurobankTransfersLayout.parse(pages, ctx)
        assertTrue(result.parsedRows.isEmpty())
        assertEquals(
            listOf(
                StatementSkipReason.INCOMPLETE_ROW,
                StatementSkipReason.NO_DATE,
                StatementSkipReason.AMOUNT_ABOVE_CEILING
            ),
            result.skippedRows.map { it.reason }
        )
    }

    @Test
    fun `unknown or unsupported home currency still parses rows with the evidence kept`() {
        val result = parser.parse(StatementTextFixtures.REVOLUT_PAGES, null)
        assertTrue(result.parsedRows.all { it.currencyEvidence == CurrencyEvidence.ROW })
        assertTrue(result.parsedRows.all { it.tx.currency == "EUR" })
    }
}
