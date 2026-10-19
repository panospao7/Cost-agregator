package com.yourname.expensetracker.domain.bank

import com.yourname.expensetracker.BuildConfig
import androidx.room.withTransaction
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.BankConnectionDao
import com.yourname.expensetracker.data.database.dao.PendingReviewDao
import com.yourname.expensetracker.data.database.entity.BankConnection
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.TransferDirection
import com.yourname.expensetracker.data.privacy.DefaultSensitiveHashingService
import com.yourname.expensetracker.data.security.BankTokenCipher
import com.yourname.expensetracker.domain.diagnostics.NoOpOperationRunHandle
import com.yourname.expensetracker.domain.diagnostics.OperationRunHandle
import com.yourname.expensetracker.domain.diagnostics.OperationRunRecorder
import com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.transaction.CreateExpenseResult
import com.yourname.expensetracker.domain.transaction.DeduplicationMode
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
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
    }

    /**
     * W2 privacy recorder: captures every serialized field the sync loop hands to the
     * operation-run handle (events plus terminal summaries) so a test can scan them for
     * sentinels. Cancellation is never swallowed here — the handle only records.
     */
    private class RecordingOperationRunHandle : OperationRunHandle {
        data class Recorded(
            val stage: String,
            val outcome: com.yourname.expensetracker.domain.diagnostics.EventOutcome,
            val reasonCode: com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode?,
            val severity: com.yourname.expensetracker.domain.diagnostics.EventSeverity,
            val metadata: SafeEventMetadata,
            val entityType: String?,
            val entityId: Long?,
            val exception: Throwable?
        )

        override val runId: Long = 7L
        override val correlationId: String = "corr-fixed"
        val events = mutableListOf<Recorded>()
        val terminalTexts = mutableListOf<String?>()

        override suspend fun event(
            stage: String,
            outcome: com.yourname.expensetracker.domain.diagnostics.EventOutcome,
            reasonCode: com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode?,
            severity: com.yourname.expensetracker.domain.diagnostics.EventSeverity,
            metadata: SafeEventMetadata,
            entityType: String?,
            entityId: Long?,
            exception: Throwable?,
            isTerminal: Boolean
        ) {
            events.add(Recorded(stage, outcome, reasonCode, severity, metadata, entityType, entityId, exception))
        }

        override suspend fun increment(processed: Int, succeeded: Int, failed: Int, skipped: Int, warnings: Int, errors: Int) = Unit
        override suspend fun success() { terminalTexts.add(null) }
        override suspend fun partialSuccess(summary: String?) { terminalTexts.add(summary) }
        override suspend fun failedFinal(reason: String, error: Throwable?) { terminalTexts.add(reason) }
        override suspend fun failedRetryable(reason: String, error: Throwable?) { terminalTexts.add(reason) }
        override suspend fun cancelled(reason: String?) { terminalTexts.add(reason) }
    }

    private class RecordingRecorder(private val handle: OperationRunHandle) : OperationRunRecorder {
        override suspend fun start(
            operationType: String,
            actor: String?,
            metadata: SafeEventMetadata
        ): OperationRunHandle = handle

        override suspend fun <T> runOperation(
            operationType: String,
            actor: String?,
            metadata: SafeEventMetadata,
            block: suspend (OperationRunHandle) -> T
        ): T = block(handle)
    }

    @Before
    fun setUp() {
        // isStubMode is immutable and already true under BuildConfig.DEBUG (unit tests),
        // so syncTransactions() passes requireStubMode() without any assignment.
        coordinator = mockk(relaxed = true)
        val bankConnectionDao = mockk<BankConnectionDao>(relaxed = true)
        val pendingReviewDao = mockk<PendingReviewDao>(relaxed = true)
        val database = mockk<AppDatabase>(relaxed = true)
        // The low-confidence review path runs inside database.withTransaction —
        // execute the block directly against the relaxed mock (repo-standard pattern).
        io.mockk.mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { database.withTransaction(any<suspend () -> Any>()) } coAnswers {
            secondArg<suspend () -> Any>().invoke()
        }
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
        stubBankTokenCipherForJvm()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    /**
     * [BankTokenCipher] is Android Keystore-backed; any real call on the plain
     * JVM fails with `java.security.KeyStoreException: AndroidKeyStore not found`
     * (it has no constructor seam — it is a Kotlin object used statically by
     * [BankApiIntegration]). Stub the object (repo-standard mockkObject seam,
     * cf. WorkerExecutionGuardTest) with a value-preserving round-trip —
     * "encrypt" returns the plaintext, "decrypt" reports Success with it — so
     * the production connection/refresh/persist flows run end-to-end without a
     * keystore. Assertions on outcomes and DAO writes are unchanged.
     */
    private fun stubBankTokenCipherForJvm() {
        mockkObject(BankTokenCipher)
        every { BankTokenCipher.encryptIfNeeded(any()) } answers { firstArg<String>() }
        every { BankTokenCipher.decryptWithResult(any()) } answers {
            BankTokenCipher.DecryptResult.Success(firstArg())
        }
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
        // P10-P1-05: identity-bearing bank metadata is stable across sync runs;
        // bankSyncRunId is per-run provenance BY DESIGN — each request carries its
        // own run id (the STRICT_EXTERNAL_ID test above pins the passthrough).
        assertEquals(first.accountId, second.accountId)
        assertEquals(first.bankConnectionId, second.bankConnectionId)
        assertEquals(1L, first.bankSyncRunId)
        assertEquals(2L, second.bankSyncRunId)
    }

    // ── P10-P1-04: Low-confidence review route ─────────────────────────────────

    @Test
    fun `low confidence bank transaction triggers pending review on sync`() = runTest {
        // P10-P1-04: drives the REAL syncTransactions loop (generateMockTransactions is
        // the only stubbed seam, so the transactions and their confidences are exact).
        // Below-threshold transactions must be inserted as PendingReview rows and must
        // NEVER reach coordinator.createExpenseStandaloneV2; transactions AT or above
        // the threshold take the standard coordinator path and create no review.
        val bankConnectionDao = mockk<BankConnectionDao>(relaxed = true)
        val pendingReviewDao = mockk<PendingReviewDao>(relaxed = true)
        val database = mockk<AppDatabase>(relaxed = true)
        val writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true)
        val reviewCoordinator = mockk<TransactionLifecycleCoordinator>(relaxed = true)
        io.mockk.mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { database.withTransaction(any<suspend () -> Any>()) } coAnswers {
            secondArg<suspend () -> Any>().invoke()
        }
        // No pre-existing pending duplicate; the atomic insert reports a created row.
        coEvery {
            pendingReviewDao.getPendingDuplicateCandidateInRangeTypeAware(
                any(), any(), any(), any(), any(), any(), any(), any()
            )
        } returns null
        val insertedReviews = mutableListOf<PendingReview>()
        coEvery { pendingReviewDao.insert(capture(insertedReviews)) } returns 1L
        val coordinatorRequests = mutableListOf<CreateExpenseRequest>()
        coEvery { reviewCoordinator.createExpenseStandaloneV2(capture(coordinatorRequests)) } returns
            CreateExpenseResult.Created(expenseId = 1L)

        val threshold = BankApiIntegration.BANK_REVIEW_CONFIDENCE_THRESHOLD
        val justBelowThreshold = Math.nextDown(threshold)
        fun tx(id: String, amount: Double, merchant: String, confidence: Float) = BankTransaction(
            id = id, date = 1000L, amount = amount,
            currency = "EUR", merchant = merchant, description = "Synthetic purchase",
            reference = null, movementType = BankMovementType.PURCHASE, confidence = confidence
        )
        val transactions = listOf(
            tx("low-conf-1", -10.0, "Lowmart", 0.30f),
            tx("below-threshold-1", -11.0, "Belowmart", justBelowThreshold),
            tx("at-threshold-1", -20.0, "Thresholdmart", threshold),
            tx("high-conf-1", -30.0, "Highmart", 0.95f)
        )
        val realIntegration = BankApiIntegration(
            timeProvider = FakeTimeProvider(),
            coordinator = reviewCoordinator,
            writeBarrier = writeBarrier,
            operationRunRecorder = BlockInvokingRecorder(),
            hashingService = DefaultSensitiveHashingService(),
            privacySettingsRepository = mockk(relaxed = true) {
                coEvery { getSettings() } returns PrivacySettings()
            },
            bankConnectionDao = bankConnectionDao,
            pendingReviewDao = pendingReviewDao,
            database = database
        )
        val testIntegration = spyk(realIntegration)
        every { testIntegration.generateMockTransactions(any(), any()) } returns transactions

        if (!BuildConfig.DEBUG) {
            assertDemoApiDenied { testIntegration.syncTransactions(connection, since = 0L) }
            coVerify(exactly = 0) { pendingReviewDao.insert(any()) }
            // assertDemoApiDenied checks the class-level coordinator; this test wires its own.
            coVerify(exactly = 0) { reviewCoordinator.createExpenseStandaloneV2(any()) }
            return@runTest
        }
        val outcome = testIntegration.syncTransactions(connection, since = 0L)

        assertEquals(
            "2 reviews + 2 coordinator imports, no skips and no errors",
            BankSyncOutcome.Success(importedCount = 4, skippedCount = 0),
            outcome
        )
        // Review path: exactly the two below-threshold transactions, with exact fields.
        coVerify(exactly = 2) { pendingReviewDao.insert(any()) }
        assertEquals(2, insertedReviews.size)
        assertEquals(listOf(0.30f, justBelowThreshold), insertedReviews.map { it.confidence })
        assertEquals(listOf(10.0, 11.0), insertedReviews.map { it.suggestedAmount })
        assertEquals(listOf("Lowmart", "Belowmart"), insertedReviews.map { it.suggestedMerchant })
        insertedReviews.forEach { review ->
            assertEquals("EUR", review.suggestedCurrency)
            assertEquals(TransactionType.PURCHASE.name, review.suggestedType)
            assertEquals(42L, review.suggestedCategoryId)
            assertEquals(1000L, review.suggestedDate)
            assertEquals("bank.sync.revolut", review.packageName)
            assertEquals("Low-confidence bank transaction from revolut", review.explanation)
            assertNull("no raw bank payload may be stored in the title", review.notificationTitle)
            assertNull(review.rawNotificationId)
            assertNull(review.scannedReceiptId)
            assertNotNull("bank review identity must be set", review.bankReviewIdentity)
            // Default rawBankStatementStorageMode is STORE_REDACTED: the provider description
            // ("Synthetic purchase") must never be stored raw on the review.
            assertEquals("[REDACTED]", review.notificationText)
        }
        // Exact identity, computed through the real hashing service from the documented
        // "<bankId>|<connectionId>|<providerTransactionId>" input and purpose.
        val identityHasher = DefaultSensitiveHashingService()
        assertEquals(
            listOf("low-conf-1", "below-threshold-1").map { txId ->
                identityHasher.hmacSha256Prefix("revolut|${connection.id}|$txId", "bankReviewIdentity")
            },
            insertedReviews.map { it.bankReviewIdentity }
        )
        assertTrue(
            "review identities must be hashed and distinct per provider transaction",
            insertedReviews.map { it.bankReviewIdentity }.toSet().size == 2 &&
                insertedReviews.none { it.bankReviewIdentity in setOf("low-conf-1", "below-threshold-1") }
        )
        coVerify(exactly = 2) {
            writeBarrier.checkWritesAllowed("BankApiIntegration.syncTransactions.pendingReview")
        }
        // Standard path: only the transactions at/above the threshold; none of the
        // low-confidence amounts ever reach the lifecycle coordinator.
        coVerify(exactly = 2) { reviewCoordinator.createExpenseStandaloneV2(any()) }
        assertEquals(listOf(20.0, 30.0), coordinatorRequests.map { it.amount })
        assertTrue(coordinatorRequests.all { it.source == ExpenseSource.BANK_API_SYNC })
    }

    @Test
    fun `sync run events carry only hashed provider id and allow-listed metadata with no sentinel text`() = runTest {
        // W2 call-site privacy: sentinel provider id / description (with an IBAN-like token) /
        // merchant / reference must never reach any run event field, terminal summary, or
        // metadata value. Production emission sites: BankApiIntegration.syncTransactions
        // (PAGE_FETCHED itemCount; TRANSACTION_SKIPPED/SENT_FOR_REVIEW/IMPORTED/FAILED with
        // putHashed("providerTransactionId") + currency/confidence/errorCount).
        val bankConnectionDao = mockk<BankConnectionDao>(relaxed = true)
        val pendingReviewDao = mockk<PendingReviewDao>(relaxed = true)
        val database = mockk<AppDatabase>(relaxed = true)
        val reviewCoordinator = mockk<TransactionLifecycleCoordinator>(relaxed = true)
        io.mockk.mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { database.withTransaction(any<suspend () -> Any>()) } coAnswers {
            secondArg<suspend () -> Any>().invoke()
        }
        coEvery {
            pendingReviewDao.getPendingDuplicateCandidateInRangeTypeAware(
                any(), any(), any(), any(), any(), any(), any(), any()
            )
        } returns null
        coEvery { pendingReviewDao.insert(any()) } returns 1L
        coEvery { reviewCoordinator.createExpenseStandaloneV2(any()) } returnsMany listOf(
            CreateExpenseResult.Created(expenseId = 1L),
            CreateExpenseResult.ValidationFailed(listOf("SENTINEL_DESC_9k SENTINEL_MERCHANT_9k"))
        )

        val sentinelDescription = "SENTINEL_DESC_9k GR1601101250000000012300695"
        fun tx(id: String, amount: Double, merchant: String, confidence: Float) = BankTransaction(
            id = id, date = 1000L, amount = amount,
            currency = "EUR", merchant = merchant, description = sentinelDescription,
            reference = "SENTINEL_REF_9k", movementType = BankMovementType.PURCHASE, confidence = confidence
        )
        val rawIds = listOf(
            "SENTINEL_TXN_9k_low", "SENTINEL_TXN_9k_zero", "SENTINEL_TXN_9k_ok", "SENTINEL_TXN_9k_invalid"
        )
        val transactions = listOf(
            tx(rawIds[0], -10.0, "SENTINEL_MERCHANT_9k_low", 0.30f),
            tx(rawIds[1], 0.0, "SENTINEL_MERCHANT_9k_zero", 0.95f),
            tx(rawIds[2], -30.0, "SENTINEL_MERCHANT_9k_ok", 0.95f),
            tx(rawIds[3], -31.0, "SENTINEL_MERCHANT_9k_invalid", 0.95f)
        )
        val handle = RecordingOperationRunHandle()
        val testIntegration = spyk(
            BankApiIntegration(
                timeProvider = FakeTimeProvider(),
                coordinator = reviewCoordinator,
                writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true),
                operationRunRecorder = RecordingRecorder(handle),
                hashingService = DefaultSensitiveHashingService(),
                privacySettingsRepository = mockk(relaxed = true) {
                    coEvery { getSettings() } returns PrivacySettings()
                },
                bankConnectionDao = bankConnectionDao,
                pendingReviewDao = pendingReviewDao,
                database = database
            )
        )
        every { testIntegration.generateMockTransactions(any(), any()) } returns transactions

        if (!BuildConfig.DEBUG) {
            assertDemoApiDenied { testIntegration.syncTransactions(connection, since = 0L) }
            coVerify(exactly = 0) { reviewCoordinator.createExpenseStandaloneV2(any()) }
            return@runTest
        }
        val outcome = testIntegration.syncTransactions(connection, since = 0L)

        assertEquals(BankSyncOutcome.Partial(importedCount = 2, skippedCount = 1, failedCount = 1), outcome)

        val allowedKeys = setOf(
            "providerTransactionId", "currency", "confidence", "reason", "errorCount", "itemCount", "refreshOutcome"
        )
        val fixedSkipReasons = setOf(
            com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.INVALID_AMOUNT,
            com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.REFUND_UNSUPPORTED,
            com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.TRANSFER_METADATA_MISSING
        )
        val hex16 = Regex("^[0-9a-f]{16}$")
        val sentinelFragments = listOf(
            "SENTINEL_TXN_9k", "SENTINEL_DESC_9k", "SENTINEL_MERCHANT_9k", "SENTINEL_REF_9k",
            "GR1601101250000000012300695"
        )

        val perTransaction = handle.events.filter { it.stage.startsWith("TRANSACTION_") }
        assertEquals(
            listOf("TRANSACTION_SENT_FOR_REVIEW", "TRANSACTION_SKIPPED", "TRANSACTION_IMPORTED", "TRANSACTION_FAILED"),
            perTransaction.map { it.stage }
        )
        assertTrue(handle.events.any { it.stage == "PAGE_FETCHED" })

        handle.events.forEach { e ->
            val json = org.json.JSONObject(e.metadata.toJson())
            val keys = json.keys().asSequence().toSet()
            assertTrue("stage=${e.stage} keys outside allow-list: $keys", allowedKeys.containsAll(keys))
            val serialized = listOf(
                e.stage, e.outcome.name, e.reasonCode?.name, e.severity.name,
                e.metadata.toJson(), e.entityType, e.entityId
            ).joinToString("|")
            sentinelFragments.forEach { s ->
                assertTrue("sentinel $s leaked in stage=${e.stage}: $serialized", !serialized.contains(s))
            }
            assertNull("no raw throwable expected on stage=${e.stage}", e.exception)
            if (json.has("providerTransactionId")) {
                val hashed = json.getString("providerTransactionId")
                assertTrue("not 16 hex: $hashed", hashed.matches(hex16))
                assertTrue("raw id leaked", hashed !in rawIds)
            }
        }
        // Every per-transaction event must carry the hashed identity.
        perTransaction.forEach {
            assertTrue(org.json.JSONObject(it.metadata.toJson()).has("providerTransactionId"))
        }
        // Distinct raw ids hash to distinct identities.
        val hashes = perTransaction.map { org.json.JSONObject(it.metadata.toJson()).getString("providerTransactionId") }
        assertEquals(hashes.size, hashes.toSet().size)
        // Contract skip uses a fixed reason code, never text.
        assertTrue(perTransaction.single { it.stage == "TRANSACTION_SKIPPED" }.reasonCode in fixedSkipReasons)
        // Terminal summaries are bounded counters, never payload text.
        handle.terminalTexts.filterNotNull().forEach { text ->
            sentinelFragments.forEach { s -> assertTrue("terminal text leaked $s", !text.contains(s)) }
        }
    }

    @Test
    fun `low confidence bank transaction maps to a strict hashed identity request`() = runTest {
        // P10-P1-04 / P10-CURRENT-006 mapping shape only (the review routing itself is
        // asserted in the sync test above): mapTransactionToExpense does not apply the
        // confidence check, so a low-confidence transaction still maps to the same
        // strict, hashed-identity request shape as a high-confidence one.
        val tx = BankTransaction(
            id = "low-conf-1", date = 1000L, amount = -10.0,
            currency = "EUR", merchant = "Unknown", description = "Blurry receipt",
            reference = null, movementType = BankMovementType.PURCHASE, confidence = 0.30f
        )
        val request = integration.mapTransactionToExpense(tx, connection, syncRunId = 1L)
        assertEquals(ExpenseSource.BANK_API_SYNC, request.source)
        // P10-CURRENT-006 (RP-17 17-C): idempotencyKey carries the HASHED provider
        // transaction identity, never the raw provider id — low-confidence requests
        // keep exactly the same strict-identity shape as high-confidence ones.
        assertEquals(request.bankProviderTransactionIdHash, request.idempotencyKey)
        assertTrue(
            "raw provider id must never be the idempotency key",
            request.idempotencyKey != "low-conf-1"
        )
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
        if (!BuildConfig.DEBUG) {
            assertDemoApiDenied { testIntegration.completeConnection("revolut", "auth-code-123") }
            coVerify(exactly = 0) { bankConnectionDao.insert(any()) }
            return@runTest
        }
        val result = testIntegration.completeConnection("revolut", "auth-code-123")
        assertNotNull(result)
        assertEquals(999L, result!!.id)
        assertEquals("revolut", result.bankId)
        assertTrue(result.isConnected)
    }

    // ── P10-P1-06 / RP-17 17-E: Token refresh persistence ────────────────────

    @Test
    fun `refreshToken persists new tokens on success`() = runTest {
        // P10-P1-06: refreshToken must call bankConnectionDao.updateTokenIfConnected on success.
        // The connection carries encrypted tokens.
        val bankConnectionDao = mockk<BankConnectionDao>(relaxed = true)
        val pendingReviewDao = mockk<PendingReviewDao>(relaxed = true)
        val database = mockk<AppDatabase>(relaxed = true)
        // The low-confidence review path runs inside database.withTransaction —
        // execute the block directly against this test's relaxed mock (same
        // repo-standard pattern as setUp).
        io.mockk.mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { database.withTransaction(any<suspend () -> Any>()) } coAnswers {
            secondArg<suspend () -> Any>().invoke()
        }
        // High-confidence imports must resolve to a real Created result — a bare
        // relaxed coordinator mock would fabricate a base sealed instance that
        // matches no outcome branch and be recorded as per-transaction failures.
        coEvery { coordinator.createExpenseStandaloneV2(any()) } returns
            CreateExpenseResult.Created(expenseId = 1L)
        // refreshToken() decrypts the existing refresh token; the cipher stub keeps
        // this a value-preserving round-trip on the JVM (no Android Keystore).
        // tokenExpiry must be strictly older than the fake clock for the refresh
        // branch (`tokenExpiry < now`) to trigger, so the clock is set to 1_000.
        val conn = connection.copy(
            id = 5L,
            refreshToken = BankTokenCipher.encryptIfNeeded("demo_refresh_revolut"),
            tokenExpiry = 1L // expired — triggers refresh
        )
        val testIntegration = BankApiIntegration(
            timeProvider = FakeTimeProvider(fixedTime = 1_000L),
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
        // RP-17 17-E: the token write is conditional on isConnected = 1.
        // 1 affected row = write succeeded; the sync then completes normally.
        coEvery {
            bankConnectionDao.updateTokenIfConnected(any(), any(), any(), any(), any())
        } returns 1
        if (!BuildConfig.DEBUG) {
            assertDemoApiDenied { testIntegration.syncTransactions(conn, since = 0L) }
            coVerify(exactly = 0) { bankConnectionDao.updateTokenIfConnected(any(), any(), any(), any(), any()) }
            return@runTest
        }
        val syncOutcome = testIntegration.syncTransactions(conn, since = 0L)
        assertTrue(
            "expected a terminal success/partial outcome, got $syncOutcome",
            syncOutcome is BankSyncOutcome.Success || syncOutcome is BankSyncOutcome.Partial
        )
        coVerify {
            bankConnectionDao.updateTokenIfConnected(5L, any(), any(), any(), any())
        }
    }

    @Test
    fun `token write to disconnected connection yields typed Blocked outcome`() = runTest {
        // RP-17 17-E: refresh/disconnect race — the conditional update affected
        // zero rows, so the sync must stop with a typed disconnected/blocked
        // outcome and never resurrect tokens on a disconnected connection.
        val bankConnectionDao = mockk<BankConnectionDao>(relaxed = true)
        // tokenExpiry must be strictly older than the fake clock for the refresh
        // branch (`tokenExpiry < now`) to trigger; otherwise the sync would skip
        // refresh entirely and the Blocked outcome would never be reachable.
        val conn = connection.copy(
            id = 5L,
            isConnected = false,
            refreshToken = BankTokenCipher.encryptIfNeeded("demo_refresh_revolut"),
            tokenExpiry = 1L // expired — triggers refresh
        )
        val testIntegration = BankApiIntegration(
            timeProvider = FakeTimeProvider(fixedTime = 1_000L),
            coordinator = mockk(relaxed = true),
            writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true),
            operationRunRecorder = BlockInvokingRecorder(),
            hashingService = DefaultSensitiveHashingService(),
            privacySettingsRepository = mockk(relaxed = true) {
                coEvery { getSettings() } returns PrivacySettings()
            },
            bankConnectionDao = bankConnectionDao,
            pendingReviewDao = mockk<PendingReviewDao>(relaxed = true),
            database = mockk<AppDatabase>(relaxed = true)
        )
        coEvery {
            bankConnectionDao.updateTokenIfConnected(any(), any(), any(), any(), any())
        } returns 0

        if (!BuildConfig.DEBUG) {
            assertDemoApiDenied { testIntegration.syncTransactions(conn, since = 0L) }
            coVerify(exactly = 0) { bankConnectionDao.updateTokenIfConnected(any(), any(), any(), any(), any()) }
            return@runTest
        }
        val syncOutcome = testIntegration.syncTransactions(conn, since = 0L)

        assertTrue("expected Blocked, got $syncOutcome", syncOutcome is BankSyncOutcome.Blocked)
        assertEquals(
            com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.CONNECTION_DISCONNECTED,
            (syncOutcome as BankSyncOutcome.Blocked).reasonCode
        )
    }

    @Test
    fun `bank sync rethrows cancellation and does not continue importing`() = runTest {
        if (!BuildConfig.DEBUG) {
            assertDemoApiDenied { integration.syncTransactions(connection, since = 0L) }
            return@runTest
        }
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
    private suspend fun assertDemoApiDenied(block: suspend () -> Any?) {
        assertTrue(runCatching { block() }.exceptionOrNull() is IllegalStateException)
        coVerify(exactly = 0) { coordinator.createExpenseStandaloneV2(any()) }
    }
}
