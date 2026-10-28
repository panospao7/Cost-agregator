package com.yourname.expensetracker.domain.receipt.statement

import com.yourname.expensetracker.domain.parser.ParsedTransactionType
import com.yourname.expensetracker.domain.parser.ParsedTransferDirection

/**
 * Eurobank card statement. Row: `dd/MM/yyyy dd/MM/yyyy DESCRIPTION AMOUNT` (transaction date,
 * value date, description, amount). A negative amount is a purchase; an unsigned positive amount
 * is a credit (refund or top-up) and becomes an incoming DEPOSIT. Identical rows are all kept.
 */
object EurobankCardLayout : TextStatementLayout {
    override val id: StatementLayoutId = StatementLayoutId.EUROBANK_CARD

    private const val HEADER_WINDOW = 6
    private const val HEADER_TAIL_LINES = 2

    private val ROW = Regex(
        """^(\d{2}/\d{2}/\d{4})\s+(\d{2}/\d{2}/\d{4})\s+(.+?)\s+([-+−]?\d[\d.,]*)$"""
    )

    /** A line that starts a transaction row (its text is data, never header). */
    private val DATE_LED = Regex("""^\d{2}/\d{2}/\d{4}(?:\s|$)""")

    /**
     * A description that ends in a money-shaped token ("SOME SHOP -7,41"): with a second amount on
     * the line this is a statement with a balance column, not this layout's single amount column.
     * A bare integer ("SHOP / 210 949") is not money-shaped.
     */
    private val MONEY_SHAPED = Regex("""^[-+−]?\d[\d.,]*[.,]\d{2}$""")

    /** Balance or debit/credit columns mean a different table: this layout reads one signed amount. */
    private val FOREIGN_COLUMNS = listOf("ΥΠΟΛΟΙΠΟ", "ΧΡΕΩΣΗ", "ΠΙΣΤΩΣΗ")

    private fun isDescriptionAmountHeader(folded: String): Boolean =
        folded.contains("ΠΕΡΙΓΡΑΦΗ") && folded.contains("ΠΟΣΟ")

    /**
     * The header line plus up to [HEADER_TAIL_LINES] following non-row lines, where an extra column
     * title can sit when the header wraps.
     */
    private fun hasForeignColumns(lines: List<String>, folded: List<String>, index: Int): Boolean {
        var text = folded[index]
        var next = index + 1
        while (next < lines.size && next <= index + HEADER_TAIL_LINES && !DATE_LED.containsMatchIn(lines[next])) {
            text += " " + folded[next]
            next++
        }
        return FOREIGN_COLUMNS.any { text.contains(it) }
    }

    /** Index of the "ΠΕΡΙΓΡΑΦΗ ΠΟΣΟ" header line whose preceding lines name both date columns. */
    private fun headerIndices(lines: List<String>): List<Int> {
        val folded = lines.map { StatementText.fold(it) }
        return folded.indices.filter { index ->
            isDescriptionAmountHeader(folded[index]) &&
                (maxOf(0, index - HEADER_WINDOW)..index)
                    .joinToString(" ") { folded[it] }
                    .let { it.contains("ΣΥΝΑΛΛΑΓΗΣ") && it.contains("ΑΞΙΑΣ") } &&
                !hasForeignColumns(lines, folded, index)
        }
    }

    override fun matches(pages: List<List<String>>): Boolean =
        headerIndices(StatementText.flatten(pages).map { it.second }).isNotEmpty()

    override fun parse(pages: List<List<String>>, ctx: StatementContext): TextStatementParseResult {
        val flat = StatementText.flattenCounted(pages)
        val lines = flat.lines
        val firstHeader = headerIndices(lines.map { it.second }).firstOrNull()
            ?: return TextStatementParseResult(id, emptyList())
        val headerCurrency = StatementAmountParser.singleHeaderCurrency(
            lines.subList(0, firstHeader).map { it.second }
        )
        val rows = mutableListOf<StatementRow>()
        for (index in firstHeader + 1 until lines.size) {
            val (page, line) = lines[index]
            val match = ROW.matchEntire(line) ?: continue
            if (MONEY_SHAPED.matches(match.groupValues[3].substringAfterLast(' '))) {
                // Two money tokens on the line: the last number may be a balance, not the amount.
                rows += StatementRow.Skipped(StatementSkipReason.INCOMPLETE_ROW, page)
                continue
            }
            val signed = StatementAmountParser.parse(match.groupValues[4])
            if (signed == null) {
                rows += StatementRow.Skipped(StatementSkipReason.INCOMPLETE_ROW, page)
                continue
            }
            // A zero amount flows on to buildRow, which counts it as Skipped INCOMPLETE_ROW.
            val credit = signed > 0.0
            rows += ctx.buildRow(
                page = page,
                amount = signed,
                date = StatementDates.parseDmy(match.groupValues[1], ctx.zone),
                merchant = ctx.merchantCleaner.clean(
                    CounterpartyLabel.scrubLongDigitRuns(CounterpartyLabel.maskIbans(match.groupValues[3]))
                ),
                type = if (credit) ParsedTransactionType.DEPOSIT else ParsedTransactionType.PURCHASE,
                direction = if (credit) ParsedTransferDirection.INCOMING else null,
                accountName = null,
                rowCurrency = null,
                headerCurrency = headerCurrency
            )
        }
        rows += flat.oversizedSkips()
        return TextStatementParseResult(id, rows)
    }
}
