package com.yourname.expensetracker.domain.receipt.statement

import com.yourname.expensetracker.domain.parser.ParsedTransactionType
import com.yourname.expensetracker.domain.parser.ParsedTransferDirection
import com.yourname.expensetracker.domain.receipt.statement.StatementTextFixtures.moment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NbgAccountLayoutTest {
    private val result = NbgAccountLayout.parse(StatementTextFixtures.NBG_PAGES, StatementTextFixtures.context())
    private val txs get() = result.parsedRows.map { it.tx }

    @Test
    fun `layout matches the column header and not an accounts overview`() {
        assertTrue(NbgAccountLayout.matches(StatementTextFixtures.NBG_PAGES))
        assertFalse(NbgAccountLayout.matches(StatementTextFixtures.ACCOUNTS_OVERVIEW_PAGES))
        assertFalse(NbgAccountLayout.matches(StatementTextFixtures.REVOLUT_PAGES))
    }

    @Test
    fun `every row is read and the orphan tail is counted as no date`() {
        assertEquals(12, txs.size)
        val skipped = result.skippedRows
        assertEquals(1, skipped.size)
        assertEquals(StatementSkipReason.NO_DATE, skipped.single().reason)
        assertEquals(2, skipped.single().page)
    }

    @Test
    fun `incoming transfer code 013 is a transfer with a From label`() {
        val tx = txs[0]
        assertEquals(ParsedTransactionType.TRANSFER, tx.type)
        assertEquals(ParsedTransferDirection.INCOMING, tx.transferDirection)
        assertEquals(15.0, tx.amount, 0.0001)
        assertEquals("MARIA EXAMPLE", tx.merchant)
        assertEquals("From: MARIA EXAMPLE", tx.transferAccountName)
        assertEquals(moment(2031, 12, 19, 9, 15, 42), tx.date)
    }

    @Test
    fun `card purchase takes the amount and not the balance`() {
        val tx = txs[1]
        assertEquals(ParsedTransactionType.PURCHASE, tx.type)
        assertNull(tx.transferDirection)
        assertEquals(0.85, tx.amount, 0.0001)
        assertEquals("SOME SHOP", tx.merchant)
    }

    @Test
    fun `repeated identical rows are all kept`() {
        assertEquals(2, txs.count { it.amount == 3.70 && it.merchant == "SOME SHOP" })
    }

    @Test
    fun `wrapped row with the tail on the next line is stitched`() {
        val incoming = txs[4]
        assertEquals(ParsedTransactionType.TRANSFER, incoming.type)
        assertEquals(ParsedTransferDirection.INCOMING, incoming.transferDirection)
        assertEquals(2.0, incoming.amount, 0.0001)
        assertEquals("JOHN DOE SMITH EXAMPLE", incoming.merchant)
        assertEquals("From: JOHN DOE SMITH EXAMPLE", incoming.transferAccountName)
        assertEquals(moment(2031, 12, 13, 11, 20, 36), incoming.date)
    }

    @Test
    fun `wrapped row whose tail line starts with the marker is a debit transfer`() {
        val outgoing = txs[5]
        assertEquals(ParsedTransactionType.TRANSFER, outgoing.type)
        assertEquals(ParsedTransferDirection.OUTGOING, outgoing.transferDirection)
        assertEquals(1250.0, outgoing.amount, 0.0001)
        assertEquals("To: JOHN DOE SMITH EXAMPLE", outgoing.transferAccountName)
    }

    @Test
    fun `card credit and benefit code are incoming deposits`() {
        val refund = txs[6]
        assertEquals(ParsedTransactionType.DEPOSIT, refund.type)
        assertEquals(ParsedTransferDirection.INCOMING, refund.transferDirection)
        assertEquals(6.15, refund.amount, 0.0001)
        val benefit = txs[7]
        assertEquals(ParsedTransactionType.DEPOSIT, benefit.type)
        assertEquals(25.0, benefit.amount, 0.0001)
    }

    @Test
    fun `unknown operation code follows the debit marker`() {
        val tx = txs[8]
        assertEquals(ParsedTransactionType.PURCHASE, tx.type)
        assertEquals(3.0, tx.amount, 0.0001)
    }

    @Test
    fun `row wrapped across a page break is joined after the page furniture is dropped`() {
        val tx = txs[9]
        assertEquals(12.34, tx.amount, 0.0001)
        assertTrue(tx.merchant.startsWith("ACME MARKET WITH A VERY LONG"))
        assertEquals(moment(2031, 12, 6, 10, 0, 0), tx.date)
    }

    @Test
    fun `own-account transfer code 068 follows the debit marker`() {
        val tx = txs[10]
        assertEquals(ParsedTransactionType.TRANSFER, tx.type)
        assertEquals(ParsedTransferDirection.OUTGOING, tx.transferDirection)
        assertEquals(50.0, tx.amount, 0.0001)
        assertEquals("To: MARIA EXAMPLE", tx.transferAccountName)
    }

    @Test
    fun `iban-only description is masked and never stored`() {
        val tx = txs[11]
        assertEquals(ParsedTransactionType.TRANSFER, tx.type)
        assertEquals("Account ****1234", tx.merchant)
        assertEquals("To: Account ****1234", tx.transferAccountName)
        assertFalse(txs.joinToString(" ") { "${it.merchant} ${it.transferAccountName}" }
            .contains(StatementTextFixtures.OWN_IBAN))
    }

    @Test
    fun `currency evidence comes from the single currency in the header block`() {
        assertTrue(result.parsedRows.all { it.currencyEvidence == CurrencyEvidence.STATEMENT_HEADER })
        assertTrue(txs.all { it.currency == "EUR" })
    }

    @Test
    fun `header block column header and page furniture produce no rows`() {
        val junk = NbgAccountLayout.parse(StatementTextFixtures.NBG_JUNK_PAGES, StatementTextFixtures.context())
        assertTrue(junk.rows.isEmpty())
    }

    @Test
    fun `date-led row that never receives its tail is counted as incomplete`() {
        val pages = listOf(
            listOf(
                "Ημερομηνία / Ώρα Valeur Κατάστημα Συν Περιγραφή Χ/Π Ποσό Λογιστικό Υπόλοιπο",
                "16/12/2031 08:10:20 15/12/2031 222 040 SOME SHOP",
                "14/12/2031 03:04:05 12/12/2031 222 040 OTHER SHOP Χ -2,25 70,30"
            )
        )
        val parsed = NbgAccountLayout.parse(pages, StatementTextFixtures.context())
        assertEquals(1, parsed.parsedRows.size)
        assertEquals(StatementSkipReason.INCOMPLETE_ROW, parsed.skippedRows.single().reason)
    }

    @Test
    fun `a zero amount row is counted as incomplete instead of being dropped`() {
        val pages = listOf(
            listOf(
                "Ημερομηνία / Ώρα Valeur Κατάστημα Συν Περιγραφή Χ/Π Ποσό Λογιστικό Υπόλοιπο",
                "14/12/2031 03:04:05 12/12/2031 222 040 OTHER SHOP Χ 0,00 70,30"
            )
        )
        val parsed = NbgAccountLayout.parse(pages, StatementTextFixtures.context())
        assertTrue(parsed.parsedRows.isEmpty())
        assertEquals(StatementSkipReason.INCOMPLETE_ROW, parsed.skippedRows.single().reason)
    }

    @Test
    fun `payment reference in a description is masked in the merchant`() {
        val pages = listOf(
            listOf(
                "Ημερομηνία / Ώρα Valeur Κατάστημα Συν Περιγραφή Χ/Π Ποσό Λογιστικό Υπόλοιπο",
                "14/12/2031 03:04:05 12/12/2031 222 040 164500000000/SOME SUBSCRIPTION Χ -9,99 70,30"
            )
        )
        val tx = NbgAccountLayout.parse(pages, StatementTextFixtures.context()).parsedRows.single().tx
        assertTrue(tx.merchant, tx.merchant.contains("****0000"))
        assertTrue(tx.merchant, tx.merchant.contains("SOME SUBSCRIPTION"))
        assertFalse(tx.merchant, Regex("""\d{12}""").containsMatchIn(tx.merchant))
    }

    private val columnHeader = "Ημερομηνία / Ώρα Valeur Κατάστημα Συν Περιγραφή Χ/Π Ποσό Λογιστικό Υπόλοιπο"

    @Test
    fun `a malformed date-led line closes the open row and is counted instead of being stitched`() {
        val pages = listOf(
            listOf(
                columnHeader,
                "16/12/2031 08:10:20 15/12/2031 222 040 SOME SHOP",
                "14/12/2031 garbled line",
                "Χ -2,25 70,30"
            )
        )
        val parsed = NbgAccountLayout.parse(pages, StatementTextFixtures.context())
        assertTrue(parsed.parsedRows.isEmpty())
        assertEquals(
            listOf(
                StatementSkipReason.INCOMPLETE_ROW,
                StatementSkipReason.INCOMPLETE_ROW,
                StatementSkipReason.NO_DATE
            ),
            parsed.skippedRows.map { it.reason }
        )
    }

    @Test
    fun `a malformed date-led line with no open row is counted as incomplete`() {
        val pages = listOf(
            listOf(
                columnHeader,
                "14/12/2031 03:04:05 12/12/2031 222 040 OTHER SHOP Χ -2,25 70,30",
                "13/12/2031 not a row"
            )
        )
        val parsed = NbgAccountLayout.parse(pages, StatementTextFixtures.context())
        assertEquals(1, parsed.parsedRows.size)
        assertEquals(listOf(StatementSkipReason.INCOMPLETE_ROW), parsed.skippedRows.map { it.reason })
    }

    @Test
    fun `a transfer description is masked down to the last four of a domestic account or phone number`() {
        val pages = listOf(
            listOf(
                columnHeader,
                "14/12/2031 03:04:05 12/12/2031 222 064 MARIA EXAMPLE 12345678901 Χ -2,25 70,30",
                "13/12/2031 03:04:05 12/12/2031 222 064 JOHN DOE 69/1234.5678 Π 3,25 72,55"
            )
        )
        val parsed = NbgAccountLayout.parse(pages, StatementTextFixtures.context())
        val first = parsed.parsedRows[0].tx
        assertEquals("MARIA EXAMPLE ****8901", first.merchant)
        assertEquals("To: MARIA EXAMPLE ****8901", first.transferAccountName)
        val second = parsed.parsedRows[1].tx
        assertEquals("JOHN DOE ****5678", second.merchant)
        assertEquals("From: JOHN DOE ****5678", second.transferAccountName)
    }

    @Test
    fun `a purchase merchant keeps a nine to eleven digit store number`() {
        val pages = listOf(
            listOf(
                columnHeader,
                "14/12/2031 03:04:05 12/12/2031 222 040 SOME SHOP 12345678901 Χ -2,25 70,30"
            )
        )
        val tx = NbgAccountLayout.parse(pages, StatementTextFixtures.context()).parsedRows.single().tx
        assertTrue(tx.merchant, tx.merchant.contains("12345678901"))
    }

    @Test(timeout = 20_000)
    fun `hostile 100000 character lines finish quickly and are counted`() {
        val pages = listOf(
            listOf(
                columnHeader,
                "9".repeat(100_000) + " από " + "9".repeat(100_000) + " Ημ/νία Εκτύπωσης",
                "*".repeat(100_000),
                "14/12/2031 03:04:05 12/12/2031 222 040 OTHER SHOP Χ -2,25 70,30"
            )
        )
        val parsed = NbgAccountLayout.parse(pages, StatementTextFixtures.context())
        assertEquals(1, parsed.parsedRows.size)
        assertEquals(2, parsed.skippedRows.count { it.reason == StatementSkipReason.INCOMPLETE_ROW })
    }

    @Test
    fun `amount printed with a leading minus is accepted`() {
        val pages = listOf(
            listOf(
                "Ημερομηνία / Ώρα Valeur Κατάστημα Συν Περιγραφή Χ/Π Ποσό Λογιστικό Υπόλοιπο",
                "14/12/2031 03:04:05 12/12/2031 222 040 OTHER SHOP Π -2,25 70,30"
            )
        )
        val parsed = NbgAccountLayout.parse(pages, StatementTextFixtures.context())
        assertEquals(2.25, parsed.parsedRows.single().tx.amount, 0.0001)
    }
}
