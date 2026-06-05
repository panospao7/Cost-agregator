package com.yourname.expensetracker.domain.transaction

import java.util.Locale

/**
 * Policy for whether a merchant-category mapping learned from a transaction
 * should be promoted to the global dictionary.
 *
 * C10 / E3-NOW-004: Only user-confirmed or review-approved sources create
 * permanent mappings. Auto-accepted notifications, OCR guesses, bank imports,
 * and other machine-generated sources are not trusted for strong learning.
 */
object SourceLearningPolicy {

    /** Sources that are trusted enough to create permanent merchant-category mappings. */
    fun isTrustedForLearning(source: ExpenseSource): Boolean = when (source) {
        ExpenseSource.MANUAL_ENTRY,
        ExpenseSource.MANUAL,
        ExpenseSource.REVIEW_APPROVAL,
        ExpenseSource.RECEIPT_BATCH_REVIEW,
        ExpenseSource.BANK_STATEMENT_REVIEW -> true
        else -> false
    }

    /** String-based overload for callers that pass source as a raw name.
     *
     * Handles both valid [ExpenseSource] enum names and legacy production strings
     * (e.g. "USER_EDIT") that are not enum values but represent user-initiated
     * changes and should therefore be trusted for learning.
     */
    fun isTrustedForLearning(sourceName: String): Boolean {
        val normalized = sourceName.uppercase(Locale.ROOT)
        // Known user-driven production strings that are NOT enum values
        return when (normalized) {
            "USER_EDIT",
            "CATEGORY_CORRECTION",
            "BUSINESS_TAX_UPDATE",
            "USER_ACTION" -> true
            "SYSTEM",
            "MATCHING_WORKER",
            "PARSER_ONLY",
            "RECEIPT_ITEM_MAJORITY",
            "RECEIPT_MATCHER",
            "GROUP_EXPENSE",
            "GROUP_HARD_DELETE" -> false
            else -> try {
                isTrustedForLearning(ExpenseSource.valueOf(normalized))
            } catch (e: IllegalArgumentException) {
                false
            }
        }
    }
}
