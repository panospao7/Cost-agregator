package com.yourname.expensetracker.domain.receipt.statement

import com.yourname.expensetracker.domain.parser.ParsedTransaction

/**
 * Processor-side checks for one parsed statement row, as a pure function so they can be tested
 * without Room: a per-item amount ceiling, a mandatory date, and the source-currency evidence.
 */
object StatementImportGate {
    /** Per-item amount ceiling (the parser's own invariant is 1,000,000). */
    const val AMOUNT_CEILING: Double = 100_000.0

    sealed class Decision {
        /** [currencyIsSourceEvidence] is false when the currency is only the home-currency assumption. */
        data class Accept(
            val tx: ParsedTransaction,
            val currencyIsSourceEvidence: Boolean
        ) : Decision()

        data class Skip(val reason: StatementSkipReason) : Decision()
    }

    fun evaluate(row: StatementRow.Parsed): Decision {
        val tx = row.tx
        if (tx.date == null) return Decision.Skip(StatementSkipReason.NO_DATE)
        if (tx.amount > AMOUNT_CEILING) return Decision.Skip(StatementSkipReason.AMOUNT_ABOVE_CEILING)
        return Decision.Accept(tx, row.currencyEvidence != CurrencyEvidence.NONE)
    }

    /** Skipped-row counts by reason, for run summaries; carries no row content. */
    fun skipCounts(rows: List<StatementRow>): Map<StatementSkipReason, Int> =
        rows.filterIsInstance<StatementRow.Skipped>().groupingBy { it.reason }.eachCount()
}
