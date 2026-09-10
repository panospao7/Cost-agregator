package com.yourname.expensetracker.ui.screens.backup

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.backup.CostbackupBundle
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.domain.backup.DatabaseBackupRepository
import com.yourname.expensetracker.domain.backup.DatabaseImportResult
import com.yourname.expensetracker.domain.util.TimeProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.io.InputStream
import javax.inject.Inject

/**
 * UI state for the Backup & Restore screen.
 */
data class BackupRestoreUiState(
    val isBackingUp: Boolean = false,
    val isRestoring: Boolean = false,
    val lastBackupDate: String? = null,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val restartRequired: Boolean = false
)

@HiltViewModel
class BackupRestoreViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val databaseBackupRepository: DatabaseBackupRepository,
    private val restoreMaintenanceMode: RestoreMaintenanceMode,
    private val timeProvider: TimeProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(BackupRestoreUiState())
    val uiState: StateFlow<BackupRestoreUiState> = _uiState.asStateFlow()

    init {
        loadLastBackupInfo()
    }

    /**
     * Loads last backup timestamp from the maintenance mode prefs or database stats.
     */
    private fun loadLastBackupInfo() {
        viewModelScope.launch {
            try {
                val stats = databaseBackupRepository.getDatabaseStats()
                val lastBackup = stats.lastBackupDate
                if (lastBackup != null && lastBackup > 0L) {
                    val formatted = java.time.format.DateTimeFormatter.ofPattern(
                        "yyyy-MM-dd HH:mm",
                        java.util.Locale.getDefault()
                    ).format(java.time.Instant.ofEpochMilli(lastBackup).atZone(java.time.ZoneId.systemDefault()))
                    _uiState.value = _uiState.value.copy(lastBackupDate = formatted)
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to load backup info")
            }
        }
    }

    /**
     * Creates a .costbackup bundle with the given password.
     */
    fun createBackup(password: String) {
        if (password.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Password cannot be empty")
            return
        }
        // S3-016: Prevent duplicate operations
        if (_uiState.value.isBackingUp || _uiState.value.isRestoring) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isBackingUp = true,
                errorMessage = null,
                successMessage = null
            )

            val result = databaseBackupRepository.createCostBackup(password)

            result.fold(
                onSuccess = { file ->
                    Timber.d("Backup created successfully")
                    _uiState.value = _uiState.value.copy(
                        isBackingUp = false,
                        successMessage = "Backup created successfully: ${file.name}",
                        lastBackupDate = java.time.format.DateTimeFormatter.ofPattern(
                            "yyyy-MM-dd HH:mm",
                            java.util.Locale.getDefault()
                        ).format(
                            // G-TIME-01: derive the display timestamp from the injected TimeProvider.
                            java.time.Instant.ofEpochMilli(timeProvider.now()).atZone(java.time.ZoneId.systemDefault())
                        )
                    )
                },
                onFailure = { error ->
                    Timber.e(error, "BACKUP_CREATE_FAILED")
                    val message = when {
                        error is com.yourname.expensetracker.data.backup.CostbackupBundle.WrongBackupPasswordException ->
                            "Encryption error: Incorrect password or corrupt backup file"
                        error.message?.contains("denied", ignoreCase = true) == true ->
                            "Backup denied by privacy settings"
                        else -> "Backup failed. Please try again."
                    }
                    _uiState.value = _uiState.value.copy(
                        isBackingUp = false,
                        errorMessage = message
                    )
                }
            )
        }
    }

    /**
     * Restores a .costbackup bundle from the given URI.
     *
     * P7-CURRENT-017: Before copying the (potentially huge) URI to a temp file we
     * preflight it: reject files larger than [MAX_BACKUP_BUNDLE_BYTES] using the
     * ContentResolver size metadata when available, and validate the COSTBACKUP
     * header magic from the first [CostbackupBundle.HEADER_SIZE] bytes. The copy
     * itself is then size-bounded so a provider that lies about (or omits) its
     * size cannot fill the cache. Only after a clean copy do we call
     * [DatabaseBackupRepository.restoreCostBackup].
     */
    fun restoreBackup(uri: Uri, password: String) {
        if (password.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Password cannot be empty")
            return
        }
        // S3-016: Prevent duplicate operations
        if (_uiState.value.isRestoring || _uiState.value.isBackingUp) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isRestoring = true,
                errorMessage = null,
                successMessage = null,
                restartRequired = false
            )

            // P7-CURRENT-017: cheap size precheck from provider metadata before opening a stream.
            val declaredSize = queryUriSize(uri)
            if (declaredSize != null && declaredSize > MAX_BACKUP_BUNDLE_BYTES) {
                _uiState.value = _uiState.value.copy(
                    isRestoring = false,
                    errorMessage = "Backup file is too large (max ${MAX_BACKUP_BUNDLE_BYTES / (1024 * 1024)} MB)"
                )
                return@launch
            }

            val tempFile = File.createTempFile("restore_", ".costbackup", context.cacheDir)
            val copyResult = runCatching {
                val input = context.contentResolver.openInputStream(uri)
                    ?: error("Could not open selected backup file")
                input.use { src -> copyBackupWithPreflight(src, tempFile) }
            }
            copyResult.getOrElse { error ->
                tempFile.delete()
                val message = when (error) {
                    is CostbackupBundle.InvalidBackupFormatException ->
                        "Not a valid .costbackup file"
                    is CostbackupBundle.UnsupportedBackupVersionException ->
                        "Unsupported backup version"
                    is CostbackupBundle.BackupTooLargeException ->
                        "Backup file is too large (max ${MAX_BACKUP_BUNDLE_BYTES / (1024 * 1024)} MB)"
                    else -> "Failed to read backup file"
                }
                _uiState.value = _uiState.value.copy(isRestoring = false, errorMessage = message)
                return@launch
            }

            // S3-006: try/finally ensures isRestoring resets and temp file is deleted even on throw
            val result = try {
                databaseBackupRepository.restoreCostBackup(tempFile, password)
            } catch (t: Throwable) {
                Result.failure(t)
            } finally {
                tempFile.delete()
            }

            result.fold(
                onSuccess = { importResult ->
                    Timber.d("Restore completed: %s", importResult)
                    when (importResult) {
                        is DatabaseImportResult.SuccessNeedsRestart ->
                            _uiState.value = _uiState.value.copy(isRestoring = false, successMessage = "Restore completed successfully!", restartRequired = true)
                        is DatabaseImportResult.Success ->
                            _uiState.value = _uiState.value.copy(isRestoring = false, successMessage = "Restore completed successfully!")
                        is DatabaseImportResult.Loading ->
                            _uiState.value = _uiState.value.copy(isRestoring = false)
                        is DatabaseImportResult.Error ->
                            _uiState.value = _uiState.value.copy(isRestoring = false, errorMessage = importResult.message)
                    }
                },
                onFailure = { error ->
                    Timber.e(error, "RESTORE_FAILED")
                    val message = when {
                        error is com.yourname.expensetracker.data.backup.CostbackupBundle.WrongBackupPasswordException ->
                            "Incorrect password or corrupt backup file"
                        // P7-CURRENT-023: zip-bomb / oversized-bundle rejection from the
                        // extract phase (the preflight copy maps this separately above).
                        error is com.yourname.expensetracker.data.backup.CostbackupBundle.BackupTooLargeException ->
                            "Backup file is too large or contains too many entries"
                        error.message?.contains("password", ignoreCase = true) == true ->
                            "Incorrect password or corrupt backup file"
                        error.message?.contains("denied", ignoreCase = true) == true ->
                            "Restore denied by privacy settings"
                        else -> "Restore failed. Please try again."
                    }
                    _uiState.value = _uiState.value.copy(isRestoring = false, errorMessage = message)
                }
            )
        }
    }

    /**
     * Clears the current error message.
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    /**
     * Clears the current success message.
     */
    fun clearSuccess() {
        _uiState.value = _uiState.value.copy(successMessage = null)
    }

    /**
     * P7-P1-08: Dismisses the restart-required banner and unblocks writes.
     *
     * After a successful restore the app enters
     * [RestoreMaintenanceMode.Mode.RESTORE_COMPLETE_RESTART_REQUIRED] which blocks all
     * database writes. The user can dismiss this warning to resume normal operation
     * (the restored database is already in place). This call:
     * 1. Clears the screen-local `restartRequired` banner flag.
     * 2. Exits maintenance mode to [RestoreMaintenanceMode.Mode.NORMAL], re-enabling
     *    writes and rescheduling background workers.
     *
     * Note: the global app-shell lock ([com.yourname.expensetracker.ui.MainActivity]
     * observing `operationalStateFlow`) is also cleared by the exit call, so the full-
     * screen restart barrier is dismissed as well.
     */
    fun dismissRestartRequired() {
        _uiState.value = _uiState.value.copy(restartRequired = false)
        restoreMaintenanceMode.exit(forceRestartRequired = false)
    }

    /**
     * P7-CURRENT-017: Returns the provider-declared size of [uri] in bytes via
     * [OpenableColumns.SIZE], or null when the provider does not report a size.
     * Used as a cheap pre-copy reject; the bounded copy is the authoritative guard.
     */
    private fun queryUriSize(uri: Uri): Long? {
        return try {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return null
                    val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0 && !cursor.isNull(idx)) cursor.getLong(idx) else null
                }
        } catch (e: Exception) {
            Timber.w(e, "Failed to query backup URI size")
            null
        }
    }

    /**
     * P7-CURRENT-017: Validates the COSTBACKUP header from the first
     * [CostbackupBundle.HEADER_SIZE] bytes BEFORE copying the body, then streams
     * the rest into [dest] with a hard [MAX_BACKUP_BUNDLE_BYTES] cap so a provider
     * that under-reports (or omits) its size cannot fill the cache.
     *
     * @throws CostbackupBundle.InvalidBackupFormatException bad/short magic
     * @throws CostbackupBundle.UnsupportedBackupVersionException unknown format version
     * @throws CostbackupBundle.BackupTooLargeException copy exceeds the size cap
     */
    @VisibleForTesting
    internal fun copyBackupWithPreflight(source: InputStream, dest: File) {
        copyBackupWithPreflight(source, dest, MAX_BACKUP_BUNDLE_BYTES)
    }

    /** Size-cap-injectable variant for tests (see [copyBackupWithPreflight]). */
    @VisibleForTesting
    internal fun copyBackupWithPreflight(source: InputStream, dest: File, maxBytes: Long) {
        // 1. Read + validate the fixed-size header (magic + format version).
        val header = ByteArray(CostbackupBundle.HEADER_SIZE)
        var off = 0
        while (off < header.size) {
            val n = source.read(header, off, header.size - off)
            if (n == -1) {
                throw CostbackupBundle.InvalidBackupFormatException("File too short: missing header")
            }
            off += n
        }
        CostbackupBundle.readHeader(header) // throws on bad magic / unsupported version

        // 2. Write header + bounded body to the temp file.
        dest.outputStream().use { out ->
            out.write(header)
            var total = header.size.toLong()
            val buf = ByteArray(8192)
            while (true) {
                val n = source.read(buf)
                if (n == -1) break
                total += n
                if (total > maxBytes) {
                    throw CostbackupBundle.BackupTooLargeException(
                        "Backup exceeds max size ($maxBytes bytes)"
                    )
                }
                out.write(buf, 0, n)
            }
        }
    }

    companion object {
        /**
         * P7-CURRENT-017: hard cap on a restorable .costbackup bundle (500 MB).
         * Larger files are rejected before/while copying to avoid filling the cache.
         */
        const val MAX_BACKUP_BUNDLE_BYTES: Long = 500L * 1024 * 1024
    }
}
