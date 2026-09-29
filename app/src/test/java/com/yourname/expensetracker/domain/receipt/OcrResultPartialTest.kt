package com.yourname.expensetracker.domain.receipt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrResultPartialTest {
    @Test
    fun failedPagesArePartialEvenWhenLegacyProducerCountsAttempts() {
        assertTrue(result(processed = 3, total = 3, failed = 1).isPartial)
    }

    @Test
    fun truncationAndSuccessfulPageCountsArePartial() {
        assertTrue(result(processed = 5, total = 8).isPartial)
        assertTrue(result(processed = 2, total = 3, failed = 1).isPartial)
    }

    @Test
    fun failedPageEvidenceDoesNotRequirePageTotals() {
        assertTrue(result(failed = 1).isPartial)
    }

    @Test
    fun completePdfAndImageAreNotPartial() {
        assertFalse(result(processed = 3, total = 3).isPartial)
        assertFalse(result(processed = 3, total = 3, failed = 0).isPartial)
        assertFalse(result().isPartial)
    }

    private fun result(processed: Int? = null, total: Int? = null, failed: Int? = null) =
        OcrResult("", emptyList(), "", pagesProcessed = processed, totalPages = total, failedPages = failed)
}
