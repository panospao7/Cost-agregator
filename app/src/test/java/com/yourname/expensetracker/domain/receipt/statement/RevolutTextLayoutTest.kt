package com.yourname.expensetracker.domain.receipt.statement

import com.yourname.expensetracker.domain.parser.ParsedTransactionType
import com.yourname.expensetracker.domain.parser.ParsedTransferDirection
import com.yourname.expensetracker.domain.receipt.statement.StatementTextFixtures.day
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RevolutTextLayoutTest {
    private val result = RevolutTextLayout.parse(StatementTextFixtures.REVOLUT_PAGES, StatementTextFixtures.context())
    private val txs get() = result.parsedRows.map { it.tx }

    @Test
    fun `layout matches only the Revolut table header`() {
        assertTrue(RevolutTextLayout.matches(StatementTextFixtures.REVOLUT_PAGES))
        assertFalse(RevolutTextLayout.matches(StatementTextFixtures.NBG_PAGES))
        assertEquals(StatementLayoutId.REVOLUT_TEXT, result.layout)
    }

    @Test
    fun `only settled rows become transactions and pending and reverted are counted`() {
        assertEquals(11, txs.size)
        val skipped = result.skippedRows
        assertEquals(2, skipped.size)
        assertTrue(skipped.all { it.reason == StatementSkipReason.NOT_SETTLED })
    }

    @Test
    fun `three identical top-ups are all kept as incoming deposits`() {
        val topUps = txs.take(3)
        assertTrue(topUps.all { it.type == ParsedTransactionType.DEPOSIT })
        assertTrue(topUps.all { it.transferDirection == ParsedTransferDirection.INCOMING })
        assertTrue(topUps.all { it.amount == 12.0 })
        assertTrue(topUps.all { it.date == day(2031, 3, 10) })
        assertTrue(topUps.all { it.merchant == "Revolut top-up" })
    }

    @Test
    fun `transfer to takes direction and label from the balance chain and To line`() {
        val tx = txs[3]
        assertEquals(ParsedTransactionType.TRANSFER, tx.type)
        assertEquals(ParsedTransferDirection.OUTGOING, tx.transferDirection)
        assertEquals(21.5, tx.amount, 0.0001)
        assertEquals("MARIA EXAMPLE", tx.merchant)
        assertEquals("To: MARIA EXAMPLE", tx.transferAccountName)
        assertEquals(day(2031, 3, 11), tx.date)
    }

    @Test
    fun `fee row is outgoing with the fee excluded and the foreign amount ignored`() {
        val tx = txs[4]
        assertEquals(ParsedTransactionType.PURCHASE, tx.type)
        assertNull(tx.transferDirection)
        assertEquals(6.40, tx.amount, 0.0001)
        assertEquals("Some Cafe", tx.merchant)
        assertEquals("EUR", tx.currency)
    }

    @Test
    fun `transfer from is incoming with a From label`() {
        val tx = txs[5]
        assertEquals(ParsedTransactionType.TRANSFER, tx.type)
        assertEquals(ParsedTransferDirection.INCOMING, tx.transferDirection)
        assertEquals(31.0, tx.amount, 0.0001)
        assertEquals("ANNA EXAMPLE", tx.merchant)
        assertEquals("From: ANNA EXAMPLE", tx.transferAccountName)
    }

    @Test
    fun `exchange into the account is an incoming currency exchange transfer by the balance chain`() {
        val tx = txs[6]
        assertEquals(ParsedTransactionType.TRANSFER, tx.type)
        assertEquals(ParsedTransferDirection.INCOMING, tx.transferDirection)
        assertEquals("Currency exchange", tx.merchant)
        // A transfer needs an account name to be approvable: the fixed label stands in for it.
        assertEquals("Currency exchange", tx.transferAccountName)
        assertEquals(11.0, tx.amount, 0.0001)
    }

    @Test
    fun `exchange out of the account is an outgoing currency exchange transfer`() {
        val pages = listOf(
            listOf(
                "Account (Current Account) €50.00 €20.00 €0.00 €30.00",
                "Date Description Money out Money in Balance",
                "Mar 10, 2031 Exchanged to USD €20.00 €30.00",
                "\$22.00"
            )
        )
        val tx = RevolutTextLayout.parse(pages, StatementTextFixtures.context()).parsedRows.single().tx
        assertEquals(ParsedTransactionType.TRANSFER, tx.type)
        assertEquals(ParsedTransferDirection.OUTGOING, tx.transferDirection)
        assertEquals("Currency exchange", tx.merchant)
        assertEquals("Currency exchange", tx.transferAccountName)
    }

    @Test
    fun `a row that breaks the chain falls back to keywords and is counted`() {
        val tx = txs[8]
        assertEquals(ParsedTransactionType.TRANSFER, tx.type)
        assertEquals(ParsedTransferDirection.OUTGOING, tx.transferDirection)
        assertEquals(500.0, tx.amount, 0.0001)
        assertEquals(1, result.chainResets)
        // The chain restarts from the printed balance, so the next row is read correctly.
        assertEquals(ParsedTransactionType.PURCHASE, txs[9].type)
        assertEquals(4.5, txs[9].amount, 0.0001)
    }

    @Test
    fun `currency comes from the symbol on the row`() {
        assertTrue(result.parsedRows.all { it.currencyEvidence == CurrencyEvidence.ROW })
        assertTrue(txs.all { it.currency == "EUR" })
    }

    @Test
    fun `reference card and masked account text are never stored`() {
        val stored = txs.joinToString(" ") { "${it.merchant} ${it.transferAccountName.orEmpty()}" }
        assertFalse(stored.contains("secret-ref"))
        assertFalse(stored.contains("Reference"))
        assertFalse(stored.contains("Card"))
        assertFalse(stored.contains("7890"))
        assertFalse(stored.contains("1111"))
    }

    @Test
    fun `header summary and address junk produce no rows`() {
        val junk = RevolutTextLayout.parse(StatementTextFixtures.REVOLUT_JUNK_PAGES, StatementTextFixtures.context())
        assertTrue(junk.rows.isEmpty())
    }

    @Test
    fun `without an opening balance the first row uses keywords and is not a reset`() {
        val pages = listOf(
            listOf(
                "Date Description Money out Money in Balance",
                "Mar 10, 2031 Transfer from ANNA EXAMPLE €31.00 €31.00",
                "From: ANNA EXAMPLE"
            )
        )
        val parsed = RevolutTextLayout.parse(pages, StatementTextFixtures.context())
        assertEquals(0, parsed.chainResets)
        assertEquals(ParsedTransferDirection.INCOMING, parsed.parsedRows.single().tx.transferDirection)
    }

    @Test
    fun `amount above one million is skipped instead of throwing`() {
        val pages = listOf(
            listOf(
                "Date Description Money out Money in Balance",
                "Mar 10, 2031 Some Shop €2,000,000.00 €5.00"
            )
        )
        val parsed = RevolutTextLayout.parse(pages, StatementTextFixtures.context())
        assertEquals(StatementSkipReason.AMOUNT_ABOVE_CEILING, parsed.skippedRows.single().reason)
    }

    @Test
    fun `an impossible date is skipped as no date`() {
        val pages = listOf(
            listOf(
                "Date Description Money out Money in Balance",
                "Feb 30, 2031 Some Shop €2.00 €5.00"
            )
        )
        val parsed = RevolutTextLayout.parse(pages, StatementTextFixtures.context())
        assertEquals(StatementSkipReason.NO_DATE, parsed.skippedRows.single().reason)
    }

    @Test
    fun `money in keywords decide the direction when the balance chain cannot`() {
        val descriptions = listOf(
            "Payment from JOHN DOE",
            "Refund from Some Shop",
            "Cashback",
            "Interest paid"
        )
        for (description in descriptions) {
            val pages = listOf(
                listOf(
                    "Date Description Money out Money in Balance",
                    "Mar 10, 2031 $description €5.00 €9.00"
                )
            )
            val parsed = RevolutTextLayout.parse(pages, StatementTextFixtures.context())
            val tx = parsed.parsedRows.single().tx
            assertEquals(description, ParsedTransactionType.DEPOSIT, tx.type)
            assertEquals(description, ParsedTransferDirection.INCOMING, tx.transferDirection)
        }
    }

    @Test
    fun `the balance chain still wins over the money in keywords`() {
        val pages = listOf(
            listOf(
                "Account (Current Account) €10.00 €5.00 €0.00 €5.00",
                "Date Description Money out Money in Balance",
                "Mar 10, 2031 Refund policy fee €5.00 €5.00"
            )
        )
        val tx = RevolutTextLayout.parse(pages, StatementTextFixtures.context()).parsedRows.single().tx
        assertEquals(ParsedTransactionType.PURCHASE, tx.type)
    }

    @Test
    fun `a future dated row is counted as an unreasonable date and not as no date`() {
        val pages = listOf(
            listOf(
                "Date Description Money out Money in Balance",
                "Mar 10, 2033 Some Shop €2.00 €5.00"
            )
        )
        val parsed = RevolutTextLayout.parse(pages, StatementTextFixtures.context())
        assertEquals(StatementSkipReason.UNREASONABLE_DATE, parsed.skippedRows.single().reason)
    }

    @Test(timeout = 20_000)
    fun `a 100000 asterisk counterparty line finishes quickly and is counted`() {
        val pages = listOf(
            listOf(
                "Date Description Money out Money in Balance",
                "Mar 10, 2031 Transfer to MARIA EXAMPLE €2.00 €5.00",
                "To: " + "*".repeat(100_000) + "1234"
            )
        )
        val parsed = RevolutTextLayout.parse(pages, StatementTextFixtures.context())
        val tx = parsed.parsedRows.single().tx
        assertEquals("MARIA EXAMPLE", tx.merchant)
        assertEquals(listOf(StatementSkipReason.INCOMPLETE_ROW), parsed.skippedRows.map { it.reason })
    }

    @Test(timeout = 20_000)
    fun `a bounded asterisk mask is still read from a short counterparty line`() {
        val pages = listOf(
            listOf(
                "Date Description Money out Money in Balance",
                "Mar 10, 2031 Transfer to €2.00 €5.00",
                "To: *1234"
            )
        )
        val tx = RevolutTextLayout.parse(pages, StatementTextFixtures.context()).parsedRows.single().tx
        assertEquals("Revolut ****1234", tx.merchant)
    }

    @Test
    fun `a zero amount row is counted as incomplete instead of being dropped`() {
        val pages = listOf(
            listOf(
                "Date Description Money out Money in Balance",
                "Mar 10, 2031 Some Shop €0.00 €5.00",
                "Mar 11, 2031 Other Shop €2.00 €3.00"
            )
        )
        val parsed = RevolutTextLayout.parse(pages, StatementTextFixtures.context())
        assertEquals(1, parsed.parsedRows.size)
        assertEquals(StatementSkipReason.INCOMPLETE_ROW, parsed.skippedRows.single().reason)
    }

    @Test
    fun `a short line starting with Pending before the settled table does not hide settled rows`() {
        val pages = listOf(
            listOf(
                "Pending transactions are listed separately",
                "Date Description Money out Money in Balance",
                "Mar 10, 2031 Some Shop €2.00 €5.00",
                "Mar 11, 2031 Other Shop €1.00 €4.00"
            )
        )
        val parsed = RevolutTextLayout.parse(pages, StatementTextFixtures.context())
        assertEquals(2, parsed.parsedRows.size)
        assertTrue(parsed.skippedRows.isEmpty())
    }

    @Test
    fun `rows after a Pending or Reverted section header are skipped and the settled header ends the section`() {
        val pages = listOf(
            listOf(
                "Date Description Money out Money in Balance",
                "Mar 10, 2031 Some Shop €2.00 €5.00",
                "Pending from March 1, 2031 to December 20, 2031",
                "Start date Description Money out Money in",
                "Mar 11, 2031 Some Hold €1.00",
                "Reverted from March 1, 2031 to December 20, 2031",
                "Start date Description Money out Money in",
                "Mar 12, 2031 Some Reversal €3.00"
            ),
            listOf(
                "Date Description Money out Money in Balance",
                "Mar 13, 2031 Other Shop €1.00 €4.00"
            )
        )
        val parsed = RevolutTextLayout.parse(pages, StatementTextFixtures.context())
        assertEquals(2, parsed.parsedRows.size)
        assertEquals(
            listOf(StatementSkipReason.NOT_SETTLED, StatementSkipReason.NOT_SETTLED),
            parsed.skippedRows.map { it.reason }
        )
        assertEquals(day(2031, 3, 13), parsed.parsedRows.last().tx.date)
    }
}
