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
     * Create a .costbackup bundle (encrypted ZIP with manifest, checksums,
     * database snapshot, and receipt images).
     * @param password The user-provided encryption password
     * @param includeReceiptImages Whether to include receipt image assets (default: true)
     * @param redacted Whether to sanitize sensitive data (default: true)
     * @return Result containing the .costbackup File
     */
    suspend fun createCostBackup(
        password: String,
        includeReceiptImages: Boolean = true,
        redacted: Boolean = true
    ): Result<File>

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
     *
     * ## BAK-10: Reset database has no typed confirmation
     * Callers MUST obtain explicit typed confirmation from the user (e.g., the
     * user must type "DELETE") before invoking this method. This is a destructive
     * operation that permanently removes ALL data. Implementations MUST create a
     * safety backup before performing the reset.
     *
     * Required user confirmation: The user must type the exact word "DELETE"
     * (case-insensitive) in a confirmation dialog before this method is called.
     * ViewModels should validate the confirmation string matches "DELETE"
     * before delegating to this method.
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
    val allTableCounts: Map<String, Int> = emptyMap(),
    val receiptAssetWarnings: List<String> = emptyList()
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
