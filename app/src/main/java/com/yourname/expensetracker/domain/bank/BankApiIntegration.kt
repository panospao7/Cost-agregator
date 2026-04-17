package com.yourname.expensetracker.domain.bank

import com.yourname.expensetracker.data.database.entity.BankConnection
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.SyncFrequency
import com.yourname.expensetracker.data.database.entity.SyncStatus
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.TransferDirection
import com.yourname.expensetracker.data.security.BankTokenCipher
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.text.SimpleDateFormat
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

@Singleton
class BankApiIntegration @Inject constructor(
    private val timeProvider: TimeProvider
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
    suspend fun initiateConnection(bankId: String): String? = withContext(Dispatchers.IO) {
        // In real implementation, this would:
        // 1. Generate OAuth state parameter
        // 2. Build authorization URL
        // 3. Return URL for WebView/browser
        
        val bank = SUPPORTED_BANKS.find { it.id == bankId }
        if (bank == null) {
            Timber.e("Bank not supported: $bankId")
            return@withContext null
        }
        
        // Placeholder return - would be actual OAuth URL
        "https://oauth.${bank.id}.example.com/auth?client_id=demo&response_type=code"
    }
    
    /**
     * Complete connection after OAuth callback (placeholder).
     */
    suspend fun completeConnection(
        bankId: String,
        authCode: String
    ): BankConnection? = withContext(Dispatchers.IO) {
        // In real implementation, this would:
        // 1. Exchange auth code for access token
        // 2. Get refresh token
        // 3. Fetch account information
        // 4. Create BankConnection entity
        
        val bank = SUPPORTED_BANKS.find { it.id == bankId } ?: return@withContext null
        
        BankConnection(
            bankId = bankId,
            bankName = bank.name,
            countryCode = bank.countryCode,
            isConnected = true,
            isActive = true,
            accessToken = BankTokenCipher.encryptIfNeeded("demo_token_$bankId"), // Would be real token
            refreshToken = BankTokenCipher.encryptIfNeeded("demo_refresh_$bankId"),
            tokenEncryptionVersion = 1,
            tokenExpiry = timeProvider.now() + (30 * 24 * 60 * 60 * 1000L) // 30 days
        )
    }
    
    /**
     * Sync transactions from bank (placeholder).
     */
    suspend fun syncTransactions(
        connection: BankConnection,
        since: Long? = null
    ): SyncResult = withContext(Dispatchers.IO) {
        // In real implementation, this would:
        // 1. Check token validity and refresh if needed
        // 2. Call bank API to fetch transactions
        // 3. Map API response to BankTransaction objects
        // 4. Convert to Expense entities
        // 5. Handle duplicates
        
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
            
            val imported = mutableListOf<Expense>()
            val errors = mutableListOf<String>()
            
            for (transaction in mockTransactions) {
                try {
                    val expense = mapTransactionToExpense(transaction, connection)
                    imported.add(expense)
                } catch (e: Exception) {
                    errors.add("Failed to import transaction ${transaction.id}: ${e.message}")
                    Timber.e(e, "Failed to import transaction")
                }
            }
            
            SyncResult(
                success = errors.isEmpty(),
                importedCount = imported.size,
                skippedCount = 0,
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
    private suspend fun refreshToken(connection: BankConnection): Boolean {
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
     * Map bank transaction to expense entity.
     */
    private fun mapTransactionToExpense(
        transaction: BankTransaction,
        connection: BankConnection
    ): Expense {
        val transactionType = transaction.movementType?.toTransactionType() ?: inferTransactionType(transaction)

        return Expense(
            amount = transaction.amount,
            currency = transaction.currency,
            merchant = transaction.merchant,
            transactionType = transactionType,
            date = transaction.date,
            categoryId = connection.defaultCategoryId,
            transferDirection = transaction.transferDirection.takeIf { transactionType == TransactionType.TRANSFER },
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
            else -> TransactionType.UNKNOWN
        }
    }
    
    /**
     * Generate mock transactions for demonstration.
     */
    private fun generateMockTransactions(bankId: String, since: Long?): List<BankTransaction> {
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
                    currency = "EUR",
                    merchant = merchant,
                    description = "Purchase from $merchant",
                    reference = "REF${(1000..9999).random()}",
                    movementType = BankMovementType.PURCHASE
                )
            )
        }
        
        return transactions
    }
}

data class BankInfo(
    val id: String,
    val name: String,
    val countryCode: String,
    val apiType: String
)
