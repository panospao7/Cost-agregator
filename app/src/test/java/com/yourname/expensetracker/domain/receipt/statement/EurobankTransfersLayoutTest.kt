package com.yourname.expensetracker.domain.receipt.statement

import com.yourname.expensetracker.domain.parser.ParsedTransactionType
import com.yourname.expensetracker.domain.parser.ParsedTransferDirection
import com.yourname.expensetracker.domain.receipt.statement.StatementTextFixtures.day
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EurobankTransfersLayoutTest {
    private val result = EurobankTransfersLayout.parse(
        StatementTextFixtures.EUROBANK_TRANSFERS_PAGES,
        StatementTextFixtures.context()
    )
    private val txs get() = result.parsedRows.map { it.tx }

    @Test
    fun `layout matches the transfers title only`() {
        assertTrue(EurobankTransfersLayout.matches(StatementTextFixtures.EUROBANK_TRANSFERS_PAGES))
        assertFalse(EurobankTransfersLayout.matches(StatementTextFixtures.EUROBANK_CARD_PAGES))
        assertEquals(StatementLayoutId.EUROBANK_TRANSFERS, result.layout)
    }

    @Test
    fun `credited transfers are imported and the others are counted`() {
        assertEquals(3, txs.size)
        val reasons = result.skippedRows.map { it.reason }
        assertEquals(
            listOf(StatementSkipReason.CANCELLED, StatementSkipReason.UNKNOWN_STATUS),
            reasons
        )
    }

    @Test
    fun `credited transfer is an outgoing transfer labelled with bank and last four digits`() {
        val tx = txs[0]
        assertEquals(ParsedTransactionType.TRANSFER, tx.type)
        assertEquals(ParsedTransferDirection.OUTGOING, tx.transferDirection)
        assertEquals(11.0, tx.amount, 0.0001)
        assertEquals("EUR", tx.currency)
        assertEquals(day(2031, 12, 7), tx.date)
        assertEquals("SOME BANK SA ****5678", tx.merchant)
        assertEquals("To: SOME BANK SA ****5678", tx.transferAccountName)
    }

    @Test
    fun `wrapped recipient iban is repaired before the label is built`() {
        val tx = txs[1]
        assertEquals(6.75, tx.amount, 0.0001)
        assertEquals(day(2031, 11, 20), tx.date)
        assertEquals("EUROBANK TEST SA ****7778", tx.merchant)
    }

    @Test
    fun `rows on the second page are read after the repeated header`() {
        val tx = txs[2]
        assertEquals(8.25, tx.amount, 0.0001)
        assertEquals(day(2031, 10, 1), tx.date)
    }

    @Test
    fun `a credited transfer with a zero amount is counted as incomplete`() {
        val pages = listOf(
            listOf(
                "Ιστορικό Μεταφορών σε άλλη τράπεζα",
                "07/12/2031 ${StatementTextFixtures.OWN_IBAN} ${StatementTextFixtures.RECIPIENT_IBAN_1} SOME BANK SA 0,00 EUR Πιστώθηκε στον",
                "δικαιούχο"
            )
        )
        val parsed = EurobankTransfersLayout.parse(pages, StatementTextFixtures.context())
        assertTrue(parsed.parsedRows.isEmpty())
        assertEquals(StatementSkipReason.INCOMPLETE_ROW, parsed.skippedRows.single().reason)
    }

    @Test
    fun `no iban and no own account digits are stored anywhere`() {
        val stored = txs.joinToString(" ") { "${it.merchant} ${it.transferAccountName}" }
        assertFalse(Regex("""[A-Z]{2}\d{6,}""").containsMatchIn(stored))
        assertFalse(stored.contains("1234"))
        assertEquals(CurrencyEvidence.ROW, result.parsedRows.first().currencyEvidence)
    }

    @Test
    fun `title column headers and footer produce no rows`() {
        val junk = EurobankTransfersLayout.parse(
            StatementTextFixtures.EUROBANK_TRANSFERS_JUNK_PAGES,
            StatementTextFixtures.context()
        )
        assertTrue(junk.rows.isEmpty())
    }
}
