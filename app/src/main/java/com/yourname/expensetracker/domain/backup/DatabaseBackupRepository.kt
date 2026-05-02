package com.yourname.expensetracker.domain.backup

import java.io.File

/**
 * Repository interface for database backup and restore operations.
 */
interface DatabaseBackupRepository {
    /**
     * Export the current database to an app-private file.
     * @return Result containing the exported file path or error message
     */
    suspend fun exportDatabase(): Result<File>

    /**
     * Returns a user-facing notice when legacy plaintext backups are detected
     * in public Downloads from older app versions.
     */
    suspend fun getLegacyPublicBackupNotice(): String?
    
    /**
     * Import database from a file.
     * Automatically creates a backup of current database before importing.
     * @param sourceFile The database file to import
     * @return Result containing import summary with row counts or failure
     */
    suspend fun importDatabase(sourceFile: File): Result<DatabaseImportSummary>
    
    /**
     * Restore a .costbackup bundle (encrypted ZIP with manifest + assets).
     * @param bundleFile The .costbackup file to restore
     * @param password The user-provided encryption password
     * @return Result containing the import result (may be SuccessNeedsRestart)
     */
    suspend fun restoreCostBackup(bundleFile: File, password: String): Result<DatabaseImportResult>
    
    /**
     * Get the current database statistics (transaction count, etc.)
     */
    suspend fun getDatabaseStats(): DatabaseStats
    
    /**
     * Create a safety backup before import operations.
     */
    suspend fun createSafetyBackup(): Result<File>
    
    /**
     * Reset database to empty state.
     */
    suspend fun resetDatabase(): Result<Unit>
}

data class DatabaseStats(
    val transactionCount: Int,
    val categoryCount: Int,
    val merchantCount: Int,
    val pendingReviewCount: Int,
    val lastBackupDate: Long? = null
)

data class DatabaseImportSummary(
    val transactionCount: Int,
    val categoryCount: Int,
    val merchantCount: Int,
    val pendingReviewCount: Int,
    val budgetCount: Int,
    val receiptCount: Int = 0,
    val warrantyCount: Int = 0,
    val groupCount: Int = 0,
    val subscriptionCount: Int = 0,
    val savingsGoalCount: Int = 0,
    val allTableCounts: Map<String, Int> = emptyMap()
) {
    fun hasMeaningfulData(): Boolean {
        return transactionCount > 0 ||
            categoryCount > 0 ||
            merchantCount > 0 ||
            pendingReviewCount > 0 ||
            budgetCount > 0 ||
            receiptCount > 0 ||
            warrantyCount > 0 ||
            groupCount > 0 ||
            subscriptionCount > 0 ||
            savingsGoalCount > 0
    }
}
