package com.yourname.expensetracker.data.database.dao

import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.MatchStatus
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.database.entity.TransactionType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val FIXED_NOW = 1_710_000_000_000L
private const val CLAIM_NOW = FIXED_NOW + 5_000L
private const val CLAIM_CONFIDENCE = 0.95f

/**
 * S6 (P9-P1-07 / NEW-07): DB-backed coverage for the load-bearing conditional
 * UPDATE [ScannedReceiptDao.claimForAutoMatch]. The atomic compare-and-set is the
 * overlap-safety primitive that prevents two concurrent matching runs from both
 * auto-linking the same receipt; its correctness lives entirely in the SQL WHERE
 * clause (`matchStatus IN ('UNMATCHED', 'SUGGESTED')`), so it is exercised here
 * against a REAL in-memory Room database rather than a mock.
 *
 * Setup mirrors [com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptMatchLifecycleServiceTest]
 * (Robolectric + [AppDatabase.inMemoryBuilder] + real DAOs). A parent [Expense] is
 * inserted first because `scanned_receipts.expenseId` carries a foreign key to
 * `expenses(id)` that Room enforces.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ScannedReceiptClaimTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: ScannedReceiptDao
    private lateinit var expenseDao: ExpenseDao

    @Before
    fun setup() {
        database = AppDatabase.inMemoryBuilder(
            ApplicationProvider.getApplicationContext()
        ).build()
        dao = database.scannedReceiptDao()
        expenseDao = database.expenseDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    private suspend fun insertExpense(): Long {
        return expenseDao.insert(
            Expense(
                amount = 12.34,
                merchant = "Store",
                transactionType = TransactionType.PURCHASE,
                date = FIXED_NOW
            )
        )
    }

    private suspend fun insertReceipt(matchStatus: MatchStatus): Long {
        return dao.insert(
            ScannedReceipt(
                imagePath = null,
                rawOcrText = "sample",
                parsedTotal = 12.34,
                parsedMerchant = "Store",
                parsedDate = FIXED_NOW,
                parsedItems = null,
                parsedTaxAmount = null,
                currency = "EUR",
                confidence = 0.9f,
                matchStatus = matchStatus,
                createdAt = FIXED_NOW,
                updatedAt = FIXED_NOW
            )
        )
    }

    /**
     * Asserts the claim succeeds from a claimable start state: returns 1, flips the
     * row to AUTO_MATCHED, sets expenseId/matchConfidence, and advances updatedAt.
     */
    private suspend fun assertClaimSucceeds(startStatus: MatchStatus) {
        val expenseId = insertExpense()
        val receiptId = insertReceipt(startStatus)

        val claimed = dao.claimForAutoMatch(
            receiptId = receiptId,
            expenseId = expenseId,
            confidence = CLAIM_CONFIDENCE,
            now = CLAIM_NOW
        )

        assertEquals("claim must affect exactly 1 row from $startStatus", 1, claimed)
        val after = dao.getById(receiptId)
        assertNotNull(after)
        assertEquals(
            "matchStatus must flip to AUTO_MATCHED from $startStatus",
            MatchStatus.AUTO_MATCHED, after!!.matchStatus
        )
        assertEquals("expenseId must be set on claim from $startStatus", expenseId, after.expenseId)
        assertEquals(
            "matchConfidence must be persisted on claim from $startStatus",
            CLAIM_CONFIDENCE, after.matchConfidence!!, 0.0001f
        )
        assertEquals("updatedAt must advance on claim from $startStatus", CLAIM_NOW, after.updatedAt)
    }

    /**
     * Asserts the claim is rejected from an already-resolved start state: returns 0
     * and leaves the row entirely untouched (status, expenseId, confidence, updatedAt).
     */
    private suspend fun assertClaimRejected(startStatus: MatchStatus) {
        val expenseId = insertExpense()
        val receiptId = insertReceipt(startStatus)

        val claimed = dao.claimForAutoMatch(
            receiptId = receiptId,
            expenseId = expenseId,
            confidence = CLAIM_CONFIDENCE,
            now = CLAIM_NOW
        )

        assertEquals("claim must affect 0 rows from $startStatus", 0, claimed)
        val after = dao.getById(receiptId)
        assertNotNull(after)
        assertEquals("matchStatus must be unchanged for $startStatus", startStatus, after!!.matchStatus)
        assertNull("expenseId must remain unset for $startStatus", after.expenseId)
        assertNull("matchConfidence must remain unset for $startStatus", after.matchConfidence)
        assertEquals("updatedAt must be unchanged for $startStatus", FIXED_NOW, after.updatedAt)
    }

    @Test
    fun `claimForAutoMatch on UNMATCHED returns 1 and flips to AUTO_MATCHED`() = runTest {
        assertClaimSucceeds(MatchStatus.UNMATCHED)
    }

    @Test
    fun `claimForAutoMatch on SUGGESTED returns 1 and flips to AUTO_MATCHED`() = runTest {
        assertClaimSucceeds(MatchStatus.SUGGESTED)
    }

    @Test
    fun `claimForAutoMatch on AUTO_MATCHED returns 0 and leaves row unchanged`() = runTest {
        assertClaimRejected(MatchStatus.AUTO_MATCHED)
    }

    @Test
    fun `claimForAutoMatch on MANUALLY_MATCHED returns 0 and leaves row unchanged`() = runTest {
        assertClaimRejected(MatchStatus.MANUALLY_MATCHED)
    }

    @Test
    fun `claimForAutoMatch on REJECTED returns 0 and leaves row unchanged`() = runTest {
        assertClaimRejected(MatchStatus.REJECTED)
    }
}
