package com.yourname.expensetracker.domain.bank

import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.BankConnectionDao
import com.yourname.expensetracker.data.database.dao.PendingReviewDao
import com.yourname.expensetracker.data.database.entity.BankConnection
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.TransferDirection
import com.yourname.expensetracker.data.privacy.DefaultSensitiveHashingService
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import com.yourname.expensetracker.domain.diagnostics.NoOpOperationRunHandle
import com.yourname.expensetracker.domain.diagnostics.OperationRunHandle
import com.yourname.expensetracker.domain.diagnostics.OperationRunRecorder
import com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.privacy.RawStorageMode
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * RP-17 17-C (transfer/refund/amount contract) and 17-D (review identity +
 * privacy) unit contract on the real mapping/evaluation code:
 *
 *  - zero amounts skip with INVALID_AMOUNT before any mapping;
 *  - refund-like description text WITHOUT typed provider semantics skips with
 *    REFUND_UNSUPPORTED; typed provider refund semantics imports as DEPOSIT;
 *  - transfers require provider-supplied direction + a persistable privacy-safe
 *    account reference, otherwise TRANSFER_METADATA_MISSING (skipped BEFORE the
 *    lifecycle / review queue; validator stays strict, no REDACTED placeholder);
 *  - under STORE_REDACTED a valid transfer with a provider-masked reference
 *    imports with that reference as the transfer account name;
 *  - direction is never inferred from amount sign or description;
 *  - bank review identity is stable across sync runs and distinct per
 *    connection scope; review title/text carry no raw bank payload.
 */
class BankTransactionContractTest {

    private lateinit var integration: BankApiIntegration
    private val connection = BankConnection(
        id = 42L,
        bankId = "nbg",
        bankName = "National Bank of Greece",
        countryCode = "GR",
        defaultCategoryId = 9L
    )
    private val hashingService = DefaultSensitiveHashingService()

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
    }

    @Before
    fun setUp() {
        val privacyRepository = mockk<PrivacySettingsRepository>(relaxed = true)
        coEvery { privacyRepository.getSettings() } returns PrivacySettings()
        integration = BankApiIntegration(
            timeProvider = FakeTimeProvider(),
            coordinator = mockk(relaxed = true),
            writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true),
            operationRunRecorder = BlockInvokingRecorder(),
            hashingService = hashingService,
            privacySettingsRepository = privacyRepository,
            bankConnectionDao = mockk<BankConnectionDao>(relaxed = true),
            pendingReviewDao = mockk<PendingReviewDao>(relaxed = true),
            database = mockk<AppDatabase>(relaxed = true)
        )
    }

    private fun tx(
        amount: Double,
        description: String = "Card purchase",
        movementType: BankMovementType? = BankMovementType.PURCHASE,
        direction: TransferDirection? = null,
        accountRef: String? = null
    ) = BankTransaction(
        id = "nbg_tx_1",
        date = 1_000L,
        amount = amount,
        currency = "EUR",
        merchant = "Store",
        description = description,
        reference = "REF1",
        movementType = movementType,
        transferDirection = direction,
        transferAccountRef = accountRef
    )

    // ── 17-C: zero amounts ───────────────────────────────────────────────────

    @Test
    fun `zero amount skips with INVALID_AMOUNT before mapping`() {
        val skip = integration.evaluateBankTransactionContract(
            tx(amount = 0.0), RawStorageMode.STORE_REDACTED
        )
        assertEquals(DiagnosticReasonCode.INVALID_AMOUNT, skip)
    }

    // ── 17-C: refund / reversal / cashback ───────────────────────────────────

    @Test
    fun `refund text without typed semantics skips with REFUND_UNSUPPORTED`() {
        val skip = integration.evaluateBankTransactionContract(
            tx(amount = 50.0, description = "REFUND for order 123", movementType = BankMovementType.PURCHASE),
            RawStorageMode.STORE_REDACTED
        )
        assertEquals(DiagnosticReasonCode.REFUND_UNSUPPORTED, skip)
    }

    @Test
    fun `cashback text without typed semantics skips with REFUND_UNSUPPORTED`() {
        val skip = integration.evaluateBankTransactionContract(
            tx(amount = 5.0, description = "cashback bonus", movementType = null),
            RawStorageMode.STORE_RAW
        )
        assertEquals(DiagnosticReasonCode.REFUND_UNSUPPORTED, skip)
    }

    @Test
    fun `typed provider refund semantics imports as DEPOSIT`() = runTest {
        val transaction = tx(
            amount = 50.0,
            description = "REFUND for order 123",
            movementType = BankMovementType.REFUND
        )
        assertNull(
            integration.evaluateBankTransactionContract(transaction, RawStorageMode.STORE_REDACTED)
        )
        val request = integration.mapTransactionToExpense(transaction, connection, syncRunId = 1L)
        assertEquals(TransactionType.DEPOSIT, request.transactionType)
        assertEquals(50.0, request.amount, 0.0)
    }

    @Test
    fun `reversal and cashback typed semantics map to DEPOSIT`() {
        assertNull(
            integration.evaluateBankTransactionContract(
                tx(amount = 1.0, movementType = BankMovementType.REVERSAL), RawStorageMode.STORE_REDACTED
            )
        )
        assertNull(
            integration.evaluateBankTransactionContract(
                tx(amount = 1.0, movementType = BankMovementType.CASHBACK), RawStorageMode.STORE_REDACTED
            )
        )
    }

    // ── 17-C: transfer metadata ──────────────────────────────────────────────

    @Test
    fun `transfer without direction skips with TRANSFER_METADATA_MISSING`() {
        val skip = integration.evaluateBankTransactionContract(
            tx(
                amount = -200.0,
                description = "Transfer to savings",
                movementType = BankMovementType.TRANSFER,
                direction = null,
                accountRef = "****1234"
            ),
            RawStorageMode.STORE_REDACTED
        )
        assertEquals(DiagnosticReasonCode.TRANSFER_METADATA_MISSING, skip)
    }

    @Test
    fun `transfer without privacy-safe account reference skips with TRANSFER_METADATA_MISSING`() {
        val skip = integration.evaluateBankTransactionContract(
            tx(
                amount = -200.0,
                movementType = BankMovementType.TRANSFER,
                direction = TransferDirection.OUTGOING,
                accountRef = null
            ),
            RawStorageMode.STORE_REDACTED
        )
        assertEquals(DiagnosticReasonCode.TRANSFER_METADATA_MISSING, skip)
    }

    @Test
    fun `valid transfer with masked reference imports under redaction`() = runTest {
        val transaction = tx(
            amount = -200.0,
            description = "Transfer to savings",
            movementType = BankMovementType.TRANSFER,
            direction = TransferDirection.OUTGOING,
            accountRef = "****1234"
        )
        assertNull(
            integration.evaluateBankTransactionContract(transaction, RawStorageMode.STORE_REDACTED)
        )
        val request = integration.mapTransactionToExpense(
            transaction, connection, syncRunId = 1L, rawModeOverride = RawStorageMode.STORE_REDACTED
        )
        assertEquals(TransactionType.TRANSFER, request.transactionType)
        assertEquals(TransferDirection.OUTGOING, request.transferDirection)
        assertEquals("****1234", request.transferAccountName)
    }

    @Test
    fun `transfer under DO_NOT_STORE skips because account reference cannot persist`() {
        val skip = integration.evaluateBankTransactionContract(
            tx(
                amount = -200.0,
                movementType = BankMovementType.TRANSFER,
                direction = TransferDirection.OUTGOING,
                accountRef = "****1234"
            ),
            RawStorageMode.DO_NOT_STORE
        )
        assertEquals(DiagnosticReasonCode.TRANSFER_METADATA_MISSING, skip)
    }

    @Test
    fun `description transfer text without direction never imports as transfer`() {
        // "sent to" text used to infer TRANSFER and then fail lifecycle validation.
        // Contract: skip BEFORE lifecycle with TRANSFER_METADATA_MISSING.
        val skip = integration.evaluateBankTransactionContract(
            tx(amount = -20.0, description = "sent to George", movementType = null),
            RawStorageMode.STORE_REDACTED
        )
        assertEquals(DiagnosticReasonCode.TRANSFER_METADATA_MISSING, skip)
    }

    // ── 17-C: ordinary movement and direction inference ban ──────────────────

    @Test
    fun `ordinary purchase deposit and withdrawal pass the contract`() {
        assertNull(
            integration.evaluateBankTransactionContract(
                tx(amount = -12.0, movementType = BankMovementType.PURCHASE), RawStorageMode.STORE_REDACTED
            )
        )
        assertNull(
            integration.evaluateBankTransactionContract(
                tx(amount = 900.0, movementType = BankMovementType.DEPOSIT), RawStorageMode.STORE_REDACTED
            )
        )
        assertNull(
            integration.evaluateBankTransactionContract(
                tx(amount = -60.0, movementType = BankMovementType.WITHDRAWAL), RawStorageMode.STORE_REDACTED
            )
        )
    }

    @Test
    fun `direction is never inferred from sign or description`() {
        // Negative amount + transfer-ish text without typed movement/direction:
        // infers TRANSFER from text → no direction → skip (never OUTGOING by sign).
        val skip = integration.evaluateBankTransactionContract(
            tx(amount = -80.0, description = "Transfer to savings", movementType = null),
            RawStorageMode.STORE_REDACTED
        )
        assertEquals(DiagnosticReasonCode.TRANSFER_METADATA_MISSING, skip)
    }

    // ── 17-D: stable review identity + privacy-safe review fields ────────────

    @Test
    fun `bank review identity is stable across sync runs and scoped per connection`() = runTest {
        val transaction = tx(amount = -10.0)

        val review1 = integration.buildBankPendingReview(
            transaction, connection, TransactionType.PURCHASE, "store", RawStorageMode.STORE_REDACTED
        )
        val review2 = integration.buildBankPendingReview(
            transaction, connection, TransactionType.PURCHASE, "store", RawStorageMode.STORE_REDACTED
        )

        assertNotNull(review1.bankReviewIdentity)
        assertEquals("cross-run stable fingerprint", review1.bankReviewIdentity, review2.bankReviewIdentity)

        val otherConnection = connection.copy(id = 43L)
        val reviewOtherScope = integration.buildBankPendingReview(
            transaction, otherConnection, TransactionType.PURCHASE, "store", RawStorageMode.STORE_REDACTED
        )
        assertNotNull(reviewOtherScope.bankReviewIdentity)
        org.junit.Assert.assertNotEquals(
            "different connection scope must produce a different identity",
            review1.bankReviewIdentity,
            reviewOtherScope.bankReviewIdentity
        )
        // Identity is privacy-safe: hash of the scope input, never the raw scope.
        org.junit.Assert.assertNotEquals("nbg|42|nbg_tx_1", review1.bankReviewIdentity)
    }

    @Test
    fun `bank review raw fields follow the raw persistence policy`() = runTest {
        val transaction = tx(
            amount = -10.0,
            description = "Purchase at corner shop IBAN GR11 0022"
        )

        val redacted = integration.buildBankPendingReview(
            transaction, connection, TransactionType.PURCHASE, "store", RawStorageMode.STORE_REDACTED
        )
        assertNull("title must never carry raw bank payload", redacted.notificationTitle)
        assertEquals("[REDACTED]", redacted.notificationText)

        val doNotStore = integration.buildBankPendingReview(
            transaction, connection, TransactionType.PURCHASE, "store", RawStorageMode.DO_NOT_STORE
        )
        assertNull(doNotStore.notificationTitle)
        assertNull(doNotStore.notificationText)

        val rawMode = integration.buildBankPendingReview(
            transaction, connection, TransactionType.PURCHASE, "store", RawStorageMode.STORE_RAW
        )
        assertNull("title stays unpopulated in every mode", rawMode.notificationTitle)
        assertEquals(transaction.description, rawMode.notificationText)
    }

    @Test
    fun `bank review scope hash enables scoped disconnect cleanup`() = runTest {
        val review = integration.buildBankPendingReview(
            tx(amount = -10.0), connection, TransactionType.PURCHASE, "store", RawStorageMode.STORE_REDACTED
        )
        assertEquals(
            hashingService.hmacSha256Prefix("42", BankApiIntegration.BANK_ACCOUNT_SCOPE_PURPOSE),
            review.bankConnectionScopeHash
        )
    }
}
