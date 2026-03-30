package com.yourname.expensetracker.data.repository

import android.content.Context
import android.os.Environment
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.domain.backup.DatabaseBackupRepository
import com.yourname.expensetracker.domain.backup.DatabaseImportSummary
import com.yourname.expensetracker.domain.backup.DatabaseStats
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DatabaseBackupRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase
) : DatabaseBackupRepository {
    
    companion object {
        private const val DATABASE_NAME = "expense_tracker_db"
        private const val BACKUP_PREFIX = "expense_tracker_backup_"
        private const val DATE_FORMAT = "yyyy-MM-dd_HH-mm-ss"
    }
    
    override suspend fun exportDatabase(): Result<File> = withContext(Dispatchers.IO) {
        try {
            val dbFile = context.getDatabasePath(DATABASE_NAME)
            if (!dbFile.exists()) {
                return@withContext Result.failure(Exception("Database file not found"))
            }
            
            // First, checkpoint WAL to ensure all data is in the main database file
            // This makes the backup self-contained and eliminates need for companion files
            // Note: Do NOT use runInTransaction for checkpoint - it causes SQLITE_LOCKED
            // PRAGMA wal_checkpoint returns a result, so we must use rawQuery not execSQL
            var checkpointSuccess = false
            var attempts = 0
            val maxAttempts = 3
            while (!checkpointSuccess && attempts < maxAttempts) {
                try {
                    val cursor = database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)")
                    var busy = 1
                    if (cursor.moveToFirst()) {
                        busy = cursor.getInt(0) // First column is busy (0=success, 1=blocked)
                    }
                    cursor.close()
                    
                    if (busy == 0) {
                        checkpointSuccess = true
                        Timber.d("WAL checkpoint completed before export (attempt ${attempts + 1})")
                    } else {
                        attempts++
                        if (attempts >= maxAttempts) {
                            Timber.e("Checkpoint busy after $maxAttempts attempts, failing export")
                            return@withContext Result.failure(Exception("Database is busy (WAL checkpoint blocked). Please try again."))
                        }
                        Timber.w("Checkpoint busy (code $busy), retrying in 200ms (attempt $attempts)")
                        kotlinx.coroutines.delay(200)
                    }
                } catch (e: android.database.sqlite.SQLiteDatabaseLockedException) {
                    attempts++
                    if (attempts >= maxAttempts) {
                        Timber.e("Checkpoint locked after $maxAttempts attempts, failing export")
                        return@withContext Result.failure(Exception("Database is locked. Please try again."))
                    }
                    Timber.w("Checkpoint locked, retrying in 200ms (attempt $attempts)")
                    kotlinx.coroutines.delay(200)
                }
            }
            
            // Create timestamped filename
            val timestamp = SimpleDateFormat(DATE_FORMAT, Locale.getDefault()).format(Date())
            val backupFileName = "${BACKUP_PREFIX}${timestamp}.db"
            
            // Save to Downloads folder
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val backupFile = File(downloadsDir, backupFileName)
            
            // Copy database file (now self-contained after checkpoint)
            dbFile.inputStream().use { input ->
                backupFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            
            Timber.d("Database exported successfully to: ${backupFile.absolutePath}")
            Result.success(backupFile)
        } catch (e: Exception) {
            Timber.e(e, "Failed to export database")
            Result.failure(e)
        }
    }
    
    override suspend fun importDatabase(sourceFile: File): Result<DatabaseImportSummary> = withContext(Dispatchers.IO) {
        try {
            // Validate source file exists and is readable
            if (!sourceFile.exists()) {
                return@withContext Result.failure(Exception("Source database file not found: ${sourceFile.absolutePath}"))
            }
            if (!sourceFile.canRead()) {
                return@withContext Result.failure(Exception("Cannot read source database file. Check file permissions."))
            }
            if (sourceFile.length() == 0L) {
                return@withContext Result.failure(Exception("Source database file is empty."))
            }
            
            // Validate source database before touching anything
            val sourceValidation = validateSourceDatabase(sourceFile)
            if (sourceValidation.isFailure) {
                Timber.e("Source database validation failed: ${sourceValidation.exceptionOrNull()?.message}")
                return@withContext Result.failure(
                    Exception("Invalid backup file: ${sourceValidation.exceptionOrNull()?.message}")
                )
            }
            val sourceSummary = sourceValidation.getOrNull()!!
            
            // Block empty imports - source must have at least 1 transaction or 1 category
            if (sourceSummary.transactionCount == 0 && sourceSummary.categoryCount == 0) {
                Timber.e("Source database is empty (0 transactions, 0 categories). Blocking import.")
                return@withContext Result.failure(
                    Exception("Backup file contains no data. Import blocked to prevent data loss.")
                )
            }
            
            Timber.d("Source validated: ${sourceSummary.transactionCount} transactions, ${sourceSummary.categoryCount} categories, schema v${sourceSummary.schemaVersion}")
            
            // First, create safety backup
            val safetyBackupResult = createSafetyBackup()
            if (safetyBackupResult.isFailure) {
                Timber.w("Failed to create safety backup, but proceeding with import")
            }
            
            // Close database connections
            database.close()
            
            val dbFile = context.getDatabasePath(DATABASE_NAME)
            val dbWalFile = File(dbFile.parent, "$DATABASE_NAME-wal")
            val dbShmFile = File(dbFile.parent, "$DATABASE_NAME-shm")
            
            // Atomic replacement: copy to temp, then rename
            val tempFile = File(dbFile.parent, "${DATABASE_NAME}_temp")
            try {
                // Copy source to temp file
                sourceFile.inputStream().use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                
                // Delete old database files
                dbFile.delete()
                dbWalFile.delete()
                dbShmFile.delete()
                
                // Atomic rename: temp -> main database
                if (!tempFile.renameTo(dbFile)) {
                    throw Exception("Failed to move imported database into place")
                }
                
                Timber.d("Database imported successfully from: ${sourceFile.absolutePath}")
                
                // Verify import by reading directly from SQLite
                val destSummary = verifyImportedDatabaseDirect()
                
                // Compare source vs destination
                if (sourceSummary.transactionCount > 0 && destSummary.transactionCount == 0) {
                    Timber.e("Data loss detected! Source had ${sourceSummary.transactionCount} transactions but destination has 0")
                    return@withContext Result.failure(
                        Exception("Data integrity check failed: Expected ${sourceSummary.transactionCount} transactions but got ${destSummary.transactionCount}")
                    )
                }
                
                Timber.d("Import verified: ${destSummary.transactionCount} transactions, ${destSummary.categoryCount} categories")
                Result.success(destSummary)
                
            } finally {
                // Cleanup temp file if still exists
                if (tempFile.exists()) {
                    tempFile.delete()
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to import database")
            Result.failure(e)
        }
    }
    
    /**
     * Validates source database by reading directly via SQLite (not Room).
     * Checks:
     * - User version is compatible
     * - Key tables exist
     * - Has at least minimum required data
     */
    private data class SourceValidationSummary(
        val transactionCount: Int,
        val categoryCount: Int,
        val merchantCount: Int,
        val pendingReviewCount: Int,
        val budgetCount: Int,
        val schemaVersion: Int
    )
    
    private fun validateSourceDatabase(sourceFile: File): Result<SourceValidationSummary> {
        return try {
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                sourceFile.absolutePath,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            )
            
            try {
                // Get schema version
                val versionCursor = db.rawQuery("PRAGMA user_version", null)
                val schemaVersion = if (versionCursor.moveToFirst()) versionCursor.getInt(0) else 0
                versionCursor.close()
                
                // Reject old schemas that would trigger destructive migration
                if (schemaVersion in 1..5) {
                    Timber.e("Source database schema version $schemaVersion is too old (1-5). Destructive migration would wipe data.")
                    return Result.failure(
                        Exception("Backup from old app version (schema $schemaVersion). Migration would destroy data. Please use a newer backup or manually migrate first.")
                    )
                }
                
                // Check key tables exist
                val tablesToCheck = listOf("expenses", "categories", "merchant_categories", "budgets", "pending_reviews")
                for (table in tablesToCheck) {
                    val cursor = db.rawQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                        arrayOf(table)
                    )
                    val exists = cursor.moveToFirst()
                    cursor.close()
                    if (!exists) {
                        Timber.w("Table '$table' missing in source database")
                    }
                }
                
                // Count rows in key tables
                val expenseCount = db.rawQuery("SELECT COUNT(*) FROM expenses", null).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }
                val categoryCount = db.rawQuery("SELECT COUNT(*) FROM categories", null).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }
                val merchantCount = db.rawQuery("SELECT COUNT(*) FROM merchant_categories", null).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }
                val pendingReviewCount = db.rawQuery("SELECT COUNT(*) FROM pending_reviews", null).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }
                val budgetCount = db.rawQuery("SELECT COUNT(*) FROM budgets", null).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }
                
                Result.success(SourceValidationSummary(
                    transactionCount = expenseCount,
                    categoryCount = categoryCount,
                    merchantCount = merchantCount,
                    pendingReviewCount = pendingReviewCount,
                    budgetCount = budgetCount,
                    schemaVersion = schemaVersion
                ))
            } finally {
                db.close()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to validate source database")
            Result.failure(Exception("Could not read source database: ${e.message}"))
        }
    }
    
    /**
     * Verifies imported database by reading directly via SQLite (not Room).
     * More reliable after file replacement than using Room connection.
     */
    private fun verifyImportedDatabaseDirect(): DatabaseImportSummary {
        return try {
            val dbFile = context.getDatabasePath(DATABASE_NAME)
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            )
            
            try {
                val expenseCount = db.rawQuery("SELECT COUNT(*) FROM expenses", null).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }
                val categoryCount = db.rawQuery("SELECT COUNT(*) FROM categories", null).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }
                val merchantCount = db.rawQuery("SELECT COUNT(*) FROM merchant_categories", null).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }
                val pendingReviewCount = db.rawQuery("SELECT COUNT(*) FROM pending_reviews", null).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }
                val budgetCount = db.rawQuery("SELECT COUNT(*) FROM budgets", null).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }
                
                DatabaseImportSummary(
                    transactionCount = expenseCount,
                    categoryCount = categoryCount,
                    merchantCount = merchantCount,
                    pendingReviewCount = pendingReviewCount,
                    budgetCount = budgetCount
                )
            } finally {
                db.close()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to verify imported database directly")
            // Return empty summary - will need restart
            DatabaseImportSummary(
                transactionCount = -1,
                categoryCount = 0,
                merchantCount = 0,
                pendingReviewCount = 0,
                budgetCount = 0
            )
        }
    }
    
    private suspend fun verifyImportedDatabase(): DatabaseImportSummary = withContext(Dispatchers.IO) {
        try {
            // Reopen the database by accessing it (Room will auto-reopen)
            val expenseDao = database.expenseDao()
            val categoryDao = database.categoryDao()
            val merchantDao = database.merchantCategoryDao()
            val pendingReviewDao = database.pendingReviewDao()
            val budgetDao = database.budgetDao()
            
            // Get actual counts from the imported database
            val expenseCount = expenseDao.getAll().size
            val categoryCount = categoryDao.getAll().size
            val merchantCount = merchantDao.getAll().size
            val pendingReviewCount = pendingReviewDao.getPending().size
            val budgetCount = budgetDao.getAll().size
            
            DatabaseImportSummary(
                transactionCount = expenseCount,
                categoryCount = categoryCount,
                merchantCount = merchantCount,
                pendingReviewCount = pendingReviewCount,
                budgetCount = budgetCount
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to verify imported database - database may need app restart to access")
            // Return empty summary if we can't read (will need restart)
            DatabaseImportSummary(
                transactionCount = -1, // -1 indicates needs restart
                categoryCount = 0,
                merchantCount = 0,
                pendingReviewCount = 0,
                budgetCount = 0
            )
        }
    }
    
    override suspend fun getDatabaseStats(): DatabaseStats = withContext(Dispatchers.IO) {
        try {
            val expenseDao = database.expenseDao()
            val categoryDao = database.categoryDao()
            val merchantDao = database.merchantCategoryDao()
            val pendingReviewDao = database.pendingReviewDao()
            
            DatabaseStats(
                transactionCount = expenseDao.getAll().size,
                categoryCount = categoryDao.getAll().size,
                merchantCount = merchantDao.getAll().size,
                pendingReviewCount = pendingReviewDao.getPending().size
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to get database stats")
            DatabaseStats(0, 0, 0, 0)
        }
    }
    
    override suspend fun createSafetyBackup(): Result<File> = withContext(Dispatchers.IO) {
        try {
            val dbFile = context.getDatabasePath(DATABASE_NAME)
            if (!dbFile.exists()) {
                return@withContext Result.failure(Exception("Database file not found"))
            }
            
            // Checkpoint WAL to ensure all data is in the main database file
            // Note: Do NOT use runInTransaction for checkpoint - it causes SQLITE_LOCKED
            // PRAGMA wal_checkpoint returns a result, so we must use rawQuery not execSQL
            var checkpointSuccess = false
            var attempts = 0
            val maxAttempts = 3
            while (!checkpointSuccess && attempts < maxAttempts) {
                try {
                    val cursor = database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)")
                    var busy = 1
                    if (cursor.moveToFirst()) {
                        busy = cursor.getInt(0) // First column is busy (0=success, 1=blocked)
                    }
                    cursor.close()
                    
                    if (busy == 0) {
                        checkpointSuccess = true
                        Timber.d("WAL checkpoint completed before safety backup (attempt ${attempts + 1})")
                    } else {
                        attempts++
                        if (attempts >= maxAttempts) {
                            Timber.e("Checkpoint busy after $maxAttempts attempts, failing safety backup")
                            return@withContext Result.failure(Exception("Database is busy (WAL checkpoint blocked). Please try again."))
                        }
                        Timber.w("Checkpoint busy (code $busy), retrying in 200ms (attempt $attempts)")
                        kotlinx.coroutines.delay(200)
                    }
                } catch (e: android.database.sqlite.SQLiteDatabaseLockedException) {
                    attempts++
                    if (attempts >= maxAttempts) {
                        Timber.e("Checkpoint locked after $maxAttempts attempts, failing safety backup")
                        return@withContext Result.failure(Exception("Database is locked. Please try again."))
                    }
                    Timber.w("Checkpoint locked, retrying in 200ms (attempt $attempts)")
                    kotlinx.coroutines.delay(200)
                }
            }
            
            val timestamp = SimpleDateFormat(DATE_FORMAT, Locale.getDefault()).format(Date())
            val safetyBackupFileName = "${BACKUP_PREFIX}SAFETY_${timestamp}.db"
            
            val appDir = context.filesDir
            val safetyBackupDir = File(appDir, "safety_backups")
            safetyBackupDir.mkdirs()
            
            val safetyBackupFile = File(safetyBackupDir, safetyBackupFileName)
            
            // Copy database file (now self-contained after checkpoint)
            dbFile.inputStream().use { input ->
                safetyBackupFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            
            // Clean up old safety backups (keep only last 3)
            cleanupOldSafetyBackups(safetyBackupDir)
            
            Timber.d("Safety backup created: ${safetyBackupFile.absolutePath}")
            Result.success(safetyBackupFile)
        } catch (e: Exception) {
            Timber.e(e, "Failed to create safety backup")
            Result.failure(e)
        }
    }
    
    override suspend fun resetDatabase(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Create safety backup first
            createSafetyBackup()
            
            // Close database
            database.close()
            
            val dbFile = context.getDatabasePath(DATABASE_NAME)
            val dbWalFile = File(dbFile.parent, "$DATABASE_NAME-wal")
            val dbShmFile = File(dbFile.parent, "$DATABASE_NAME-shm")
            
            // Delete all database files
            dbFile.delete()
            dbWalFile.delete()
            dbShmFile.delete()
            
            Timber.d("Database reset successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to reset database")
            Result.failure(e)
        }
    }
    
    private fun cleanupOldSafetyBackups(backupDir: File) {
        val backups = backupDir.listFiles { file ->
            file.name.startsWith(BACKUP_PREFIX + "SAFETY_") && file.name.endsWith(".db")
        }?.sortedBy { it.lastModified() } ?: return
        
        // Keep only last 3 backups
        if (backups.size > 3) {
            backups.take(backups.size - 3).forEach { it.delete() }
        }
    }
}
