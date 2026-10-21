package com.yourname.expensetracker.ui.screens.review

import com.yourname.expensetracker.domain.receipt.lifecycle.BankStatementResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatementImportSummaryTest {
    private fun result(
        found: Int = 10,
        reviews: Int = 8,
        duplicates: Int = 1,
        partial: Boolean = false,
        skipped: Int = 0,
        byReason: Map<String, Int> = emptyMap(),
        pages: Int? = null,
        total: Int? = null
    ) = BankStatementResult(
        receiptId = 1L, transactionsFound = found, reviewsCreated = reviews, duplicatesSkipped = duplicates,
        isPartial = partial, skippedCount = skipped, skippedByReason = byReason,
        pagesProcessed = pages, totalPages = total
    )

    @Test
    fun plainResultKeepsTheExistingCompletionText() {
        // Imported = reviews created + duplicates (8 + 1); the headline is not transactionsFound.
        assertEquals(
            "Imported 9 transactions from statement! (8 reviews created, 1 duplicates skipped)",
            statementImportSummary(result())
        )
    }

    @Test
    fun skippedRowsAreNotCountedInTheImportedHeadline() {
        // 11 rows parsed: 8 reviews + 1 duplicate + 2 skipped (e.g. no date, amount too large).
        val message = statementImportSummary(
            result(found = 11, reviews = 8, duplicates = 1, skipped = 2, byReason = mapOf("NO_DATE" to 1, "AMOUNT_ABOVE_CEILING" to 1))
        )
        assertEquals(
            "Imported 9 transactions from statement! (8 reviews created, 1 duplicates skipped) " +
                "2 skipped (no date: 1, amount too large: 1).",
            message
        )
    }

    @Test
    fun pagesSkippedCountAndReasonsAreReportedAsCountsOnly() {
        val message = statementImportSummary(
            result(skipped = 5, byReason = mapOf("CANCELLED" to 2, "NO_DATE" to 3), pages = 48, total = 48)
        )
        assertEquals(
            "Imported 9 transactions from statement! (8 reviews created, 1 duplicates skipped) " +
                "5 skipped (no date: 3, cancelled: 2). Read 48 of 48 pages.",
            message
        )
    }

    @Test
    fun partialRunKeepsItsLeadAndCarriesTheNumbers() {
        val message = statementImportSummary(
            result(found = 3, reviews = 2, duplicates = 1, partial = true, pages = 5, total = 8)
        )
        assertTrue(message, message.startsWith("Statement partially imported; some pages were not processed (Read 5 of 8 pages). "))
        assertTrue(message.contains("Imported 3 transactions"))
        assertTrue(message.contains("2 reviews created"))
        // The page numbers appear once, in the lead.
        assertEquals(1, Regex("Read 5 of 8 pages").findAll(message).count())
    }

    @Test
    fun partialRunWithoutPageNumbersHasNoParentheses() {
        val message = statementImportSummary(result(partial = true))
        assertTrue(message.startsWith("Statement partially imported; some pages were not processed. Imported 9"))
        assertFalse(message.contains("(Read"))
    }

    @Test
    fun skippedCountWithoutReasonsIsStillShown() {
        assertTrue(statementImportSummary(result(skipped = 4)).endsWith(" 4 skipped."))
    }

    @Test
    fun reasonsWithoutAKnownLabelAreBoundedAndOtherwiseCollapsed() {
        val message = statementImportSummary(
            result(
                skipped = 4,
                byReason = mapOf(
                    "AMOUNT_ABOVE_CEILING" to 1, "UNKNOWN_STATUS" to 1, "NEW_FUTURE_REASON" to 1,
                    "Some Shop 12345" to 1
                )
            )
        )
        assertTrue(message, message.contains("4 skipped (unknown status: 1, amount too large: 1, new future reason: 1, other: 1)."))
        assertFalse(message.contains("Some Shop"))
    }

    @Test
    fun pageNumbersAreClampedAndIgnoredWhenImplausible() {
        assertTrue(statementImportSummary(result(pages = 9, total = 5)).contains("Read 5 of 5 pages."))
        assertFalse(statementImportSummary(result(pages = 3, total = 0)).contains("Read"))
        assertFalse(statementImportSummary(result(pages = null, total = 5)).contains("Read"))
    }

    @Test
    fun summaryNeverContainsMerchantOrAmountText() {
        val message = statementImportSummary(result(skipped = 1, byReason = mapOf("NO_DATE" to 1), pages = 1, total = 1))
        assertFalse(message.contains("EUR"))
        assertFalse(message.contains("IBAN", ignoreCase = true))
    }
}
