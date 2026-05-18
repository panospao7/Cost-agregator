package com.yourname.expensetracker.data.repository

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.Environment
import com.yourname.expensetracker.BuildConfig
import com.yourname.expensetracker.data.backup.BackupVerifier
import com.yourname.expensetracker.data.backup.CostbackupBundle
import com.yourname.expensetracker.data.backup.RestoreJournal
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.backup.RestoreDatabaseOpener
import com.yourname.expensetracker.data.backup.WorkerDrainTimeoutException
import com.yourname.expensetracker.data.privacy.BackupEncryptionService
import com.yourname.expensetracker.data.privacy.ExportAnonymizer
import com.yourname.expensetracker.data.database.APP_DATABASE_SCHEMA_VERSION
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.di.IoDispatcher
import com.yourname.expensetracker.domain.backup.BackupPrivacyMode
import com.yourname.expensetracker.domain.backup.DatabaseBackupRepository
import com.yourname.expensetracker.domain.backup.DatabaseImportResult
import com.yourname.expensetracker.domain.backup.DatabaseImportSummary
import com.yourname.expensetracker.domain.backup.DatabaseStats
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptAssetStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of [DatabaseBackupRepository] that backs up and restores the
 * SQLite database and associated assets.
 *
 * Supports:
 * - Legacy `.db` / `.enc` export (plaintext or auto-key encrypted)
 * - `.costbackup` bundle format (user-password encrypted ZIP with manifest,
 *   checksums, DB snapshot, and receipt images)
 * - Full 56-table verification via [BackupVerifier]
 * - Maintenance mode via [RestoreMaintenanceMode]
 * - Crash-safe restore journal via [RestoreJournal]
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
    private val secureKeyStorage: SecureKeyStorage,
    private val receiptAssetStore: ReceiptAssetStore,
    private val restoreMaintenanceMode: RestoreMaintenanceMode,
    private val restoreJournal: RestoreJournal,
    private val workerDrain: com.yourname.expensetracker.domain.workers.WorkerDrainController,
    private val restoreDatabaseOpener: RestoreDatabaseOpener,
    private val maintenanceOperationRunner: com.yourname.expensetracker.data.backup.MaintenanceOperationRunner,
    private val snapshotCreator: com.yourname.expensetracker.data.backup.SqliteSnapshotCreator,
    private val restoreInternalWriteScope: com.yourname.expensetracker.data.backup.RestoreInternalWriteScope,
    private val operationRunRecorder: com.yourname.expensetracker.domain.diagnostics.OperationRunRecorder
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
        receiptAssetStore: ReceiptAssetStore,
        restoreMaintenanceMode: RestoreMaintenanceMode,
        restoreJournal: RestoreJournal,
        stagedImportVerifier: suspend (Context, String, File, Int, DatabaseImportSummary) -> DatabaseImportSummary,
        liveImportVerifier: suspend (AppDatabase, File, Int, DatabaseImportSummary) -> DatabaseImportSummary
    ) : this(
        context, database, ioDispatcher, privacyGate, privacySettingsRepository,
        backupEncryptionService, exportAnonymizer, secureKeyStorage,
        receiptAssetStore, restoreMaintenanceMode, restoreJournal,
        com.yourname.expensetracker.domain.workers.NoOpWorkerDrainController(),
        object : RestoreDatabaseOpener { override fun openFreshDatabase() = database },
        com.yourname.expensetracker.data.backup.MaintenanceOperationRunner(
            restoreMaintenanceMode,
            com.yourname.expensetracker.domain.workers.NoOpWorkerDrainController()
        ),
        com.yourname.expensetracker.data.backup.SqliteSnapshotCreator(),
        com.yourname.expensetracker.data.backup.RestoreInternalWriteScope(restoreMaintenanceMode),
        object : com.yourname.expensetracker.domain.diagnostics.OperationRunRecorder {
            override suspend fun start(operationType: String, actor: String?, metadata: com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata): com.yourname.expensetracker.domain.diagnostics.OperationRunHandle =
                com.yourname.expensetracker.domain.diagnostics.NoOpOperationRunHandle
            override suspend fun <T> runOperation(operationType: String, actor: String?, metadata: com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata, block: suspend (com.yourname.expensetracker.domain.diagnostics.OperationRunHandle) -> T): T =
                block(com.yourname.expensetracker.domain.diagnostics.NoOpOperationRunHandle)
            override suspend fun recoverStaleRunningOperationRuns(staleThresholdMs: Long) = Unit
        }
    ) {
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
                budgetCount = countRowsForVerification(supportDb, "budgets"),
                receiptCount = countRowsForVerification(supportDb, "scanned_receipts"),
                warrantyCount = countRowsForVerification(supportDb, "warranties"),
                groupCount = countRowsForVerification(supportDb, "expense_groups"),
                subscriptionCount = countRowsForVerification(supportDb, "subscription_candidates"),
                savingsGoalCount = countRowsForVerification(supportDb, "savings_goals"),
                allTableCounts = BackupVerifier.allTableNames().associateWith { tableName ->
                    countRowsForVerification(supportDb, tableName)
                }
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
            // Use the full BackupVerifier for Tier 1 exact count checks.
            // If we have allTableCounts from both source and actual, use the verifier.
            val sourceCounts = sourceSummary.allTableCounts
            val actualCounts = actualSummary.allTableCounts

            if (sourceCounts.isNotEmpty() && actualCounts.isNotEmpty()) {
                // Full verification across all tables
                for ((tableName, expectedCount) in sourceCounts) {
                    val actualCount = actualCounts[tableName] ?: 0
                    val tier = BackupVerifier.tableTier(tableName)
                    if (tier == BackupVerifier.VerificationTier.TIER_1_EXACT) {
                        verifyCoreTableCountPreservedForVerification(
                            tableLabel = tableName,
                            sourceCount = expectedCount,
                            actualCount = actualCount,
                            sourceSchemaVersion = sourceSchemaVersion
                        )
                    }
                }
            } else {
                // Fallback for legacy imports without full table counts
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
        }

        private fun verifyCoreTableCountPreservedForVerification(
            tableLabel: String,
            sourceCount: Int,
            actualCount: Int,
            sourceSchemaVersion: Int
        ) {
            // EXACT match required — NOT >= (G5/G6 fix)
            if (actualCount != sourceCount) {
                throw Exception(
                    "Verified import changed $tableLabel from $sourceCount to $actualCount during migration from schema v$sourceSchemaVersion"
                )
            }
        }

        private fun countRowsForVerification(
            db: androidx.sqlite.db.SupportSQLiteDatabase,
            tableName: String
        ): Int {
            return readSingleIntForVerification(db.query("SELECT COUNT(*) FROM \"$tableName\""))
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
    
    @Deprecated("Use createCostBackup() for production. Raw DB export is debug-only.")
    override suspend fun exportDatabase(): Result<File> = withContext(ioDispatcher) {
        // P1-11: Raw DB export is disabled in release builds
        if (!BuildConfig.DEBUG) throw UnsupportedOperationException("Raw DB export disabled in release")
        // Enter maintenance mode + drain workers before copying live DB (debug path)
        try {
            maintenanceOperationRunner.enterAndDrain(
                RestoreMaintenanceMode.Mode.BACKUP_EXPORTING,
                "exportDatabaseDebugRaw",
                failOnTimeout = true
            )
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        try {
            val dbFile = context.getDatabasePath(AppDatabase.DATABASE_NAME)
            if (!dbFile.exists()) {
                restoreMaintenanceMode.exit(forceRestartRequired = false)
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
                if (encryptedDecision.blocksExecution()) {
                    return@withContext Result.failure(
                        Exception("Encrypted backup denied by privacy gate: ${encryptedDecision.reason()}")
                    )
                }
            } else {
                // Gate: RAWBACKUP_EXPORT must be allowed
                val rawDecision = privacyGate.check(
                    PrivacyCapability.RAWBACKUP_EXPORT,
                    mapOf("operation" to "export")
                )
                if (rawDecision.blocksExecution()) {
                    return@withContext Result.failure(
                        Exception("Plaintext backup denied by privacy gate: ${rawDecision.reason()}")
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
            val timestamp = DateTimeFormatter.ofPattern(DATE_FORMAT, Locale.US).format(LocalDateTime.now(ZoneId.systemDefault()))
            val backupFileName = "${BACKUP_PREFIX}${timestamp}.db"
            val encryptedFileName = "${BACKUP_PREFIX}${timestamp}.enc"

            // Save to app-private storage by default
            val exportDir = File(context.filesDir, EXPORT_SUBDIR).apply { mkdirs() }

            if (encryptionEnabled) {
                // --- Encrypted export flow ---
                // 1. Copy DB to a temp file and sanitise
                val tempCopy = File(exportDir, "temp_export_${timestamp}.db")
                try {
                    dbFile.inputStream().use { input ->
                        tempCopy.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }

                    // 2. Sanitize the temp copy (strip raw OCR / notification content)
                    exportAnonymizer.sanitizeExport(tempCopy)

                    // 3. Stream-encrypt: read sanitised file + write ciphertext in 8KB chunks
                    val backupPassword = getOrCreateBackupPassword()
                    val backupFile = File(exportDir, encryptedFileName)
                    FileOutputStream(backupFile).use { fos ->
                        backupEncryptionService.encrypt(tempCopy, fos, backupPassword)
                    }

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
                restoreMaintenanceMode.exit(forceRestartRequired = false)
                Result.success(backupFile)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to export database")
            runCatching { restoreMaintenanceMode.exit(forceRestartRequired = false) }
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
    
    /**
     * Creates a .costbackup bundle (encrypted ZIP with manifest, checksums,
     * database snapshot, and receipt images).
     *
     * @param password The user-provided encryption password
     * @param includeReceiptImages Whether to include receipt image assets
     * @param redacted Whether to sanitize sensitive data (default: true)
     * @return Result containing the .costbackup File
     */
    override suspend fun createCostBackup(
        password: String,
        includeReceiptImages: Boolean,
        redacted: Boolean,
        privacyMode: BackupPrivacyMode?
    ): Result<File> = withContext(ioDispatcher) {
        val run = operationRunRecorder.start("BACKUP_EXPORT", actor = "user")
        try {
            // If privacyMode is provided, derive booleans from it
            val resolvedIncludeReceiptImages = privacyMode?.includesReceiptImages ?: includeReceiptImages
            val resolvedRedacted = privacyMode?.redactsRawText ?: redacted

            // Privacy gate check
            val encryptedDecision = privacyGate.check(
                PrivacyCapability.ENCRYPTED_BACKUP,
                mapOf("operation" to "create_costbackup")
            )
            if (encryptedDecision is PrivacyDecision.Denied) {
                run.failedFinal("Privacy gate denied: ${encryptedDecision.reason}")
                return@withContext Result.failure(
                    Exception("Encrypted backup denied by privacy gate: ${encryptedDecision.reason}")
                )
            }

            // P1-05: Enter backup maintenance mode + drain workers for point-in-time consistency
            maintenanceOperationRunner.enterAndDrain(RestoreMaintenanceMode.Mode.BACKUP_EXPORTING,
            "createCostBackup",
            failOnTimeout = true)
            Timber.d("BackupOperationEvent.BACKUP_STARTED: entering BACKUP_EXPORTING mode")
            run.event("MAINTENANCE_ENTERED", com.yourname.expensetracker.domain.diagnostics.EventOutcome.COMPLETED)

            // WAL checkpoint
            val checkpointResult = checkpointWal()
            if (checkpointResult.isFailure) {
                restoreMaintenanceMode.exit(forceRestartRequired = false)
                return@withContext Result.failure(
                    checkpointResult.exceptionOrNull() ?: Exception("Failed to checkpoint WAL")
                )
            }

            val dbFile = context.getDatabasePath(AppDatabase.DATABASE_NAME)
            if (!dbFile.exists()) {
                restoreMaintenanceMode.exit(forceRestartRequired = false)
                return@withContext Result.failure(Exception("Database file not found"))
            }

            // Copy DB to temp for snapshot
            val timestamp = DateTimeFormatter.ofPattern(DATE_FORMAT, Locale.US).format(LocalDateTime.now(ZoneId.systemDefault()))
            val tempDb = java.io.File(context.cacheDir, "costbackup_snapshot_${timestamp}.db")
            try {
                // Capture live counts under drain before snapshot (for equivalence verification)
                val liveCountsBeforeCopy = runCatching {
                    val liveDb = android.database.sqlite.SQLiteDatabase.openDatabase(
                        dbFile.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
                    )
                    liveDb.use {
                        BackupVerifier.allTableNames().associateWith { tableName ->
                            runCatching {
                                liveDb.rawQuery("SELECT COUNT(*) FROM \"$tableName\"", null)
                                    .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
                            }.getOrDefault(0)
                        }
                    }
                }.getOrNull()

                // Use VACUUM INTO if supported, otherwise drained file-copy
                val snapshotResult = snapshotCreator.createSnapshot(dbFile, tempDb, liveCountsBeforeCopy)
                Timber.d("Snapshot created via %s", snapshotResult.method)
                run.event("SNAPSHOT_CREATED", com.yourname.expensetracker.domain.diagnostics.EventOutcome.COMPLETED)

                // Sanitize if redacted
                if (resolvedRedacted) {
                    exportAnonymizer.sanitizeExport(tempDb)
                }

                // Get table counts from snapshot
                val snapshotDb = android.database.sqlite.SQLiteDatabase.openDatabase(
                    tempDb.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
                )
                val tableCounts = try {
                    BackupVerifier.allTableNames().associateWith { tableName ->
                        try {
                            val cursor = snapshotDb.rawQuery("SELECT COUNT(*) FROM \"$tableName\"", null)
                            cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
                        } catch (e: Exception) {
                            0
                        }
                    }
                } finally {
                    snapshotDb.close()
                }

                // Verify snapshot integrity before bundling
                val snapshotVerification = BackupVerifier.verify(tempDb, tableCounts)
                if (!snapshotVerification.passed) {
                    restoreMaintenanceMode.exit(forceRestartRequired = false)
                    return@withContext Result.failure(
                        Exception("Backup snapshot verification failed: ${snapshotVerification.errors.joinToString("; ")}")
                    )
                }

                // Collect receipt assets
                // P7-P1-2: Skip receipt images when redacted=true (they contain PII).
                val receiptFiles = if (resolvedIncludeReceiptImages && !resolvedRedacted) {
                    collectReceiptAssetsForBackup()
                } else {
                    emptyMap()
                }

                // Create .costbackup
                val backupsDir = java.io.File(context.filesDir, "backups").apply { mkdirs() }
                val shortUuid = UUID.randomUUID().toString().take(8)
                val outputName = "expense_tracker_backup_${timestamp}_${shortUuid}.costbackup"
                val outputFile = java.io.File(backupsDir, outputName)

                val result = CostbackupBundle.create(
                    outputFile = outputFile,
                    databaseFile = tempDb,
                    receiptFiles = receiptFiles,
                    password = password,
                    tableCounts = tableCounts,
                    databaseVersion = APP_DATABASE_SCHEMA_VERSION,
                    redacted = resolvedRedacted,
                    includeReceiptImages = resolvedIncludeReceiptImages,
                    encryptionService = backupEncryptionService,
                    privacyModeName = privacyMode?.name
                )

                if (result.isFailure) {
                    restoreMaintenanceMode.exit(forceRestartRequired = false)
                    run.failedFinal("Bundle creation failed", result.exceptionOrNull())
                    return@withContext Result.failure(
                        result.exceptionOrNull() ?: Exception("Failed to create .costbackup bundle")
                    )
                }

                run.event("ENCRYPTED", com.yourname.expensetracker.domain.diagnostics.EventOutcome.COMPLETED)
                Timber.d("Created .costbackup: %s", outputFile.absolutePath)
                restoreMaintenanceMode.exit(forceRestartRequired = false)
                Timber.d("BackupOperationEvent.BACKUP_COMPLETED: backup finished successfully")
                run.success()
                Result.success(outputFile)
            } finally {
                tempDb.delete()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to create .costbackup bundle")
            restoreMaintenanceMode.exit(forceRestartRequired = false)
            run.failedFinal(e.message ?: "Exception", e)
            Result.failure(e)
        }
    }

    override suspend fun restoreCostBackup(
        bundleFile: File,
        password: String
    ): Result<DatabaseImportResult> = withContext(ioDispatcher) {
        val run = operationRunRecorder.start("RESTORE_COSTBACKUP", actor = "user")
        try {
            // 1. Enter maintenance mode + drain workers — RESTORE_PREPARING, blocks all writes
            maintenanceOperationRunner.enterAndDrain(RestoreMaintenanceMode.Mode.RESTORE_PREPARING,
            "restoreCostBackup",
            failOnTimeout = true)
            Timber.w("Restore: entered maintenance mode, workers drained")
            run.event("MAINTENANCE_ENTERED", com.yourname.expensetracker.domain.diagnostics.EventOutcome.COMPLETED)

            val liveDbFile = context.getDatabasePath(AppDatabase.DATABASE_NAME)
            val liveDbPath = liveDbFile.absolutePath

            val stagedDbName = "${IMPORT_STAGING_PREFIX}${System.currentTimeMillis()}"
            val stagedDbFile = context.getDatabasePath(stagedDbName)
            val stagedDbPath = stagedDbFile.absolutePath

            // 2. Create restore journal — state = PREPARING
            var journalEntry = restoreJournal.beginJournal(
                sourceBackupPath = bundleFile.absolutePath,
                stagedDbPath = stagedDbPath,
                liveDbPath = liveDbPath
            )
            Timber.d("Restore journal created: %s", journalEntry.operationId)
            run.event("JOURNAL_CREATED", com.yourname.expensetracker.domain.diagnostics.EventOutcome.COMPLETED)

            // 3. Extract bundle to temp workspace
            val tempDir = File(context.cacheDir, "costbackup_extract_${System.currentTimeMillis()}")
            val extractionResult = CostbackupBundle.extract(bundleFile, tempDir, password)
                .getOrElse { error ->
                    // Wrong password or corrupt bundle — exit maintenance, live DB never touched
                    restoreMaintenanceMode.exit(forceRestartRequired = false)
                    restoreJournal.failJournal(journalEntry, error.message ?: "Extraction failed")
                    tempDir.deleteRecursively()
                    return@withContext when (error) {
                        is CostbackupBundle.WrongBackupPasswordException ->
                            Result.failure(error)
                        is CostbackupBundle.InvalidBackupFormatException ->
                            Result.failure(error)
                        is CostbackupBundle.UnsupportedBackupVersionException ->
                            Result.failure(error)
                        is CostbackupBundle.ChecksumMismatchException ->
                            Result.failure(error)
                        else -> Result.failure(error)
                    }
                }

            val manifest = extractionResult.manifest
            journalEntry = restoreJournal.transitionTo(journalEntry, RestoreJournal.JournalState.STAGED)

            // 4. Verify manifest has data
            val manifestTableCounts = manifest.tableCounts
            if (manifestTableCounts.values.all { it == 0 }) {
                restoreMaintenanceMode.exit(forceRestartRequired = false)
                restoreJournal.failJournal(journalEntry, "Backup contains no data")
                tempDir.deleteRecursively()
                return@withContext Result.failure(
                    Exception("Backup contains no data. Restore blocked.")
                )
            }

            // 5. Copy extracted DB to staging location
            val extractedDb = extractionResult.dbFile
            copyFile(extractedDb, stagedDbFile)

            // 6. Quick-verify staged DB (integrity, FK, Tier 1 counts)
            try {
                BackupVerifier.verifyQuick(stagedDbFile, manifestTableCounts)
                Timber.d("Staged DB quick verification passed")
            } catch (e: Exception) {
                restoreMaintenanceMode.exit(forceRestartRequired = false)
                restoreJournal.failJournal(journalEntry, "Staged verification failed: ${e.message}")
                stagedDbFile.delete()
                tempDir.deleteRecursively()
                return@withContext Result.failure(
                    Exception("Staged database verification failed: ${e.message}")
                )
            }

            // 6a. Open staged DB with Room to trigger migration BEFORE swapping to live.
            //     This ensures any schema migration failures are caught while the live DB
            //     is still intact and before a safety backup is created.
            try {
                val stagedDatabase = AppDatabase.fileBuilder(context, stagedDbName).build()
                try {
                    // Opening writable DB triggers Room migration if needed
                    stagedDatabase.openHelper.writableDatabase
                    Timber.d("Staged DB Room migration triggered successfully")

                    // P7-P1-1: Step 6b — Verify staged DB after migration BEFORE swapping to live.
                    // Catches migration corruption while the live DB remains intact.
                    val postMigrationCheck = runCatching {
                        BackupVerifier.verifyQuick(stagedDbFile, manifestTableCounts)
                    }

                    postMigrationCheck.getOrElse { error ->
                        Timber.e(error, "Post-migration staged DB verification failed — aborting restore")
                        restoreMaintenanceMode.exit(forceRestartRequired = false)
                        restoreJournal.failJournal(journalEntry, "Post-migration verification failed: ${error.message}")
                        stagedDbFile.delete()
                        File(stagedDbPath + "-wal").delete()
                        File(stagedDbPath + "-shm").delete()
                        tempDir.deleteRecursively()
                        return@withContext Result.failure(
                            Exception("Post-migration staged DB verification failed: ${error.message}")
                        )
                    }

                    Timber.d("Post-migration quick verification passed")
                } finally {
                    runCatching { stagedDatabase.close() }
                }
            } catch (e: Exception) {
                restoreMaintenanceMode.exit(forceRestartRequired = false)
                restoreJournal.failJournal(journalEntry, "Staged migration failed: ${e.message}")
                stagedDbFile.delete()
                tempDir.deleteRecursively()
                // Delete any migrated WAL/SHM files Room may have created
                File(stagedDbPath + "-wal").delete()
                File(stagedDbPath + "-shm").delete()
                return@withContext Result.failure(
                    Exception("Staged database migration failed: ${e.message}")
                )
            }

            // 7. Create safety backup
            val safetyBackupResult = createSafetyBackupInternalAssumingMaintenance("restore")
            if (safetyBackupResult.isFailure) {
                val reason = safetyBackupResult.exceptionOrNull()?.message ?: "Unknown error"
                restoreMaintenanceMode.exit(forceRestartRequired = false)
                restoreJournal.failJournal(journalEntry, "Safety backup failed: $reason")
                stagedDbFile.delete()
                tempDir.deleteRecursively()
                return@withContext Result.failure(
                    Exception("Restore cancelled because safety backup failed: $reason")
                )
            }
            val safetyBackupFile = safetyBackupResult.getOrNull()
                ?: return@withContext Result.failure(Exception("Safety backup created but path unavailable"))

            // 8. Persist safety backup path in journal before swap, so crash recovery
            //    can restore from it if the process dies during the swap.
            journalEntry = restoreJournal.transitionTo(
                journalEntry,
                RestoreJournal.JournalState.SAFETY_BACKUP_CREATED,
                safetyBackupPath = safetyBackupFile.absolutePath
            )
            // Swap live DB with staged DB
            journalEntry = restoreJournal.transitionTo(journalEntry, RestoreJournal.JournalState.SWAPPING)
            run.event("LIVE_DB_SWAPPING", com.yourname.expensetracker.domain.diagnostics.EventOutcome.ATTEMPTED)
            val liveDbWalFile = File(liveDbFile.parentFile, "${AppDatabase.DATABASE_NAME}-wal")
            val liveDbShmFile = File(liveDbFile.parentFile, "${AppDatabase.DATABASE_NAME}-shm")
            val stagedDbWalFile = File(stagedDbFile.parentFile, "$stagedDbName-wal")
            val stagedDbShmFile = File(stagedDbFile.parentFile, "$stagedDbName-shm")

            closeLiveDatabaseForFileSwap()

            val preRestoreFile = File(liveDbFile.parentFile, "${AppDatabase.DATABASE_NAME}.pre_restore")
            try {
                // Move live → .pre_restore (never delete before staged is verified in place)
                if (liveDbFile.exists()) {
                    liveDbFile.renameTo(preRestoreFile)
                }
                // P7-CURRENT-001: Delete existing WAL/SHM sidecars before copying staged DB
                val liveWal = File(liveDbFile.path + "-wal")
                val liveShm = File(liveDbFile.path + "-shm")
                liveWal.delete()
                liveShm.delete()
                // Copy staged → live
                copyFile(stagedDbFile, liveDbFile)
                if (stagedDbWalFile.exists()) copyFile(stagedDbWalFile, liveDbWalFile)
                if (stagedDbShmFile.exists()) copyFile(stagedDbShmFile, liveDbShmFile)
            } catch (e: Exception) {
                // Swap failed — attempt rollback
                Timber.e(e, "Swap failed, attempting rollback")
                restoreFromSafetyBackup(safetyBackupFile, liveDbFile, liveDbWalFile, liveDbShmFile)
                restoreMaintenanceMode.exit(forceRestartRequired = false)
                restoreJournal.failJournal(journalEntry, "Swap failed: ${e.message}")
                run.failedFinal("Database swap failed", e)
                tempDir.deleteRecursively()
                return@withContext Result.failure(
                    Exception("Database swap failed and was rolled back: ${e.message}")
                )
            }
            // F6: After live DB swap, do NOT use run handle for Room writes — DB is replaced.
            // All further diagnostics go to the restore journal only.

            // 9. Verify live DB using a FRESH Room instance — the injected singleton is stale after swap
            journalEntry = restoreJournal.transitionTo(journalEntry, RestoreJournal.JournalState.VERIFYING)
            try {
                val freshDb = restoreDatabaseOpener.openFreshDatabase()
                val liveSummary: DatabaseImportSummary
                try {
                    freshDb.openHelper.writableDatabase
                    liveSummary = queryRoomCountsForVerification(freshDb)

                    // Full verification
                    val verificationResult = BackupVerifier.verify(liveDbFile, manifestTableCounts)
                    if (!verificationResult.passed) {
                        throw Exception("Live verification failed: ${verificationResult.errors.joinToString("; ")}")
                    }

                    verifySummaryPreservedForVerification(
                        DatabaseImportSummary(
                            transactionCount = manifestTableCounts["expenses"] ?: 0,
                            categoryCount = manifestTableCounts["categories"] ?: 0,
                            merchantCount = manifestTableCounts["merchant_categories"] ?: 0,
                            pendingReviewCount = manifestTableCounts["pending_reviews"] ?: 0,
                            budgetCount = manifestTableCounts["budgets"] ?: 0,
                            allTableCounts = manifestTableCounts
                        ),
                        liveSummary,
                        manifest.databaseVersion
                    )

                    journalEntry = restoreJournal.transitionTo(
                        journalEntry,
                        RestoreJournal.JournalState.ASSETS_RESTORING
                    )

                    val receiptWarnings = if (tempDir.exists()) {
                        restoreReceiptAssets(tempDir, manifest, freshDb, journalEntry)
                    } else {
                        emptyList()
                    }

                    preRestoreFile.delete()
                    stagedDbFile.delete()
                    stagedDbWalFile.delete()
                    stagedDbShmFile.delete()
                    tempDir.deleteRecursively()

                    restoreJournal.commitJournal(journalEntry)
                    restoreMaintenanceMode.exit(forceRestartRequired = true)

                    Timber.w("Restore completed successfully. Restart required.")
                    // F6: DB was swapped — do NOT call run.success() on old Room handle.
                    // Record completion in journal only.
                    restoreJournal.transitionTo(journalEntry, RestoreJournal.JournalState.COMPLETE)
                    Result.success(
                        DatabaseImportResult.SuccessNeedsRestart(
                            DatabaseImportSummary(
                                transactionCount = liveSummary.transactionCount,
                                categoryCount = liveSummary.categoryCount,
                                merchantCount = liveSummary.merchantCount,
                                pendingReviewCount = liveSummary.pendingReviewCount,
                                budgetCount = liveSummary.budgetCount,
                                receiptCount = liveSummary.receiptCount,
                                warrantyCount = liveSummary.warrantyCount,
                                groupCount = liveSummary.groupCount,
                                subscriptionCount = liveSummary.subscriptionCount,
                                savingsGoalCount = liveSummary.savingsGoalCount,
                                allTableCounts = liveSummary.allTableCounts,
                                receiptAssetWarnings = receiptWarnings
                            )
                        )
                    )
                } finally {
                    runCatching { freshDb.close() }
                }
            } catch (e: Exception) {
                // Verification failed — rollback from safety backup
                Timber.e(e, "Live verification failed, rolling back")
                restoreMaintenanceMode.enter(RestoreMaintenanceMode.Mode.RESTORE_ROLLING_BACK)
                runCatching { database.close() }
                runCatching { database.openHelper.close() }

                val rollbackOk = restoreFromSafetyBackup(
                    safetyBackupFile, liveDbFile, liveDbWalFile, liveDbShmFile
                ).isSuccess

                if (!rollbackOk) {
                    // Critical: both live and safety backup may be corrupt
                    restoreJournal.failJournal(journalEntry, "Verification failed and rollback also failed")
                    restoreMaintenanceMode.enterCriticalRecoveryRequired(
                        "Restore verification failed and safety backup rollback also failed"
                    )
                    tempDir.deleteRecursively()
                    return@withContext Result.failure(
                        Exception("CRITICAL: Restore failed and safety backup rollback also failed. " +
                            "Manual recovery required. Error: ${e.message}")
                    )
                }

                restoreJournal.failJournal(journalEntry, "Verification failed, rolled back: ${e.message}")
                restoreMaintenanceMode.exit(forceRestartRequired = false)
                tempDir.deleteRecursively()

                // F6: DB was swapped — do not use old run handle. Journal is authoritative.
                Result.failure(Exception("Restore verification failed and was rolled back: ${e.message}"))
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to restore .costbackup bundle")
            restoreMaintenanceMode.exit(forceRestartRequired = false)
            // Only finalize run if DB was NOT swapped (run handle may still be valid)
            run.failedFinal(e.message ?: "Exception", e)
            Result.failure(e)
        }
    }

    /**
     * Collects receipt asset files for inclusion in a .costbackup bundle.
     *
     * @return map of relative ZIP path (e.g. "files/receipts/{id}_{name}") → original File
     */
    private suspend fun collectReceiptAssetsForBackup(): Map<String, java.io.File> {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val dao = database.scannedReceiptDao()
                val receiptsWithPath = dao.getAllWithImagePath()
                val manifest = receiptAssetStore.generateBackupManifest(receiptsWithPath)

                val result = mutableMapOf<String, java.io.File>()
                for (entry in manifest) {
                    val relPath = "files/receipts/${entry.receiptId}_${java.io.File(entry.imagePath).name}"
                    val file = java.io.File(entry.imagePath)
                    if (file.exists() && file.isFile) {
                        result[relPath] = file
                    }
                }
                Timber.d("Collected %d receipt asset(s) for backup", result.size)
                result
            } catch (e: Exception) {
                Timber.e(e, "Failed to collect receipt assets for backup")
                emptyMap()
            }
        }
    }

    /**
     * Restores receipt asset files from the extracted bundle and updates
     * the [ScannedReceipt.imagePath] in the database to point to the new location.
     *
     * Backup files are named as "{receiptId}_{originalFilename}", so we parse the
     * receipt ID from the filename to locate the corresponding DB record.
     *
     * ## Idempotency
     * Asset restore is best-effort. If the DB transaction that updates image paths
     * is rolled back, the DB remains consistent — orphan asset files left on disk
     * are cleaned up by the periodic receipt asset cleanup job.
     *
     * @return list of warning messages for any files that could not be restored.
     */
    private suspend fun restoreReceiptAssets(
        assetsDir: java.io.File,
        manifest: CostbackupBundle.BackupManifest,
        db: com.yourname.expensetracker.data.database.AppDatabase,
        journalEntry: RestoreJournal.JournalEntry? = null
    ): List<String> {
        val warnings = mutableListOf<String>()
        val receiptsDir = java.io.File(context.filesDir, "receipts")
        receiptsDir.mkdirs()

        val receiptsSubdir = java.io.File(assetsDir, "receipts")
        if (!receiptsSubdir.exists()) {
            Timber.d("No receipt assets to restore")
            return warnings
        }

        val receiptFiles = receiptsSubdir.listFiles { f -> f.isFile } ?: emptyArray()
        if (receiptFiles.isEmpty()) {
            Timber.d("No receipt asset files in bundle")
            return warnings
        }

        val dao = db.scannedReceiptDao()
        var restoredCount = 0

        for (assetFile in receiptFiles) {
            try {
                // 1. Parse receipt ID first — skip copy if ID is invalid
                val receiptId = assetFile.nameWithoutExtension
                    .substringBefore("_")
                    .toLongOrNull()

                if (receiptId == null || receiptId <= 0L) {
                    val msg = "Could not parse receipt ID from filename: ${assetFile.name}"
                    warnings.add(msg)
                    Timber.w(msg)
                    continue
                }

                // 2. Validate receipt row exists before copying asset
                val receipt = dao.getById(receiptId)
                if (receipt == null) {
                    val msg = "Receipt record not found for restored asset (id=$receiptId, file=${assetFile.name})"
                    warnings.add(msg)
                    Timber.w(msg)
                    continue
                }

                // 3. Copy asset to temp, then update DB, then rename to final
                val extension = assetFile.extension.takeIf { it.isNotBlank() } ?: "jpg"
                val finalFile = java.io.File(receiptsDir, "${UUID.randomUUID()}.$extension")
                val tempFile = java.io.File(receiptsDir, "${finalFile.name}.tmp")
                try {
                    assetFile.inputStream().use { input ->
                        tempFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    restoreInternalWriteScope.run("restoreReceiptAssets.updateImagePath") {
                        dao.update(receipt.copy(imagePath = finalFile.absolutePath))
                    }
                    if (!tempFile.renameTo(finalFile)) {
                        tempFile.copyTo(finalFile, overwrite = true)
                        tempFile.delete()
                    }
                    Timber.d("Restored receipt asset: %s -> %s (receiptId=%d)",
                        assetFile.name, finalFile.absolutePath, receiptId)
                } catch (e: Exception) {
                    runCatching { tempFile.delete() }
                    runCatching { finalFile.delete() }
                    throw e
                }
                restoredCount++
                // Update journal ledger: mark task COMPLETED
                if (journalEntry != null) {
                    val updatedTasks = journalEntry.assetTasks.map { t ->
                        if (t.receiptId == receiptId) t.copy(
                            status = RestoreJournal.AssetRestoreStatus.COMPLETED,
                            targetPath = finalFile.absolutePath
                        ) else t
                    }
                    runCatching { restoreJournal.writeJournal(journalEntry.copy(assetTasks = updatedTasks)) }
                }
            } catch (e: Exception) {
                val msg = "Failed to restore receipt asset: ${assetFile.name} - ${e.message}"
                warnings.add(msg)
                Timber.e(e, "Failed to restore receipt asset: %s", assetFile.name)
                // Update journal ledger: mark task FAILED
                if (journalEntry != null) {
                    val receiptId = assetFile.nameWithoutExtension.substringBefore("_").toLongOrNull()
                    if (receiptId != null) {
                        val updatedTasks = journalEntry.assetTasks.map { t ->
                            if (t.receiptId == receiptId) t.copy(
                                status = RestoreJournal.AssetRestoreStatus.FAILED,
                                error = e.message
                            ) else t
                        }
                        runCatching { restoreJournal.writeJournal(journalEntry.copy(assetTasks = updatedTasks)) }
                    }
                }
            }
        }

        if (warnings.isNotEmpty()) {
            Timber.w(
                "Receipt asset restore complete: %d files restored, %d warnings",
                restoredCount, warnings.size
            )
        } else {
            Timber.d(
                "Receipt asset restore complete: %d files restored, no warnings",
                restoredCount
            )
        }
        return warnings
    }

    /**
     * Imports a legacy `.db` database file, replacing the current live database.
     *
     * ## BAK-N1: Legacy import lacks journal + maintenance mode (planned)
     * Unlike [restoreCostBackup] which uses [RestoreJournal] for crash-safe
     * tracking and [RestoreMaintenanceMode] to block writes during restore,
     * this legacy import path does neither:
     *
     * - **No restore journal**: There is no [RestoreJournal] entry created before
     *   the swap. If the process crashes during file replacement (between
     *   [closeLiveDatabaseForFileSwap] and the final [liveImportVerifier]), the
     *   database ends up in an indeterminate state with no audit trail.
     * - **No maintenance mode**: Writes from other coroutines (auto-categorization,
     *   recurring generation, notification processing) can race with the file swap,
     *   potentially corrupting the live database or leaving WAL/SHM sidecars in an
     *   inconsistent state.
     *
     * ### Backport plan:
     * 1. **Wrap in [RestoreMaintenanceMode]**: Call `enter(RESTORE_PREPARING)`
     *    before the safety backup and `exit()` after verification (or on failure).
     * 2. **Create a [RestoreJournal] entry**: Journal the source path, staged path,
     *    and live DB path. Transition through states (PREPARING → STAGED →
     *    SAFETY_BACKUP_CREATED → SWAPPING → VERIFYING → COMMITTED/FAILED) just
     *    like [restoreCostBackup].
     * 3. **Leverage existing safety backup**: The safety backup is already created
     *    below; the journal just needs to track its path for crash-recovery.
     *
     * Once these two pieces are added, `importDatabase()` becomes as crash-safe
     * as [restoreCostBackup] and the two code paths can potentially share more
     * infrastructure.
     */
    /**
     * ## RSP-R5A: LegacyDatabaseImporter for pre-v6 schemas (planned)
     * Currently [importDatabase] requires a source database at schema version
     * >= [MIN_SUPPORTED_SCHEMA_VERSION] (v6). Databases created by very old app
     * versions (v1–v5) are rejected because the migration chain does not cover
     * those versions (see RSP-R2A).
     *
     * A future `LegacyDatabaseImporter` component should:
     * 1. Open the legacy `.db` file with `SQLiteDatabase.openDatabase()` (bypassing
     *    Room's schema validation).
     * 2. Query the legacy table names and column definitions via `PRAGMA table_info`.
     * 3. Map legacy columns to current [Expense], [Category], etc. fields using a
     *    best-effort mapping (with sensible defaults for columns that did not exist).
     * 4. Insert the mapped data into a freshly-migrated (v115) staging database via
     *    the DAOs (not raw SQL), so all entity validations, defaults, and FK checks
     *    are applied.
     * 5. Log warnings for any data that could not be mapped (e.g. dropped columns).
     *
     * Until this importer is built, the [validateSourceDatabase] step will reject
     * pre-v6 files with a descriptive error message directing the user to export
     * their data first.
     */
    override suspend fun importDatabase(sourceFile: File): Result<DatabaseImportSummary> = withContext(ioDispatcher) {
        // P7-P0-01: Legacy .db import is debug-only. Production restores must use
        // restoreCostBackup() which provides encrypted bundles, manifest verification,
        // checksums, and full journaled state-machine safety.
        if (!BuildConfig.DEBUG) {
            return@withContext Result.failure(
                UnsupportedOperationException("Legacy .db import is disabled in release builds. Use .costbackup restore instead.")
            )
        }

        // Enter maintenance mode + drain workers — blocks all concurrent writes during the swap
        maintenanceOperationRunner.enterAndDrain(RestoreMaintenanceMode.Mode.RESTORE_PREPARING,
        "importDatabase",
        failOnTimeout = true)
        Timber.w("Legacy import: entered maintenance mode, workers drained")
        try {
            // Validate source file exists and is readable
            if (!sourceFile.exists()) {
                restoreMaintenanceMode.exit(forceRestartRequired = false)
                return@withContext Result.failure(Exception("Source database file not found: ${sourceFile.absolutePath}"))
            }
            if (!sourceFile.canRead()) {
                restoreMaintenanceMode.exit(forceRestartRequired = false)
                return@withContext Result.failure(Exception("Cannot read source database file. Check file permissions."))
            }
            if (sourceFile.length() == 0L) {
                restoreMaintenanceMode.exit(forceRestartRequired = false)
                return@withContext Result.failure(Exception("Source database file is empty."))
            }

            // Validate source database before touching anything
            val sourceValidation = validateSourceDatabase(sourceFile)
            if (sourceValidation.isFailure) {
                Timber.e("Source database validation failed: ${sourceValidation.exceptionOrNull()?.message}")
                restoreMaintenanceMode.exit(forceRestartRequired = false)
                return@withContext Result.failure(
                    Exception("Invalid backup file: ${sourceValidation.exceptionOrNull()?.message}")
                )
            }
            val sourceSummary = sourceValidation.getOrNull() ?: run {
                restoreMaintenanceMode.exit(forceRestartRequired = false)
                return@withContext Result.failure(Exception("Failed to read backup summary"))
            }

            // Block empty imports only when all tracked meaningful tables are empty.
            if (!sourceSummary.hasMeaningfulData()) {
                Timber.e(
                    "Source database is empty across tracked tables " +
                        "(expenses=0, categories=0, merchants=0, pendingReviews=0, budgets=0). Blocking import."
                )
                restoreMaintenanceMode.exit(forceRestartRequired = false)
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

            // Create restore journal — crash-safe state tracking identical to restoreCostBackup
            var journalEntry = restoreJournal.beginJournal(
                sourceBackupPath = sourceFile.absolutePath,
                stagedDbPath = stagedDbFile.absolutePath,
                liveDbPath = liveDbFile.absolutePath
            )
            Timber.d("Legacy import journal created: %s", journalEntry.operationId)

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

                journalEntry = restoreJournal.transitionTo(journalEntry, RestoreJournal.JournalState.STAGED)

                val safetyBackupResult = createSafetyBackupInternalAssumingMaintenance("restore")
                if (safetyBackupResult.isFailure) {
                    val reason = safetyBackupResult.exceptionOrNull()?.message ?: "Unknown backup error"
                    Timber.e("Database import aborted: safety backup failed: $reason")
                    restoreJournal.failJournal(journalEntry, "Safety backup failed: $reason")
                    restoreMaintenanceMode.exit(forceRestartRequired = false)
                    return@withContext Result.failure(
                        Exception(
                            "Import cancelled because safety backup failed. " +
                                "Please free storage/permissions and retry. Details: $reason"
                        )
                    )
                }
                safetyBackupFile = safetyBackupResult.getOrNull() ?: run {
                    restoreJournal.failJournal(journalEntry, "Safety backup path unavailable")
                    restoreMaintenanceMode.exit(forceRestartRequired = false)
                    return@withContext Result.failure(Exception("Safety backup was created but path is unavailable"))
                }

                journalEntry = restoreJournal.transitionTo(
                    journalEntry,
                    RestoreJournal.JournalState.SAFETY_BACKUP_CREATED,
                    safetyBackupPath = safetyBackupFile.absolutePath
                )

                journalEntry = restoreJournal.transitionTo(journalEntry, RestoreJournal.JournalState.SWAPPING)
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

                journalEntry = restoreJournal.transitionTo(journalEntry, RestoreJournal.JournalState.VERIFYING)
                val freshDb = restoreDatabaseOpener.openFreshDatabase()
                val finalSummary = try {
                    liveImportVerifier(
                        freshDb,
                        liveDbFile,
                        sourceSummary.schemaVersion,
                        verifiedStagedSummary
                    )
                } finally {
                    runCatching { freshDb.close() }
                }

                Timber.d("Import verified: ${finalSummary.transactionCount} transactions, ${finalSummary.categoryCount} categories")
                importSucceeded = true
                restoreJournal.commitJournal(journalEntry)
                restoreMaintenanceMode.exit(forceRestartRequired = true)

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

                    restoreJournal.failJournal(journalEntry, importError.message ?: "Import failed after swap")
                    if (rollbackResult.isSuccess) {
                        restoreMaintenanceMode.exit(forceRestartRequired = false)
                    } else {
                        restoreMaintenanceMode.enterCriticalRecoveryRequired(
                            "Import failed after swap and rollback also failed"
                        )
                    }

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
                restoreJournal.failJournal(journalEntry, importError.message ?: "Import failed pre-swap")
                restoreMaintenanceMode.exit(forceRestartRequired = false)
                throw importError

            } finally {
                stagedDbFile.delete()
                stagedDbWalFile.delete()
                stagedDbShmFile.delete()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to import database")
            // Ensure maintenance mode is exited even for unexpected failures.
            // Calling exit() when already NORMAL is harmless (idempotent write).
            restoreMaintenanceMode.exit(forceRestartRequired = false)
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

    // Use the domain-level hasMeaningfulData for the updated DatabaseImportSummary,
    // which also checks receiptCount, warrantyCount, groupCount, etc.

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

            // Use fresh Room — injected singleton is stale after DB file copy
            val freshDb = restoreDatabaseOpener.openFreshDatabase()
            try {
                freshDb.openHelper.writableDatabase
                refreshInvalidationTrackerSafelyForVerification(freshDb)
            } finally {
                runCatching { freshDb.close() }
            }
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
        // Public entry: enter maintenance mode and drain workers before copying live DB
        try {
            maintenanceOperationRunner.enterAndDrain(
                RestoreMaintenanceMode.Mode.BACKUP_EXPORTING,
                "createSafetyBackup",
                failOnTimeout = true
            )
            val result = createSafetyBackupInternalAssumingMaintenance("createSafetyBackup")
            restoreMaintenanceMode.exit(forceRestartRequired = false)
            result
        } catch (e: Exception) {
            runCatching { restoreMaintenanceMode.exit(forceRestartRequired = false) }
            Result.failure(e)
        }
    }

    /**
     * Creates a safety backup assuming maintenance mode is already active and workers are drained.
     * Call only from within an already-entered maintenance scope.
     */
    private suspend fun createSafetyBackupInternalAssumingMaintenance(
        callerOperation: String
    ): Result<File> = withContext(ioDispatcher) {
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

            val timestamp = DateTimeFormatter.ofPattern(DATE_FORMAT, Locale.US).format(LocalDateTime.now(ZoneId.systemDefault()))
            val safetyBackupDir = File(context.filesDir, "safety_backups").apply { mkdirs() }
            val safetyBackupFile = File(safetyBackupDir, "${BACKUP_PREFIX}SAFETY_${timestamp}.db")

            dbFile.inputStream().use { input ->
                safetyBackupFile.outputStream().use { output -> input.copyTo(output) }
            }

            cleanupOldSafetyBackups(safetyBackupDir)
            Timber.d("Safety backup created by $callerOperation: ${safetyBackupFile.absolutePath}")
            Result.success(safetyBackupFile)
        } catch (e: Exception) {
            Timber.e(e, "Failed to create safety backup")
            Result.failure(e)
        }
    }
    
    override suspend fun resetDatabase(): Result<Unit> = withContext(ioDispatcher) {
        try {
            // Enter maintenance mode + drain workers — blocks all writes for the duration of the reset
            maintenanceOperationRunner.enterAndDrain(RestoreMaintenanceMode.Mode.RESETTING_DATABASE,
            "resetDatabase",
            failOnTimeout = true)
            Timber.w("Reset: entered RESETTING_DATABASE maintenance mode, workers drained")

            val liveDbFile = context.getDatabasePath(AppDatabase.DATABASE_NAME)

            // Create safety backup before touching anything
            val safetyBackupResult = createSafetyBackupInternalAssumingMaintenance("restore")
            if (safetyBackupResult.isFailure) {
                val reason = safetyBackupResult.exceptionOrNull()?.message ?: "Unknown backup error"
                Timber.e("Database reset aborted: safety backup failed: $reason")
                restoreMaintenanceMode.exit(forceRestartRequired = false)
                return@withContext Result.failure(
                    Exception(
                        "Reset cancelled because safety backup failed. " +
                            "Please free storage/permissions and retry. Details: $reason"
                    )
                )
            }

            // Journal the operation for crash-safe tracking
            val journalEntry = restoreJournal.beginJournal(
                sourceBackupPath = safetyBackupResult.getOrNull()?.absolutePath ?: "",
                stagedDbPath = "",
                liveDbPath = liveDbFile.absolutePath
            )

            try {
                closeLiveDatabaseForFileSwap()

                val dbWalFile = File(liveDbFile.parent, "${AppDatabase.DATABASE_NAME}-wal")
                val dbShmFile = File(liveDbFile.parent, "${AppDatabase.DATABASE_NAME}-shm")

                liveDbFile.delete()
                dbWalFile.delete()
                dbShmFile.delete()

                restoreJournal.commitJournal(journalEntry)

                // Reset requires restart — Room singleton is stale after DB deletion
                restoreMaintenanceMode.exit(forceRestartRequired = true)

                Timber.w("Database reset successfully. Restart required.")
                Result.success(Unit)
            } catch (e: Exception) {
                restoreJournal.failJournal(journalEntry, e.message ?: "Reset failed")
                restoreMaintenanceMode.exit(forceRestartRequired = true)
                Timber.e(e, "Database reset failed")
                Result.failure(e)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to reset database")
            restoreMaintenanceMode.exit(forceRestartRequired = false)
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

    /**
     * P7-P1-03: Uses TRUNCATE mode which holds the write lock for the duration,
     * preventing any WAL frames from being written between checkpoint and file copy.
     * This guarantees the copied .db file is a self-contained point-in-time snapshot.
     *
     * TODO (P2-29): Consider using the SQLite Online Backup API
     *   (sqlite3_backup_init/step/finish) for zero-copy hot snapshots without
     *   needing to close or lock the database. This would eliminate the retry loop
     *   and the brief write-blocking window.
     */
    private suspend fun checkpointWal(): Result<Unit> {
        val maxAttempts = 3
        repeat(maxAttempts) { attempt ->
            try {
                val busy = database.openHelper.writableDatabase
                    .query("PRAGMA wal_checkpoint(TRUNCATE)")
                    .use { cursor ->
                        if (cursor.moveToFirst()) cursor.getInt(0) else 1
                    }

                if (busy == 0) {
                    Timber.d("WAL checkpoint (TRUNCATE) completed (attempt ${attempt + 1})")
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
