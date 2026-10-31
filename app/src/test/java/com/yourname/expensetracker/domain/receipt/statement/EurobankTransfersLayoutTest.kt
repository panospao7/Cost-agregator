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

    private val title = "Ιστορικό Μεταφορών σε άλλη τράπεζα"
    private val own = StatementTextFixtures.OWN_IBAN
    private val creditedRow =
        "07/12/2031 $own ${StatementTextFixtures.RECIPIENT_IBAN_1} SOME BANK SA 11,00 EUR Πιστώθηκε στον"

    @Test
    fun `a card debited cancelled row after a credited row does not cancel the credited one`() {
        val pages = listOf(
            listOf(
                title,
                creditedRow,
                "δικαιούχο",
                // Debited from a card, not an IBAN: it fails the strict row pattern.
                "06/12/2031 0000 00** **** 0000 OTHER BANK 4,00 EUR Ακυρώθηκε"
            )
        )
        val parsed = EurobankTransfersLayout.parse(pages, StatementTextFixtures.context())
        assertEquals(1, parsed.parsedRows.size)
        assertEquals(11.0, parsed.parsedRows.single().tx.amount, 0.0001)
        assertEquals(listOf(StatementSkipReason.INCOMPLETE_ROW), parsed.skippedRows.map { it.reason })
    }

    @Test
    fun `a malformed date-led line closes an incomplete open row and is counted itself`() {
        val pages = listOf(
            listOf(
                title,
                "07/12/2031 $own ${StatementTextFixtures.RECIPIENT_IBAN_1} SOME BANK SA",
                "06/12/2031 not a row",
                "11,00 EUR Πιστώθηκε στον δικαιούχο"
            )
        )
        val parsed = EurobankTransfersLayout.parse(pages, StatementTextFixtures.context())
        assertTrue(parsed.parsedRows.isEmpty())
        assertEquals(
            listOf(StatementSkipReason.INCOMPLETE_ROW, StatementSkipReason.INCOMPLETE_ROW),
            parsed.skippedRows.map { it.reason }
        )
    }

    @Test
    fun `a negated status is unknown and never credited`() {
        val pages = listOf(
            listOf(
                title,
                "07/12/2031 $own ${StatementTextFixtures.RECIPIENT_IBAN_1} SOME BANK SA 11,00 EUR Δεν πιστώθηκε",
                "05/12/2031 $own ${StatementTextFixtures.RECIPIENT_IBAN_2} OTHER BANK 4,00 EUR Δεν ακυρώθηκε"
            )
        )
        val parsed = EurobankTransfersLayout.parse(pages, StatementTextFixtures.context())
        assertTrue(parsed.parsedRows.isEmpty())
        assertEquals(
            listOf(StatementSkipReason.UNKNOWN_STATUS, StatementSkipReason.UNKNOWN_STATUS),
            parsed.skippedRows.map { it.reason }
        )
    }

    @Test
    fun `an unrecognised or missing status is unknown and a cancelled status is cancelled`() {
        val pages = listOf(
            listOf(
                title,
                "07/12/2031 $own ${StatementTextFixtures.RECIPIENT_IBAN_1} SOME BANK SA 11,00 EUR",
                "05/12/2031 $own ${StatementTextFixtures.RECIPIENT_IBAN_2} OTHER BANK 4,00 EUR Σε αναμονή",
                "03/12/2031 $own ${StatementTextFixtures.RECIPIENT_IBAN_4} THIRD BANK 5,00 EUR Ακυρώθηκε"
            )
        )
        val parsed = EurobankTransfersLayout.parse(pages, StatementTextFixtures.context())
        assertEquals(
            listOf(
                StatementSkipReason.UNKNOWN_STATUS,
                StatementSkipReason.UNKNOWN_STATUS,
                StatementSkipReason.CANCELLED
            ),
            parsed.skippedRows.map { it.reason }
        )
    }

    @Test(timeout = 20_000)
    fun `hostile 100000 character lines finish quickly and are counted`() {
        val pages = listOf(
            listOf(
                title,
                "*".repeat(100_000),
                "9".repeat(100_000),
                creditedRow,
                "δικαιούχο"
            )
        )
        val parsed = EurobankTransfersLayout.parse(pages, StatementTextFixtures.context())
        assertEquals(1, parsed.parsedRows.size)
        assertEquals(2, parsed.skippedRows.count { it.reason == StatementSkipReason.INCOMPLETE_ROW })
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
