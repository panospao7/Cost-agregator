package com.yourname.expensetracker.domain.receipt.lifecycle

import com.yourname.expensetracker.data.database.entity.BankStatementImportRun
import com.yourname.expensetracker.domain.receipt.OcrResult
import org.junit.Assert.assertEquals
import org.junit.Test

class BankStatementCompletionStatusTest {
    @Test
    fun completeRecognitionAndItemsProduceCompletedStatus() {
        assertEquals(
            BankStatementImportRun.STATUS_COMPLETED,
            bankStatementCompletionStatus(0, 0, 0, false)
        )
    }

    @Test
    fun duplicatesRetainCompletedWithSkipsStatus() {
        assertEquals(
            BankStatementImportRun.STATUS_COMPLETED_WITH_SKIPS,
            bankStatementCompletionStatus(0, 0, 1, false)
        )
    }

    @Test
    fun partialRecognitionCannotProduceFullyCompletedStatus() {
        val results = listOf(
            OcrResult("", emptyList(), "", pagesProcessed = 3, totalPages = 3, failedPages = 1),
            OcrResult("", emptyList(), "", pagesProcessed = 2, totalPages = 3, failedPages = 1),
            OcrResult("", emptyList(), "", pagesProcessed = 5, totalPages = 8),
            OcrResult("", emptyList(), "", failedPages = 1)
        )
        for (ocr in results) {
            assertEquals(
                BankStatementImportRun.STATUS_COMPLETED_WITH_SKIPS,
                bankStatementCompletionStatus(0, 0, 0, ocr.isPartial)
            )
        }
    }

    @Test
    fun failedItemsTakePrecedenceOverPartialRecognitionAndDuplicates() {
        assertEquals(
            BankStatementImportRun.STATUS_FAILED,
            bankStatementCompletionStatus(1, 0, 2, true)
        )
    }

    // Owner decision O2 (statement-import plan): skipped rows no longer fail a run by themselves.
    // This assertion previously pinned (0 failed, 1 skipped, partial) => FAILED regardless of
    // reviews. A run now FAILS only when an item failed or nothing usable came out of it; skipped
    // rows next to at least one created review end COMPLETED_WITH_SKIPS. The failure semantics it
    // used to pin now live in skippedItemsWithoutAnyUsableOutcomeStillFailTheRun.
    @Test
    fun skippedItemsNextToAtLeastOneCreatedReviewCompleteWithSkips() {
        assertEquals(
            BankStatementImportRun.STATUS_COMPLETED_WITH_SKIPS,
            bankStatementCompletionStatus(0, 1, 0, true, createdItemCount = 1)
        )
        assertEquals(
            BankStatementImportRun.STATUS_COMPLETED_WITH_SKIPS,
            bankStatementCompletionStatus(0, 3, 0, false, createdItemCount = 2)
        )
    }

    @Test
    fun skippedItemsWithoutAnyUsableOutcomeStillFailTheRun() {
        // The original failure semantics survive for runs that produced nothing.
        assertEquals(
            BankStatementImportRun.STATUS_FAILED,
            bankStatementCompletionStatus(0, 1, 0, true)
        )
        assertEquals(
            BankStatementImportRun.STATUS_FAILED,
            bankStatementCompletionStatus(0, 2, 0, false, createdItemCount = 0)
        )
    }

    @Test
    fun skippedItemsNextToDuplicatesOnlyAreNotFailed() {
        assertEquals(
            BankStatementImportRun.STATUS_COMPLETED_WITH_SKIPS,
            bankStatementCompletionStatus(0, 1, 2, false, createdItemCount = 0)
        )
    }

    @Test
    fun failedItemsStillFailTheRunEvenWhenReviewsWereCreated() {
        assertEquals(
            BankStatementImportRun.STATUS_FAILED,
            bankStatementCompletionStatus(1, 0, 0, false, createdItemCount = 5)
        )
    }
}
