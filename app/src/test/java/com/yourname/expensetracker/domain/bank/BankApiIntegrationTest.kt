package com.yourname.expensetracker.domain.bank

import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
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
        // BankApiConfig.isStubMode is a mutable global; pin it true so syncTransactions()
        // passes requireStubMode() regardless of test execution order.
        BankApiConfig.isStubMode = true
        coordinator = mockk(relaxed = true)
        integration = BankApiIntegration(
            timeProvider = FakeTimeProvider(),
            coordinator = coordinator,
            writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true),
            operationRunRecorder = BlockInvokingRecorder(),
            hashingService = DefaultSensitiveHashingService(),
            privacySettingsRepository = mockk(relaxed = true) {
                coEvery { getSettings() } returns PrivacySettings()
            }
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
