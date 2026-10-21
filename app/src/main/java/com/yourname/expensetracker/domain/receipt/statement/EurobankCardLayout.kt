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

    private val ROW = Regex(
        """^(\d{2}/\d{2}/\d{4})\s+(\d{2}/\d{2}/\d{4})\s+(.+?)\s+([-+−]?\d[\d.,]*)$"""
    )

    private fun isDescriptionAmountHeader(folded: String): Boolean =
        folded.contains("ΠΕΡΙΓΡΑΦΗ") && folded.contains("ΠΟΣΟ")

    /** Index of the "ΠΕΡΙΓΡΑΦΗ ΠΟΣΟ" header line whose preceding lines name both date columns. */
    private fun headerIndices(lines: List<String>): List<Int> {
        val folded = lines.map { StatementText.fold(it) }
        return folded.indices.filter { index ->
            isDescriptionAmountHeader(folded[index]) &&
                (maxOf(0, index - HEADER_WINDOW)..index)
                    .joinToString(" ") { folded[it] }
                    .let { it.contains("ΣΥΝΑΛΛΑΓΗΣ") && it.contains("ΑΞΙΑΣ") }
        }
    }

    override fun matches(pages: List<List<String>>): Boolean =
        headerIndices(StatementText.flatten(pages).map { it.second }).isNotEmpty()

    override fun parse(pages: List<List<String>>, ctx: StatementContext): TextStatementParseResult {
        val lines = StatementText.flatten(pages)
        val firstHeader = headerIndices(lines.map { it.second }).firstOrNull()
            ?: return TextStatementParseResult(id, emptyList())
        val headerCurrency = StatementAmountParser.singleHeaderCurrency(
            lines.subList(0, firstHeader).map { it.second }
        )
        val rows = mutableListOf<StatementRow>()
        for (index in firstHeader + 1 until lines.size) {
            val (page, line) = lines[index]
            val match = ROW.matchEntire(line) ?: continue
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
                merchant = ctx.merchantCleaner.clean(match.groupValues[3]),
                type = if (credit) ParsedTransactionType.DEPOSIT else ParsedTransactionType.PURCHASE,
                direction = if (credit) ParsedTransferDirection.INCOMING else null,
                accountName = null,
                rowCurrency = null,
                headerCurrency = headerCurrency
            )
        }
        return TextStatementParseResult(id, rows)
    }
}
