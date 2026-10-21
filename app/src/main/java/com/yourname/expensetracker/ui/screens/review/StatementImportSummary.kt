package com.yourname.expensetracker.ui.screens.review

import com.yourname.expensetracker.domain.receipt.lifecycle.BankStatementResult

/**
 * User-facing summary of one statement import, built from counts only (never a merchant, amount,
 * IBAN or any statement text). Pure: no Android, no resources, no clock.
 *
 * Shape: "Imported F transactions from statement! (X reviews created, Y duplicates skipped)" where
 * F = X + Y (rows skipped by the run are not part of F), followed,
 * when there is something to report, by " Z skipped (no date: a, cancelled: b)." and
 * " Read N of M pages.". A partial run keeps its "partially imported" lead and now carries the page
 * numbers when they are known.
 */
internal fun statementImportSummary(result: BankStatementResult): String {
    // "Imported" counts rows that became a review or were recognised as a duplicate. Rows the run
    // skipped (layout skips, pre-AI skips such as no date or amount ceiling, AI rejections) are
    // reported only in the "skipped" sentence, never in both numbers: transactionsFound also holds
    // parsed rows that were later skipped, so it is not used for the headline.
    val handled = result.reviewsCreated + result.duplicatesSkipped
    val core = "Imported $handled transactions from statement! " +
        "(${result.reviewsCreated} reviews created, ${result.duplicatesSkipped} duplicates skipped)"
    val pages = pagesPhrase(result.pagesProcessed, result.totalPages)

    val lead = if (result.isPartial) {
        "Statement partially imported; some pages were not processed" +
            (pages?.let { " ($it)" } ?: "") + ". "
    } else {
        ""
    }

    val sentences = buildList {
        skippedPhrase(result)?.let { add(it) }
        // A partial run already carries its page numbers in the lead.
        if (!result.isPartial && pages != null) add("$pages.")
    }
    return buildString {
        append(lead)
        append(core)
        sentences.forEach { append(' ').append(it) }
    }
}

private fun pagesPhrase(processed: Int?, total: Int?): String? {
    if (processed == null || total == null || total <= 0 || processed < 0) return null
    return "Read ${processed.coerceAtMost(total)} of $total pages"
}

/** "Z skipped (no date: a, cancelled: b)." or null when nothing was skipped. */
private fun skippedPhrase(result: BankStatementResult): String? {
    val byReason = result.skippedByReason.filterValues { it > 0 }
    val total = maxOf(result.skippedCount, byReason.values.sum())
    if (total <= 0) return null
    if (byReason.isEmpty()) return "$total skipped."
    val ordered = byReason.entries.sortedWith(
        compareBy<Map.Entry<String, Int>>({ reasonRank(it.key) }, { it.key })
    )
    return "$total skipped (" + ordered.joinToString(", ") { "${reasonLabel(it.key)}: ${it.value}" } + ")."
}

private val REASON_ORDER = listOf(
    "NO_DATE", "UNREASONABLE_DATE", "CANCELLED", "UNKNOWN_STATUS", "NOT_SETTLED", "AMOUNT_ABOVE_CEILING",
    "INCOMPLETE_ROW", "CURRENCY_UNKNOWN", "AI_REJECTED"
)

private fun reasonRank(code: String): Int =
    REASON_ORDER.indexOf(code).let { if (it >= 0) it else REASON_ORDER.size }

private val BOUNDED_REASON_CODE = Regex("^[A-Z][A-Z_]{0,39}$")

private fun reasonLabel(code: String): String = when (code) {
    "NO_DATE" -> "no date"
    "UNREASONABLE_DATE" -> "date in the future"
    "CANCELLED" -> "cancelled"
    "UNKNOWN_STATUS" -> "unknown status"
    "NOT_SETTLED" -> "pending"
    "AMOUNT_ABOVE_CEILING" -> "amount too large"
    "INCOMPLETE_ROW" -> "incomplete"
    "CURRENCY_UNKNOWN" -> "unknown currency"
    "AI_REJECTED" -> "not a transaction"
    // Only bounded upper-case codes are ever rendered; anything else collapses to "other".
    else -> if (BOUNDED_REASON_CODE.matches(code)) code.lowercase().replace('_', ' ') else "other"
}
