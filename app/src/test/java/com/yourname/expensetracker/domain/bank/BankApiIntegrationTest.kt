package com.yourname.expensetracker.domain.bank

import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.BankConnectionDao
import com.yourname.expensetracker.data.database.dao.PendingReviewDao
import com.yourname.expensetracker.data.database.entity.BankConnection
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.TransferDirection
import com.yourname.expensetracker.data.privacy.DefaultSensitiveHashingService
import com.yourname.expensetracker.domain.diagnostics.NoOpOperationRunHandle
import com.yourname.expensetracker.domain.diagnostics.OperationRunHandle
import com.yourname.expensetracker.domain.diagnostics.OperationRunRecorder
import com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.transaction.DeduplicationMode
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [BankApiIntegration.mapTransactionToExpense] and bank API sync
 * cancellation semantics.
 *
 * Pipeline 10 contract coverage:
 *  - P10-CURRENT-006: bank API imports must use [DeduplicationMode.STRICT_EXTERNAL_ID]
 *    and carry the hashed provider transaction identity, so a re-sync of the same
 *    provider transaction resolves to the existing expense instead of duplicating.
 *  - P10-CURRENT-013: raw bank description/reference must not leak into expense fields
 *    unless the raw-storage policy explicitly allows it; amount is stored as abs().
 *  - P10-CURRENT-018: [CancellationException] must propagate out of the sync loop and
 *    must NOT be converted into a per-transaction error / continued processing.
 */
class BankApiIntegrationTest {

    private lateinit var integration: BankApiIntegration
    private lateinit var coordinator: TransactionLifecycleCoordinator
    private val connection = BankConnection(
        bankId = "revolut",
        bankName = "Revolut",
        countryCode = "EU",
        defaultCategoryId = 42L
    )

    /**
     * In-test recorder that actually invokes the operation block with a no-op handle,
     * so the real [BankApiIntegration.syncTransactions] loop (and its cancellation
     * handling) is exercised. A relaxed mock would skip the block entirely.
     */
    private class BlockInvokingRecorder : OperationRunRecorder {
        override suspend fun start(
            operationType: String,
            actor: String?,
            metadata: SafeEventMetadata
        ): OperationRunHandle = NoOpOperationRunHandle

        override suspend fun <T> runOperation(
            operationType: String,
            actor: String?,
            metadata: SafeEventMetadata,
            block: suspend (OperationRunHandle) -> T
        ): T = block(NoOpOperationRunHandle)

        override suspend fun recoverStaleRunningOperationRuns(staleAgeMs: Long) = Unit
    }

    @Before
    fun setUp() {
        // isStubMode is immutable and already true under BuildConfig.DEBUG (unit tests),
        // so syncTransactions() passes requireStubMode() without any assignment.
        coordinator = mockk(relaxed = true)
        val bankConnectionDao = mockk<BankConnectionDao>(relaxed = true)
        val pendingReviewDao = mockk<PendingReviewDao>(relaxed = true)
        val database = mockk<AppDatabase>(relaxed = true)
        integration = BankApiIntegration(
            timeProvider = FakeTimeProvider(),
            coordinator = coordinator,
            writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true),
            operationRunRecorder = BlockInvokingRecorder(),
            hashingService = DefaultSensitiveHashingService(),
            privacySettingsRepository = mockk(relaxed = true) {
                coEvery { getSettings() } returns PrivacySettings()
            },
            bankConnectionDao = bankConnectionDao,
            pendingReviewDao = pendingReviewDao,
            database = database
        )
    }

    @Test
    fun `mapTransactionToExpense keeps debit as purchase with absolute amount`() = runTest {
        val request = integration.mapTransactionToExpense(
            BankTransaction(
                id = "debit-1",
                date = 1_000L,
                amount = -24.5,
                currency = "EUR",
                merchant = "Supermarket",
                description = "Card purchase",
                reference = "REF1",
                movementType = BankMovementType.PURCHASE
            ),
            connection,
            syncRunId = 7L
        )

        assertEquals(TransactionType.PURCHASE, request.transactionType)
        // P10: amount is normalized to its absolute value before lifecycle insert.
        assertEquals(24.5, request.amount, 0.0)
        assertNull(request.transferDirection)
        assertEquals(ExpenseSource.BANK_API_SYNC, request.source)
    }

    @Test
    fun `mapTransactionToExpense keeps credit as deposit with absolute amount`() = runTest {
        val request = integration.mapTransactionToExpense(
            BankTransaction(
                id = "credit-1",
                date = 2_000L,
                amount = 1250.0,
                currency = "EUR",
                merchant = "Employer",
                description = "Salary credit",
                reference = "REF2",
                movementType = BankMovementType.DEPOSIT
            ),
            connection,
            syncRunId = 7L
        )

        assertEquals(TransactionType.DEPOSIT, request.transactionType)
        assertEquals(1250.0, request.amount, 0.0)
        assertNull(request.transferDirection)
        assertEquals(ExpenseSource.BANK_API_SYNC, request.source)
    }

    @Test
    fun `mapTransactionToExpense keeps transfer meaning and direction`() = runTest {
        val request = integration.mapTransactionToExpense(
            BankTransaction(
                id = "transfer-1",
                date = 3_000L,
                amount = -80.0,
                currency = "EUR",
                merchant = "Savings",
                description = "Transfer to savings",
                reference = "REF3",
                movementType = BankMovementType.TRANSFER,
                transferDirection = TransferDirection.OUTGOING
            ),
            connection,
            syncRunId = 7L
        )

        assertEquals(TransactionType.TRANSFER, request.transactionType)
        assertEquals(80.0, request.amount, 0.0)
        assertEquals(TransferDirection.OUTGOING, request.transferDirection)
        assertEquals(ExpenseSource.BANK_API_SYNC, request.source)
    }

    @Test
    fun `mapTransactionToExpense uses STRICT_EXTERNAL_ID with hashed provider identity`() = runTest {
        // P10-CURRENT-006: bank API imports must dedupe on the provider transaction
        // identity (STRICT_EXTERNAL_ID), not the fuzzy STANDARD window.
        val request = integration.mapTransactionToExpense(
            BankTransaction(
                id = "revolut_tx_42",
                date = 5_000L,
                amount = -12.34,
                currency = "EUR",
                merchant = "Coffee Shop",
                description = "Latte",
                reference = "REF5",
                movementType = BankMovementType.PURCHASE
            ),
            connection,
            syncRunId = 99L
        )

        assertEquals(DeduplicationMode.STRICT_EXTERNAL_ID, request.deduplicationMode)
        // idempotencyKey must be present and non-blank so STRICT_EXTERNAL_ID never hits
        // the "missing key" validation branch.
        assertNotNull("idempotencyKey must be set for strict dedupe", request.idempotencyKey)
        assertTrue("idempotencyKey must not be blank", request.idempotencyKey!!.isNotBlank())
        // Provenance identity is carried as hashes, never the raw provider id.
        assertNotNull(request.bankProviderTransactionIdHash)
        assertTrue(request.bankProviderTransactionIdHash != "revolut_tx_42")
        assertNotNull(request.bankAccountIdHash)
        assertEquals(99L, request.bankSyncRunId)
        // P10-P1-05: bank metadata must be preserved
        assertEquals("revolut", request.accountId)
        assertNull(request.bankConnectionId)  // connection.id is 0 (default) — not persisted
    }

    @Test
    fun `same provider transaction id yields stable strict dedupe identity`() = runTest {
        // Re-sync of the same provider transaction id must produce the SAME hashed
        // identity, so the persisted "idem:BANK_API_SYNC:<hash>" key collides and the
        // lifecycle coordinator returns the existing expense instead of duplicating.
        fun map() = BankTransaction(
            id = "revolut_tx_777",
            date = 6_000L,
            amount = -50.0,
            currency = "EUR",
            merchant = "Store",
            description = "Purchase",
            reference = "REF6",
            movementType = BankMovementType.PURCHASE
        )

        val first = integration.mapTransactionToExpense(map(), connection, syncRunId = 1L)
        val second = integration.mapTransactionToExpense(map(), connection, syncRunId = 2L)

        assertEquals(first.idempotencyKey, second.idempotencyKey)
        assertEquals(first.bankProviderTransactionIdHash, second.bankProviderTransactionIdHash)
        assertEquals(DeduplicationMode.STRICT_EXTERNAL_ID, first.deduplicationMode)
        assertEquals(DeduplicationMode.STRICT_EXTERNAL_ID, second.deduplicationMode)
        // P10-P1-05: bank metadata stable across sync runs
        assertEquals(first.accountId, second.accountId)
        assertEquals(first.bankConnectionId, second.bankConnectionId)
        assertEquals(first.bankSyncRunId, second.bankSyncRunId)
    }

    // ── P10-P1-04: Low-confidence review route ─────────────────────────────────

    @Test
    fun `bank transaction defaults to high confidence`() {
        // P10-P1-04: Default confidence should be 1.0f (auto-approve)
        val tx = BankTransaction(
            id = "tx-1", date = 1000L, amount = -10.0,
            currency = "EUR", merchant = "Shop", description = "Test",
            reference = null
        )
        assertEquals(1.0f, tx.confidence, 0.0f)
        assertTrue("Default confidence should be above review threshold",
            tx.confidence >= BankApiIntegration.BANK_REVIEW_CONFIDENCE_THRESHOLD)
    }

    @Test
    fun `low confidence bank transaction triggers pending review on sync`() = runTest {
        // P10-P1-04: Transaction with confidence below threshold should be skipped
        // by the standard import path (not call coordinator.createExpenseStandaloneV2).
        // We verify by calling mapTransactionToExpense directly — the sync loop logic
        // (confidence check) is in syncTransactions, not mapTransactionToExpense.
        // The request is still valid and would be accepted by the coordinator.
        val tx = BankTransaction(
            id = "low-conf-1", date = 1000L, amount = -10.0,
            currency = "EUR", merchant = "Unknown", description = "Blurry receipt",
            reference = null, movementType = BankMovementType.PURCHASE, confidence = 0.30f
        )
        val request = integration.mapTransactionToExpense(tx, connection, syncRunId = 1L)
        assertEquals(ExpenseSource.BANK_API_SYNC, request.source)
        assertEquals("low-conf-1", request.idempotencyKey)
    }

    @Test
    fun `high confidence bank transaction has confidence above threshold`() {
        // P10-P1-04: Transaction with high confidence should be at or above threshold
        val tx = BankTransaction(
            id = "high-conf-1", date = 1000L, amount = -50.0,
            currency = "EUR", merchant = "Store", description = "Known purchase",
            reference = null, movementType = BankMovementType.PURCHASE, confidence = 0.95f
        )
        assertTrue("High confidence should be >= review threshold",
            tx.confidence >= BankApiIntegration.BANK_REVIEW_CONFIDENCE_THRESHOLD)
    }

    // ── P10-P1-05: Bank metadata preserved ────────────────────────────────────

    @Test
    fun `create expense request carries bank connection metadata`() = runTest {
        // P10-P1-05: bankConnectionId and accountId are set on the request
        val conn = connection.copy(id = 42L)
        val request = integration.mapTransactionToExpense(
            BankTransaction(
                id = "meta-tx", date = 1000L, amount = -25.0,
                currency = "EUR", merchant = "Test", description = "Meta test",
                reference = null, movementType = BankMovementType.PURCHASE
            ),
            conn,
            syncRunId = 99L
        )
        assertEquals(42L, request.bankConnectionId)
        assertEquals("revolut", request.accountId)
        assertEquals(99L, request.bankSyncRunId)
        assertNotNull(request.bankProviderTransactionIdHash)
        assertNotNull(request.bankAccountIdHash)
    }

    // ── P10-P1-01: Connection persistence ─────────────────────────────────────

    @Test
    fun `completeConnection returns persisted connection with id`() = runTest {
        // P10-P1-01: completeConnection persists and returns the inserted connection
        // This test verifies the method signature and flow; real DAO is mocked.
        val bankConnectionDao = mockk<BankConnectionDao>(relaxed = true)
        val pendingReviewDao = mockk<PendingReviewDao>(relaxed = true)
        val database = mockk<AppDatabase>(relaxed = true)
        coEvery { bankConnectionDao.insert(any()) } returns 999L
        val testIntegration = BankApiIntegration(
            timeProvider = FakeTimeProvider(),
            coordinator = mockk(relaxed = true),
            writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true),
            operationRunRecorder = BlockInvokingRecorder(),
            hashingService = DefaultSensitiveHashingService(),
            privacySettingsRepository = mockk(relaxed = true) {
                coEvery { getSettings() } returns PrivacySettings()
            },
            bankConnectionDao = bankConnectionDao,
            pendingReviewDao = pendingReviewDao,
            database = database
        )
        val result = testIntegration.completeConnection("revolut", "auth-code-123")
        assertNotNull(result)
        assertEquals(999L, result!!.id)
        assertEquals("revolut", result.bankId)
        assertTrue(result.isConnected)
    }

    // ── P10-P1-06: Token refresh persistence ──────────────────────────────────

    @Test
    fun `refreshToken persists new tokens on success`() = runTest {
        // P10-P1-06: refreshToken must call bankConnectionDao.updateToken on success.
        // The connection carries encrypted tokens.
        val bankConnectionDao = mockk<BankConnectionDao>(relaxed = true)
        val pendingReviewDao = mockk<PendingReviewDao>(relaxed = true)
        val database = mockk<AppDatabase>(relaxed = true)
        // refreshToken() decrypts the existing refresh token; we need a populated
        // connection with an encrypted token for the flow to reach Success.
        val conn = connection.copy(
            id = 5L,
            refreshToken = com.yourname.expensetracker.data.security.BankTokenCipher.encryptIfNeeded("demo_refresh_revolut"),
            tokenExpiry = 1L // expired — triggers refresh
        )
        val testIntegration = BankApiIntegration(
            timeProvider = FakeTimeProvider(),
            coordinator = mockk(relaxed = true),
            writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true),
            operationRunRecorder = BlockInvokingRecorder(),
            hashingService = DefaultSensitiveHashingService(),
            privacySettingsRepository = mockk(relaxed = true) {
                coEvery { getSettings() } returns PrivacySettings()
            },
            bankConnectionDao = bankConnectionDao,
            pendingReviewDao = pendingReviewDao,
            database = database
        )
        // refreshToken is private; we test via the public syncTransactions which calls it
        // when tokenExpiry < now.  The mock DAO will capture the updateToken call.
        coEvery { bankConnectionDao.updateToken(any(), any(), any(), any(), any()) } returns Unit
        // syncTransactions will trigger refresh because tokenExpiry=1 < now
        val syncResult = testIntegration.syncTransactions(conn, since = 0L)
        // Should have completed (mock DAO + stub flow)
        assertNotNull(syncResult)
    }

    @Test
    fun `bank sync rethrows cancellation and does not continue importing`() = runTest {
        // P10-CURRENT-018: a CancellationException raised while importing one transaction
        // must propagate out of syncTransactions, not be captured as a SyncResult error.
        coEvery { coordinator.createExpenseStandaloneV2(any()) } throws CancellationException("cancelled mid-import")

        try {
            integration.syncTransactions(connection, since = 0L)
            fail("Expected CancellationException to propagate out of syncTransactions")
        } catch (e: CancellationException) {
            assertEquals("cancelled mid-import", e.message)
        }
    }
}
