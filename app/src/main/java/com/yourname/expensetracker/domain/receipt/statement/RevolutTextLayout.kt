package com.yourname.expensetracker.domain.receipt.statement

import com.yourname.expensetracker.domain.parser.ParsedTransactionType
import com.yourname.expensetracker.domain.parser.ParsedTransferDirection
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Revolut text statement. Rows start after `Date Description Money out Money in Balance` and are
 * date-led (`Jan 10, 2023 Transfer to JOHN DOE EUR20.00 EUR0.00`). The last two currency amounts on
 * the row are the amount and the balance after it. The direction comes from the balance chain
 * (opening balance from the "Account (Current Account)" summary row), with a keyword fallback when
 * a row does not fit the chain. `Reference:` and `Card:` lines are never stored.
 */
object RevolutTextLayout : TextStatementLayout {
    override val id: StatementLayoutId = StatementLayoutId.REVOLUT_TEXT

    private val TABLE_HEADER = Regex("""^Date\s+Description\s+Money out\s+Money in\s+Balance$""")
    private val UNSETTLED_SECTION = Regex("""^(?:Pending|Reverted)\b""")
    private val DATE_LED = Regex("""^([A-Za-z]{3}\s+\d{1,2},\s*\d{4})\s+(.*)$""")
    private const val SUMMARY_ROW_PREFIX = "Account (Current Account)"
    private const val MAX_SECTION_TITLE_LENGTH = 100
    private const val CURRENCY_EXCHANGE_LABEL = "Currency exchange"

    override fun matches(pages: List<List<String>>): Boolean =
        StatementText.flatten(pages).any { TABLE_HEADER.matches(it.second) }

    private class RawRow(
        val page: Int,
        val dateText: String,
        val description: String,
        val amount: StatementAmountParser.CurrencyAmount,
        val balance: StatementAmountParser.CurrencyAmount,
        var fee: Double = 0.0,
        var counterparty: String? = null
    )

    private sealed class Entry {
        class Row(val raw: RawRow) : Entry()
        class Skip(val skipped: StatementRow.Skipped) : Entry()
    }

    override fun parse(pages: List<List<String>>, ctx: StatementContext): TextStatementParseResult {
        val lines = StatementText.flatten(pages)
        val openingBalance = lines
            .firstOrNull { it.second.startsWith(SUMMARY_ROW_PREFIX) }
            ?.let { StatementAmountParser.findCurrencyAmounts(it.second).firstOrNull()?.value }

        val entries = mutableListOf<Entry>()
        var inTable = false
        var unsettled = false
        var current: RawRow? = null

        for ((page, line) in lines) {
            if (TABLE_HEADER.matches(line)) {
                // Only the settled table carries the Balance column; Pending/Reverted sections use
                // a header without it, so reaching this header always ends any unsettled section.
                inTable = true
                unsettled = false
                current = null
                continue
            }
            if (line.length <= MAX_SECTION_TITLE_LENGTH && UNSETTLED_SECTION.containsMatchIn(line) &&
                !DATE_LED.matches(line)
            ) {
                unsettled = true
                inTable = true
                current = null
                continue
            }
            if (!inTable) continue

            val dateLed = DATE_LED.matchEntire(line)
            if (dateLed != null && StatementDates.isRevolutMonth(dateLed.groupValues[1].take(3))) {
                current = null
                if (unsettled) {
                    entries += Entry.Skip(StatementRow.Skipped(StatementSkipReason.NOT_SETTLED, page))
                    continue
                }
                val rest = dateLed.groupValues[2]
                val amounts = StatementAmountParser.findCurrencyAmounts(rest)
                if (amounts.size < 2) {
                    entries += Entry.Skip(StatementRow.Skipped(StatementSkipReason.INCOMPLETE_ROW, page))
                    continue
                }
                val amount = amounts[amounts.size - 2]
                val balance = amounts.last()
                val raw = RawRow(
                    page = page,
                    dateText = dateLed.groupValues[1],
                    description = rest.substring(0, amount.start).trim(),
                    amount = amount,
                    balance = balance
                )
                entries += Entry.Row(raw)
                current = raw
                continue
            }

            val row = current ?: continue
            when {
                line.startsWith("Fee:") ->
                    StatementAmountParser.findCurrencyAmounts(line).firstOrNull()?.let { row.fee = abs(it.value) }
                line.startsWith("To:") || line.startsWith("From:") ->
                    row.counterparty = line.substringAfter(':').trim()
                // "Reference:", "Card:", foreign amounts, rate notes and page junk are never stored.
            }
        }
        return resolve(entries, openingBalance, ctx)
    }

    private fun cents(value: Double): Long = (value * 100.0).roundToLong()

    private fun resolve(
        entries: List<Entry>,
        openingBalance: Double?,
        ctx: StatementContext
    ): TextStatementParseResult {
        val rows = mutableListOf<StatementRow>()
        var previous: Long? = openingBalance?.let { cents(it) }
        var chainResets = 0
        for (entry in entries) {
            when (entry) {
                is Entry.Skip -> rows += entry.skipped
                is Entry.Row -> {
                    val raw = entry.raw
                    val amountCents = cents(abs(raw.amount.value))
                    val balanceCents = cents(raw.balance.value)
                    val feeCents = cents(raw.fee)
                    var chain: ParsedTransferDirection? = null
                    val before = previous
                    if (before != null) {
                        chain = when {
                            before + amountCents == balanceCents -> ParsedTransferDirection.INCOMING
                            before - amountCents == balanceCents -> ParsedTransferDirection.OUTGOING
                            feeCents > 0 && before - (amountCents + feeCents) == balanceCents ->
                                ParsedTransferDirection.OUTGOING
                            else -> null
                        }
                        if (chain == null) chainResets++
                    }
                    previous = balanceCents
                    // A zero amount is still a counted row: buildRow turns it into a Skipped
                    // INCOMPLETE_ROW so the summary counts add up.
                    rows += toRow(raw, chain, ctx)
                }
            }
        }
        return TextStatementParseResult(id, rows, chainResets)
    }

    private fun toRow(
        raw: RawRow,
        chainDirection: ParsedTransferDirection?,
        ctx: StatementContext
    ): StatementRow {
        val description = raw.description
        val upper = description.uppercase()
        val transferKeyword = upper.startsWith("TRANSFER TO") || upper.startsWith("TRANSFER FROM") ||
            upper.startsWith("RECEIVED FROM")
        val keywordDirection = when {
            upper.startsWith("TRANSFER TO") -> ParsedTransferDirection.OUTGOING
            upper.startsWith("TRANSFER FROM") || upper.startsWith("TOP-UP BY") ||
                upper.startsWith("RECEIVED") -> ParsedTransferDirection.INCOMING
            else -> ParsedTransferDirection.OUTGOING
        }
        val direction = chainDirection ?: keywordDirection

        val type: ParsedTransactionType
        val merchant: String
        val accountName: String?
        val txDirection: ParsedTransferDirection?
        when {
            transferKeyword -> {
                val label = transferLabel(raw, direction)
                type = ParsedTransactionType.TRANSFER
                merchant = label.merchant
                accountName = label.accountName
                txDirection = direction
            }
            upper.startsWith("EXCHANGED") -> {
                // A currency exchange moves money between the user's own pockets: a transfer whose
                // direction is the EUR balance movement, with no counterparty to name.
                type = ParsedTransactionType.TRANSFER
                merchant = CURRENCY_EXCHANGE_LABEL
                accountName = null
                txDirection = direction
            }
            direction == ParsedTransferDirection.INCOMING -> {
                type = ParsedTransactionType.DEPOSIT
                merchant = if (upper.startsWith("TOP-UP BY")) "Revolut top-up" else cleanMerchant(description, ctx)
                accountName = null
                txDirection = ParsedTransferDirection.INCOMING
            }
            else -> {
                type = ParsedTransactionType.PURCHASE
                merchant = cleanMerchant(description, ctx)
                accountName = null
                txDirection = null
            }
        }
        return ctx.buildRow(
            page = raw.page,
            amount = abs(raw.amount.value),
            date = StatementDates.parseRevolut(raw.dateText, ctx.zone),
            merchant = merchant,
            type = type,
            direction = txDirection,
            accountName = accountName,
            rowCurrency = raw.amount.currency,
            headerCurrency = null
        )
    }

    private fun cleanMerchant(description: String, ctx: StatementContext): String =
        ctx.merchantCleaner.clean(CounterpartyLabel.maskIbans(description))

    /** Name from the To:/From: line, else from the description; masked reference as last resort. */
    private fun transferLabel(raw: RawRow, direction: ParsedTransferDirection): CounterpartyLabel.Label {
        val fromDescription = raw.description
            .replace(Regex("""(?i)^(?:transfer to|transfer from|received from)\s*"""), "")
        val counterparty = raw.counterparty.orEmpty()
        val mask = Regex("""\*+\s*(\d{2,4})$""").find(counterparty)?.groupValues?.get(1)
        CounterpartyLabel.fromName(counterparty, direction)?.let { return it }
        CounterpartyLabel.fromName(fromDescription, direction)?.let { return it }
        if (mask != null) return CounterpartyLabel.fromReference("Revolut", mask, direction)
        return CounterpartyLabel.fromDescription(fromDescription, "Revolut", direction)
    }
}
