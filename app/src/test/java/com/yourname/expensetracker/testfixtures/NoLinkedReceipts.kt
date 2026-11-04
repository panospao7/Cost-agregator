package com.yourname.expensetracker.testfixtures

import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLinkService
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.mockk

/**
 * P-03-1: a receipt link owner for coordinator tests that do not exercise receipt unlinking:
 * every expense has no linked receipts, so `deleteExpense` behaves as it does with none.
 * Tests of the unlink itself use a real [ReceiptLinkService].
 */
fun noLinkedReceiptsLinkService(): Lazy<ReceiptLinkService> {
    val service = mockk<ReceiptLinkService>(relaxed = true)
    coEvery { service.getLinksForExpense(any()) } returns emptyList()
    return Lazy { service }
}
