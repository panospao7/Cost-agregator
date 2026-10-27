package com.yourname.expensetracker.domain.export

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.domain.transaction.SpendingExclusionReason

/**
 * Export marking for rows flagged by the installment spending exclusion (loan-installments OD-11, INV-1).
 *
 * Flagged rows (`spendingExclusionReason != null`) are exported by the generic CSV/JSON export, the
 * PDF and the business report with a fixed marker in the existing notes field (or a summary line) so
 * a reader knows the row is not counted in spending. ALL THREE accounting formats (Xero, FreshBooks
 * and QuickBooks IIF) EXCLUDE flagged rows (owner-approvals 41(a)/42) via [isInstallmentFlagged];
 * their empty check, policy validation sample and recordCount use only unflagged rows. Marker labels:
 *  - [SpendingExclusionReason.INSTALLMENT_PURCHASE] -> [NOT_COUNTED]
 *  - [SpendingExclusionReason.INSTALLMENT_REVERSED] -> [REFUNDED]
 *
 * This helper only chooses a label. It does NOT decide what counts as spending: every export total
 * keeps using `Expense.effectiveAmount` / `Expense.countsAsSpending`, the Kotlin projection of the
 * canonical predicate. No plan data (plan name, lender, schedule, plan id) is ever written; the
 * marker is one of two fixed English strings. Exports are English-only today (headers, labels and
 * report text are hardcoded literals), so the marker is not a localized string resource.
 *
 * Importers call [isMarkedNotes] and SKIP rows whose notes begin with a marker (owner-approvals 41(b)),
 * counting them as skipped. The import request has no spending-exclusion field, so re-importing can
 * never create a flag.
 */
object ExportInstallmentMarker {
    const val NOT_COUNTED: String = "[not counted (installment plan)]"
    const val REFUNDED: String = "[refunded]"

    private val ALL_MARKERS: List<String> = listOf(NOT_COUNTED, REFUNDED)

    /** Marker for a stored reason code, or null for an unflagged row. Unknown codes fail closed to [NOT_COUNTED]. */
    fun markerFor(reason: String?): String? = when (reason) {
        null -> null
        SpendingExclusionReason.INSTALLMENT_REVERSED.name -> REFUNDED
        else -> NOT_COUNTED
    }

    fun markerFor(expense: Expense): String? = markerFor(expense.spendingExclusionReason)

    /**
     * Single owner of the "is this row installment-flagged" rule (owner-approvals 41(a)): any stored
     * spending exclusion reason code marks the row (INSTALLMENT_PURCHASE or INSTALLMENT_REVERSED;
     * unknown codes fail closed to flagged, consistent with [markerFor]).
     */
    fun isInstallmentFlagged(reason: String?): Boolean = reason != null

    fun isInstallmentFlagged(expense: Expense): Boolean = isInstallmentFlagged(expense.spendingExclusionReason)

    fun isInstallmentFlagged(transaction: ExportTransaction): Boolean =
        isInstallmentFlagged(transaction.spendingExclusionReason)

    /**
     * True when [notes] begin with one of the two fixed export markers (owner-approvals 41(b)).
     * Importers skip such rows; they are rows the exporter itself marked as installment-flagged.
     */
    fun isMarkedNotes(notes: String?): Boolean {
        if (notes == null) return false
        val trimmed = notes.trimStart()
        return ALL_MARKERS.any { trimmed.startsWith(it) }
    }

    /** Notes with the marker prefixed; unflagged rows (null marker) return [notes] unchanged. */
    fun notesWithMarker(notes: String?, marker: String?): String? {
        if (marker == null) return notes
        return if (notes.isNullOrBlank()) marker else "$marker $notes"
    }

    /** Count of flagged rows per marker, for report summaries. Rows are counted, never summed. */
    fun countByMarker(expenses: List<Expense>): Map<String, Int> =
        expenses.mapNotNull { markerFor(it) }.groupingBy { it }.eachCount()
}
