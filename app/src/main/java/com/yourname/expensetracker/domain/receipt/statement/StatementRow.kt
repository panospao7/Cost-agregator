package com.yourname.expensetracker.domain.receipt.statement

import com.yourname.expensetracker.domain.parser.ParsedTransaction

/** Text-PDF statement layouts the parser can recognise from their header lines. */
enum class StatementLayoutId {
    REVOLUT_TEXT,
    NBG_ACCOUNT,
    EUROBANK_CARD,
    EUROBANK_TRANSFERS
}

/** Where the currency of a parsed row came from. */
enum class CurrencyEvidence {
    /** A currency symbol or code printed on the row itself. */
    ROW,

    /** The statement header names exactly one currency. */
    STATEMENT_HEADER,

    /** No evidence: the caller's home currency is only an assumption. */
    NONE
}

/** Bounded reasons a statement row is counted but not imported. Never carries row text. */
enum class StatementSkipReason {
    NO_DATE,
    CANCELLED,
    UNKNOWN_STATUS,
    NOT_SETTLED,
    AMOUNT_ABOVE_CEILING,

    /** A dated row whose amount columns never arrived (truncated or unreadable). */
    INCOMPLETE_ROW,

    /** A row whose date parsed but lies in the future (beyond the one-day clock tolerance). */
    UNREASONABLE_DATE
}

sealed class StatementRow {
    data class Parsed(
        val tx: ParsedTransaction,
        val currencyEvidence: CurrencyEvidence
    ) : StatementRow()

    data class Skipped(
        val reason: StatementSkipReason,
        /** 1-based page the row started on. */
        val page: Int
    ) : StatementRow()
}

/**
 * Result of [TextStatementParser.parse]. [layout] is null when no layout matched; the caller then
 * falls back to the generic parser. [chainResets] counts Revolut rows whose balance chain did not
 * fit (direction taken from keywords instead). [failed] is true when a layout matched but its parse
 * threw: [layout] stays set and [rows] is empty, so the caller must not fall back to the generic
 * parser for a statement it already recognised.
 */
data class TextStatementParseResult(
    val layout: StatementLayoutId?,
    val rows: List<StatementRow>,
    val chainResets: Int = 0,
    val failed: Boolean = false
) {
    val parsedRows: List<StatementRow.Parsed>
        get() = rows.filterIsInstance<StatementRow.Parsed>()

    val skippedRows: List<StatementRow.Skipped>
        get() = rows.filterIsInstance<StatementRow.Skipped>()
}
