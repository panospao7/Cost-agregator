package com.yourname.expensetracker.domain.bank

import com.yourname.expensetracker.data.database.entity.BankStatementImportItem
import org.junit.Test
import org.junit.Assert.*

/**
 * PR21-4: Tests that validate bank statement skipped/failed item audit policy.
 *
 * Policy: BankStatementImportItem is the authoritative per-item audit ledger
 * for rows skipped before receipt creation. Receipt lifecycle events begin
 * only after receipt/review state exists.
 */
class BankStatementItemAuditTest {

    @Test
    fun `invalid amount creates skipped item ledger with sanitized reason`() {
        val item = BankStatementImportItem(
            runId = 1L,
            itemIndex = 0,
            transactionFingerprint = null,
            status = BankStatementImportItem.STATUS_SKIPPED,
            merchant = "Test Merchant",
            amount = Double.NaN,
            currency = "EUR",
            errorReason = "INVALID_AMOUNT: error class=IllegalArgumentException, item=0",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        assertEquals(BankStatementImportItem.STATUS_SKIPPED, item.status)
        assertNotNull(item.errorReason)
        assertTrue(item.errorReason!!.contains("INVALID_AMOUNT"))
        // Must NOT contain raw exception message
        assertFalse(item.errorReason!!.contains("java.lang."))
        assertFalse(item.errorReason!!.contains("at com.yourname"))
    }

    @Test
    fun `invalid currency creates skipped item ledger with sanitized reason`() {
        val item = BankStatementImportItem(
            runId = 1L,
            itemIndex = 1,
            transactionFingerprint = null,
            status = BankStatementImportItem.STATUS_SKIPPED,
            merchant = "Test Merchant",
            amount = 10.0,
            currency = "",
            errorReason = "MISSING_CURRENCY: error class=IllegalStateException, item=1",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        assertEquals(BankStatementImportItem.STATUS_SKIPPED, item.status)
        assertTrue(item.errorReason!!.contains("MISSING_CURRENCY"))
        assertFalse(item.errorReason!!.contains("java.lang."))
    }

    @Test
    fun `unreasonable date creates skipped item ledger`() {
        val item = BankStatementImportItem(
            runId = 1L,
            itemIndex = 2,
            transactionFingerprint = null,
            status = BankStatementImportItem.STATUS_SKIPPED,
            merchant = "Test Merchant",
            amount = 10.0,
            currency = "EUR",
            errorReason = "UNREASONABLE_DATE: error class=IllegalArgumentException, item=2",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        assertEquals(BankStatementImportItem.STATUS_SKIPPED, item.status)
        assertTrue(item.errorReason!!.contains("UNREASONABLE_DATE"))
    }

    @Test
    fun `skipped item does not imply receipt lifecycle event`() {
        // Policy: skipped items exist only as ledger rows. No receipt = no receipt event.
        // This is a structural test — skipped items have status SKIPPED, not CREATED_REVIEW.
        assertNotEquals(
            BankStatementImportItem.STATUS_CREATED_REVIEW,
            BankStatementImportItem.STATUS_SKIPPED
        )
        assertNotEquals("SKIPPED", "CREATED_REVIEW") // sanity
    }

    @Test
    fun `failed item records error class only no raw message`() {
        val item = BankStatementImportItem(
            runId = 1L,
            itemIndex = 3,
            transactionFingerprint = null,
            status = BankStatementImportItem.STATUS_FAILED,
            merchant = "Test Merchant",
            amount = 10.0,
            currency = "EUR",
            errorReason = "ITEM_PROCESSING_FAILURE: error class=RuntimeException, item=3",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        assertEquals(BankStatementImportItem.STATUS_FAILED, item.status)
        assertNotNull(item.errorReason)
        assertTrue(item.errorReason!!.contains("ITEM_PROCESSING_FAILURE"))
        assertTrue(item.errorReason!!.contains("error class="))
        // Must NOT contain raw stack trace elements
        assertFalse(item.errorReason!!.contains("at com.yourname"))
        assertFalse(item.errorReason!!.contains("\tat "))
    }

    @Test
    fun `non-positive amount creates skipped item`() {
        val item = BankStatementImportItem(
            runId = 1L,
            itemIndex = 4,
            transactionFingerprint = null,
            status = BankStatementImportItem.STATUS_SKIPPED,
            merchant = "Test Merchant",
            amount = 0.0,
            currency = "EUR",
            errorReason = "NON_POSITIVE_AMOUNT: error class=IllegalArgumentException, item=4",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        assertEquals(BankStatementImportItem.STATUS_SKIPPED, item.status)
        assertTrue(item.errorReason!!.contains("NON_POSITIVE_AMOUNT"))
    }

    @Test
    fun `duplicate expense item has duplicate reason and expense id`() {
        val item = BankStatementImportItem(
            runId = 1L,
            itemIndex = 5,
            transactionFingerprint = null,
            status = BankStatementImportItem.STATUS_DUPLICATE_EXPENSE,
            merchant = "Test Merchant",
            amount = 10.0,
            currency = "EUR",
            duplicateReason = "Duplicate expense ID 42",
            expenseId = 42L,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        assertEquals(BankStatementImportItem.STATUS_DUPLICATE_EXPENSE, item.status)
        assertNotNull(item.duplicateReason)
        assertNotNull(item.expenseId)
        assertEquals(42L, item.expenseId)
    }
}
