package com.yourname.expensetracker.domain.bank

import androidx.annotation.VisibleForTesting
import com.yourname.expensetracker.BuildConfig
import androidx.room.withTransaction
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.BankConnectionDao
import com.yourname.expensetracker.data.database.dao.PendingReviewDao
import com.yourname.expensetracker.data.database.entity.BankConnection
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.data.database.entity.SyncFrequency
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.TransferDirection
import com.yourname.expensetracker.data.security.BankTokenCipher
import com.yourname.expensetracker.domain.intelligence.DuplicateDetectionPolicy
import com.yourname.expensetracker.domain.common.sha256Prefix
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.transaction.CreateExpenseResult
import com.yourname.expensetracker.domain.transaction.DeduplicationMode
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.privacy.RawContentSanitizer
import com.yourname.expensetracker.domain.privacy.RawStorageMode
import com.yourname.expensetracker.domain.privacy.SensitiveHashingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class BankTransaction(
    val id: String,
    val date: Long,
    val amount: Double,
    val currency: String,
    val merchant: String,
    val description: String,
    val reference: String?,
    val movementType: BankMovementType? = null,
    val transferDirection: TransferDirection? = null,
    /**
     * RP-17 17-C: provider-supplied, privacy-safe account reference for
     * transfers (e.g. a provider-masked IBAN like "****1234"). This is an
     * approved reference supplied independently by the provider — the raw
     * transaction description must never be used as the transfer account name.
     */
    val transferAccountRef: String? = null,
    /** P10-P1-04: Confidence score (0.0–1.0). Below-threshold transactions route to PendingReview. */
    val confidence: Float = 1.0f
)

enum class BankMovementType {
    PURCHASE,
    WITHDRAWAL,
    TRANSFER,
    DEPOSIT,
    /**
     * RP-17 17-C: typed provider refund/reversal/cashback semantics. Refund-like
     * movement is only honored when the provider supplies these explicitly;
     * description text alone never triggers refund handling (that skips with
     * REFUND_UNSUPPORTED).
     */
    REFUND,
    REVERSAL,
    CASHBACK
}

/** RP-17 17-C: refund-like movement types (typed provider semantics). */
private val REFUND_LIKE_MOVEMENT_TYPES: Set<BankMovementType> =
    setOf(BankMovementType.REFUND, BankMovementType.REVERSAL, BankMovementType.CASHBACK)

/** RP-17 17-C: refund-like words never mint a refund without typed provider semantics. */
private val REFUND_DESCRIPTION_WORDS = listOf("refund", "reversal", "cashback")

private fun BankMovementType.toTransactionType(): TransactionType = when (this) {
    BankMovementType.PURCHASE -> TransactionType.PURCHASE
    BankMovementType.WITHDRAWAL -> TransactionType.WITHDRAWAL
    BankMovementType.TRANSFER -> TransactionType.TRANSFER
    BankMovementType.DEPOSIT -> TransactionType.DEPOSIT
    // RP-17 17-C: typed refund semantics map to a credit-type transaction.
    BankMovementType.REFUND -> TransactionType.DEPOSIT
    BankMovementType.REVERSAL -> TransactionType.DEPOSIT
    BankMovementType.CASHBACK -> TransactionType.DEPOSIT
}

// RP-17 17-B: the flat `SyncResult(success/errors:List<String>)` was replaced by
// the sealed [BankSyncOutcome] (see BankSyncOutcome.kt). Outcomes carry counts
// and typed controlled codes; no string parsing anywhere in the chain.


// TODO (P2-1): Demo bank sync is intentionally non-deterministic.
// When real bank providers are added, ensure sync is idempotent and repeatable.
//
// RP-17 (register D12, deferred-gate note): provider cursor persistence and
// outer-batch atomicity remain DEFERRED pending a real provider cursor/identity
// contract. Current idempotency: per-transaction STRICT_EXTERNAL_ID dedupe on
// the hashed provider transaction identity (idem:BANK_API_SYNC:<hash>) plus the
// atomic bank-review unique identity index (17-D). A crash mid-batch can
// re-process already-committed items; they resolve to duplicates/skips, never
// duplicates rows.

@Singleton
class BankApiIntegration @Inject constructor(
    private val timeProvider: TimeProvider,
    private val coordinator: TransactionLifecycleCoordinator,
    private val writeBarrier: DatabaseWriteBarrier,
    private val operationRunRecorder: com.yourname.expensetracker.domain.diagnostics.OperationRunRecorder,
    private val hashingService: SensitiveHashingService,
    private val privacySettingsRepository: PrivacySettingsRepository,
    private val bankConnectionDao: BankConnectionDao,
    private val pendingReviewDao: PendingReviewDao,
    private val database: AppDatabase
) {
    /**
     * RP-17 17-D: optimizes away same-process review-creation races. The unique
     * bankReviewIdentity index is the actual atomicity guarantee.
     */
    private val reviewInsertMutex = kotlinx.coroutines.sync.Mutex()

    companion object {
        /** P10-P1-04: Confidence below this threshold routes bank transactions to PendingReview. */
        const val BANK_REVIEW_CONFIDENCE_THRESHOLD = 0.75f

        /**
         * RP-17 17-D: HMAC purpose for the stable bank-review identity.
         * Identity input = "<bankId>|<connectionId>|<providerTransactionId>" —
         * provider transaction identity scoped to the approved connection/account.
         * Stable across sync runs; unique index on pending_reviews.bankReviewIdentity
         * makes duplicate review creation impossible under concurrent syncs.
         */
        const val BANK_REVIEW_IDENTITY_PURPOSE = "bankReviewIdentity"

        /** Existing HMAC purpose reused for the connection account scope hash. */
        const val BANK_ACCOUNT_SCOPE_PURPOSE = "bankAccountId"

        // Supported bank APIs (placeholders for actual implementations)
        val SUPPORTED_BANKS = listOf(
            BankInfo("nbg", "National Bank of Greece", "GR", "Open Banking API"),
            BankInfo("eurobank", "Eurobank", "GR", "Open Banking API"),
            BankInfo("alpha", "Alpha Bank", "GR", "Open Banking API"),
            BankInfo("piraeus", "Piraeus Bank", "GR", "Open Banking API"),
            BankInfo("revolut", "Revolut", "EU", "Revolut API"),
            BankInfo("n26", "N26", "EU", "N26 API")
        )
    }
    
    /**
     * Get list of supported banks for connection.
     */
    fun getSupportedBanks(): List<BankInfo> = SUPPORTED_BANKS
    
    /**
     * Check if a bank is supported.
     */
    fun isBankSupported(bankId: String): Boolean {
        return SUPPORTED_BANKS.any { it.id == bankId }
    }
    
    /**
     * Initiate OAuth connection flow (placeholder).
     */
    @StubForDemo
    suspend fun initiateConnection(bankId: String): String? = withContext(Dispatchers.IO) {
        requireStubMode()
        writeBarrier.checkWritesAllowed("BankApiIntegration.initiateConnection")

        val bank = SUPPORTED_BANKS.find { it.id == bankId }
        if (bank == null) {
            Timber.e("Bank not supported: $bankId")
            return@withContext null
        }
        
        "https://oauth.${bank.id}.example.com/auth?client_id=demo&response_type=code"
    }
    
    @StubForDemo
    suspend fun completeConnection(
        bankId: String,
        authCode: String
    ): BankConnection? = withContext(Dispatchers.IO) {
        requireStubMode()
        writeBarrier.checkWritesAllowed("BankApiIntegration.completeConnection")

        val bank = SUPPORTED_BANKS.find { it.id == bankId } ?: return@withContext null
        
        val connection = BankConnection(
            bankId = bankId,
            bankName = bank.name,
            countryCode = bank.countryCode,
            isConnected = true,
            isActive = true,
            accessToken = BankTokenCipher.encryptIfNeeded("demo_token_$bankId"),
            refreshToken = BankTokenCipher.encryptIfNeeded("demo_refresh_$bankId"),
            tokenEncryptionVersion = 1,
            tokenExpiry = timeProvider.now() + (30 * 24 * 60 * 60 * 1000L),
            createdAt = timeProvider.now()
        )
        // P10-P1-01: Persist connection so it survives process death
        val id = bankConnectionDao.insert(connection)
        connection.copy(id = id)
    }
    
    @StubForDemo
    suspend fun syncTransactions(
        connection: BankConnection,
        since: Long? = null
    ): BankSyncOutcome = withContext(Dispatchers.IO) {
        requireStubMode()
        // DDL-016-14: operation run must start BEFORE barrier check so blocked sync has a durable record

        // RP-17 17-B: every barrier/token/reauth branch assigns its outcome; the
        // final block assigns the aggregate. Cancellation propagates (never an outcome).
        var syncOutcome: BankSyncOutcome = BankSyncOutcome.RetryableFailure()
        operationRunRecorder.runOperation("BANK_SYNC", actor = "system") { run ->
            run.event("SYNC_STARTED", com.yourname.expensetracker.domain.diagnostics.EventOutcome.ATTEMPTED)

            // Check write barrier inside operation so blocking is durable
            try {
                writeBarrier.checkWritesAllowed("BankApiIntegration.syncTransactions")
            } catch (e: com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException) {
                // DDL-C67-02: WRITE_BARRIER is a stage event (non-terminal); CANCELLED is the single terminal
                run.event("WRITE_BARRIER", com.yourname.expensetracker.domain.diagnostics.EventOutcome.BLOCKED,
                    severity = com.yourname.expensetracker.domain.diagnostics.EventSeverity.WARNING,
                    reasonCode = com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.RESTORE_BLOCKED,
                    exception = e, isTerminal = false)
                run.cancelled(com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.RESTORE_BLOCKED.name)
                // RP-17 17-B: barrier block is a typed Blocked outcome (17-E barrier semantics).
                syncOutcome = BankSyncOutcome.Blocked(
                    com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.RESTORE_BLOCKED
                )
                return@runOperation
            }

            // I3: Token refresh with finalization
            if (connection.tokenExpiry != null && connection.tokenExpiry < timeProvider.now()) {
                run.event("TOKEN_REFRESH_STARTED", com.yourname.expensetracker.domain.diagnostics.EventOutcome.ATTEMPTED)
                when (refreshToken(connection)) {
                    RefreshOutcome.Success -> {
                        run.event("TOKEN_REFRESHED", com.yourname.expensetracker.domain.diagnostics.EventOutcome.COMPLETED)
                    }
                    RefreshOutcome.ReauthRequired -> {
                        // NEW-P10-002: keystore key was permanently invalidated, so the stored
                        // refresh token can never be decrypted again. Record a DISTINCT, durable
                        // re-authentication-required signal (dedicated event name + metadata flag +
                        // reason message) so this is no longer indistinguishable from a generic
                        // token refresh failure / null decrypt.
                        run.event("TOKEN_REAUTH_REQUIRED",
                            com.yourname.expensetracker.domain.diagnostics.EventOutcome.FAILED_FINAL,
                            reasonCode = com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.TOKEN_INVALID,
                            severity = com.yourname.expensetracker.domain.diagnostics.EventSeverity.ERROR,
                            metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                                .put("refreshOutcome", "REAUTH_REQUIRED").build())
                        run.failedFinal("REAUTH_REQUIRED: bank token key invalidated, user must re-authenticate")
                        // RP-17 17-B: keystore invalidation is the typed ReauthRequired outcome.
                        syncOutcome = BankSyncOutcome.ReauthRequired(
                            com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.TOKEN_INVALID
                        )
                        return@runOperation
                    }
                    RefreshOutcome.Disconnected -> {
                        // RP-17 17-E: conditional token write affected zero rows — the
                        // connection was disconnected concurrently. Typed blocked outcome;
                        // never retries into a disconnected connection.
                        run.event("TOKEN_WRITE_DISCONNECTED",
                            com.yourname.expensetracker.domain.diagnostics.EventOutcome.BLOCKED,
                            reasonCode = com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.CONNECTION_DISCONNECTED,
                            severity = com.yourname.expensetracker.domain.diagnostics.EventSeverity.WARNING)
                        run.failedFinal(com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.CONNECTION_DISCONNECTED.name)
                        syncOutcome = BankSyncOutcome.Blocked(
                            com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.CONNECTION_DISCONNECTED
                        )
                        return@runOperation
                    }
                    RefreshOutcome.Failed -> {
                        run.event("TOKEN_REFRESH_FAILED",
                            com.yourname.expensetracker.domain.diagnostics.EventOutcome.FAILED_FINAL,
                            reasonCode = com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.TOKEN_INVALID)
                        run.failedFinal("Token expired and refresh failed")
                        // RP-17 17-B: generic refresh failure is terminal for this run.
                        syncOutcome = BankSyncOutcome.PermanentFailure(
                            com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.TOKEN_INVALID
                        )
                        return@runOperation
                    }
                }
            }

            val mockTransactions = generateMockTransactions(connection.bankId, since)
            run.event("PAGE_FETCHED", com.yourname.expensetracker.domain.diagnostics.EventOutcome.COMPLETED,
                metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                    .put("itemCount", mockTransactions.size).build())

            var importedCount = 0
            var skippedCount = 0
            val errors = mutableListOf<String>()
            // RP-17 17-C: raw-storage mode resolved once per sync for contract gating.
            val mode = privacySettingsRepository.getSettings().rawBankStatementStorageMode

            val syncRunId = run.runId  // PR6: capture sync run ID for provenance linking
            for (transaction in mockTransactions) {
                try {
                    // RP-17 17-C: contract gating BEFORE any routing (lifecycle or review).
                    // Zero amounts, refund-like text without typed provider semantics, and
                    // transfers without provider-supplied direction + privacy-safe account
                    // reference never enter the lifecycle or the review queue.
                    val contractSkip = evaluateBankTransactionContract(transaction, mode)
                    if (contractSkip != null) {
                        skippedCount++
                        run.event("TRANSACTION_SKIPPED",
                            com.yourname.expensetracker.domain.diagnostics.EventOutcome.SKIPPED,
                            reasonCode = contractSkip,
                            metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                                .putHashed("providerTransactionId", transaction.id)
                                .put("currency", transaction.currency)
                                .build())
                        run.increment(processed = 1, skipped = 1)
                        continue
                    }

                    // P10-P1-04: Route low-confidence transactions to PendingReview
                    if (transaction.confidence < BANK_REVIEW_CONFIDENCE_THRESHOLD) {
                        val transactionType = transaction.movementType?.toTransactionType()
                            ?: inferTransactionType(transaction)
                        val normalizedMerchant = DuplicateDetectionPolicy.normalizeMerchant(transaction.merchant)
                        val dedupWindow = DuplicateDetectionPolicy.DUPLICATE_WINDOW_MS
                        val amountTolerance = DuplicateDetectionPolicy.AMOUNT_TOLERANCE
                        val startDate = transaction.date - dedupWindow
                        val endDate = DuplicateDetectionPolicy.windowEndExclusive(transaction.date)
                        val minAmount = kotlin.math.abs(transaction.amount) - amountTolerance
                        val maxAmount = kotlin.math.abs(transaction.amount) + amountTolerance

                        // P10-P1-08: Check existing pending reviews before creating one
                        val existingPending = pendingReviewDao.getPendingDuplicateCandidateInRangeTypeAware(
                            merchantKey = normalizedMerchant,
                            merchantName = transaction.merchant,
                            startDate = startDate,
                            endDate = endDate,
                            minAmount = minAmount,
                            maxAmount = maxAmount,
                            currency = transaction.currency,
                            transactionType = transactionType.name
                        )
                        if (existingPending != null) {
                            skippedCount++
                            run.event("TRANSACTION_DUPLICATE_SKIPPED",
                                com.yourname.expensetracker.domain.diagnostics.EventOutcome.DUPLICATE,
                                reasonCode = com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.DUPLICATE,
                                metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                                    .putHashed("providerTransactionId", transaction.id)
                                    .put("reason", "PendingReview already exists").build())
                            run.increment(processed = 1, skipped = 1)
                        } else {
                            // RP-17 17-D: create the PendingReview through the atomic
                            // insert-if-absent path. The unique index on
                            // pending_reviews.bankReviewIdentity makes duplicate review
                            // creation impossible even under concurrent syncs for the same
                            // connection; the in-process mutex only serializes creation so
                            // the common case never pays for a conflict. The mutex is an
                            // optimization — the constraint is the guarantee.
                            var createdReview = false
                            reviewInsertMutex.withLock {
                                database.withTransaction {
                                    writeBarrier.checkWritesAllowed("BankApiIntegration.syncTransactions.pendingReview")
                                    val review = buildBankPendingReview(
                                        transaction = transaction,
                                        connection = connection,
                                        transactionType = transactionType,
                                        normalizedMerchant = normalizedMerchant,
                                        mode = mode
                                    )
                                    val reviewId = pendingReviewDao.insert(review)
                                    // IGNORE conflict strategy: -1L means the unique identity
                                    // index already holds a review for this provider transaction.
                                    createdReview = reviewId > 0
                                }
                            }
                            if (createdReview) {
                                importedCount++
                                run.event("TRANSACTION_SENT_FOR_REVIEW",
                                    com.yourname.expensetracker.domain.diagnostics.EventOutcome.CREATED,
                                    entityType = "pending_review",
                                    metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                                        .putHashed("providerTransactionId", transaction.id)
                                        .put("confidence", transaction.confidence.toString())
                                        .put("currency", transaction.currency)
                                        .build())
                                run.increment(processed = 1, succeeded = 1)
                            } else {
                                skippedCount++
                                run.event("TRANSACTION_DUPLICATE_SKIPPED",
                                    com.yourname.expensetracker.domain.diagnostics.EventOutcome.DUPLICATE,
                                    reasonCode = com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.DUPLICATE,
                                    metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                                        .putHashed("providerTransactionId", transaction.id)
                                        .put("reason", "bank review identity conflict").build())
                                run.increment(processed = 1, skipped = 1)
                            }
                        }
                    } else {
                        // High-confidence transactions go through the standard coordinator pipeline
                        val request = mapTransactionToExpense(transaction, connection, syncRunId)
                            .copy(correlationId = run.correlationId)  // DDL-016-15: propagate bank sync correlation
                        when (val result = coordinator.createExpenseStandaloneV2(request)) {
                            is CreateExpenseResult.Created -> {
                                importedCount++
                                run.event("TRANSACTION_IMPORTED",
                                    com.yourname.expensetracker.domain.diagnostics.EventOutcome.CREATED,
                                    entityType = "expense", entityId = result.expenseId,
                                    metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                                        .putHashed("providerTransactionId", transaction.id)
                                        .put("currency", transaction.currency)
                                        .build())
                                run.increment(processed = 1, succeeded = 1)
                            }
                            is CreateExpenseResult.DuplicateSkipped -> {
                                skippedCount++
                                run.event("TRANSACTION_DUPLICATE_SKIPPED",
                                    com.yourname.expensetracker.domain.diagnostics.EventOutcome.DUPLICATE,
                                    reasonCode = com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.DUPLICATE,
                                    metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                                        .putHashed("providerTransactionId", transaction.id).build())
                                run.increment(processed = 1, skipped = 1)
                            }
                            is CreateExpenseResult.ValidationFailed -> {
                                val hashId = transaction.id.sha256Prefix(8)
                                errors.add("Transaction validation failed [hash=$hashId, errors=${result.errors.size}]")
                                run.event("TRANSACTION_FAILED",
                                    com.yourname.expensetracker.domain.diagnostics.EventOutcome.FAILED_FINAL,
                                    reasonCode = com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.VALIDATION_FAILED,
                                    metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                                        .putHashed("providerTransactionId", transaction.id)
                                        .put("errorCount", result.errors.size).build())
                                run.increment(processed = 1, failed = 1, errors = 1)
                            }
                            is CreateExpenseResult.InsertConflict -> {
                                skippedCount++
                                run.event("TRANSACTION_DUPLICATE_SKIPPED",
                                    com.yourname.expensetracker.domain.diagnostics.EventOutcome.DUPLICATE,
                                    reasonCode = com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.DUPLICATE,
                                    metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                                        .putHashed("providerTransactionId", transaction.id)
                                        .put("currency", transaction.currency)
                                        .build())
                                run.increment(processed = 1, skipped = 1)
                            }
                            is CreateExpenseResult.Error -> {
                                val hashId = transaction.id.sha256Prefix(8)
                                errors.add("Transaction import failed [hash=$hashId, reason=ERROR]")
                                run.event("TRANSACTION_FAILED",
                                    com.yourname.expensetracker.domain.diagnostics.EventOutcome.FAILED_RETRYABLE,
                                    exception = result.exception,
                                    metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                                        .putHashed("providerTransactionId", transaction.id)
                                        .put("currency", transaction.currency)
                                        .build())
                                run.increment(processed = 1, failed = 1, errors = 1)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // P10-CURRENT-018: never swallow coroutine/worker cancellation. Rethrow so the
                    // sync stops promptly and does not keep importing, and so restore/backup
                    // cancellation semantics hold once this runs inside a worker. CancellationException
                    // is NOT a per-transaction failure and must not be recorded as one.
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    // DDL-81-19: generic per-transaction exception needs a TRANSACTION_FAILED event
                    val hashId = transaction.id.sha256Prefix(8)
                    errors.add("Transaction import failed [hash=$hashId, reason=EXCEPTION]")
                    run.event("TRANSACTION_FAILED",
                        com.yourname.expensetracker.domain.diagnostics.EventOutcome.FAILED_RETRYABLE,
                        reasonCode = com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.UNKNOWN_ERROR,
                        severity = com.yourname.expensetracker.domain.diagnostics.EventSeverity.ERROR,
                        exception = e,
                        metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                            .putHashed("providerTransactionId", transaction.id)
                            .put("currency", transaction.currency)
                            .build())
                    run.increment(processed = 1, failed = 1, errors = 1)
                    Timber.e(e, "Failed to import transaction")
                }
            }

            // RP-17 17-B: aggregate outcome, mapped one-to-one to the operation-run
            // finalizer. Skips alone never fail a sync; failures with at least one
            // import are Partial; failures without any import are retryable.
            syncOutcome = when {
                errors.isEmpty() -> BankSyncOutcome.Success(
                    importedCount = importedCount,
                    skippedCount = skippedCount
                )
                importedCount > 0 -> BankSyncOutcome.Partial(
                    importedCount = importedCount,
                    skippedCount = skippedCount,
                    failedCount = errors.size
                )
                else -> BankSyncOutcome.RetryableFailure(failedCount = errors.size)
            }
            when (syncOutcome) {
                is BankSyncOutcome.Success -> {
                    // success() called automatically by runOperation if still RUNNING
                }
                is BankSyncOutcome.Partial -> run.partialSuccess("${errors.size} errors")
                else -> run.failedRetryable("${errors.size} errors, no imports")
            }
        }
        syncOutcome
    }
    
    /**
     * Outcome of a token refresh attempt.
     *
     * NEW-P10-002: a permanently-invalidated keystore key ([RefreshOutcome.ReauthRequired])
     * is surfaced distinctly from a generic decryption failure ([RefreshOutcome.Failed]) so the
     * caller can record a durable re-authentication-required signal instead of collapsing key
     * invalidation into an indistinguishable generic failure (the old `decryptIfNeeded` -> null).
     *
     * RP-17 17-E: [RefreshOutcome.Disconnected] is returned when the conditional token
     * write affected zero rows — the connection was disconnected concurrently
     * (refresh/disconnect race) and the sync must stop with a typed blocked outcome.
     */
    private sealed interface RefreshOutcome {
        object Success : RefreshOutcome
        object ReauthRequired : RefreshOutcome
        object Failed : RefreshOutcome
        object Disconnected : RefreshOutcome
    }

    /**
     * Refresh access token (stub with persistence).
     *
     * P10-P1-06: When decryption succeeds, generates new stub tokens, encrypts them,
     * and persists via [BankConnectionDao.updateTokenIfConnected]. In a real implementation,
     * this would call the provider's OAuth refresh endpoint.
     *
     * RP-17 17-E: the write barrier is checked IMMEDIATELY before the token write,
     * and the write is conditional on `isConnected = 1` (SQL-level refresh/disconnect
     * race guard). Zero affected rows map to [RefreshOutcome.Disconnected].
     */
    @StubForDemo
    private suspend fun refreshToken(connection: BankConnection): RefreshOutcome {
        requireStubMode()

        // NEW-P10-002: use decryptWithResult so key invalidation is not collapsed to null.
        return when (BankTokenCipher.decryptWithResult(connection.refreshToken)) {
            is BankTokenCipher.DecryptResult.Success -> {
                // P10-P1-06: Generate fresh stub tokens and encrypt
                val newAccessToken = BankTokenCipher.encryptIfNeeded("demo_token_${connection.bankId}_refreshed")
                val newRefreshToken = BankTokenCipher.encryptIfNeeded("demo_refresh_${connection.bankId}_refreshed")
                val newExpiry = timeProvider.now() + (30 * 24 * 60 * 60 * 1000L)
                // RP-17 17-E: barrier re-checked immediately before the write.
                writeBarrier.checkWritesAllowed("BankApiIntegration.refreshToken")
                val updatedRows = bankConnectionDao.updateTokenIfConnected(
                    id = connection.id,
                    accessToken = newAccessToken ?: "",
                    refreshToken = newRefreshToken,
                    encryptionVersion = 1,
                    expiry = newExpiry
                )
                if (updatedRows == 0) {
                    // Refresh/disconnect race: the connection row was disconnected between
                    // read and write. Never resurrect a disconnected connection's tokens.
                    Timber.w(
                        "Token write skipped: connection %s no longer connected",
                        connection.bankId
                    )
                    return RefreshOutcome.Disconnected
                }
                Timber.i("Token refreshed and persisted for bank %s", connection.bankId)
                RefreshOutcome.Success
            }
            is BankTokenCipher.DecryptResult.KeyInvalidated -> {
                // Keystore key permanently invalidated (e.g. biometric / lock-screen change):
                // the stored refresh token can never be decrypted again, so the user must
                // explicitly re-authenticate. Distinct from a generic decryption failure.
                Timber.w(
                    "Bank token key permanently invalidated for bank %s; re-authentication required",
                    connection.bankId
                )
                RefreshOutcome.ReauthRequired
            }
            is BankTokenCipher.DecryptResult.Failed -> {
                Timber.w(
                    "Refresh token is missing/invalid for bank %s; explicit re-auth required",
                    connection.bankId
                )
                RefreshOutcome.Failed
            }
        }
    }
    
    /**
     * Check if sync is needed based on frequency.
     */
    fun shouldSync(connection: BankConnection): Boolean {
        if (!connection.isActive || !connection.isConnected) return false
        if (!connection.autoSync) return false
        
        val lastSync = connection.lastSync ?: return true
        val now = timeProvider.now()
        
        return when (connection.syncFrequency) {
            SyncFrequency.HOURLY -> now - lastSync > (60 * 60 * 1000L)
            SyncFrequency.DAILY -> now - lastSync > (24 * 60 * 60 * 1000L)
            SyncFrequency.WEEKLY -> now - lastSync > (7 * 24 * 60 * 60 * 1000L)
            SyncFrequency.MANUAL -> false
        }
    }
    
    /**
     * Map bank transaction to a [CreateExpenseRequest], which is then passed
     * through [TransactionLifecycleCoordinator.createExpense] for full lifecycle
     * handling (validate → normalize → dedupe → insert atomic → event).
     *
     * RP-17 17-C: transfers require provider-supplied [BankTransaction.transferDirection]
     * and a privacy-safe provider-supplied [BankTransaction.transferAccountRef];
     * transactions missing that metadata are skipped with TRANSFER_METADATA_MISSING
     * by [evaluateBankTransactionContract] before this mapping. The raw transaction
     * description is NEVER used as the transfer account name, and no REDACTED
     * placeholder is fabricated (the validator stays strict).
     *
     * @param rawModeOverride optional pre-resolved raw-storage mode (the sync loop
     *   resolves it once per sync); defaults to resolving from settings.
     */
    @VisibleForTesting
    internal suspend fun mapTransactionToExpense(
        transaction: BankTransaction,
        connection: BankConnection,
        syncRunId: Long,
        rawModeOverride: RawStorageMode? = null
    ): CreateExpenseRequest {
        val transactionType = transaction.movementType?.toTransactionType() ?: inferTransactionType(transaction)

        // PR5: Redact/hash sensitive bank fields based on privacy policy
        val mode = rawModeOverride
            ?: privacySettingsRepository.getSettings().rawBankStatementStorageMode  // PR5-FIX: use dedicated bank statement mode

        val safeDescription: String? = RawContentSanitizer.sanitizeBankDescription(transaction.description, mode)
        val safeReference: String? = RawContentSanitizer.sanitizeBankDescription(transaction.reference, mode)
        // RP-17 17-C: transfer account name comes ONLY from the provider's
        // privacy-safe account reference (masked by contract). DO_NOT_STORE never
        // persists it; other modes store the already-masked reference as-is.
        val safeTransferAccountName: String? =
            if (transactionType == TransactionType.TRANSFER) {
                resolveTransferAccountRef(transaction.transferAccountRef, mode)
            } else {
                null
            }
        val notes = buildString {
            if (safeDescription != null) append(safeDescription)
            if (safeReference != null) append(" (Ref: $safeReference)")
        }.takeIf { it.isNotBlank() }

        // P0: Compute hashed identity fields for deterministic bank provenance
        val providerTxHash = hashingService.hmacSha256Prefix(transaction.id, "providerTransactionId")
        val accountHash = hashingService.hmacSha256Prefix(connection.id.toString(), "bankAccountId")

        return CreateExpenseRequest(
            merchant = transaction.merchant,
            amount = kotlin.math.abs(transaction.amount),
            currency = transaction.currency,
            date = transaction.date,
            transactionType = transactionType,
            source = ExpenseSource.BANK_API_SYNC,
            categoryId = connection.defaultCategoryId,
            transferDirection = transaction.transferDirection.takeIf { transactionType == TransactionType.TRANSFER },
            transferAccountName = safeTransferAccountName,
            idempotencyKey = providerTxHash ?: transaction.id,
            notes = notes,
            bankSyncRunId = syncRunId,
            bankConnectionId = connection.id.takeIf { it > 0L },
            accountId = connection.bankId,
            bankProviderTransactionIdHash = providerTxHash,
            bankAccountIdHash = accountHash,
            // P10-CURRENT-006: bank API imports must dedupe on the provider transaction
            // identity, not the fuzzy STANDARD merchant/amount/date window. This persists a
            // canonical "idem:BANK_API_SYNC:<providerTxHash>" dedupeKey so a re-sync of the
            // same provider transaction resolves to the existing expense even if the
            // merchant/description/amount text changes outside the standard window/tolerance.
            // idempotencyKey is always set above (providerTxHash ?: transaction.id), so the
            // STRICT_EXTERNAL_ID "missing key" validation branch is never hit.
            deduplicationMode = DeduplicationMode.STRICT_EXTERNAL_ID
        )
    }

    /**
     * RP-17 17-C: inference is a last-resort fallback when the provider supplies
     * NO typed movement semantics. Direction is never inferred (from amount sign
     * or description), and refund-like text never manufactures a DEPOSIT —
     * refund-like movement without typed provider semantics is skipped upstream
     * with REFUND_UNSUPPORTED.
     */
    private fun inferTransactionType(transaction: BankTransaction): TransactionType {
        val normalized = transaction.description.lowercase(Locale.ROOT)

        return when {
            normalized.contains("transfer") || normalized.contains("sent to") || normalized.contains("received from") -> TransactionType.TRANSFER
            normalized.contains("withdraw") || normalized.contains("atm") || normalized.contains("cash withdrawal") -> TransactionType.WITHDRAWAL
            transaction.amount > 0 -> TransactionType.DEPOSIT
            transaction.amount < 0 -> TransactionType.PURCHASE
            else -> TransactionType.UNKNOWN
        }
    }

    /**
     * RP-17 17-C: pre-lifecycle contract evaluation. Returns the typed skip code
     * when the transaction must not enter the lifecycle or the review queue, or
     * null when the contract is satisfied. Ordering: amount validity first, then
     * refund semantics, then transfer metadata.
     *
     * Contract rules:
     *  - Zero/NaN amounts skip with INVALID_AMOUNT before any mapping.
     *  - Refund/reversal/cashback REQUIRES typed provider semantics
     *    ([BankMovementType.REFUND]/[BankMovementType.REVERSAL]/[BankMovementType.CASHBACK]);
     *    refund-like description text without typed semantics skips with
     *    REFUND_UNSUPPORTED (description is never trusted for refunds).
     *  - Transfers require provider-supplied [BankTransaction.transferDirection]
     *    AND a privacy-safe [BankTransaction.transferAccountRef] that the active
     *    raw-storage mode may persist; otherwise TRANSFER_METADATA_MISSING.
     */
    @VisibleForTesting
    internal fun evaluateBankTransactionContract(
        transaction: BankTransaction,
        mode: RawStorageMode
    ): DiagnosticReasonCode? {
        // 1. Zero amounts are never meaningful imports.
        if (transaction.amount == 0.0 || transaction.amount.isNaN()) {
            return DiagnosticReasonCode.INVALID_AMOUNT
        }

        // 2. Refund-like semantics require typed provider movement, never text.
        val refundLikeText = REFUND_DESCRIPTION_WORDS.any { transaction.description.lowercase(Locale.ROOT).contains(it) }
        val typedRefund = transaction.movementType in REFUND_LIKE_MOVEMENT_TYPES
        if (refundLikeText && !typedRefund) {
            return DiagnosticReasonCode.REFUND_UNSUPPORTED
        }

        // 3. Transfers need provider-supplied direction + persistable account ref.
        val resolvedType = transaction.movementType?.toTransactionType()
            ?: inferTransactionType(transaction)
        if (resolvedType == TransactionType.TRANSFER) {
            // The validator is strict: a TRANSFER without an explicit direction or
            // account name fails. Both must be provider-supplied before lifecycle.
            val accountRefPersistable = resolveTransferAccountRef(transaction.transferAccountRef, mode) != null
            if (transaction.transferDirection == null || !accountRefPersistable) {
                return DiagnosticReasonCode.TRANSFER_METADATA_MISSING
            }
        }
        return null
    }

    /**
     * RP-17 17-C: the transfer account name is only ever the provider's
     * privacy-safe (masked-by-contract) account reference. DO_NOT_STORE never
     * persists it. No REDACTED placeholder is fabricated in its place.
     */
    private fun resolveTransferAccountRef(ref: String?, mode: RawStorageMode): String? =
        when (mode) {
            RawStorageMode.DO_NOT_STORE -> null
            RawStorageMode.STORE_METADATA_ONLY,
            RawStorageMode.STORE_REDACTED,
            RawStorageMode.STORE_RAW -> ref?.takeIf { it.isNotBlank() }
        }

    /**
     * RP-17 17-D: builds the bank PendingReview with the stable cross-run
     * identity and full raw-persistence policy applied.
     *
     * Identity (register D12, cross-run source-fingerprint contract):
     * `bankReviewIdentity = HMAC("<bankId>|<connectionId>|<providerTransactionId>")`.
     * Same provider transaction re-synced in a later run yields the SAME identity,
     * so the unique index turns a duplicate review creation into a typed skip.
     * Scope is the connection/account (bankId namespace + connection row id);
     * two same-bank connections are unrepresentable today because bankId is
     * unique on bank_connections — the scope is documented, not asserted, per plan.
     *
     * Privacy: title/text pass through the bank raw-persistence policy; nothing
     * raw is stored in notificationTitle/notificationText regardless of mode.
     */
    @VisibleForTesting
    internal suspend fun buildBankPendingReview(
        transaction: BankTransaction,
        connection: BankConnection,
        transactionType: TransactionType,
        normalizedMerchant: String,
        mode: RawStorageMode
    ): PendingReview {
        val identityInput = "${connection.bankId}|${connection.id}|${transaction.id}"
        val bankReviewIdentity = hashingService.hmacSha256Prefix(
            identityInput,
            BANK_REVIEW_IDENTITY_PURPOSE
        )
        val scopeHash = hashingService.hmacSha256Prefix(
            connection.id.toString(),
            BANK_ACCOUNT_SCOPE_PURPOSE
        )
        // RP-17 17-D: complete bank RawPersistencePolicy on review payload fields.
        // The description is the only provider free-text candidate; the title was
        // previously raw merchant text and is no longer populated at all.
        val sanitizedText = RawContentSanitizer.sanitizeBankDescription(transaction.description, mode)

        return PendingReview(
            rawNotificationId = null,
            scannedReceiptId = null,
            suggestedAmount = kotlin.math.abs(transaction.amount),
            suggestedCurrency = transaction.currency,
            suggestedMerchant = transaction.merchant,
            suggestedMerchantKey = normalizedMerchant,
            suggestedType = transactionType.name,
            suggestedCategoryId = connection.defaultCategoryId,
            suggestedDate = transaction.date,
            confidence = transaction.confidence,
            matchType = null,
            explanation = "Low-confidence bank transaction from ${connection.bankId}",
            packageName = "bank.sync.${connection.bankId}",
            // RP-17 17-D: no raw bank payload in title/text fields.
            notificationTitle = null,
            notificationText = sanitizedText,
            createdAt = timeProvider.now(),
            bankReviewIdentity = bankReviewIdentity,
            bankConnectionScopeHash = scopeHash
        )
    }

    /**
     * Generate mock transactions for demonstration.
     */
    @VisibleForTesting
    @StubForDemo
    fun generateMockTransactions(bankId: String, since: Long?): List<BankTransaction> {
        requireStubMode()

        val transactions = mutableListOf<BankTransaction>()
        val now = timeProvider.now()
        val startTime = since ?: (now - (7 * 24 * 60 * 60 * 1000L)) // Last 7 days if no since
        
        // P10-PR1 (NEW-P10-004): Seeded random for reproducible test data
        val rng = kotlin.random.Random(bankId.hashCode().toLong() + (since ?: 0L))
        val count = rng.nextInt(5, 11)
        val merchants = listOf("Supermarket", "Gas Station", "Restaurant", "Coffee Shop", "Online Store")
        
        for (i in 0 until count) {
            val date = startTime + ((now - startTime) * i / count)
            val merchant = merchants[rng.nextInt(merchants.size)]
            // P10-P1-04: Assign varied confidence — some below threshold to exercise review route
            val confidence = when (rng.nextInt(10)) {
                in 0..6 -> 0.85f + rng.nextFloat() * 0.15f  // 70% high confidence
                in 7..8 -> 0.50f + rng.nextFloat() * 0.25f  // 20% medium confidence (below threshold)
                else -> 0.10f + rng.nextFloat() * 0.40f     // 10% low confidence
            }.coerceIn(0f, 1f)
            transactions.add(
                BankTransaction(
                    id = "${bankId}_tx_${i}_${date}",
                    date = date,
                    amount = -(rng.nextInt(10, 201)).toDouble(),
                    currency = "EUR",
                    merchant = merchant,
                    description = "Purchase from $merchant",
                    reference = "REF${rng.nextInt(1000, 10000)}",
                    movementType = BankMovementType.PURCHASE,
                    confidence = confidence
                )
            )
        }
        
        return transactions
    }

    private fun requireStubMode() {
        if (!BuildConfig.DEBUG) {
            error("Bank integration is demo-only and disabled in release builds")
        }
        require(BankApiConfig.isStubMode) { "Bank integration not implemented — set BankApiConfig.isStubMode = true for demo" }
    }
}

data class BankInfo(
    val id: String,
    val name: String,
    val countryCode: String,
    val apiType: String
)
