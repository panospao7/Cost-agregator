package com.yourname.expensetracker.data.repository

import android.content.Context
import android.os.Environment
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.di.IoDispatcher
import com.yourname.expensetracker.domain.backup.DatabaseBackupRepository
import com.yourname.expensetracker.domain.backup.DatabaseImportSummary
import com.yourname.expensetracker.domain.backup.DatabaseStats
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
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
    private val database: AppDatabase,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : DatabaseBackupRepository {
    
    companion object {
        private const val DATABASE_NAME = "expense_tracker_db"
        private const val BACKUP_PREFIX = "expense_tracker_backup_"
        private const val DATE_FORMAT = "yyyy-MM-dd_HH-mm-ss"
        private const val EXPORT_SUBDIR = "exports"
        private const val MIN_SUPPORTED_SCHEMA_VERSION = 6
        private const val FALLBACK_CURRENT_SCHEMA_VERSION = 70

        private val CURRENT_SUPPORTED_SCHEMA_VERSION: Int by lazy {
            AppDatabase::class.java
                .getAnnotation(androidx.room.Database::class.java)
                ?.version
                ?: FALLBACK_CURRENT_SCHEMA_VERSION
        }
    }
    
    override suspend fun exportDatabase(): Result<File> = withContext(ioDispatcher) {
        try {
            val dbFile = context.getDatabasePath(DATABASE_NAME)
            if (!dbFile.exists()) {
                return@withContext Result.failure(Exception("Database file not found"))
            }
            
            val checkpointResult = checkpointWal()
            if (checkpointResult.isFailure) {
                return@withContext Result.failure(
                    checkpointResult.exceptionOrNull() ?: Exception("Failed to checkpoint WAL")
                )
            }
            
            // Create timestamped filename
            val timestamp = SimpleDateFormat(DATE_FORMAT, Locale.getDefault()).format(Date())
            val backupFileName = "${BACKUP_PREFIX}${timestamp}.db"
            
            // Save to app-private storage by default
            val exportDir = File(context.filesDir, EXPORT_SUBDIR).apply { mkdirs() }
            val backupFile = File(exportDir, backupFileName)
            
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

    override suspend fun getLegacyPublicBackupNotice(): String? = withContext(ioDispatcher) {
        runCatching {
            val downloads = File(Environment.getExternalStorageDirectory(), Environment.DIRECTORY_DOWNLOADS)
            if (!downloads.exists() || !downloads.isDirectory) {
                return@runCatching null
            }

            val legacyBackups = downloads.listFiles { file ->
                file.isFile &&
                    file.name.startsWith(BACKUP_PREFIX) &&
                    file.name.endsWith(".db")
            }?.toList().orEmpty()

            if (legacyBackups.isEmpty()) return@runCatching null

            "Found ${legacyBackups.size} legacy database backup(s) in public Downloads from older app versions. " +
                "These files may be readable by other apps. Move or delete them after securely re-exporting from the app."
        }.getOrElse { err ->
            Timber.w(err, "Failed to check legacy public backups")
            null
        }
    }
    
    override suspend fun importDatabase(sourceFile: File): Result<DatabaseImportSummary> = withContext(ioDispatcher) {
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
            val sourceSummary = sourceValidation.getOrNull()
                ?: return@withContext Result.failure(Exception("Failed to read backup summary"))
            
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
                val reason = safetyBackupResult.exceptionOrNull()?.message
                    ?: "Unknown backup error"
                Timber.e("Database import aborted: safety backup failed: $reason")
                return@withContext Result.failure(
                    Exception(
                        "Import cancelled because safety backup failed. " +
                            "Please free storage/permissions and retry. Details: $reason"
                    )
                )
            }
            val safetyBackupFile = safetyBackupResult.getOrNull()
                ?: return@withContext Result.failure(Exception("Safety backup was created but path is unavailable"))
            
            // Close database and clear connection pool before replacing files
            runCatching { database.close() }
                .onFailure { Timber.w(it, "Failed to close Room database before import") }
            runCatching { database.openHelper.close() }
                .onFailure { Timber.w(it, "Failed to close Room openHelper before import") }
            
            val dbFile = context.getDatabasePath(DATABASE_NAME)
            val dbWalFile = File(dbFile.parent, "$DATABASE_NAME-wal")
            val dbShmFile = File(dbFile.parent, "$DATABASE_NAME-shm")
            
            // Atomic replacement: copy to temp, then rename
            val tempFile = File(dbFile.parent, "${DATABASE_NAME}_temp")
            var destinationFilesMutated = false
            var importSucceeded = false
            try {
                // Copy source to temp file
                sourceFile.inputStream().use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                
                // Delete old database files
                destinationFilesMutated = true
                dbFile.delete()
                dbWalFile.delete()
                dbShmFile.delete()
                
                // Atomic rename: temp -> main database
                if (!tempFile.renameTo(dbFile)) {
                    throw Exception("Failed to move imported database into place")
                }
                
                Timber.d("Database imported successfully from: ${sourceFile.absolutePath}")

                // Force Room to re-open with a fresh connection after file replacement.
                // This ensures migrations/checks run against the new file and stale handles are dropped.
                database.openHelper.writableDatabase

                // Broadcast table invalidations so active Flow observers refresh immediately.
                refreshInvalidationTrackerSafely()
                
                // Verify import by reading directly from SQLite
                val destSummary = verifyImportedDatabaseDirect()
                
                // Compare source vs destination
                if (sourceSummary.transactionCount > 0 && destSummary.transactionCount == 0) {
                    Timber.e("Data loss detected! Source had ${sourceSummary.transactionCount} transactions but destination has 0")
                    throw Exception(
                        "Data integrity check failed: Expected ${sourceSummary.transactionCount} transactions but got ${destSummary.transactionCount}"
                    )
                }
                
                // Verify Room can also read from the newly imported DB instance.
                val roomSummary = verifyImportedDatabase()

                val finalSummary = if (roomSummary.transactionCount == -1) {
                    Timber.w("Direct verify passed, but Room verification failed. App restart may be required.")
                    roomSummary
                } else {
                    roomSummary
                }

                Timber.d("Import verified: ${finalSummary.transactionCount} transactions, ${finalSummary.categoryCount} categories")
                importSucceeded = true
                Result.success(finalSummary)
            } catch (importError: Exception) {
                if (destinationFilesMutated && !importSucceeded) {
                    val rollbackResult = restoreFromSafetyBackup(
                        safetyBackupFile = safetyBackupFile,
                        dbFile = dbFile,
                        dbWalFile = dbWalFile,
                        dbShmFile = dbShmFile
                    )

                    return@withContext if (rollbackResult.isSuccess) {
                        Result.failure(
                            Exception(
                                "Import failed and database was restored from safety backup. " +
                                    "Reason: ${importError.message}",
                                importError
                            )
                        )
                    } else {
                        val rollbackError = rollbackResult.exceptionOrNull()
                        Result.failure(
                            Exception(
                                "Import failed and automatic restore also failed. " +
                                    "Manual recovery may be required. " +
                                    "Import error: ${importError.message}. " +
                                    "Restore error: ${rollbackError?.message}",
                                importError
                            )
                        )
                    }
                }
                throw importError
                
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
                
                // Reject unsupported schema versions before touching destination files.
                val currentSupportedVersion = CURRENT_SUPPORTED_SCHEMA_VERSION
                if (schemaVersion < MIN_SUPPORTED_SCHEMA_VERSION || schemaVersion > currentSupportedVersion) {
                    Timber.e(
                        "Source database schema version $schemaVersion is unsupported. " +
                            "Supported range: $MIN_SUPPORTED_SCHEMA_VERSION..$currentSupportedVersion"
                    )
                    val message = if (schemaVersion < MIN_SUPPORTED_SCHEMA_VERSION) {
                        "Backup from old app version (schema $schemaVersion). " +
                            "Supported schema range is $MIN_SUPPORTED_SCHEMA_VERSION..$currentSupportedVersion. " +
                            "Migration would destroy data. Please use a newer backup or manually migrate first."
                    } else {
                        "Backup uses newer schema $schemaVersion than this app supports ($currentSupportedVersion). " +
                            "Please update the app or export from a compatible version."
                    }
                    return Result.failure(
                        Exception(message)
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
    
    private suspend fun verifyImportedDatabase(): DatabaseImportSummary = withContext(ioDispatcher) {
        try {
            // Reopen with a fresh connection and refresh invalidation state
            database.openHelper.writableDatabase
            refreshInvalidationTrackerSafely()

            // Reopen the database by accessing it (Room will auto-reopen)
            val expenseDao = database.expenseDao()
            val categoryDao = database.categoryDao()
            val merchantDao = database.merchantCategoryDao()
            val pendingReviewDao = database.pendingReviewDao()
            val budgetDao = database.budgetDao()
            
            // Get actual counts from the imported database
            val expenseCount = expenseDao.getTotalCount()
            val categoryCount = categoryDao.getCount()
            val merchantCount = merchantDao.getCount()
            val pendingReviewCount = pendingReviewDao.getPendingCount()
            val budgetCount = budgetDao.getCount()
            
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

    private fun refreshInvalidationTrackerSafely() {
        val tracker = database.invalidationTracker
        val invoked = listOf("refresh", "refreshAsync", "refreshVersionsAsync").any { methodName ->
            runCatching {
                val method = tracker.javaClass.methods.firstOrNull { it.name == methodName && it.parameterCount == 0 }
                    ?: return@runCatching false
                method.invoke(tracker)
                true
            }.getOrDefault(false)
        }

        if (!invoked) {
            Timber.w("Could not invoke Room invalidation refresh method")
        }
    }

    private fun restoreFromSafetyBackup(
        safetyBackupFile: File,
        dbFile: File,
        dbWalFile: File,
        dbShmFile: File
    ): Result<Unit> {
        return runCatching {
            runCatching { database.close() }
                .onFailure { Timber.w(it, "Failed to close Room database before rollback restore") }
            runCatching { database.openHelper.close() }
                .onFailure { Timber.w(it, "Failed to close Room openHelper before rollback restore") }

            if (!safetyBackupFile.exists() || !safetyBackupFile.canRead()) {
                throw Exception("Safety backup is not accessible: ${safetyBackupFile.absolutePath}")
            }

            val backupWalFile = File(safetyBackupFile.parentFile, "${safetyBackupFile.name}-wal")
            val backupShmFile = File(safetyBackupFile.parentFile, "${safetyBackupFile.name}-shm")

            dbFile.delete()
            dbWalFile.delete()
            dbShmFile.delete()

            safetyBackupFile.inputStream().use { input ->
                dbFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            if (backupWalFile.exists()) {
                backupWalFile.inputStream().use { input ->
                    dbWalFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }

            if (backupShmFile.exists()) {
                backupShmFile.inputStream().use { input ->
                    dbShmFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }

            database.openHelper.writableDatabase
            refreshInvalidationTrackerSafely()
        }.onSuccess {
            Timber.w("Import rollback succeeded using safety backup: ${safetyBackupFile.absolutePath}")
        }.onFailure {
            Timber.e(it, "Import rollback failed using safety backup: ${safetyBackupFile.absolutePath}")
        }
    }
    
    override suspend fun getDatabaseStats(): DatabaseStats = withContext(ioDispatcher) {
        try {
            val expenseDao = database.expenseDao()
            val categoryDao = database.categoryDao()
            val merchantDao = database.merchantCategoryDao()
            val pendingReviewDao = database.pendingReviewDao()
            
            DatabaseStats(
                transactionCount = expenseDao.getTotalCount(),
                categoryCount = categoryDao.getCount(),
                merchantCount = merchantDao.getCount(),
                pendingReviewCount = pendingReviewDao.getPendingCount()
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to get database stats")
            DatabaseStats(0, 0, 0, 0)
        }
    }
    
    override suspend fun createSafetyBackup(): Result<File> = withContext(ioDispatcher) {
        try {
            val dbFile = context.getDatabasePath(DATABASE_NAME)
            if (!dbFile.exists()) {
                return@withContext Result.failure(Exception("Database file not found"))
            }
            
            val checkpointResult = checkpointWal()
            if (checkpointResult.isFailure) {
                return@withContext Result.failure(
                    checkpointResult.exceptionOrNull() ?: Exception("Failed to checkpoint WAL")
                )
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
    
    override suspend fun resetDatabase(): Result<Unit> = withContext(ioDispatcher) {
        try {
            // Create safety backup first
            val safetyBackupResult = createSafetyBackup()
            if (safetyBackupResult.isFailure) {
                val reason = safetyBackupResult.exceptionOrNull()?.message
                    ?: "Unknown backup error"
                Timber.e("Database reset aborted: safety backup failed: $reason")
                return@withContext Result.failure(
                    Exception(
                        "Reset cancelled because safety backup failed. " +
                            "Please free storage/permissions and retry. Details: $reason"
                    )
                )
            }
            
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

    private suspend fun checkpointWal(): Result<Unit> {
        val maxAttempts = 3
        repeat(maxAttempts) { attempt ->
            try {
                val busy = database.openHelper.writableDatabase
                    .query("PRAGMA wal_checkpoint(FULL)")
                    .use { cursor ->
                        if (cursor.moveToFirst()) cursor.getInt(0) else 1
                    }

                if (busy == 0) {
                    Timber.d("WAL checkpoint completed (attempt ${attempt + 1})")
                    return Result.success(Unit)
                }

                val attemptsSoFar = attempt + 1
                if (attemptsSoFar >= maxAttempts) {
                    Timber.e("Checkpoint busy after $maxAttempts attempts")
                    return Result.failure(Exception("Database is busy (WAL checkpoint blocked). Please try again."))
                }

                Timber.w("Checkpoint busy (code $busy), retrying in 200ms (attempt $attemptsSoFar)")
                delay(200)
            } catch (e: android.database.sqlite.SQLiteDatabaseLockedException) {
                val attemptsSoFar = attempt + 1
                if (attemptsSoFar >= maxAttempts) {
                    Timber.e("Checkpoint locked after $maxAttempts attempts")
                    return Result.failure(Exception("Database is locked. Please try again."))
                }

                Timber.w("Checkpoint locked, retrying in 200ms (attempt $attemptsSoFar)")
                delay(200)
            }
        }

        return Result.failure(Exception("Failed to checkpoint WAL"))
    }
}
