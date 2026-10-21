package com.yourname.expensetracker.domain.receipt.statement

import com.yourname.expensetracker.domain.parser.ParsedTransactionType
import com.yourname.expensetracker.domain.parser.ParsedTransferDirection

/**
 * NBG account statement. A row starts with `dd/MM/yyyy HH:mm:ss dd/MM/yyyy BRANCH CODE DESC` and
 * ends with `[Χ|Π] AMOUNT BALANCE`; the tail can sit on a continuation line when the description
 * wraps. The amount is the token after the marker, never the balance.
 */
object NbgAccountLayout : TextStatementLayout {
    override val id: StatementLayoutId = StatementLayoutId.NBG_ACCOUNT

    /** A pending row is abandoned (INCOMPLETE_ROW) after this many continuation lines. */
    private const val MAX_CONTINUATION_LINES = 4

    private val DATE_LED = Regex(
        """^(\d{2}/\d{2}/\d{4})\s+(\d{2}:\d{2}:\d{2})\s+(\d{2}/\d{2}/\d{4})\s+(\d{1,4})\s+(\d{3})(?:\s+(.*))?$"""
    )
    private val TAIL = Regex("""^(.*?)\s*([ΧΠ])\s+([-+−]?\d[\d.,]*)\s+([-+−]?\d[\d.,]*)$""")

    private val FOOTER_PRINT_DATE = Regex("""\d+\s+από\s+\d+\s+Ημ/νία\s+Εκτύπωσης""")
    private const val BANK_NAME_PREFIX = "Εθνική Τράπεζα της Ελλάδος"

    private val OWN_TRANSFER_CODES = setOf("064", "068", "022")
    private const val INCOMING_TRANSFER_CODE = "013"

    private fun isColumnHeader(line: String): Boolean =
        line.contains("Χ/Π") && line.contains("Ποσό") && line.contains("Ημερομηνία")

    /** Per-page repeated header/footer lines that are never part of a row. */
    private fun isPageFurniture(line: String): Boolean =
        line.startsWith(BANK_NAME_PREFIX) ||
            FOOTER_PRINT_DATE.containsMatchIn(line) ||
            line.contains("www.nbg.gr", ignoreCase = true) ||
            line.startsWith("(+30)") ||
            isColumnHeader(line)

    override fun matches(pages: List<List<String>>): Boolean =
        StatementText.flatten(pages).any { isColumnHeader(it.second) }

    private class Pending(
        val page: Int,
        val dateText: String,
        val timeText: String,
        val code: String,
        val text: StringBuilder,
        var continuationLines: Int = 0
    )

    override fun parse(pages: List<List<String>>, ctx: StatementContext): TextStatementParseResult {
        val lines = StatementText.flatten(pages)
        val firstHeader = lines.indexOfFirst { isColumnHeader(it.second) }
        if (firstHeader < 0) return TextStatementParseResult(id, emptyList())
        val headerCurrency = StatementAmountParser.singleHeaderCurrency(
            lines.subList(0, firstHeader).map { it.second }
        )
        val rows = mutableListOf<StatementRow>()
        var pending: Pending? = null

        fun finish(open: Pending, tail: MatchResult) {
            rows += buildRow(open, tail, ctx, headerCurrency)
        }

        for (index in firstHeader + 1 until lines.size) {
            val (page, line) = lines[index]
            if (isPageFurniture(line)) continue

            val dateLed = DATE_LED.matchEntire(line)
            if (dateLed != null) {
                pending?.let { rows += StatementRow.Skipped(StatementSkipReason.INCOMPLETE_ROW, it.page) }
                pending = null
                val open = Pending(
                    page = page,
                    dateText = dateLed.groupValues[1],
                    timeText = dateLed.groupValues[2],
                    code = dateLed.groupValues[5],
                    text = StringBuilder(dateLed.groupValues[6])
                )
                val tail = TAIL.matchEntire(open.text.toString().trim())
                if (tail != null) finish(open, tail) else pending = open
                continue
            }

            val open = pending
            if (open == null) {
                // A tail with no row above it has lost its date; count it instead of guessing.
                if (TAIL.matchEntire(line) != null) {
                    rows += StatementRow.Skipped(StatementSkipReason.NO_DATE, page)
                }
                continue
            }
            open.text.append(' ').append(line)
            open.continuationLines++
            val tail = TAIL.matchEntire(open.text.toString().trim())
            if (tail != null) {
                finish(open, tail)
                pending = null
            } else if (open.continuationLines >= MAX_CONTINUATION_LINES) {
                rows += StatementRow.Skipped(StatementSkipReason.INCOMPLETE_ROW, open.page)
                pending = null
            }
        }
        pending?.let { rows += StatementRow.Skipped(StatementSkipReason.INCOMPLETE_ROW, it.page) }
        return TextStatementParseResult(id, rows)
    }

    private fun buildRow(
        open: Pending,
        tail: MatchResult,
        ctx: StatementContext,
        headerCurrency: String?
    ): StatementRow {
        val description = tail.groupValues[1].trim()
        val credit = tail.groupValues[2] == "Π"
        val amount = StatementAmountParser.parse(tail.groupValues[3])
        val balance = StatementAmountParser.parse(tail.groupValues[4])
        if (amount == null || balance == null) {
            return StatementRow.Skipped(StatementSkipReason.INCOMPLETE_ROW, open.page)
        }
        val direction = if (credit) ParsedTransferDirection.INCOMING else ParsedTransferDirection.OUTGOING
        val date = StatementDates.parseDmyTime(open.dateText, open.timeText, ctx.zone)

        val isTransfer = open.code == INCOMING_TRANSFER_CODE || open.code in OWN_TRANSFER_CODES
        val type: ParsedTransactionType
        val merchant: String
        val accountName: String?
        val txDirection: ParsedTransferDirection?
        if (isTransfer) {
            val label = CounterpartyLabel.fromDescription(description, null, direction)
            type = ParsedTransactionType.TRANSFER
            merchant = label.merchant
            accountName = label.accountName
            txDirection = direction
        } else {
            // Card purchases (040), benefits (076) and unknown codes follow the debit/credit
            // marker: a debit is a PURCHASE, a credit (refund, benefit) an incoming DEPOSIT.
            type = if (credit) ParsedTransactionType.DEPOSIT else ParsedTransactionType.PURCHASE
            merchant = ctx.merchantCleaner.clean(
                CounterpartyLabel.scrubLongDigitRuns(CounterpartyLabel.maskIbans(description))
            )
            accountName = null
            txDirection = if (credit) ParsedTransferDirection.INCOMING else null
        }
        return ctx.buildRow(
            page = open.page,
            amount = amount,
            date = date,
            merchant = merchant,
            type = type,
            direction = txDirection,
            accountName = accountName,
            rowCurrency = null,
            headerCurrency = headerCurrency
        )
    }
}
