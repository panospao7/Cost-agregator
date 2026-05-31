package com.yourname.expensetracker.domain.bank

import androidx.annotation.VisibleForTesting
import com.yourname.expensetracker.BuildConfig
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.entity.BankConnection
import com.yourname.expensetracker.data.database.entity.SyncFrequency
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.TransferDirection
import com.yourname.expensetracker.data.security.BankTokenCipher
import com.yourname.expensetracker.domain.common.sha256Prefix
import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.transaction.CreateExpenseResult
import com.yourname.expensetracker.domain.transaction.DeduplicationMode
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.privacy.RawStorageMode
import com.yourname.expensetracker.domain.privacy.SensitiveHashingService
import kotlinx.coroutines.Dispatchers
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
    val transferDirection: TransferDirection? = null
)

enum class BankMovementType {
    PURCHASE,
    WITHDRAWAL,
    TRANSFER,
    DEPOSIT
}

private fun BankMovementType.toTransactionType(): TransactionType = when (this) {
    BankMovementType.PURCHASE -> TransactionType.PURCHASE
    BankMovementType.WITHDRAWAL -> TransactionType.WITHDRAWAL
    BankMovementType.TRANSFER -> TransactionType.TRANSFER
    BankMovementType.DEPOSIT -> TransactionType.DEPOSIT
}

data class SyncResult(
    val success: Boolean,
    val importedCount: Int,
    val skippedCount: Int,
    val errorCount: Int,
    val errors: List<String>
)

// TODO (P2-1): Demo bank sync is intentionally non-deterministic.
// When real bank providers are added, ensure sync is idempotent and repeatable.

@Singleton
class BankApiIntegration @Inject constructor(
    private val timeProvider: TimeProvider,
    private val coordinator: TransactionLifecycleCoordinator,
    private val writeBarrier: DatabaseWriteBarrier,
    private val operationRunRecorder: com.yourname.expensetracker.domain.diagnostics.OperationRunRecorder,
    private val hashingService: SensitiveHashingService,
    private val privacySettingsRepository: PrivacySettingsRepository
) {
    
    companion object {
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
        
        BankConnection(
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
    }
    
    @StubForDemo
    suspend fun syncTransactions(
        connection: BankConnection,
        since: Long? = null
    ): SyncResult = withContext(Dispatchers.IO) {
        requireStubMode()
        // DDL-016-14: operation run must start BEFORE barrier check so blocked sync has a durable record

        var syncResult = SyncResult(success = false, importedCount = 0, skippedCount = 0, errorCount = 0, errors = emptyList())
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
                return@runOperation
            }

            // I3: Token refresh with finalization
            if (connection.tokenExpiry != null && connection.tokenExpiry < timeProvider.now()) {
                run.event("TOKEN_REFRESH_STARTED", com.yourname.expensetracker.domain.diagnostics.EventOutcome.ATTEMPTED)
                val refreshed = refreshToken(connection)
                if (!refreshed) {
                    run.event("TOKEN_REFRESH_FAILED",
                        com.yourname.expensetracker.domain.diagnostics.EventOutcome.FAILED_FINAL,
                        reasonCode = com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.TOKEN_INVALID)
                    run.failedFinal("Token expired and refresh failed")
                    syncResult = SyncResult(success = false, importedCount = 0, skippedCount = 0, errorCount = 1,
                        errors = listOf("Token expired and refresh failed"))
                    return@runOperation
                }
                run.event("TOKEN_REFRESHED", com.yourname.expensetracker.domain.diagnostics.EventOutcome.COMPLETED)
            }

            val mockTransactions = generateMockTransactions(connection.bankId, since)
            run.event("PAGE_FETCHED", com.yourname.expensetracker.domain.diagnostics.EventOutcome.COMPLETED,
                metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                    .put("itemCount", mockTransactions.size).build())

            var importedCount = 0
            var skippedCount = 0
            val errors = mutableListOf<String>()

            val syncRunId = run.runId  // PR6: capture sync run ID for provenance linking
            for (transaction in mockTransactions) {
                try {
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

            syncResult = SyncResult(
                success = errors.isEmpty(),
                importedCount = importedCount,
                skippedCount = skippedCount,
                errorCount = errors.size,
                errors = errors
            )
            if (errors.isNotEmpty()) run.partialSuccess("${errors.size} errors")
            // success() called automatically by runOperation if still RUNNING
        }
        syncResult
    }
    
    /**
     * Refresh access token (placeholder).
     */
    @StubForDemo
    private suspend fun refreshToken(connection: BankConnection): Boolean {
        requireStubMode()

        // In real implementation, this would use refresh_token to get new access_token
        val decryptedRefresh = BankTokenCipher.decryptIfNeeded(connection.refreshToken)
        if (decryptedRefresh == null) {
            Timber.w(
                "Refresh token is missing/invalid for bank %s; explicit re-auth required",
                connection.bankId
            )
            return false
        }

        return true
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
     */
    @VisibleForTesting
    internal suspend fun mapTransactionToExpense(
        transaction: BankTransaction,
        connection: BankConnection,
        syncRunId: Long
    ): CreateExpenseRequest {
        val transactionType = transaction.movementType?.toTransactionType() ?: inferTransactionType(transaction)

        // PR5: Redact/hash sensitive bank fields based on privacy policy
        val settings = privacySettingsRepository.getSettings()
        val mode = settings.rawOcrStorageMode  // bank API uses OCR storage mode

        val safeDescription: String? = when (mode) {
            RawStorageMode.STORE_RAW -> transaction.description
            RawStorageMode.STORE_REDACTED -> "[REDACTED]"
            else -> null
        }
        val safeReference: String? = when (mode) {
            RawStorageMode.STORE_RAW -> transaction.reference
            RawStorageMode.STORE_REDACTED -> if (transaction.reference != null) "[REDACTED]" else null
            else -> null
        }
        // transferAccountName must never be raw description unless STORE_RAW
        val safeTransferAccountName: String? = when {
            transactionType == TransactionType.TRANSFER && mode == RawStorageMode.STORE_RAW ->
                transaction.description.takeIf { it.isNotBlank() }
            else -> null
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

    private fun inferTransactionType(transaction: BankTransaction): TransactionType {
        val normalized = transaction.description.lowercase(Locale.ROOT)

        return when {
            transaction.transferDirection != null -> TransactionType.TRANSFER
            normalized.contains("refund") || normalized.contains("reversal") || normalized.contains("cashback") -> TransactionType.DEPOSIT
            normalized.contains("transfer") || normalized.contains("sent to") || normalized.contains("received from") -> TransactionType.TRANSFER
            normalized.contains("withdraw") || normalized.contains("atm") || normalized.contains("cash withdrawal") -> TransactionType.WITHDRAWAL
            transaction.amount > 0 -> TransactionType.DEPOSIT
            transaction.amount < 0 -> TransactionType.PURCHASE
            transaction.amount == 0.0 -> TransactionType.UNKNOWN
            else -> TransactionType.PURCHASE
        }
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
        
        // Generate 5-10 mock transactions
        val count = (5..10).random()
        val merchants = listOf("Supermarket", "Gas Station", "Restaurant", "Coffee Shop", "Online Store")
        
        for (i in 0 until count) {
            val date = startTime + ((now - startTime) * i / count)
            val merchant = merchants.random()
            transactions.add(
                BankTransaction(
                    id = "${bankId}_tx_${i}_${date}",
                    date = date,
                    amount = -(10..200).random().toDouble(),
                    currency = "EUR", // STUB: currency defaults to EUR; real implementation should use home currency from settings
                    merchant = merchant,
                    description = "Purchase from $merchant",
                    reference = "REF${(1000..9999).random()}",
                    movementType = BankMovementType.PURCHASE
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
