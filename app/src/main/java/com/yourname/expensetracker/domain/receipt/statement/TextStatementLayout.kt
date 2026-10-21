package com.yourname.expensetracker.domain.receipt.statement

import com.yourname.expensetracker.domain.core.money.CurrencyAssumption
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import com.yourname.expensetracker.domain.parser.ParsedTransactionType
import com.yourname.expensetracker.domain.parser.ParsedTransferDirection
import com.yourname.expensetracker.domain.util.MerchantCleaner
import java.time.ZoneId

/**
 * One text-PDF statement layout. [matches] is decided from header lines only; [parse] is total:
 * malformed lines become [StatementRow.Skipped] or are ignored, never exceptions.
 */
interface TextStatementLayout {
    val id: StatementLayoutId

    fun matches(pages: List<List<String>>): Boolean

    fun parse(pages: List<List<String>>, ctx: StatementContext): TextStatementParseResult
}

/** Inputs shared by every layout. [homeCurrency] is an assumption only, never source evidence. */
class StatementContext(
    val merchantCleaner: MerchantCleaner,
    val nowMs: Long,
    val zone: ZoneId,
    homeCurrency: String?
) {
    private val resolvedHome: String? =
        CurrencyCode.parse(homeCurrency)?.takeIf { it in CurrencyCode.ALL_SUPPORTED }?.code

    /**
     * Builds one statement row. [rowCurrency] is the currency printed on the row (evidence ROW),
     * [headerCurrency] the single currency named by the statement header (STATEMENT_HEADER);
     * with neither the home currency is only an assumption.
     */
    fun buildRow(
        page: Int,
        amount: Double,
        date: Long?,
        merchant: String,
        type: ParsedTransactionType,
        direction: ParsedTransferDirection?,
        accountName: String?,
        rowCurrency: String?,
        headerCurrency: String?
    ): StatementRow {
        if (date == null) return StatementRow.Skipped(StatementSkipReason.NO_DATE, page)
        val magnitude = kotlin.math.abs(amount)
        if (!magnitude.isFinite() || magnitude <= 0.0) {
            return StatementRow.Skipped(StatementSkipReason.INCOMPLETE_ROW, page)
        }
        if (magnitude > StatementAmountParser.MAX_AMOUNT) {
            return StatementRow.Skipped(StatementSkipReason.AMOUNT_ABOVE_CEILING, page)
        }
        if (date > nowMs + FUTURE_TOLERANCE_MS) {
            return StatementRow.Skipped(StatementSkipReason.NO_DATE, page)
        }
        val evidence: CurrencyEvidence
        val currency: String
        val assumption: CurrencyAssumption
        when {
            rowCurrency != null -> {
                evidence = CurrencyEvidence.ROW
                currency = rowCurrency
                assumption = CurrencyAssumption.PARSED_FROM_SOURCE
            }
            headerCurrency != null -> {
                evidence = CurrencyEvidence.STATEMENT_HEADER
                currency = headerCurrency
                assumption = CurrencyAssumption.PARSED_FROM_SOURCE
            }
            resolvedHome != null -> {
                evidence = CurrencyEvidence.NONE
                currency = resolvedHome
                assumption = CurrencyAssumption.ASSUMED_HOME_CURRENCY
            }
            else -> {
                evidence = CurrencyEvidence.NONE
                currency = UNKNOWN_CURRENCY
                assumption = CurrencyAssumption.UNKNOWN
            }
        }
        // Last scrub for every layout: a payment/card reference never reaches a stored label.
        val safeMerchant = CounterpartyLabel.scrubLongDigitRuns(merchant).trim().ifEmpty { "Unknown" }
        val safeAccountName = accountName?.let { CounterpartyLabel.scrubLongDigitRuns(it) }
        return try {
            StatementRow.Parsed(
                tx = ParsedTransaction(
                    amount = magnitude,
                    currency = currency,
                    merchant = safeMerchant,
                    type = type,
                    confidence = CONFIDENCE,
                    date = date,
                    transferDirection = direction,
                    transferAccountName = safeAccountName?.take(MAX_ACCOUNT_NAME),
                    validationNowEpochMs = nowMs,
                    currencyAssumption = assumption
                ),
                currencyEvidence = evidence
            )
        } catch (e: IllegalArgumentException) {
            StatementRow.Skipped(StatementSkipReason.INCOMPLETE_ROW, page)
        }
    }

    private companion object {
        const val UNKNOWN_CURRENCY = "XXX"
        const val FUTURE_TOLERANCE_MS = 86_400_000L
        const val CONFIDENCE = 0.95f
        const val MAX_ACCOUNT_NAME = 100
    }
}

/** Line helpers shared by the layouts. */
internal object StatementText {
    // Regex escapes (not literal characters): no-break space A0, narrow no-break space 202F, figure space 2007.
    private val WHITESPACE = Regex("""[\s\xA0\x{202F}\x{2007}]+""")

    /** Collapses every whitespace run (including non-breaking spaces) and trims. */
    fun clean(line: String): String = WHITESPACE.replace(line, " ").trim()

    /** Uppercases and removes Greek diacritics so "Πιστώθηκε" compares with "ΠΙΣΤΩΘΗΚΕ". */
    fun fold(text: String): String =
        java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
            .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
            .uppercase()

    /** Flattens pages to (page number, cleaned line) pairs, dropping blank lines. */
    fun flatten(pages: List<List<String>>): List<Pair<Int, String>> =
        pages.flatMapIndexed { index, lines ->
            lines.map { clean(it) }.filter { it.isNotEmpty() }.map { (index + 1) to it }
        }
}
