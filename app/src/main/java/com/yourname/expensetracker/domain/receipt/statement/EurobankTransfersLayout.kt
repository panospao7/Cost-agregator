package com.yourname.expensetracker.domain.receipt.statement

import com.yourname.expensetracker.domain.parser.ParsedTransactionType
import com.yourname.expensetracker.domain.parser.ParsedTransferDirection

/**
 * Eurobank "transfers to other bank" history. A row runs from one date line to the next and reads
 * `dd/MM/yyyy OWN_IBAN RECIPIENT_IBAN BANK AMOUNT CUR STATUS`; the recipient IBAN and the status can
 * wrap onto following lines. Only credited transfers are imported (always TRANSFER OUTGOING); the
 * account holder's own IBAN is ignored and the counterparty IBAN never leaves this file, only the
 * label "<BANK> ****<last4>".
 */
object EurobankTransfersLayout : TextStatementLayout {
    override val id: StatementLayoutId = StatementLayoutId.EUROBANK_TRANSFERS

    private const val TITLE = "ΙΣΤΟΡΙΚΟ ΜΕΤΑΦΟΡΩΝ ΣΕ ΑΛΛΗ ΤΡΑΠΕΖΑ"
    private const val GREEK_IBAN_LENGTH = 27
    private const val MAX_STATUS_LENGTH = 80

    /** Folded Greek negations ("ΔΕΝ", "ΜΗ", "ΟΧΙ"): a negated status is never a credit. */
    private val NEGATIONS = setOf("ΔΕΝ", "ΜΗ", "ΜΗΝ", "ΟΧΙ")

    private val DATE_LED = Regex("""^(\d{2}/\d{2}/\d{4})\s+([A-Z]{2}\d{2}[A-Z0-9]*)(?:\s.*)?$""")
    private val DATE_START = Regex("""^\d{2}/\d{2}/\d{4}(?:\s|$)""")
    private val IBAN_START = Regex("""^[A-Z]{2}\d{2}[A-Z0-9]+$""")
    private val DIGITS = Regex("""^\d+$""")
    private val AMOUNT_CURRENCY = Regex("""^(\d[\d.,]*)\s+(EUR|USD|GBP)\b\s*(.*)$""")
    private val PAGE_NUMBER = Regex("""^Σελίδα\s+\d+/\d+""")

    /** Lines that end a row buffer: the page footer. */
    private fun isFooter(line: String): Boolean =
        line.startsWith("Για οποιαδήποτε πληροφορία") ||
            line.contains("ebanking@", ignoreCase = true) ||
            line.startsWith("ΤΡΑΠΕΖΑ EUROBANK") ||
            line.contains("Γ.Ε.ΜΗ") ||
            line.contains("Ημ/νία και ώρα") ||
            PAGE_NUMBER.containsMatchIn(line)

    override fun matches(pages: List<List<String>>): Boolean =
        StatementText.flatten(pages).any { StatementText.fold(it.second).contains(TITLE) }

    private class Buffer(val page: Int, val dateText: String, val parts: MutableList<String>)

    override fun parse(pages: List<List<String>>, ctx: StatementContext): TextStatementParseResult {
        val rows = mutableListOf<StatementRow>()
        var buffer: Buffer? = null
        var inTable = false
        val flat = StatementText.flattenCounted(pages)

        fun flush() {
            buffer?.let { rows += toRow(it, ctx) }
            buffer = null
        }

        for ((page, line) in flat.lines) {
            // Lines above the first title line (cover text, search period) are never rows.
            if (!inTable) {
                if (StatementText.fold(line).contains(TITLE)) inTable = true
                continue
            }
            if (isFooter(line)) {
                flush()
                continue
            }
            val dateLed = DATE_LED.matchEntire(line)
            if (dateLed != null) {
                flush()
                buffer = Buffer(page, dateLed.groupValues[1], mutableListOf(line.substringAfter(' ').trim()))
                continue
            }
            if (DATE_START.containsMatchIn(line)) {
                // A date-led line that fails the strict row pattern still starts a new row: it closes
                // the open one and is counted itself, never appended to the previous row.
                flush()
                rows += StatementRow.Skipped(StatementSkipReason.INCOMPLETE_ROW, page)
                continue
            }
            buffer?.parts?.add(line)
        }
        flush()
        rows += flat.oversizedSkips()
        return TextStatementParseResult(id, rows)
    }

    private fun toRow(buffer: Buffer, ctx: StatementContext): StatementRow {
        val tokens = buffer.parts.joinToString(" ").split(' ').filter { it.isNotEmpty() }
        // tokens: own IBAN, recipient IBAN (maybe wrapped), bank name words, amount, CUR, status words.
        var index = 0
        val own = readIban(tokens, index) ?: return skipped(buffer, StatementSkipReason.INCOMPLETE_ROW)
        index = own.second
        val recipient = readIban(tokens, index) ?: return skipped(buffer, StatementSkipReason.INCOMPLETE_ROW)
        index = recipient.second

        val remainder = tokens.drop(index).joinToString(" ")
        val amountStart = Regex("""(?:^|\s)(\d[\d.,]*\s+(?:EUR|USD|GBP)\b.*)$""").find(remainder)
            ?: return skipped(buffer, StatementSkipReason.INCOMPLETE_ROW)
        val bank = remainder.substring(0, amountStart.groups[1]!!.range.first).trim()
        val tail = AMOUNT_CURRENCY.matchEntire(amountStart.groupValues[1])
            ?: return skipped(buffer, StatementSkipReason.INCOMPLETE_ROW)
        val amount = StatementAmountParser.parse(tail.groupValues[1])
            ?: return skipped(buffer, StatementSkipReason.INCOMPLETE_ROW)
        val currency = tail.groupValues[2]
        // Only this row's own tokens after the amount and currency decide the status.
        val status = StatementText.fold(tail.groupValues[3])
        val words = status.split(' ').filter { it.isNotEmpty() }
        val negated = words.any { it in NEGATIONS }
        val cancelled = status.contains("ΑΚΥΡΩΘΗΚΕ")
        val credited = status.contains("ΠΙΣΤΩΘΗΚΕ")
        when {
            // "Δεν πιστώθηκε", both words at once, an oversized or unrecognised status: never imported.
            negated || cancelled == credited || status.length > MAX_STATUS_LENGTH ->
                return skipped(buffer, StatementSkipReason.UNKNOWN_STATUS)
            cancelled -> return skipped(buffer, StatementSkipReason.CANCELLED)
        }
        if (amount == 0.0) return skipped(buffer, StatementSkipReason.INCOMPLETE_ROW)

        val label = CounterpartyLabel.fromIban(recipient.first, bank, ParsedTransferDirection.OUTGOING)
        return ctx.buildRow(
            page = buffer.page,
            amount = amount,
            date = StatementDates.parseDmy(buffer.dateText, ctx.zone),
            merchant = label.merchant,
            type = ParsedTransactionType.TRANSFER,
            direction = ParsedTransferDirection.OUTGOING,
            accountName = label.accountName,
            rowCurrency = currency,
            headerCurrency = null
        )
    }

    private fun skipped(buffer: Buffer, reason: StatementSkipReason): StatementRow =
        StatementRow.Skipped(reason, buffer.page)

    /**
     * Reads one IBAN starting at [start]; a Greek IBAN that wraps is repaired by appending the
     * following digit-only pieces until it reaches 27 characters. Returns the IBAN and the next index.
     */
    private fun readIban(tokens: List<String>, start: Int): Pair<String, Int>? {
        val first = tokens.getOrNull(start)?.takeIf { IBAN_START.matches(it) } ?: return null
        var iban = first
        var next = start + 1
        if (first.startsWith("GR")) {
            while (iban.length < GREEK_IBAN_LENGTH) {
                val piece = tokens.getOrNull(next)?.takeIf { DIGITS.matches(it) } ?: break
                if (iban.length + piece.length > GREEK_IBAN_LENGTH) break
                iban += piece
                next++
            }
        }
        return iban to next
    }
}
