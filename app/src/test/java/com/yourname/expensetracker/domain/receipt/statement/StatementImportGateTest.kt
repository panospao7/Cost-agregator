package com.yourname.expensetracker.domain.receipt.statement

import com.yourname.expensetracker.domain.parser.ParsedTransaction
import com.yourname.expensetracker.domain.parser.ParsedTransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatementImportGateTest {
    private fun row(
        amount: Double = 12.5,
        date: Long? = StatementTextFixtures.day(2031, 11, 11),
        evidence: CurrencyEvidence = CurrencyEvidence.ROW
    ) = StatementRow.Parsed(
        tx = ParsedTransaction(
            amount = amount,
            currency = "EUR",
            merchant = "SOME SHOP",
            type = ParsedTransactionType.PURCHASE,
            confidence = 0.95f,
            date = date,
            validationNowEpochMs = StatementTextFixtures.NOW_MS
        ),
        currencyEvidence = evidence
    )

    @Test
    fun `row without a date is skipped as no date`() {
        val decision = StatementImportGate.evaluate(row(date = null))
        assertEquals(StatementImportGate.Decision.Skip(StatementSkipReason.NO_DATE), decision)
    }

    @Test
    fun `amount above the ceiling is skipped and the ceiling itself is accepted`() {
        val above = StatementImportGate.evaluate(row(amount = StatementImportGate.AMOUNT_CEILING + 0.01))
        assertEquals(StatementImportGate.Decision.Skip(StatementSkipReason.AMOUNT_ABOVE_CEILING), above)
        val atCeiling = StatementImportGate.evaluate(row(amount = StatementImportGate.AMOUNT_CEILING))
        assertTrue(atCeiling is StatementImportGate.Decision.Accept)
    }

    @Test
    fun `row and header currency count as source evidence`() {
        for (evidence in listOf(CurrencyEvidence.ROW, CurrencyEvidence.STATEMENT_HEADER)) {
            val decision = StatementImportGate.evaluate(row(evidence = evidence))
            assertTrue((decision as StatementImportGate.Decision.Accept).currencyIsSourceEvidence)
        }
    }

    @Test
    fun `missing currency evidence is flagged as an assumption`() {
        val decision = StatementImportGate.evaluate(row(evidence = CurrencyEvidence.NONE))
        assertFalse((decision as StatementImportGate.Decision.Accept).currencyIsSourceEvidence)
    }

    @Test
    fun `skip counts group reasons without any row content`() {
        val rows = listOf(
            StatementRow.Skipped(StatementSkipReason.NO_DATE, 1),
            StatementRow.Skipped(StatementSkipReason.NO_DATE, 2),
            StatementRow.Skipped(StatementSkipReason.CANCELLED, 1),
            row()
        )
        val counts = StatementImportGate.skipCounts(rows)
        assertEquals(2, counts[StatementSkipReason.NO_DATE])
        assertEquals(1, counts[StatementSkipReason.CANCELLED])
        assertEquals(2, counts.size)
    }
}
