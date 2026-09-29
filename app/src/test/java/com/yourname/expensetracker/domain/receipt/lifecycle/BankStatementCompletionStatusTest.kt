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

    @Test
    fun skippedItemsRetainFailureSemantics() {
        assertEquals(
            BankStatementImportRun.STATUS_FAILED,
            bankStatementCompletionStatus(0, 1, 0, true)
        )
    }
}
