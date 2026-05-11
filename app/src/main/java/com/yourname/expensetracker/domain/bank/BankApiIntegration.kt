package com.yourname.expensetracker.domain.bank

import androidx.annotation.VisibleForTesting
import com.yourname.expensetracker.BuildConfig
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.entity.BankConnection
import com.yourname.expensetracker.data.database.entity.SyncFrequency
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.TransferDirection
import com.yourname.expensetracker.data.security.BankTokenCipher
import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.transaction.CreateExpenseResult
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.util.TimeProvider
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
    private val writeBarrier: DatabaseWriteBarrier
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
        writeBarrier.checkWritesAllowed("BankApiIntegration.syncTransactions")

        // In real implementation, this would:
        // 1. Check token validity and refresh if needed
        // 2. Call bank API to fetch transactions
        // 3. Map API response to BankTransaction objects
        // 4. Convert to Expense entities
        // 5. Handle duplicates
        // TODO: BankTransactionClassifier — Route low-confidence transactions
        // to PendingReview instead of auto-creating expenses. A classifier
        // should evaluate merchant name match quality, amount plausibility,
        // and description coherence before auto-import.
        
        try {
            // Check if token is expired
            if (connection.tokenExpiry != null && connection.tokenExpiry < timeProvider.now()) {
                val refreshed = refreshToken(connection)
                if (!refreshed) {
                    return@withContext SyncResult(
                        success = false,
                        importedCount = 0,
                        skippedCount = 0,
                        errorCount = 1,
                        errors = listOf("Token expired and refresh failed")
                    )
                }
            }
            
            // Placeholder: Simulate fetching transactions
            val mockTransactions = generateMockTransactions(connection.bankId, since)
            
            var importedCount = 0
            var skippedCount = 0
            val errors = mutableListOf<String>()
            
            for (transaction in mockTransactions) {
                try {
                    val request = mapTransactionToExpense(transaction, connection)
                    @Suppress("DEPRECATION_ERROR") // TODO: migrate to createExpenseStandalone()
                    when (val result = coordinator.createExpense(request)) {
                        is CreateExpenseResult.Created -> importedCount++
                        is CreateExpenseResult.DuplicateSkipped -> skippedCount++
                        is CreateExpenseResult.ValidationFailed ->
                            errors.add("Validation failed for transaction ${transaction.id}: ${result.errors.joinToString(", ")}")
                        is CreateExpenseResult.InsertConflict ->
                            errors.add("Insert conflict for transaction ${transaction.id}: dedupeKey=${result.dedupeKey}")
                        is CreateExpenseResult.Error ->
                            errors.add("Failed to import transaction ${transaction.id}: ${result.exception.message}")
                    }
                } catch (e: Exception) {
                    errors.add("Failed to import transaction ${transaction.id}: ${e.message}")
                    Timber.e(e, "Failed to import transaction")
                }
            }
            
            SyncResult(
                success = errors.isEmpty(),
                importedCount = importedCount,
                skippedCount = skippedCount,
                errorCount = errors.size,
                errors = errors
            )
            
        } catch (e: Exception) {
            Timber.e(e, "Sync failed for bank ${connection.bankId}")
            SyncResult(
                success = false,
                importedCount = 0,
                skippedCount = 0,
                errorCount = 1,
                errors = listOf("Sync failed: ${e.message}")
            )
        }
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
    private fun mapTransactionToExpense(
        transaction: BankTransaction,
        connection: BankConnection
    ): CreateExpenseRequest {
        val transactionType = transaction.movementType?.toTransactionType() ?: inferTransactionType(transaction)

        return CreateExpenseRequest(
            merchant = transaction.merchant,
            amount = kotlin.math.abs(transaction.amount),
            currency = transaction.currency,
            date = transaction.date,
            transactionType = transactionType,
            source = ExpenseSource.BANK_API_SYNC,
            categoryId = connection.defaultCategoryId,
            transferDirection = transaction.transferDirection.takeIf { transactionType == TransactionType.TRANSFER },
            // TODO (P0-4): BankTransaction needs transferAccountName field.
            // Once the bank provider returns account names for transfers, pass:
            //   transferAccountName = transaction.transferAccountName
            transferAccountName = transaction.description.takeIf { it.isNotBlank() }, // fallback: description often contains account name for transfers
            // P0-5: Use bank transaction external ID for dedup on re-sync
            idempotencyKey = transaction.id,
            notes = transaction.description + (transaction.reference?.let { " (Ref: $it)" } ?: "")
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
