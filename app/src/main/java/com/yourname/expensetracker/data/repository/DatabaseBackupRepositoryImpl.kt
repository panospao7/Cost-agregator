package com.yourname.expensetracker.data.repository

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.Environment
import com.yourname.expensetracker.data.privacy.BackupEncryptionService
import com.yourname.expensetracker.data.privacy.ExportAnonymizer
import com.yourname.expensetracker.data.database.APP_DATABASE_SCHEMA_VERSION
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.di.IoDispatcher
import com.yourname.expensetracker.domain.backup.DatabaseBackupRepository
import com.yourname.expensetracker.domain.backup.DatabaseImportSummary
import com.yourname.expensetracker.domain.backup.DatabaseStats
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of [DatabaseBackupRepository] that backs up and restores the
 * SQLite database only.
 *
 * ## Known limitation — receipt images are NOT included
 *
 * This implementation only copies the Room database file (`.db`).  Receipt
 * image assets stored by [ReceiptAssetStore] in `filesDir/receipts/` are
 * **not** included in the backup.  A future PR will introduce an archive-based
 * backup format (e.g. ZIP or TAR) that bundles both the database and the
 * receipt image files together using [ReceiptAssetStore.generateBackupManifest].
 *
 * Meanwhile, receipt image files persist in app-private storage and survive
 * app data clears only if the user explicitly backs them up via the OS backup
 * mechanism.
 */
@Singleton
class DatabaseBackupRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val privacyGate: PrivacyGate,
    private val privacySettingsRepository: PrivacySettingsRepository,
    private val backupEncryptionService: BackupEncryptionService,
    private val exportAnonymizer: ExportAnonymizer,
    private val secureKeyStorage: SecureKeyStorage
) : DatabaseBackupRepository {

    private var stagedImportVerifier: suspend (Context, String, File, Int, DatabaseImportSummary) -> DatabaseImportSummary =
        ::verifyStagedImportWithRoom

    private var liveImportVerifier: suspend (AppDatabase, File, Int, DatabaseImportSummary) -> DatabaseImportSummary =
        ::reopenAndVerifyLiveImport

    internal constructor(
        context: Context,
        database: AppDatabase,
        ioDispatcher: CoroutineDispatcher,
        privacyGate: PrivacyGate,
        privacySettingsRepository: PrivacySettingsRepository,
        backupEncryptionService: BackupEncryptionService,
        exportAnonymizer: ExportAnonymizer,
        secureKeyStorage: SecureKeyStorage,
        stagedImportVerifier: suspend (Context, String, File, Int, DatabaseImportSummary) -> DatabaseImportSummary,
        liveImportVerifier: suspend (AppDatabase, File, Int, DatabaseImportSummary) -> DatabaseImportSummary
    ) : this(context, database, ioDispatcher, privacyGate, privacySettingsRepository, backupEncryptionService, exportAnonymizer, secureKeyStorage) {
        this.stagedImportVerifier = stagedImportVerifier
        this.liveImportVerifier = liveImportVerifier
    }
    
    companion object {
        private const val BACKUP_PREFIX = "expense_tracker_backup_"
        private const val DATE_FORMAT = "yyyy-MM-dd_HH-mm-ss"
        private const val EXPORT_SUBDIR = "exports"
        private const val MIN_SUPPORTED_SCHEMA_VERSION = 6
        private const val BUDGETS_SCHEMA_GUARD_VERSION = 86
        private const val IMPORT_STAGING_PREFIX = "expense_tracker_db_import_stage_"
        private const val CURRENT_SUPPORTED_SCHEMA_VERSION = APP_DATABASE_SCHEMA_VERSION
        private const val BACKUP_ENCRYPTION_KEY_NAME = "backup_encryption_password"
        private const val BACKUP_ENCRYPTION_KEY_BYTES = 32 // 256 bits

        @JvmStatic
        suspend fun verifyStagedImportWithRoom(
            context: Context,
            databaseName: String,
            databaseFile: File,
            sourceSchemaVersion: Int,
            sourceSummary: DatabaseImportSummary
        ): DatabaseImportSummary {
            val stagedDatabase = AppDatabase.fileBuilder(context, databaseName).build()
            return try {
                stagedDatabase.openHelper.writableDatabase
                val verifiedSummary = queryRoomCountsForVerification(stagedDatabase)
                verifyDatabaseFileStateForVerification(
                    databaseFile = databaseFile,
                    sourceSchemaVersion = sourceSchemaVersion,
                    sourceSummary = sourceSummary,
                    actualSummary = verifiedSummary
                )
                verifiedSummary
            } finally {
                runCatching { stagedDatabase.close() }
            }
        }

        @JvmStatic
        suspend fun reopenAndVerifyLiveImport(
            database: AppDatabase,
            databaseFile: File,
            sourceSchemaVersion: Int,
            sourceSummary: DatabaseImportSummary
        ): DatabaseImportSummary {
            database.openHelper.writableDatabase
            refreshInvalidationTrackerSafelyForVerification(database)
            val verifiedSummary = queryRoomCountsForVerification(database)
            verifyDatabaseFileStateForVerification(
                databaseFile = databaseFile,
                sourceSchemaVersion = sourceSchemaVersion,
                sourceSummary = sourceSummary,
                actualSummary = verifiedSummary
            )
            return verifiedSummary
        }

        private fun queryRoomCountsForVerification(database: AppDatabase): DatabaseImportSummary {
            val supportDb = database.openHelper.writableDatabase
            return DatabaseImportSummary(
                transactionCount = countRowsForVerification(supportDb, "expenses"),
                categoryCount = countRowsForVerification(supportDb, "categories"),
                merchantCount = countRowsForVerification(supportDb, "merchant_categories"),
                pendingReviewCount = countRowsForVerification(supportDb, "pending_reviews"),
                budgetCount = countRowsForVerification(supportDb, "budgets")
            )
        }

        private fun verifyDatabaseFileStateForVerification(
            databaseFile: File,
            sourceSchemaVersion: Int,
            sourceSummary: DatabaseImportSummary,
            actualSummary: DatabaseImportSummary
        ) {
            val db = SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            try {
                val currentSchemaVersion = readSingleIntForVerification(db.rawQuery("PRAGMA user_version", null))
                if (currentSchemaVersion != CURRENT_SUPPORTED_SCHEMA_VERSION) {
                    throw Exception(
                        "Migrated database schema mismatch. Expected v$CURRENT_SUPPORTED_SCHEMA_VERSION but found v$currentSchemaVersion"
                    )
                }

                val integrity = readSingleStringForVerification(db.rawQuery("PRAGMA integrity_check", null))
                if (!integrity.equals("ok", ignoreCase = true)) {
                    throw Exception("Database integrity check failed: $integrity")
                }

                verifySummaryPreservedForVerification(sourceSummary, actualSummary, sourceSchemaVersion)
            } finally {
                db.close()
            }
        }

        internal fun verifySummaryPreservedForVerification(
            sourceSummary: DatabaseImportSummary,
            actualSummary: DatabaseImportSummary,
            sourceSchemaVersion: Int
        ) {
            // These core tables are expected to be count-preserving across staged import.
            // We intentionally block any reduction here rather than only full zeroing so that
            // partial data loss is caught before the live database is swapped.
            verifyCoreTableCountPreservedForVerification(
                tableLabel = "expenses",
                sourceCount = sourceSummary.transactionCount,
                actualCount = actualSummary.transactionCount,
                sourceSchemaVersion = sourceSchemaVersion
            )
            verifyCoreTableCountPreservedForVerification(
                tableLabel = "categories",
                sourceCount = sourceSummary.categoryCount,
                actualCount = actualSummary.categoryCount,
                sourceSchemaVersion = sourceSchemaVersion
            )
            verifyCoreTableCountPreservedForVerification(
                tableLabel = "merchant mappings",
                sourceCount = sourceSummary.merchantCount,
                actualCount = actualSummary.merchantCount,
                sourceSchemaVersion = sourceSchemaVersion
            )
            verifyCoreTableCountPreservedForVerification(
                tableLabel = "pending reviews",
                sourceCount = sourceSummary.pendingReviewCount,
                actualCount = actualSummary.pendingReviewCount,
                sourceSchemaVersion = sourceSchemaVersion
            )
            verifyCoreTableCountPreservedForVerification(
                tableLabel = "budgets",
                sourceCount = sourceSummary.budgetCount,
                actualCount = actualSummary.budgetCount,
                sourceSchemaVersion = sourceSchemaVersion
            )
        }

        private fun verifyCoreTableCountPreservedForVerification(
            tableLabel: String,
            sourceCount: Int,
            actualCount: Int,
            sourceSchemaVersion: Int
        ) {
            if (actualCount < sourceCount) {
                throw Exception(
                    "Verified import reduced $tableLabel from $sourceCount to $actualCount during migration from schema v$sourceSchemaVersion"
                )
            }
        }

        private fun countRowsForVerification(
            db: androidx.sqlite.db.SupportSQLiteDatabase,
            tableName: String
        ): Int {
            return readSingleIntForVerification(db.query("SELECT COUNT(*) FROM $tableName"))
        }

        private fun readSingleIntForVerification(cursor: Cursor): Int {
            cursor.use {
                return if (it.moveToFirst()) it.getInt(0) else 0
            }
        }

        private fun readSingleStringForVerification(cursor: Cursor): String {
            cursor.use {
                return if (it.moveToFirst()) it.getString(0) ?: "" else ""
            }
        }

        private fun refreshInvalidationTrackerSafelyForVerification(database: AppDatabase) {
            val tracker = database.invalidationTracker
            val invoked = listOf("refresh", "refreshAsync", "refreshVersionsAsync").any { methodName ->
                runCatching {
                    val method = tracker.javaClass.methods.firstOrNull {
                        it.name == methodName && it.parameterCount == 0
                    } ?: return@runCatching false
                    method.invoke(tracker)
                    true
                }.getOrDefault(false)
            }

            if (!invoked) {
                Timber.w("Could not invoke Room invalidation refresh method")
            }
        }

    }
    
    override suspend fun exportDatabase(): Result<File> = withContext(ioDispatcher) {
        try {
            val dbFile = context.getDatabasePath(AppDatabase.DATABASE_NAME)
            if (!dbFile.exists()) {
                return@withContext Result.failure(Exception("Database file not found"))
            }

            // --- Privacy gate checks ---
            // 1. Check whether encrypted backup is enabled via settings
            val settings = privacySettingsRepository.getSettings()
            val encryptionEnabled = settings.encryptedBackupEnabled

            if (encryptionEnabled) {
                // Gate: ENCRYPTED_BACKUP must be allowed
                val encryptedDecision = privacyGate.check(
                    PrivacyCapability.ENCRYPTED_BACKUP,
                    mapOf("operation" to "export")
                )
                if (encryptedDecision is PrivacyDecision.Denied) {
                    return@withContext Result.failure(
                        Exception("Encrypted backup denied by privacy gate: ${encryptedDecision.reason}")
                    )
                }
            } else {
                // Gate: RAWBACKUP_EXPORT must be allowed
                val rawDecision = privacyGate.check(
                    PrivacyCapability.RAWBACKUP_EXPORT,
                    mapOf("operation" to "export")
                )
                if (rawDecision is PrivacyDecision.Denied) {
                    return@withContext Result.failure(
                        Exception("Plaintext backup denied by privacy gate: ${rawDecision.reason}")
                    )
                }
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
            val encryptedFileName = "${BACKUP_PREFIX}${timestamp}.enc"

            // Save to app-private storage by default
            val exportDir = File(context.filesDir, EXPORT_SUBDIR).apply { mkdirs() }

            if (encryptionEnabled) {
                // --- Encrypted export flow ---
                // 1. Copy DB to a temp file
                val tempCopy = File(exportDir, "temp_export_${timestamp}.db")
                try {
                    dbFile.inputStream().use { input ->
                        tempCopy.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }

                    // 2. Sanitize the temp copy (strip raw OCR / notification content)
                    exportAnonymizer.sanitizeExport(tempCopy)

                    // 3. Read sanitized bytes and encrypt
                    val plainBytes = tempCopy.readBytes()
                    val backupPassword = getOrCreateBackupPassword()
                    val encryptedBytes = backupEncryptionService.encrypt(plainBytes, backupPassword)

                    // 4. Write encrypted output
                    val backupFile = File(exportDir, encryptedFileName)
                    backupFile.writeBytes(encryptedBytes)

                    Timber.d("Database encrypted and exported successfully to: ${backupFile.absolutePath}")
                    Result.success(backupFile)
                } finally {
                    tempCopy.delete()
                }
            } else {
                // --- Plaintext export flow (existing behaviour) ---
                val backupFile = File(exportDir, backupFileName)

                dbFile.inputStream().use { input ->
                    backupFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                Timber.d("Database exported successfully to: ${backupFile.absolutePath}")
                Result.success(backupFile)
            }
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
            
            // Block empty imports only when all tracked meaningful tables are empty.
            if (!sourceSummary.hasMeaningfulData()) {
                Timber.e(
                    "Source database is empty across tracked tables " +
                        "(expenses=0, categories=0, merchants=0, pendingReviews=0, budgets=0). Blocking import."
                )
                return@withContext Result.failure(
                    Exception("Backup file contains no data. Import blocked to prevent data loss.")
                )
            }
            
            Timber.d("Source validated: ${sourceSummary.transactionCount} transactions, ${sourceSummary.categoryCount} categories, schema v${sourceSummary.schemaVersion}")
            val liveDbFile = context.getDatabasePath(AppDatabase.DATABASE_NAME)
            val liveDbWalFile = File(liveDbFile.parentFile, "${AppDatabase.DATABASE_NAME}-wal")
            val liveDbShmFile = File(liveDbFile.parentFile, "${AppDatabase.DATABASE_NAME}-shm")
            val stagedDbName = "$IMPORT_STAGING_PREFIX${System.currentTimeMillis()}"
            val stagedDbFile = context.getDatabasePath(stagedDbName)
            val stagedDbWalFile = File(stagedDbFile.parentFile, "$stagedDbName-wal")
            val stagedDbShmFile = File(stagedDbFile.parentFile, "$stagedDbName-shm")

            var destinationFilesMutated = false
            var importSucceeded = false
            var safetyBackupFile: File? = null
            try {
                copyFile(sourceFile, stagedDbFile)

                val preflightResult = preflightImportedFile(stagedDbFile, sourceSummary)
                if (preflightResult.isFailure) {
                    throw preflightResult.exceptionOrNull() ?: Exception("Imported database preflight failed")
                }

                val verifiedStagedSummary = stagedImportVerifier(
                    context,
                    stagedDbName,
                    stagedDbFile,
                    sourceSummary.schemaVersion,
                    sourceSummary.toImportSummary()
                )

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
                safetyBackupFile = safetyBackupResult.getOrNull()
                    ?: return@withContext Result.failure(Exception("Safety backup was created but path is unavailable"))

                closeLiveDatabaseForFileSwap()

                destinationFilesMutated = true
                replaceDatabaseFiles(
                    sourceDbFile = stagedDbFile,
                    sourceWalFile = stagedDbWalFile,
                    sourceShmFile = stagedDbShmFile,
                    targetDbFile = liveDbFile,
                    targetWalFile = liveDbWalFile,
                    targetShmFile = liveDbShmFile
                )

                Timber.d("Verified database staged and swapped from: ${sourceFile.absolutePath}")

                val finalSummary = liveImportVerifier(
                    database,
                    liveDbFile,
                    sourceSummary.schemaVersion,
                    verifiedStagedSummary
                )

                Timber.d("Import verified: ${finalSummary.transactionCount} transactions, ${finalSummary.categoryCount} categories")
                importSucceeded = true
                Result.success(finalSummary)
            } catch (importError: Exception) {
                if (destinationFilesMutated && !importSucceeded) {
                    val rollbackResult = restoreFromSafetyBackup(
                        safetyBackupFile = safetyBackupFile
                            ?: throw Exception("Import failed after swap but safety backup path is unavailable"),
                        dbFile = liveDbFile,
                        dbWalFile = liveDbWalFile,
                        dbShmFile = liveDbShmFile
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
                stagedDbFile.delete()
                stagedDbWalFile.delete()
                stagedDbShmFile.delete()
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
        val schemaVersion: Int,
        val requiresBudgetsRepair: Boolean
    )

    private fun SourceValidationSummary.hasMeaningfulData(): Boolean {
        return transactionCount > 0 ||
            categoryCount > 0 ||
            merchantCount > 0 ||
            pendingReviewCount > 0 ||
            budgetCount > 0
    }

    private data class BudgetsColumnContract(
        val type: String,
        val notNull: Boolean,
        val defaultValue: String?,
        val pkPosition: Int
    )

    private data class BudgetsSchemaValidation(
        val isValid: Boolean,
        val isRepairable: Boolean,
        val reason: String
    )

    private data class BudgetsForeignKeyContract(
        val referencedTable: String,
        val fromColumn: String,
        val toColumn: String,
        val onDelete: String,
        val onUpdate: String
    )

    private val expectedBudgetsColumnsV86 = linkedMapOf(
        "id" to BudgetsColumnContract("INTEGER", notNull = true, defaultValue = null, pkPosition = 1),
        "categoryId" to BudgetsColumnContract("INTEGER", notNull = false, defaultValue = null, pkPosition = 0),
        "amount" to BudgetsColumnContract("REAL", notNull = true, defaultValue = null, pkPosition = 0),
        "period" to BudgetsColumnContract("TEXT", notNull = true, defaultValue = null, pkPosition = 0),
        "periodMode" to BudgetsColumnContract("TEXT", notNull = true, defaultValue = "'ROLLING'", pkPosition = 0),
        "startDate" to BudgetsColumnContract("INTEGER", notNull = true, defaultValue = null, pkPosition = 0),
        "isActive" to BudgetsColumnContract("INTEGER", notNull = true, defaultValue = "1", pkPosition = 0),
        "notifyAtWarning" to BudgetsColumnContract("REAL", notNull = true, defaultValue = "0.75", pkPosition = 0),
        "notifyAtCritical" to BudgetsColumnContract("REAL", notNull = true, defaultValue = "0.9", pkPosition = 0),
        "rollover" to BudgetsColumnContract("INTEGER", notNull = true, defaultValue = "0", pkPosition = 0),
        "createdAt" to BudgetsColumnContract("INTEGER", notNull = true, defaultValue = null, pkPosition = 0),
        "lastWarningNotifiedAt" to BudgetsColumnContract("INTEGER", notNull = false, defaultValue = null, pkPosition = 0),
        "lastCriticalNotifiedAt" to BudgetsColumnContract("INTEGER", notNull = false, defaultValue = null, pkPosition = 0),
        "lastExceededNotifiedAt" to BudgetsColumnContract("INTEGER", notNull = false, defaultValue = null, pkPosition = 0)
    )

    private val expectedBudgetsForeignKeyV86 = BudgetsForeignKeyContract(
        referencedTable = "categories",
        fromColumn = "categoryId",
        toColumn = "id",
        onDelete = "SET NULL",
        onUpdate = "NO ACTION"
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
                
                var requiresBudgetsRepair = false
                if (schemaVersion == BUDGETS_SCHEMA_GUARD_VERSION && tableExists(db, "budgets")) {
                    val budgetsValidation = validateBudgetsSchemaV86(db)
                    if (!budgetsValidation.isValid) {
                        if (budgetsValidation.isRepairable) {
                            requiresBudgetsRepair = true
                            Timber.w("Detected stale budgets schema in source backup: ${budgetsValidation.reason}")
                        } else {
                            return Result.failure(
                                Exception(
                                    "Unsupported budgets schema for v$BUDGETS_SCHEMA_GUARD_VERSION backup: ${budgetsValidation.reason}"
                                )
                            )
                        }
                    }
                }
                
                // Count rows in key tables
                val expenseCount = countRowsFromSourceTable(db, "expenses", required = true)
                val categoryCount = countRowsFromSourceTable(db, "categories", required = true)
                val merchantCount = countRowsFromSourceTable(db, "merchant_categories", required = false)
                val pendingReviewCount = countRowsFromSourceTable(db, "pending_reviews", required = false)
                val budgetCount = countRowsFromSourceTable(db, "budgets", required = false)
                
                Result.success(SourceValidationSummary(
                    transactionCount = expenseCount,
                    categoryCount = categoryCount,
                    merchantCount = merchantCount,
                    pendingReviewCount = pendingReviewCount,
                    budgetCount = budgetCount,
                    schemaVersion = schemaVersion,
                    requiresBudgetsRepair = requiresBudgetsRepair
                ))
            } finally {
                db.close()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to validate source database")
            Result.failure(Exception("Could not read source database: ${e.message}"))
        }
    }
    
    private fun closeLiveDatabaseForFileSwap() {
        runCatching { database.close() }
            .onFailure { Timber.w(it, "Failed to close Room database before import") }
        runCatching { database.openHelper.close() }
            .onFailure { Timber.w(it, "Failed to close Room openHelper before import") }
    }

    private fun replaceDatabaseFiles(
        sourceDbFile: File,
        sourceWalFile: File,
        sourceShmFile: File,
        targetDbFile: File,
        targetWalFile: File,
        targetShmFile: File
    ) {
        if (!sourceDbFile.exists()) {
            throw Exception("Verified staged database file is missing: ${sourceDbFile.absolutePath}")
        }

        targetDbFile.delete()
        targetWalFile.delete()
        targetShmFile.delete()

        if (!sourceDbFile.renameTo(targetDbFile)) {
            copyFile(sourceDbFile, targetDbFile)
            sourceDbFile.delete()
        }

        moveOptionalSidecar(sourceWalFile, targetWalFile)
        moveOptionalSidecar(sourceShmFile, targetShmFile)
    }

    private fun moveOptionalSidecar(sourceFile: File, targetFile: File) {
        targetFile.delete()
        if (!sourceFile.exists()) return

        if (!sourceFile.renameTo(targetFile)) {
            copyFile(sourceFile, targetFile)
            sourceFile.delete()
        }
    }

    private fun copyFile(sourceFile: File, destinationFile: File) {
        destinationFile.parentFile?.mkdirs()
        sourceFile.inputStream().use { input ->
            destinationFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun SourceValidationSummary.toImportSummary(): DatabaseImportSummary {
        return DatabaseImportSummary(
            transactionCount = transactionCount,
            categoryCount = categoryCount,
            merchantCount = merchantCount,
            pendingReviewCount = pendingReviewCount,
            budgetCount = budgetCount
        )
    }

    private fun preflightImportedFile(
        tempFile: File,
        sourceSummary: SourceValidationSummary
    ): Result<Unit> {
        if (sourceSummary.schemaVersion != BUDGETS_SCHEMA_GUARD_VERSION) {
            return Result.success(Unit)
        }

        return runCatching {
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                tempFile.absolutePath,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
            )
            try {
                var validation = validateBudgetsSchemaV86(db)
                if (!validation.isValid && validation.isRepairable) {
                    Timber.w("Repairing stale budgets schema in imported backup: ${validation.reason}")
                    repairBudgetsSchemaToV86(db)
                    validation = validateBudgetsSchemaV86(db)
                }

                if (!validation.isValid) {
                    throw Exception("Imported backup has incompatible budgets schema: ${validation.reason}")
                }

                if (sourceSummary.requiresBudgetsRepair) {
                    Timber.i("Budgets schema guard verified repaired import file for schema v$BUDGETS_SCHEMA_GUARD_VERSION")
                }
            } finally {
                db.close()
            }
        }
    }

    private fun countRowsFromSourceTable(
        db: android.database.sqlite.SQLiteDatabase,
        tableName: String,
        required: Boolean
    ): Int {
        if (!tableExists(db, tableName)) {
            val message = "Table '$tableName' missing in source database"
            return if (required) {
                throw Exception(message)
            } else {
                Timber.w("$message; treating row count as 0 for import validation")
                0
            }
        }

        return db.rawQuery("SELECT COUNT(*) FROM $tableName", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun tableExists(
        db: android.database.sqlite.SQLiteDatabase,
        tableName: String
    ): Boolean {
        return db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
            arrayOf(tableName)
        ).use { cursor ->
            cursor.moveToFirst()
        }
    }

    private fun validateBudgetsSchemaV86(
        db: android.database.sqlite.SQLiteDatabase
    ): BudgetsSchemaValidation {
        val columns = mutableMapOf<String, BudgetsColumnContract>()
        db.rawQuery("PRAGMA table_info('budgets')", null).use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                val type = cursor.getString(cursor.getColumnIndexOrThrow("type"))?.uppercase(Locale.US) ?: ""
                val notNull = cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) == 1
                val defaultValue = normalizeDefaultValue(cursor.getString(cursor.getColumnIndexOrThrow("dflt_value")))
                val pk = cursor.getInt(cursor.getColumnIndexOrThrow("pk"))
                columns[name] = BudgetsColumnContract(type = type, notNull = notNull, defaultValue = defaultValue, pkPosition = pk)
            }
        }

        if (columns.isEmpty()) {
            return BudgetsSchemaValidation(isValid = false, isRepairable = false, reason = "budgets table is missing")
        }

        if (columns.keys.toSet() != expectedBudgetsColumnsV86.keys.toSet()) {
            return BudgetsSchemaValidation(
                isValid = false,
                isRepairable = false,
                reason = "column set mismatch. found=${columns.keys} expected=${expectedBudgetsColumnsV86.keys}"
            )
        }

        val nonRepairableMismatches = mutableListOf<String>()
        val repairableMismatches = mutableListOf<String>()

        expectedBudgetsColumnsV86.forEach { (name, expected) ->
            val actual = columns[name] ?: return@forEach
            if (actual.type != expected.type) {
                nonRepairableMismatches += "$name type expected ${expected.type} got ${actual.type}"
            }
            if (actual.notNull != expected.notNull) {
                nonRepairableMismatches += "$name notNull expected ${expected.notNull} got ${actual.notNull}"
            }
            if (actual.pkPosition != expected.pkPosition) {
                nonRepairableMismatches += "$name pk expected ${expected.pkPosition} got ${actual.pkPosition}"
            }
            if (actual.defaultValue != expected.defaultValue) {
                repairableMismatches += "$name default expected ${expected.defaultValue} got ${actual.defaultValue}"
            }
        }

        if (nonRepairableMismatches.isNotEmpty()) {
            return BudgetsSchemaValidation(
                isValid = false,
                isRepairable = false,
                reason = nonRepairableMismatches.joinToString("; ")
            )
        }

        val foreignKeyValidation = validateBudgetsForeignKeys(db)
        if (!foreignKeyValidation.isValid) {
            return foreignKeyValidation
        }

        val indexValidation = validateBudgetsIndexes(db)
        if (!indexValidation.isValid && !indexValidation.isRepairable) {
            return indexValidation
        }
        if (!indexValidation.isValid) {
            repairableMismatches += indexValidation.reason
        }

        if (repairableMismatches.isNotEmpty()) {
            return BudgetsSchemaValidation(
                isValid = false,
                isRepairable = true,
                reason = repairableMismatches.joinToString("; ")
            )
        }

        return BudgetsSchemaValidation(isValid = true, isRepairable = false, reason = "ok")
    }

    private fun validateBudgetsForeignKeys(
        db: android.database.sqlite.SQLiteDatabase
    ): BudgetsSchemaValidation {
        val foreignKeys = mutableListOf<BudgetsForeignKeyContract>()
        db.rawQuery("PRAGMA foreign_key_list('budgets')", null).use { cursor ->
            while (cursor.moveToNext()) {
                foreignKeys += BudgetsForeignKeyContract(
                    referencedTable = cursor.getString(cursor.getColumnIndexOrThrow("table")),
                    fromColumn = cursor.getString(cursor.getColumnIndexOrThrow("from")),
                    toColumn = cursor.getString(cursor.getColumnIndexOrThrow("to")),
                    onDelete = cursor.getString(cursor.getColumnIndexOrThrow("on_delete")),
                    onUpdate = cursor.getString(cursor.getColumnIndexOrThrow("on_update"))
                )
            }
        }

        if (foreignKeys.size != 1) {
            return BudgetsSchemaValidation(
                isValid = false,
                isRepairable = false,
                reason = "invalid foreign keys count expected 1 got ${foreignKeys.size}"
            )
        }

        val actual = foreignKeys.first()
        if (actual != expectedBudgetsForeignKeyV86) {
            return BudgetsSchemaValidation(
                isValid = false,
                isRepairable = false,
                reason =
                    "invalid foreign key expected ${expectedBudgetsForeignKeyV86.fromColumn}->" +
                        "${expectedBudgetsForeignKeyV86.referencedTable}(${expectedBudgetsForeignKeyV86.toColumn}) " +
                        "onDelete ${expectedBudgetsForeignKeyV86.onDelete} onUpdate ${expectedBudgetsForeignKeyV86.onUpdate} " +
                        "got ${actual.fromColumn}->${actual.referencedTable}(${actual.toColumn}) " +
                        "onDelete ${actual.onDelete} onUpdate ${actual.onUpdate}"
            )
        }

        return BudgetsSchemaValidation(isValid = true, isRepairable = false, reason = "ok")
    }

    private fun validateBudgetsIndexes(
        db: android.database.sqlite.SQLiteDatabase
    ): BudgetsSchemaValidation {
        val indexMetadata = mutableMapOf<String, Boolean>()
        db.rawQuery("PRAGMA index_list('budgets')", null).use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                val isUnique = cursor.getInt(cursor.getColumnIndexOrThrow("unique")) == 1
                indexMetadata[name] = isUnique
            }
        }

        val requiredIndexes = listOf("index_budgets_categoryId", "index_budgets_isActive")
        val missing = requiredIndexes.filterNot(indexMetadata::containsKey)
        if (missing.isNotEmpty()) {
            return BudgetsSchemaValidation(
                isValid = false,
                isRepairable = true,
                reason = "missing indexes ${missing.joinToString(",")}"
            )
        }

        val uniqueMismatches = requiredIndexes.filter { indexMetadata[it] == true }
        if (uniqueMismatches.isNotEmpty()) {
            return BudgetsSchemaValidation(
                isValid = false,
                isRepairable = false,
                reason = "invalid unique index metadata ${uniqueMismatches.joinToString(",")}"
            )
        }

        val wrongIndexColumns = mutableListOf<String>()
        if (!indexHasExactColumns(db, "index_budgets_categoryId", listOf("categoryId"))) {
            wrongIndexColumns += "index_budgets_categoryId(categoryId)"
        }
        if (!indexHasExactColumns(db, "index_budgets_isActive", listOf("isActive"))) {
            wrongIndexColumns += "index_budgets_isActive(isActive)"
        }

        if (wrongIndexColumns.isNotEmpty()) {
            return BudgetsSchemaValidation(
                isValid = false,
                isRepairable = true,
                reason = "invalid index columns ${wrongIndexColumns.joinToString(",")}"
            )
        }

        return BudgetsSchemaValidation(isValid = true, isRepairable = false, reason = "ok")
    }

    private fun indexHasExactColumns(
        db: android.database.sqlite.SQLiteDatabase,
        indexName: String,
        expectedColumns: List<String>
    ): Boolean {
        val actualColumns = mutableListOf<String>()
        db.rawQuery("PRAGMA index_info('$indexName')", null).use { cursor ->
            while (cursor.moveToNext()) {
                actualColumns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
            }
        }
        return actualColumns == expectedColumns
    }

    private fun normalizeDefaultValue(rawValue: String?): String? {
        if (rawValue == null) return null
        var value = rawValue.trim()
        while (value.startsWith("(") && value.endsWith(")") && value.length > 2) {
            value = value.substring(1, value.length - 1).trim()
        }
        return value
    }

    private fun repairBudgetsSchemaToV86(db: android.database.sqlite.SQLiteDatabase) {
        db.beginTransaction()
        try {
            db.execSQL(
                """
                CREATE TABLE budgets_new (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    categoryId INTEGER,
                    amount REAL NOT NULL,
                    period TEXT NOT NULL,
                    periodMode TEXT NOT NULL DEFAULT 'ROLLING',
                    startDate INTEGER NOT NULL,
                    isActive INTEGER NOT NULL DEFAULT 1,
                    notifyAtWarning REAL NOT NULL DEFAULT 0.75,
                    notifyAtCritical REAL NOT NULL DEFAULT 0.9,
                    rollover INTEGER NOT NULL DEFAULT 0,
                    createdAt INTEGER NOT NULL,
                    lastWarningNotifiedAt INTEGER,
                    lastCriticalNotifiedAt INTEGER,
                    lastExceededNotifiedAt INTEGER,
                    FOREIGN KEY(categoryId) REFERENCES categories(id) ON DELETE SET NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO budgets_new (
                    id,
                    categoryId,
                    amount,
                    period,
                    periodMode,
                    startDate,
                    isActive,
                    notifyAtWarning,
                    notifyAtCritical,
                    rollover,
                    createdAt,
                    lastWarningNotifiedAt,
                    lastCriticalNotifiedAt,
                    lastExceededNotifiedAt
                )
                SELECT
                    id,
                    categoryId,
                    amount,
                    period,
                    periodMode,
                    startDate,
                    isActive,
                    notifyAtWarning,
                    notifyAtCritical,
                    rollover,
                    createdAt,
                    lastWarningNotifiedAt,
                    lastCriticalNotifiedAt,
                    lastExceededNotifiedAt
                FROM budgets
                """.trimIndent()
            )
            db.execSQL("DROP TABLE budgets")
            db.execSQL("ALTER TABLE budgets_new RENAME TO budgets")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_budgets_categoryId ON budgets (categoryId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_budgets_isActive ON budgets (isActive)")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
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
            refreshInvalidationTrackerSafelyForVerification(database)
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
            val dbFile = context.getDatabasePath(AppDatabase.DATABASE_NAME)
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
            
            val dbFile = context.getDatabasePath(AppDatabase.DATABASE_NAME)
            val dbWalFile = File(dbFile.parent, "${AppDatabase.DATABASE_NAME}-wal")
            val dbShmFile = File(dbFile.parent, "${AppDatabase.DATABASE_NAME}-shm")
            
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

    /**
     * Returns the backup encryption password, generating and persisting a new
     * random one if none exists yet.
     *
     * The password is a base64-encoded 256-bit random value stored in
     * [SecureKeyStorage]. This ensures the password is consistent across
     * export sessions without requiring user input.
     */
    private fun getOrCreateBackupPassword(): String {
        val existing = secureKeyStorage.getKey(BACKUP_ENCRYPTION_KEY_NAME)
        if (existing != null && existing.isNotBlank()) {
            return existing
        }

        val random = SecureRandom()
        val keyBytes = ByteArray(BACKUP_ENCRYPTION_KEY_BYTES)
        random.nextBytes(keyBytes)
        val newPassword = Base64.getEncoder().encodeToString(keyBytes)
        secureKeyStorage.storeKey(BACKUP_ENCRYPTION_KEY_NAME, newPassword)
        Timber.d("Generated and stored new backup encryption password")
        return newPassword
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
