package com.yourname.expensetracker.domain.receipt.statement

import com.yourname.expensetracker.domain.parser.ParsedTransactionType
import com.yourname.expensetracker.domain.parser.ParsedTransferDirection
import com.yourname.expensetracker.domain.receipt.statement.StatementTextFixtures.day
import com.yourname.expensetracker.domain.util.MerchantCleaner
import com.yourname.expensetracker.domain.util.TimeProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EurobankCardLayoutTest {
    private val result = EurobankCardLayout.parse(StatementTextFixtures.EUROBANK_CARD_PAGES, StatementTextFixtures.context())
    private val txs get() = result.parsedRows.map { it.tx }

    @Test
    fun `layout matches the header that is split over several lines`() {
        assertTrue(EurobankCardLayout.matches(StatementTextFixtures.EUROBANK_CARD_PAGES))
        assertTrue(EurobankCardLayout.matches(listOf(listOf("ΗΜ/ΝΙΑ ΣΥΝΑΛΛΑΓΗΣ ΗΜ/ΝΙΑ ΑΞΙΑΣ ΠΕΡΙΓΡΑΦΗ ΠΟΣΟ"))))
        assertFalse(EurobankCardLayout.matches(StatementTextFixtures.NBG_PAGES))
        assertFalse(EurobankCardLayout.matches(StatementTextFixtures.EUROBANK_TRANSFERS_PAGES))
    }

    @Test
    fun `all rows are read across pages and nothing is skipped`() {
        assertEquals(7, txs.size)
        assertTrue(result.skippedRows.isEmpty())
        assertEquals(StatementLayoutId.EUROBANK_CARD, result.layout)
    }

    @Test
    fun `identical repeated rows are all kept`() {
        val repeated = txs.filter { it.merchant == "SOME SHOP" }
        assertEquals(3, repeated.size)
        assertTrue(repeated.all { it.amount == 3.0 && it.date == day(2031, 11, 11) })
    }

    @Test
    fun `negative amount is a purchase dated by the transaction date`() {
        val tx = txs[3]
        assertEquals(ParsedTransactionType.PURCHASE, tx.type)
        assertNull(tx.transferDirection)
        assertEquals(2.10, tx.amount, 0.0001)
        assertEquals("OTHER SHOP LTD", tx.merchant)
        assertEquals(day(2031, 11, 10), tx.date)
    }

    @Test
    fun `unsigned positive amount is an incoming deposit`() {
        val tx = txs[4]
        assertEquals(ParsedTransactionType.DEPOSIT, tx.type)
        assertEquals(ParsedTransferDirection.INCOMING, tx.transferDirection)
        assertEquals(12.50, tx.amount, 0.0001)
        assertEquals(day(2031, 11, 9), tx.date)
    }

    @Test
    fun `thousands grouped amount and punctuation in the description are kept`() {
        val tx = txs[5]
        assertEquals(1234.56, tx.amount, 0.0001)
        assertTrue(tx.merchant.startsWith("SHOP"))
    }

    @Test
    fun `rows on the second page are read after the repeated header`() {
        val tx = txs[6]
        assertEquals(5.99, tx.amount, 0.0001)
        assertEquals(day(2031, 11, 7), tx.date)
    }

    @Test
    fun `currency evidence comes from the single currency in the header block`() {
        assertTrue(result.parsedRows.all { it.currencyEvidence == CurrencyEvidence.STATEMENT_HEADER })
        assertTrue(txs.all { it.currency == "EUR" })
    }

    @Test
    fun `address postal code limit search period and footer produce no rows`() {
        val junk = EurobankCardLayout.parse(StatementTextFixtures.EUROBANK_CARD_JUNK_PAGES, StatementTextFixtures.context())
        assertTrue(junk.rows.isEmpty())
    }

    @Test
    fun `a row with an impossible date is counted as no date`() {
        val pages = listOf(
            StatementTextFixtures.EUROBANK_CARD_PAGES[1].take(5) + listOf("31/02/2031 01/03/2031 SOME SHOP -3,00")
        )
        val parsed = EurobankCardLayout.parse(pages, StatementTextFixtures.context())
        assertEquals(StatementSkipReason.NO_DATE, parsed.skippedRows.single().reason)
    }

    @Test
    fun `a zero amount row is counted as incomplete instead of being dropped`() {
        val pages = listOf(
            StatementTextFixtures.EUROBANK_CARD_PAGES[1].take(5) + listOf(
                "11/11/2031 13/11/2031 SOME SHOP 0,00",
                "10/11/2031 11/11/2031 OTHER SHOP LTD -2,10"
            )
        )
        val parsed = EurobankCardLayout.parse(pages, StatementTextFixtures.context())
        assertEquals(1, parsed.parsedRows.size)
        assertEquals(StatementSkipReason.INCOMPLETE_ROW, parsed.skippedRows.single().reason)
    }

    @Test
    fun `a row whose amount token cannot be read is counted as incomplete`() {
        val pages = listOf(
            StatementTextFixtures.EUROBANK_CARD_PAGES[1].take(5) + listOf("11/11/2031 13/11/2031 SOME SHOP 1.2.3,4,5")
        )
        val parsed = EurobankCardLayout.parse(pages, StatementTextFixtures.context())
        assertTrue(parsed.parsedRows.isEmpty())
        assertEquals(StatementSkipReason.INCOMPLETE_ROW, parsed.skippedRows.single().reason)
    }

    private val tableHeaderLines = StatementTextFixtures.EUROBANK_CARD_PAGES[1].take(5)

    private fun parserAtFixtureNow() = TextStatementParser(
        MerchantCleaner(),
        object : TimeProvider {
            override fun now(): Long = StatementTextFixtures.NOW_MS
        }
    )

    @Test
    fun `a header with a balance column does not match and produces no deposit`() {
        val pages = listOf(
            listOf(
                "ΗΜ/ΝΙΑ ΣΥΝΑΛΛΑΓΗΣ ΗΜ/ΝΙΑ ΑΞΙΑΣ ΠΕΡΙΓΡΑΦΗ ΠΟΣΟ ΥΠΟΛΟΙΠΟ",
                "10/11/2031 10/11/2031 SOME SHOP -7,41 1.227,15"
            )
        )
        assertFalse(EurobankCardLayout.matches(pages))
        assertTrue(EurobankCardLayout.parse(pages, StatementTextFixtures.context()).rows.isEmpty())
        val parsed = parserAtFixtureNow().parse(pages, "EUR")
        assertNull(parsed.layout)
        assertTrue(parsed.parsedRows.none { it.tx.type == ParsedTransactionType.DEPOSIT })
    }

    @Test
    fun `a balance column title on the line after the header is detected too`() {
        val pages = listOf(
            tableHeaderLines + listOf("Υπόλοιπο", "10/11/2031 10/11/2031 SOME SHOP -7,41 1.227,15")
        )
        assertFalse(EurobankCardLayout.matches(pages))
    }

    @Test
    fun `debit and credit column headers do not match this single amount layout`() {
        val debitCredit = listOf(
            listOf("ΗΜ/ΝΙΑ ΣΥΝΑΛΛΑΓΗΣ ΗΜ/ΝΙΑ ΑΞΙΑΣ ΠΕΡΙΓΡΑΦΗ ΧΡΕΩΣΗ ΠΙΣΤΩΣΗ ΠΟΣΟ", "10/11/2031 10/11/2031 SOME SHOP 7,41")
        )
        assertFalse(EurobankCardLayout.matches(debitCredit))
        assertFalse(
            EurobankCardLayout.matches(
                listOf(listOf("ΗΜ/ΝΙΑ ΣΥΝΑΛΛΑΓΗΣ ΗΜ/ΝΙΑ ΑΞΙΑΣ ΠΕΡΙΓΡΑΦΗ ΠΟΣΟ ΠΙΣΤΩΣΗ"))
            )
        )
    }

    @Test
    fun `a row whose description ends in an amount is incomplete and not read as the last number`() {
        val pages = listOf(tableHeaderLines + listOf("10/11/2031 10/11/2031 SOME SHOP -7,41 1.227,15"))
        val parsed = EurobankCardLayout.parse(pages, StatementTextFixtures.context())
        assertTrue(parsed.parsedRows.isEmpty())
        assertEquals(StatementSkipReason.INCOMPLETE_ROW, parsed.skippedRows.single().reason)
    }

    @Test
    fun `a bare number at the end of the description is still a normal row`() {
        val pages = listOf(tableHeaderLines + listOf("10/11/2031 10/11/2031 SHOP / 210 949 -1,50"))
        val parsed = EurobankCardLayout.parse(pages, StatementTextFixtures.context())
        assertEquals(1.5, parsed.parsedRows.single().tx.amount, 0.0001)
    }

    @Test
    fun `a future dated row is counted as an unreasonable date and not as no date`() {
        val pages = listOf(tableHeaderLines + listOf("10/11/2033 11/11/2033 SOME SHOP -3,00"))
        val parsed = EurobankCardLayout.parse(pages, StatementTextFixtures.context())
        assertTrue(parsed.parsedRows.isEmpty())
        assertEquals(StatementSkipReason.UNREASONABLE_DATE, parsed.skippedRows.single().reason)
    }

    @Test(timeout = 20_000)
    fun `a 100000 character line is dropped and counted while the other rows are read`() {
        val pages = listOf(
            tableHeaderLines + listOf(
                "10/11/2031 10/11/2031 SOME SHOP " + "9".repeat(100_000),
                "10/11/2031 11/11/2031 OTHER SHOP LTD -2,10"
            )
        )
        val parsed = EurobankCardLayout.parse(pages, StatementTextFixtures.context())
        assertEquals(1, parsed.parsedRows.size)
        assertEquals(listOf(StatementSkipReason.INCOMPLETE_ROW), parsed.skippedRows.map { it.reason })
    }

    @Test
    fun `without any currency in the header the home currency stays an assumption`() {
        val pages = listOf(
            listOf(
                "ΗΜ/ΝΙΑ",
                "ΣΥΝΑΛΛΑΓΗΣ",
                "ΗΜ/ΝΙΑ",
                "ΑΞΙΑΣ",
                "ΠΕΡΙΓΡΑΦΗ ΠΟΣΟ",
                "11/11/2031 13/11/2031 SOME SHOP -3,00"
            )
        )
        val parsed = EurobankCardLayout.parse(pages, StatementTextFixtures.context(home = "EUR"))
        assertEquals(CurrencyEvidence.NONE, parsed.parsedRows.single().currencyEvidence)
    }
}
